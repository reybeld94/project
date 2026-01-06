import re
from difflib import SequenceMatcher

_CLEAN_RE = re.compile(r"[^a-z0-9]+")

def norm(s: str) -> str:
    s = (s or "").lower().strip()
    # cosas típicas que ensucian
    s = s.replace("hd", " ").replace("fhd", " ").replace("uhd", " ")
    s = s.replace("4k", " ").replace("us", " ").replace("usa", " ")
    s = s.replace("tv", " ")
    s = _CLEAN_RE.sub(" ", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s

def score(a: str, b: str) -> float:
    if not a or not b:
        return 0.0
    return SequenceMatcher(None, a, b).ratio()

def best_match(name: str, candidates: list[tuple[str, str]], min_score: float = 0.72):
    """
    candidates: [(xmltv_id, display_name)]
    devuelve (xmltv_id, display_name, score) o None
    """
    n = norm(name)
    best = None
    best_s = 0.0

    for xml_id, disp in candidates:
        s = score(n, norm(disp))
        if s > best_s:
            best_s = s
            best = (xml_id, disp, s)

    if best and best_s >= min_score:
        return best
    return None
