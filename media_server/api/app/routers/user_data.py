"""Router for managing user data (playback progress, watched items, favorites, my list)."""

from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select, delete
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.deps import get_db
from app.models import (
    ProviderUser,
    UserPlaybackProgress,
    UserWatchedItem,
    UserFavorite,
    UserMyList,
)
from app.schemas import (
    PlaybackProgressCreate,
    PlaybackProgressUpdate,
    PlaybackProgressOut,
    WatchedItemCreate,
    WatchedItemOut,
    FavoriteCreate,
    FavoriteOut,
    MyListItemCreate,
    MyListItemOut,
)


router = APIRouter(prefix="/user-data", tags=["user-data"])


def get_provider_user_by_code(db: Session, unique_code: str) -> ProviderUser:
    """Get provider user by unique code and validate."""
    user = db.execute(
        select(ProviderUser).where(ProviderUser.unique_code == unique_code)
    ).scalar_one_or_none()

    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    if not user.is_enabled:
        raise HTTPException(status_code=403, detail="User is disabled")

    return user


# ========================================
# PLAYBACK PROGRESS ENDPOINTS
# ========================================

@router.post("/progress", response_model=PlaybackProgressOut)
def save_playback_progress(
    payload: PlaybackProgressCreate,
    unique_code: str = Query(..., description="User's unique code"),
    db: Session = Depends(get_db),
):
    """Save or update playback progress for a user."""
    user = get_provider_user_by_code(db, unique_code)

    # Check if progress already exists
    existing = db.execute(
        select(UserPlaybackProgress).where(
            UserPlaybackProgress.provider_user_id == user.id,
            UserPlaybackProgress.content_type == payload.content_type,
            UserPlaybackProgress.content_id == payload.content_id,
        )
    ).scalar_one_or_none()

    if existing:
        # Update existing progress
        existing.position_ms = payload.position_ms
        existing.duration_ms = payload.duration_ms
        if payload.title:
            existing.title = payload.title
        if payload.poster_url:
            existing.poster_url = payload.poster_url
        if payload.backdrop_url:
            existing.backdrop_url = payload.backdrop_url
        if payload.season_number is not None:
            existing.season_number = payload.season_number
        if payload.episode_number is not None:
            existing.episode_number = payload.episode_number
        existing.updated_at = datetime.now(timezone.utc)

        db.commit()
        db.refresh(existing)
        return existing
    else:
        # Create new progress
        progress = UserPlaybackProgress(
            provider_user_id=user.id,
            content_type=payload.content_type,
            content_id=payload.content_id,
            position_ms=payload.position_ms,
            duration_ms=payload.duration_ms,
            title=payload.title,
            poster_url=payload.poster_url,
            backdrop_url=payload.backdrop_url,
            season_number=payload.season_number,
            episode_number=payload.episode_number,
            created_at=datetime.now(timezone.utc),
            updated_at=datetime.now(timezone.utc),
        )

        try:
            db.add(progress)
            db.commit()
            db.refresh(progress)
            return progress
        except IntegrityError:
            db.rollback()
            raise HTTPException(status_code=400, detail="Database integrity error")


@router.get("/progress", response_model=list[PlaybackProgressOut])
def get_all_playback_progress(
    unique_code: str = Query(..., description="User's unique code"),
    limit: int = Query(100, ge=1, le=500),
    db: Session = Depends(get_db),
):
    """Get all playback progress for a user (for Continue Watching)."""
    user = get_provider_user_by_code(db, unique_code)

    progress_list = db.execute(
        select(UserPlaybackProgress)
        .where(UserPlaybackProgress.provider_user_id == user.id)
        .order_by(UserPlaybackProgress.updated_at.desc())
        .limit(limit)
    ).scalars().all()

    return progress_list


@router.get("/progress/{content_type}/{content_id}", response_model=PlaybackProgressOut | None)
def get_playback_progress(
    content_type: str,
    content_id: str,
    unique_code: str = Query(..., description="User's unique code"),
    db: Session = Depends(get_db),
):
    """Get playback progress for a specific item."""
    user = get_provider_user_by_code(db, unique_code)

    progress = db.execute(
        select(UserPlaybackProgress).where(
            UserPlaybackProgress.provider_user_id == user.id,
            UserPlaybackProgress.content_type == content_type,
            UserPlaybackProgress.content_id == content_id,
        )
    ).scalar_one_or_none()

    return progress


@router.delete("/progress/{content_type}/{content_id}")
def delete_playback_progress(
    content_type: str,
    content_id: str,
    unique_code: str = Query(..., description="User's unique code"),
    db: Session = Depends(get_db),
):
    """Delete playback progress for a specific item."""
    user = get_provider_user_by_code(db, unique_code)

    result = db.execute(
        delete(UserPlaybackProgress).where(
            UserPlaybackProgress.provider_user_id == user.id,
            UserPlaybackProgress.content_type == content_type,
            UserPlaybackProgress.content_id == content_id,
        )
    )
    db.commit()

    if result.rowcount == 0:
        raise HTTPException(status_code=404, detail="Progress not found")

    return {"message": "Progress deleted"}


# ========================================
# WATCHED ITEMS ENDPOINTS
# ========================================

@router.post("/watched", response_model=WatchedItemOut)
def mark_as_watched(
    payload: WatchedItemCreate,
    unique_code: str = Query(..., description="User's unique code"),
    db: Session = Depends(get_db),
):
    """Mark an item as watched (>=95% completion)."""
    user = get_provider_user_by_code(db, unique_code)

    # Check if already marked as watched
    existing = db.execute(
        select(UserWatchedItem).where(
            UserWatchedItem.provider_user_id == user.id,
            UserWatchedItem.content_type == payload.content_type,
            UserWatchedItem.content_id == payload.content_id,
        )
    ).scalar_one_or_none()

    if existing:
        # Update watched_at timestamp
        existing.watched_at = datetime.now(timezone.utc)
        db.commit()
        db.refresh(existing)
        return existing

    # Create new watched item
    watched = UserWatchedItem(
        provider_user_id=user.id,
        content_type=payload.content_type,
        content_id=payload.content_id,
        watched_at=datetime.now(timezone.utc),
        created_at=datetime.now(timezone.utc),
    )

    try:
        db.add(watched)
        db.commit()
        db.refresh(watched)
        return watched
    except IntegrityError:
        db.rollback()
        raise HTTPException(status_code=400, detail="Database integrity error")


@router.get("/watched", response_model=list[WatchedItemOut])
def get_watched_items(
    unique_code: str = Query(..., description="User's unique code"),
    content_type: str | None = Query(None, description="Filter by content type"),
    limit: int = Query(100, ge=1, le=500),
    db: Session = Depends(get_db),
):
    """Get all watched items for a user."""
    user = get_provider_user_by_code(db, unique_code)

    query = select(UserWatchedItem).where(
        UserWatchedItem.provider_user_id == user.id
    )

    if content_type:
        query = query.where(UserWatchedItem.content_type == content_type)

    query = query.order_by(UserWatchedItem.watched_at.desc()).limit(limit)

    watched_list = db.execute(query).scalars().all()
    return watched_list


@router.get("/watched/{content_type}/{content_id}", response_model=WatchedItemOut | None)
def check_is_watched(
    content_type: str,
    content_id: str,
    unique_code: str = Query(..., description="User's unique code"),
    db: Session = Depends(get_db),
):
    """Check if a specific item is marked as watched."""
    user = get_provider_user_by_code(db, unique_code)

    watched = db.execute(
        select(UserWatchedItem).where(
            UserWatchedItem.provider_user_id == user.id,
            UserWatchedItem.content_type == content_type,
            UserWatchedItem.content_id == content_id,
        )
    ).scalar_one_or_none()

    return watched


@router.delete("/watched/{content_type}/{content_id}")
def unmark_watched(
    content_type: str,
    content_id: str,
    unique_code: str = Query(..., description="User's unique code"),
    db: Session = Depends(get_db),
):
    """Remove watched status from an item."""
    user = get_provider_user_by_code(db, unique_code)

    result = db.execute(
        delete(UserWatchedItem).where(
            UserWatchedItem.provider_user_id == user.id,
            UserWatchedItem.content_type == content_type,
            UserWatchedItem.content_id == content_id,
        )
    )
    db.commit()

    if result.rowcount == 0:
        raise HTTPException(status_code=404, detail="Watched item not found")

    return {"message": "Watched status removed"}


# ========================================
# FAVORITES ENDPOINTS
# ========================================

@router.post("/favorites", response_model=FavoriteOut)
def add_to_favorites(
    payload: FavoriteCreate,
    unique_code: str = Query(..., description="User's unique code"),
    db: Session = Depends(get_db),
):
    """Add an item to favorites (Me encanta)."""
    user = get_provider_user_by_code(db, unique_code)

    # Check if already in favorites
    existing = db.execute(
        select(UserFavorite).where(
            UserFavorite.provider_user_id == user.id,
            UserFavorite.content_type == payload.content_type,
            UserFavorite.content_id == payload.content_id,
        )
    ).scalar_one_or_none()

    if existing:
        return existing

    # Create new favorite
    favorite = UserFavorite(
        provider_user_id=user.id,
        content_type=payload.content_type,
        content_id=payload.content_id,
        created_at=datetime.now(timezone.utc),
    )

    try:
        db.add(favorite)
        db.commit()
        db.refresh(favorite)
        return favorite
    except IntegrityError:
        db.rollback()
        raise HTTPException(status_code=400, detail="Database integrity error")


@router.get("/favorites", response_model=list[FavoriteOut])
def get_favorites(
    unique_code: str = Query(..., description="User's unique code"),
    content_type: str | None = Query(None, description="Filter by content type"),
    limit: int = Query(100, ge=1, le=500),
    db: Session = Depends(get_db),
):
    """Get all favorites for a user."""
    user = get_provider_user_by_code(db, unique_code)

    query = select(UserFavorite).where(UserFavorite.provider_user_id == user.id)

    if content_type:
        query = query.where(UserFavorite.content_type == content_type)

    query = query.order_by(UserFavorite.created_at.desc()).limit(limit)

    favorites = db.execute(query).scalars().all()
    return favorites


@router.get("/favorites/{content_type}/{content_id}", response_model=FavoriteOut | None)
def check_is_favorite(
    content_type: str,
    content_id: str,
    unique_code: str = Query(..., description="User's unique code"),
    db: Session = Depends(get_db),
):
    """Check if a specific item is in favorites."""
    user = get_provider_user_by_code(db, unique_code)

    favorite = db.execute(
        select(UserFavorite).where(
            UserFavorite.provider_user_id == user.id,
            UserFavorite.content_type == content_type,
            UserFavorite.content_id == content_id,
        )
    ).scalar_one_or_none()

    return favorite


@router.delete("/favorites/{content_type}/{content_id}")
def remove_from_favorites(
    content_type: str,
    content_id: str,
    unique_code: str = Query(..., description="User's unique code"),
    db: Session = Depends(get_db),
):
    """Remove an item from favorites."""
    user = get_provider_user_by_code(db, unique_code)

    result = db.execute(
        delete(UserFavorite).where(
            UserFavorite.provider_user_id == user.id,
            UserFavorite.content_type == content_type,
            UserFavorite.content_id == content_id,
        )
    )
    db.commit()

    if result.rowcount == 0:
        raise HTTPException(status_code=404, detail="Favorite not found")

    return {"message": "Removed from favorites"}


# ========================================
# MY LIST ENDPOINTS
# ========================================

@router.post("/my-list", response_model=MyListItemOut)
def add_to_my_list(
    payload: MyListItemCreate,
    unique_code: str = Query(..., description="User's unique code"),
    db: Session = Depends(get_db),
):
    """Add an item to My List."""
    user = get_provider_user_by_code(db, unique_code)

    # Check if already in my list
    existing = db.execute(
        select(UserMyList).where(
            UserMyList.provider_user_id == user.id,
            UserMyList.content_type == payload.content_type,
            UserMyList.content_id == payload.content_id,
        )
    ).scalar_one_or_none()

    if existing:
        return existing

    # Create new my list item
    my_list_item = UserMyList(
        provider_user_id=user.id,
        content_type=payload.content_type,
        content_id=payload.content_id,
        created_at=datetime.now(timezone.utc),
    )

    try:
        db.add(my_list_item)
        db.commit()
        db.refresh(my_list_item)
        return my_list_item
    except IntegrityError:
        db.rollback()
        raise HTTPException(status_code=400, detail="Database integrity error")


@router.get("/my-list", response_model=list[MyListItemOut])
def get_my_list(
    unique_code: str = Query(..., description="User's unique code"),
    content_type: str | None = Query(None, description="Filter by content type"),
    limit: int = Query(100, ge=1, le=500),
    db: Session = Depends(get_db),
):
    """Get all items in My List for a user."""
    user = get_provider_user_by_code(db, unique_code)

    query = select(UserMyList).where(UserMyList.provider_user_id == user.id)

    if content_type:
        query = query.where(UserMyList.content_type == content_type)

    query = query.order_by(UserMyList.created_at.desc()).limit(limit)

    my_list = db.execute(query).scalars().all()
    return my_list


@router.get("/my-list/{content_type}/{content_id}", response_model=MyListItemOut | None)
def check_is_in_my_list(
    content_type: str,
    content_id: str,
    unique_code: str = Query(..., description="User's unique code"),
    db: Session = Depends(get_db),
):
    """Check if a specific item is in My List."""
    user = get_provider_user_by_code(db, unique_code)

    my_list_item = db.execute(
        select(UserMyList).where(
            UserMyList.provider_user_id == user.id,
            UserMyList.content_type == content_type,
            UserMyList.content_id == content_id,
        )
    ).scalar_one_or_none()

    return my_list_item


@router.delete("/my-list/{content_type}/{content_id}")
def remove_from_my_list(
    content_type: str,
    content_id: str,
    unique_code: str = Query(..., description="User's unique code"),
    db: Session = Depends(get_db),
):
    """Remove an item from My List."""
    user = get_provider_user_by_code(db, unique_code)

    result = db.execute(
        delete(UserMyList).where(
            UserMyList.provider_user_id == user.id,
            UserMyList.content_type == content_type,
            UserMyList.content_id == content_id,
        )
    )
    db.commit()

    if result.rowcount == 0:
        raise HTTPException(status_code=404, detail="My list item not found")

    return {"message": "Removed from my list"}
