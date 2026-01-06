"""add channel_number to live_streams

Revision ID: c9a1f6b2d0e1
Revises: 5ab2745bde13
Create Date: 2025-12-31

"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa

revision: str = "c9a1f6b2d0e1"
down_revision: Union[str, None] = "5ab2745bde13"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("live_streams", sa.Column("channel_number", sa.Integer(), nullable=True))

    # check: si se pone, debe ser > 0
    op.create_check_constraint(
        "ck_live_streams_channel_number_positive",
        "live_streams",
        "channel_number IS NULL OR channel_number > 0",
    )

    # index normal para ordenar/filtrar rápido
    op.create_index("ix_live_streams_channel_number", "live_streams", ["channel_number"], unique=False)

    # unique por provider cuando channel_number NO es null (evita duplicados)
    op.create_index(
        "uq_live_streams_provider_channel_number",
        "live_streams",
        ["provider_id", "channel_number"],
        unique=True,
        postgresql_where=sa.text("channel_number IS NOT NULL"),
    )


def downgrade() -> None:
    op.drop_index("uq_live_streams_provider_channel_number", table_name="live_streams")
    op.drop_index("ix_live_streams_channel_number", table_name="live_streams")
    op.drop_constraint("ck_live_streams_channel_number_positive", "live_streams", type_="check")
    op.drop_column("live_streams", "channel_number")
