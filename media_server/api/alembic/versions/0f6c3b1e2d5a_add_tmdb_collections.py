"""add tmdb collections

Revision ID: 0f6c3b1e2d5a
Revises: d773cca4b90b
Create Date: 2026-01-04 22:05:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = "0f6c3b1e2d5a"
down_revision: Union[str, None] = "d773cca4b90b"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade():
    op.create_table(
        "tmdb_collections",
        sa.Column("id", sa.UUID(), primary_key=True),
        sa.Column("name", sa.String(length=255), nullable=False),
        sa.Column("slug", sa.String(length=255), nullable=False),
        sa.Column("source_type", sa.String(length=50), nullable=False, server_default="tmdb"),
        sa.Column("source_id", sa.Integer()),
        sa.Column("filters", sa.JSON()),
        sa.Column("cache_ttl_seconds", sa.Integer()),
        sa.Column("enabled", sa.Boolean(), nullable=False, server_default=sa.text("true")),
        sa.Column("order_index", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()")),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()")),
        sa.UniqueConstraint("slug", name="uq_tmdb_collections_slug"),
    )

    op.create_index("ix_tmdb_collections_slug", "tmdb_collections", ["slug"])
    op.create_index("ix_tmdb_collections_enabled", "tmdb_collections", ["enabled"])
    op.create_index("ix_tmdb_collections_order_index", "tmdb_collections", ["order_index"])

    op.create_table(
        "tmdb_collection_cache",
        sa.Column("id", sa.UUID(), primary_key=True),
        sa.Column(
            "collection_id",
            sa.UUID(),
            sa.ForeignKey("tmdb_collections.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("page", sa.Integer(), nullable=False),
        sa.Column("payload", sa.JSON(), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()")),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()")),
        sa.UniqueConstraint("collection_id", "page", name="uq_tmdb_collection_cache_collection_page"),
    )

    op.create_index("ix_tmdb_collection_cache_expires_at", "tmdb_collection_cache", ["expires_at"])


def downgrade():
    op.drop_index("ix_tmdb_collection_cache_expires_at", table_name="tmdb_collection_cache")
    op.drop_table("tmdb_collection_cache")

    op.drop_index("ix_tmdb_collections_order_index", table_name="tmdb_collections")
    op.drop_index("ix_tmdb_collections_enabled", table_name="tmdb_collections")
    op.drop_index("ix_tmdb_collections_slug", table_name="tmdb_collections")
    op.drop_table("tmdb_collections")
