import uuid
from datetime import datetime
from sqlalchemy import Integer
from sqlalchemy import String, DateTime, Boolean, ForeignKey, UniqueConstraint, text
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship
from sqlalchemy import Index
from sqlalchemy import Integer
from sqlalchemy import String, DateTime, Boolean, ForeignKey, UniqueConstraint
from sqlalchemy import Index
from sqlalchemy import JSON

from .db import Base


class Provider(Base):
    __tablename__ = "providers"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    base_url: Mapped[str] = mapped_column(String(500), nullable=False)
    username: Mapped[str] = mapped_column(String(120), nullable=False)
    password: Mapped[str] = mapped_column(String(200), nullable=False)  # MVP: plaintext (luego ciframos)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)


class Account(Base):
    __tablename__ = "accounts"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    username: Mapped[str] = mapped_column(String(120), unique=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)

    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)


class Device(Base):
    __tablename__ = "devices"
    __table_args__ = (UniqueConstraint("device_key", name="uq_devices_device_key"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    device_key: Mapped[str] = mapped_column(String(120), nullable=False)  # ID estable del box (lo defines tú)
    name: Mapped[str | None] = mapped_column(String(120), nullable=True)

    account_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), ForeignKey("accounts.id"), nullable=True)
    account = relationship("Account", lazy="joined")

    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)


class TmdbConfig(Base):
    __tablename__ = "tmdb_config"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)

    is_enabled: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    api_key: Mapped[str | None] = mapped_column(String(128), nullable=True)
    read_access_token: Mapped[str | None] = mapped_column(String(512), nullable=True)

    language: Mapped[str | None] = mapped_column(String(16), nullable=True, default="en-US")
    region: Mapped[str | None] = mapped_column(String(8), nullable=True, default="US")

    requests_per_second: Mapped[int] = mapped_column(Integer, default=5, nullable=False)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)


class TmdbCollection(Base):
    __tablename__ = "tmdb_collections"
    __table_args__ = (
        UniqueConstraint("slug", name="uq_tmdb_collections_slug"),
        Index("ix_tmdb_collections_slug", "slug"),
        Index("ix_tmdb_collections_enabled", "enabled"),
        Index("ix_tmdb_collections_order_index", "order_index"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)

    name: Mapped[str] = mapped_column(String(255), nullable=False)
    slug: Mapped[str] = mapped_column(String(255), nullable=False)

    source_type: Mapped[str] = mapped_column(String(50), default="tmdb", nullable=False)
    source_id: Mapped[int | None] = mapped_column(Integer, nullable=True)

    filters: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    cache_ttl_seconds: Mapped[int | None] = mapped_column(Integer, nullable=True)

    enabled: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    order_index: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)

    cache_entries = relationship("TmdbCollectionCache", back_populates="collection", cascade="all, delete-orphan")


class TmdbCollectionCache(Base):
    __tablename__ = "tmdb_collection_cache"
    __table_args__ = (
        UniqueConstraint("collection_id", "page", name="uq_tmdb_collection_cache_collection_page"),
        Index("ix_tmdb_collection_cache_expires_at", "expires_at"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)

    collection_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("tmdb_collections.id", ondelete="CASCADE"),
        nullable=False,
    )
    collection = relationship("TmdbCollection", back_populates="cache_entries", lazy="joined")

    page: Mapped[int] = mapped_column(Integer, nullable=False)
    payload: Mapped[dict] = mapped_column(JSON, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)


class Category(Base):
    __tablename__ = "categories"
    # Unique por provider + tipo + id externo
    __table_args__ = (UniqueConstraint("provider_id", "cat_type", "provider_category_id", name="uq_categories_provider_type_extid"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)

    # legacy
    provider_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), ForeignKey("providers.id"), nullable=True)
    provider = relationship("Provider", lazy="joined")

    cat_type: Mapped[str] = mapped_column(String(20), nullable=False)  # live|vod|series
    provider_category_id: Mapped[int] = mapped_column(Integer, nullable=False)  # category_id de Xtream
    name: Mapped[str] = mapped_column(String(255), nullable=False)

    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)

class LiveStream(Base):
    __tablename__ = "live_streams"
    __table_args__ = (UniqueConstraint("provider_id", "provider_stream_id", name="uq_live_streams_provider_streamid"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)

    provider_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("providers.id"), nullable=False)
    provider = relationship("Provider", lazy="joined")

    category_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), ForeignKey("categories.id"), nullable=True)
    category = relationship("Category", lazy="joined")

    provider_stream_id: Mapped[int] = mapped_column(Integer, nullable=False)  # stream_id de Xtream
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    normalized_name: Mapped[str | None] = mapped_column(String(255), nullable=True)

    channel_number: Mapped[int | None] = mapped_column(Integer, nullable=True)
    custom_logo_url: Mapped[str | None] = mapped_column(String(800), nullable=True)

    stream_icon: Mapped[str | None] = mapped_column(String(800), nullable=True)
    epg_channel_id: Mapped[str | None] = mapped_column(String(120), nullable=True)

    epg_source_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), ForeignKey("epg_sources.id"), nullable=True)
    epg_source = relationship("EpgSource", lazy="joined")


    approved: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    alt1_stream_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("live_streams.id", ondelete="SET NULL"),
        nullable=True,
    )
    alt2_stream_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("live_streams.id", ondelete="SET NULL"),
        nullable=True,
    )
    alt3_stream_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("live_streams.id", ondelete="SET NULL"),
        nullable=True,
    )

    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)

class EpgSource(Base):
    __tablename__ = "epg_sources"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    xmltv_url: Mapped[str] = mapped_column(String(1200), nullable=False)

    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)

class EpgChannel(Base):
    __tablename__ = "epg_channels"
    __table_args__ = (
        UniqueConstraint("epg_source_id", "xmltv_id", name="uq_epg_channels_source_xmltvid"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)

    # ✅ Nuevo: la fuente XMLTV real
    epg_source_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("epg_sources.id"),
        nullable=True,   # déjalo True por ahora para no romper datos existentes
    )
    epg_source = relationship("EpgSource", lazy="joined")

    # (opcional / legacy) si todavía lo tienes en el esquema
    provider_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), ForeignKey("providers.id"), nullable=True)
    provider = relationship("Provider", lazy="joined")

    xmltv_id: Mapped[str] = mapped_column(String(200), nullable=False)  # <channel id="...">
    display_name: Mapped[str] = mapped_column(String(255), nullable=False)
    icon_url: Mapped[str | None] = mapped_column(String(800), nullable=True)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)



class EpgProgram(Base):
    __tablename__ = "epg_programs"
    __table_args__ = (
        UniqueConstraint("channel_id", "start_time", name="uq_epg_programs_channel_start"),
        Index("ix_epg_programs_channel_time", "channel_id", "start_time", "end_time"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)

    epg_source_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("epg_sources.id"), nullable=False)
    epg_source = relationship("EpgSource", lazy="joined")

    # legacy (puedes borrarlo después si quieres)
    provider_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), ForeignKey("providers.id"), nullable=True)
    provider = relationship("Provider", lazy="joined")

    channel_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("epg_channels.id"), nullable=False)
    channel = relationship("EpgChannel", lazy="joined")

    start_time: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)  # UTC
    end_time: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)    # UTC

    title: Mapped[str] = mapped_column(String(255), nullable=False)
    description: Mapped[str | None] = mapped_column(String(2000), nullable=True)
    category: Mapped[str | None] = mapped_column(String(120), nullable=True)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)

class VodStream(Base):
    __tablename__ = "vod_streams"
    __table_args__ = (
        UniqueConstraint("provider_id", "provider_stream_id", name="uq_vod_streams_provider_streamid"),
        Index(
            "uq_vod_streams_provider_tmdb_id",
            "provider_id",
            "tmdb_id",
            unique=True,
            postgresql_where=text("tmdb_id IS NOT NULL"),
        ),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)

    provider_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("providers.id"), nullable=False)
    provider = relationship("Provider", lazy="joined")

    category_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), ForeignKey("categories.id"), nullable=True)
    category = relationship("Category", lazy="joined")

    provider_stream_id: Mapped[int] = mapped_column(Integer, nullable=False)  # stream_id (VOD) de Xtream
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    normalized_name: Mapped[str | None] = mapped_column(String(255), nullable=True)

    # Xtream suele mandar poster aquí
    stream_icon: Mapped[str | None] = mapped_column(String(800), nullable=True)
    custom_poster_url: Mapped[str | None] = mapped_column(String(800), nullable=True)

    # Opcional (si te lo trae get_vod_info o get_vod_streams)
    container_extension: Mapped[str | None] = mapped_column(String(20), nullable=True)  # mp4/mkv/avi...
    rating: Mapped[str | None] = mapped_column(String(50), nullable=True)
    added: Mapped[str | None] = mapped_column(String(50), nullable=True)  # a veces viene como string/epoch

    approved: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)

    tmdb_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    tmdb_status: Mapped[str] = mapped_column(String(20), default="missing", nullable=False)  # missing|synced|failed
    tmdb_last_sync: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    tmdb_error: Mapped[str | None] = mapped_column(String(500), nullable=True)

    tmdb_title: Mapped[str | None] = mapped_column(String(255), nullable=True)
    tmdb_overview: Mapped[str | None] = mapped_column(String(4000), nullable=True)
    tmdb_release_date: Mapped[datetime | None] = mapped_column(DateTime(timezone=False), nullable=True)  # ok si lo guardas como date en SQL
    tmdb_genres: Mapped[list | None] = mapped_column(JSON, nullable=True)
    tmdb_vote_average: Mapped[float | None] = mapped_column(Integer, nullable=True)  # si prefieres float real, cámbialo a Float

    tmdb_poster_path: Mapped[str | None] = mapped_column(String(255), nullable=True)
    tmdb_backdrop_path: Mapped[str | None] = mapped_column(String(255), nullable=True)

    tmdb_raw: Mapped[dict | None] = mapped_column(JSON, nullable=True)


class SeriesItem(Base):
    __tablename__ = "series_items"
    __table_args__ = (UniqueConstraint("provider_id", "provider_series_id", name="uq_series_provider_seriesid"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)

    provider_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("providers.id"), nullable=False)
    provider = relationship("Provider", lazy="joined")

    category_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), ForeignKey("categories.id"), nullable=True)
    category = relationship("Category", lazy="joined")

    provider_series_id: Mapped[int] = mapped_column(Integer, nullable=False)  # series_id (Xtream)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    normalized_name: Mapped[str | None] = mapped_column(String(255), nullable=True)

    cover: Mapped[str | None] = mapped_column(String(800), nullable=True)
    custom_cover_url: Mapped[str | None] = mapped_column(String(800), nullable=True)

    approved: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow, nullable=False)

    tmdb_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    tmdb_status: Mapped[str] = mapped_column(String(20), default="missing", nullable=False)
    tmdb_last_sync: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    tmdb_error: Mapped[str | None] = mapped_column(String(500), nullable=True)

    tmdb_title: Mapped[str | None] = mapped_column(String(255), nullable=True)
    tmdb_overview: Mapped[str | None] = mapped_column(String(4000), nullable=True)
    tmdb_release_date: Mapped[datetime | None] = mapped_column(DateTime(timezone=False), nullable=True)
    tmdb_genres: Mapped[list | None] = mapped_column(JSON, nullable=True)
    tmdb_vote_average: Mapped[float | None] = mapped_column(Integer, nullable=True)

    tmdb_poster_path: Mapped[str | None] = mapped_column(String(255), nullable=True)
    tmdb_backdrop_path: Mapped[str | None] = mapped_column(String(255), nullable=True)

    tmdb_raw: Mapped[dict | None] = mapped_column(JSON, nullable=True)
