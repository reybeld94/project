"""Router for managing provider users (Xtream Codes credentials)."""

from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.deps import get_db
from app.models import Provider, ProviderUser
from app.schemas import ProviderUserCreate, ProviderUserOut, ProviderUserUpdate
from app.utils import generate_unique_code


router = APIRouter(prefix="/provider-users", tags=["provider-users"])


@router.post("", response_model=ProviderUserOut)
def create_provider_user(payload: ProviderUserCreate, db: Session = Depends(get_db)):
    """Create a new provider user with auto-generated unique code."""
    # Verify provider exists
    provider = db.get(Provider, payload.provider_id)
    if not provider:
        raise HTTPException(status_code=404, detail="Provider not found")

    # Generate unique code (retry if collision occurs)
    max_retries = 10
    for _ in range(max_retries):
        unique_code = generate_unique_code()

        # Check if code already exists
        existing = db.execute(
            select(ProviderUser).where(ProviderUser.unique_code == unique_code)
        ).scalar_one_or_none()

        if not existing:
            break
    else:
        raise HTTPException(
            status_code=500,
            detail="Failed to generate unique code after multiple attempts"
        )

    # Create user
    user = ProviderUser(
        provider_id=payload.provider_id,
        username=payload.username,
        password=payload.password,
        unique_code=unique_code,
        alias=payload.alias,
        is_enabled=payload.is_enabled,
        created_at=datetime.utcnow(),
        updated_at=datetime.utcnow(),
    )

    try:
        db.add(user)
        db.commit()
        db.refresh(user)
        return user
    except IntegrityError as e:
        db.rollback()
        if "uq_provider_users_provider_username" in str(e):
            raise HTTPException(
                status_code=400,
                detail="Username already exists for this provider"
            )
        raise HTTPException(status_code=400, detail="Database integrity error")


@router.get("", response_model=list[ProviderUserOut])
def list_provider_users(
    provider_id: str | None = None,
    db: Session = Depends(get_db)
):
    """List all provider users, optionally filtered by provider."""
    query = select(ProviderUser).order_by(ProviderUser.created_at.desc())

    if provider_id:
        query = query.where(ProviderUser.provider_id == provider_id)

    users = db.execute(query).scalars().all()
    return users


@router.get("/by-code/{unique_code}", response_model=ProviderUserOut)
def get_provider_user_by_code(unique_code: str, db: Session = Depends(get_db)):
    """Get provider user by unique code."""
    user = db.execute(
        select(ProviderUser).where(ProviderUser.unique_code == unique_code)
    ).scalar_one_or_none()

    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    if not user.is_enabled:
        raise HTTPException(status_code=403, detail="User is disabled")

    return user


@router.get("/{user_id}", response_model=ProviderUserOut)
def get_provider_user(user_id: str, db: Session = Depends(get_db)):
    """Get provider user by ID."""
    user = db.get(ProviderUser, user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    return user


@router.patch("/{user_id}", response_model=ProviderUserOut)
def update_provider_user(
    user_id: str,
    payload: ProviderUserUpdate,
    db: Session = Depends(get_db)
):
    """Update provider user."""
    user = db.get(ProviderUser, user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    changed = False

    if payload.username is not None:
        user.username = payload.username
        changed = True

    if payload.password is not None:
        user.password = payload.password
        changed = True

    if payload.alias is not None:
        user.alias = payload.alias
        changed = True

    if payload.is_enabled is not None:
        user.is_enabled = payload.is_enabled
        changed = True

    if changed:
        user.updated_at = datetime.utcnow()
        try:
            db.commit()
            db.refresh(user)
        except IntegrityError as e:
            db.rollback()
            if "uq_provider_users_provider_username" in str(e):
                raise HTTPException(
                    status_code=400,
                    detail="Username already exists for this provider"
                )
            raise HTTPException(status_code=400, detail="Database integrity error")

    return user


@router.delete("/{user_id}")
def delete_provider_user(user_id: str, db: Session = Depends(get_db)):
    """Delete provider user."""
    user = db.get(ProviderUser, user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    db.delete(user)
    db.commit()
    return {"message": "User deleted successfully"}
