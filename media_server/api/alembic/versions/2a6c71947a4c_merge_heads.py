"""merge heads

Revision ID: 2a6c71947a4c
Revises: 1f2b3c4d5e6f, 3c1f2e4d5a6b
Create Date: 2026-01-09 00:49:00.438419

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '2a6c71947a4c'
down_revision: Union[str, None] = ('1f2b3c4d5e6f', '3c1f2e4d5a6b')
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    pass


def downgrade() -> None:
    pass
