"""add normalized_name to live_streams

Revision ID: e24b366bcec2
Revises: c9a1f6b2d0e1
Create Date: 2025-12-31 15:33:46.278719

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'e24b366bcec2'
down_revision: Union[str, None] = 'c9a1f6b2d0e1'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "live_streams",
        sa.Column("normalized_name", sa.String(length=255), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("live_streams", "normalized_name")