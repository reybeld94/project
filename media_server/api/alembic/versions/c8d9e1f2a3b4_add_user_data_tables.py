"""add user data tables

Revision ID: c8d9e1f2a3b4
Revises: b2d3e4f5a6c7
Create Date: 2026-01-20 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID


# revision identifiers, used by Alembic.
revision: str = "c8d9e1f2a3b4"
down_revision: Union[str, None] = "b2d3e4f5a6c7"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade():
    # Create user_playback_progress table
    op.create_table(
        "user_playback_progress",
        sa.Column("id", UUID(as_uuid=True), nullable=False),
        sa.Column("provider_user_id", UUID(as_uuid=True), nullable=False),
        sa.Column("content_type", sa.String(20), nullable=False),
        sa.Column("content_id", sa.String(120), nullable=False),
        sa.Column("position_ms", sa.Integer(), server_default="0", nullable=False),
        sa.Column("duration_ms", sa.Integer(), nullable=False),
        sa.Column("title", sa.String(500), nullable=True),
        sa.Column("poster_url", sa.String(800), nullable=True),
        sa.Column("backdrop_url", sa.String(800), nullable=True),
        sa.Column("season_number", sa.Integer(), nullable=True),
        sa.Column("episode_number", sa.Integer(), nullable=True),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["provider_user_id"], ["provider_users.id"], ondelete="CASCADE"),
        sa.UniqueConstraint("provider_user_id", "content_type", "content_id", name="uq_user_playback_progress_user_content"),
    )
    op.create_index("ix_user_playback_progress_provider_user_id", "user_playback_progress", ["provider_user_id"])
    op.create_index("ix_user_playback_progress_updated_at", "user_playback_progress", ["updated_at"])

    # Create user_watched_items table
    op.create_table(
        "user_watched_items",
        sa.Column("id", UUID(as_uuid=True), nullable=False),
        sa.Column("provider_user_id", UUID(as_uuid=True), nullable=False),
        sa.Column("content_type", sa.String(20), nullable=False),
        sa.Column("content_id", sa.String(120), nullable=False),
        sa.Column("watched_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["provider_user_id"], ["provider_users.id"], ondelete="CASCADE"),
        sa.UniqueConstraint("provider_user_id", "content_type", "content_id", name="uq_user_watched_items_user_content"),
    )
    op.create_index("ix_user_watched_items_provider_user_id", "user_watched_items", ["provider_user_id"])
    op.create_index("ix_user_watched_items_watched_at", "user_watched_items", ["watched_at"])

    # Create user_favorites table
    op.create_table(
        "user_favorites",
        sa.Column("id", UUID(as_uuid=True), nullable=False),
        sa.Column("provider_user_id", UUID(as_uuid=True), nullable=False),
        sa.Column("content_type", sa.String(20), nullable=False),
        sa.Column("content_id", sa.String(120), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["provider_user_id"], ["provider_users.id"], ondelete="CASCADE"),
        sa.UniqueConstraint("provider_user_id", "content_type", "content_id", name="uq_user_favorites_user_content"),
    )
    op.create_index("ix_user_favorites_provider_user_id", "user_favorites", ["provider_user_id"])
    op.create_index("ix_user_favorites_created_at", "user_favorites", ["created_at"])

    # Create user_my_list table
    op.create_table(
        "user_my_list",
        sa.Column("id", UUID(as_uuid=True), nullable=False),
        sa.Column("provider_user_id", UUID(as_uuid=True), nullable=False),
        sa.Column("content_type", sa.String(20), nullable=False),
        sa.Column("content_id", sa.String(120), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["provider_user_id"], ["provider_users.id"], ondelete="CASCADE"),
        sa.UniqueConstraint("provider_user_id", "content_type", "content_id", name="uq_user_my_list_user_content"),
    )
    op.create_index("ix_user_my_list_provider_user_id", "user_my_list", ["provider_user_id"])
    op.create_index("ix_user_my_list_created_at", "user_my_list", ["created_at"])


def downgrade():
    # Drop user_my_list table
    op.drop_index("ix_user_my_list_created_at", table_name="user_my_list")
    op.drop_index("ix_user_my_list_provider_user_id", table_name="user_my_list")
    op.drop_table("user_my_list")

    # Drop user_favorites table
    op.drop_index("ix_user_favorites_created_at", table_name="user_favorites")
    op.drop_index("ix_user_favorites_provider_user_id", table_name="user_favorites")
    op.drop_table("user_favorites")

    # Drop user_watched_items table
    op.drop_index("ix_user_watched_items_watched_at", table_name="user_watched_items")
    op.drop_index("ix_user_watched_items_provider_user_id", table_name="user_watched_items")
    op.drop_table("user_watched_items")

    # Drop user_playback_progress table
    op.drop_index("ix_user_playback_progress_updated_at", table_name="user_playback_progress")
    op.drop_index("ix_user_playback_progress_provider_user_id", table_name="user_playback_progress")
    op.drop_table("user_playback_progress")
