"""add tmdb normalized tables

Revision ID: 9b1c2d3e4f5a
Revises: 1f2b3c4d5e6f
Create Date: 2026-01-06 10:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = "9b1c2d3e4f5a"
down_revision: Union[str, None] = "1f2b3c4d5e6f"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade():
    op.create_table(
        "tmdb_entities",
        sa.Column("id", sa.UUID(), primary_key=True),
        sa.Column("tmdb_id", sa.Integer(), nullable=False),
        sa.Column("kind", sa.String(length=20), nullable=False),
        sa.Column("adult", sa.Boolean(), nullable=True),
        sa.Column("backdrop_path", sa.String(length=255), nullable=True),
        sa.Column("belongs_to_collection_id", sa.Integer(), nullable=True),
        sa.Column("belongs_to_collection_name", sa.String(length=255), nullable=True),
        sa.Column("belongs_to_collection_poster_path", sa.String(length=255), nullable=True),
        sa.Column("belongs_to_collection_backdrop_path", sa.String(length=255), nullable=True),
        sa.Column("budget", sa.Integer(), nullable=True),
        sa.Column("homepage", sa.String(length=500), nullable=True),
        sa.Column("imdb_id", sa.String(length=30), nullable=True),
        sa.Column("origin_language", sa.String(length=20), nullable=True),
        sa.Column("original_title", sa.String(length=255), nullable=True),
        sa.Column("overview", sa.String(length=4000), nullable=True),
        sa.Column("popularity", sa.Float(), nullable=True),
        sa.Column("poster_path", sa.String(length=255), nullable=True),
        sa.Column("release_date", sa.Date(), nullable=True),
        sa.Column("revenue", sa.Integer(), nullable=True),
        sa.Column("runtime", sa.Integer(), nullable=True),
        sa.Column("status", sa.String(length=80), nullable=True),
        sa.Column("tagline", sa.String(length=255), nullable=True),
        sa.Column("title", sa.String(length=255), nullable=True),
        sa.Column("video", sa.Boolean(), nullable=True),
        sa.Column("vote_average", sa.Float(), nullable=True),
        sa.Column("vote_count", sa.Integer(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.UniqueConstraint("tmdb_id", "kind", name="uq_tmdb_entities_tmdb_id_kind"),
    )
    op.create_index("ix_tmdb_entities_tmdb_id", "tmdb_entities", ["tmdb_id"])
    op.create_index("ix_tmdb_entities_kind", "tmdb_entities", ["kind"])

    op.create_table(
        "tmdb_genres",
        sa.Column("id", sa.UUID(), primary_key=True),
        sa.Column("tmdb_entity_id", sa.UUID(), sa.ForeignKey("tmdb_entities.id", ondelete="CASCADE"), nullable=False),
        sa.Column("genre_id", sa.Integer(), nullable=True),
        sa.Column("name", sa.String(length=120), nullable=True),
    )
    op.create_index("ix_tmdb_genres_entity_id", "tmdb_genres", ["tmdb_entity_id"])

    op.create_table(
        "tmdb_origin_countries",
        sa.Column("id", sa.UUID(), primary_key=True),
        sa.Column("tmdb_entity_id", sa.UUID(), sa.ForeignKey("tmdb_entities.id", ondelete="CASCADE"), nullable=False),
        sa.Column("iso_3166_1", sa.String(length=8), nullable=True),
    )
    op.create_index("ix_tmdb_origin_countries_entity_id", "tmdb_origin_countries", ["tmdb_entity_id"])

    op.create_table(
        "tmdb_production_companies",
        sa.Column("id", sa.UUID(), primary_key=True),
        sa.Column("tmdb_entity_id", sa.UUID(), sa.ForeignKey("tmdb_entities.id", ondelete="CASCADE"), nullable=False),
        sa.Column("company_id", sa.Integer(), nullable=True),
        sa.Column("name", sa.String(length=255), nullable=True),
        sa.Column("logo_path", sa.String(length=255), nullable=True),
        sa.Column("origin_country", sa.String(length=8), nullable=True),
    )
    op.create_index("ix_tmdb_production_companies_entity_id", "tmdb_production_companies", ["tmdb_entity_id"])

    op.create_table(
        "tmdb_production_countries",
        sa.Column("id", sa.UUID(), primary_key=True),
        sa.Column("tmdb_entity_id", sa.UUID(), sa.ForeignKey("tmdb_entities.id", ondelete="CASCADE"), nullable=False),
        sa.Column("iso_3166_1", sa.String(length=8), nullable=True),
        sa.Column("name", sa.String(length=120), nullable=True),
    )
    op.create_index("ix_tmdb_production_countries_entity_id", "tmdb_production_countries", ["tmdb_entity_id"])

    op.create_table(
        "tmdb_spoken_languages",
        sa.Column("id", sa.UUID(), primary_key=True),
        sa.Column("tmdb_entity_id", sa.UUID(), sa.ForeignKey("tmdb_entities.id", ondelete="CASCADE"), nullable=False),
        sa.Column("english_name", sa.String(length=120), nullable=True),
        sa.Column("iso_639_1", sa.String(length=12), nullable=True),
        sa.Column("name", sa.String(length=120), nullable=True),
    )
    op.create_index("ix_tmdb_spoken_languages_entity_id", "tmdb_spoken_languages", ["tmdb_entity_id"])

    op.create_table(
        "tmdb_cast",
        sa.Column("id", sa.UUID(), primary_key=True),
        sa.Column("tmdb_entity_id", sa.UUID(), sa.ForeignKey("tmdb_entities.id", ondelete="CASCADE"), nullable=False),
        sa.Column("person_id", sa.Integer(), nullable=True),
        sa.Column("name", sa.String(length=255), nullable=True),
        sa.Column("original_name", sa.String(length=255), nullable=True),
        sa.Column("known_for_department", sa.String(length=80), nullable=True),
        sa.Column("popularity", sa.Float(), nullable=True),
        sa.Column("profile_path", sa.String(length=255), nullable=True),
        sa.Column("adult", sa.Boolean(), nullable=True),
        sa.Column("gender", sa.Integer(), nullable=True),
        sa.Column("cast_id", sa.Integer(), nullable=True),
        sa.Column("character", sa.String(length=255), nullable=True),
        sa.Column("credit_id", sa.String(length=64), nullable=True),
        sa.Column("order_index", sa.Integer(), nullable=True),
    )
    op.create_index("ix_tmdb_cast_entity_id", "tmdb_cast", ["tmdb_entity_id"])

    op.create_table(
        "tmdb_crew",
        sa.Column("id", sa.UUID(), primary_key=True),
        sa.Column("tmdb_entity_id", sa.UUID(), sa.ForeignKey("tmdb_entities.id", ondelete="CASCADE"), nullable=False),
        sa.Column("person_id", sa.Integer(), nullable=True),
        sa.Column("name", sa.String(length=255), nullable=True),
        sa.Column("original_name", sa.String(length=255), nullable=True),
        sa.Column("known_for_department", sa.String(length=80), nullable=True),
        sa.Column("popularity", sa.Float(), nullable=True),
        sa.Column("profile_path", sa.String(length=255), nullable=True),
        sa.Column("adult", sa.Boolean(), nullable=True),
        sa.Column("gender", sa.Integer(), nullable=True),
        sa.Column("department", sa.String(length=120), nullable=True),
        sa.Column("job", sa.String(length=120), nullable=True),
        sa.Column("credit_id", sa.String(length=64), nullable=True),
    )
    op.create_index("ix_tmdb_crew_entity_id", "tmdb_crew", ["tmdb_entity_id"])

    op.create_table(
        "tmdb_videos",
        sa.Column("id", sa.UUID(), primary_key=True),
        sa.Column("tmdb_entity_id", sa.UUID(), sa.ForeignKey("tmdb_entities.id", ondelete="CASCADE"), nullable=False),
        sa.Column("tmdb_video_id", sa.String(length=80), nullable=True),
        sa.Column("iso_639_1", sa.String(length=12), nullable=True),
        sa.Column("iso_3166_1", sa.String(length=8), nullable=True),
        sa.Column("name", sa.String(length=255), nullable=True),
        sa.Column("key", sa.String(length=120), nullable=True),
        sa.Column("site", sa.String(length=60), nullable=True),
        sa.Column("size", sa.Integer(), nullable=True),
        sa.Column("type", sa.String(length=60), nullable=True),
        sa.Column("official", sa.Boolean(), nullable=True),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index("ix_tmdb_videos_entity_id", "tmdb_videos", ["tmdb_entity_id"])

    op.create_table(
        "tmdb_images",
        sa.Column("id", sa.UUID(), primary_key=True),
        sa.Column("tmdb_entity_id", sa.UUID(), sa.ForeignKey("tmdb_entities.id", ondelete="CASCADE"), nullable=False),
        sa.Column("image_type", sa.String(length=20), nullable=False),
        sa.Column("aspect_ratio", sa.Float(), nullable=True),
        sa.Column("height", sa.Integer(), nullable=True),
        sa.Column("iso_3166_1", sa.String(length=8), nullable=True),
        sa.Column("iso_639_1", sa.String(length=12), nullable=True),
        sa.Column("file_path", sa.String(length=255), nullable=True),
        sa.Column("vote_average", sa.Float(), nullable=True),
        sa.Column("vote_count", sa.Integer(), nullable=True),
        sa.Column("width", sa.Integer(), nullable=True),
    )
    op.create_index("ix_tmdb_images_entity_id", "tmdb_images", ["tmdb_entity_id"])

    op.create_table(
        "tmdb_release_dates",
        sa.Column("id", sa.UUID(), primary_key=True),
        sa.Column("tmdb_entity_id", sa.UUID(), sa.ForeignKey("tmdb_entities.id", ondelete="CASCADE"), nullable=False),
        sa.Column("iso_3166_1", sa.String(length=8), nullable=True),
        sa.Column("certification", sa.String(length=30), nullable=True),
        sa.Column("descriptors", sa.JSON(), nullable=True),
        sa.Column("iso_639_1", sa.String(length=12), nullable=True),
        sa.Column("note", sa.String(length=255), nullable=True),
        sa.Column("release_date", sa.DateTime(timezone=True), nullable=True),
        sa.Column("release_type", sa.Integer(), nullable=True),
    )
    op.create_index("ix_tmdb_release_dates_entity_id", "tmdb_release_dates", ["tmdb_entity_id"])


def downgrade():
    op.drop_index("ix_tmdb_release_dates_entity_id", table_name="tmdb_release_dates")
    op.drop_table("tmdb_release_dates")

    op.drop_index("ix_tmdb_images_entity_id", table_name="tmdb_images")
    op.drop_table("tmdb_images")

    op.drop_index("ix_tmdb_videos_entity_id", table_name="tmdb_videos")
    op.drop_table("tmdb_videos")

    op.drop_index("ix_tmdb_crew_entity_id", table_name="tmdb_crew")
    op.drop_table("tmdb_crew")

    op.drop_index("ix_tmdb_cast_entity_id", table_name="tmdb_cast")
    op.drop_table("tmdb_cast")

    op.drop_index("ix_tmdb_spoken_languages_entity_id", table_name="tmdb_spoken_languages")
    op.drop_table("tmdb_spoken_languages")

    op.drop_index("ix_tmdb_production_countries_entity_id", table_name="tmdb_production_countries")
    op.drop_table("tmdb_production_countries")

    op.drop_index("ix_tmdb_production_companies_entity_id", table_name="tmdb_production_companies")
    op.drop_table("tmdb_production_companies")

    op.drop_index("ix_tmdb_origin_countries_entity_id", table_name="tmdb_origin_countries")
    op.drop_table("tmdb_origin_countries")

    op.drop_index("ix_tmdb_genres_entity_id", table_name="tmdb_genres")
    op.drop_table("tmdb_genres")

    op.drop_index("ix_tmdb_entities_kind", table_name="tmdb_entities")
    op.drop_index("ix_tmdb_entities_tmdb_id", table_name="tmdb_entities")
    op.drop_table("tmdb_entities")
