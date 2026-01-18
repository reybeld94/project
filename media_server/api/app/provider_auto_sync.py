from datetime import datetime
import logging

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Provider, ProviderAutoSyncConfig
from app.routers.providers import sync_all, sync_categories


log = logging.getLogger("mini_media_server")


def get_or_create_provider_auto_sync(db: Session) -> ProviderAutoSyncConfig:
    cfg = db.execute(select(ProviderAutoSyncConfig).limit(1)).scalar_one_or_none()
    if cfg:
        return cfg

    cfg = ProviderAutoSyncConfig(interval_minutes=60)
    db.add(cfg)
    db.commit()
    db.refresh(cfg)
    return cfg


def update_provider_auto_sync(db: Session, interval_minutes: int) -> ProviderAutoSyncConfig:
    cfg = get_or_create_provider_auto_sync(db)
    cfg.interval_minutes = interval_minutes
    cfg.updated_at = datetime.utcnow()
    db.commit()
    db.refresh(cfg)
    return cfg


def run_provider_auto_sync(db: Session) -> dict:
    providers = db.execute(
        select(Provider).where(Provider.is_active == True)
    ).scalars().all()

    results = []
    for provider in providers:
        provider_id = str(provider.id)
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

    return {"providers": len(providers), "results": results}
