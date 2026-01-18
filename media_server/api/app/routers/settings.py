from datetime import datetime

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.deps import get_db
from app.provider_auto_sync import get_or_create_provider_auto_sync
from app.schemas import ProviderAutoSyncConfigOut, ProviderAutoSyncConfigUpdate


router = APIRouter(prefix="/settings", tags=["settings"])


@router.get("/provider-auto-sync", response_model=ProviderAutoSyncConfigOut)
def get_provider_auto_sync_config(db: Session = Depends(get_db)):
    cfg = get_or_create_provider_auto_sync(db)
    return ProviderAutoSyncConfigOut(interval_minutes=cfg.interval_minutes)


@router.patch("/provider-auto-sync", response_model=ProviderAutoSyncConfigOut)
def update_provider_auto_sync_config(
    payload: ProviderAutoSyncConfigUpdate,
    db: Session = Depends(get_db),
):
    cfg = get_or_create_provider_auto_sync(db)
    if payload.interval_minutes is not None:
        cfg.interval_minutes = payload.interval_minutes
        cfg.updated_at = datetime.utcnow()
        db.commit()
        db.refresh(cfg)
    return ProviderAutoSyncConfigOut(interval_minutes=cfg.interval_minutes)
