from __future__ import annotations

import json
import logging
import threading
import uuid
from datetime import datetime, timedelta

import httpx
from fastapi import APIRouter, BackgroundTasks, Body, Depends, HTTPException
from sqlalchemy import or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.db import SessionLocal
from app.deps import get_db
from app.models import TmdbCollection, TmdbCollectionCache
from app.routers.tmdb import get_or_create_cfg
from app.schemas import (
    CollectionCacheOut,
    CollectionCreate,
    CollectionFilters,
    CollectionOut,
    CollectionPreviewIn,
    CollectionPreviewOut,
    CollectionUpdate,
)
from app.tmdb_client import RateLimiter, fetch_discover, fetch_tmdb_list, fetch_trending, tmdb_get_json

router = APIRouter(prefix="/collections", tags=["collections"])

DEFAULT_CACHE_TTL_SECONDS = 3600
ALLOWED_COLLECTION_SOURCES = {"trending", "list", "discover", "collection"}
log = logging.getLogger(__name__)

_cache_metrics = {"hits": 0, "misses": 0, "expired": 0, "tmdb_errors": 0}
_metrics_lock = threading.Lock()


def _increment_metric(metric: str) -> None:
    with _metrics_lock:
        _cache_metrics[metric] = _cache_metrics.get(metric, 0) + 1
        snapshot = dict(_cache_metrics)
    log.info(
        "collections cache metrics: hits=%s misses=%s expired=%s tmdb_errors=%s",
        snapshot.get("hits", 0),
        snapshot.get("misses", 0),
        snapshot.get("expired", 0),
        snapshot.get("tmdb_errors", 0),
    )


def _resolve_cache_ttl(collection: TmdbCollection) -> int:
    ttl = collection.cache_ttl_seconds
    if not ttl or ttl <= 0:
        ttl = DEFAULT_CACHE_TTL_SECONDS
    return int(ttl)


def _upsert_cache_entry(
    db: Session,
    *,
    collection: TmdbCollection,
    page: int,
    payload: dict,
    now: datetime,
) -> TmdbCollectionCache:
    expires_at = now + timedelta(seconds=_resolve_cache_ttl(collection))
    cache = db.execute(
        select(TmdbCollectionCache)
        .where(TmdbCollectionCache.collection_id == collection.id)
        .where(TmdbCollectionCache.page == page)
    ).scalar_one_or_none()
    if cache:
        cache.payload = payload
        cache.expires_at = expires_at
        cache.updated_at = now
    else:
        cache = TmdbCollectionCache(
            collection_id=collection.id,
            page=page,
            payload=payload,
            expires_at=expires_at,
            created_at=now,
            updated_at=now,
        )
        db.add(cache)
    return cache


def _refresh_cache_entry(collection_id: uuid.UUID, page: int) -> None:
    db = SessionLocal()
    try:
        collection = db.get(TmdbCollection, collection_id)
        if not collection or not collection.enabled:
            return
        payload = _resolve_tmdb_payload(
            source_type=collection.source_type,
            source_id=collection.source_id,
            filters=collection.filters,
            page=page,
            db=db,
        )
        now = datetime.utcnow()
        _upsert_cache_entry(db, collection=collection, page=page, payload=payload, now=now)
        db.commit()
    except HTTPException as exc:
        _increment_metric("tmdb_errors")
        log.warning(
            "TMDB refresh failed for collection_id=%s page=%s: %s",
            collection_id,
            page,
            exc.detail,
        )
        db.rollback()
    except Exception:
        _increment_metric("tmdb_errors")
        log.exception(
            "TMDB refresh failed for collection_id=%s page=%s due to unexpected error",
            collection_id,
            page,
        )
        db.rollback()
    finally:
        db.close()


def refresh_expired_collection_caches() -> dict:
    db = SessionLocal()
    refreshed = 0
    failed = 0
    now = datetime.utcnow()
    try:
        caches = db.execute(
            select(TmdbCollectionCache)
            .join(TmdbCollection, TmdbCollection.id == TmdbCollectionCache.collection_id)
            .where(TmdbCollection.enabled == True)
            .where(TmdbCollectionCache.expires_at <= now)
        ).scalars().all()
        for cache in caches:
            try:
                payload = _resolve_tmdb_payload(
                    source_type=cache.collection.source_type,
                    source_id=cache.collection.source_id,
                    filters=cache.collection.filters,
                    page=cache.page,
                    db=db,
                )
                _upsert_cache_entry(
                    db,
                    collection=cache.collection,
                    page=cache.page,
                    payload=payload,
                    now=datetime.utcnow(),
                )
                refreshed += 1
            except HTTPException as exc:
                failed += 1
                _increment_metric("tmdb_errors")
                log.warning(
                    "TMDB refresh job failed for collection_id=%s page=%s: %s",
                    cache.collection_id,
                    cache.page,
                    exc.detail,
                )
        db.commit()
    except Exception:
        failed += 1
        _increment_metric("tmdb_errors")
        log.exception("TMDB refresh job failed due to unexpected error")
        db.rollback()
    finally:
        db.close()
    return {"refreshed": refreshed, "failed": failed}


def _normalize_filters(filters: CollectionFilters | None) -> dict | None:
    if not filters:
        return None
    data = filters.dict(exclude_none=True)
    return data or None


def _get_collection_by_identifier(db: Session, identifier: str) -> TmdbCollection:
    collection = None
    try:
        uuid_value = uuid.UUID(identifier)
    except ValueError:
        uuid_value = None

    if uuid_value:
        collection = db.get(TmdbCollection, uuid_value)

    if not collection:
        collection = db.execute(
            select(TmdbCollection).where(TmdbCollection.slug == identifier)
        ).scalar_one_or_none()

    if not collection:
        raise HTTPException(status_code=404, detail="Collection not found")

    return collection


def _get_tmdb_credentials(db: Session):
    cfg = get_or_create_cfg(db)
    if not cfg.is_enabled:
        raise HTTPException(status_code=400, detail="TMDB is disabled in settings")

    token = cfg.read_access_token
    api_key = cfg.api_key
    if not token and not api_key:
        raise HTTPException(status_code=400, detail="Missing TMDB credentials (token or api_key)")

    return cfg, token, api_key


def _resolve_tmdb_payload(
    *,
    source_type: str,
    source_id: int | None,
    filters: dict | None,
    page: int,
    db: Session,
) -> dict:
    if source_type not in ALLOWED_COLLECTION_SOURCES:
        raise HTTPException(
            status_code=400,
            detail=f"source_type inválido. Valores permitidos: {sorted(ALLOWED_COLLECTION_SOURCES)}",
        )

    cfg, token, api_key = _get_tmdb_credentials(db)

    normalized_filters = dict(filters or {})
    language = normalized_filters.pop("language", None)
    region = normalized_filters.pop("region", None)
    kind = normalized_filters.get("kind")

    if source_type == "trending":
        time_window = normalized_filters.get("time_window") or "day"
        return fetch_trending(
            kind or "all",
            time_window,
            token=token,
            api_key=api_key,
            language=language,
            region=region,
            page=page,
            config=cfg,
        )

    if source_type == "list":
        list_key = normalized_filters.get("list_key")
        if not kind:
            raise HTTPException(status_code=400, detail="list requiere filters.kind")
        if not list_key:
            raise HTTPException(status_code=400, detail="list requiere filters.list_key")
        return fetch_tmdb_list(
            kind,
            list_key,
            token=token,
            api_key=api_key,
            language=language,
            region=region,
            page=page,
            config=cfg,
        )

    if source_type == "discover":
        if not kind:
            raise HTTPException(status_code=400, detail="discover requiere filters.kind")
        return fetch_discover(
            kind,
            normalized_filters.get("filters"),
            sort_by=normalized_filters.get("sort_by"),
            token=token,
            api_key=api_key,
            language=language,
            region=region,
            page=page,
            config=cfg,
        )

    if not source_id:
        raise HTTPException(status_code=400, detail="collection requiere source_id")
    if page != 1:
        raise HTTPException(status_code=400, detail="collection solo soporta page=1")

    limiter = RateLimiter(rps=cfg.requests_per_second or 5)
    params = {"language": (language or cfg.language or "en-US").strip()}
    with httpx.Client(timeout=20) as client:
        return tmdb_get_json(
            client,
            limiter,
            f"/collection/{source_id}",
            token=token,
            api_key=api_key,
            params=params,
        )


@router.get("", response_model=list[CollectionOut])
def list_collections(
    q: str | None = None,
    enabled: bool | None = None,
    source_type: str | None = None,
    limit: int = 50,
    offset: int = 0,
    db: Session = Depends(get_db),
):
    stmt = select(TmdbCollection)

    if enabled is not None:
        stmt = stmt.where(TmdbCollection.enabled == enabled)

    if source_type:
        stmt = stmt.where(TmdbCollection.source_type == source_type)

    if q:
        qq = f"%{q.strip()}%"
        stmt = stmt.where(or_(TmdbCollection.name.ilike(qq), TmdbCollection.slug.ilike(qq)))

    stmt = stmt.order_by(TmdbCollection.order_index.asc(), TmdbCollection.created_at.desc())
    rows = db.execute(stmt.limit(min(limit, 200)).offset(offset)).scalars().all()
    return rows


@router.post("", response_model=CollectionOut)
def create_collection(payload: CollectionCreate, db: Session = Depends(get_db)):
    filters = _normalize_filters(payload.filters)

    collection = TmdbCollection(
        name=payload.name.strip(),
        slug=payload.slug.strip(),
        source_type=payload.source_type,
        source_id=payload.source_id,
        filters=filters,
        cache_ttl_seconds=payload.cache_ttl_seconds,
        enabled=payload.enabled,
        order_index=payload.order_index,
        created_at=datetime.utcnow(),
        updated_at=datetime.utcnow(),
    )

    db.add(collection)
    try:
        db.commit()
    except IntegrityError as exc:
        db.rollback()
        raise HTTPException(status_code=400, detail="Collection slug already exists") from exc

    db.refresh(collection)
    return collection


@router.get("/preview", response_model=CollectionPreviewOut)
def preview_collection(
    source_type: str | None = None,
    source_id: int | None = None,
    kind: str | None = None,
    time_window: str | None = None,
    list_key: str | None = None,
    sort_by: str | None = None,
    language: str | None = None,
    region: str | None = None,
    filters_json: str | None = None,
    page: int = 1,
    body: CollectionPreviewIn | None = Body(default=None),
    db: Session = Depends(get_db),
):
    filters: dict = {}

    if body:
        source_type = body.source_type or source_type
        source_id = body.source_id or source_id
        filters.update(_normalize_filters(body.filters) or {})

    if filters_json:
        try:
            filters["filters"] = json.loads(filters_json)
        except json.JSONDecodeError as exc:
            raise HTTPException(status_code=400, detail="filters_json debe ser JSON válido") from exc

    if kind:
        filters["kind"] = kind
    if time_window:
        filters["time_window"] = time_window
    if list_key:
        filters["list_key"] = list_key
    if sort_by:
        filters["sort_by"] = sort_by
    if language:
        filters["language"] = language
    if region:
        filters["region"] = region

    if not source_type:
        raise HTTPException(status_code=400, detail="source_type es requerido")

    page = max(1, int(page or 1))
    payload = _resolve_tmdb_payload(
        source_type=source_type,
        source_id=source_id,
        filters=filters or None,
        page=page,
        db=db,
    )

    return {
        "source_type": source_type,
        "source_id": source_id,
        "filters": filters or None,
        "page": page,
        "payload": payload,
    }


@router.post("/preview", response_model=CollectionPreviewOut)
def preview_collection_post(
    body: CollectionPreviewIn,
    db: Session = Depends(get_db),
):
    return preview_collection(body=body, db=db)


@router.get("/{collection_id_or_slug}", response_model=CollectionOut)
def get_collection(collection_id_or_slug: str, db: Session = Depends(get_db)):
    return _get_collection_by_identifier(db, collection_id_or_slug)


@router.patch("/{collection_id_or_slug}", response_model=CollectionOut)
def update_collection(
    collection_id_or_slug: str,
    payload: CollectionUpdate,
    db: Session = Depends(get_db),
):
    collection = _get_collection_by_identifier(db, collection_id_or_slug)

    data = payload.dict(exclude_unset=True, exclude={"filters"})
    filters = payload.filters

    if "name" in data:
        collection.name = (data["name"] or "").strip() or collection.name

    if "slug" in data:
        collection.slug = (data["slug"] or "").strip() or collection.slug

    if "source_type" in data:
        collection.source_type = data["source_type"]

    if "source_id" in data:
        collection.source_id = data["source_id"]

    if filters is not None:
        collection.filters = _normalize_filters(filters)

    if "cache_ttl_seconds" in data:
        collection.cache_ttl_seconds = data["cache_ttl_seconds"]

    if "enabled" in data:
        collection.enabled = bool(data["enabled"])

    if "order_index" in data:
        collection.order_index = int(data["order_index"] or 0)

    collection.updated_at = datetime.utcnow()

    try:
        db.commit()
    except IntegrityError as exc:
        db.rollback()
        raise HTTPException(status_code=400, detail="Collection slug already exists") from exc

    db.refresh(collection)
    return collection


@router.delete("/{collection_id_or_slug}")
def delete_collection(collection_id_or_slug: str, db: Session = Depends(get_db)):
    collection = _get_collection_by_identifier(db, collection_id_or_slug)
    db.delete(collection)
    db.commit()
    return {"ok": True, "id": str(collection.id)}


@router.get("/{collection_id_or_slug}/items", response_model=CollectionCacheOut)
def collection_items(
    collection_id_or_slug: str,
    page: int = 1,
    stale_while_revalidate: bool = False,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
):
    collection = _get_collection_by_identifier(db, collection_id_or_slug)
    if not collection.enabled:
        raise HTTPException(status_code=400, detail="Collection is disabled")

    page = max(1, int(page or 1))
    now = datetime.utcnow()

    cache = db.execute(
        select(TmdbCollectionCache)
        .where(TmdbCollectionCache.collection_id == collection.id)
        .where(TmdbCollectionCache.page == page)
    ).scalar_one_or_none()

    if cache and cache.expires_at > now:
        _increment_metric("hits")
        return {
            "collection_id": collection.id,
            "page": cache.page,
            "payload": cache.payload,
            "expires_at": cache.expires_at,
            "cached": True,
            "stale": False,
        }

    if cache:
        _increment_metric("expired")
        if stale_while_revalidate:
            if background_tasks:
                background_tasks.add_task(_refresh_cache_entry, collection.id, page)
            return {
                "collection_id": collection.id,
                "page": cache.page,
                "payload": cache.payload,
                "expires_at": cache.expires_at,
                "cached": True,
                "stale": True,
            }
    else:
        _increment_metric("misses")

    try:
        payload = _resolve_tmdb_payload(
            source_type=collection.source_type,
            source_id=collection.source_id,
            filters=collection.filters,
            page=page,
            db=db,
        )
    except HTTPException as exc:
        _increment_metric("tmdb_errors")
        log.warning(
            "TMDB fetch failed for collection_id=%s page=%s: %s",
            collection.id,
            page,
            exc.detail,
        )
        return {
            "collection_id": collection.id,
            "page": page,
            "payload": {},
            "expires_at": None,
            "cached": False,
            "stale": False,
        }

    cache = _upsert_cache_entry(
        db,
        collection=collection,
        page=page,
        payload=payload,
        now=now,
    )

    db.commit()
    db.refresh(cache)

    return {
        "collection_id": collection.id,
        "page": cache.page,
        "payload": cache.payload,
        "expires_at": cache.expires_at,
        "cached": False,
        "stale": False,
    }
