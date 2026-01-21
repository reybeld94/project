import { el, badge, button, input, pageShell } from "../ui.js";
import { api } from "../api.js";

function formatTime(value) {
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return "—";
  return new Intl.DateTimeFormat("es", { hour:"2-digit", minute:"2-digit" }).format(d);
}

function formatWindow(start, end) {
  return `${formatTime(start)} - ${formatTime(end)}`;
}

function formatEpgOption(channel) {
  const label = channel.display_name || channel.xmltv_id || "";
  const id = channel.xmltv_id || "";
  return label && id ? `${label} (${id})` : (label || id || "");
}

function resolveXmltvId(value, epgChannelLookup, optionMap) {
  if (!value) return null;
  const direct = optionMap.get(value);
  if (direct) return direct;
  if (epgChannelLookup.has(value)) return value;
  const match = value.match(/\(([^)]+)\)\s*$/);
  if (!match) return null;
  const candidate = match[1].trim();
  return epgChannelLookup.has(candidate) ? candidate : null;
}

// Convert minutes to +/-HHMM format
function minutesToOffset(minutes) {
  if (minutes === null || minutes === undefined || minutes === 0) return "";
  const sign = minutes >= 0 ? "+" : "-";
  const abs = Math.abs(minutes);
  const hours = Math.floor(abs / 60);
  const mins = abs % 60;
  return `${sign}${String(hours).padStart(2, "0")}${String(mins).padStart(2, "0")}`;
}

// Convert +/-HHMM format to minutes
function offsetToMinutes(value) {
  if (!value || value.trim() === "") return null;
  const trimmed = value.trim();
  const match = trimmed.match(/^([+-])(\d{2})(\d{2})$/);
  if (!match) return null;
  const sign = match[1];
  const hours = parseInt(match[2], 10);
  const mins = parseInt(match[3], 10);
  if (hours > 12 || mins >= 60) return null; // Limit to +/-12 hours
  const totalMinutes = hours * 60 + mins;
  return sign === "-" ? -totalMinutes : totalMinutes;
}

export function EpgPage(appState) {
  let providerId = appState.epg?.providerId || "";
  let epgSourceId = appState.epg?.sourceId || "";
  let hours = appState.epg?.hours || 8;
  let approvedOnly = appState.epg?.approvedOnly ?? true;
  let limit = appState.epg?.limit || 80;
  let offset = appState.epg?.offset || 0;
  let q = appState.epg?.q || "";
  let loadSeq = 0;
  let providersLoaded = false;
  let epgSourcesLoaded = false;
  let epgSources = [];
  let epgChannels = [];
  let epgChannelsTotal = 0;
  let epgChannelsSource = "";
  let epgChannelsLoading = false;
  let searchT = null;

  const status = el("div", { class:"text-xs text-zinc-500" }, "Cargando EPG…");
  const mappingStatus = el("div", { class:"text-xs text-zinc-500" }, "");
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

  const epgSourceSelect = el("select", {
    class:"bg-black/30 border border-white/10 rounded-xl px-3 py-2 text-sm text-zinc-100 min-w-[220px]",
    onchange: async () => {
      epgSourceId = epgSourceSelect.value;
      appState.epg.sourceId = epgSourceId;
      await loadEpgChannels();
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

  async function loadEpgSources() {
    const data = await api.epg.sources();
    epgSources = (data.items || []).filter(source => source.is_active);
    epgSourceSelect.innerHTML = "";
    if (!epgSources.length) {
      epgSourceSelect.appendChild(el("option", { value:"" }, "No active EPG sources"));
      epgSourceId = "";
      appState.epg.sourceId = "";
      epgSourcesLoaded = true;
      return;
    }
    epgSources.forEach(source => {
      epgSourceSelect.appendChild(el("option", { value:source.id }, source.name));
    });
    if (!epgSourceId) epgSourceId = epgSources[0].id;
    epgSourceSelect.value = epgSourceId;
    appState.epg.sourceId = epgSourceId;
    epgSourcesLoaded = true;
  }

  async function loadEpgChannels() {
    if (!epgSourceId || epgChannelsLoading) return;
    if (epgChannelsSource === epgSourceId && epgChannels.length) return;
    epgChannelsLoading = true;
    mappingStatus.textContent = "Cargando canales XMLTV…";
    try {
      const data = await api.epg.channels(epgSourceId, { q:"", limit: 500, offset: 0 });
      epgChannels = data.items || [];
      epgChannelsTotal = data.total || epgChannels.length;
      epgChannelsSource = epgSourceId;
      mappingStatus.textContent = epgChannelsTotal > epgChannels.length
        ? `⚠️ Mostrando ${epgChannels.length} de ${epgChannelsTotal} canales XMLTV.`
        : `XMLTV cargado (${epgChannels.length}).`;
    } catch (err) {
      mappingStatus.textContent = `❌ ${err.message}`;
    } finally {
      epgChannelsLoading = false;
    }
  }

  async function loadGrid() {
    await ensureProviders();
    await ensureEpgSources();
    await loadEpgChannels();
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

    const items = (data.items || []).filter(item => {
      if (!q) return true;
      return (item.name || "").toLowerCase().includes(q.toLowerCase());
    });

    const windowStart = new Date(data.window.start);
    const windowEnd = new Date(data.window.end);
    const slotMinutes = 30;
    const slotWidth = 140;
    const channelColWidth = 240;
    const xmlColWidth = 260;

    const windowStartMs = windowStart.getTime();
    const windowEndMs = windowEnd.getTime();
    const totalMinutes = Math.max(slotMinutes, Math.ceil((windowEndMs - windowStartMs) / 60000));
    const slotCount = Math.ceil(totalMinutes / slotMinutes);
    const gridColumns = `${channelColWidth}px ${xmlColWidth}px repeat(${slotCount}, ${slotWidth}px)`;

    const scrollArea = el("div", { class:"flex-1 min-h-0 overflow-auto hz-scroll" });
    const gridBody = el("div", { class:"min-w-full" });

    const headerRow = el("div", {
      class:"sticky top-0 z-10 bg-black/80 border-b border-white/10",
      style: `display:grid;grid-template-columns:${gridColumns};`
    }, [
      el("div", { class:"px-3 py-2 text-xs uppercase tracking-wide text-zinc-400 border-r border-white/10" }, "Canal"),
      el("div", { class:"px-3 py-2 text-xs uppercase tracking-wide text-zinc-400 border-r border-white/10" }, "XMLTV"),
      ...Array.from({ length: slotCount }).map((_, idx) => {
        const slotTime = new Date(windowStartMs + idx * slotMinutes * 60000);
        return el("div", {
          class:"px-2 py-2 text-xs text-zinc-400 border-r border-white/5 text-center whitespace-nowrap"
        }, formatTime(slotTime));
      }),
    ]);

    gridBody.appendChild(headerRow);

    if (!items.length) {
      gridBody.appendChild(
        el("div", { class:"p-4 text-sm text-zinc-400" }, "Sin canales para mostrar.")
      );
    }

    const epgChannelLookup = new Map(epgChannels.map(item => [item.xmltv_id, item.display_name || item.xmltv_id]));
    const epgSourceLookup = new Map(epgSources.map(source => [source.id, source.name]));
    const epgOptionMap = new Map(epgChannels.map(ch => [formatEpgOption(ch), ch.xmltv_id]));

    items.forEach(item => {
      const row = el("div", {
        class:"border-b border-white/5",
        style: `display:grid;grid-template-columns:${gridColumns};align-items:stretch;min-height:72px;`
      });

      const logo = item.logo
        ? el("img", { src:item.logo, class:"w-10 h-10 rounded-xl bg-zinc-200/90 object-contain bg-black/30 border border-white/10" })
        : el("div", { class:"w-10 h-10 rounded-xl bg-zinc-900/70 border border-white/10 flex items-center justify-center text-[10px] text-zinc-500" }, "NO");

      const channelCell = el("div", { class:"flex items-center gap-3 px-3 py-3 border-r border-white/10" }, [
        logo,
        el("div", {}, [
          el("div", { class:"text-sm font-semibold text-zinc-100" }, item.name || "Sin nombre"),
          el("div", { class:"text-xs text-zinc-500" }, item.channel_number ? `Canal ${item.channel_number}` : "Sin número"),
          el("div", { class:"mt-1 flex items-center gap-2" }, [
            item.epg_channel_id ? badge("✓ EPG Mapeado", "green") : badge("Sin mapeo EPG", "amber"),
            item.programs && item.programs.length > 0 ? badge(`${item.programs.length} programas`, "blue") : null,
          ]),
        ])
      ]);

      const mappingLabel = item.epg_channel_id
        ? `${item.epg_channel_name || epgChannelLookup.get(item.epg_channel_id) || item.epg_channel_id}`
        : "Sin mapeo";
      const sourceLabel = item.epg_source_id
        ? (epgSourceLookup.get(item.epg_source_id) || "Fuente desconocida")
        : "Sin fuente";

      const mappingListId = `epg-map-${item.live_id}`;
      let currentLabel = item.epg_channel_id
        ? formatEpgOption({
          xmltv_id: item.epg_channel_id,
          display_name: item.epg_channel_name || epgChannelLookup.get(item.epg_channel_id)
        })
        : "";
      const mappingInput = el("input", {
        class:"w-full bg-black/40 border border-white/10 rounded-lg px-2 py-1 text-xs text-zinc-100",
        list: mappingListId,
        placeholder:"Buscar canal XMLTV…",
        disabled: !epgSourceId || !epgChannels.length,
        value: currentLabel,
        onchange: async (e) => {
          const rawValue = e.target.value.trim();
          const xmltvId = resolveXmltvId(rawValue, epgChannelLookup, epgOptionMap);
          if (!epgSourceId) return;
          if (rawValue && !xmltvId) {
            status.textContent = "⚠️ Selecciona un canal XMLTV válido de la lista.";
            e.target.value = currentLabel;
            return;
          }
          status.textContent = "💾 Guardando mapeo EPG…";
          mappingStatus.textContent = "Actualizando base de datos…";
          e.target.disabled = true;
          try {
            const result = await api.live.patch(item.live_id, {
              epg_source_id: xmltvId ? epgSourceId : null,
              epg_channel_id: xmltvId,
            });

            // Actualizar estado local con la respuesta del servidor
            item.epg_source_id = result.epg_source_id;
            item.epg_channel_id = result.epg_channel_id;
            item.epg_channel_name = xmltvId ? (epgChannelLookup.get(xmltvId) || xmltvId) : null;
            currentLabel = xmltvId
              ? formatEpgOption({ xmltv_id: xmltvId, display_name: item.epg_channel_name })
              : "";
            e.target.value = currentLabel;

            status.textContent = xmltvId
              ? `✅ Mapeo guardado: ${item.name} → ${item.epg_channel_name}`
              : `✅ Mapeo eliminado: ${item.name}`;
            mappingStatus.textContent = "✓ Cambios guardados en la base de datos.";

            // Recargar después de un pequeño delay para asegurar consistencia
            setTimeout(() => {
              status.textContent = "🔄 Recargando grid para mostrar cambios…";
              reload();
            }, 800);
          } catch (err) {
            status.textContent = `❌ Error: ${err.message}`;
            mappingStatus.textContent = `❌ No se pudo guardar el mapeo.`;
            e.target.value = currentLabel;
          } finally {
            e.target.disabled = false;
          }
        }
      });
      const mappingList = el("datalist", { id: mappingListId }, [
        el("option", { value:"" }, "Sin asignar"),
        ...epgChannels.map(ch => el("option", {
          value: formatEpgOption(ch),
          label: ch.display_name || ch.xmltv_id
        }))
      ]);

      // Time offset input
      let currentOffset = minutesToOffset(item.epg_time_offset);
      const offsetInput = el("input", {
        class:"w-full bg-black/40 border border-white/10 rounded-lg px-2 py-1 text-xs text-zinc-100 font-mono text-center mt-2",
        placeholder:"+/-HHMM",
        maxlength: "5",
        value: currentOffset,
        title: "Ajuste de tiempo: +HHMM adelanta, -HHMM atrasa (ej: +0030 = +30 min, -0100 = -1 hora)",
        onkeypress: (e) => {
          // Solo permitir números, + y -
          if (!/[0-9+-]/.test(e.key)) {
            e.preventDefault();
          }
        },
        onchange: async (e) => {
          const rawValue = e.target.value.trim();
          const offsetMinutes = rawValue ? offsetToMinutes(rawValue) : null;

          if (rawValue && offsetMinutes === null) {
            status.textContent = "⚠️ Formato inválido. Usa +/-HHMM (ej: +0030, -0145)";
            e.target.value = currentOffset;
            return;
          }

          status.textContent = "💾 Guardando ajuste de tiempo EPG…";
          mappingStatus.textContent = "Actualizando offset…";
          e.target.disabled = true;

          try {
            const result = await api.live.patch(item.live_id, {
              epg_time_offset: offsetMinutes,
            });

            // Actualizar estado local
            item.epg_time_offset = result.epg_time_offset;
            currentOffset = minutesToOffset(result.epg_time_offset);
            e.target.value = currentOffset;

            const offsetText = offsetMinutes
              ? `${offsetMinutes > 0 ? '+' : ''}${offsetMinutes} minutos`
              : "sin ajuste";
            status.textContent = `✅ Offset guardado: ${item.name} → ${offsetText}`;
            mappingStatus.textContent = "✓ Ajuste aplicado.";

            // Recargar después de un pequeño delay
            setTimeout(() => {
              status.textContent = "🔄 Recargando grid con nuevo offset…";
              reload();
            }, 800);
          } catch (err) {
            status.textContent = `❌ Error: ${err.message}`;
            mappingStatus.textContent = `❌ No se pudo guardar el offset.`;
            e.target.value = currentOffset;
          } finally {
            e.target.disabled = false;
          }
        }
      });

      const mappingCell = el("div", { class:"px-3 py-3 border-r border-white/10" }, [
        el("div", { class:"text-xs text-zinc-400 mb-2" }, sourceLabel),
        el("div", { class:"text-xs text-zinc-200 mb-2 truncate" }, mappingLabel),
        mappingInput,
        mappingList,
        el("div", { class:"flex items-center gap-1 mt-2" }, [
          el("span", { class:"text-[10px] text-zinc-500" }, "Ajuste:"),
          offsetInput,
        ]),
      ]);

      const timelineBase = el("div", {
        class:"bg-black/20 border-r border-white/5 relative",
        style:`grid-column:3 / span ${slotCount}; min-height: 72px;`
      });

      row.appendChild(channelCell);
      row.appendChild(mappingCell);
      row.appendChild(timelineBase);

      if (!item.programs?.length) {
        timelineBase.appendChild(el("div", {
          class:"text-xs text-zinc-500 px-3 py-3"
        }, "Sin programación en la ventana."));
      } else {
        item.programs.forEach(pr => {
          const startMs = new Date(pr.start).getTime();
          const endMs = new Date(pr.end).getTime();
          const clampedStart = Math.max(startMs, windowStartMs);
          const clampedEnd = Math.min(endMs, windowEndMs);
          if (!Number.isFinite(clampedStart) || !Number.isFinite(clampedEnd) || clampedEnd <= clampedStart) return;

          // Calcular posición absoluta dentro del timeline
          const startPercent = ((clampedStart - windowStartMs) / (windowEndMs - windowStartMs)) * 100;
          const widthPercent = ((clampedEnd - clampedStart) / (windowEndMs - windowStartMs)) * 100;

          timelineBase.appendChild(
            el("div", {
              class:"absolute top-2 bottom-2 rounded-lg border border-white/10 bg-gradient-to-br from-blue-900/80 to-purple-900/80 backdrop-blur-sm px-2 py-1 text-xs text-zinc-100 overflow-hidden shadow-lg hover:shadow-xl hover:from-blue-800/90 hover:to-purple-800/90 transition-all cursor-pointer",
              style:`left:${startPercent}%; width:${widthPercent}%; min-width:${slotWidth * 0.9}px;`,
              title:`${pr.title}\n${formatTime(pr.start)} - ${formatTime(pr.end)}${pr.category ? '\n' + pr.category : ''}${pr.description ? '\n\n' + pr.description : ''}`
            }, [
              el("div", { class:"text-[10px] text-zinc-300 font-semibold mb-0.5" }, `${formatTime(pr.start)} - ${formatTime(pr.end)}`),
              el("div", { class:"text-xs text-white font-medium truncate" }, pr.title || "Untitled"),
              pr.category ? el("div", { class:"text-[10px] text-blue-300 truncate mt-0.5" }, pr.category) : null,
            ])
          );
        });
      }

      gridBody.appendChild(row);
    });

    scrollArea.appendChild(gridBody);
    gridWrap.innerHTML = "";
    gridWrap.appendChild(scrollArea);
  }

  async function ensureProviders() {
    if (providersLoaded) return;
    await loadProviders();
  }

  async function ensureEpgSources() {
    if (epgSourcesLoaded) return;
    await loadEpgSources();
  }

  const content = el("div", { class:"h-full min-h-0 flex flex-col gap-3" }, [
    el("div", { class:"flex flex-wrap items-center gap-3" }, [
      providerSelect,
      epgSourceSelect,
      hoursSelect,
      approvedToggle,
      reloadBtn,
      el("div", { class:"w-[240px]" }, searchInput),
    ]),
    status,
    mappingStatus,
    el("div", { class:"flex-1 min-h-0" }, gridWrap),
  ]);

  ensureProviders()
    .then(ensureEpgSources)
    .then(loadEpgChannels)
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
