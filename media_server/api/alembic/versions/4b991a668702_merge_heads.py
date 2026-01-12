"""merge heads

Revision ID: 4b991a668702
Revises: 2a6c71947a4c, 9b1c2d3e4f5a
Create Date: 2026-01-11 19:38:42.187525

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '4b991a668702'
down_revision: Union[str, None] = ('2a6c71947a4c', '9b1c2d3e4f5a')
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    pass


def downgrade() -> None:
    pass
