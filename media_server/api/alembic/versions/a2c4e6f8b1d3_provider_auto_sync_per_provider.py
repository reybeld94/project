"""provider auto sync per provider

Revision ID: a2c4e6f8b1d3
Revises: 0f6c3b1e2d5a
Create Date: 2026-01-05 10:00:00.000000

"""
from typing import Sequence, Union
from uuid import uuid4

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = "a2c4e6f8b1d3"
down_revision: Union[str, None] = "0f6c3b1e2d5a"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade():
    op.add_column("provider_auto_sync_config", sa.Column("provider_id", sa.UUID(), nullable=True))
    op.add_column("provider_auto_sync_config", sa.Column("last_run_at", sa.DateTime(timezone=True), nullable=True))

    conn = op.get_bind()
    interval = conn.execute(
        sa.text("SELECT interval_minutes FROM provider_auto_sync_config LIMIT 1")
    ).scalar()
    if interval is None:
        interval = 60

    conn.execute(sa.text("DELETE FROM provider_auto_sync_config"))
    providers = conn.execute(sa.text("SELECT id FROM providers")).fetchall()
    for row in providers:
        conn.execute(
            sa.text(
                """
                INSERT INTO provider_auto_sync_config (id, provider_id, interval_minutes, created_at, updated_at)
                VALUES (:id, :provider_id, :interval, now(), now())
                """
            ),
            {
                "id": str(uuid4()),
                "provider_id": row[0],
                "interval": interval,
            },
        )

    op.alter_column("provider_auto_sync_config", "provider_id", nullable=False)
    op.create_unique_constraint(
        "uq_provider_auto_sync_provider_id",
        "provider_auto_sync_config",
        ["provider_id"],
    )


def downgrade():
    conn = op.get_bind()
    interval = conn.execute(
        sa.text("SELECT interval_minutes FROM provider_auto_sync_config LIMIT 1")
    ).scalar()
    if interval is None:
        interval = 60

    conn.execute(sa.text("DELETE FROM provider_auto_sync_config"))

    op.drop_constraint(
        "uq_provider_auto_sync_provider_id",
        "provider_auto_sync_config",
        type_="unique",
    )
    op.drop_column("provider_auto_sync_config", "last_run_at")
    op.drop_column("provider_auto_sync_config", "provider_id")

    conn.execute(
        sa.text(
            """
            INSERT INTO provider_auto_sync_config (id, interval_minutes, created_at, updated_at)
            VALUES (:id, :interval, now(), now())
            """
        ),
        {
            "id": str(uuid4()),
            "interval": interval,
        },
    )
