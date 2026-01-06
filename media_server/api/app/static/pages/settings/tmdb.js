import { el, badge, button, input } from "../../ui.js";
import { api } from "../../api.js";

export function TmdbTab() {
  let cfg = null;
  let stat = null;
  let activity = null;

  let apiKey = "";
  let token = "";
  let enabled = false;
  let language = "en-US";
  let region = "US";
  let rps = 5;

  let autoRefresh = true;
  let refreshSec = 3;

  let timer = null;
  let inFlight = false;
  let lastRefreshAt = null;

  const msg = el("div", { class:"text-xs text-zinc-500 mt-2" }, "");

  const enabledToggle = el("input", {
    type:"checkbox",
    class:"scale-110",
    onchange: (e) => { enabled = !!e.target.checked; }
  });

  const apiKeyInput = input({ placeholder:"TMDB API Key (v3) — opcional si usas token", value:"", onInput:(v)=>{ apiKey=v; } });
  const tokenInput  = input({ placeholder:"TMDB Read Access Token (v4) — recomendado", value:"", onInput:(v)=>{ token=v; } });
  const langInput   = input({ placeholder:"language (ej: en-US, es-ES)", value:"en-US", onInput:(v)=>{ language=v; } });
  const regInput    = input({ placeholder:"region (ej: US)", value:"US", onInput:(v)=>{ region=v; } });

  const rpsInput    = el("input", {
    class:"w-[140px] rounded-lg bg-zinc-900/70 border border-white/10 px-3 py-2 text-sm text-zinc-100 focus:outline-none focus-visible:hz-focus",
    type:"number", min:"1", max:"20", value:"5",
    oninput:(e)=>{ rps = parseInt(e.target.value || "5", 10); }
  });

  const autoToggle = el("input", {
    type:"checkbox",
    class:"scale-110",
    onchange: (e) => {
      autoRefresh = !!e.target.checked;
      autoToggle.checked = autoRefresh;
      if (autoRefresh) startPolling();
      else stopPolling();
    }
  });

  const refreshInput = el("input", {
    class:"w-[110px] rounded-lg bg-zinc-900/70 border border-white/10 px-3 py-2 text-sm text-zinc-100 focus:outline-none focus-visible:hz-focus",
    type:"number", min:"2", max:"30", value:"3",
    oninput:(e)=> {
      refreshSec = Math.max(2, Math.min(30, parseInt(e.target.value || "3", 10)));
      refreshInput.value = String(refreshSec);
      if (autoRefresh) startPolling();
    }
  });

  const statusBox = el("div", { class:"hz-glass rounded-2xl p-4 border border-white/10" }, [
    el("div", { class:"flex items-center justify-between gap-3" }, [
      el("div", { class:"font-medium text-zinc-100" }, "Sync Status"),
      el("div", { class:"flex items-center gap-2" }, [
        badge("Movies", "zinc"),
        badge("Series", "zinc"),
      ])
    ]),
    el("div", { class:"text-sm text-zinc-400 mt-2" }, "Cargando…")
  ]);

  const activityBox = el("div", { class:"hz-glass rounded-2xl p-4 border border-white/10" }, [
    el("div", { class:"flex items-center justify-between" }, [
      el("div", { class:"font-medium text-zinc-100" }, "Live Activity"),
      el("div", { class:"text-xs text-zinc-500" }, "—")
    ]),
    el("div", { class:"text-sm text-zinc-400 mt-2" }, "Cargando…")
  ]);

  function isOnTmdbTab() {
    return String(location.hash || "").includes("#/settings/tmdb");
  }

  function stopPolling() {
    if (timer) clearInterval(timer);
    timer = null;
  }

  function startPolling() {
    stopPolling();
    // refresh inmediato y luego interval
    refreshNow();
    timer = setInterval(() => {
      if (!isOnTmdbTab()) { stopPolling(); return; }
      refreshNow();
    }, Math.max(2, refreshSec) * 1000);
  }

  function fmtWhen(iso) {
    if (!iso) return "—";
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    return d.toLocaleString();
  }

  async function refreshNow() {
    if (inFlight) return;
    inFlight = true;
    try {
      stat = await api.tmdb.status();
      activity = await api.tmdb.activity({ limit: 20 });
      lastRefreshAt = new Date();
      renderStatus();
      renderActivity();
    } catch (e) {
      // no mates la UI por un error temporal
      msg.textContent = `Auto-refresh error: ${e.message}`;
    } finally {
      inFlight = false;
    }
  }

  async function load() {
    msg.textContent = "Loading TMDB config…";
    cfg = await api.tmdb.getConfig();
    stat = await api.tmdb.status();
    activity = await api.tmdb.activity({ limit: 20 });

    enabled = !!cfg.is_enabled;
    enabledToggle.checked = enabled;

    language = cfg.language || "en-US";
    region = cfg.region || "US";
    rps = cfg.requests_per_second || 5;

    langInput.value = language;
    regInput.value = region;
    rpsInput.value = String(rps);

    apiKeyInput.value = "";
    tokenInput.value = "";
    apiKeyInput.placeholder = cfg.api_key_masked ? `API Key (guardada): ${cfg.api_key_masked}` : "TMDB API Key (v3) — opcional si usas token";
    tokenInput.placeholder  = cfg.read_access_token_masked ? `Token (guardado): ${cfg.read_access_token_masked}` : "TMDB Read Access Token (v4) — recomendado";

    autoToggle.checked = autoRefresh;
    refreshInput.value = String(refreshSec);

    renderStatus();
    renderActivity();
    msg.textContent = "";

    // arranca live refresh si está en esta pestaña
    if (autoRefresh) startPolling();
  }

  function renderStatus() {
    if (!stat) return;
    statusBox.innerHTML = "";

    const line = (label, a, b, c) =>
      el("div", { class:"flex items-center justify-between text-sm py-1" }, [
        el("div", { class:"text-zinc-300" }, label),
        el("div", { class:"flex items-center gap-2" }, [
          badge(`synced ${a}`, "green"),
          badge(`missing ${b}`, "amber"),
          badge(`failed ${c}`, "red"),
        ])
      ]);

    statusBox.appendChild(el("div", { class:"flex items-center justify-between" }, [
      el("div", { class:"font-medium text-zinc-100" }, "Sync Status"),
      stat.enabled ? badge("ENABLED", "green") : badge("DISABLED", "amber"),
    ]));

    statusBox.appendChild(el("div", { class:"text-xs text-zinc-500 mt-2" },
      "Auto-sync corre en batches para no abusar del servicio y respetar 429."
    ));

    statusBox.appendChild(el("div", { class:"text-xs text-zinc-500 mt-1" }, [
      el("span", {}, "Last refresh: "),
      el("span", { class:"text-zinc-300" }, lastRefreshAt ? lastRefreshAt.toLocaleString() : "—")
    ]));

    statusBox.appendChild(el("div", { class:"mt-3 space-y-2" }, [
      line(`Movies total ${stat.movies_total}`, stat.movies_synced, stat.movies_missing, stat.movies_failed),
      line(`Series total ${stat.series_total}`, stat.series_synced, stat.series_missing, stat.series_failed),
    ]));
  }

  function renderActivity() {
    activityBox.innerHTML = "";

    const headerRight = el("div", { class:"text-xs text-zinc-500" }, activity?.server_time ? fmtWhen(activity.server_time) : "—");

    activityBox.appendChild(el("div", { class:"flex items-center justify-between" }, [
      el("div", { class:"font-medium text-zinc-100" }, "Live Activity"),
      headerRight,
    ]));

    activityBox.appendChild(el("div", { class:"text-xs text-zinc-500 mt-2" },
      "Últimos ítems tocados por el sincronizador (synced/missing/failed)."
    ));

    const items = (activity?.items || []);
    if (!items.length) {
      activityBox.appendChild(el("div", { class:"text-sm text-zinc-400 mt-3" }, "Nada reciente todavía…"));
      return;
    }

    const list = el("div", { class:"mt-3 space-y-2" }, items.map(it => {
      const tone = it.tmdb_status === "synced" ? "green" : (it.tmdb_status === "missing" ? "amber" : (it.tmdb_status === "failed" ? "red" : "zinc"));
      const title = it.tmdb_title || it.normalized_name || it.name || "(no title)";
      const when = it.tmdb_last_sync ? fmtWhen(it.tmdb_last_sync) : "—";

      return el("div", { class:"flex items-start justify-between gap-3 p-3 rounded-xl border border-white/10 bg-white/5" }, [
        el("div", { class:"min-w-0" }, [
          el("div", { class:"flex items-center gap-2" }, [
            badge(it.kind.toUpperCase(), "zinc"),
            badge(it.tmdb_status || "?", tone),
            it.tmdb_id ? badge(`id ${it.tmdb_id}`, "blue") : null,
          ]),
          el("div", { class:"mt-1 text-sm text-zinc-100 truncate" }, title),
          el("div", { class:"mt-1 text-xs text-zinc-500 truncate" }, it.name),
          it.tmdb_error ? el("div", { class:"mt-1 text-xs text-rose-200/90 truncate" }, it.tmdb_error) : null,
        ]),
        el("div", { class:"text-xs text-zinc-500 whitespace-nowrap" }, when)
      ]);
    }));

    activityBox.appendChild(list);
  }

  const saveBtn = button("Save TMDB Settings", {
    tone:"blue",
    onClick: async () => {
      msg.textContent = "Saving…";
      try {
        await api.tmdb.saveConfig({
          is_enabled: enabled,
          api_key: apiKey || null,
          read_access_token: token || null,
          language: (language || "").trim() || null,
          region: (region || "").trim() || null,
          requests_per_second: rps || 5,
        });
        await load();
        msg.textContent = "Saved ✅";
      } catch (e) {
        msg.textContent = `Error: ${e.message}`;
      }
    }
  });

  const syncMoviesBtn = button("Sync next 20 Movies", {
    tone:"zinc",
    onClick: async () => {
      msg.textContent = "Syncing movies…";
      try {
        await api.tmdb.syncMovies({ limit: 20, approvedOnly: true });
        await refreshNow();
        msg.textContent = "Movies batch done ✅";
      } catch (e) {
        msg.textContent = `Error: ${e.message}`;
      }
    }
  });

  const syncSeriesBtn = button("Sync next 20 Series", {
    tone:"zinc",
    onClick: async () => {
      msg.textContent = "Syncing series…";
      try {
        await api.tmdb.syncSeries({ limit: 20, approvedOnly: true });
        await refreshNow();
        msg.textContent = "Series batch done ✅";
      } catch (e) {
        msg.textContent = `Error: ${e.message}`;
      }
    }
  });

  const root = el("div", { class:"space-y-4" }, [
    el("div", { class:"hz-glass rounded-2xl p-4 border border-white/10" }, [
      el("div", { class:"flex items-center justify-between" }, [
        el("div", { class:"font-medium text-zinc-100" }, "TMDB Configuration"),
        el("label", { class:"flex items-center gap-2 text-sm text-zinc-300" }, [
          enabledToggle,
          el("span", {}, "Enable TMDB Sync")
        ])
      ]),

      el("div", { class:"grid grid-cols-1 md:grid-cols-2 gap-3 mt-3" }, [
        el("div", {}, [el("div", { class:"text-xs text-zinc-500 mb-1" }, "Read Access Token (recommended)"), tokenInput]),
        el("div", {}, [el("div", { class:"text-xs text-zinc-500 mb-1" }, "API Key (optional)"), apiKeyInput]),
        el("div", {}, [el("div", { class:"text-xs text-zinc-500 mb-1" }, "Language"), langInput]),
        el("div", {}, [el("div", { class:"text-xs text-zinc-500 mb-1" }, "Region"), regInput]),
        el("div", {}, [el("div", { class:"text-xs text-zinc-500 mb-1" }, "Requests/sec (safe throttle)"), rpsInput]),
      ]),

      el("div", { class:"mt-4 flex flex-wrap items-center gap-3" }, [
        el("label", { class:"flex items-center gap-2 text-sm text-zinc-300" }, [
          autoToggle,
          el("span", {}, "Live refresh")
        ]),
        el("div", { class:"flex items-center gap-2 text-sm text-zinc-300" }, [
          el("span", { class:"text-zinc-500" }, "Every"),
          refreshInput,
          el("span", { class:"text-zinc-500" }, "sec")
        ]),
      ]),

      el("div", { class:"flex items-center gap-2 mt-4" }, [saveBtn, syncMoviesBtn, syncSeriesBtn]),
      msg,
    ]),
    statusBox,
    activityBox,
  ]);

  load();
  return root;
}
