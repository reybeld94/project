"""add provider users system

Revision ID: b2d3e4f5a6c7
Revises: a2c4e6f8b1d3
Create Date: 2026-01-18 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID


# revision identifiers, used by Alembic.
revision: str = "b2d3e4f5a6c7"
down_revision: Union[str, None] = "a2c4e6f8b1d3"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade():
    # Create provider_users table
    op.create_table(
        "provider_users",
        sa.Column("id", UUID(as_uuid=True), nullable=False),
        sa.Column("provider_id", UUID(as_uuid=True), nullable=False),
        sa.Column("username", sa.String(120), nullable=False),
        sa.Column("password", sa.String(200), nullable=False),
        sa.Column("unique_code", sa.String(6), nullable=False),
        sa.Column("alias", sa.String(120), nullable=False),
        sa.Column("is_enabled", sa.Boolean(), server_default="true", nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["provider_id"], ["providers.id"], ondelete="CASCADE"),
        sa.UniqueConstraint("unique_code", name="uq_provider_users_unique_code"),
        sa.UniqueConstraint("provider_id", "username", name="uq_provider_users_provider_username"),
    )

    # Create indexes
    op.create_index("ix_provider_users_unique_code", "provider_users", ["unique_code"])
    op.create_index("ix_provider_users_provider_id", "provider_users", ["provider_id"])

    # Make provider username and password nullable for new provider creation flow
    op.alter_column("providers", "username", nullable=True)
    op.alter_column("providers", "password", nullable=True)


def downgrade():
    # Restore provider username and password as required
    op.alter_column("providers", "username", nullable=False)
    op.alter_column("providers", "password", nullable=False)

    # Drop indexes
    op.drop_index("ix_provider_users_provider_id", table_name="provider_users")
    op.drop_index("ix_provider_users_unique_code", table_name="provider_users")

    # Drop provider_users table
    op.drop_table("provider_users")
