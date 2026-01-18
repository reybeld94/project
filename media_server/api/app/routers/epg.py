from datetime import datetime, timezone, timedelta
import threading
from app.models import VodStream, SeriesItem
from app.tmdb_client import _clean_title_and_year

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from sqlalchemy import select, delete
from sqlalchemy import func
import os, asyncio, logging
from sqlalchemy import select
from app.db import SessionLocal
from app.models import EpgSource
from app.epg_match import best_match
from app.deps import get_db
from app.schemas import EpgSourceCreate
from app.models import Provider, LiveStream, Category, EpgSource, EpgChannel, EpgProgram
from app.xmltv import download_xmltv_to_file, iter_xmltv, parse_xmltv_datetime

log = logging.getLogger("mini_media_server")
_SYNC_LOCK = threading.Lock()

EPG_AUTO_SYNC = os.getenv("EPG_AUTO_SYNC", "1").strip().lower() not in {"0","false","no","off"}
EPG_AUTO_SYNC_MINUTES = int(os.getenv("EPG_AUTO_SYNC_MINUTES", "60"))
EPG_AUTO_SYNC_HOURS = int(os.getenv("EPG_AUTO_SYNC_HOURS", "36"))
EPG_ENRICH_MISSING_DESC = os.getenv("EPG_ENRICH_MISSING_DESC", "1").strip().lower() not in {"0","false","no","off"}
EPG_ENRICH_MAX_DESC_LEN = int(os.getenv("EPG_ENRICH_MAX_DESC_LEN", "1900"))


router = APIRouter(prefix="/epg", tags=["epg"])
def _title_key_for_library_match(raw: str) -> str:
    cleaned, _year = _clean_title_and_year(raw or "")
    return (cleaned or "").strip().casefold()

def _build_library_desc_map(db: Session) -> dict[str, str]:
    out: dict[str, str] = {}

    def add_keys(name: str | None, normalized: str | None, tmdb_title: str | None, overview: str | None):
        ov = (overview or "").strip()
        if not ov:
            return
        ov = ov[:EPG_ENRICH_MAX_DESC_LEN]

        # Probamos varias “llaves” para matchear
        for s in (normalized, tmdb_title, name):
            k = _title_key_for_library_match(s or "")
            if k and k not in out:
                out[k] = ov

    # Movies
    mrows = db.execute(
        select(VodStream.name, VodStream.normalized_name, VodStream.tmdb_title, VodStream.tmdb_overview)
        .where(VodStream.tmdb_overview != None)
    ).all()
    for name, normalized, tmdb_title, overview in mrows:
        add_keys(name, normalized, tmdb_title, overview)

    # Series
    srows = db.execute(
        select(SeriesItem.name, SeriesItem.normalized_name, SeriesItem.tmdb_title, SeriesItem.tmdb_overview)
        .where(SeriesItem.tmdb_overview != None)
    ).all()
    for name, normalized, tmdb_title, overview in srows:
        add_keys(name, normalized, tmdb_title, overview)

    return out


def sync_epg_for_source_id(
    db: Session,
    source_id: str,
    hours: int = 36,
    purge_all_programs: bool = True,
    auto_map_provider_id: str | None = None,
    auto_map_approved_only: bool = True,
    auto_map_min_score: float = 0.72,
):
    """
    Core sync que puede llamarse desde el endpoint o desde un job automático.

    Args:
        auto_map_provider_id: Si se especifica, ejecuta automapeo para este provider después de sincronizar
        auto_map_approved_only: Si es True, solo automapea canales aprobados
        auto_map_min_score: Score mínimo para el automapeo (default 0.72)
    """
    src = db.get(EpgSource, source_id)
    if not src:
        raise HTTPException(status_code=404, detail="EPG source not found")

    url = src.xmltv_url

    try:
        path = download_xmltv_to_file(url)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"XMLTV download failed: {e}")

    now = datetime.now(timezone.utc)
    window_start = now - timedelta(hours=6)
    window_end = now + timedelta(hours=max(1, min(hours, 168)))  # max 7 días

    # Cache de channels en memoria (xmltv_id -> EpgChannel)
    channel_map: dict[str, EpgChannel] = {}

    existing_channels = db.execute(
        select(EpgChannel).where(EpgChannel.epg_source_id == src.id)
    ).scalars().all()
    for c in existing_channels:
        channel_map[c.xmltv_id] = c

    new_channels = 0
    up_channels = 0
    new_programs = 0
    purged_programs = 0

    try:
        with _SYNC_LOCK:
            # ✅ Lo que tú quieres: no mezclar jamás. Borramos TODO lo viejo de esta fuente.
            if purge_all_programs:
                res = db.execute(delete(EpgProgram).where(EpgProgram.epg_source_id == src.id))
                purged_programs = int(getattr(res, "rowcount", 0) or 0)

            # Para evitar violación del unique (channel_id, start_time) si el XML viene con duplicados raros
            seen_prog_keys: set[tuple[str, str]] = set()

            library_desc = _build_library_desc_map(db) if EPG_ENRICH_MISSING_DESC else {}

            for kind, elem in iter_xmltv(path):
                if kind == "channel":
                    xml_id = elem.get("id") or ""
                    if not xml_id:
                        continue

                    names = elem.findall("display-name")
                    display = (names[0].text if names and names[0].text else xml_id).strip()

                    icon = elem.find("icon")
                    icon_url = icon.get("src") if icon is not None else None

                    existing = channel_map.get(xml_id)
                    if existing:
                        if existing.display_name != display or existing.icon_url != icon_url:
                            existing.display_name = display
                            existing.icon_url = icon_url
                            existing.updated_at = datetime.utcnow()
                            up_channels += 1
                    else:
                        ch = EpgChannel(
                            epg_source_id=src.id,
                            xmltv_id=xml_id,
                            display_name=display,
                            icon_url=icon_url,
                            updated_at=datetime.utcnow(),
                        )
                        db.add(ch)
                        db.flush()
                        channel_map[xml_id] = ch
                        new_channels += 1

                elif kind == "programme":
                    xml_id = elem.get("channel") or ""
                    if not xml_id:
                        continue

                    start_s = elem.get("start") or ""
                    stop_s = elem.get("stop") or ""

                    try:
                        start = parse_xmltv_datetime(start_s)
                        stop = parse_xmltv_datetime(stop_s) if stop_s else None
                    except Exception:
                        continue

                    if not stop or stop <= start:
                        continue

                    # ventana
                    if stop <= window_start or start >= window_end:
                        continue

                    title_el = elem.find("title")
                    desc_el = elem.find("desc")
                    cat_el = elem.find("category")

                    title = (title_el.text if title_el is not None and title_el.text else "Untitled").strip()
                    desc = (desc_el.text if desc_el is not None and desc_el.text else None)
                    cat = (cat_el.text if cat_el is not None and cat_el.text else None)

                    ch = channel_map.get(xml_id)

                    # Si el XML no trae desc, intenta enriquecer desde tu librería local
                    if EPG_ENRICH_MISSING_DESC and (desc is None or not str(desc).strip()):
                        ktitle = _title_key_for_library_match(title)
                        found = library_desc.get(ktitle)
                        if found:
                            desc = found

                    if not ch:
                        ch = EpgChannel(
                            epg_source_id=src.id,
                            xmltv_id=xml_id,
                            display_name=xml_id,
                            icon_url=None,
                            updated_at=datetime.utcnow(),
                        )
                        db.add(ch)
                        db.flush()
                        channel_map[xml_id] = ch
                        new_channels += 1

                    k = (str(ch.id), start.isoformat())
                    if k in seen_prog_keys:
                        continue
                    seen_prog_keys.add(k)

                    # ✅ Como ya purgamos, solo insertamos. Nada de “mezclas”.
                    db.add(EpgProgram(
                        epg_source_id=src.id,
                        channel_id=ch.id,
                        start_time=start,
                        end_time=stop,
                        title=title,
                        description=desc,
                        category=cat,
                    ))
                    new_programs += 1

            src.updated_at = datetime.utcnow()
            db.commit()

        result = {
            "ok": True,
            "source_id": source_id,
            "xmltv_url": url,
            "window": {"start": window_start.isoformat(), "end": window_end.isoformat()},
            "purged_programs": purged_programs,
            "channels": {"new": new_channels, "updated": up_channels},
            "programs": {"new": new_programs},
            "auto_map": None,
        }

        # Si se especifica provider_id, ejecutar automapeo después de sincronizar
        if auto_map_provider_id:
            try:
                p = db.get(Provider, auto_map_provider_id)
                if p:
                    log.info(f"Ejecutando automapeo para provider {auto_map_provider_id} (approved_only={auto_map_approved_only})")

                    epg_channels = db.execute(
                        select(EpgChannel.xmltv_id, EpgChannel.display_name)
                        .where(EpgChannel.epg_source_id == src.id)
                    ).all()
                    candidates = [(a, b) for (a, b) in epg_channels]

                    stmt = select(LiveStream).where(LiveStream.provider_id == p.id)
                    if auto_map_approved_only:
                        stmt = stmt.where(LiveStream.approved == True)

                    streams = db.execute(stmt.order_by(LiveStream.name.asc()).limit(5000)).scalars().all()

                    matched = 0
                    changed = 0

                    for s in streams:
                        if s.epg_source_id and s.epg_source_id != src.id:
                            continue

                        if s.epg_channel_id and s.epg_source_id == src.id:
                            continue

                        name_for_match = (s.normalized_name or s.name or "").strip()
                        if not name_for_match:
                            continue

                        m = best_match(name_for_match, candidates, min_score=auto_map_min_score)
                        if not m:
                            continue

                        xml_id, disp, sc = m
                        matched += 1

                        s.epg_source_id = src.id
                        s.epg_channel_id = xml_id
                        changed += 1

                    db.commit()

                    result["auto_map"] = {
                        "executed": True,
                        "provider_id": auto_map_provider_id,
                        "approved_only": auto_map_approved_only,
                        "min_score": auto_map_min_score,
                        "matched": matched,
                        "updated": changed,
                        "total_streams_processed": len(streams),
                    }
                    log.info(f"Automapeo completado: {matched} matches, {changed} actualizados")
            except Exception as e:
                log.error(f"Error en automapeo: {e}")
                result["auto_map"] = {"executed": False, "error": str(e)}

        return result
    finally:
        try:
            import os
            os.remove(path)
        except Exception:
            pass


@router.get("/sources")
def list_sources(db: Session = Depends(get_db)):
    rows = db.execute(select(EpgSource).order_by(EpgSource.name.asc())).scalars().all()
    return {"ok": True, "items": [
        {"id": str(x.id), "name": x.name, "xmltv_url": x.xmltv_url, "is_active": x.is_active}
        for x in rows
    ]}

@router.post("/sources")
def create_source(payload: EpgSourceCreate, db: Session = Depends(get_db)):
    s = EpgSource(
        name=payload.name.strip(),
        xmltv_url=str(payload.xmltv_url).strip(),
        is_active=True,
        updated_at=datetime.utcnow(),
    )
    db.add(s)
    db.commit()
    db.refresh(s)
    return {"ok": True, "id": str(s.id)}

@router.get("/channels")
def list_epg_channels(
    source_id: str,
    q: str | None = None,
    limit: int = 200,
    offset: int = 0,
    db: Session = Depends(get_db),
):
    """
    Lista/search de canales XMLTV dentro de una fuente.
    """
    src = db.get(EpgSource, source_id)

    if not src:
        raise HTTPException(status_code=404, detail="EPG source not found")

    stmt = select(EpgChannel).where(EpgChannel.epg_source_id == src.id)

    if q:
        qq = f"%{q.strip()}%"
        stmt = stmt.where(
            (EpgChannel.display_name.ilike(qq)) | (EpgChannel.xmltv_id.ilike(qq))
        )

    total = db.execute(select(func.count()).select_from(stmt.subquery())).scalar_one()

    rows = db.execute(
        stmt.order_by(EpgChannel.display_name.asc())
        .limit(min(limit, 500))
        .offset(max(0, offset))
    ).scalars().all()

    return {
        "ok": True,
        "total": int(total),
        "items": [
            {
                "id": str(x.id),
                "xmltv_id": x.xmltv_id,
                "display_name": x.display_name,
                "icon_url": x.icon_url,
                "epg_source_id": str(x.epg_source_id) if x.epg_source_id else None,
            }
            for x in rows
        ],
    }


@router.post("/sync")
def sync_epg(
    source_id: str,
    hours: int = 36,
    purge_all_programs: bool = True,
    auto_map_provider_id: str | None = None,
    auto_map_approved_only: bool = True,
    auto_map_min_score: float = 0.72,
    db: Session = Depends(get_db),
):
    """
    Endpoint de sync manual. Por defecto purga TODO lo viejo para nunca mezclar.

    Args:
        auto_map_provider_id: Si se especifica, ejecuta automapeo para este provider después de sincronizar
        auto_map_approved_only: Si es True, solo automapea canales aprobados (default: True)
        auto_map_min_score: Score mínimo para el automapeo (default: 0.72)
    """
    return sync_epg_for_source_id(
        db,
        source_id=source_id,
        hours=hours,
        purge_all_programs=purge_all_programs,
        auto_map_provider_id=auto_map_provider_id,
        auto_map_approved_only=auto_map_approved_only,
        auto_map_min_score=auto_map_min_score,
    )



@router.get("/now")
def epg_now(provider_id: str, live_ids: str, db: Session = Depends(get_db)):
    """
    live_ids: CSV de UUIDs de LiveStream.
    Usa live_stream.epg_source_id + live_stream.epg_channel_id (xmltv_id).
    """
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    ids = [x.strip() for x in live_ids.split(",") if x.strip()]
    if not ids:
        raise HTTPException(status_code=400, detail="live_ids required")

    streams = db.execute(
        select(LiveStream).where(LiveStream.provider_id == p.id, LiveStream.id.in_(ids))
    ).scalars().all()

    now = datetime.now(timezone.utc)

    results = []
    for s in streams:
        src_id = s.epg_source_id
        xml_id = (s.epg_channel_id or "").strip()

        if not src_id or not xml_id:
            results.append({"live_id": str(s.id), "name": s.name, "epg": None})
            continue

        ch = db.execute(
            select(EpgChannel).where(
                EpgChannel.epg_source_id == src_id,
                EpgChannel.xmltv_id == xml_id,
            )
        ).scalar_one_or_none()

        if not ch:
            results.append({"live_id": str(s.id), "name": s.name, "epg": None})
            continue

        prog = db.execute(
            select(EpgProgram).where(
                EpgProgram.channel_id == ch.id,
                EpgProgram.start_time <= now,
                EpgProgram.end_time > now,
            ).order_by(EpgProgram.start_time.desc())
        ).scalar_one_or_none()

        if not prog:
            results.append({"live_id": str(s.id), "name": s.name, "epg": None})
            continue

        results.append({
            "live_id": str(s.id),
            "name": s.name,
            "epg": {
                "title": prog.title,
                "start": prog.start_time.isoformat(),
                "end": prog.end_time.isoformat(),
                "description": prog.description,
                "category": prog.category,
                "channel_display": ch.display_name,
                "xmltv_id": ch.xmltv_id,
                "epg_source_id": str(src_id),
            }
        })

    return {"ok": True, "count": len(results), "items": results}



@router.get("/unmapped")
def epg_unmapped(provider_id: str, limit: int = 200, db: Session = Depends(get_db)):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    rows = db.execute(
        select(LiveStream)
        .where(LiveStream.provider_id == p.id)
        .where((LiveStream.epg_source_id == None) | (LiveStream.epg_channel_id == None) | (LiveStream.epg_channel_id == ""))
        .order_by(LiveStream.name.asc())
        .limit(min(limit, 500))
    ).scalars().all()

    return {
        "ok": True,
        "count": len(rows),
        "items": [{"live_id": str(x.id), "name": x.name} for x in rows],
    }


@router.post("/auto_map")
def epg_auto_map(
    provider_id: str,
    source_id: str,
    min_score: float = 0.72,
    dry_run: bool = True,
    approved_only: bool = False,
    limit: int = 5000,
    db: Session = Depends(get_db),
):
    """
    Auto-mapea LiveStream -> EpgChannel por nombre usando UNA fuente XMLTV.
    Guarda:
      - live_stream.epg_source_id = source_id
      - live_stream.epg_channel_id = epg_channel.xmltv_id

    Args:
        approved_only: Si es True, solo automapea canales aprobados (approved=True)
    """
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    src = db.get(EpgSource, source_id)
    if not src:
        raise HTTPException(status_code=404, detail="EPG source not found")

    epg_channels = db.execute(
        select(EpgChannel.xmltv_id, EpgChannel.display_name)
        .where(EpgChannel.epg_source_id == src.id)
    ).all()
    candidates = [(a, b) for (a, b) in epg_channels]

    stmt = select(LiveStream).where(LiveStream.provider_id == p.id)

    if approved_only:
        stmt = stmt.where(LiveStream.approved == True)

    streams = db.execute(
        stmt.order_by(LiveStream.name.asc())
        .limit(min(limit, 20000))
    ).scalars().all()

    matched = 0
    changed = 0
    skipped_other_source = 0
    sample = []

    for s in streams:
        if s.epg_source_id and s.epg_source_id != src.id:
            skipped_other_source += 1
            continue

        if s.epg_channel_id and s.epg_source_id == src.id:
            continue

        name_for_match = (s.normalized_name or s.name or "").strip()
        if not name_for_match:
            continue

        m = best_match(name_for_match, candidates, min_score=min_score)
        if not m:
            continue

        xml_id, disp, sc = m
        matched += 1

        if not dry_run:
            s.epg_source_id = src.id
            s.epg_channel_id = xml_id
            changed += 1

        if len(sample) < 25:
            sample.append({
                "live_id": str(s.id),
                "live_name": s.name,
                "normalized_name": s.normalized_name,
                "xmltv_id": xml_id,
                "epg_name": disp,
                "score": round(sc, 4),
            })

    if not dry_run:
        db.commit()

    return {
        "ok": True,
        "provider_id": provider_id,
        "source_id": source_id,
        "source_name": src.name,
        "dry_run": dry_run,
        "approved_only": approved_only,
        "min_score": min_score,
        "matched_candidates": matched,
        "updated": changed,
        "skipped_other_source": skipped_other_source,
        "total_streams_processed": len(streams),
        "sample": sample,
    }


@router.post("/auto_map_approved")
def epg_auto_map_approved(
    provider_id: str,
    source_id: str,
    min_score: float = 0.72,
    dry_run: bool = True,
    db: Session = Depends(get_db),
):
    """
    Endpoint conveniente para automapear SOLO canales aprobados.
    Es equivalente a llamar /auto_map con approved_only=True.

    Útil para cuando sincronizas el XML y quieres automapear solo
    los canales que ya has aprobado, permitiendo que el usuario
    todavía pueda verificar y modificar el mapeo manualmente.
    """
    return epg_auto_map(
        provider_id=provider_id,
        source_id=source_id,
        min_score=min_score,
        dry_run=dry_run,
        approved_only=True,  # Siempre True para este endpoint
        limit=5000,
        db=db,
    )


@router.get("/grid")
def epg_grid(
    provider_id: str,
    category_ext_id: int | None = None,
    hours: int = 8,
    limit_channels: int = 50,
    offset_channels: int = 0,
    approved_only: bool = True,
    db: Session = Depends(get_db),
):

    """
    Devuelve canales + programas en ventana [now, now+hours]
    """
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    now = datetime.now(timezone.utc)
    end = now + timedelta(hours=max(1, min(hours, 24)))

    # canales a mostrar
    stmt = select(LiveStream).where(
        LiveStream.provider_id == p.id,
        LiveStream.is_active == True,
    )

    if approved_only:
        stmt = stmt.where(LiveStream.approved == True)

    if category_ext_id is not None:
        cat = db.execute(
            select(Category).where(
                Category.provider_id == p.id,
                Category.cat_type == "live",
                Category.provider_category_id == category_ext_id,
            )
        ).scalar_one_or_none()
        if not cat:
            raise HTTPException(status_code=404, detail="Category not found")
        stmt = stmt.where(LiveStream.category_id == cat.id)

    streams = db.execute(
        stmt.order_by(LiveStream.name.asc())
        .limit(min(limit_channels, 200))
        .offset(offset_channels)
    ).scalars().all()

    # Pre-carga channels xmltv
    # Pre-carga channels xmltv por fuente (epg_source_id + xmltv_id)
    epg_ch = {}  # key: (source_uuid, xmltv_id) -> EpgChannel
    xml_by_source = {}

    for s in streams:
        if s.epg_source_id and s.epg_channel_id:
            xml = (s.epg_channel_id or "").strip()
            if not xml:
                continue
            xml_by_source.setdefault(s.epg_source_id, set()).add(xml)

    for src_id, xml_ids in xml_by_source.items():
        rows = db.execute(
            select(EpgChannel).where(
                EpgChannel.epg_source_id == src_id,
                EpgChannel.xmltv_id.in_(list(xml_ids)),
            )
        ).scalars().all()

        for c in rows:
            epg_ch[(c.epg_source_id, c.xmltv_id)] = c

    items = []
    for s in streams:
        xml_id = (s.epg_channel_id or "").strip()
        ch = epg_ch.get((s.epg_source_id, xml_id)) if (s.epg_source_id and xml_id) else None


        programs = []
        if ch:
            prows = db.execute(
                select(EpgProgram)
                .where(EpgProgram.channel_id == ch.id)
                .where(EpgProgram.end_time > now, EpgProgram.start_time < end)
                .order_by(EpgProgram.start_time.asc())
            ).scalars().all()

            programs = [{
                "title": pr.title,
                "start": pr.start_time.isoformat(),
                "end": pr.end_time.isoformat(),
                "category": pr.category,
                "description": pr.description,
            } for pr in prows]

        items.append({
            "live_id": str(s.id),
            "name": s.name,
            "logo": s.custom_logo_url,
            "channel_number": s.channel_number,
            "epg_source_id": str(s.epg_source_id) if s.epg_source_id else None,
            "epg_channel_id": xml_id or None,
            "epg_channel_name": ch.display_name if ch else None,
            "programs": programs,
        })

    return {
        "ok": True,
        "window": {"start": now.isoformat(), "end": end.isoformat()},
        "count": len(items),
        "items": items,
    }
