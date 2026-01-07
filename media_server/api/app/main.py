from fastapi import FastAPI
from sqlalchemy import text
from .routers.live import router as live_router
from .routers.epg import router as epg_router
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from .routers.providers import router as providers_router
import os
import asyncio
import logging
from sqlalchemy import text, select
from .db import engine, SessionLocal
from .models import EpgSource
from .routers.epg import sync_epg_for_source_id
from .routers.vod import router as vod_router
from .routers.series import router as series_router
from .routers.tmdb import router as tmdb_router
from .routers.collections import router as collections_router
from .routers.tmdb import (
    sync_movies as tmdb_sync_movies,
    sync_series as tmdb_sync_series,
    get_or_create_cfg as tmdb_get_or_create_cfg,
)


log = logging.getLogger("mini_media_server")

EPG_AUTO_SYNC = os.getenv("EPG_AUTO_SYNC", "1").strip().lower() not in {"0", "false", "no", "off"}
EPG_AUTO_SYNC_MINUTES = int(os.getenv("EPG_AUTO_SYNC_MINUTES", "30"))
EPG_AUTO_SYNC_HOURS = int(os.getenv("EPG_AUTO_SYNC_HOURS", "36"))
TMDB_AUTO_SYNC = os.getenv("TMDB_AUTO_SYNC", "1").strip().lower() not in {"0", "false", "no", "off"}
TMDB_AUTO_SYNC_MINUTES = int(os.getenv("TMDB_AUTO_SYNC_MINUTES", "5"))
TMDB_AUTO_SYNC_BATCH_MOVIES = int(os.getenv("TMDB_AUTO_SYNC_BATCH_MOVIES", "5"))
TMDB_AUTO_SYNC_BATCH_SERIES = int(os.getenv("TMDB_AUTO_SYNC_BATCH_SERIES", "5"))
TMDB_AUTO_SYNC_COOLDOWN_MINUTES = int(os.getenv("TMDB_AUTO_SYNC_COOLDOWN_MINUTES", "60"))
TMDB_AUTO_SYNC_IDLE_MINUTES = int(os.getenv("TMDB_AUTO_SYNC_IDLE_MINUTES", "30"))


app = FastAPI(title="Mini Media Server (Local)")
STATIC_DIR = os.path.join(os.path.dirname(__file__), "static")
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

@app.get("/ui")
def ui():
    return FileResponse(os.path.join(STATIC_DIR, "index.html"))

@app.get("/health")
def health():
    try:
        with engine.connect() as conn:
            conn.execute(text("select 1;"))
        return {"ok": True, "db": "up"}
    except Exception as e:
        return {"ok": False, "db": "down", "error": str(e)}

def _sync_one_source_blocking(source_id: str):
    db = SessionLocal()
    try:
        sync_epg_for_source_id(
            db,
            source_id=source_id,
            hours=EPG_AUTO_SYNC_HOURS,
            purge_all_programs=True,
        )
    finally:
        db.close()

def _sync_tmdb_movies_blocking():
    db = SessionLocal()
    try:
        return tmdb_sync_movies(
            limit=TMDB_AUTO_SYNC_BATCH_MOVIES,
            approved_only=True,
            cooldown_minutes=TMDB_AUTO_SYNC_COOLDOWN_MINUTES,
            db=db,
        )
    finally:
        db.close()

def _sync_tmdb_series_blocking():
    db = SessionLocal()
    try:
        return tmdb_sync_series(
            limit=TMDB_AUTO_SYNC_BATCH_SERIES,
            approved_only=True,
            cooldown_minutes=TMDB_AUTO_SYNC_COOLDOWN_MINUTES,
            db=db,
        )
    finally:
        db.close()


@app.on_event("startup")
async def _start_epg_auto_sync():
    if not EPG_AUTO_SYNC:
        log.info("EPG auto-sync: disabled (EPG_AUTO_SYNC=0)")
        return

    interval_s = max(60, EPG_AUTO_SYNC_MINUTES * 60)
    log.info("EPG auto-sync: enabled (every %s min, window=%s h)", EPG_AUTO_SYNC_MINUTES, EPG_AUTO_SYNC_HOURS)

    async def loop():
        await asyncio.sleep(2)
        while True:
            try:
                db = SessionLocal()
                try:
                    sources = db.execute(
                        select(EpgSource).where(EpgSource.is_active == True)
                    ).scalars().all()
                    source_ids = [str(s.id) for s in sources]
                finally:
                    db.close()

                for sid in source_ids:
                    try:
                        await asyncio.to_thread(_sync_one_source_blocking, sid)
                    except Exception as e:
                        log.exception("EPG auto-sync failed for source_id=%s: %s", sid, e)

            except Exception as e:
                log.exception("EPG auto-sync loop error: %s", e)

            await asyncio.sleep(interval_s)

    asyncio.create_task(loop())

@app.on_event("startup")
async def _start_tmdb_auto_sync():
    if not TMDB_AUTO_SYNC:
        log.info("TMDB auto-sync: disabled (TMDB_AUTO_SYNC=0)")
        return

    interval_s = max(60, TMDB_AUTO_SYNC_MINUTES * 60)
    idle_s = max(interval_s, TMDB_AUTO_SYNC_IDLE_MINUTES * 60)

    log.info(
        "TMDB auto-sync: enabled (every %s min, batch movies=%s, batch series=%s, cooldown=%s min)",
        TMDB_AUTO_SYNC_MINUTES,
        TMDB_AUTO_SYNC_BATCH_MOVIES,
        TMDB_AUTO_SYNC_BATCH_SERIES,
        TMDB_AUTO_SYNC_COOLDOWN_MINUTES,
    )

    async def loop():
        await asyncio.sleep(5)
        while True:
            try:
                # 1) Leer config TMDB (sin spamear logs si está apagado)
                db = SessionLocal()
                try:
                    cfg = tmdb_get_or_create_cfg(db)
                    enabled = bool(cfg.is_enabled) and bool(cfg.read_access_token or cfg.api_key)
                finally:
                    db.close()

                if not enabled:
                    await asyncio.sleep(idle_s)
                    continue

                # 2) Ejecutar tandas pequeñas (en thread)
                r_movies = await asyncio.to_thread(_sync_tmdb_movies_blocking)
                r_series = await asyncio.to_thread(_sync_tmdb_series_blocking)

                processed = int((r_movies or {}).get("processed", 0)) + int((r_series or {}).get("processed", 0))

                # 3) Si no había trabajo, duerme más para ser “Plex-like” y conservador
                await asyncio.sleep(idle_s if processed == 0 else interval_s)

            except Exception as e:
                log.exception("TMDB auto-sync loop error: %s", e)
                await asyncio.sleep(interval_s)

    asyncio.create_task(loop())




app.include_router(providers_router)
app.include_router(live_router)
app.include_router(epg_router)
app.include_router(vod_router)
app.include_router(series_router)
app.include_router(tmdb_router)
app.include_router(collections_router)
