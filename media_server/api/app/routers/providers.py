from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from app.deps import get_db
from app.models import Category, LiveStream, Provider, SeriesItem, VodStream
from app.schemas import ProviderCreate, ProviderOut, ProviderUpdate
from app.xtream_client import XtreamError, xtream_get


router = APIRouter(prefix="/providers", tags=["providers"])

@router.post("", response_model=ProviderOut)
def create_provider(payload: ProviderCreate, db: Session = Depends(get_db)):
    # MVP: base_url guardado como string
    p = Provider(
        name=payload.name,
        base_url=str(payload.base_url).rstrip("/"),
        username=payload.username,
        password=payload.password,
        is_active=True,
    )
    db.add(p)
    db.commit()
    db.refresh(p)
    return p

@router.get("", response_model=list[ProviderOut])
def list_providers(db: Session = Depends(get_db)):
    providers = db.execute(select(Provider).order_by(Provider.created_at.desc())).scalars().all()
    return providers

@router.get("/{provider_id}", response_model=ProviderOut)
def get_provider(provider_id: str, db: Session = Depends(get_db)):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")
    return p

@router.patch("/{provider_id}", response_model=ProviderOut)
def update_provider(provider_id: str, payload: ProviderUpdate, db: Session = Depends(get_db)):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    changed = False

    if payload.name is not None:
        p.name = payload.name
        changed = True

    if payload.base_url is not None:
        p.base_url = str(payload.base_url).rstrip("/")
        changed = True

    if payload.username is not None:
        p.username = payload.username
        changed = True

    if payload.password is not None:
        p.password = payload.password
        changed = True

    if payload.is_active is not None:
        p.is_active = payload.is_active
        changed = True

    if changed:
        p.updated_at = datetime.utcnow()
        db.commit()
        db.refresh(p)

    return p


@router.get("/{provider_id}/test")
def test_provider(provider_id: str, db: Session = Depends(get_db)):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    try:
        data = xtream_get(p.base_url, p.username, p.password, "get_live_categories")
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Xtream test failed: {e}")

    # típicamente devuelve lista
    count = len(data) if isinstance(data, list) else 0
    return {"ok": True, "action": "get_live_categories", "count": count}


def _sync_one_category_set(db: Session, provider: Provider, cat_type: str, raw: list[dict]) -> int:
    # Upsert manual (MVP): busca por provider + cat_type + provider_category_id
    changed = 0
    seen = set()

    for item in raw or []:
        ext_id = int(item.get("category_id"))
        name = (item.get("category_name") or "").strip() or f"Category {ext_id}"
        seen.add(ext_id)

        existing = db.execute(
            select(Category).where(
                Category.provider_id == provider.id,
                Category.cat_type == cat_type,
                Category.provider_category_id == ext_id,
            )
        ).scalar_one_or_none()

        if existing:
            # update si cambió
            if existing.name != name or existing.is_active is False:
                existing.name = name
                existing.is_active = True
                existing.updated_at = datetime.utcnow()
                changed += 1
        else:
            db.add(Category(
                provider_id=provider.id,
                cat_type=cat_type,
                provider_category_id=ext_id,
                name=name,
                is_active=True,
                updated_at=datetime.utcnow(),
            ))
            changed += 1

    # desactiva las que ya no vienen
    existing_all = db.execute(
        select(Category).where(Category.provider_id == provider.id, Category.cat_type == cat_type)
    ).scalars().all()

    for c in existing_all:
        if c.provider_category_id not in seen and c.is_active:
            c.is_active = False
            c.updated_at = datetime.utcnow()
            changed += 1

    return changed


@router.post("/{provider_id}/sync/categories")
def sync_categories(provider_id: str, db: Session = Depends(get_db)):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    try:
        live = xtream_get(p.base_url, p.username, p.password, "get_live_categories")
        vod = xtream_get(p.base_url, p.username, p.password, "get_vod_categories")
        series = xtream_get(p.base_url, p.username, p.password, "get_series_categories")
    except XtreamError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Xtream sync failed: {e}")

    if not isinstance(live, list) or not isinstance(vod, list) or not isinstance(series, list):
        raise HTTPException(status_code=400, detail="Xtream returned unexpected data format")

    changed = 0
    changed += _sync_one_category_set(db, p, "live", live)
    changed += _sync_one_category_set(db, p, "vod", vod)
    changed += _sync_one_category_set(db, p, "series", series)

    db.commit()
    return {
        "ok": True,
        "provider_id": provider_id,
        "changed": changed,
        "counts": {"live": len(live), "vod": len(vod), "series": len(series)},
    }

@router.get("/{provider_id}/categories")
def list_categories(provider_id: str, cat_type: str = "live", active_only: bool = True, db: Session = Depends(get_db)):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    q = select(Category).where(Category.provider_id == p.id, Category.cat_type == cat_type)
    if active_only:
        q = q.where(Category.is_active == True)

    rows = db.execute(q.order_by(Category.name.asc())).scalars().all()
    return [
        {
            "id": str(c.id),
            "cat_type": c.cat_type,
            "provider_category_id": c.provider_category_id,
            "name": c.name,
            "is_active": c.is_active,
        }
        for c in rows
    ]


@router.post("/{provider_id}/sync/live_streams")
def sync_live_streams(provider_id: str, category_ext_id: int, db: Session = Depends(get_db)):
    """
    category_ext_id = category_id de Xtream (provider_category_id)
    """
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    cat = db.execute(
        select(Category).where(
            Category.provider_id == p.id,
            Category.cat_type == "live",
            Category.provider_category_id == category_ext_id,
        )
    ).scalar_one_or_none()

    if not cat:
        raise HTTPException(status_code=404, detail="Category not found for this provider")

    # Xtream: action=get_live_streams, y muchos paneles aceptan category_id como filtro
    try:
        raw = xtream_get(p.base_url, p.username, p.password, "get_live_streams", category_id=category_ext_id)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Xtream sync failed: {e}")

    if not isinstance(raw, list):
        raise HTTPException(status_code=400, detail="Xtream returned unexpected data format (expected list)")

    seen = set()
    changed = 0

    for item in raw:
        ext_stream_id = int(item.get("stream_id"))
        name = (item.get("name") or "").strip() or f"Live {ext_stream_id}"
        icon = (item.get("stream_icon") or None)
        epg_id = (item.get("epg_channel_id") or None)

        seen.add(ext_stream_id)

        existing = db.execute(
            select(LiveStream).where(
                LiveStream.provider_id == p.id,
                LiveStream.provider_stream_id == ext_stream_id,
            )
        ).scalar_one_or_none()

        now = datetime.utcnow()

        if existing:
            # update si cambió o si estaba inactive
            if (
                existing.name != name
                or existing.stream_icon != icon
                or existing.epg_channel_id != epg_id
                or existing.category_id != cat.id
                or existing.is_active is False
            ):
                existing.name = name
                existing.stream_icon = icon
                existing.epg_channel_id = epg_id
                existing.category_id = cat.id
                existing.is_active = True
                existing.updated_at = now
                changed += 1
        else:
            db.add(LiveStream(
                provider_id=p.id,
                category_id=cat.id,
                provider_stream_id=ext_stream_id,
                name=name,
                stream_icon=icon,
                epg_channel_id=epg_id,
                is_active=True,
                updated_at=now,
            ))
            changed += 1

    # desactiva los que ya no aparecen en ESTA categoría
    existing_in_cat = db.execute(
        select(LiveStream).where(
            LiveStream.provider_id == p.id,
            LiveStream.category_id == cat.id,
            LiveStream.is_active == True,
        )
    ).scalars().all()

    now = datetime.utcnow()
    for s in existing_in_cat:
        if s.provider_stream_id not in seen:
            s.is_active = False
            s.updated_at = now
            changed += 1

    db.commit()
    return {"ok": True, "provider_id": provider_id, "category_ext_id": category_ext_id, "count": len(raw), "changed": changed}

@router.post("/{provider_id}/sync/all")
def sync_all(
    provider_id: str,
    live: bool = True,
    include_inactive_categories: bool = False,
    db: Session = Depends(get_db),
):
    """
    One-click sync:
    - (asume que YA tienes categories sincronizadas)
    - recorre todas las LIVE categories y hace sync de streams
    """
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    started = datetime.utcnow()

    result = {
        "ok": True,
        "provider_id": provider_id,
        "live": {"categories": 0, "total_streams": 0, "changed": 0, "details": []},
        "started_at": started.isoformat() + "Z",
        "finished_at": None,
        "seconds": None,
    }

    if not live:
        finished = datetime.utcnow()
        result["finished_at"] = finished.isoformat() + "Z"
        result["seconds"] = (finished - started).total_seconds()
        return result

    # Carga categorías LIVE desde tu DB (ya sincronizadas)
    q = select(Category).where(
        Category.provider_id == p.id,
        Category.cat_type == "live",
    )
    if not include_inactive_categories:
        q = q.where(Category.is_active == True)

    cats = db.execute(q.order_by(Category.name.asc())).scalars().all()
    result["live"]["categories"] = len(cats)

    for cat in cats:
        # pedir streams de esa categoría
        raw = xtream_get(
            p.base_url, p.username, p.password,
            "get_live_streams",
            category_id=cat.provider_category_id
        )

        if not isinstance(raw, list):
            # si un provider devuelve algo raro, seguimos
            result["live"]["details"].append({
                "category_ext_id": cat.provider_category_id,
                "category_name": cat.name,
                "error": "Unexpected format (expected list)",
            })
            continue

        seen = set()
        changed = 0

        for item in raw:
            try:
                ext_stream_id = int(item.get("stream_id"))
            except Exception:
                continue

            name = (item.get("name") or "").strip() or f"Live {ext_stream_id}"
            icon = item.get("stream_icon") or None
            epg_id = item.get("epg_channel_id") or None

            seen.add(ext_stream_id)

            existing = db.execute(
                select(LiveStream).where(
                    LiveStream.provider_id == p.id,
                    LiveStream.provider_stream_id == ext_stream_id,
                )
            ).scalar_one_or_none()

            now = datetime.utcnow()

            if existing:
                if (
                    existing.name != name
                    or existing.stream_icon != icon
                    or existing.epg_channel_id != epg_id
                    or existing.category_id != cat.id
                    or existing.is_active is False
                ):
                    existing.name = name
                    existing.stream_icon = icon
                    existing.epg_channel_id = epg_id
                    existing.category_id = cat.id
                    existing.is_active = True
                    existing.updated_at = now
                    changed += 1
            else:
                db.add(LiveStream(
                    provider_id=p.id,
                    category_id=cat.id,
                    provider_stream_id=ext_stream_id,
                    name=name,
                    stream_icon=icon,
                    epg_channel_id=epg_id,
                    is_active=True,
                    updated_at=now,
                ))
                changed += 1

        # desactivar los que ya no vienen en esa categoría
        existing_in_cat = db.execute(
            select(LiveStream).where(
                LiveStream.provider_id == p.id,
                LiveStream.category_id == cat.id,
                LiveStream.is_active == True,
            )
        ).scalars().all()

        now = datetime.utcnow()
        for s in existing_in_cat:
            if s.provider_stream_id not in seen:
                s.is_active = False
                s.updated_at = now
                changed += 1

        db.commit()

        result["live"]["total_streams"] += len(raw)
        result["live"]["changed"] += changed
        result["live"]["details"].append({
            "category_ext_id": cat.provider_category_id,
            "category_name": cat.name,
            "count": len(raw),
            "changed": changed,
        })

    finished = datetime.utcnow()
    result["finished_at"] = finished.isoformat() + "Z"
    result["seconds"] = (finished - started).total_seconds()
    return result

@router.post("/{provider_id}/sync/vod_streams")
def sync_provider_vod_streams(
    provider_id: str,
    include_inactive_categories: bool = True,   # ✅ default: incluir todas
    deactivate_missing: bool = False,            # ✅ default: NO apagar nada
    db: Session = Depends(get_db),
):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    started = datetime.utcnow()

    # ✅ (opcional pero RECOMENDADO) refresca categorías VOD desde la fuente
    try:
        vod_cats = xtream_get(p.base_url, p.username, p.password, "get_vod_categories")
        if isinstance(vod_cats, list):
            _sync_one_category_set(db, p, "vod", vod_cats)
            db.commit()
    except Exception:
        # si falla, seguimos con lo que haya en DB
        pass

    result = {
        "ok": True,
        "provider_id": provider_id,
        "vod": {"categories": 0, "total_streams": 0, "changed": 0, "details": []},
        "started_at": started.isoformat() + "Z",
        "finished_at": None,
        "seconds": None,
        "deactivate_missing": deactivate_missing,
    }

    q = select(Category).where(
        Category.provider_id == p.id,
        Category.cat_type == "vod",
    )
    if not include_inactive_categories:
        q = q.where(Category.is_active == True)

    cats = db.execute(q.order_by(Category.name.asc())).scalars().all()
    result["vod"]["categories"] = len(cats)

    for cat in cats:
        try:
            raw = xtream_get(
                p.base_url, p.username, p.password,
                "get_vod_streams",
                timeout=120.0,
                category_id=cat.provider_category_id
            )
        except Exception as e:
            result["vod"]["details"].append({
                "category_ext_id": cat.provider_category_id,
                "category_name": cat.name,
                "error": str(e),
            })
            continue

        if not isinstance(raw, list):
            result["vod"]["details"].append({
                "category_ext_id": cat.provider_category_id,
                "category_name": cat.name,
                "error": "Unexpected format (expected list)",
            })
            continue

        seen = set()
        changed = 0
        now = datetime.utcnow()

        def _pick_canonical(streams: list[VodStream], stream_id: int) -> VodStream:
            preferred = [s for s in streams if s.provider_stream_id == stream_id] or streams

            def score(item: VodStream) -> tuple:
                return (
                    1 if item.tmdb_id is not None else 0,
                    1 if item.tmdb_title else 0,
                    1 if item.normalized_name else 0,
                    1 if item.tmdb_overview else 0,
                    item.updated_at or item.created_at,
                )

            return max(preferred, key=score)

        for item in raw:
            try:
                ext_stream_id = int(item.get("stream_id"))
            except Exception:
                continue

            name = (item.get("name") or "").strip() or f"VOD {ext_stream_id}"
            icon = item.get("stream_icon") or None
            container_ext = (item.get("container_extension") or None)
            rating = (item.get("rating") or None)
            added = (item.get("added") or None)

            seen.add(ext_stream_id)

            existing_matches = db.execute(
                select(VodStream).where(
                    VodStream.provider_id == p.id,
                    or_(
                        VodStream.provider_stream_id == ext_stream_id,
                        func.lower(VodStream.name) == name.lower(),
                    ),
                )
            ).scalars().all()

            if existing_matches:
                existing = _pick_canonical(existing_matches, ext_stream_id)
                if (
                    existing.name != name
                    or existing.stream_icon != icon
                    or existing.category_id != cat.id
                    or existing.container_extension != container_ext
                    or existing.rating != rating
                    or existing.added != added
                    or existing.provider_stream_id != ext_stream_id
                    or existing.is_active is False
                ):
                    existing.name = name
                    existing.stream_icon = icon
                    existing.category_id = cat.id
                    existing.container_extension = container_ext
                    existing.rating = rating
                    existing.added = added
                    existing.provider_stream_id = ext_stream_id
                    existing.is_active = True
                    existing.updated_at = now
                    changed += 1

                for dup in existing_matches:
                    if dup.id == existing.id:
                        continue
                    db.delete(dup)
                    changed += 1
            else:
                db.add(VodStream(
                    provider_id=p.id,
                    category_id=cat.id,
                    provider_stream_id=ext_stream_id,
                    name=name,
                    stream_icon=icon,
                    container_extension=container_ext,
                    rating=rating,
                    added=added,
                    is_active=True,
                    updated_at=now,
                ))
                changed += 1

        # ✅ SOLO si tú lo pides: desactivar los que no vinieron
        if deactivate_missing:
            existing_in_cat = db.execute(
                select(VodStream).where(
                    VodStream.provider_id == p.id,
                    VodStream.category_id == cat.id,
                    VodStream.is_active == True,
                )
            ).scalars().all()

            for s in existing_in_cat:
                if s.provider_stream_id not in seen:
                    s.is_active = False
                    s.updated_at = now
                    changed += 1

        db.commit()

        result["vod"]["total_streams"] += len(raw)
        result["vod"]["changed"] += changed
        result["vod"]["details"].append({
            "category_ext_id": cat.provider_category_id,
            "category_name": cat.name,
            "count": len(raw),
            "changed": changed,
        })

    finished = datetime.utcnow()
    result["finished_at"] = finished.isoformat() + "Z"
    result["seconds"] = (finished - started).total_seconds()
    return result


@router.post("/{provider_id}/sync/series_items")
def sync_series_items(provider_id: str, category_ext_id: int, db: Session = Depends(get_db)):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    cat = db.execute(
        select(Category).where(
            Category.provider_id == p.id,
            Category.cat_type == "series",
            Category.provider_category_id == category_ext_id,
        )
    ).scalar_one_or_none()
    if not cat:
        raise HTTPException(status_code=404, detail="Category not found for this provider")

    try:
        raw = xtream_get(p.base_url, p.username, p.password, "get_series", category_id=category_ext_id)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Xtream sync failed: {e}")

    if not isinstance(raw, list):
        raise HTTPException(status_code=400, detail="Xtream returned unexpected data format (expected list)")

    seen = set()
    changed = 0
    now = datetime.utcnow()

    for item in raw:
        # series_id es lo normal; algunos panels mandan "series_id" como string
        try:
            ext_id = int(item.get("series_id"))
        except Exception:
            continue

        name = (item.get("name") or "").strip() or f"Series {ext_id}"
        cover = item.get("cover") or item.get("stream_icon") or None

        seen.add(ext_id)

        existing = db.execute(
            select(SeriesItem).where(SeriesItem.provider_id == p.id, SeriesItem.provider_series_id == ext_id)
        ).scalar_one_or_none()

        if existing:
            if (
                existing.name != name
                or existing.cover != cover
                or existing.category_id != cat.id
                or existing.is_active is False
            ):
                existing.name = name
                existing.cover = cover
                existing.category_id = cat.id
                existing.is_active = True
                existing.updated_at = now
                changed += 1
        else:
            db.add(SeriesItem(
                provider_id=p.id,
                category_id=cat.id,
                provider_series_id=ext_id,
                name=name,
                cover=cover,
                is_active=True,
                updated_at=now,
            ))
            changed += 1

    # desactiva los que ya no vienen en ESTA categoría
    existing_in_cat = db.execute(
        select(SeriesItem).where(
            SeriesItem.provider_id == p.id,
            SeriesItem.category_id == cat.id,
            SeriesItem.is_active == True,
        )
    ).scalars().all()

    for s in existing_in_cat:
        if s.provider_series_id not in seen:
            s.is_active = False
            s.updated_at = now
            changed += 1

    db.commit()
    return {"ok": True, "provider_id": provider_id, "category_ext_id": category_ext_id, "count": len(raw), "changed": changed}
