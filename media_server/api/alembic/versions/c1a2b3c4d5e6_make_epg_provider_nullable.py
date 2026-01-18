"""make epg provider columns nullable

Revision ID: c1a2b3c4d5e6
Revises: e2bed3130843
Create Date: 2026-01-18 03:15:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = "c1a2b3c4d5e6"
down_revision: Union[str, None] = "e2bed3130843"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.alter_column(
        "epg_channels",
        "provider_id",
        existing_type=sa.UUID(),
        nullable=True,
    )
    op.alter_column(
        "epg_programs",
        "provider_id",
        existing_type=sa.UUID(),
        nullable=True,
    )


def downgrade() -> None:
    op.alter_column(
        "epg_programs",
        "provider_id",
        existing_type=sa.UUID(),
        nullable=False,
    )
    op.alter_column(
        "epg_channels",
        "provider_id",
        existing_type=sa.UUID(),
        nullable=False,
    )
