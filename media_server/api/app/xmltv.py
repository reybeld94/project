import gzip
import os
import tempfile
from datetime import datetime, timezone, timedelta

import httpx
from lxml import etree

def xmltv_url(base_url: str, username: str, password: str) -> str:
    return f"{base_url.rstrip('/')}/xmltv.php?username={username}&password={password}"

def parse_xmltv_datetime(s: str) -> datetime:
    """
    XMLTV típico: YYYYMMDDHHMMSS +0000
    Ej: 20251231021521 +0000
    """
    s = (s or "").strip()
    if not s:
        raise ValueError("Empty datetime")

    parts = s.split()
    dt_raw = parts[0]
    tz_raw = parts[1] if len(parts) > 1 else "+0000"

    dt = datetime.strptime(dt_raw, "%Y%m%d%H%M%S")

    sign = 1 if tz_raw.startswith("+") else -1
    hh = int(tz_raw[1:3])
    mm = int(tz_raw[3:5])
    offset = timedelta(hours=hh, minutes=mm) * sign

    return dt.replace(tzinfo=timezone(offset)).astimezone(timezone.utc)

def download_xmltv_to_file(url: str, timeout: float = 60.0) -> str:
    """
    Descarga XMLTV a un archivo temporal. Soporta gzip.
    """
    with httpx.Client(timeout=timeout, follow_redirects=True) as client:
        r = client.get(url)
        r.raise_for_status()

        content = r.content
        enc = (r.headers.get("content-encoding") or "").lower()

        if enc == "gzip" or content[:2] == b"\x1f\x8b":
            content = gzip.decompress(content)

    fd, path = tempfile.mkstemp(prefix="xmltv_", suffix=".xml")
    os.close(fd)
    with open(path, "wb") as f:
        f.write(content)
    return path

def iter_xmltv(path: str):
    """
    Generator de eventos (channels, programmes) usando iterparse.
    """
    context = etree.iterparse(path, events=("end",), recover=True, huge_tree=True)
    for _, elem in context:
        tag = elem.tag
        if tag == "channel":
            yield ("channel", elem)
            elem.clear()
        elif tag == "programme":
            yield ("programme", elem)
            elem.clear()
