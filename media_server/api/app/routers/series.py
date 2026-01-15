from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from sqlalchemy import select, func, or_
from app.deps import get_db
from app.models import Provider, Category, SeriesItem, Season, Episode
from app.schemas import SeriesItemUpdate
from app.xtream_client import xtream_get
from sqlalchemy.exc import IntegrityError

router = APIRouter(prefix="/series", tags=["series"])


@router.get("")
def list_series(
    provider_id: str,
    category_ext_id: int | None = None,
    q: str | None = None,
    limit: int = 50,
    offset: int = 0,
    active_only: bool = True,
    db: Session = Depends(get_db),
):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    stmt = select(SeriesItem).where(SeriesItem.provider_id == p.id)

    if active_only:
        stmt = stmt.where(SeriesItem.is_active == True)

    if category_ext_id is not None:
        cat = db.execute(
            select(Category).where(
                Category.provider_id == p.id,
                Category.cat_type == "series",
                Category.provider_category_id == category_ext_id,
            )
        ).scalar_one_or_none()
        if not cat:
            raise HTTPException(status_code=404, detail="Category not found for this provider")
        stmt = stmt.where(SeriesItem.category_id == cat.id)

    if q:
        qq = f"%{q.strip()}%"
        stmt = stmt.where(or_(SeriesItem.name.ilike(qq), SeriesItem.normalized_name.ilike(qq)))

    total = db.execute(select(func.count()).select_from(stmt.subquery())).scalar_one()
    rows = db.execute(stmt.order_by(SeriesItem.name.asc()).limit(min(limit, 200)).offset(offset)).scalars().all()

    return {
        "total": int(total),
        "items": [
            {
                "id": str(x.id),
                "provider_series_id": x.provider_series_id,
                "name": x.name,
                "normalized_name": x.normalized_name,
                "cover": x.custom_cover_url or x.cover,
                "raw_cover": x.cover,
                "custom_cover_url": x.custom_cover_url,
                "is_active": x.is_active,
                "category_name": x.category.name if x.category else None,
                "category_ext_id": x.category.provider_category_id if x.category else None,
            }
            for x in rows
        ],
    }


@router.get("/{series_id}/info")
def series_info(series_id: str, db: Session = Depends(get_db)):
    s = db.get(SeriesItem, series_id)
    if not s:
        raise HTTPException(status_code=404, detail="Series not found")

    p = db.get(Provider, s.provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    info = xtream_get(p.base_url, p.username, p.password, "get_series_info", series_id=s.provider_series_id)
    return {"id": str(s.id), "provider_series_id": s.provider_series_id, "info": info}


@router.post("/{series_id}/sync_seasons")
def sync_series_seasons(series_id: str, db: Session = Depends(get_db)):
    """
    Sincroniza temporadas y episodios de una serie desde el proveedor Xtream
    y los guarda en la base de datos local.
    """
    s = db.get(SeriesItem, series_id)
    if not s:
        raise HTTPException(status_code=404, detail="Series not found")

    p = db.get(Provider, s.provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    try:
        raw = xtream_get(
            p.base_url,
            p.username,
            p.password,
            "get_series_info",
            series_id=s.provider_series_id,
        ) or {}
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"Error fetching from provider: {exc}") from exc

    seasons_raw = raw.get("seasons") or []
    episodes_raw = raw.get("episodes") or {}

    now = datetime.utcnow()
    seasons_synced = 0
    episodes_synced = 0

    for sea in seasons_raw:
        sn = sea.get("season_number")
        if sn is None:
            continue

        existing_season = db.execute(
            select(Season).where(Season.series_id == s.id, Season.season_number == sn)
        ).scalar_one_or_none()

        if existing_season:
            season = existing_season
            season.name = sea.get("name") or f"Season {sn}"
            season.cover = sea.get("cover") or sea.get("cover_big")
            season.air_date = _parse_date(sea.get("air_date"))
            season.updated_at = now
        else:
            season = Season(
                series_id=s.id,
                season_number=sn,
                name=sea.get("name") or f"Season {sn}",
                cover=sea.get("cover") or sea.get("cover_big"),
                air_date=_parse_date(sea.get("air_date")),
                created_at=now,
                updated_at=now,
            )
            db.add(season)
            db.flush()
            seasons_synced += 1

        key = str(sn)
        eps = episodes_raw.get(key) or episodes_raw.get(sn) or []

        for ep in eps:
            ep_id = ep.get("id") or ep.get("episode_id")
            ep_num = ep.get("episode_num") or ep.get("episode_number")

            if ep_id is None or ep_num is None:
                continue

            existing_ep = db.execute(
                select(Episode).where(
                    Episode.season_id == season.id,
                    Episode.provider_episode_id == int(ep_id),
                )
            ).scalar_one_or_none()

            duration_value = ep.get("duration_secs") or ep.get("duration")

            if existing_ep:
                existing_ep.title = ep.get("title") or ep.get("name") or f"Episode {ep_num}"
                existing_ep.episode_number = int(ep_num)
                existing_ep.container_extension = ep.get("container_extension") or ep.get("container")
                existing_ep.duration_secs = _coerce_int(duration_value)
                existing_ep.info_json = ep
                existing_ep.updated_at = now
            else:
                new_ep = Episode(
                    season_id=season.id,
                    provider_episode_id=int(ep_id),
                    episode_number=int(ep_num),
                    title=ep.get("title") or ep.get("name") or f"Episode {ep_num}",
                    container_extension=ep.get("container_extension") or ep.get("container"),
                    duration_secs=_coerce_int(duration_value),
                    info_json=ep,
                    created_at=now,
                    updated_at=now,
                )
                db.add(new_ep)
                episodes_synced += 1

        season.episode_count = len(eps)
        season.is_synced = True

    db.commit()

    return {
        "ok": True,
        "series_id": str(s.id),
        "seasons_synced": seasons_synced,
        "episodes_synced": episodes_synced,
    }


@router.get("/{series_id}/seasons")
def series_seasons(series_id: str, force_refresh: bool = False, db: Session = Depends(get_db)):
    """
    Obtiene temporadas y episodios de una serie.
    - Si existen en DB local, los devuelve de ahí (rápido)
    - Si no existen o force_refresh=True, los obtiene del proveedor
    """
    s = db.get(SeriesItem, series_id)
    if not s:
        raise HTTPException(status_code=404, detail="Series not found")

    p = db.get(Provider, s.provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    if not force_refresh:
        local_seasons = db.execute(
            select(Season).where(Season.series_id == s.id).order_by(Season.season_number.asc())
        ).scalars().all()

        if local_seasons:
            seasons_out = []
            for sea in local_seasons:
                eps = db.execute(
                    select(Episode)
                    .where(Episode.season_id == sea.id)
                    .order_by(Episode.episode_number.asc())
                ).scalars().all()

                seasons_out.append(
                    {
                        "season_number": sea.season_number,
                        "name": sea.name,
                        "cover": sea.cover,
                        "episode_count": sea.episode_count,
                        "episodes": [
                            {
                                "episode_id": ep.provider_episode_id,
                                "episode_number": ep.episode_number,
                                "title": ep.title,
                                "container_extension": ep.container_extension,
                                "duration_secs": ep.duration_secs,
                            }
                            for ep in eps
                        ],
                    }
                )

            return {
                "series_id": str(s.id),
                "provider_id": str(s.provider_id),
                "provider_series_id": s.provider_series_id,
                "source": "database",
                "seasons": seasons_out,
            }

    try:
        raw = xtream_get(
            p.base_url,
            p.username,
            p.password,
            "get_series_info",
            series_id=s.provider_series_id,
        ) or {}
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"Error fetching from provider: {exc}") from exc

    seasons_raw = raw.get("seasons") or []
    episodes_raw = raw.get("episodes") or {}

    seasons_out = []
    for sea in seasons_raw:
        sn = sea.get("season_number")
        key = str(sn) if sn is not None else None
        eps = []
        if key is not None and key in episodes_raw:
            eps = episodes_raw.get(key) or []
        elif sn is not None and sn in episodes_raw:
            eps = episodes_raw.get(sn) or []

        eps_out = []
        for ep in eps:
            eps_out.append(
                {
                    "episode_id": ep.get("id") or ep.get("episode_id"),
                    "title": ep.get("title") or ep.get("name"),
                    "episode_number": ep.get("episode_num") or ep.get("episode_number"),
                    "container_extension": ep.get("container_extension") or ep.get("container"),
                    "duration_secs": ep.get("duration_secs") or ep.get("duration"),
                }
            )

        seasons_out.append(
            {
                "season_number": sn,
                "name": sea.get("name"),
                "cover": sea.get("cover"),
                "episode_count": len(eps_out),
                "episodes": eps_out,
            }
        )

    if not seasons_out and isinstance(episodes_raw, dict) and episodes_raw:
        for k, eps in episodes_raw.items():
            try:
                sn = int(k)
            except Exception:
                sn = k
            eps_out = []
            for ep in (eps or []):
                eps_out.append(
                    {
                        "episode_id": ep.get("id") or ep.get("episode_id"),
                        "title": ep.get("title") or ep.get("name"),
                        "episode_number": ep.get("episode_num") or ep.get("episode_number"),
                        "container_extension": ep.get("container_extension") or ep.get("container"),
                        "duration_secs": ep.get("duration_secs") or ep.get("duration"),
                    }
                )
            seasons_out.append(
                {
                    "season_number": sn,
                    "name": f"Season {sn}",
                    "cover": None,
                    "episode_count": len(eps_out),
                    "episodes": eps_out,
                }
            )

        seasons_out.sort(
            key=lambda x: (x["season_number"] if isinstance(x["season_number"], int) else 999999)
        )

    return {
        "series_id": str(s.id),
        "provider_id": str(s.provider_id),
        "provider_series_id": s.provider_series_id,
        "source": "provider",
        "seasons": seasons_out,
    }


@router.get("/episode/play")
def series_episode_play(
    provider_id: str,
    episode_id: int,
    format: str = "mp4",
    db: Session = Depends(get_db),
):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    ext = format.lower().strip()
    url = f"{p.base_url.rstrip('/')}/series/{p.username}/{p.password}/{episode_id}.{ext}"
    return {"provider_id": provider_id, "episode_id": episode_id, "url": url}


@router.get("/episode/{episode_id}/tracks")
def series_episode_tracks(
    episode_id: int,
    provider_id: str,
    series_id: str | None = None,
    db: Session = Depends(get_db),
):
    """
    Intenta obtener info de tracks desde Xtream get_series_info.
    Fallback: retorna estructura vacía (ExoPlayer detectará en runtime).
    """
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    series_item = None
    if series_id:
        series_item = db.get(SeriesItem, series_id)
        if not series_item:
            raise HTTPException(status_code=404, detail="Series not found")
        if series_item.provider_id != p.id:
            raise HTTPException(status_code=404, detail="Series not found for provider")

    def _empty_response(note: str):
        return {
            "episode_id": episode_id,
            "provider_id": provider_id,
            "series_id": str(series_item.id) if series_item else None,
            "container": None,
            "audio_tracks": [],
            "subtitle_tracks": [],
            "note": note,
        }

    if not series_item:
        return _empty_response("Info no disponible, ExoPlayer detectará tracks")

    try:
        info = xtream_get(
            p.base_url,
            p.username,
            p.password,
            "get_series_info",
            series_id=series_item.provider_series_id,
        )
        episodes_raw = info.get("episodes") or {}
        target_id = str(episode_id)
        episode_data = None

        if isinstance(episodes_raw, dict):
            for eps in episodes_raw.values():
                for ep in eps or []:
                    ep_id = ep.get("id") or ep.get("episode_id")
                    if ep_id is not None and str(ep_id) == target_id:
                        episode_data = ep
                        break
                if episode_data:
                    break
        elif isinstance(episodes_raw, list):
            for ep in episodes_raw:
                ep_id = ep.get("id") or ep.get("episode_id")
                if ep_id is not None and str(ep_id) == target_id:
                    episode_data = ep
                    break

        if not episode_data:
            return _empty_response("Info no disponible, ExoPlayer detectará tracks")

        return {
            "episode_id": episode_id,
            "provider_id": provider_id,
            "series_id": str(series_item.id),
            "container": episode_data.get("container_extension") or episode_data.get("container"),
            "audio_tracks": episode_data.get("audio", []),
            "subtitle_tracks": episode_data.get("subtitles", []),
            "duration_secs": episode_data.get("duration_secs"),
            "note": "Tracks finales detectados por ExoPlayer al reproducir",
        }
    except Exception:
        return _empty_response("Info no disponible, ExoPlayer detectará tracks")


@router.patch("/{series_id}")
def update_series(series_id: str, payload: SeriesItemUpdate, db: Session = Depends(get_db)):
    s = db.get(SeriesItem, series_id)
    if not s:
        raise HTTPException(status_code=404, detail="Series not found")

    data = payload.dict(exclude_unset=True)

    reset_tmdb = False

    if "normalized_name" in data:
        new_name = (data["normalized_name"] or "").strip() or None
        if new_name != s.normalized_name:
            s.normalized_name = new_name
            reset_tmdb = True

    if reset_tmdb:
        s.tmdb_id = None
        s.tmdb_status = "missing"
        s.tmdb_last_sync = None
        s.tmdb_error = None
        s.tmdb_title = None
        s.tmdb_overview = None
        s.tmdb_release_date = None
        s.tmdb_genres = None
        s.tmdb_vote_average = None
        s.tmdb_poster_path = None
        s.tmdb_backdrop_path = None
        s.tmdb_raw = None

    if "custom_cover_url" in data:
        v = (data["custom_cover_url"] or "").strip()
        s.custom_cover_url = v or None

    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(status_code=409, detail="Integrity error")

    db.refresh(s)
    return {
        "id": str(s.id),
        "provider_series_id": s.provider_series_id,
        "name": s.name,
        "normalized_name": s.normalized_name,
        "custom_cover_url": s.custom_cover_url,
    }

@router.get("/all")
def list_series_all(
    q: str | None = None,
    limit: int = 60,
    offset: int = 0,
    active_only: bool = True,
    db: Session = Depends(get_db),
):
    """
    Lista Series de TODOS los providers activos (modo Media Server).
    """
    stmt = (
        select(SeriesItem)
        .join(Provider, Provider.id == SeriesItem.provider_id)
        .where(Provider.is_active == True)
    )

    if active_only:
        stmt = stmt.where(SeriesItem.is_active == True)

    if q:
        qq = f"%{q.strip()}%"
        stmt = stmt.where(or_(SeriesItem.name.ilike(qq), SeriesItem.normalized_name.ilike(qq)))

    total = db.execute(select(func.count()).select_from(stmt.subquery())).scalar_one()

    rows = (
        db.execute(
            stmt.order_by(SeriesItem.name.asc())
            .limit(min(max(limit, 1), 200))
            .offset(offset)
        )
        .scalars()
        .all()
    )

    return {
        "total": int(total),
        "items": [
            {
                "id": str(x.id),
                "provider_id": str(x.provider_id),
                "provider_name": x.provider.name if x.provider else None,

                "provider_series_id": x.provider_series_id,
                "name": x.name,
                "normalized_name": x.normalized_name,

                "cover": x.custom_cover_url or x.cover,
                "raw_cover": x.cover,
                "custom_cover_url": x.custom_cover_url,

                "is_active": x.is_active,

                "category_name": x.category.name if x.category else None,
                "category_ext_id": x.category.provider_category_id if x.category else None,
            }
            for x in rows
        ],
    }

@router.get("/{series_id}")
def series_detail(series_id: str, db: Session = Depends(get_db)):
    s = db.get(SeriesItem, series_id)
    if not s:
        raise HTTPException(status_code=404, detail="Series not found")

    p = db.get(Provider, s.provider_id)
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
    tmdb_networks = []
    tmdb_cast = []
    tmdb_trailer = None

    raw = s.tmdb_raw or {}
    try:
      # TV suele traer episode_run_time como lista
      ert = raw.get("episode_run_time")
      if isinstance(ert, list) and ert:
        tmdb_runtime = ert[0]
      elif raw.get("runtime") is not None:
        tmdb_runtime = raw.get("runtime")

      nets = raw.get("networks") or []
      tmdb_networks = [n.get("name") for n in nets if n.get("name")]

      credits = raw.get("credits") or {}
      cast = credits.get("cast") or []
      tmdb_cast = [
        {"name": c.get("name"), "character": c.get("character"), "profile_path": c.get("profile_path")}
        for c in cast[:10] if c.get("name")
      ]

      videos = (raw.get("videos") or {}).get("results") or []
      trailer = next((v for v in videos if v.get("type") == "Trailer" and v.get("site") == "YouTube" and v.get("key")), None)
      if trailer:
        tmdb_trailer = {"site": trailer.get("site"), "key": trailer.get("key"), "name": trailer.get("name")}
    except Exception:
      pass


    return {
        "id": str(s.id),
        "provider_id": str(s.provider_id),
        "provider_name": p.name if p else None,

        "provider_series_id": s.provider_series_id,
        "name": s.name,
        "normalized_name": s.normalized_name,

        "cover": s.custom_cover_url or s.cover,
        "raw_cover": s.cover,
        "custom_cover_url": s.custom_cover_url,

        "is_active": s.is_active,

        "category_ext_id": s.category.provider_category_id if s.category else None,
        "category_name": s.category.name if s.category else None,

        # TMDB
        "tmdb_id": s.tmdb_id,
        "tmdb_status": s.tmdb_status,
        "tmdb_last_sync": s.tmdb_last_sync.isoformat() if s.tmdb_last_sync else None,
        "tmdb_error": s.tmdb_error,

        "tmdb_runtime": tmdb_runtime,
        "tmdb_networks": tmdb_networks,
        "tmdb_cast": tmdb_cast,
        "tmdb_trailer": tmdb_trailer,

        "tmdb_title": s.tmdb_title,
        "tmdb_overview": s.tmdb_overview,
        "tmdb_release_date": iso_date(s.tmdb_release_date),
        "tmdb_genres": s.tmdb_genres,
        "tmdb_vote_average": s.tmdb_vote_average,

        "tmdb_poster_path": s.tmdb_poster_path,
        "tmdb_backdrop_path": s.tmdb_backdrop_path,
    }


def _parse_date(val):
    """Helper para parsear fechas del proveedor."""
    if not val:
        return None
    try:
        if isinstance(val, str):
            for fmt in ["%Y-%m-%d", "%Y/%m/%d", "%d-%m-%Y"]:
                try:
                    return datetime.strptime(val, fmt)
                except ValueError:
                    continue
        return None
    except Exception:
        return None


def _coerce_int(val):
    if val is None or val == "":
        return None
    try:
        return int(val)
    except (TypeError, ValueError):
        return None
