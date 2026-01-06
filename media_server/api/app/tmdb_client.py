import re
import time
import threading
from typing import Any, Optional, Tuple

import httpx

TMDB_BASE = "https://api.themoviedb.org/3"

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
        r = client.get(url, params=params, headers=headers)

        if r.status_code == 429:
            # Rate limiting: respetar 429, TMDB pide ser “respectful”. :contentReference[oaicite:3]{index=3}
            retry_after = r.headers.get("Retry-After")
            sleep_s = float(retry_after) if retry_after and retry_after.isdigit() else backoff
            time.sleep(min(30.0, sleep_s))
            backoff = min(30.0, backoff * 2)
            continue

        r.raise_for_status()
        return r.json()

    raise RuntimeError("TMDB: too many 429 retries")

def find_and_fetch_movie(
    title: str,
    *,
    token: Optional[str],
    api_key: Optional[str],
    language: str = "en-US",
    region: str = "US",
    rps: int = 5,
) -> tuple[Optional[dict], Optional[dict]]:
    wanted, year = _clean_title_and_year(title)

    limiter = RateLimiter(rps=rps)
    with httpx.Client(timeout=20) as client:
        search = tmdb_get_json(
            client, limiter, "/search/movie",
            token=token, api_key=api_key,
            params={"query": wanted, "language": language, "region": region, **({"year": year} if year else {})},
        )
        best = _pick_best_result(search.get("results") or [], wanted, year, "release_date")
        if not best:
            return None, None

        mid = best.get("id")
        details = tmdb_get_json(
            client, limiter, f"/movie/{mid}",
            token=token, api_key=api_key,
            params={"language": language, "append_to_response": "credits,videos"},
        )
        return best, details

def find_and_fetch_tv(
    title: str,
    *,
    token: Optional[str],
    api_key: Optional[str],
    language: str = "en-US",
    region: str = "US",
    rps: int = 5,
) -> tuple[Optional[dict], Optional[dict]]:
    wanted, year = _clean_title_and_year(title)

    limiter = RateLimiter(rps=rps)
    with httpx.Client(timeout=20) as client:
        search = tmdb_get_json(
            client, limiter, "/search/tv",
            token=token, api_key=api_key,
            params={"query": wanted, "language": language, **({"first_air_date_year": year} if year else {})},
        )
        best = _pick_best_result(search.get("results") or [], wanted, year, "first_air_date")
        if not best:
            return None, None

        tid = best.get("id")
        details = tmdb_get_json(
            client, limiter, f"/tv/{tid}",
            token=token, api_key=api_key,
            params={"language": language, "append_to_response": "credits,videos"},
        )
        return best, details
