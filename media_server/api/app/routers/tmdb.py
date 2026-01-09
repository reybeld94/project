from datetime import datetime, timedelta
import threading

import httpx
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from sqlalchemy import select, func, or_

from app.deps import get_db
from app.models import TmdbConfig, VodStream, SeriesItem
from app.schemas import TmdbConfigOut, TmdbConfigUpdate, TmdbStatusOut, TmdbActivityOut
from app.tmdb_client import RateLimiter, find_and_fetch_movie, find_and_fetch_tv, tmdb_get_json

router = APIRouter(prefix="/tmdb", tags=["tmdb"])
GENRE_CACHE_TTL = timedelta(hours=24)
GENRE_ALLOWED_KINDS = {"movie", "tv"}
_genre_cache: dict[tuple[str, str], dict] = {}
_genre_lock = threading.Lock()

def _copy_tmdb_fields(target: VodStream, source: VodStream) -> None:
    target.tmdb_id = source.tmdb_id
    target.tmdb_status = source.tmdb_status
    target.tmdb_last_sync = source.tmdb_last_sync
    target.tmdb_error = None
    target.tmdb_title = source.tmdb_title
    target.tmdb_overview = source.tmdb_overview
    target.tmdb_release_date = source.tmdb_release_date
    target.tmdb_genres = source.tmdb_genres
    target.tmdb_vote_average = source.tmdb_vote_average
    target.tmdb_poster_path = source.tmdb_poster_path
    target.tmdb_backdrop_path = source.tmdb_backdrop_path
    target.tmdb_raw = source.tmdb_raw

def _dedupe_tmdb_streams(db: Session, provider_id, tmdb_id: int | None) -> None:
    if tmdb_id is None:
        return
    group = db.execute(
        select(VodStream)
        .where(VodStream.provider_id == provider_id, VodStream.tmdb_id == tmdb_id)
        .order_by(VodStream.created_at.desc(), VodStream.id.desc())
    ).scalars().all()
    if len(group) < 2:
        return
    winner = group[0]
    synced_donor = next((item for item in group if item.tmdb_status == "synced"), None)
    if winner.tmdb_status != "synced" and synced_donor:
        _copy_tmdb_fields(winner, synced_donor)
        winner.tmdb_status = "synced"
        winner.tmdb_error = None
    for dup in group[1:]:
        db.delete(dup)


def mask(s: str | None, keep: int = 4) -> str | None:
    if not s:
        return None
    s = s.strip()
    if len(s) <= keep:
        return "*" * len(s)
    return ("*" * (len(s) - keep)) + s[-keep:]

def get_or_create_cfg(db: Session) -> TmdbConfig:
    cfg = db.execute(select(TmdbConfig).limit(1)).scalar_one_or_none()
    if not cfg:
        cfg = TmdbConfig(is_enabled=False, language="en-US", region="US", requests_per_second=5)
        db.add(cfg)
        db.commit()
        db.refresh(cfg)
    return cfg

@router.get("/config", response_model=TmdbConfigOut)
def get_config(db: Session = Depends(get_db)):
    cfg = get_or_create_cfg(db)
    return {
        "is_enabled": cfg.is_enabled,
        "api_key_masked": mask(cfg.api_key, 4),
        "read_access_token_masked": mask(cfg.read_access_token, 6),
        "language": cfg.language,
        "region": cfg.region,
        "requests_per_second": cfg.requests_per_second,
    }

@router.patch("/config", response_model=TmdbConfigOut)
def update_config(payload: TmdbConfigUpdate, db: Session = Depends(get_db)):
    cfg = get_or_create_cfg(db)
    data = payload.dict(exclude_unset=True)

    if "is_enabled" in data:
        cfg.is_enabled = bool(data["is_enabled"])

    if "api_key" in data:
        v = (data["api_key"] or "").strip()
        cfg.api_key = v or None

    if "read_access_token" in data:
        v = (data["read_access_token"] or "").strip()
        cfg.read_access_token = v or None

    if "language" in data:
        v = (data["language"] or "").strip()
        cfg.language = v or None

    if "region" in data:
        v = (data["region"] or "").strip()
        cfg.region = v or None

    if "requests_per_second" in data:
        cfg.requests_per_second = max(1, int(data["requests_per_second"] or 5))

    cfg.updated_at = datetime.utcnow()
    db.commit()
    db.refresh(cfg)

    return {
        "is_enabled": cfg.is_enabled,
        "api_key_masked": mask(cfg.api_key, 4),
        "read_access_token_masked": mask(cfg.read_access_token, 6),
        "language": cfg.language,
        "region": cfg.region,
        "requests_per_second": cfg.requests_per_second,
    }

@router.get("/status", response_model=TmdbStatusOut)
def tmdb_status(db: Session = Depends(get_db)):
    cfg = get_or_create_cfg(db)

    def counts(model):
        total = db.execute(select(func.count()).select_from(model)).scalar_one()
        synced = db.execute(select(func.count()).select_from(model).where(model.tmdb_status == "synced")).scalar_one()
        failed = db.execute(select(func.count()).select_from(model).where(model.tmdb_status == "failed")).scalar_one()
        missing = db.execute(select(func.count()).select_from(model).where(model.tmdb_status == "missing")).scalar_one()
        return int(total), int(synced), int(failed), int(missing)

    mt, ms, mf, mm = counts(VodStream)
    st, ss, sf, sm = counts(SeriesItem)

    return {
        "enabled": bool(cfg.is_enabled),
        "movies_total": mt,
        "movies_synced": ms,
        "movies_failed": mf,
        "movies_missing": mm,
        "series_total": st,
        "series_synced": ss,
        "series_failed": sf,
        "series_missing": sm,
    }

@router.get("/activity", response_model=TmdbActivityOut)
def tmdb_activity(limit: int = 20, db: Session = Depends(get_db)):
    limit = max(1, min(int(limit or 20), 100))

    mrows = db.execute(
        select(VodStream)
        .where(VodStream.tmdb_last_sync != None)
        .order_by(VodStream.tmdb_last_sync.desc())
        .limit(limit)
    ).scalars().all()

    srows = db.execute(
        select(SeriesItem)
        .where(SeriesItem.tmdb_last_sync != None)
        .order_by(SeriesItem.tmdb_last_sync.desc())
        .limit(limit)
    ).scalars().all()

    items = []

    for v in mrows:
        items.append({
            "kind": "movie",
            "id": str(v.id),
            "name": v.name,
            "normalized_name": v.normalized_name,
            "tmdb_status": v.tmdb_status,
            "tmdb_id": v.tmdb_id,
            "tmdb_title": v.tmdb_title,
            "tmdb_last_sync": v.tmdb_last_sync.isoformat() + "Z" if v.tmdb_last_sync else None,
            "tmdb_error": v.tmdb_error,
        })

    for s in srows:
        items.append({
            "kind": "series",
            "id": str(s.id),
            "name": s.name,
            "normalized_name": s.normalized_name,
            "tmdb_status": s.tmdb_status,
            "tmdb_id": s.tmdb_id,
            "tmdb_title": s.tmdb_title,
            "tmdb_last_sync": s.tmdb_last_sync.isoformat() + "Z" if s.tmdb_last_sync else None,
            "tmdb_error": s.tmdb_error,
        })

    # mezcla movies+series por fecha, y recorta a `limit`
    items.sort(key=lambda x: x.get("tmdb_last_sync") or "", reverse=True)
    items = items[:limit]

    return {
        "server_time": datetime.utcnow().isoformat() + "Z",
        "items": items
    }


@router.get("/genres")
def tmdb_genres(kind: str = "movie", db: Session = Depends(get_db)):
    kind = (kind or "").strip().lower()
    if kind not in GENRE_ALLOWED_KINDS:
        raise HTTPException(
            status_code=400,
            detail=f"kind inválido. Valores permitidos: {sorted(GENRE_ALLOWED_KINDS)}",
        )

    cfg = get_or_create_cfg(db)
    if not cfg.is_enabled:
        raise HTTPException(status_code=400, detail="TMDB is disabled in settings")

    token = cfg.read_access_token
    api_key = cfg.api_key
    if not token and not api_key:
        raise HTTPException(status_code=400, detail="Missing TMDB credentials (token or api_key)")

    language = cfg.language or "en-US"
    cache_key = (kind, language)
    now = datetime.utcnow()

    with _genre_lock:
        cached = _genre_cache.get(cache_key)
        if cached and cached["expires_at"] > now:
            return cached["payload"]
        if cached:
            _genre_cache.pop(cache_key, None)

    limiter = RateLimiter(rps=cfg.requests_per_second or 5)
    with httpx.Client(timeout=20) as client:
        payload = tmdb_get_json(
            client,
            limiter,
            f"/genre/{kind}/list",
            token=token,
            api_key=api_key,
            params={"language": language},
        )

    response = {
        "kind": kind,
        "language": language,
        "genres": payload.get("genres") or [],
    }

    with _genre_lock:
        _genre_cache[cache_key] = {"payload": response, "expires_at": now + GENRE_CACHE_TTL}

    return response


@router.post("/sync/movies")
def sync_movies(limit: int = 20, approved_only: bool = True, cooldown_minutes: int = 0, db: Session = Depends(get_db)):
    cfg = get_or_create_cfg(db)
    if not cfg.is_enabled:
        raise HTTPException(status_code=400, detail="TMDB is disabled in settings")

    token = cfg.read_access_token
    api_key = cfg.api_key
    if not token and not api_key:
        raise HTTPException(status_code=400, detail="Missing TMDB credentials (token or api_key)")

    stmt = select(VodStream).where(VodStream.tmdb_status != "synced")

    if approved_only:
        stmt = stmt.where(VodStream.approved == True)

    if cooldown_minutes and cooldown_minutes > 0:
        cutoff = datetime.utcnow() - timedelta(minutes=cooldown_minutes)
        stmt = stmt.where(or_(VodStream.tmdb_last_sync == None, VodStream.tmdb_last_sync < cutoff))

    stmt = stmt.order_by(
        VodStream.tmdb_last_sync.asc().nullsfirst(),
        VodStream.created_at.asc(),
    )

    rows = db.execute(stmt.limit(max(1, min(limit, 200)))).scalars().all()

    processed = synced = missing = failed = 0

    for v in rows:
        processed += 1
        title = (v.normalized_name or v.name or "").strip()
        try:
            best, details = find_and_fetch_movie(
                title,
                token=token,
                api_key=api_key,
                language=cfg.language or "en-US",
                region=cfg.region or "US",
                rps=cfg.requests_per_second or 5,
            )

            if not details:
                v.tmdb_status = "missing"
                v.tmdb_error = None
                v.tmdb_last_sync = datetime.utcnow()
                db.commit()
                missing += 1
                continue

            v.tmdb_id = int(details.get("id"))
            v.tmdb_status = "synced"
            v.tmdb_error = None
            v.tmdb_last_sync = datetime.utcnow()

            v.tmdb_title = details.get("title")
            v.tmdb_overview = details.get("overview")
            v.tmdb_poster_path = details.get("poster_path")
            v.tmdb_backdrop_path = details.get("backdrop_path")
            v.tmdb_vote_average = details.get("vote_average")
            rd = (details.get("release_date") or "").strip()
            if rd:
                try:
                    v.tmdb_release_date = datetime.strptime(rd, "%Y-%m-%d")
                except Exception:
                    v.tmdb_release_date = None

            # genres: [{id,name},...]
            v.tmdb_genres = [g.get("name") for g in (details.get("genres") or []) if g.get("name")]

            v.tmdb_raw = details

            db.flush()
            _dedupe_tmdb_streams(db, v.provider_id, v.tmdb_id)
            db.commit()
            synced += 1

        except Exception as e:
            v.tmdb_status = "failed"
            v.tmdb_error = str(e)[:480]
            v.tmdb_last_sync = datetime.utcnow()
            db.commit()
            failed += 1

    return {
        "processed": processed,
        "synced": synced,
        "missing": missing,
        "failed": failed,
        "approved_only": approved_only,
        "rps": cfg.requests_per_second,
    }

@router.post("/sync/series")
def sync_series(limit: int = 20, approved_only: bool = True, cooldown_minutes: int = 0, db: Session = Depends(get_db)):
    cfg = get_or_create_cfg(db)
    if not cfg.is_enabled:
        raise HTTPException(status_code=400, detail="TMDB is disabled in settings")

    token = cfg.read_access_token
    api_key = cfg.api_key
    if not token and not api_key:
        raise HTTPException(status_code=400, detail="Missing TMDB credentials (token or api_key)")

    stmt = select(SeriesItem).where(SeriesItem.tmdb_status != "synced")

    if approved_only:
        stmt = stmt.where(SeriesItem.approved == True)

    if cooldown_minutes and cooldown_minutes > 0:
        cutoff = datetime.utcnow() - timedelta(minutes=cooldown_minutes)
        stmt = stmt.where(or_(SeriesItem.tmdb_last_sync == None, SeriesItem.tmdb_last_sync < cutoff))

    stmt = stmt.order_by(
        SeriesItem.tmdb_last_sync.asc().nullsfirst(),
        SeriesItem.created_at.asc(),
    )

    rows = db.execute(stmt.limit(max(1, min(limit, 200)))).scalars().all()

    processed = synced = missing = failed = 0

    for s in rows:
        processed += 1
        title = (s.normalized_name or s.name or "").strip()
        try:
            best, details = find_and_fetch_tv(
                title,
                token=token,
                api_key=api_key,
                language=cfg.language or "en-US",
                region=cfg.region or "US",
                rps=cfg.requests_per_second or 5,
            )

            if not details:
                s.tmdb_status = "missing"
                s.tmdb_error = None
                s.tmdb_last_sync = datetime.utcnow()
                db.commit()
                missing += 1
                continue

            s.tmdb_id = int(details.get("id"))
            s.tmdb_status = "synced"
            s.tmdb_error = None
            s.tmdb_last_sync = datetime.utcnow()

            s.tmdb_title = details.get("name")
            s.tmdb_overview = details.get("overview")
            s.tmdb_poster_path = details.get("poster_path")
            s.tmdb_backdrop_path = details.get("backdrop_path")
            s.tmdb_vote_average = details.get("vote_average")
            ad = (details.get("first_air_date") or "").strip()
            if ad:
                try:
                    s.tmdb_release_date = datetime.strptime(ad, "%Y-%m-%d")
                except Exception:
                    s.tmdb_release_date = None
            s.tmdb_genres = [g.get("name") for g in (details.get("genres") or []) if g.get("name")]
            s.tmdb_raw = details

            db.commit()
            synced += 1

        except Exception as e:
            s.tmdb_status = "failed"
            s.tmdb_error = str(e)[:480]
            s.tmdb_last_sync = datetime.utcnow()
            db.commit()
            failed += 1

    return {
        "processed": processed,
        "synced": synced,
        "missing": missing,
        "failed": failed,
        "approved_only": approved_only,
        "rps": cfg.requests_per_second,
    }
