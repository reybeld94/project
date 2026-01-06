"""add epg_sources and map live streams

Revision ID: e2bed3130843
Revises: e24b366bcec2
Create Date: 2025-12-31 16:25:34.271970

"""
from typing import Sequence, Union
from sqlalchemy.dialects import postgresql
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'e2bed3130843'
down_revision: Union[str, None] = 'e24b366bcec2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade():
    op.create_table(
        "epg_sources",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("name", sa.String(length=120), nullable=False),
        sa.Column("xmltv_url", sa.String(length=1200), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default=sa.text("true")),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )

    op.add_column("live_streams", sa.Column("epg_source_id", postgresql.UUID(as_uuid=True), nullable=True))
    op.create_foreign_key("fk_live_streams_epg_source", "live_streams", "epg_sources", ["epg_source_id"], ["id"])

    op.add_column("epg_channels", sa.Column("epg_source_id", postgresql.UUID(as_uuid=True), nullable=True))
    op.create_foreign_key("fk_epg_channels_epg_source", "epg_channels", "epg_sources", ["epg_source_id"], ["id"])

    op.add_column("epg_programs", sa.Column("epg_source_id", postgresql.UUID(as_uuid=True), nullable=True))
    op.create_foreign_key("fk_epg_programs_epg_source", "epg_programs", "epg_sources", ["epg_source_id"], ["id"])

    # OJO: cambiar constraints únicos de epg_channels requiere drop/create:
    op.drop_constraint("uq_epg_channels_provider_xmltvid", "epg_channels", type_="unique")
    op.create_unique_constraint("uq_epg_channels_source_xmltvid", "epg_channels", ["epg_source_id", "xmltv_id"])