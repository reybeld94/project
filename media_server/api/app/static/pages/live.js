import { el, badge, button, input, modal, pageShell } from "../ui.js";
import { api } from "../api.js";

async function copyToClipboard(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    // fallback (por si el browser bloquea clipboard en http o sin permiso)
    const ta = document.createElement("textarea");
    ta.value = text;
    ta.style.position = "fixed";
    ta.style.left = "-9999px";
    ta.style.top = "0";
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    try {
      document.execCommand("copy");
      document.body.removeChild(ta);
      return true;
    } catch {
      document.body.removeChild(ta);
      return false;
    }
  }
}

function rowBadges(item) {
  const out = [];

  // ✅ NEW: Approved badge
  out.push(item.approved ? badge("APPROVED", "green") : badge("PENDING", "zinc"));

  // EPG badge
  if (item.epg_source_id && item.epg_channel_id) out.push(badge("EPG", "green"));
  else out.push(badge("NO EPG", "zinc"));

  if (item.alt1_stream_id) out.push(badge("ALT1", "blue"));
  if (item.alt2_stream_id) out.push(badge("ALT2", "blue"));
  if (item.alt3_stream_id) out.push(badge("ALT3", "blue"));

  out.push(item.custom_logo_url ? badge("LOGO", "amber") : badge("NO LOGO", "zinc"));
  return out;
}

function inlineEditNormalized({ value, onSave }) {
  let editing = false;
  let draft = value || "";

  const label = el("div", { class:"text-zinc-100 font-medium" }, value || "—");
  const editBtn = el("button", {
    class:"text-xs text-zinc-400 hover:text-zinc-200 underline underline-offset-2",
    onclick: (e) => {
      e.stopPropagation();
      if (editing) return;
      editing = true;
      render();
    }
  }, "edit");

  const box = el("div", { class:"flex items-center gap-2" }, [label, editBtn]);

  function render() {
    box.innerHTML = "";

    if (!editing) {
      label.textContent = value || "—";
      box.appendChild(label);
      box.appendChild(editBtn);
      return;
    }

    const inp = el("input", {
      class:"w-[320px] max-w-full bg-black/30 border border-white/10 rounded-xl px-3 py-2 text-sm text-zinc-100 outline-none focus:ring-2 focus:ring-white/10",
      value: draft,
      placeholder: "Normalized name…",
      oninput: (e) => { draft = e.target.value; },
      onkeydown: async (e) => {
        if (e.key === "Escape") {
          editing = false;
          draft = value || "";
          render();
        }
        if (e.key === "Enter") {
          e.preventDefault();
          await doSave();
        }
      },
      onblur: async () => {
        // blur = guardar también (si cambió)
        await doSave();
      }
    });

    const saving = el("div", { class:"text-xs text-zinc-500" }, "");
    box.appendChild(inp);
    box.appendChild(saving);

    inp.focus();
    inp.select();

    async function doSave() {
      const clean = (draft || "").trim();
      if (clean === (value || "")) {
        editing = false;
        render();
        return;
      }
      saving.textContent = "saving…";
      try {
        await onSave(clean);
        value = clean || null;
        editing = false;
        render();
      } catch (err) {
        saving.textContent = `error: ${err.message}`;
      }
    }
  }

  return box;
}

function inlineEditChannelNumber({ value, onSave }) {
  let editing = false;
  let draft = (value ?? "") === null ? "" : String(value ?? "");

  const label = el("div", { class:"text-zinc-200" }, (value ?? value === 0) ? String(value) : "—");
  const editBtn = el("button", {
    class:"text-xs text-zinc-400 hover:text-zinc-200 underline underline-offset-2",
    onclick: (e) => {
      e.stopPropagation();
      if (editing) return;
      editing = true;
      render();
    }
  }, "edit");

  const box = el("div", { class:"flex items-center gap-2" }, [label, editBtn]);

  function render() {
    box.innerHTML = "";

    if (!editing) {
      label.textContent = (value ?? value === 0) ? String(value) : "—";
      box.appendChild(label);
      box.appendChild(editBtn);
      return;
    }

    const inp = el("input", {
      type: "number",
      inputmode: "numeric",
      class:"w-[110px] bg-black/30 border border-white/10 rounded-xl px-3 py-2 text-sm text-zinc-100 outline-none focus:ring-2 focus:ring-white/10 text-right",
      value: draft,
      placeholder: "—",
      oninput: (e) => { draft = e.target.value; },
      onkeydown: async (e) => {
        if (e.key === "Escape") {
          editing = false;
          draft = (value ?? value === 0) ? String(value) : "";
          render();
        }
        if (e.key === "Enter") {
          e.preventDefault();
          await doSave();
        }
      },
      onblur: async () => { await doSave(); }
    });

    const saving = el("div", { class:"text-xs text-zinc-500" }, "");
    box.appendChild(inp);
    box.appendChild(saving);

    inp.focus();
    inp.select();

    async function doSave() {
      const raw = (draft || "").trim();

      // vacío => null
      const next = raw === "" ? null : parseInt(raw, 10);

      // valida entero si no está vacío
      if (raw !== "" && (!Number.isFinite(next) || String(next) !== raw)) {
        saving.textContent = "solo números enteros";
        return;
      }

      if ((next ?? null) === (value ?? null)) {
        editing = false;
        render();
        return;
      }

      saving.textContent = "saving…";
      try {
        await onSave(next);
        value = next;
        editing = false;
        render();
      } catch (err) {
        saving.textContent = `error: ${err.message}`;
      }
    }
  }

  return box;
}


function epgMappingPanel({ liveItem, onAfterSave }) {
  // state local
  let sources = [];
  let sourceId = liveItem.epg_source_id || "";
  let q = "";
  let searchT = null;
  let lastQ = null;
  let limit = 50;
  let offset = 0;
  let total = 0;
  let items = [];
  let picked = null; // { xmltv_id, display_name }

  let loading = false;

  const status = el("div", { class:"text-xs text-zinc-500" }, "");
  const resultsBox = el("div", { class:"hz-glass rounded-2xl p-3 max-h-[280px] overflow-auto hz-scroll" }, [
    el("div", { class:"text-sm text-zinc-400" }, "Cargando canales EPG…")
  ]);

  const sourceSelect = el("select", {
    class:"w-full rounded-xl bg-zinc-900/70 border border-white/10 px-3 py-2 text-sm text-zinc-100 focus:outline-none focus-visible:hz-focus",
    onchange: async (e) => {
      sourceId = e.target.value || "";
      offset = 0;
      picked = null;
      await loadChannels();
      renderPicked();
    }
  });

  const searchRow = el("div", { class:"flex items-center gap-2" }, [
  el("div", { class:"flex-1" }, input({
    placeholder: "Search EPG channel (display name / xmltv_id)…",
    value: q,
    onInput: (v) => {
      q = v;
      offset = 0;

      // debounce para no hacer 1 request por tecla
      if (searchT) clearTimeout(searchT);
      searchT = setTimeout(async () => {
        if (!sourceId) return;
        if (q === lastQ) return;
        lastQ = q;
        await loadChannels();
      }, 250);
    }
  })),
]);


  const pickedRow = el("div", { class:"flex items-center justify-between gap-3" });
  function renderPicked() {
    pickedRow.innerHTML = "";

    const left = el("div", { class:"min-w-0" }, [
      el("div", { class:"text-xs text-zinc-400" }, "Selected"),
      el("div", { class:"text-sm text-zinc-100 truncate mt-1" },
        picked ? `${picked.display_name} (${picked.xmltv_id})` : "—"
      )
    ]);

    const right = el("div", { class:"flex items-center gap-2 shrink-0" }, [
      button("Save mapping", {
        tone:"blue",
        small:true,
        onClick: async () => {
          if (!sourceId) { status.textContent = "Selecciona un EPG source primero."; return; }
          if (!picked?.xmltv_id) { status.textContent = "Selecciona un canal EPG para asignar."; return; }

          status.textContent = "Guardando mapping…";
          try {
            await api.live.patch(liveItem.id, {
              epg_source_id: sourceId,
              epg_channel_id: picked.xmltv_id
            });

            // refresca el item local (para que el modal muestre “Configured” sin cerrar)
            liveItem.epg_source_id = sourceId;
            liveItem.epg_channel_id = picked.xmltv_id;

            status.textContent = "✅ Guardado.";
            await onAfterSave?.();
          } catch (e) {
            status.textContent = `❌ Error: ${e.message}`;
          }
        }
      }),
      button("Clear", {
        tone:"red",
        small:true,
        onClick: async () => {
          status.textContent = "Limpiando mapping…";
          try {
            await api.live.patch(liveItem.id, { epg_source_id: null, epg_channel_id: null });
            liveItem.epg_source_id = null;
            liveItem.epg_channel_id = null;
            picked = null;
            renderPicked();
            status.textContent = "✅ EPG removido.";
            await onAfterSave?.();
          } catch (e) {
            status.textContent = `❌ Error: ${e.message}`;
          }
        }
      })
    ]);

    pickedRow.appendChild(left);
    pickedRow.appendChild(right);
  }

  function renderSources() {
    sourceSelect.innerHTML = "";

    const active = sources.filter(s => s.is_active);
    if (!active.length) {
      sourceSelect.appendChild(el("option", { value:"" }, "No active EPG sources"));
      return;
    }

    // si no hay sourceId, elige el primero activo
    if (!sourceId) sourceId = String(active[0].id);

    for (const s of active) {
      sourceSelect.appendChild(
        el("option", { value: String(s.id), selected: String(s.id) === String(sourceId) }, s.name)
      );
    }
  }

  function renderResults() {
    resultsBox.innerHTML = "";

    if (!sourceId) {
      resultsBox.appendChild(el("div", { class:"text-sm text-zinc-400" }, "Selecciona un EPG source."));
      return;
    }

    if (loading) {
      resultsBox.appendChild(el("div", { class:"text-sm text-zinc-400" }, "Cargando…"));
      return;
    }

    if (!items.length) {
      resultsBox.appendChild(el("div", { class:"text-sm text-zinc-400" }, "No results."));
      return;
    }

    const list = el("div", { class:"space-y-2" },
      items.map(ch => el("div", {
        class:"flex items-center justify-between gap-3 px-3 py-2 rounded-xl border border-white/10 bg-white/5 hover:bg-white/10",
      }, [
        el("div", { class:"min-w-0" }, [
          el("div", { class:"text-sm text-zinc-100 truncate" }, ch.display_name),
          el("div", { class:"text-xs text-zinc-500 truncate mt-0.5" }, ch.xmltv_id),
        ]),
        button("Pick", {
          tone:"zinc",
          small:true,
          onClick: () => {
            picked = { xmltv_id: ch.xmltv_id, display_name: ch.display_name };
            renderPicked();
          }
        })
      ]))
    );

    const canPrev = offset > 0;
    const canNext = (offset + limit) < total;

    const pager = el("div", { class:"flex items-center justify-between mt-3 pt-3 border-t border-white/10" }, [
      el("div", { class:"text-xs text-zinc-500" }, `Showing ${items.length} • total ${total} • offset ${offset}`),
      el("div", { class:"flex items-center gap-2" }, [
        button("Prev", {
          tone:"zinc", small:true,
          onClick: async () => { if(!canPrev) return; offset = Math.max(0, offset - limit); await loadChannels(); }
        }),
        button("Next", {
          tone:"zinc", small:true,
          onClick: async () => { if(!canNext) return; offset = offset + limit; await loadChannels(); }
        }),
      ])
    ]);

    resultsBox.appendChild(list);
    resultsBox.appendChild(pager);
  }

  async function loadSources() {
    try {
      const r = await api.epg.sources();
      sources = r?.items || [];
      renderSources();
    } catch (e) {
      status.textContent = `❌ No pude cargar EPG sources: ${e.message}`;
    }
  }

  async function loadChannels() {
    if (!sourceId) { items = []; total = 0; renderResults(); return; }

    loading = true;
    renderResults();

    try {
      const r = await api.epg.channels(sourceId, { q, limit, offset });
      items = r?.items || [];
      total = Number(r?.total || 0);

      // si el canal ya está asignado, intenta “auto-pick” visual para que el user lo vea
      if (liveItem.epg_channel_id && !picked) {
        const found = items.find(x => x.xmltv_id === liveItem.epg_channel_id);
        if (found) picked = { xmltv_id: found.xmltv_id, display_name: found.display_name };
      }
    } catch (e) {
      status.textContent = `❌ Error buscando canales: ${e.message}`;
      items = [];
      total = 0;
    } finally {
      loading = false;
      renderResults();
    }
  }

  // layout
  const wrap = el("div", { class:"space-y-3" }, [
    el("div", { class:"flex items-center justify-between" }, [
      el("div", { class:"text-sm font-semibold text-zinc-100" }, "EPG Mapping"),
      el("div", { class:"flex items-center gap-2" }, [
        liveItem.epg_source_id && liveItem.epg_channel_id ? badge("Configured", "green") : badge("Not set", "zinc")
      ])
    ]),
    el("div", { class:"grid grid-cols-2 gap-3" }, [
      el("div", {}, [
        el("div", { class:"text-xs text-zinc-400 mb-2" }, "EPG Source"),
        sourceSelect
      ]),
      el("div", {}, [
        el("div", { class:"text-xs text-zinc-400 mb-2" }, "Current xmltv_id"),
        el("div", { class:"hz-glass rounded-xl px-3 py-2 text-sm text-zinc-100 border border-white/10" },
          (liveItem.epg_channel_id || "—")
        )
      ])
    ]),
    searchRow,
    pickedRow,
    resultsBox,
    status
  ]);

  // init
  (async () => {
    renderPicked();
    await loadSources();
    await loadChannels();
    renderPicked();
  })();

  return wrap;
}


export function LivePage(appState) {
  let q = appState.live.q || "";
  let limit = appState.live.limit || 50;
  let offset = appState.live.offset || 0;
  let approved = (appState.live.approved === undefined) ? null : appState.live.approved;
  let searchT = null;
  let lastQ = q;
  let loadSeq = 0;


  const status = el("div", { class:"text-xs text-zinc-500" }, "Cargando…");

  // IMPORTANT: h-full + min-h-0 + flex para que el body de la tabla sea scrolleable
  const tableWrap = el("div", { class:"hz-glass rounded-2xl overflow-hidden h-full min-h-0 flex flex-col" }, [
    el("div", { class:"p-5 text-sm text-zinc-300" }, "Cargando canales…")
  ]);

  async function load() {
    const seq = ++loadSeq;
    status.textContent = "Loading…";

    const providers = await api.providers.list();
    if (seq !== loadSeq) return;

    const activeProviders = providers.filter(p => p.is_active);

    const data = await api.live.listAll({ q, limit, offset, activeOnly:true, approved });
    if (seq !== loadSeq) return;

    status.textContent =
      `Providers activos: ${activeProviders.length} • Mostrando ${data.items.length} de ${data.total}`;

    tableWrap.innerHTML = "";

    // Contenedor scrolleable REAL
    const scrollArea = el("div", { class:"flex-1 min-h-0 overflow-auto hz-scroll" });

    const table = el("table", { class:"min-w-full text-sm" }, [
      el("thead", { class:"text-zinc-400 sticky top-0 bg-zinc-950/90 backdrop-blur border-b border-white/10" }, [
        el("tr", {}, [
          el("th", { class:"text-left font-medium px-4 py-3 w-[76px]" }, "Logo"),
          el("th", { class:"text-left font-medium px-4 py-3 w-[90px]" }, "#"),
          el("th", { class:"text-left font-medium px-4 py-3" }, "Channel (Normalized)"),
          el("th", { class:"text-left font-medium px-4 py-3 w-[260px]" }, "Flags"),
          el("th", { class:"text-right font-medium px-4 py-3 w-[140px]" }, "Actions"),
        ])
      ]),
      el("tbody", {}, data.items.map(item => {
        const logoNode = item.custom_logo_url
          ? el("img", { src:item.custom_logo_url, class:"w-10 h-10 bg-zinc-200/90 rounded-lg object-contain bg-black/30 border border-white/10" })
          : el("div", { class:"w-10 h-10 rounded-lg bg-zinc-900/70 border border-white/10 flex items-center justify-center text-[10px] text-zinc-500" }, "NO");

        const normalized = item.normalized_name || null;
        const num = item.channel_number ?? null;

        const openDetails = () => {
          let pickedFile = null;
          const msg = el("div", { class:"text-xs text-zinc-500" }, "");

          let approvedDraft = !!item.approved;

const approvedToggle = el("label", { class:"inline-flex items-center gap-2 select-none" }, [
  el("input", {
    type:"checkbox",
    checked: approvedDraft,
    onchange: async (e) => {
      const next = !!e.target.checked;
      msg.textContent = "Saving approval…";
      try {
        await api.live.patch(item.id, { approved: next });
        item.approved = next;            // mantiene el item al día
        msg.textContent = next ? "✅ Approved." : "✅ Unapproved.";
        await load();                    // refresca badges / lista
      } catch (err) {
        e.target.checked = !!item.approved; // rollback
        msg.textContent = `❌ Error: ${err.message}`;
      }
    }
  }),
  el("span", { class:"text-sm text-zinc-200" }, "Approved"),
]);


          const logoPreview = el("div", { class:"flex items-center gap-3" }, [
            item.custom_logo_url
              ? el("img", { src:item.custom_logo_url, class:"w-16 h-16 rounded-xl bg-zinc-200/90 object-contain bg-black/30 border border-white/10" })
              : el("div", { class:"w-16 h-16 rounded-xl bg-zinc-900/70 border border-white/10 flex items-center justify-center text-xs text-zinc-500" }, "NO LOGO"),
            el("div", { class:"flex flex-col gap-2" }, [
              el("input", {
                type:"file",
                accept:"image/png,image/jpeg,image/webp,image/svg+xml",
                class:"text-xs text-zinc-300",
                onchange: (e) => { pickedFile = e.target.files?.[0] || null; msg.textContent = pickedFile ? `Selected: ${pickedFile.name}` : ""; }
              }),
              el("div", { class:"flex items-center gap-2" }, [
                button("Upload / Replace", {
                  tone:"blue",
                  small:true,
                  onClick: async () => {
                    if (!pickedFile) { msg.textContent = "Selecciona un archivo primero."; return; }
                    msg.textContent = "Subiendo logo…";
                    try {
                      const r = await api.live.uploadLogo(item.id, pickedFile);
                      msg.textContent = "OK. Recargando…";
                      await load(); // recarga tabla
                      // cerrar modal y reabrir para ver preview actualizado (simple)
                      document.querySelector(".fixed.inset-0.z-50")?.remove();
                    } catch (e) {
                      msg.textContent = `Error: ${e.message}`;
                    }
                  }
                }),
                button("Delete", {
                  tone:"zinc",
                  small:true,
                  onClick: async () => {
                    msg.textContent = "Borrando logo…";
                    try {
                      await api.live.deleteLogo(item.id);
                      msg.textContent = "OK. Recargando…";
                      await load();
                      document.querySelector(".fixed.inset-0.z-50")?.remove();
                    } catch (e) {
                      msg.textContent = `Error: ${e.message}`;
                    }
                  }
                }),
              ])
            ])
          ]);

          const normalizedEditor = inlineEditNormalized({
            value: normalized,
            onSave: async (newValue) => {
              await api.live.patch(item.id, { normalized_name: newValue });
              await load();
            }
          });

          const epgPanel = epgMappingPanel({
  liveItem: item,
  onAfterSave: async () => {
    await load(); // refresca la tabla + badges
  }
});

// ===== Alternate URLs (accordion) =====
let alt1 = "", alt2 = "", alt3 = "";
let loadedAlts = false;
const altMsg = el("div", { class:"text-xs text-zinc-500 mt-2" }, "");

const inpAlt1 = input({
  placeholder:"http://.../live/user/pass/12345.m3u8",
  value:"",
  onInput: (v) => { alt1 = v; }
});
const inpAlt2 = input({
  placeholder:"http://.../live/user/pass/12345.m3u8",
  value:"",
  onInput: (v) => { alt2 = v; }
});
const inpAlt3 = input({
  placeholder:"http://.../live/user/pass/12345.m3u8",
  value:"",
  onInput: (v) => { alt3 = v; }
});

async function loadAltUrlsOnce() {
  if (loadedAlts) return;
  loadedAlts = true;

  altMsg.textContent = "Loading alternates…";

  try {
    // Solo precarga si ya está configurado en BD (stream_id)
    if (item.alt1_stream_id) {
      const r1 = await api.live.play(item.id, { alt1:true, format:"m3u8" });
      alt1 = r1?.url || "";
      inpAlt1.value = alt1;
    }
    if (item.alt2_stream_id) {
      const r2 = await api.live.play(item.id, { alt2:true, format:"m3u8" });
      alt2 = r2?.url || "";
      inpAlt2.value = alt2;
    }
    if (item.alt3_stream_id) {
      const r3 = await api.live.play(item.id, { alt3:true, format:"m3u8" });
      alt3 = r3?.url || "";
      inpAlt3.value = alt3;
    }

    altMsg.textContent = "";
  } catch (e) {
    altMsg.textContent = `❌ Error loading alternates: ${e.message}`;
  }
}

const altAccordion = el("details", { class:"hz-glass rounded-2xl border border-white/10 overflow-hidden" }, [
  el("summary", {
    class:"px-4 py-3 cursor-pointer select-none flex items-center justify-between text-sm text-zinc-100 hover:bg-white/5"
  }, [
    el("div", { class:"font-medium" }, "Alternate URL"),
    el("div", { class:"text-xs text-zinc-500" }, "ALT1 / ALT2 / ALT3")
  ]),
  el("div", { class:"p-4 space-y-3" }, [
    el("div", { class:"text-xs text-zinc-500" },
      "Pega la URL completa del stream alterno. Vacío = borrar."
    ),

    el("div", {}, [ el("div", { class:"text-xs text-zinc-400 mb-2" }, "ALT1 URL"), inpAlt1 ]),
    el("div", {}, [ el("div", { class:"text-xs text-zinc-400 mb-2" }, "ALT2 URL"), inpAlt2 ]),
    el("div", {}, [ el("div", { class:"text-xs text-zinc-400 mb-2" }, "ALT3 URL"), inpAlt3 ]),

    el("div", { class:"flex items-center gap-2" }, [
      button("Save Alternates", {
        tone:"blue",
        small:true,
        onClick: async () => {
          altMsg.textContent = "Saving…";
          try {
            const r = await api.live.patch(item.id, {
              alt1_url: (alt1 || "").trim() || null,
              alt2_url: (alt2 || "").trim() || null,
              alt3_url: (alt3 || "").trim() || null,
            });

            // Mantén el item actualizado para badges + próximas precargas
            item.alt1_stream_id = r.alt1_stream_id || null;
            item.alt2_stream_id = r.alt2_stream_id || null;
            item.alt3_stream_id = r.alt3_stream_id || null;

            altMsg.textContent = "✅ Saved.";
            await load();
          } catch (e) {
            altMsg.textContent = `❌ Error: ${e.message}`;
          }
        }
      }),

      button("Clear", {
        tone:"zinc",
        small:true,
        onClick: () => {
          alt1 = ""; alt2 = ""; alt3 = "";
          inpAlt1.value = ""; inpAlt2.value = ""; inpAlt3.value = "";
          altMsg.textContent = "Cleared (not saved yet).";
        }
      }),
    ]),

    altMsg
  ])
]);

altAccordion.addEventListener("toggle", () => {
  if (altAccordion.open) loadAltUrlsOnce();
});


const channelNumberEditor = inlineEditChannelNumber({
  value: item.channel_number ?? null,
  onSave: async (newValue) => {
    await api.live.patch(item.id, { channel_number: newValue });
    item.channel_number = newValue;
  }
});




const body = el("div", { class:"space-y-5" }, [
  el("div", { class:"grid grid-cols-2 gap-3" }, [
    el("div", {}, [
      el("div", { class:"text-xs text-zinc-400" }, "Name"),
      el("div", { class:"text-sm text-zinc-100 mt-1" }, item.name || "—"),
    ]),
    el("div", {}, [
      el("div", { class:"text-xs text-zinc-400" }, "Normalized Name"),
      el("div", { class:"mt-1" }, normalizedEditor),
    ]),
    el("div", {}, [
  el("div", { class:"text-xs text-zinc-400" }, "Channel Number"),
  el("div", { class:"mt-1 flex justify-start" }, channelNumberEditor),
]),
el("div", {}, [
  el("div", { class:"text-xs text-zinc-400" }, "Approved"),
  el("div", { class:"mt-2" }, approvedToggle),
]),

    el("div", {}, [
      el("div", { class:"text-xs text-zinc-400" }, "EPG"),
      el("div", { class:"text-sm text-zinc-100 mt-1" },
        (item.epg_source_id && item.epg_channel_id) ? "Configured" : "Not set"
      ),
    ]),
  ]),

  el("div", {}, [
    el("div", { class:"text-xs text-zinc-400 mb-2" }, "Logo"),
    logoPreview,
    msg
  ]),

  el("div", {}, [
  el("div", { class:"text-xs text-zinc-400 mb-2" }, "Flags"),
  el("div", { class:"flex flex-wrap gap-2" }, rowBadges(item)),
]),

// ✅ NUEVO: acordeón alternates
altAccordion,

el("div", { class:"pt-2 border-t border-white/10" }, epgPanel),

]);


          modal({
  title: item.normalized_name || item.name || "Channel",
  body: el("div", { class:"max-h-[70vh] overflow-auto hz-scroll pr-1" }, body),

  footer: [
    button("Close", { tone:"zinc", onClick: () => document.querySelector(".fixed.inset-0.z-50")?.remove() })
  ]
});

        };

        // Inline editor en la tabla (sin abrir modal)
        const inlineEditor = inlineEditNormalized({
          value: normalized,
          onSave: async (newValue) => {
            await api.live.patch(item.id, { normalized_name: newValue });
            await load();
          }
        });

        return el("tr", {
          class:"border-b border-white/5 hover:bg-white/5 cursor-pointer",
          onclick: openDetails,
        }, [
          el("td", { class:"px-4 py-3" }, logoNode),
          el("td", { class:"px-4 py-3" }, el("div", { onclick:(e)=> e.stopPropagation() }, inlineEditChannelNumber({
  value: num,
  onSave: async (newValue) => {
    await api.live.patch(item.id, { channel_number: newValue });
    item.channel_number = newValue; // mantiene el item actualizado sin recargar toda la tabla
  }
}))),

          el("td", { class:"px-4 py-3" }, [
            el("div", { onclick:(e)=> e.stopPropagation() }, inlineEditor),
            el("div", { class:"text-xs text-zinc-500 mt-0.5" }, item.name || ""),
          ]),
          el("td", { class:"px-4 py-3" }, el("div", { class:"flex flex-wrap gap-2" }, rowBadges(item))),
          el("td", { class:"px-4 py-3 text-right" }, [
            el("div", { class:"inline-flex flex-col gap-2 items-end" }, [
  button("Details", {
    tone:"zinc",
    small:true,
    onClick:(e)=>{ e.stopPropagation(); openDetails(); }
  }),

  button("Play", {
    tone:"zinc",
    small:true,
    onClick: async (e) => {
      e.stopPropagation();
      try {
        const r = await api.live.play(item.id, "m3u8");
        const url = r?.url || "";
        if (!url) { status.textContent = "❌ No se pudo obtener el URL."; return; }

        const ok = await copyToClipboard(url);
        status.textContent = ok ? "✅ Stream URL copiado al portapapeles." : "⚠️ No pude copiar. Te lo muestro en consola.";
        if (!ok) console.log("Stream URL:", url);
      } catch (err) {
        status.textContent = `❌ Error: ${err.message}`;
      }
    }
  }),
])

          ]),
        ]);
      }))
    ]);

    scrollArea.appendChild(table);
    tableWrap.appendChild(scrollArea);

    // Paginación
    const canPrev = offset > 0;
    const canNext = (offset + limit) < data.total;

    tableWrap.appendChild(
      el("div", { class:"flex items-center justify-between px-4 py-3 border-t border-white/10" }, [
        el("div", { class:"text-xs text-zinc-500" }, `offset ${offset} • limit ${limit}`),
        el("div", { class:"flex items-center gap-2" }, [
          button("Prev", {
            tone:"zinc", small:true,
            onClick:()=>{ if(!canPrev) return; offset = Math.max(0, offset-limit); appState.live.offset=offset; load(); }
          }),
          button("Next", {
            tone:"zinc", small:true,
            onClick:()=>{ if(!canNext) return; offset = offset+limit; appState.live.offset=offset; load(); }
          }),
        ])
      ])
    );
  }

  const approvedSelect = el("select", {
    class:"rounded-lg bg-zinc-900/70 border border-white/10 px-3 py-2 text-sm text-zinc-100 focus:outline-none focus-visible:hz-focus",
    onchange: () => {
      const v = approvedSelect.value;
      approved = v === "approved" ? true : v === "pending" ? false : null;
      appState.live.approved = approved;
      offset = 0;
      appState.live.offset = 0;
      load();
    }
  }, [
    el("option", { value:"all", selected: approved === null }, "All"),
    el("option", { value:"approved", selected: approved === true }, "Approved"),
    el("option", { value:"pending", selected: approved === false }, "Pending"),
  ]);


  const topRight = el("div", { class:"w-[520px] max-w-full flex items-center gap-2" }, [
  approvedSelect,
  el("div", { class:"flex-1" }, input({
    placeholder:"Search channels (name / normalized)…",
    value: q,
    onInput: (v) => {
      q = v;
      appState.live.q = v;

      offset = 0;
      appState.live.offset = 0;

      if (searchT) clearTimeout(searchT);
      searchT = setTimeout(() => {
        if (q === lastQ) return;
        lastQ = q;
        load();
      }, 250);
    }
  })),
]);


  const node = pageShell({
    title: "Live TV",
    subtitle: "Canales agregados (providers activos). Edita el nombre normalizado y gestiona logos desde aquí.",
    right: el("div", { class:"flex flex-col items-end gap-1" }, [ topRight, status ]),
    content: el("div", { class:"h-full min-h-0 overflow-hidden" }, tableWrap),
  });

  load().catch(err => { status.textContent = `Error: ${err.message}`; });
  return node;
}
