// --- Global crash overlay (no more black screens) ---
(function crashOverlay(){
  function show(title, err){
    const msg = (err && (err.stack || err.message)) ? (err.stack || err.message) : String(err);
    console.error(title, err);

    const old = document.getElementById("__crash_overlay__");
    if (old) old.remove();

    const overlay = document.createElement("div");
    overlay.id = "__crash_overlay__";
    overlay.style.cssText = `
      position:fixed; inset:0; z-index:999999;
      background:rgba(0,0,0,.88);
      color:#fff; font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      padding:16px; overflow:auto;
    `;
    overlay.innerHTML = `
      <div style="max-width:1100px;margin:0 auto">
        <div style="display:flex;align-items:center;justify-content:space-between;gap:12px">
          <h1 style="font-size:18px;font-weight:700;margin:0">UI Crash: ${title}</h1>
          <button id="__crash_close__" style="background:#222;border:1px solid #444;border-radius:10px;padding:8px 10px;color:#fff;cursor:pointer">Close</button>
        </div>
        <pre style="white-space:pre-wrap;word-break:break-word;margin-top:12px;line-height:1.35">${escapeHtml(msg)}</pre>
        <div style="margin-top:10px;color:#aaa;font-size:12px">Tip: revisa imports/paths o el endpoint que falló. Mira Console para más detalles.</div>
      </div>
    `;
    document.body.appendChild(overlay);
    document.getElementById("__crash_close__").onclick = () => overlay.remove();
  }

  function escapeHtml(s){
    return String(s)
      .replaceAll("&","&amp;")
      .replaceAll("<","&lt;")
      .replaceAll(">","&gt;");
  }

  window.addEventListener("error", (e) => show("window.error", e.error || e.message));
  window.addEventListener("unhandledrejection", (e) => show("unhandledrejection", e.reason));
})();


const BASE = ""; // mismo host

async function req(path, { method="GET", body=null, headers={} } = {}) {
  const opts = { method, headers: { ...headers } };
  if (body !== null) {
    opts.headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(body);
  }

  const r = await fetch(`${BASE}${path}`, opts);
  const text = await r.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { raw: text }; }

  if (!r.ok) {
    const msg = data?.detail || data?.error || `${r.status} ${r.statusText}`;
    const err = new Error(msg);
    err.status = r.status;
    err.data = data;
    throw err;
  }
  return data;
}
async function reqForm(path, { method="POST", formData } = {}) {
  const r = await fetch(`${BASE}${path}`, { method, body: formData });
  const text = await r.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { raw: text }; }

  if (!r.ok) {
    const msg = data?.detail || data?.error || `${r.status} ${r.statusText}`;
    const err = new Error(msg);
    err.status = r.status;
    err.data = data;
    throw err;
  }
  return data;
}


export const api = {
  health: () => req("/health"),

  providers: {
    list: () => req("/providers"),
    create: (payload) => req("/providers", { method:"POST", body: payload }),
    test: (id) => req(`/providers/${id}/test`),
    patch: (id, payload) => req(`/providers/${id}`, { method:"PATCH", body: payload }),
    syncAll: (id) => req(`/providers/${id}/sync/all`, { method:"POST" }),
  },

  epg: {
    sources: () => req("/epg/sources"),
    createSource: (payload) => req("/epg/sources", { method:"POST", body: payload }),
    syncSource: (sourceId, hours=36) => req(`/epg/sync?source_id=${encodeURIComponent(sourceId)}&hours=${hours}`, { method:"POST" }),
    channels: (sourceId, { q="", limit=200, offset=0 }={}) =>
      req(`/epg/channels?source_id=${encodeURIComponent(sourceId)}&q=${encodeURIComponent(q)}&limit=${limit}&offset=${offset}`),
  },

  tmdb: {
    getConfig: () => req("/tmdb/config"),
    activity: ({ limit=20 }={}) => req(`/tmdb/activity?limit=${limit}`),
    saveConfig: (payload) => req("/tmdb/config", { method:"PATCH", body: payload }),
    status: () => req("/tmdb/status"),
    genres: ({ kind="movie" }={}) => req(`/tmdb/genres?kind=${encodeURIComponent(kind)}`),
    syncMovies: ({ limit=20, approvedOnly=true }={}) =>
      req(`/tmdb/sync/movies?limit=${limit}&approved_only=${approvedOnly}`, { method:"POST" }),
    syncSeries: ({ limit=20, approvedOnly=true }={}) =>
      req(`/tmdb/sync/series?limit=${limit}&approved_only=${approvedOnly}`, { method:"POST" }),
  },


  collections: {
    list: ({ q="", enabled=null, source_type="", limit=50, offset=0 }={}) => {
      const en = enabled === null ? "" : `&enabled=${enabled}`;
      const src = source_type ? `&source_type=${encodeURIComponent(source_type)}` : "";
      return req(`/collections?q=${encodeURIComponent(q)}&limit=${limit}&offset=${offset}${en}${src}`);
    },
    create: (payload) => req("/collections", { method:"POST", body: payload }),
    update: (idOrSlug, payload) => req(`/collections/${encodeURIComponent(idOrSlug)}`, { method:"PATCH", body: payload }),
    remove: (idOrSlug) => req(`/collections/${encodeURIComponent(idOrSlug)}`, { method:"DELETE" }),
    preview: (payload) => req("/collections/preview", { method:"POST", body: payload }),
    items: (idOrSlug, { page=1, stale_while_revalidate=false }={}) =>
      req(`/collections/${encodeURIComponent(idOrSlug)}/items?page=${page}&stale_while_revalidate=${stale_while_revalidate}`),
  },


    vod: {
  listAll: ({ q="", limit=60, offset=0, activeOnly=true, approved=null, synced=null }={}) => {
    const ap = approved === null ? "" : `&approved=${approved}`;
    const sy = synced === null ? "" : `&synced=${synced}`;
    return req(`/vod/all?q=${encodeURIComponent(q)}&limit=${limit}&offset=${offset}&active_only=${activeOnly}${ap}${sy}`);
  },

  play: (vodId, format=null) => {
  const q = format ? `?format=${encodeURIComponent(format)}` : "";
  return req(`/vod/${encodeURIComponent(vodId)}/play${q}`);
},

  get: (vodId) => req(`/vod/${vodId}`),
  patch: (vodId, payload) => req(`/vod/${vodId}`, { method:"PATCH", body: payload }),
},


    series: {
    listAll: ({ q="", limit=60, offset=0, activeOnly=true, approved=null }={}) => {
      const ap = approved === null ? "" : `&approved=${approved}`;
      return req(`/series/all?q=${encodeURIComponent(q)}&limit=${limit}&offset=${offset}&active_only=${activeOnly}${ap}`);
    },
    seasons: (id) => req(`/series/${encodeURIComponent(id)}/seasons`),
    get: (seriesId) => req(`/series/${seriesId}`),
    getInfo: (id) => req(`/series/${encodeURIComponent(id)}/info`),
    patch: (seriesId, payload) => req(`/series/${seriesId}`, { method:"PATCH", body: payload }),
    playEpisode: (provider_id, episode_id, fmt="mp4") =>
  req(`/series/episode/play?provider_id=${encodeURIComponent(provider_id)}&episode_id=${encodeURIComponent(episode_id)}&format=${encodeURIComponent(fmt)}`),

  },

  live: {
    listAll: ({ q="", limit=50, offset=0, activeOnly=true, approved=null }={}) => {
      const ap = approved === null ? "" : `&approved=${approved}`;
      return req(`/live/all?q=${encodeURIComponent(q)}&limit=${limit}&offset=${offset}&active_only=${activeOnly}${ap}`);
    },

    list: ({ providerId, q="", limit=50, offset=0, activeOnly=true }={}) =>
      req(`/live?provider_id=${encodeURIComponent(providerId)}&q=${encodeURIComponent(q)}&limit=${limit}&offset=${offset}&active_only=${activeOnly}`),

    play: (liveId, arg="m3u8") => {
  let format = "m3u8";
  let alt1 = false, alt2 = false, alt3 = false;
  let openVlc = false;

  if (typeof arg === "string") {
    format = arg || "m3u8";
  } else if (arg && typeof arg === "object") {
    format = arg.format || "m3u8";
    alt1 = !!arg.alt1;
    alt2 = !!arg.alt2;
    alt3 = !!arg.alt3;
    openVlc = !!arg.openVlc;
  }

  const qs = new URLSearchParams();
  qs.set("format", format);
  if (alt1) qs.set("alt1", "1");
  if (alt2) qs.set("alt2", "1");
  if (alt3) qs.set("alt3", "1");
  if (openVlc) qs.set("open_vlc", "1");

  return req(`/live/${encodeURIComponent(liveId)}/play?${qs.toString()}`);
},


    patch: (liveId, payload) => req(`/live/${liveId}`, { method:"PATCH", body: payload }),

    uploadLogo: async (liveId, file) => {
      const fd = new FormData();
      fd.append("file", file);
      return reqForm(`/live/${liveId}/logo`, { method:"POST", formData: fd });
    },

    deleteLogo: (liveId) => req(`/live/${liveId}/logo`, { method:"DELETE" }),
  },


  openapi: () => req("/openapi.json"),
};
