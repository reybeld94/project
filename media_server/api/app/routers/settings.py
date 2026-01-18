from datetime import datetime
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.deps import get_db
from app.models import Provider
from app.provider_auto_sync import get_or_create_provider_auto_sync
from app.schemas import ProviderAutoSyncConfigOut, ProviderAutoSyncConfigUpdate


router = APIRouter(prefix="/settings", tags=["settings"])


def _resolve_provider_id(db: Session, provider_id: str | None) -> UUID:
    if provider_id:
        try:
            return UUID(provider_id)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail="Invalid provider_id") from exc
    provider = db.execute(
        select(Provider)
        .where(Provider.is_active == True)
        .order_by(Provider.created_at.desc())
    ).scalars().first()
    if provider:
        return provider.id
    provider = db.execute(select(Provider).order_by(Provider.created_at.desc())).scalars().first()
    if provider:
        return provider.id
    raise HTTPException(status_code=404, detail="Provider not found")


@router.get("/provider-auto-sync", response_model=ProviderAutoSyncConfigOut)
def get_provider_auto_sync_config(provider_id: str | None = None, db: Session = Depends(get_db)):
    resolved_provider_id = _resolve_provider_id(db, provider_id)
    cfg = get_or_create_provider_auto_sync(db, resolved_provider_id)
    return ProviderAutoSyncConfigOut(
        provider_id=cfg.provider_id,
        interval_minutes=cfg.interval_minutes,
        last_run_at=cfg.last_run_at,
    )


@router.patch("/provider-auto-sync", response_model=ProviderAutoSyncConfigOut)
def update_provider_auto_sync_config(
    payload: ProviderAutoSyncConfigUpdate,
    provider_id: str | None = None,
    db: Session = Depends(get_db),
):
    resolved_provider_id = _resolve_provider_id(db, provider_id)
    cfg = get_or_create_provider_auto_sync(db, resolved_provider_id)
    if payload.interval_minutes is not None:
        cfg.interval_minutes = payload.interval_minutes
        cfg.updated_at = datetime.utcnow()
        db.commit()
        db.refresh(cfg)
    return ProviderAutoSyncConfigOut(
        provider_id=cfg.provider_id,
        interval_minutes=cfg.interval_minutes,
        last_run_at=cfg.last_run_at,
    )
