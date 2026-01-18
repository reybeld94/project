import { el, badge, button, input, modal, pageShell } from "../ui.js";
import { api } from "../api.js";

function formatTime(value) {
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return "—";
  return new Intl.DateTimeFormat("es", { hour:"2-digit", minute:"2-digit" }).format(d);
}

function formatWindow(start, end) {
  return `${formatTime(start)} - ${formatTime(end)}`;
}

function mappingModal({ channel, onSaved }) {
  let sources = [];
  let sourceId = channel.epg_source_id || "";
  let q = "";
  let searchT = null;
  let loadSeq = 0;

  const status = el("div", { class:"text-xs text-zinc-500" }, "");
  const results = el("div", { class:"mt-3 space-y-2 max-h-[360px] overflow-auto hz-scroll pr-1" });

  async function loadSources() {
    const data = await api.epg.sources();
    sources = (data.items || []).filter(s => s.is_active);
    if (!sourceId && sources.length) sourceId = sources[0].id;
    renderSourceOptions();
    await loadChannels();
  }

  async function loadChannels() {
    const seq = ++loadSeq;
    results.innerHTML = "";
    if (!sourceId) {
      status.textContent = "Selecciona un EPG source.";
      return;
    }
    status.textContent = "Buscando canales…";
    try {
      const data = await api.epg.channels(sourceId, { q, limit: 100, offset: 0 });
      if (seq !== loadSeq) return;
      status.textContent = `${data.total} canales en XML`;
      results.innerHTML = "";

      if (!data.items.length) {
        results.appendChild(el("div", { class:"text-sm text-zinc-400" }, "Sin resultados."));
        return;
      }

      data.items.forEach(item => {
        const active = item.xmltv_id === channel.epg_channel_id && sourceId === channel.epg_source_id;
        results.appendChild(
          el("button", {
            class:`w-full text-left px-3 py-2 rounded-xl border ${active ? "border-blue-500/50 bg-blue-500/10" : "border-white/10 hover:bg-white/5"}`,
            onclick: async () => {
              status.textContent = "Guardando mapeo…";
              try {
                await api.live.patch(channel.live_id, {
                  epg_source_id: sourceId,
                  epg_channel_id: item.xmltv_id,
                });
                status.textContent = "✅ Mapeo guardado.";
                handleSaved();
              } catch (err) {
                status.textContent = `❌ ${err.message}`;
              }
            }
          }, [
            el("div", { class:"text-sm text-zinc-100 font-medium" }, item.display_name || item.xmltv_id),
            el("div", { class:"text-xs text-zinc-500" }, item.xmltv_id),
          ])
        );
      });
    } catch (err) {
      if (seq !== loadSeq) return;
      status.textContent = `❌ ${err.message}`;
    }
  }

  function renderSourceOptions() {
    sourceSelect.innerHTML = "";
    if (!sources.length) {
      sourceSelect.appendChild(el("option", { value:"" }, "No active EPG sources"));
      return;
    }
    sources.forEach(s => {
      sourceSelect.appendChild(el("option", { value:s.id }, s.name));
    });
    sourceSelect.value = sourceId || "";
  }

  const sourceSelect = el("select", {
    class:"w-full bg-black/30 border border-white/10 rounded-xl px-3 py-2 text-sm text-zinc-100",
    onchange: async (e) => {
      sourceId = e.target.value;
      await loadChannels();
    }
  });

  const searchInput = input({
    placeholder:"Search EPG channel (display name / xmltv_id)…",
    value:"",
    onInput: (val) => {
      q = val || "";
      if (searchT) clearTimeout(searchT);
      searchT = setTimeout(() => loadChannels(), 250);
    }
  });

  const removeBtn = button("Clear mapping", {
    tone:"zinc",
    small:true,
    onClick: async () => {
      status.textContent = "Limpiando mapeo…";
      try {
        await api.live.patch(channel.live_id, {
          epg_source_id: null,
          epg_channel_id: null,
        });
        status.textContent = "✅ Mapeo removido.";
        handleSaved();
      } catch (err) {
        status.textContent = `❌ ${err.message}`;
      }
    }
  });

  const body = el("div", {}, [
    el("div", { class:"grid grid-cols-1 md:grid-cols-2 gap-3" }, [
      el("div", {}, [
        el("div", { class:"text-xs text-zinc-400 mb-1" }, "EPG Source"),
        sourceSelect,
      ]),
      el("div", {}, [
        el("div", { class:"text-xs text-zinc-400 mb-1" }, "Search"),
        searchInput,
      ]),
    ]),
    el("div", { class:"flex items-center justify-between mt-3" }, [
      el("div", { class:"text-xs text-zinc-500" }, channel.name),
      removeBtn,
    ]),
    results,
    status,
  ]);

  const { close } = modal({
    title: "Asignar canal XMLTV",
    body,
    onClose: () => {
      if (searchT) clearTimeout(searchT);
    }
  });

  const handleSaved = () => {
    close();
    onSaved?.();
  };

  loadSources().catch(err => {
    status.textContent = `❌ ${err.message}`;
  });
}

export function EpgPage(appState) {
  let providerId = appState.epg?.providerId || "";
  let hours = appState.epg?.hours || 8;
  let approvedOnly = appState.epg?.approvedOnly ?? true;
  let limit = appState.epg?.limit || 80;
  let offset = appState.epg?.offset || 0;
  let q = appState.epg?.q || "";
  let loadSeq = 0;
  let providersLoaded = false;
  let searchT = null;

  const status = el("div", { class:"text-xs text-zinc-500" }, "Cargando EPG…");
  const gridWrap = el("div", { class:"hz-glass rounded-2xl h-full min-h-0 flex flex-col overflow-hidden" }, [
    el("div", { class:"p-5 text-sm text-zinc-300" }, "Cargando grid…"),
  ]);

  const providerSelect = el("select", {
    class:"bg-black/30 border border-white/10 rounded-xl px-3 py-2 text-sm text-zinc-100 min-w-[220px]",
    onchange: () => {
      providerId = providerSelect.value;
      appState.epg.providerId = providerId;
      loadGrid().catch(err => { status.textContent = `❌ ${err.message}`; });
    }
  });

  const hoursSelect = el("select", {
    class:"bg-black/30 border border-white/10 rounded-xl px-3 py-2 text-sm text-zinc-100",
    onchange: () => {
      hours = parseInt(hoursSelect.value, 10) || 8;
      appState.epg.hours = hours;
      loadGrid().catch(err => { status.textContent = `❌ ${err.message}`; });
    }
  }, [2,4,6,8,12,24].map(h => el("option", { value:String(h) }, `${h}h`)));
  hoursSelect.value = String(hours);

  const approvedToggle = el("label", { class:"inline-flex items-center gap-2 text-sm text-zinc-300" }, [
    el("input", {
      type:"checkbox",
      checked: approvedOnly,
      onchange: () => {
        approvedOnly = !approvedOnly;
        appState.epg.approvedOnly = approvedOnly;
        loadGrid().catch(err => { status.textContent = `❌ ${err.message}`; });
      }
    }),
    el("span", {}, "Solo aprobados"),
  ]);

  const searchInput = input({
    placeholder:"Buscar canal…",
    value:q,
    onInput: (val) => {
      q = val;
      appState.epg.q = q;
      if (searchT) clearTimeout(searchT);
      searchT = setTimeout(() => {
        loadGrid().catch(err => { status.textContent = `❌ ${err.message}`; });
      }, 250);
    }
  });

  const reloadBtn = button("Refresh", { tone:"zinc", onClick: () => loadGrid().catch(err => { status.textContent = `❌ ${err.message}`; }) });
  const reload = () => loadGrid().catch(err => { status.textContent = `❌ ${err.message}`; });

  async function loadProviders() {
    const data = await api.providers.list();
    const active = data.filter(p => p.is_active);
    providerSelect.innerHTML = "";
    if (!active.length) {
      providerSelect.appendChild(el("option", { value:"" }, "No active providers"));
      return;
    }
    active.forEach(p => {
      providerSelect.appendChild(el("option", { value:p.id }, p.name));
    });
    if (!providerId) providerId = active[0].id;
    providerSelect.value = providerId;
    providersLoaded = true;
  }

  async function loadGrid() {
    await ensureProviders();
    const seq = ++loadSeq;
    status.textContent = "Cargando grid…";
    gridWrap.innerHTML = "";
    gridWrap.appendChild(el("div", { class:"p-5 text-sm text-zinc-300" }, "Cargando grid…"));

    if (!providerId) {
      status.textContent = "Selecciona un provider.";
      return;
    }

    const data = await api.epg.grid(providerId, { hours, limit, offset, approvedOnly });
    if (seq !== loadSeq) return;

    status.textContent = `Canales: ${data.count} • Ventana ${formatWindow(data.window.start, data.window.end)}`;

    const scrollArea = el("div", { class:"flex-1 min-h-0 overflow-auto hz-scroll" });
    const grid = el("div", { class:"p-4 grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4 gap-4" });

    const items = (data.items || []).filter(item => {
      if (!q) return true;
      return (item.name || "").toLowerCase().includes(q.toLowerCase());
    });

    if (!items.length) {
      grid.appendChild(el("div", { class:"text-sm text-zinc-400" }, "Sin canales para mostrar."));
    }

    items.forEach(item => {
      const logo = item.logo
        ? el("img", { src:item.logo, class:"w-12 h-12 rounded-xl bg-zinc-200/90 object-contain bg-black/30 border border-white/10" })
        : el("div", { class:"w-12 h-12 rounded-xl bg-zinc-900/70 border border-white/10 flex items-center justify-center text-[10px] text-zinc-500" }, "NO");

      const mappingBadge = item.epg_channel_id
        ? badge("EPG", "green")
        : badge("NO EPG", "zinc");

      const header = el("div", { class:"flex items-start justify-between gap-3" }, [
        el("div", { class:"flex items-center gap-3" }, [
          logo,
          el("div", {}, [
            el("div", { class:"text-sm font-semibold text-zinc-100" }, item.name || "Sin nombre"),
            el("div", { class:"text-xs text-zinc-500" }, item.channel_number ? `Canal ${item.channel_number}` : "Sin número"),
          ])
        ]),
        mappingBadge,
      ]);

      const mapping = el("div", { class:"text-xs text-zinc-400 mt-2" },
        item.epg_channel_name
          ? `${item.epg_channel_name} (${item.epg_channel_id})`
          : "Sin mapeo XMLTV"
      );

      const programList = el("div", { class:"mt-3 space-y-2" });
      if (!item.programs?.length) {
        programList.appendChild(el("div", { class:"text-xs text-zinc-500" }, "Sin programación en la ventana."));
      } else {
        item.programs.forEach(pr => {
          programList.appendChild(
            el("div", { class:"rounded-xl border border-white/10 bg-black/30 px-3 py-2" }, [
              el("div", { class:"text-[11px] text-zinc-400" }, `${formatTime(pr.start)} - ${formatTime(pr.end)}`),
              el("div", { class:"text-sm text-zinc-100" }, pr.title || "Untitled"),
              pr.category ? el("div", { class:"text-[11px] text-zinc-500" }, pr.category) : null,
            ])
          );
        });
      }

      const actions = el("div", { class:"mt-3 flex items-center justify-between" }, [
        el("div", { class:"text-[11px] text-zinc-500" }, item.epg_source_id ? "Fuente asignada" : "Sin fuente"),
        button("Map XML", {
          tone:"zinc",
          small:true,
          onClick: () => mappingModal({ channel: item, onSaved: reload }),
        })
      ]);

      const card = el("div", { class:"hz-glass rounded-2xl p-4 flex flex-col" }, [
        header,
        mapping,
        programList,
        actions,
      ]);

      grid.appendChild(card);
    });

    scrollArea.appendChild(grid);
    gridWrap.innerHTML = "";
    gridWrap.appendChild(scrollArea);
  }

  async function ensureProviders() {
    if (providersLoaded) return;
    await loadProviders();
  }

  const content = el("div", { class:"h-full min-h-0 flex flex-col gap-3" }, [
    el("div", { class:"flex flex-wrap items-center gap-3" }, [
      providerSelect,
      hoursSelect,
      approvedToggle,
      reloadBtn,
      el("div", { class:"w-[240px]" }, searchInput),
    ]),
    status,
    el("div", { class:"flex-1 min-h-0" }, gridWrap),
  ]);

  ensureProviders()
    .then(loadGrid)
    .catch(err => {
      status.textContent = `❌ ${err.message}`;
    });

  return pageShell({
    title: "EPG",
    subtitle: "Grid de canales y programación desde tu XMLTV.",
    content,
  });
}
