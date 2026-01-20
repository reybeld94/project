from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from app.deps import get_db
from app.models import Category, LiveStream, Provider, ProviderUser, SeriesItem, VodStream
from app.provider_auto_sync import get_or_create_provider_auto_sync, update_provider_auto_sync
from app.schemas import ProviderAutoSyncConfigOut, ProviderAutoSyncConfigUpdate, ProviderCreate, ProviderOut, ProviderUpdate
from app.xtream_client import XtreamError, xtream_get


router = APIRouter(prefix="/providers", tags=["providers"])


def _provider_out(db: Session, provider: Provider) -> ProviderOut:
    cfg = get_or_create_provider_auto_sync(db, provider.id)
    return ProviderOut(
        id=provider.id,
        name=provider.name,
        base_url=provider.base_url,
        username=provider.username,
        is_active=provider.is_active,
        auto_sync_interval_minutes=cfg.interval_minutes,
    )


def _get_sync_credentials(db: Session, provider: Provider) -> tuple[str, str]:
    """
    Get credentials for sync operations (catalog sync, test, etc).

    Priority:
    1. Use ADMIN user if exists and is enabled
    2. Fall back to provider's own credentials (legacy)

    Returns:
        tuple[str, str]: (username, password)

    Raises:
        HTTPException: If no valid credentials found
    """
    # Try to get ADMIN user from this provider
    admin_user = db.execute(
        select(ProviderUser).where(
            ProviderUser.provider_id == provider.id,
            ProviderUser.alias == "ADMIN",
            ProviderUser.is_enabled == True,
        )
    ).scalar_one_or_none()

    if admin_user:
        return admin_user.username, admin_user.password

    # Fall back to provider credentials (legacy)
    if provider.username and provider.password:
        return provider.username, provider.password

    raise HTTPException(
        status_code=400,
        detail="No credentials available for sync. Please create an ADMIN user or set provider credentials."
    )


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
    return _provider_out(db, p)

@router.get("", response_model=list[ProviderOut])
def list_providers(db: Session = Depends(get_db)):
    providers = db.execute(select(Provider).order_by(Provider.created_at.desc())).scalars().all()
    return [_provider_out(db, provider) for provider in providers]

@router.get("/{provider_id}", response_model=ProviderOut)
def get_provider(provider_id: str, db: Session = Depends(get_db)):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")
    return _provider_out(db, p)

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
        p.updated_at = datetime.now(timezone.utc)
        db.commit()
        db.refresh(p)

    return _provider_out(db, p)


@router.get("/{provider_id}/auto-sync", response_model=ProviderAutoSyncConfigOut)
def get_provider_auto_sync(provider_id: str, db: Session = Depends(get_db)):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")
    cfg = get_or_create_provider_auto_sync(db, p.id)
    return ProviderAutoSyncConfigOut(
        provider_id=cfg.provider_id,
        interval_minutes=cfg.interval_minutes,
        last_run_at=cfg.last_run_at,
    )


@router.patch("/{provider_id}/auto-sync", response_model=ProviderAutoSyncConfigOut)
def update_provider_auto_sync_config(
    provider_id: str,
    payload: ProviderAutoSyncConfigUpdate,
    db: Session = Depends(get_db),
):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")
    interval_minutes = payload.interval_minutes
    if interval_minutes is None:
        cfg = get_or_create_provider_auto_sync(db, p.id)
    else:
        cfg = update_provider_auto_sync(db, p.id, interval_minutes)
    return ProviderAutoSyncConfigOut(
        provider_id=cfg.provider_id,
        interval_minutes=cfg.interval_minutes,
        last_run_at=cfg.last_run_at,
    )


@router.get("/{provider_id}/test")
def test_provider(provider_id: str, db: Session = Depends(get_db)):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    username, password = _get_sync_credentials(db, p)

    try:
        data = xtream_get(p.base_url, username, password, "get_live_categories")
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
                existing.updated_at = datetime.now(timezone.utc)
                changed += 1
        else:
            db.add(Category(
                provider_id=provider.id,
                cat_type=cat_type,
                provider_category_id=ext_id,
                name=name,
                is_active=True,
                updated_at=datetime.now(timezone.utc),
            ))
            changed += 1

    # desactiva las que ya no vienen
    existing_all = db.execute(
        select(Category).where(Category.provider_id == provider.id, Category.cat_type == cat_type)
    ).scalars().all()

    for c in existing_all:
        if c.provider_category_id not in seen and c.is_active:
            c.is_active = False
            c.updated_at = datetime.now(timezone.utc)
            changed += 1

    return changed


def _sync_vod_streams_for_provider(
    db: Session,
    provider: Provider,
    include_inactive_categories: bool = True,
    deactivate_missing: bool = False,
) -> dict:
    started = datetime.now(timezone.utc)
    username, password = _get_sync_credentials(db, provider)

    try:
        vod_cats = xtream_get(provider.base_url, username, password, "get_vod_categories")
        if isinstance(vod_cats, list):
            _sync_one_category_set(db, provider, "vod", vod_cats)
            db.commit()
    except Exception:
        pass

    result = {
        "categories": 0,
        "total_streams": 0,
        "changed": 0,
        "details": [],
        "started_at": started.isoformat() + "Z",
        "finished_at": None,
        "seconds": None,
        "deactivate_missing": deactivate_missing,
    }

    q = select(Category).where(
        Category.provider_id == provider.id,
        Category.cat_type == "vod",
    )
    if not include_inactive_categories:
        q = q.where(Category.is_active == True)

    cats = db.execute(q.order_by(Category.name.asc())).scalars().all()
    result["categories"] = len(cats)

    for cat in cats:
        try:
            raw = xtream_get(
                provider.base_url,
                username,
                password,
                "get_vod_streams",
                timeout=120.0,
                category_id=cat.provider_category_id,
            )
        except Exception as e:
            result["details"].append({
                "category_ext_id": cat.provider_category_id,
                "category_name": cat.name,
                "error": str(e),
            })
            continue

        if not isinstance(raw, list):
            result["details"].append({
                "category_ext_id": cat.provider_category_id,
                "category_name": cat.name,
                "error": "Unexpected format (expected list)",
            })
            continue

        seen = set()
        changed = 0
        now = datetime.now(timezone.utc)

        def _parse_tmdb_id(raw_value) -> int | None:
            try:
                parsed = int(raw_value)
            except Exception:
                return None
            return parsed if parsed > 0 else None

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
            tmdb_id = _parse_tmdb_id(item.get("tmdb_id") or item.get("tmdb"))

            seen.add(ext_stream_id)

            existing = db.execute(
                select(VodStream)
                .where(
                    VodStream.provider_id == provider.id,
                    VodStream.provider_stream_id == ext_stream_id,
                )
                .order_by(VodStream.created_at.desc(), VodStream.id.desc())
            ).scalars().all()

            if not existing and tmdb_id is not None:
                existing = db.execute(
                    select(VodStream)
                    .where(VodStream.provider_id == provider.id, VodStream.tmdb_id == tmdb_id)
                    .order_by(VodStream.created_at.desc(), VodStream.id.desc())
                ).scalars().all()

            if existing:
                current = existing[0]
                if (
                    current.name != name
                    or current.stream_icon != icon
                    or current.category_id != cat.id
                    or current.container_extension != container_ext
                    or current.rating != rating
                    or current.added != added
                    or current.provider_stream_id != ext_stream_id
                    or current.is_active is False
                ):
                    current.name = name
                    current.stream_icon = icon
                    current.category_id = cat.id
                    current.container_extension = container_ext
                    current.rating = rating
                    current.added = added
                    current.provider_stream_id = ext_stream_id
                    current.is_active = True
                    current.updated_at = now
                    changed += 1
            else:
                db.add(VodStream(
                    provider_id=provider.id,
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

        if deactivate_missing:
            existing_in_cat = db.execute(
                select(VodStream).where(
                    VodStream.provider_id == provider.id,
                    VodStream.category_id == cat.id,
                    VodStream.is_active == True,
                )
            ).scalars().all()

            for s in existing_in_cat:
                if s.provider_stream_id not in seen:
                    s.is_active = False
                    s.updated_at = now
                    changed += 1

        if seen:
            dup_rows = db.execute(
                select(VodStream)
                .where(
                    VodStream.provider_id == provider.id,
                    VodStream.provider_stream_id.in_(seen),
                )
                .order_by(
                    VodStream.provider_stream_id.asc(),
                    VodStream.created_at.desc(),
                    VodStream.id.desc(),
                )
            ).scalars().all()

            grouped: dict[int, list[VodStream]] = {}
            for row in dup_rows:
                grouped.setdefault(row.provider_stream_id, []).append(row)

            for stream_id, group in grouped.items():
                if len(group) < 2:
                    continue
                winner = group[0]
                synced_donor = next((item for item in group if item.tmdb_status == "synced"), None)
                if winner.tmdb_status != "synced" and synced_donor:
                    _copy_tmdb_fields(winner, synced_donor)
                    winner.tmdb_status = "synced"
                    winner.tmdb_error = None
                    winner.updated_at = now
                    changed += 1
                for dup in group[1:]:
                    db.delete(dup)
                    changed += 1

        db.commit()

        result["total_streams"] += len(raw)
        result["changed"] += changed
        result["details"].append({
            "category_ext_id": cat.provider_category_id,
            "category_name": cat.name,
            "count": len(raw),
            "changed": changed,
        })

    finished = datetime.now(timezone.utc)
    result["finished_at"] = finished.isoformat() + "Z"
    result["seconds"] = (finished - started).total_seconds()
    return result


def _sync_series_items_for_provider(
    db: Session,
    provider: Provider,
    include_inactive_categories: bool = True,
) -> dict:
    started = datetime.now(timezone.utc)
    username, password = _get_sync_credentials(db, provider)

    try:
        series_cats = xtream_get(provider.base_url, username, password, "get_series_categories")
        if isinstance(series_cats, list):
            _sync_one_category_set(db, provider, "series", series_cats)
            db.commit()
    except Exception:
        pass

    result = {
        "categories": 0,
        "total_items": 0,
        "changed": 0,
        "details": [],
        "started_at": started.isoformat() + "Z",
        "finished_at": None,
        "seconds": None,
    }

    q = select(Category).where(
        Category.provider_id == provider.id,
        Category.cat_type == "series",
    )
    if not include_inactive_categories:
        q = q.where(Category.is_active == True)

    cats = db.execute(q.order_by(Category.name.asc())).scalars().all()
    result["categories"] = len(cats)

    for cat in cats:
        try:
            raw = xtream_get(
                provider.base_url,
                username,
                password,
                "get_series",
                category_id=cat.provider_category_id,
            )
        except Exception as e:
            result["details"].append({
                "category_ext_id": cat.provider_category_id,
                "category_name": cat.name,
                "error": str(e),
            })
            continue

        if not isinstance(raw, list):
            result["details"].append({
                "category_ext_id": cat.provider_category_id,
                "category_name": cat.name,
                "error": "Unexpected format (expected list)",
            })
            continue

        seen = set()
        changed = 0
        now = datetime.now(timezone.utc)

        for item in raw:
            try:
                ext_id = int(item.get("series_id"))
            except Exception:
                continue

            name = (item.get("name") or "").strip() or f"Series {ext_id}"
            cover = item.get("cover") or item.get("stream_icon") or None

            seen.add(ext_id)

            existing = db.execute(
                select(SeriesItem).where(
                    SeriesItem.provider_id == provider.id,
                    SeriesItem.provider_series_id == ext_id,
                )
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
                    provider_id=provider.id,
                    category_id=cat.id,
                    provider_series_id=ext_id,
                    name=name,
                    cover=cover,
                    is_active=True,
                    updated_at=now,
                ))
                changed += 1

        existing_in_cat = db.execute(
            select(SeriesItem).where(
                SeriesItem.provider_id == provider.id,
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

        result["total_items"] += len(raw)
        result["changed"] += changed
        result["details"].append({
            "category_ext_id": cat.provider_category_id,
            "category_name": cat.name,
            "count": len(raw),
            "changed": changed,
        })

    finished = datetime.now(timezone.utc)
    result["finished_at"] = finished.isoformat() + "Z"
    result["seconds"] = (finished - started).total_seconds()
    return result

@router.post("/{provider_id}/sync/categories")
def sync_categories(provider_id: str, db: Session = Depends(get_db)):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    username, password = _get_sync_credentials(db, p)

    try:
        live = xtream_get(p.base_url, username, password, "get_live_categories")
        vod = xtream_get(p.base_url, username, password, "get_vod_categories")
        series = xtream_get(p.base_url, username, password, "get_series_categories")
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

    username, password = _get_sync_credentials(db, p)

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
        raw = xtream_get(p.base_url, username, password, "get_live_streams", category_id=category_ext_id)
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

        now = datetime.now(timezone.utc)

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

    now = datetime.now(timezone.utc)
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
    vod: bool = True,
    series: bool = True,
    include_inactive_categories: bool = False,
    db: Session = Depends(get_db),
):
    """
    One-click sync:
    - (asume que YA tienes categories sincronizadas)
    - recorre todas las categories y hace sync de streams/items
    """
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    username, password = _get_sync_credentials(db, p)
    started = datetime.now(timezone.utc)

    result = {
        "ok": True,
        "provider_id": provider_id,
        "live": {"categories": 0, "total_streams": 0, "changed": 0, "details": []},
        "vod": {"categories": 0, "total_streams": 0, "changed": 0, "details": []},
        "series": {"categories": 0, "total_items": 0, "changed": 0, "details": []},
        "started_at": started.isoformat() + "Z",
        "finished_at": None,
        "seconds": None,
    }

    if live:
        q = select(Category).where(
            Category.provider_id == p.id,
            Category.cat_type == "live",
        )
        if not include_inactive_categories:
            q = q.where(Category.is_active == True)

        cats = db.execute(q.order_by(Category.name.asc())).scalars().all()
        result["live"]["categories"] = len(cats)

        for cat in cats:
            raw = xtream_get(
                p.base_url, username, password,
                "get_live_streams",
                category_id=cat.provider_category_id
            )

            if not isinstance(raw, list):
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

                now = datetime.now(timezone.utc)

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

            existing_in_cat = db.execute(
                select(LiveStream).where(
                    LiveStream.provider_id == p.id,
                    LiveStream.category_id == cat.id,
                    LiveStream.is_active == True,
                )
            ).scalars().all()

            now = datetime.now(timezone.utc)
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

    if vod:
        vod_result = _sync_vod_streams_for_provider(
            db,
            p,
            include_inactive_categories=include_inactive_categories,
            deactivate_missing=False,
        )
        result["vod"] = {
            "categories": vod_result["categories"],
            "total_streams": vod_result["total_streams"],
            "changed": vod_result["changed"],
            "details": vod_result["details"],
        }

    if series:
        series_result = _sync_series_items_for_provider(
            db,
            p,
            include_inactive_categories=include_inactive_categories,
        )
        result["series"] = {
            "categories": series_result["categories"],
            "total_items": series_result["total_items"],
            "changed": series_result["changed"],
            "details": series_result["details"],
        }

    finished = datetime.now(timezone.utc)
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

    vod_result = _sync_vod_streams_for_provider(
        db,
        p,
        include_inactive_categories=include_inactive_categories,
        deactivate_missing=deactivate_missing,
    )

    return {
        "ok": True,
        "provider_id": provider_id,
        "vod": {
            "categories": vod_result["categories"],
            "total_streams": vod_result["total_streams"],
            "changed": vod_result["changed"],
            "details": vod_result["details"],
        },
        "started_at": vod_result["started_at"],
        "finished_at": vod_result["finished_at"],
        "seconds": vod_result["seconds"],
        "deactivate_missing": vod_result["deactivate_missing"],
    }


@router.post("/{provider_id}/sync/series_items")
def sync_series_items(provider_id: str, category_ext_id: int, db: Session = Depends(get_db)):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    username, password = _get_sync_credentials(db, p)

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
        raw = xtream_get(p.base_url, username, password, "get_series", category_id=category_ext_id)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Xtream sync failed: {e}")

    if not isinstance(raw, list):
        raise HTTPException(status_code=400, detail="Xtream returned unexpected data format (expected list)")

    seen = set()
    changed = 0
    now = datetime.now(timezone.utc)

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
