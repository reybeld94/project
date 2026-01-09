import re
import asyncio
import random
import time
import threading
from datetime import date, timedelta
from typing import Any, Optional, Tuple

import httpx
from fastapi import HTTPException

from app.models import TmdbConfig

TMDB_BASE = "https://api.themoviedb.org/3"
MIN_VOTE_COUNT_FOR_AVERAGE_SORT = 50
ALLOWED_SOURCES = {"tmdb"}
ALLOWED_TRENDING_KINDS = {"all", "movie", "tv"}
ALLOWED_LIST_KINDS = {"movie", "tv"}
ALLOWED_TIME_WINDOWS = {"day", "week"}
ALLOWED_SORT_BY = {
    "movie": {
        "popularity.desc",
        "popularity.asc",
        "vote_average.desc",
        "vote_average.asc",
        "vote_count.desc",
        "vote_count.asc",
        "release_date.desc",
        "release_date.asc",
        "primary_release_date.desc",
        "primary_release_date.asc",
        "title.asc",
        "title.desc",
        "original_title.asc",
        "original_title.desc",
    },
    "tv": {
        "popularity.desc",
        "popularity.asc",
        "vote_average.desc",
        "vote_average.asc",
        "vote_count.desc",
        "vote_count.asc",
        "first_air_date.desc",
        "first_air_date.asc",
        "name.asc",
        "name.desc",
        "original_name.asc",
        "original_name.desc",
    },
}
ALLOWED_LIST_KEYS = {
    "movie": {"popular", "top_rated", "now_playing", "upcoming", "latest"},
    "tv": {"popular", "top_rated", "airing_today", "on_the_air", "latest"},
}
ALLOWED_FILTERS = {
    "movie": {
        "with_genres",
        "without_genres",
        "with_cast",
        "with_companies",
        "with_keywords",
        "with_original_language",
        "with_runtime.gte",
        "with_runtime.lte",
        "with_watch_providers",
        "with_watch_monetization_types",
        "vote_average.gte",
        "vote_average.lte",
        "vote_count.gte",
        "vote_count.lte",
        "release_date.gte",
        "release_date.lte",
        "primary_release_date.gte",
        "primary_release_date.lte",
        "region",
        "year",
        "primary_release_year",
        "include_adult",
        "include_video",
        "watch_region",
    },
    "tv": {
        "with_genres",
        "without_genres",
        "with_networks",
        "with_companies",
        "with_keywords",
        "with_original_language",
        "with_runtime.gte",
        "with_runtime.lte",
        "with_watch_providers",
        "with_watch_monetization_types",
        "vote_average.gte",
        "vote_average.lte",
        "vote_count.gte",
        "vote_count.lte",
        "first_air_date.gte",
        "first_air_date.lte",
        "timezone",
        "include_null_first_air_dates",
        "watch_region",
    },
}

class RateLimiter:
    def __init__(self, rps: int = 5):
        self.set_rps(rps)
        self._lock = threading.Lock()
        self._next = 0.0

    def set_rps(self, rps: int):
        rps = max(1, int(rps))
        self.min_interval = 1.0 / rps

    def wait(self):
        with self._lock:
            now = time.monotonic()
            if now < self._next:
                time.sleep(self._next - now)
            self._next = max(now, self._next) + self.min_interval


class TokenBucketRateLimiter:
    def __init__(self, rps: int = 5, burst: int = 10):
        self.rps = max(1, int(rps))
        self.capacity = max(1, int(burst))
        self.tokens = float(self.capacity)
        self.updated_at = time.monotonic()
        self._lock = asyncio.Lock()

    def _refill(self, now: float) -> None:
        elapsed = max(0.0, now - self.updated_at)
        if elapsed <= 0:
            return
        self.tokens = min(self.capacity, self.tokens + elapsed * self.rps)
        self.updated_at = now

    async def acquire(self) -> None:
        while True:
            async with self._lock:
                now = time.monotonic()
                self._refill(now)
                if self.tokens >= 1.0:
                    self.tokens -= 1.0
                    return
                needed = (1.0 - self.tokens) / float(self.rps)
            await asyncio.sleep(needed)


class TmdbRequestError(Exception):
    def __init__(self, message: str, *, status_code: int | None = None, kind: str = "unknown"):
        super().__init__(message)
        self.status_code = status_code
        self.kind = kind


def _jitter(max_jitter_s: float = 1.5) -> float:
    return random.uniform(0.0, max_jitter_s)


def _retry_sleep(base_s: float, *, max_s: float) -> float:
    return min(max_s, base_s) + _jitter()


class TmdbAsyncClient:
    def __init__(
        self,
        *,
        token: Optional[str],
        api_key: Optional[str],
        rps: int = 5,
        burst: int = 10,
        timeout: float = 20.0,
        metrics: Optional[Any] = None,
    ):
        self.token = token
        self.api_key = api_key
        self._limiter = TokenBucketRateLimiter(rps=rps, burst=burst)
        self._client = httpx.AsyncClient(timeout=timeout)
        self._metrics = metrics

    async def close(self) -> None:
        await self._client.aclose()

    async def get_json(
        self,
        path: str,
        *,
        params: Optional[dict] = None,
        max_retries: int = 5,
    ) -> dict[str, Any]:
        params = dict(params or {})
        headers = {}

        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        elif self.api_key:
            params["api_key"] = self.api_key

        url = f"{TMDB_BASE}{path}"
        backoff_429 = 1.0
        backoff_5xx = 0.5

        for attempt in range(max_retries):
            await self._limiter.acquire()
            try:
                if self._metrics is not None:
                    self._metrics.record_request()
                r = await self._client.get(url, params=params, headers=headers)
            except httpx.TimeoutException as exc:
                sleep_s = _retry_sleep(backoff_5xx, max_s=10.0)
                backoff_5xx = min(10.0, backoff_5xx * 2)
                if attempt == max_retries - 1:
                    raise TmdbRequestError("TMDB timeout", kind="timeout") from exc
                if self._metrics is not None:
                    self._metrics.record_retry("timeout")
                await asyncio.sleep(sleep_s)
                continue
            except httpx.RequestError as exc:
                sleep_s = _retry_sleep(backoff_5xx, max_s=10.0)
                backoff_5xx = min(10.0, backoff_5xx * 2)
                if attempt == max_retries - 1:
                    raise TmdbRequestError("No se pudo conectar con TMDB.", kind="network") from exc
                if self._metrics is not None:
                    self._metrics.record_retry("network")
                await asyncio.sleep(sleep_s)
                continue

            if r.status_code == 429:
                retry_after = r.headers.get("Retry-After")
                base_sleep = float(retry_after) if retry_after and retry_after.isdigit() else backoff_429
                backoff_429 = min(30.0, backoff_429 * 2)
                sleep_s = _retry_sleep(base_sleep, max_s=30.0)
                if attempt == max_retries - 1:
                    raise TmdbRequestError("TMDB alcanzó el límite de solicitudes.", status_code=429, kind="rate_limited")
                if self._metrics is not None:
                    self._metrics.record_retry("rate_limited")
                await asyncio.sleep(sleep_s)
                continue

            if 500 <= r.status_code <= 599:
                sleep_s = _retry_sleep(backoff_5xx, max_s=10.0)
                backoff_5xx = min(10.0, backoff_5xx * 2)
                if attempt == max_retries - 1:
                    raise TmdbRequestError("TMDB tuvo un error interno.", status_code=r.status_code, kind="server")
                if self._metrics is not None:
                    self._metrics.record_retry("server")
                await asyncio.sleep(sleep_s)
                continue

            if r.is_error:
                status = r.status_code
                kind = "invalid"
                if status == 401:
                    kind = "auth"
                elif status == 404:
                    kind = "not_found"
                elif status == 400:
                    kind = "invalid"
                raise TmdbRequestError(r.text, status_code=status, kind=kind)

            return r.json()

        raise TmdbRequestError("TMDB alcanzó el límite de solicitudes.", status_code=429, kind="rate_limited")

    async def get_configuration(self) -> dict[str, Any]:
        now = time.time()
        async with _config_lock:
            cached = _config_cache.get("payload")
            expires_at = _config_cache.get("expires_at", 0.0)
            if cached and now < expires_at:
                return cached

            payload = await self.get_json("/configuration")
            _config_cache["payload"] = payload
            _config_cache["expires_at"] = now + CONFIG_CACHE_TTL_S
            return payload

def _clean_title_and_year(s: str) -> Tuple[str, Optional[int]]:
    s = (s or "").strip()

    # 1) quitar extensión típica si viene como filename
    s = re.sub(r"\.(mkv|mp4|avi|mov|m4v|wmv|flv|webm|ts|m2ts)$", "", s, flags=re.I).strip()

    # 2) normalizar espacios (sin destruir demasiado el título)
    s = re.sub(r"\s+", " ", s).strip()

    # 3) Detectar y remover UNO O VARIOS años al final:
    #    "Title 2025", "Title (2025)", "Title (2025) (2025)", "Title [2025] (2025)"...
    m = re.search(r"(?:\s*[\(\[\{]?\s*((?:19|20)\d{2})\s*[\)\]\}]?\s*)+$", s)
    year: Optional[int] = None
    if m:
        years = re.findall(r"(19\d{2}|20\d{2})", m.group(0))
        if years:
            year = int(years[-1])
        s = s[:m.start()].rstrip()

    # 4) limpiar paréntesis vacíos al final si quedaron
    s = re.sub(r"[\(\[\{]\s*[\)\]\}]\s*$", "", s).strip()

    # 5) colapsar espacios final
    s = re.sub(r"\s+", " ", s).strip()
    return s, year


def _pick_best_result(results: list[dict], wanted_title: str, wanted_year: Optional[int], date_key: str) -> Optional[dict]:
    if not results:
        return None

    wt = wanted_title.casefold()

    best = None
    best_score = -10**9
    for r in results:
        title = (r.get("title") or r.get("name") or "").casefold()
        score = 0

        if title == wt:
            score += 200
        elif wt and title and wt in title:
            score += 80

        # year proximity
        y = None
        d = r.get(date_key) or ""
        if len(d) >= 4 and d[:4].isdigit():
            y = int(d[:4])

        if wanted_year and y:
            diff = abs(wanted_year - y)
            score += max(0, 60 - diff * 10)

        # popularity & votes
        score += int((r.get("popularity") or 0) * 2)
        score += int((r.get("vote_count") or 0) * 0.02)

        if score > best_score:
            best_score = score
            best = r

    return best

def _tmdb_error(status_code: int, detail: str) -> HTTPException:
    return HTTPException(status_code=status_code, detail=detail)

def _handle_tmdb_response_error(response: httpx.Response) -> None:
    status = response.status_code
    message = None
    try:
        payload = response.json()
        message = payload.get("status_message") or payload.get("status") or payload.get("message")
    except ValueError:
        message = None

    if status in {401, 403}:
        raise _tmdb_error(status, "TMDB rechazó las credenciales. Verifica token o api_key.")
    if status == 404:
        raise _tmdb_error(status, "TMDB no encontró el recurso solicitado.")
    if status == 429:
        raise _tmdb_error(status, "TMDB alcanzó el límite de solicitudes. Intenta más tarde.")
    if status == 400:
        detail = f"TMDB rechazó la solicitud: {message or 'parámetros inválidos.'}"
        raise _tmdb_error(status, detail)
    if status >= 500:
        raise _tmdb_error(502, "TMDB tuvo un error interno. Intenta más tarde.")

    raise _tmdb_error(status, f"TMDB respondió con error: {message or 'solicitud inválida.'}")

def _resolve_language_region(
    language: Optional[str],
    region: Optional[str],
    config: Optional[TmdbConfig],
) -> tuple[str, str]:
    resolved_language = (language or (config.language if config else None) or "en-US").strip()
    resolved_region = (region or (config.region if config else None) or "US").strip()
    return resolved_language, resolved_region

def _resolve_rps(rps: Optional[int], config: Optional[TmdbConfig]) -> int:
    if rps is not None:
        return max(1, int(rps))
    if config and config.requests_per_second:
        return max(1, int(config.requests_per_second))
    return 5

def _validate_source(source: str) -> None:
    if source not in ALLOWED_SOURCES:
        raise _tmdb_error(400, f"source inválido. Valores permitidos: {sorted(ALLOWED_SOURCES)}")

def _validate_kind(kind: str, *, allowed: set[str]) -> None:
    if kind not in allowed:
        raise _tmdb_error(400, f"kind inválido. Valores permitidos: {sorted(allowed)}")

def _validate_list_key(kind: str, list_key: str) -> None:
    allowed = ALLOWED_LIST_KEYS.get(kind, set())
    if list_key not in allowed:
        raise _tmdb_error(400, f"list_key inválido para {kind}. Valores permitidos: {sorted(allowed)}")

def _normalize_discover_filters(
    kind: str,
    filters: Optional[dict],
    *,
    sort_by: Optional[str],
) -> dict[str, Any]:
    clean_filters = dict(filters or {})
    release_days_back = clean_filters.pop("release_days_back", None)

    allowed_filters = ALLOWED_FILTERS.get(kind, set())
    invalid_filters = [key for key in clean_filters.keys() if key not in allowed_filters]
    if invalid_filters:
        raise _tmdb_error(
            400,
            f"Filtros no permitidos para {kind}: {sorted(invalid_filters)}. Permitidos: {sorted(allowed_filters)}",
        )

    if sort_by == "vote_average.desc":
        vote_count = clean_filters.get("vote_count.gte")
        if vote_count is None:
            raise _tmdb_error(
                400,
                f"sort_by=vote_average.desc requiere vote_count.gte >= {MIN_VOTE_COUNT_FOR_AVERAGE_SORT}.",
            )
        try:
            vote_count_value = int(vote_count)
        except (TypeError, ValueError):
            raise _tmdb_error(400, "vote_count.gte debe ser un entero.") from None
        if vote_count_value < MIN_VOTE_COUNT_FOR_AVERAGE_SORT:
            raise _tmdb_error(
                400,
                f"sort_by=vote_average.desc requiere vote_count.gte >= {MIN_VOTE_COUNT_FOR_AVERAGE_SORT}.",
            )

    if release_days_back is not None:
        try:
            release_days_back = int(release_days_back)
        except (TypeError, ValueError):
            raise _tmdb_error(400, "release_days_back debe ser un entero >= 0.") from None
        if release_days_back < 0:
            raise _tmdb_error(400, "release_days_back debe ser un entero >= 0.")
        date_key = "release_date" if kind == "movie" else "first_air_date"
        gte_key = f"{date_key}.gte"
        lte_key = f"{date_key}.lte"
        if gte_key in clean_filters or lte_key in clean_filters:
            raise _tmdb_error(400, "No combines release_days_back con filtros gte/lte manuales.")

        today = date.today()
        start_date = today - timedelta(days=release_days_back)
        clean_filters[gte_key] = start_date.isoformat()
        clean_filters[lte_key] = today.isoformat()

    return clean_filters

def _validate_sort_by(kind: str, sort_by: Optional[str]) -> None:
    if not sort_by:
        return
    allowed = ALLOWED_SORT_BY.get(kind, set())
    if sort_by not in allowed:
        raise _tmdb_error(400, f"sort_by inválido para {kind}. Valores permitidos: {sorted(allowed)}")

def tmdb_get_json(
    client: httpx.Client,
    limiter: RateLimiter,
    path: str,
    *,
    token: Optional[str],
    api_key: Optional[str],
    params: Optional[dict] = None,
    max_retries: int = 5,
) -> dict[str, Any]:
    params = dict(params or {})
    headers = {}

    # TMDB docs muestran Bearer token para v3 search/details. :contentReference[oaicite:2]{index=2}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    elif api_key:
        params["api_key"] = api_key

    url = f"{TMDB_BASE}{path}"

    backoff = 1.0
    for attempt in range(max_retries):
        limiter.wait()
        try:
            r = client.get(url, params=params, headers=headers)
        except httpx.RequestError as exc:
            raise _tmdb_error(502, "No se pudo conectar con TMDB.") from exc

        if r.status_code == 429:
            # Rate limiting: respetar 429, TMDB pide ser “respectful”. :contentReference[oaicite:3]{index=3}
            retry_after = r.headers.get("Retry-After")
            sleep_s = float(retry_after) if retry_after and retry_after.isdigit() else backoff
            time.sleep(min(30.0, sleep_s))
            backoff = min(30.0, backoff * 2)
            continue

        if r.is_error:
            _handle_tmdb_response_error(r)

        return r.json()

    raise _tmdb_error(429, "TMDB alcanzó el límite de solicitudes. Intenta más tarde.")

async def _find_and_fetch_movie_async(
    title: str,
    *,
    token: Optional[str],
    api_key: Optional[str],
    language: str = "en-US",
    region: str = "US",
    rps: int = 5,
    burst: int = 10,
) -> tuple[Optional[dict], Optional[dict]]:
    wanted, year = _clean_title_and_year(title)

    client = TmdbAsyncClient(token=token, api_key=api_key, rps=rps, burst=burst)
    try:
        search = await client.get_json(
            "/search/movie",
            params={"query": wanted, "language": language, "region": region, **({"year": year} if year else {})},
        )
        best = _pick_best_result(search.get("results") or [], wanted, year, "release_date")
        if not best:
            return None, None

        mid = best.get("id")
        details = await client.get_json(
            f"/movie/{mid}",
            params={"language": language, "append_to_response": "credits,videos,images,release_dates"},
        )
        return best, details
    finally:
        await client.close()


def find_and_fetch_movie(
    title: str,
    *,
    token: Optional[str],
    api_key: Optional[str],
    language: str = "en-US",
    region: str = "US",
    rps: int = 5,
    burst: int = 10,
) -> tuple[Optional[dict], Optional[dict]]:
    return asyncio.run(
        _find_and_fetch_movie_async(
            title,
            token=token,
            api_key=api_key,
            language=language,
            region=region,
            rps=rps,
            burst=burst,
        )
    )

async def _find_and_fetch_tv_async(
    title: str,
    *,
    token: Optional[str],
    api_key: Optional[str],
    language: str = "en-US",
    region: str = "US",
    rps: int = 5,
    burst: int = 10,
) -> tuple[Optional[dict], Optional[dict]]:
    wanted, year = _clean_title_and_year(title)

    client = TmdbAsyncClient(token=token, api_key=api_key, rps=rps, burst=burst)
    try:
        search = await client.get_json(
            "/search/tv",
            params={"query": wanted, "language": language, **({"first_air_date_year": year} if year else {})},
        )
        best = _pick_best_result(search.get("results") or [], wanted, year, "first_air_date")
        if not best:
            return None, None

        tid = best.get("id")
        details = await client.get_json(
            f"/tv/{tid}",
            params={"language": language, "append_to_response": "credits,videos,images,content_ratings"},
        )
        return best, details
    finally:
        await client.close()


def find_and_fetch_tv(
    title: str,
    *,
    token: Optional[str],
    api_key: Optional[str],
    language: str = "en-US",
    region: str = "US",
    rps: int = 5,
    burst: int = 10,
) -> tuple[Optional[dict], Optional[dict]]:
    return asyncio.run(
        _find_and_fetch_tv_async(
            title,
            token=token,
            api_key=api_key,
            language=language,
            region=region,
            rps=rps,
            burst=burst,
        )
    )

def fetch_trending(
    kind: str,
    time_window: str = "day",
    *,
    token: Optional[str],
    api_key: Optional[str],
    language: Optional[str] = None,
    region: Optional[str] = None,
    page: int = 1,
    rps: Optional[int] = None,
    config: Optional[TmdbConfig] = None,
) -> dict[str, Any]:
    _validate_kind(kind, allowed=ALLOWED_TRENDING_KINDS)
    if time_window not in ALLOWED_TIME_WINDOWS:
        raise _tmdb_error(400, f"time_window inválido. Valores permitidos: {sorted(ALLOWED_TIME_WINDOWS)}")

    resolved_language, resolved_region = _resolve_language_region(language, region, config)
    limiter = RateLimiter(rps=_resolve_rps(rps, config))
    params = {
        "language": resolved_language,
        "region": resolved_region,
        "page": max(1, int(page or 1)),
    }
    with httpx.Client(timeout=20) as client:
        return tmdb_get_json(
            client,
            limiter,
            f"/trending/{kind}/{time_window}",
            token=token,
            api_key=api_key,
            params=params,
        )

def fetch_tmdb_list(
    kind: str,
    list_key: str,
    *,
    token: Optional[str],
    api_key: Optional[str],
    source: str = "tmdb",
    language: Optional[str] = None,
    region: Optional[str] = None,
    page: int = 1,
    rps: Optional[int] = None,
    config: Optional[TmdbConfig] = None,
) -> dict[str, Any]:
    _validate_source(source)
    _validate_kind(kind, allowed=ALLOWED_LIST_KINDS)
    _validate_list_key(kind, list_key)

    resolved_language, resolved_region = _resolve_language_region(language, region, config)
    limiter = RateLimiter(rps=_resolve_rps(rps, config))
    params = {
        "language": resolved_language,
        "region": resolved_region,
        "page": max(1, int(page or 1)),
    }
    with httpx.Client(timeout=20) as client:
        return tmdb_get_json(
            client,
            limiter,
            f"/{kind}/{list_key}",
            token=token,
            api_key=api_key,
            params=params,
        )

def fetch_discover(
    kind: str,
    filters: Optional[dict],
    *,
    sort_by: Optional[str] = None,
    token: Optional[str],
    api_key: Optional[str],
    source: str = "tmdb",
    language: Optional[str] = None,
    region: Optional[str] = None,
    page: int = 1,
    rps: Optional[int] = None,
    config: Optional[TmdbConfig] = None,
) -> dict[str, Any]:
    _validate_source(source)
    _validate_kind(kind, allowed=ALLOWED_LIST_KINDS)
    _validate_sort_by(kind, sort_by)

    normalized_filters = _normalize_discover_filters(kind, filters, sort_by=sort_by)
    resolved_language, resolved_region = _resolve_language_region(language, region, config)
    limiter = RateLimiter(rps=_resolve_rps(rps, config))

    params = {
        "language": resolved_language,
        "region": resolved_region,
        "page": max(1, int(page or 1)),
        **normalized_filters,
    }
    if sort_by:
        params["sort_by"] = sort_by

    with httpx.Client(timeout=20) as client:
        return tmdb_get_json(
            client,
            limiter,
            f"/discover/{kind}",
            token=token,
            api_key=api_key,
            params=params,
        )
CONFIG_CACHE_TTL_S = 60 * 60 * 24
_config_cache: dict[str, Any] = {"payload": None, "expires_at": 0.0}
_config_lock = asyncio.Lock()
