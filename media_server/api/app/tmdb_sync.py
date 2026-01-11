from __future__ import annotations

import asyncio
import logging
import os
import time
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Iterable

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db import SessionLocal
from app.models import SeriesItem, TmdbConfig, VodStream
from app.tmdb_client import TmdbAsyncClient, TmdbRequestError, _clean_title_and_year, _pick_best_result

log = logging.getLogger(__name__)


@dataclass
class TmdbSyncSettings:
    workers: int = 2
    rps: int = 5
    burst: int = 10
    cooldown_missing_minutes: int = 15
    cooldown_failed_minutes: int = 120
    cooldown_transient_minutes: int = 15
    cooldown_invalid_days: int = 7
    resync_days: int = 14

    @classmethod
    def from_env(cls, cfg: TmdbConfig) -> "TmdbSyncSettings":
        def _env_int(key: str, default: int) -> int:
            raw = os.getenv(key)
            if raw is None or raw == "":
                return default
            try:
                return int(raw)
            except ValueError:
                return default

        rps = _env_int("TMDB_RPS", cfg.requests_per_second or 5)
        return cls(
            workers=_env_int("TMDB_SYNC_WORKERS", 2),
            rps=rps,
            burst=_env_int("TMDB_BURST", 10),
            cooldown_missing_minutes=_env_int("TMDB_COOLDOWN_MISSING", 15),
            cooldown_failed_minutes=_env_int("TMDB_COOLDOWN_FAILED", 120),
            cooldown_transient_minutes=_env_int("TMDB_COOLDOWN_TRANSIENT", 15),
            cooldown_invalid_days=_env_int("TMDB_COOLDOWN_INVALID_DAYS", 7),
            resync_days=_env_int("TMDB_RESYNC_DAYS", 14),
        )


@dataclass
class TmdbSyncMetrics:
    queued: int = 0
    processed: int = 0
    synced: int = 0
    missing: int = 0
    failed: int = 0
    requests_total: int = 0
    retry_total: int = 0
    retry_by_kind: dict[str, int] = field(default_factory=dict)
    rate_limited: int = 0
    started_at: float = field(default_factory=time.monotonic)
    finished_at: float | None = None

    def record_request(self) -> None:
        self.requests_total += 1

    def record_retry(self, kind: str) -> None:
        self.retry_total += 1
        self.retry_by_kind[kind] = self.retry_by_kind.get(kind, 0) + 1
        if kind == "rate_limited":
            self.rate_limited += 1

    def finish(self) -> None:
        self.finished_at = time.monotonic()

    @property
    def elapsed_s(self) -> float:
        end = self.finished_at if self.finished_at is not None else time.monotonic()
        return max(0.0, end - self.started_at)

    @property
    def avg_time_per_item(self) -> float:
        if self.processed == 0:
            return 0.0
        return self.elapsed_s / float(self.processed)

    @property
    def throughput_per_s(self) -> float:
        if self.elapsed_s <= 0:
            return 0.0
        return self.processed / self.elapsed_s

    def eta_seconds(self, remaining: int) -> float:
        rate = self.throughput_per_s
        if rate <= 0:
            return 0.0
        return remaining / rate


@dataclass
class TmdbSyncTask:
    kind: str
    item_id: object


def _append_to_response(kind: str) -> str:
    if kind == "movie":
        return "credits,videos,images,release_dates"
    return "credits,videos,images,content_ratings"


def _dedupe_vod_by_tmdb_id(db: Session, provider_id, tmdb_id: int | None) -> None:
    if tmdb_id is None:
        return
    group = db.execute(
        select(VodStream)
        .where(VodStream.provider_id == provider_id, VodStream.tmdb_id == tmdb_id)
        .order_by(VodStream.created_at.desc(), VodStream.id.desc())
    ).scalars().all()
    if len(group) < 2:
        return
    winner = group[0]
    synced_donor = next((item for item in group if item.tmdb_status == "synced"), None)
    if winner.tmdb_status != "synced" and synced_donor:
        _copy_tmdb_fields_vod(winner, synced_donor)
        winner.tmdb_status = "synced"
        winner.tmdb_error = None
        winner.tmdb_error_kind = None
        winner.tmdb_fail_count = 0
    for dup in group[1:]:
        db.delete(dup)


def _dedupe_series_by_tmdb_id(db: Session, provider_id, tmdb_id: int | None) -> None:
    if tmdb_id is None:
        return
    group = db.execute(
        select(SeriesItem)
        .where(SeriesItem.provider_id == provider_id, SeriesItem.tmdb_id == tmdb_id)
        .order_by(SeriesItem.created_at.desc(), SeriesItem.id.desc())
    ).scalars().all()
    if len(group) < 2:
        return
    winner = group[0]
    synced_donor = next((item for item in group if item.tmdb_status == "synced"), None)
    if winner.tmdb_status != "synced" and synced_donor:
        _copy_tmdb_fields_series(winner, synced_donor)
        winner.tmdb_status = "synced"
        winner.tmdb_error = None
        winner.tmdb_error_kind = None
        winner.tmdb_fail_count = 0
    for dup in group[1:]:
        db.delete(dup)


def _copy_tmdb_fields_vod(target: VodStream, source: VodStream) -> None:
    target.tmdb_id = source.tmdb_id
    target.tmdb_status = source.tmdb_status
    target.tmdb_last_sync = source.tmdb_last_sync
    target.tmdb_error = None
    target.tmdb_title = source.tmdb_title
    target.tmdb_overview = source.tmdb_overview
    target.tmdb_release_date = source.tmdb_release_date
    target.tmdb_genres = source.tmdb_genres
    target.tmdb_vote_average = source.tmdb_vote_average
    target.tmdb_poster_path = source.tmdb_poster_path
    target.tmdb_backdrop_path = source.tmdb_backdrop_path
    target.tmdb_raw = source.tmdb_raw


def _copy_tmdb_fields_series(target: SeriesItem, source: SeriesItem) -> None:
    target.tmdb_id = source.tmdb_id
    target.tmdb_status = source.tmdb_status
    target.tmdb_last_sync = source.tmdb_last_sync
    target.tmdb_error = None
    target.tmdb_title = source.tmdb_title
    target.tmdb_overview = source.tmdb_overview
    target.tmdb_release_date = source.tmdb_release_date
    target.tmdb_genres = source.tmdb_genres
    target.tmdb_vote_average = source.tmdb_vote_average
    target.tmdb_poster_path = source.tmdb_poster_path
    target.tmdb_backdrop_path = source.tmdb_backdrop_path
    target.tmdb_raw = source.tmdb_raw


def _calculate_failed_cooldown(settings: TmdbSyncSettings, fail_count: int, error_kind: str | None) -> timedelta:
    if error_kind in {"rate_limited", "timeout", "server", "network"}:
        return timedelta(minutes=settings.cooldown_transient_minutes)
    if error_kind in {"not_found", "invalid"}:
        return timedelta(days=settings.cooldown_invalid_days)
    multiplier = 2 ** max(0, (fail_count or 1) - 1)
    return timedelta(minutes=settings.cooldown_failed_minutes * multiplier)


def _normalize_sync_time(value: datetime | None) -> datetime | None:
    if value is None:
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value


def _eligible_for_sync(item, now: datetime, settings: TmdbSyncSettings) -> bool:
    last_sync = _normalize_sync_time(item.tmdb_last_sync)
    status = (item.tmdb_status or "missing").lower()
    if status == "synced":
        if not last_sync:
            return False
        return last_sync < now - timedelta(days=settings.resync_days)
    if status == "missing":
        if not last_sync:
            return True
        return last_sync < now - timedelta(minutes=settings.cooldown_missing_minutes)
    if status == "failed":
        if not last_sync:
            return True
        cooldown = _calculate_failed_cooldown(settings, getattr(item, "tmdb_fail_count", 0), getattr(item, "tmdb_error_kind", None))
        return last_sync < now - cooldown
    return True


def _select_candidates(
    db: Session,
    model,
    *,
    limit: int,
    settings: TmdbSyncSettings,
) -> list:
    now = datetime.now(timezone.utc)
    candidate_limit = min(max(limit * 5, limit), 1000)
    stmt = select(model)
    stmt = stmt.order_by(model.tmdb_last_sync.asc().nullsfirst(), model.created_at.asc())
    rows = db.execute(stmt.limit(candidate_limit)).scalars().all()
    picked = []
    for row in rows:
        if _eligible_for_sync(row, now, settings):
            picked.append(row)
        if len(picked) >= limit:
            break
    return picked


async def _sync_one_task(
    task: TmdbSyncTask,
    *,
    settings: TmdbSyncSettings,
    token: str | None,
    api_key: str | None,
    language: str,
    region: str,
    client: TmdbAsyncClient,
    metrics: TmdbSyncMetrics,
) -> None:
    db = SessionLocal()
    item = None
    try:
        model = VodStream if task.kind == "movie" else SeriesItem
        item = db.execute(select(model).where(model.id == task.item_id)).scalar_one_or_none()
        if not item:
            return

        name = (item.name or "").strip()
        normalized_name = (item.normalized_name or "").strip()
        if not name and not normalized_name and not item.tmdb_id:
            return

        details = None
        best = None
        resolved_tmdb_id = None

        if item.tmdb_id:
            resolved_tmdb_id = int(item.tmdb_id)
            details = await client.get_json(
                f"/{'movie' if task.kind == 'movie' else 'tv'}/{resolved_tmdb_id}",
                params={"language": language, "append_to_response": _append_to_response(task.kind)},
            )
        else:
            search_path = "/search/movie" if task.kind == "movie" else "/search/tv"
            candidate_titles: list[str] = []
            if name:
                candidate_titles.append(name)
            if normalized_name and normalized_name != name:
                candidate_titles.append(normalized_name)

            for candidate in candidate_titles:
                wanted, year = _clean_title_and_year(candidate)
                params = {"query": wanted, "language": language}
                if task.kind == "movie":
                    params["region"] = region
                    if year:
                        params["year"] = year
                else:
                    if year:
                        params["first_air_date_year"] = year

                search = await client.get_json(search_path, params=params)
                best = _pick_best_result(
                    search.get("results") or [],
                    wanted,
                    year,
                    "release_date" if task.kind == "movie" else "first_air_date",
                )
                if best:
                    break
            if not best:
                if db.in_transaction():
                    db.rollback()
                with db.begin():
                    item.tmdb_status = "missing"
                    item.tmdb_error = None
                    item.tmdb_error_kind = "not_found"
                    item.tmdb_last_sync = datetime.now(timezone.utc)
                    item.tmdb_fail_count = 0
                metrics.missing += 1
                return

            resolved_tmdb_id = int(best.get("id"))
            details = await client.get_json(
                f"/{'movie' if task.kind == 'movie' else 'tv'}/{resolved_tmdb_id}",
                params={"language": language, "append_to_response": _append_to_response(task.kind)},
            )

        if not details:
            if db.in_transaction():
                db.rollback()
            with db.begin():
                item.tmdb_status = "missing"
                item.tmdb_error = None
                item.tmdb_error_kind = "not_found"
                item.tmdb_last_sync = datetime.now(timezone.utc)
                item.tmdb_fail_count = 0
            metrics.missing += 1
            return

        now = datetime.now(timezone.utc)
        if db.in_transaction():
            db.rollback()
        with db.begin():
            tmdb_id = int(details.get("id")) if details.get("id") is not None else resolved_tmdb_id
            target = item
            if tmdb_id is not None:
                dup = db.execute(
                    select(model)
                    .where(model.provider_id == item.provider_id, model.tmdb_id == tmdb_id, model.id != item.id)
                    .order_by(model.created_at.desc(), model.id.desc())
                ).scalars().first()
                if dup:
                    if dup.tmdb_status == "synced" and item.tmdb_status != "synced":
                        target = dup
                        db.delete(item)
                    else:
                        db.delete(dup)
                        db.flush()
            target.tmdb_id = tmdb_id
            target.tmdb_status = "synced"
            target.tmdb_error = None
            target.tmdb_error_kind = None
            target.tmdb_fail_count = 0
            target.tmdb_last_sync = now
            if task.kind == "movie":
                target.tmdb_title = details.get("title")
                rd = (details.get("release_date") or "").strip()
                if rd:
                    try:
                        target.tmdb_release_date = datetime.strptime(rd, "%Y-%m-%d")
                    except Exception:
                        target.tmdb_release_date = None
            else:
                target.tmdb_title = details.get("name")
                ad = (details.get("first_air_date") or "").strip()
                if ad:
                    try:
                        target.tmdb_release_date = datetime.strptime(ad, "%Y-%m-%d")
                    except Exception:
                        target.tmdb_release_date = None
            target.tmdb_overview = details.get("overview")
            target.tmdb_poster_path = details.get("poster_path")
            target.tmdb_backdrop_path = details.get("backdrop_path")
            target.tmdb_vote_average = details.get("vote_average")
            target.tmdb_genres = [g.get("name") for g in (details.get("genres") or []) if g.get("name")]
            target.tmdb_raw = details
        metrics.synced += 1
    except TmdbRequestError as exc:
        if item is None:
            return
        db.rollback()
        with db.begin():
            item.tmdb_status = "failed"
            item.tmdb_error = str(exc)[:480]
            item.tmdb_error_kind = exc.kind
            item.tmdb_last_sync = datetime.now(timezone.utc)
            item.tmdb_fail_count = (item.tmdb_fail_count or 0) + 1
        metrics.failed += 1
    except Exception as exc:
        if item is None:
            return
        db.rollback()
        with db.begin():
            item.tmdb_status = "failed"
            item.tmdb_error = str(exc)[:480]
            item.tmdb_error_kind = "unknown"
            item.tmdb_last_sync = datetime.now(timezone.utc)
            item.tmdb_fail_count = (item.tmdb_fail_count or 0) + 1
        metrics.failed += 1
    finally:
        db.close()


async def _run_queue(
    tasks: Iterable[TmdbSyncTask],
    *,
    settings: TmdbSyncSettings,
    token: str | None,
    api_key: str | None,
    language: str,
    region: str,
    metrics: TmdbSyncMetrics,
) -> TmdbSyncMetrics:
    queue: asyncio.Queue[TmdbSyncTask] = asyncio.Queue()
    for task in tasks:
        queue.put_nowait(task)
        metrics.queued += 1

    client = TmdbAsyncClient(token=token, api_key=api_key, rps=settings.rps, burst=settings.burst, metrics=metrics)
    try:
        await client.get_configuration()
    except Exception:
        log.exception("TMDB configuration fetch failed; continuing without cached config.")

    async def worker() -> None:
        while True:
            task = await queue.get()
            try:
                await _sync_one_task(
                    task,
                    settings=settings,
                    token=token,
                    api_key=api_key,
                    language=language,
                    region=region,
                    client=client,
                    metrics=metrics,
                )
                metrics.processed += 1
            finally:
                queue.task_done()

    worker_count = max(1, settings.workers)
    workers = [asyncio.create_task(worker()) for _ in range(worker_count)]
    try:
        await queue.join()
    finally:
        for w in workers:
            w.cancel()
        await client.close()
    metrics.finish()
    return metrics


def run_tmdb_sync(
    *,
    kind: str,
    limit: int,
    cfg: TmdbConfig,
    db: Session,
    cooldown_override_minutes: int | None = None,
) -> dict:
    settings = TmdbSyncSettings.from_env(cfg)
    if cooldown_override_minutes and cooldown_override_minutes > 0:
        settings.cooldown_missing_minutes = cooldown_override_minutes
        settings.cooldown_failed_minutes = cooldown_override_minutes
        settings.cooldown_transient_minutes = cooldown_override_minutes
    rows = _select_candidates(db, VodStream if kind == "movie" else SeriesItem, limit=limit, settings=settings)
    tasks = [TmdbSyncTask(kind=kind, item_id=row.id) for row in rows]
    metrics = TmdbSyncMetrics()
    if tasks:
        asyncio.run(
            _run_queue(
                tasks,
                settings=settings,
                token=cfg.read_access_token,
                api_key=cfg.api_key,
                language=cfg.language or "en-US",
                region=cfg.region or "US",
                metrics=metrics,
            )
        )
    else:
        metrics.finish()
        log.info(
            "TMDB sync %s: no eligible items (limit=%s resync_days=%s cooldown_missing=%s cooldown_failed=%s cooldown_transient=%s cooldown_invalid_days=%s)",
            kind,
            limit,
            settings.resync_days,
            settings.cooldown_missing_minutes,
            settings.cooldown_failed_minutes,
            settings.cooldown_transient_minutes,
            settings.cooldown_invalid_days,
        )
    eta = metrics.eta_seconds(max(0, metrics.queued - metrics.processed))
    log.info(
        "TMDB sync %s: queued=%s processed=%s success=%s missing=%s failed=%s avg_time=%.2fs req_total=%s req_per_item=%.2f retries=%s 429=%s eta=%.1fs",
        kind,
        metrics.queued,
        metrics.processed,
        metrics.synced,
        metrics.missing,
        metrics.failed,
        metrics.avg_time_per_item,
        metrics.requests_total,
        (metrics.requests_total / metrics.processed) if metrics.processed else 0.0,
        metrics.retry_total,
        metrics.rate_limited,
        eta,
    )
    return {
        "processed": metrics.processed,
        "synced": metrics.synced,
        "missing": metrics.missing,
        "failed": metrics.failed,
        "queued": metrics.queued,
        "requests_total": metrics.requests_total,
        "requests_per_item": (metrics.requests_total / metrics.processed) if metrics.processed else 0.0,
        "avg_time_per_item": metrics.avg_time_per_item,
        "retry_total": metrics.retry_total,
        "retry_by_kind": metrics.retry_by_kind,
        "rate_limited": metrics.rate_limited,
        "settings": {
            "workers": settings.workers,
            "rps": settings.rps,
            "burst": settings.burst,
        },
    }


def run_tmdb_sync_now(
    *,
    limit: int = 100,
    cfg: TmdbConfig,
    db: Session,
) -> dict:
    movie_result = run_tmdb_sync(kind="movie", limit=limit, cfg=cfg, db=db)
    series_result = run_tmdb_sync(kind="series", limit=limit, cfg=cfg, db=db)
    return {
        "movies": movie_result,
        "series": series_result,
    }
