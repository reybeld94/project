import time
import httpx

class XtreamError(Exception):
    pass

def _player_api_url(base_url: str) -> str:
    return base_url.rstrip("/") + "/player_api.php"

def xtream_get(
    base_url: str,
    username: str,
    password: str,
    action: str,
    timeout: float = 120.0,   # ✅ antes 20s, ahora 120s
    retries: int = 2,         # ✅ retry simple
    **extra_params
):
    url = _player_api_url(base_url)
    params = {"username": username, "password": password, "action": action, **extra_params}

    # timeout granular (evita que connect sea rápido pero read muera)
    t = httpx.Timeout(timeout, connect=10.0)

    last_err = None
    for attempt in range(retries + 1):
        try:
            with httpx.Client(timeout=t, follow_redirects=True) as client:
                r = client.get(url, params=params)
                r.raise_for_status()
                try:
                    return r.json()
                except Exception as e:
                    raise XtreamError(f"Respuesta no es JSON. status={r.status_code} err={e}") from e
        except Exception as e:
            last_err = e
            if attempt < retries:
                time.sleep(1.0 * (attempt + 1))  # backoff 1s, 2s...
                continue
            raise XtreamError(f"Xtream GET failed action={action} params={extra_params} err={e}") from e
