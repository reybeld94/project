"""add unique tmdb index to vod streams

Revision ID: 1f2b3c4d5e6f
Revises: 0f6c3b1e2d5a
Create Date: 2026-01-05 10:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = "1f2b3c4d5e6f"
down_revision: Union[str, None] = "0f6c3b1e2d5a"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade():
    op.create_index(
        "uq_vod_streams_provider_tmdb_id",
        "vod_streams",
        ["provider_id", "tmdb_id"],
        unique=True,
        postgresql_where=sa.text("tmdb_id IS NOT NULL"),
    )


def downgrade():
    op.drop_index(
        "uq_vod_streams_provider_tmdb_id",
        table_name="vod_streams",
        postgresql_where=sa.text("tmdb_id IS NOT NULL"),
    )
