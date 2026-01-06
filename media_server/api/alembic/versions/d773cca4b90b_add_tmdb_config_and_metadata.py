"""add tmdb config and metadata

Revision ID: d773cca4b90b
Revises: da111e872b3a
Create Date: 2026-01-04 21:36:40.269735

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'd773cca4b90b'
down_revision: Union[str, None] = 'da111e872b3a'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade():
    # --- TMDB CONFIG TABLE ---
    op.create_table(
        "tmdb_config",
        sa.Column("id", sa.UUID(), primary_key=True),
        sa.Column("is_enabled", sa.Boolean(), nullable=False, server_default=sa.text("false")),
        sa.Column("api_key", sa.String(length=128)),
        sa.Column("read_access_token", sa.String(length=512)),
        sa.Column("language", sa.String(length=16), server_default="en-US"),
        sa.Column("region", sa.String(length=8), server_default="US"),
        sa.Column("requests_per_second", sa.Integer(), nullable=False, server_default="5"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()")),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()")),
    )

    # --- VOD STREAMS ---
    op.add_column("vod_streams", sa.Column("tmdb_id", sa.Integer()))
    op.add_column("vod_streams", sa.Column("tmdb_status", sa.String(length=20), nullable=False, server_default="missing"))
    op.add_column("vod_streams", sa.Column("tmdb_last_sync", sa.DateTime(timezone=True)))
    op.add_column("vod_streams", sa.Column("tmdb_error", sa.String(length=500)))
    op.add_column("vod_streams", sa.Column("tmdb_title", sa.String(length=255)))
    op.add_column("vod_streams", sa.Column("tmdb_overview", sa.String(length=4000)))
    op.add_column("vod_streams", sa.Column("tmdb_release_date", sa.Date()))
    op.add_column("vod_streams", sa.Column("tmdb_genres", sa.JSON()))
    op.add_column("vod_streams", sa.Column("tmdb_vote_average", sa.Float()))
    op.add_column("vod_streams", sa.Column("tmdb_poster_path", sa.String(length=255)))
    op.add_column("vod_streams", sa.Column("tmdb_backdrop_path", sa.String(length=255)))
    op.add_column("vod_streams", sa.Column("tmdb_raw", sa.JSON()))

    op.create_index("ix_vod_tmdb_status", "vod_streams", ["tmdb_status"])

    # --- SERIES ---
    op.add_column("series_items", sa.Column("tmdb_id", sa.Integer()))
    op.add_column("series_items", sa.Column("tmdb_status", sa.String(length=20), nullable=False, server_default="missing"))
    op.add_column("series_items", sa.Column("tmdb_last_sync", sa.DateTime(timezone=True)))
    op.add_column("series_items", sa.Column("tmdb_error", sa.String(length=500)))
    op.add_column("series_items", sa.Column("tmdb_title", sa.String(length=255)))
    op.add_column("series_items", sa.Column("tmdb_overview", sa.String(length=4000)))
    op.add_column("series_items", sa.Column("tmdb_release_date", sa.Date()))
    op.add_column("series_items", sa.Column("tmdb_genres", sa.JSON()))
    op.add_column("series_items", sa.Column("tmdb_vote_average", sa.Float()))
    op.add_column("series_items", sa.Column("tmdb_poster_path", sa.String(length=255)))
    op.add_column("series_items", sa.Column("tmdb_backdrop_path", sa.String(length=255)))
    op.add_column("series_items", sa.Column("tmdb_raw", sa.JSON()))

    op.create_index("ix_series_tmdb_status", "series_items", ["tmdb_status"])


def downgrade():
    op.drop_index("ix_series_tmdb_status", table_name="series_items")
    op.drop_index("ix_vod_tmdb_status", table_name="vod_streams")

    op.drop_column("series_items", "tmdb_raw")
    op.drop_column("series_items", "tmdb_backdrop_path")
    op.drop_column("series_items", "tmdb_poster_path")
    op.drop_column("series_items", "tmdb_vote_average")
    op.drop_column("series_items", "tmdb_genres")
    op.drop_column("series_items", "tmdb_release_date")
    op.drop_column("series_items", "tmdb_overview")
    op.drop_column("series_items", "tmdb_title")
    op.drop_column("series_items", "tmdb_error")
    op.drop_column("series_items", "tmdb_last_sync")
    op.drop_column("series_items", "tmdb_status")
    op.drop_column("series_items", "tmdb_id")

    op.drop_column("vod_streams", "tmdb_raw")
    op.drop_column("vod_streams", "tmdb_backdrop_path")
    op.drop_column("vod_streams", "tmdb_poster_path")
    op.drop_column("vod_streams", "tmdb_vote_average")
    op.drop_column("vod_streams", "tmdb_genres")
    op.drop_column("vod_streams", "tmdb_release_date")
    op.drop_column("vod_streams", "tmdb_overview")
    op.drop_column("vod_streams", "tmdb_title")
    op.drop_column("vod_streams", "tmdb_error")
    op.drop_column("vod_streams", "tmdb_last_sync")
    op.drop_column("vod_streams", "tmdb_status")
    op.drop_column("vod_streams", "tmdb_id")

    op.drop_table("tmdb_config")