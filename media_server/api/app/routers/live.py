from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from sqlalchemy import select, func, or_
from app.schemas import LiveStreamUpdate
from app.deps import get_db
from app.models import Provider, Category, LiveStream, ProviderUser
from app.vlc import launch_vlc
from sqlalchemy.exc import IntegrityError
import os
import shutil
from pathlib import Path
from uuid import uuid4
from fastapi import UploadFile, File
from urllib.parse import urlparse
import re
from sqlalchemy import select
import random

router = APIRouter(prefix="/live", tags=["live"])

LOGO_DIR = Path(__file__).resolve().parent.parent / "static" / "logos"
LOGO_DIR.mkdir(parents=True, exist_ok=True)

_ALLOWED = {
    "image/png": ".png",
    "image/jpeg": ".jpg",
    "image/webp": ".webp",
    "image/svg+xml": ".svg",
}


def _get_credentials(db: Session, provider: Provider, unique_code: str | None = None) -> tuple[str, str]:
    """
    Get username and password for streaming URLs.

    Priority:
    1. If unique_code provided, use those credentials (must be enabled)
    2. If provider has users, pick a random enabled user
    3. Fall back to provider's own credentials (legacy)

    Returns:
        tuple[str, str]: (username, password)

    Raises:
        HTTPException: If no valid credentials found
    """
    # If unique_code provided, use that user
    if unique_code:
        user = db.execute(
            select(ProviderUser).where(
                ProviderUser.unique_code == unique_code,
                ProviderUser.provider_id == provider.id,
            )
        ).scalar_one_or_none()

        if not user:
            raise HTTPException(status_code=404, detail="User not found for this code")

        if not user.is_enabled:
            raise HTTPException(status_code=403, detail="User is disabled")

        return user.username, user.password

    # Try to get a random enabled user from this provider
    users = db.execute(
        select(ProviderUser).where(
            ProviderUser.provider_id == provider.id,
            ProviderUser.is_enabled == True,
        )
    ).scalars().all()

    if users:
        user = random.choice(users)
        return user.username, user.password

    # Fall back to provider credentials (legacy)
    if provider.username and provider.password:
        return provider.username, provider.password

    raise HTTPException(
        status_code=400,
        detail="No credentials available. Please add users to this provider."
    )


@router.get("")
def list_live_streams(
    provider_id: str,
    category_ext_id: int | None = None,   # provider_category_id (Xtream)
    q: str | None = None,                 # search
    limit: int = 50,
    offset: int = 0,
    active_only: bool = True,
    sort: str = "name",
    approved: bool | None = None,  # None=all, True=only approved, False=only NOT approved
    approved_only: bool = False,  # backward compat (maps to approved=True)
    db: Session = Depends(get_db),
):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    stmt = select(LiveStream).where(LiveStream.provider_id == p.id)

    if active_only:
        stmt = stmt.where(LiveStream.is_active == True)

    # backward compat: si usan el flag viejo, equivale a approved=True
    if approved is None and approved_only:
        approved = True

    if approved is not None:
        stmt = stmt.where(LiveStream.approved == approved)

    if category_ext_id is not None:
        cat = db.execute(
            select(Category).where(
                Category.provider_id == p.id,
                Category.cat_type == "live",
                Category.provider_category_id == category_ext_id,
            )
        ).scalar_one_or_none()
        if not cat:
            raise HTTPException(status_code=404, detail="Category not found for this provider")
        stmt = stmt.where(LiveStream.category_id == cat.id)

    if q:
        qq = f"%{q.strip()}%"
        stmt = stmt.where(or_(
            LiveStream.name.ilike(qq),
            LiveStream.normalized_name.ilike(qq),
        ))

    total = db.execute(select(func.count()).select_from(stmt.subquery())).scalar_one()

    rows = db.execute(
        stmt.order_by(
            (LiveStream.channel_number.is_(None)).asc(),
            LiveStream.channel_number.asc(),
            LiveStream.name.asc(),
        )
        .limit(min(limit, 200)).offset(offset)
    ).scalars().all()

    return {
        "total": int(total),
        "items": [
            {
                "id": str(x.id),
                "provider_stream_id": x.provider_stream_id,
                "channel_number": x.channel_number,
                "name": x.name,
                "normalized_name": x.normalized_name,
                "custom_logo_url": x.custom_logo_url,
                "logo": x.custom_logo_url,

                "epg_source_id": str(x.epg_source_id) if x.epg_source_id else None,
                "epg_channel_id": x.epg_channel_id,  # ✅ <-- AÑADE ESTA LÍNEA

                "is_active": x.is_active,
                "approved": x.approved,
                "alt1_stream_id": str(x.alt1_stream_id) if x.alt1_stream_id else None,
                "alt2_stream_id": str(x.alt2_stream_id) if x.alt2_stream_id else None,
                "alt3_stream_id": str(x.alt3_stream_id) if x.alt3_stream_id else None,
                "category_id": str(x.category_id) if x.category_id else None,
                "category_name": x.category.name if x.category else None,
                "category_ext_id": x.category.provider_category_id if x.category else None,
            }
            for x in rows
        ],
    }


@router.get("/{live_id}/play")
def get_live_play_url(
    live_id: str,
    format: str = "m3u8",  # m3u8 | ts
    alt1: bool = False,
    alt2: bool = False,
    alt3: bool = False,
    open_vlc: bool = False,
    unique_code: str | None = None,  # User's unique code from APK
    db: Session = Depends(get_db),
):
    """
    Devuelve URL lista para ExoPlayer.

    Default: stream principal
    Opcional: ?alt1=1 | ?alt2=1 | ?alt3=1 (solo uno a la vez)

    Xtream típico:
      /live/{user}/{pass}/{stream_id}.m3u8
      /live/{user}/{pass}/{stream_id}.ts
    """
    s = db.get(LiveStream, live_id)
    if not s:
        raise HTTPException(status_code=404, detail="Live stream not found")

    # ✅ Solo permitir un alt a la vez
    alt_count = int(alt1) + int(alt2) + int(alt3)
    if alt_count > 1:
        raise HTTPException(status_code=400, detail="Use only one of alt1, alt2, alt3")

    # ✅ Decide qué stream usar (main o alt)
    target = s
    alt_label = None

    if alt1:
        alt_label = "alt1"
        if not s.alt1_stream_id:
            raise HTTPException(status_code=404, detail="alt1 not configured")
        target = db.get(LiveStream, str(s.alt1_stream_id))
        if not target:
            raise HTTPException(status_code=404, detail="alt1 stream not found")

    elif alt2:
        alt_label = "alt2"
        if not s.alt2_stream_id:
            raise HTTPException(status_code=404, detail="alt2 not configured")
        target = db.get(LiveStream, str(s.alt2_stream_id))
        if not target:
            raise HTTPException(status_code=404, detail="alt2 stream not found")

    elif alt3:
        alt_label = "alt3"
        if not s.alt3_stream_id:
            raise HTTPException(status_code=404, detail="alt3 not configured")
        target = db.get(LiveStream, str(s.alt3_stream_id))
        if not target:
            raise HTTPException(status_code=404, detail="alt3 stream not found")

    # ✅ Provider del stream seleccionado (puede ser distinto)
    p = db.get(Provider, target.provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    fmt = (format or "").lower().strip()
    if fmt not in ("m3u8", "ts"):
        raise HTTPException(status_code=400, detail="format must be m3u8 or ts")

    # Get credentials (either from unique_code or random user)
    username, password = _get_credentials(db, p, unique_code)

    url = f"{p.base_url.rstrip('/')}/live/{username}/{password}/{target.provider_stream_id}.{fmt}"

    out = {"id": str(target.id), "name": target.name, "url": url}
    if open_vlc:
        out["vlc_started"] = launch_vlc(url)
    if not alt_label:
        for n in (1, 2, 3):
            alt_id = getattr(s, f"alt{n}_stream_id")
            if not alt_id:
                continue
            alt_stream = db.get(LiveStream, str(alt_id))
            if not alt_stream:
                continue
            alt_provider = db.get(Provider, alt_stream.provider_id)
            if not alt_provider:
                continue
            # Use same credentials for alternative streams
            alt_username, alt_password = _get_credentials(db, alt_provider, unique_code)
            alt_url = (
                f"{alt_provider.base_url.rstrip('/')}/live/"
                f"{alt_username}/{alt_password}/"
                f"{alt_stream.provider_stream_id}.{fmt}"
            )
            out[f"alt{n}"] = alt_url
    if alt_label:
        out["alt"] = alt_label
        out["base_id"] = str(s.id)  # el canal original (main)
    return out



@router.patch("/{live_id}")
def update_live_stream(
    live_id: str,
    payload: LiveStreamUpdate,
    db: Session = Depends(get_db),
):
    s = db.get(LiveStream, live_id)
    if not s:
        raise HTTPException(status_code=404, detail="Live stream not found")

    data = payload.dict(exclude_unset=True)

    for n in (1, 2, 3):
        url_key = f"alt{n}_url"
        id_key  = f"alt{n}_stream_id"

        if url_key in data and id_key in data:
            raise HTTPException(status_code=400, detail=f"Send only {url_key} OR {id_key}, not both")

        if url_key in data:
            raw = (data[url_key] or "").strip()
            if not raw:
                data[id_key] = None  # vacío = clear
            else:
                alt_stream = _resolve_alt_url_to_stream(db, raw)
                data[id_key] = alt_stream.id

            # ya no necesitamos guardar url_key como campo (no existe en el modelo)
            data.pop(url_key, None)


    # Validar alts si vienen
    for k in ("alt1_stream_id", "alt2_stream_id", "alt3_stream_id"):
        if k in data and data[k] is not None:
            alt = db.get(LiveStream, str(data[k]))
            if not alt:
                raise HTTPException(status_code=404, detail=f"{k} does not exist")

    # ✅ channel_number independiente de approved
    if "channel_number" in data:
        v = data["channel_number"]
        if v is not None and v <= 0:
            raise HTTPException(status_code=400, detail="channel_number must be > 0 or null")
        s.channel_number = v

    if "approved" in data:
        s.approved = data["approved"]
    if "normalized_name" in data:
        v = data["normalized_name"]
        v = (v or "").strip()
        s.normalized_name = v or None
    if "custom_logo_url" in data:
        v = (data["custom_logo_url"] or "").strip()
        s.custom_logo_url = v or None

    if "epg_source_id" in data:
        s.epg_source_id = data["epg_source_id"]

    if "epg_channel_id" in data:
        v = (data["epg_channel_id"] or "").strip()
        s.epg_channel_id = v or None


    if "alt1_stream_id" in data:
        s.alt1_stream_id = data["alt1_stream_id"]
    if "alt2_stream_id" in data:
        s.alt2_stream_id = data["alt2_stream_id"]
    if "alt3_stream_id" in data:
        s.alt3_stream_id = data["alt3_stream_id"]

    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(status_code=409, detail="Integrity error")

    db.refresh(s)

    return {
        "id": str(s.id),
        "provider_stream_id": s.provider_stream_id,
        "name": s.name,
        "normalized_name": s.normalized_name,
        "stream_icon": s.stream_icon,
        "epg_channel_id": s.epg_channel_id,
        "is_active": s.is_active,
        "category_id": str(s.category_id) if s.category_id else None,
        "approved": s.approved,
        "channel_number": s.channel_number,
        "epg_source_id": str(s.epg_source_id) if s.epg_source_id else None,
        "alt1_stream_id": str(s.alt1_stream_id) if s.alt1_stream_id else None,
        "alt2_stream_id": str(s.alt2_stream_id) if s.alt2_stream_id else None,
        "alt3_stream_id": str(s.alt3_stream_id) if s.alt3_stream_id else None,
    }


@router.get("/resolve")
def resolve_live_stream(
    provider_id: str,
    provider_stream_id: int,
    db: Session = Depends(get_db),
):
    p = db.get(Provider, provider_id)
    if not p:
        raise HTTPException(status_code=404, detail="Provider not found")

    s = db.execute(
        select(LiveStream).where(
            LiveStream.provider_id == p.id,
            LiveStream.provider_stream_id == provider_stream_id,
        )
    ).scalar_one_or_none()

    if not s:
        raise HTTPException(status_code=404, detail="Live stream not found for that provider_stream_id")

    return {
        "id": str(s.id),
        "provider_stream_id": s.provider_stream_id,
        "name": s.name,
    }

@router.post("/{live_id}/logo")
def upload_live_logo(
    live_id: str,
    file: UploadFile = File(...),
    db: Session = Depends(get_db),
):
    s = db.get(LiveStream, live_id)
    if not s:
        raise HTTPException(status_code=404, detail="Live stream not found")

    ct = (file.content_type or "").lower().strip()
    if ct not in _ALLOWED:
        raise HTTPException(status_code=400, detail=f"Unsupported content-type: {ct}")

    # borra logo anterior si era local
    if s.custom_logo_url and s.custom_logo_url.startswith("/static/logos/"):
        old_name = s.custom_logo_url.split("/static/logos/")[-1]
        old_path = LOGO_DIR / old_name
        try:
            if old_path.exists():
                old_path.unlink()
        except Exception:
            pass

    ext = _ALLOWED[ct]
    fname = f"{live_id}_{uuid4().hex}{ext}"
    out_path = LOGO_DIR / fname

    with out_path.open("wb") as out:
        shutil.copyfileobj(file.file, out)

    s.custom_logo_url = f"/static/logos/{fname}"
    db.commit()
    db.refresh(s)

    return {"ok": True, "live_id": str(s.id), "custom_logo_url": s.custom_logo_url, "logo": s.custom_logo_url}



@router.delete("/{live_id}/logo")
def delete_live_logo(live_id: str, db: Session = Depends(get_db)):
    s = db.get(LiveStream, live_id)
    if not s:
        raise HTTPException(status_code=404, detail="Live stream not found")

    if s.custom_logo_url and s.custom_logo_url.startswith("/static/logos/"):
        name = s.custom_logo_url.split("/static/logos/")[-1]
        p = LOGO_DIR / name
        try:
            if p.exists():
                p.unlink()
        except Exception:
            pass

    s.custom_logo_url = None
    db.commit()
    db.refresh(s)
    return {"ok": True, "live_id": str(s.id), "custom_logo_url": None, "logo": None}

@router.get("/all")
def list_live_streams_all(
    q: str | None = None,
    limit: int = 50,
    offset: int = 0,
    active_only: bool = True,
    approved: bool | None = None,  # None=all, True=only approved, False=only not approved
    db: Session = Depends(get_db),
):
    stmt = (
        select(LiveStream)
        .join(Provider, Provider.id == LiveStream.provider_id)
        .where(Provider.is_active == True)
    )

    if active_only:
        stmt = stmt.where(LiveStream.is_active == True)

    if approved is not None:
        stmt = stmt.where(LiveStream.approved == approved)

    if q:
        like = f"%{q.strip()}%"
        stmt = stmt.where(
            or_(
                LiveStream.name.ilike(like),
                LiveStream.normalized_name.ilike(like),
            )
        )

    total = db.execute(select(func.count()).select_from(stmt.subquery())).scalar_one()

    rows = (
        db.execute(
            stmt.order_by(
                (LiveStream.channel_number.is_(None)).asc(),
                LiveStream.channel_number.asc(),
                LiveStream.name.asc(),
            )
            .limit(min(limit, 200))
            .offset(offset)
        )
        .scalars()
        .all()
    )

    return {
        "total": int(total),
        "items": [
            {
                "id": str(x.id),
                "provider_id": str(x.provider_id),
                "provider_name": x.provider.name if x.provider else None,

                "provider_stream_id": int(x.provider_stream_id),
                "name": x.name,
                "normalized_name": x.normalized_name,

                "channel_number": x.channel_number,

                "custom_logo_url": x.custom_logo_url,
                "logo": x.custom_logo_url,

                "alt1_stream_id": str(x.alt1_stream_id) if x.alt1_stream_id else None,
                "alt2_stream_id": str(x.alt2_stream_id) if x.alt2_stream_id else None,
                "alt3_stream_id": str(x.alt3_stream_id) if x.alt3_stream_id else None,

                "approved": x.approved,

                "epg_source_id": str(x.epg_source_id) if getattr(x, "epg_source_id", None) else None,
                "epg_channel_id": str(x.epg_channel_id) if getattr(x, "epg_channel_id", None) else None,

                "category_id": str(x.category_id) if x.category_id else None,
                "category_name": x.category.name if x.category else None,
                "category_ext_id": x.category.provider_category_id if x.category else None,
            }
            for x in rows
        ],
    }

def _resolve_alt_url_to_stream(db: Session, url: str) -> LiveStream:
    u = (url or "").strip()
    if not u:
        raise HTTPException(status_code=400, detail="Empty url")

    pu = urlparse(u)
    if not pu.scheme or not pu.netloc:
        raise HTTPException(status_code=400, detail="Invalid URL")

    # host base para comparar con Provider.base_url
    uhost = f"{pu.scheme}://{pu.netloc}".lower()

    providers = db.execute(select(Provider)).scalars().all()
    provider = None
    for p in providers:
        pp = urlparse((p.base_url or "").strip())
        phost = f"{pp.scheme}://{pp.netloc}".lower()
        if phost == uhost or (p.base_url or "").rstrip("/").lower() in u.lower():
            provider = p
            break

    if not provider:
        raise HTTPException(status_code=404, detail="Provider not found for that URL host")

    # Extraer stream_id:
    # típico: /live/user/pass/12345.m3u8
    parts = [x for x in (pu.path or "").split("/") if x]
    stream_part = parts[-1] if parts else ""
    if "live" in parts:
        i = parts.index("live")
        if len(parts) >= i + 4:
            stream_part = parts[i + 3]

    stream_id_str = (stream_part.split(".")[0] or "").strip()
    if not re.fullmatch(r"\d+", stream_id_str):
        raise HTTPException(status_code=400, detail="Could not extract numeric stream_id from URL")

    stream_id = int(stream_id_str)

    s = db.execute(
        select(LiveStream).where(
            LiveStream.provider_id == provider.id,
            LiveStream.provider_stream_id == stream_id
        )
    ).scalar_one_or_none()

    if not s:
        raise HTTPException(status_code=404, detail="Alt stream not found in your live_streams table")

    return s
