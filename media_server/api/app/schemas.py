from datetime import datetime
from pydantic import BaseModel, HttpUrl, Field
from uuid import UUID
from typing import Optional


class ProviderCreate(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    base_url: HttpUrl
    username: str = Field(min_length=1, max_length=120)
    password: str = Field(min_length=1, max_length=200)

class ProviderOut(BaseModel):
    id: UUID
    name: str
    base_url: str
    username: str
    is_active: bool

    class Config:
        from_attributes = True


class CategoryOut(BaseModel):
    id: str
    cat_type: str
    provider_category_id: int
    name: str
    is_active: bool

    class Config:
        from_attributes = True

class LiveStreamOut(BaseModel):
    id: str
    provider_stream_id: int
    name: str
    stream_icon: str | None = None
    epg_channel_id: str | None = None
    is_active: bool
    category_id: str | None = None
    normalized_name: str | None = None

    channel_number: int | None = None

    approved: bool
    alt1_stream_id: str | None = None
    alt2_stream_id: str | None = None
    alt3_stream_id: str | None = None

    class Config:
        from_attributes = True



class PagedLiveStreams(BaseModel):
    total: int
    items: list[LiveStreamOut]

class LiveStreamUpdate(BaseModel):
    approved: bool | None = None
    channel_number: int | None = None
    normalized_name: str | None = None

    epg_source_id: UUID | None = None
    epg_channel_id: str | None = None

    alt1_stream_id: UUID | None = None
    alt2_stream_id: UUID | None = None
    alt3_stream_id: UUID | None = None

    alt1_url: str | None = None
    alt2_url: str | None = None
    alt3_url: str | None = None


class EpgSourceCreate(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    xmltv_url: HttpUrl

class EpgSourceOut(BaseModel):
    id: UUID
    name: str
    xmltv_url: str
    is_active: bool

    class Config:
        from_attributes = True

class VodStreamUpdate(BaseModel):
    approved: Optional[bool] = None
    normalized_name: Optional[str] = None
    custom_poster_url: Optional[str] = None

class SeriesItemUpdate(BaseModel):
    approved: bool | None = None
    normalized_name: str | None = None
    custom_cover_url: str | None = None

class ProviderUpdate(BaseModel):
    name: str | None = None
    base_url: HttpUrl | None = None
    username: str | None = None
    password: str | None = None
    is_active: bool | None = None

class TmdbConfigOut(BaseModel):
    is_enabled: bool
    api_key_masked: str | None = None
    read_access_token_masked: str | None = None
    language: str | None = None
    region: str | None = None
    requests_per_second: int

class TmdbConfigUpdate(BaseModel):
    is_enabled: bool | None = None
    api_key: str | None = None
    read_access_token: str | None = None
    language: str | None = None
    region: str | None = None
    requests_per_second: int | None = None

class TmdbStatusOut(BaseModel):
    enabled: bool
    movies_total: int
    movies_synced: int
    movies_failed: int
    movies_missing: int
    series_total: int
    series_synced: int
    series_failed: int
    series_missing: int

class TmdbActivityItemOut(BaseModel):
    kind: str  # "movie" | "series"
    id: str
    name: str
    normalized_name: str | None = None

    tmdb_status: str | None = None
    tmdb_id: int | None = None
    tmdb_title: str | None = None
    tmdb_last_sync: str | None = None
    tmdb_error: str | None = None


class TmdbActivityOut(BaseModel):
    server_time: str
    items: list[TmdbActivityItemOut]


class CollectionFilters(BaseModel):
    kind: str | None = None
    time_window: str | None = None
    list_key: str | None = None
    sort_by: str | None = None
    filters: dict | None = None
    language: str | None = None
    region: str | None = None


class CollectionCreate(BaseModel):
    name: str = Field(min_length=1, max_length=255)
    slug: str = Field(min_length=1, max_length=255)
    source_type: str = Field(default="trending")
    source_id: int | None = None
    filters: CollectionFilters | None = None
    cache_ttl_seconds: int | None = None
    enabled: bool = True
    order_index: int = 0


class CollectionUpdate(BaseModel):
    name: str | None = None
    slug: str | None = None
    source_type: str | None = None
    source_id: int | None = None
    filters: CollectionFilters | None = None
    cache_ttl_seconds: int | None = None
    enabled: bool | None = None
    order_index: int | None = None


class CollectionOut(BaseModel):
    id: UUID
    name: str
    slug: str
    source_type: str
    source_id: int | None = None
    filters: dict | None = None
    cache_ttl_seconds: int | None = None
    enabled: bool
    order_index: int
    created_at: datetime | None = None
    updated_at: datetime | None = None

    class Config:
        from_attributes = True


class CollectionCacheOut(BaseModel):
    collection_id: UUID
    page: int
    payload: dict
    expires_at: str | None = None
    cached: bool
    stale: bool = False


class CollectionPreviewIn(BaseModel):
    source_type: str | None = None
    source_id: int | None = None
    filters: CollectionFilters | None = None


class CollectionPreviewOut(BaseModel):
    source_type: str
    source_id: int | None = None
    filters: dict | None = None
    page: int
    payload: dict
