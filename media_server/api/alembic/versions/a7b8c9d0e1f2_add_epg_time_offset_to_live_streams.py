"""add epg_time_offset to live_streams

Revision ID: a7b8c9d0e1f2
Revises: f0a1b2c3d4e5
Create Date: 2026-01-21 00:00:00.000000

"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'a7b8c9d0e1f2'
down_revision: Union[str, None] = 'f0a1b2c3d4e5'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade():
    """
    Add epg_time_offset column to live_streams table.
    This allows per-channel time offset adjustment for EPG programs (in minutes).
    Positive values shift programs forward in time, negative values shift them backward.
    """
    op.add_column(
        "live_streams",
        sa.Column("epg_time_offset", sa.Integer(), nullable=True)
    )


def downgrade():
    """Remove epg_time_offset column from live_streams table."""
    op.drop_column("live_streams", "epg_time_offset")
