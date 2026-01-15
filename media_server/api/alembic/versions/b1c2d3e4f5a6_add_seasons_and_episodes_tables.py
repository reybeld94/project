"""add seasons and episodes tables

Revision ID: b1c2d3e4f5a6
Revises: 4b991a668702
Create Date: 2025-02-14 00:00:00
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "b1c2d3e4f5a6"
down_revision = "4b991a668702"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "seasons",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("series_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("season_number", sa.Integer(), nullable=False),
        sa.Column("name", sa.String(length=255), nullable=True),
        sa.Column("cover", sa.String(length=800), nullable=True),
        sa.Column("episode_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("tmdb_id", sa.Integer(), nullable=True),
        sa.Column("air_date", sa.DateTime(), nullable=True),
        sa.Column("overview", sa.String(length=2000), nullable=True),
        sa.Column("poster_path", sa.String(length=255), nullable=True),
        sa.Column("is_synced", sa.Boolean(), nullable=False, server_default=sa.text("false")),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["series_id"], ["series_items.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("series_id", "season_number", name="uq_seasons_series_season"),
    )

    op.create_table(
        "episodes",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("season_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("provider_episode_id", sa.Integer(), nullable=False),
        sa.Column("episode_number", sa.Integer(), nullable=False),
        sa.Column("title", sa.String(length=500), nullable=True),
        sa.Column("container_extension", sa.String(length=20), nullable=True),
        sa.Column("duration_secs", sa.Integer(), nullable=True),
        sa.Column("info_json", postgresql.JSON(), nullable=True),
        sa.Column("tmdb_id", sa.Integer(), nullable=True),
        sa.Column("air_date", sa.DateTime(), nullable=True),
        sa.Column("overview", sa.String(length=2000), nullable=True),
        sa.Column("still_path", sa.String(length=255), nullable=True),
        sa.Column("vote_average", sa.Integer(), nullable=True),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["season_id"], ["seasons.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("season_id", "provider_episode_id", name="uq_episodes_season_providerid"),
        sa.UniqueConstraint("season_id", "episode_number", name="uq_episodes_season_epnum"),
    )

    op.create_index("ix_seasons_series_id", "seasons", ["series_id"])
    op.create_index("ix_episodes_season_id", "episodes", ["season_id"])



def downgrade():
    op.drop_index("ix_episodes_season_id", table_name="episodes")
    op.drop_index("ix_seasons_series_id", table_name="seasons")
    op.drop_table("episodes")
    op.drop_table("seasons")
