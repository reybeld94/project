from datetime import datetime, timedelta
import logging

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Provider, ProviderAutoSyncConfig


log = logging.getLogger("mini_media_server")


def get_or_create_provider_auto_sync(db: Session, provider_id) -> ProviderAutoSyncConfig:
    cfg = db.execute(
        select(ProviderAutoSyncConfig).where(ProviderAutoSyncConfig.provider_id == provider_id)
    ).scalar_one_or_none()
    if cfg:
        return cfg

    cfg = ProviderAutoSyncConfig(provider_id=provider_id, interval_minutes=60)
    db.add(cfg)
    db.commit()
    db.refresh(cfg)
    return cfg


def update_provider_auto_sync(db: Session, provider_id, interval_minutes: int) -> ProviderAutoSyncConfig:
    cfg = get_or_create_provider_auto_sync(db, provider_id)
    cfg.interval_minutes = interval_minutes
    cfg.updated_at = datetime.utcnow()
    db.commit()
    db.refresh(cfg)
    return cfg


def run_provider_auto_sync(db: Session) -> dict:
    # Import here to avoid circular import
    from app.routers.providers import sync_all, sync_categories

    providers = db.execute(
        select(Provider).where(Provider.is_active == True)
    ).scalars().all()

    results = []
    now = datetime.utcnow()
    for provider in providers:
        provider_id = str(provider.id)
        cfg = get_or_create_provider_auto_sync(db, provider.id)
        interval_minutes = int(cfg.interval_minutes or 0)
        if interval_minutes <= 0:
            continue
        if cfg.last_run_at and cfg.last_run_at + timedelta(minutes=interval_minutes) > now:
            continue
        try:
            sync_categories(provider_id, db=db)
            sync_all(provider_id, include_inactive_categories=False, db=db)
            results.append({"provider_id": provider_id, "ok": True})
        except HTTPException as e:
            db.rollback()
            log.warning("Provider auto-sync failed for provider_id=%s: %s", provider_id, e.detail)
            results.append({"provider_id": provider_id, "ok": False, "error": e.detail})
        except Exception as e:
            db.rollback()
            log.exception("Provider auto-sync failed for provider_id=%s: %s", provider_id, e)
            results.append({"provider_id": provider_id, "ok": False, "error": str(e)})
        finally:
            cfg.last_run_at = now
            cfg.updated_at = now
            db.commit()

    return {"providers": len(providers), "results": results}
