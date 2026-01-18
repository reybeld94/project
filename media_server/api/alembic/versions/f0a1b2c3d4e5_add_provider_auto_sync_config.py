"""add provider auto sync config

Revision ID: f0a1b2c3d4e5
Revises: b1c2d3e4f5a6, 3c1f2e4d5a6b
Create Date: 2025-02-14 00:00:00.000000

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = "f0a1b2c3d4e5"
down_revision = ("b1c2d3e4f5a6", "3c1f2e4d5a6b")
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "provider_auto_sync_config",
        sa.Column("id", sa.dialects.postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("interval_minutes", sa.Integer(), server_default="60", nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )


def downgrade():
    op.drop_table("provider_auto_sync_config")
