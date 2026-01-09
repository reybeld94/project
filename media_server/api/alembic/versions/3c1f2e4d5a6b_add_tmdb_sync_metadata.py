"""add tmdb sync metadata

Revision ID: 3c1f2e4d5a6b
Revises: 0f6c3b1e2d5a
Create Date: 2025-02-14 00:00:00.000000
"""

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = "3c1f2e4d5a6b"
down_revision = "0f6c3b1e2d5a"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("vod_streams", sa.Column("tmdb_error_kind", sa.String(length=30), nullable=True))
    op.add_column("vod_streams", sa.Column("tmdb_fail_count", sa.Integer(), server_default="0", nullable=False))
    op.add_column("series_items", sa.Column("tmdb_error_kind", sa.String(length=30), nullable=True))
    op.add_column("series_items", sa.Column("tmdb_fail_count", sa.Integer(), server_default="0", nullable=False))

    op.execute(
        """
        DELETE FROM series_items s
        USING (
            SELECT id,
                   row_number() OVER (
                       PARTITION BY provider_id, tmdb_id
                       ORDER BY created_at DESC, id DESC
                   ) AS rn
            FROM series_items
            WHERE tmdb_id IS NOT NULL
        ) dup
        WHERE s.id = dup.id AND dup.rn > 1
        """
    )

    op.create_index(
        "uq_series_provider_tmdb_id",
        "series_items",
        ["provider_id", "tmdb_id"],
        unique=True,
        postgresql_where=sa.text("tmdb_id IS NOT NULL"),
    )


def downgrade():
    op.drop_index("uq_series_provider_tmdb_id", table_name="series_items")
    op.drop_column("series_items", "tmdb_fail_count")
    op.drop_column("series_items", "tmdb_error_kind")
    op.drop_column("vod_streams", "tmdb_fail_count")
    op.drop_column("vod_streams", "tmdb_error_kind")
