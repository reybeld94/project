"""add

Revision ID: 5ab2745bde13
Revises: df4a017909cf
Create Date: 2025-12-31 06:44:35.113537

"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '5ab2745bde13'
down_revision: Union[str, None] = 'df4a017909cf'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("live_streams", sa.Column("approved", sa.Boolean(), nullable=False, server_default=sa.text("false")))

    op.add_column("live_streams", sa.Column("alt1_stream_id", sa.UUID(), nullable=True))
    op.add_column("live_streams", sa.Column("alt2_stream_id", sa.UUID(), nullable=True))
    op.add_column("live_streams", sa.Column("alt3_stream_id", sa.UUID(), nullable=True))

    op.create_foreign_key(
        "fk_live_streams_alt1",
        "live_streams",
        "live_streams",
        ["alt1_stream_id"],
        ["id"],
        ondelete="SET NULL",
    )
    op.create_foreign_key(
        "fk_live_streams_alt2",
        "live_streams",
        "live_streams",
        ["alt2_stream_id"],
        ["id"],
        ondelete="SET NULL",
    )
    op.create_foreign_key(
        "fk_live_streams_alt3",
        "live_streams",
        "live_streams",
        ["alt3_stream_id"],
        ["id"],
        ondelete="SET NULL",
    )

    # opcional pero recomendado: index para filtros rápidos
    op.create_index("ix_live_streams_approved", "live_streams", ["approved"], unique=False)

    # si quieres quitar el server_default después (opcional):
    op.alter_column("live_streams", "approved", server_default=None)


def downgrade() -> None:
    op.drop_index("ix_live_streams_approved", table_name="live_streams")

    op.drop_constraint("fk_live_streams_alt3", "live_streams", type_="foreignkey")
    op.drop_constraint("fk_live_streams_alt2", "live_streams", type_="foreignkey")
    op.drop_constraint("fk_live_streams_alt1", "live_streams", type_="foreignkey")

    op.drop_column("live_streams", "alt3_stream_id")
    op.drop_column("live_streams", "alt2_stream_id")
    op.drop_column("live_streams", "alt1_stream_id")

    op.drop_column("live_streams", "approved")