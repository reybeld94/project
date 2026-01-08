import uuid

from fastapi import APIRouter, Depends, HTTPException, Response
from sqlalchemy.orm import Session
from sqlalchemy import select, func, or_

from app.deps import get_db
from app.models import Provider, Category, VodStream
from app.schemas import VodStreamUpdate
from app.xtream_client import xtream_get

router = APIRouter(prefix="/vod", tags=["vod"])

MAX_LIMIT = 5000


def _get_vod_by_identifier(db: Session, vod_id: str) -> VodStream | None:
    normalized_id = vod_id.strip()
    if normalized_id.lower().startswith("tmdb:"):
        tmdb_value = normalized_id.split(":", 1)[1].strip()
        if not tmdb_value.isdigit():
            return None
        return db.execute(
            select(VodStream).where(VodStream.tmdb_id == int(tmdb_value))
        ).scalar_one_or_none()

    try:
        vod_uuid = uuid.UUID(normalized_id)
    except ValueError:
        return None

    return db.get(VodStream, vod_uuid)

@router.get("")

def list_vod(
        provider_id: str,
        category_ext_id: int | None = None,
        q: str | None = None,
        limit: int = 50,
        offset: int = 0,
        active_only: bool = True,
        approved: bool | None = None,
        db: Session = Depends(get_db),
):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    stmt = select(VodStream).where(VodStream.provider_id == p.id)

    if active_only:
        stmt = stmt.where(VodStream.is_active == True)

    if approved is not None:
        stmt = stmt.where(VodStream.approved == approved)

    if category_ext_id is not None:
        cat = db.execute(
            select(Category).where(
                Category.provider_id == p.id,
                Category.cat_type == "vod",
                Category.provider_category_id == category_ext_id,
            )
        ).scalar_one_or_none()
        if not cat:
            raise HTTPException(status_code=404, detail="Category not found for this provider")
        stmt = stmt.where(VodStream.category_id == cat.id)

    if q:
        qq = f"%{q.strip()}%"
        stmt = stmt.where(or_(
            VodStream.name.ilike(qq),
            VodStream.normalized_name.ilike(qq),
        ))

    total = db.execute(select(func.count()).select_from(stmt.subquery())).scalar_one()

    safe_limit = MAX_LIMIT if limit <= 0 else min(limit, MAX_LIMIT)

    rows = db.execute(
        stmt.order_by(VodStream.name.asc())
        .limit(safe_limit).offset(offset)
    ).scalars().all()

    return {
        "total": int(total),
        "items": [
            {
                "id": str(x.id),
                "provider_stream_id": x.provider_stream_id,
                "name": x.name,
                "normalized_name": x.normalized_name,
                "poster": x.custom_poster_url or x.stream_icon,
                "stream_icon": x.stream_icon,
                "custom_poster_url": x.custom_poster_url,
                "container_extension": x.container_extension,
                "rating": x.rating,
                "added": x.added,
                "approved": x.approved,
                "is_active": x.is_active,
                "category_ext_id": x.category.provider_category_id if x.category else None,
                "category_name": x.category.name if x.category else None,
            }
            for x in rows
        ],
    }

@router.get("/all")
def list_vod_all(
        q: str | None = None,
        limit: int = 60,
        offset: int = 0,
        active_only: bool = True,
        approved: bool | None = None,
        synced: bool | None = None,
        db: Session = Depends(get_db),
):
    """
    Lista VOD de TODOS los providers activos (modo Media Server).
    """
    stmt = (
        select(VodStream)
        .join(Provider, Provider.id == VodStream.provider_id)
    )

    # Se eliminan filtros por activo (provider o stream) para mostrar todos los VOD.
    # El flag active_only se mantiene en la firma por compatibilidad, pero ya no filtra.

    if approved is not None:
        stmt = stmt.where(VodStream.approved == approved)

    # Normaliza comparaciones de TMDB para tolerar mayúsculas y variantes "sync".
    tmdb_synced_values = ["synced", "sync"]
    if synced is True:
        stmt = stmt.where(func.lower(VodStream.tmdb_status).in_(tmdb_synced_values))
    elif synced is False:
        stmt = stmt.where(or_(
            VodStream.tmdb_status == None,
            func.lower(VodStream.tmdb_status).not_in(tmdb_synced_values),
        ))


    if q:
        qq = f"%{q.strip()}%"
        stmt = stmt.where(or_(
            VodStream.name.ilike(qq),
            VodStream.normalized_name.ilike(qq),
        ))

    total = db.execute(select(func.count()).select_from(stmt.subquery())).scalar_one()

    rows = db.execute(
        stmt.order_by(VodStream.name.asc())
        .limit(min(max(limit, 1), 200)).offset(offset)
    ).scalars().all()

    return {
        "total": int(total),
        "items": [
            {
                "id": str(x.id),
                "provider_id": str(x.provider_id),
                "provider_name": x.provider.name if x.provider else None,

                "provider_stream_id": x.provider_stream_id,
                "name": x.name,
                "normalized_name": x.normalized_name,

                "poster": x.custom_poster_url or x.stream_icon,
                "stream_icon": x.stream_icon,
                "custom_poster_url": x.custom_poster_url,

                "container_extension": x.container_extension,
                "rating": x.rating,
                "added": x.added,

                "approved": x.approved,
                "is_active": x.is_active,

                "tmdb_status": x.tmdb_status,
                "tmdb_title": x.tmdb_title,
                "tmdb_id": x.tmdb_id,

                "category_ext_id": x.category.provider_category_id if x.category else None,
                "category_name": x.category.name if x.category else None,
            }
            for x in rows
        ],
    }



@router.get("/{vod_id}/play")
def vod_play_url(
        vod_id: str,
        format: str | None = None,
        db: Session = Depends(get_db),
        response: Response = None,
):
    """
    Devuelve la URL ORIGINAL de Xtream (sin resolver redirects).

    IMPORTANTE: NO resolvemos el 302 redirect aquí porque:
    1. El token en la URL final (vauth/...) expira en segundos
    2. Si Python resuelve el redirect, para cuando Android lo use ya expiró
    3. ExoPlayer puede seguir redirects perfectamente bien

    Dejamos que ExoPlayer siga el 302 él mismo para obtener un token fresco.
    """
    v = _get_vod_by_identifier(db, vod_id)
    if not v:
        raise HTTPException(status_code=404, detail="VOD not found")

    p = db.get(Provider, v.provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    # Usar la extensión guardada o mp4 como fallback
    ext = (format or v.container_extension or "mp4").lower().strip()

    # URL original de Xtream - ExoPlayer seguirá el 302 por sí mismo
    url = f"{p.base_url.rstrip('/')}/movie/{p.username}/{p.password}/{v.provider_stream_id}.{ext}"

    # Evita caches
    if response is not None:
        response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate"
        response.headers["Pragma"] = "no-cache"

    return {
        "id": str(v.id),
        "name": v.name,
        "url": url,
        "container_extension": v.container_extension,
    }


@router.get("/{vod_id}/info")
def vod_info(
        vod_id: str,
        db: Session = Depends(get_db),
):
    v = _get_vod_by_identifier(db, vod_id)
    if not v:
        raise HTTPException(status_code=404, detail="VOD not found")

    p = db.get(Provider, v.provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    info = xtream_get(p.base_url, p.username, p.password, "get_vod_info", vod_id=v.provider_stream_id)
    return {"id": str(v.id), "provider_stream_id": v.provider_stream_id, "info": info}


@router.patch("/{vod_id}")
def update_vod(
        vod_id: str,
        payload: VodStreamUpdate,
        db: Session = Depends(get_db),
):
    v = _get_vod_by_identifier(db, vod_id)
    if not v:
        raise HTTPException(status_code=404, detail="VOD not found")

    data = payload.dict(exclude_unset=True)

    reset_tmdb = False

    if "approved" in data:
        new_approved = bool(data["approved"])
        if new_approved != v.approved:
            v.approved = new_approved
            # si lo acaban de aprobar y no está synced, queremos intentar ya
            if v.approved and v.tmdb_status != "synced":
                reset_tmdb = True

    if "normalized_name" in data:
        new_name = (data["normalized_name"] or "").strip() or None
        if new_name != v.normalized_name:
            v.normalized_name = new_name
            reset_tmdb = True

    if reset_tmdb:
        v.tmdb_id = None
        v.tmdb_status = "missing"
        v.tmdb_last_sync = None
        v.tmdb_error = None
        v.tmdb_title = None
        v.tmdb_overview = None
        v.tmdb_release_date = None
        v.tmdb_genres = None
        v.tmdb_vote_average = None
        v.tmdb_poster_path = None
        v.tmdb_backdrop_path = None
        v.tmdb_raw = None

    if "custom_poster_url" in data:
        s = (data["custom_poster_url"] or "").strip()
        v.custom_poster_url = s or None

    db.commit()
    db.refresh(v)

    return {
        "id": str(v.id),
        "name": v.name,
        "approved": v.approved,
        "normalized_name": v.normalized_name,
        "custom_poster_url": v.custom_poster_url,
    }

@router.get("/{vod_id}")
def vod_detail(vod_id: str, db: Session = Depends(get_db)):
    v = _get_vod_by_identifier(db, vod_id)
    if not v:
        raise HTTPException(status_code=404, detail="VOD not found")

    p = db.get(Provider, v.provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    def iso_date(x):
        if not x:
            return None
        try:
            return x.date().isoformat()
        except Exception:
            try:
                return x.isoformat()
            except Exception:
                return str(x)

    tmdb_runtime = None
    tmdb_cast = []
    tmdb_trailer = None

    raw = v.tmdb_raw or {}
    try:
        tmdb_runtime = raw.get("runtime")

        credits = raw.get("credits") or {}
        cast = credits.get("cast") or []
        tmdb_cast = [
            {"name": c.get("name"), "character": c.get("character"), "profile_path": c.get("profile_path")}
            for c in cast[:10] if c.get("name")
        ]

        videos = (raw.get("videos") or {}).get("results") or []
        trailer = next(
            (vid for vid in videos
             if vid.get("type") == "Trailer" and vid.get("site") == "YouTube" and vid.get("key")),
            None
        )
        if trailer:
            tmdb_trailer = {"site": trailer.get("site"), "key": trailer.get("key"), "name": trailer.get("name")}
    except Exception:
        pass

    return {
        "id": str(v.id),
        "provider_id": str(v.provider_id),
        "provider_name": p.name if p else None,

        "provider_stream_id": v.provider_stream_id,
        "name": v.name,
        "normalized_name": v.normalized_name,

        "poster": v.custom_poster_url or v.stream_icon,
        "stream_icon": v.stream_icon,
        "custom_poster_url": v.custom_poster_url,

        "container_extension": v.container_extension,
        "rating": v.rating,
        "added": v.added,

        "approved": v.approved,
        "is_active": v.is_active,

        "category_ext_id": v.category.provider_category_id if v.category else None,
        "category_name": v.category.name if v.category else None,

        # TMDB
        "tmdb_id": v.tmdb_id,
        "tmdb_status": v.tmdb_status,
        "tmdb_last_sync": v.tmdb_last_sync.isoformat() if v.tmdb_last_sync else None,
        "tmdb_error": v.tmdb_error,

        "tmdb_title": v.tmdb_title,
        "tmdb_overview": v.tmdb_overview,
        "tmdb_release_date": iso_date(v.tmdb_release_date),
        "tmdb_genres": v.tmdb_genres,
        "tmdb_vote_average": v.tmdb_vote_average,

        "tmdb_runtime": tmdb_runtime,
        "tmdb_cast": tmdb_cast,
        "tmdb_trailer": tmdb_trailer,

        "tmdb_poster_path": v.tmdb_poster_path,
        "tmdb_backdrop_path": v.tmdb_backdrop_path,
    }
