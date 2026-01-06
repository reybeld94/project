import { el, badge, button, input, modal, pageShell, breadcrumb } from "../ui.js";
import { api } from "../api.js";
import { go } from "../router.js";

const SERIES_GRID_KEY = "grid:series";

function readGridState(key) {
  try {
    const raw = sessionStorage.getItem(key);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function writeGridState(key, state) {
  try {
    sessionStorage.setItem(key, JSON.stringify(state));
  } catch {}
}

async function copyToClipboard(text) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {}
  try {
    const ta = document.createElement("textarea");
    ta.value = text;
    ta.style.position = "fixed";
    ta.style.opacity = "0";
    document.body.appendChild(ta);
    ta.select();
    document.execCommand("copy");
    ta.remove();
    return true;
  } catch {
    return false;
  }
}

function renderSkeletonGrid(gridWrap, count = 24) {
  gridWrap.innerHTML = "";
  const scrollArea = el("div", { class:"flex-1 min-h-0 overflow-auto hz-scroll" });
  const grid = el("div", { class:"p-4 grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 xl:grid-cols-6 gap-4" });

  for (let i = 0; i < count; i++) {
    grid.appendChild(
      el("div", { class:"hz-glass rounded-2xl overflow-hidden border border-white/10 animate-pulse" }, [
        el("div", { class:"w-full", style:"padding-top:150%; background:rgba(255,255,255,.04)" }),
        el("div", { class:"p-3 space-y-2" }, [
          el("div", { class:"h-4 rounded bg-white/5 w-3/4" }),
          el("div", { class:"h-3 rounded bg-white/5 w-1/2" }),
        ])
      ])
    );
  }

  scrollArea.appendChild(grid);
  gridWrap.appendChild(scrollArea);
}

async function prefetchSeries(appState, id) {
  const key = String(id);
  if (appState.seriesCache?.has(key)) return;
  try {
    const data = await api.series.get(id);
    appState.seriesCache?.set(key, data);
  } catch {}
}


function tmdbImg(path, size="w780") {
  if (!path) return null;
  return `https://image.tmdb.org/t/p/${size}${path}`;
}

function coverBox(url, { rounded="rounded-xl" } = {}) {
  if (!url) {
    return el("div", {
      class: `w-full h-full ${rounded} bg-zinc-900/70 border border-white/10 flex items-center justify-center text-xs text-zinc-500`
    }, "NO COVER");
  }
  return el("img", {
    src: url,
    loading: "lazy",
    class: `w-full h-full ${rounded} object-cover bg-black/30 border border-white/10`,
    onerror: (e) => { e.target.style.display = "none"; }
  });
}

function approvalPill(v) {
  return v ? badge("APPROVED", "green") : badge("PENDING", "zinc");
}

function openSeriesEditModal(item, onSaved) {
  let draftNorm = item.normalized_name || "";
  let draftCover = item.custom_cover_url || "";
  let draftApproved = !!item.approved;

  const msg = el("div", { class:"text-xs text-zinc-500" }, "");
  const coverUrl = item.custom_cover_url || tmdbImg(item.tmdb_poster_path, "w500") || item.cover;
  const cover = el("div", { class:"w-[180px] h-[270px]" }, coverBox(coverUrl, { rounded:"rounded-2xl" }));

  const normInput = el("input", {
    class:"w-full bg-black/30 border border-white/10 rounded-xl px-3 py-2 text-sm text-zinc-100 outline-none focus:ring-2 focus:ring-white/10",
    value: draftNorm,
    placeholder: "Normalized name…",
    oninput: (e) => { draftNorm = e.target.value; }
  });

  const coverInput = el("input", {
    class:"w-full bg-black/30 border border-white/10 rounded-xl px-3 py-2 text-sm text-zinc-100 outline-none focus:ring-2 focus:ring-white/10",
    value: draftCover,
    placeholder: "Custom cover URL (optional)…",
    oninput: (e) => { draftCover = e.target.value; }
  });

  const approvedToggle = el("button", {
    class: `px-3 py-2 rounded-xl text-sm transition border ${
      draftApproved ? "bg-emerald-500/15 border-emerald-400/20 text-emerald-200" : "bg-white/5 border-white/10 text-zinc-200"
    }`,
    onclick: () => {
      draftApproved = !draftApproved;
      approvedToggle.className = `px-3 py-2 rounded-xl text-sm transition border ${
        draftApproved ? "bg-emerald-500/15 border-emerald-400/20 text-emerald-200" : "bg-white/5 border-white/10 text-zinc-200"
      }`;
      approvedToggle.textContent = draftApproved ? "Approved" : "Pending";
    }
  }, draftApproved ? "Approved" : "Pending");

  const body = el("div", { class:"flex flex-col md:flex-row gap-5" }, [
    el("div", { class:"shrink-0" }, cover),
    el("div", { class:"flex-1 space-y-4" }, [
      el("div", {}, [
        el("div", { class:"text-xs text-zinc-400" }, "Title"),
        el("div", { class:"text-base text-zinc-100 font-medium mt-1" }, item.name || "—"),
        el("div", { class:"text-xs text-zinc-500 mt-1" }, `Provider: ${item.provider_name || "—"} • Series ID: ${item.provider_series_id}`),
      ]),
      el("div", { class:"grid grid-cols-1 gap-3" }, [
        el("div", {}, [ el("div", { class:"text-xs text-zinc-400 mb-1" }, "Normalized name"), normInput ]),
        el("div", {}, [
          el("div", { class:"text-xs text-zinc-400 mb-1" }, "Custom cover URL"),
          coverInput,
          el("div", { class:"text-[11px] text-zinc-500 mt-1" }, "Si lo dejas vacío, usa el cover del provider / TMDB."),
        ]),
      ]),
      el("div", { class:"flex items-center gap-2" }, [
        el("div", { class:"text-xs text-zinc-400" }, "Approval"),
        approvedToggle,
        approvalPill(!!item.approved),
      ]),
      msg,
    ])
  ]);

  const m = modal({
    title: item.normalized_name || item.tmdb_title || item.name || "Series",
    body,
    footer: [
      button("Save", {
        tone:"blue",
        onClick: async () => {
          msg.textContent = "Guardando…";
          try {
            await api.series.patch(item.id, {
              normalized_name: (draftNorm || "").trim() || null,
              custom_cover_url: (draftCover || "").trim() || null,
              approved: draftApproved,
            });
            await onSaved?.();
            m.close();
          } catch (e) {
            msg.textContent = `Error: ${e.message}`;
          }
        }
      }),
      button("Close", { tone:"zinc", onClick: () => m.close() }),
    ]
  });
}

export function SeriesPage(appState) {
  let q = appState.series?.q || "";
  let limit = appState.series?.limit || 60;
  let offset = appState.series?.offset || 0;
  let approved = (appState.series && "approved" in appState.series) ? appState.series.approved : null;

  let scrollTop = 0;
  let restoreScrollTop = null;
  const restored = readGridState(SERIES_GRID_KEY);
  if (restored) {
    if (typeof restored.q === "string") { q = restored.q; appState.series.q = restored.q; }
    if (restored.approved === true || restored.approved === false || restored.approved === null) {
      approved = restored.approved; appState.series.approved = restored.approved;
    }
    if (Number.isFinite(restored.offset)) { offset = restored.offset; appState.series.offset = restored.offset; }
    restoreScrollTop = Number.isFinite(restored.scrollTop) ? restored.scrollTop : 0;
  }


  const status = el("div", { class:"text-xs text-zinc-500" }, "Cargando…");
  const gridWrap = el("div", { class:"hz-glass rounded-2xl h-full min-h-0 flex flex-col overflow-hidden" }, [
    el("div", { class:"p-5 text-sm text-zinc-300" }, "Cargando series…")
  ]);

  async function load() {

    status.textContent = "Cargando…";
    renderSkeletonGrid(gridWrap, Math.min(limit, 24));
    const data = await api.series.listAll({ q, limit, offset, approved });
    status.textContent = `Mostrando ${data.items.length} de ${data.total}`;

    gridWrap.innerHTML = "";
    const scrollArea = el("div", { class:"flex-1 min-h-0 overflow-auto hz-scroll" });
    scrollArea.addEventListener("scroll", () => { scrollTop = scrollArea.scrollTop; });

    if (restoreScrollTop != null) {
      const v = restoreScrollTop;
      restoreScrollTop = null;
      requestAnimationFrame(() => { scrollArea.scrollTop = v; });
    }

    const grid = el("div", { class:"p-4 grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 xl:grid-cols-6 gap-4" });

    for (const item of data.items) {
            const card = el("div", {
        class:"hz-glass rounded-2xl overflow-hidden border border-white/10 hover:bg-white/5 transition cursor-pointer",
        tabindex: "0",
        onmouseenter: () => prefetchSeries(appState, item.id),
        onfocus: () => prefetchSeries(appState, item.id),
        onclick: () => {
          writeGridState(SERIES_GRID_KEY, { q, approved, offset, scrollTop });
          go(`/series/${item.id}`);
        },
      });


      const imgWrap = el("div", { class:"relative w-full", style:"padding-top:150%;" }, [
        el("div", { class:"absolute inset-0" }, coverBox(item.cover)),
        el("button", {
          class:"absolute top-2 right-2 w-9 h-9 rounded-xl bg-black/40 border border-white/10 text-zinc-100 hover:bg-black/60 transition",
          type:"button",
          title:"Edit",
          onclick: (e) => { e.stopPropagation(); openSeriesEditModal(item, load); }
        }, "✎"),
      ]);

      const meta = el("div", { class:"p-3" }, [
        el("div", { class:"text-sm font-medium text-zinc-100" }, item.normalized_name || item.name || "—"),
        el("div", { class:"text-[11px] text-zinc-500 mt-1 truncate" }, item.provider_name || ""),
        el("div", { class:"mt-2 flex flex-wrap gap-2" }, [
          approvalPill(!!item.approved),
          (item.cover ? badge("COVER", "amber") : badge("NO COVER", "zinc")),
        ])
      ]);

      card.appendChild(imgWrap);
      card.appendChild(meta);
      grid.appendChild(card);
    }

    scrollArea.appendChild(grid);
    gridWrap.appendChild(scrollArea);

    const canPrev = offset > 0;
    const canNext = (offset + limit) < data.total;

    gridWrap.appendChild(
      el("div", { class:"flex items-center justify-between px-4 py-3 border-t border-white/10" }, [
        el("div", { class:"text-xs text-zinc-500" }, `offset ${offset} • limit ${limit}`),
        el("div", { class:"flex items-center gap-2" }, [
          button("Prev", { tone:"zinc", small:true, onClick: () => {
            if (!canPrev) return;
            offset = Math.max(0, offset - limit);
            appState.series.offset = offset;
            load();
          }}),
          button("Next", { tone:"zinc", small:true, onClick: () => {
            if (!canNext) return;
            offset = offset + limit;
            appState.series.offset = offset;
            load();
          }}),
        ])
      ])
    );
  }

  const filterBar = el("div", { class:"inline-flex gap-2" }, [
    button("All", { tone: approved === null ? "blue" : "zinc", small:true, onClick: () => {
      approved = null; appState.series.approved = null; offset = 0; appState.series.offset = 0; load();
    }}),
    button("Approved", { tone: approved === true ? "blue" : "zinc", small:true, onClick: () => {
      approved = true; appState.series.approved = true; offset = 0; appState.series.offset = 0; load();
    }}),
    button("Pending", { tone: approved === false ? "blue" : "zinc", small:true, onClick: () => {
      approved = false; appState.series.approved = false; offset = 0; appState.series.offset = 0; load();
    }}),
  ]);

  const topRight = el("div", { class:"w-[560px] max-w-full flex flex-col items-end gap-2" }, [
    el("div", { class:"w-full flex items-center gap-2" }, [
      el("div", { class:"flex-1" }, input({
        placeholder:"Search series (name / normalized)…",
        value:q,
        onInput:(v)=>{ q=v; appState.series.q=v; }
      })),
      button("Search", { tone:"blue", onClick:()=>{ offset=0; appState.series.offset=0; load(); } }),
    ]),
    el("div", { class:"w-full flex items-center justify-between" }, [ filterBar, status ])
  ]);

  const node = pageShell({
    title: "Series",
    subtitle: "Grid tipo Plex. Click abre la página detalle. El ✎ abre editor rápido.",
    right: topRight,
    content: el("div", { class:"h-full min-h-0 overflow-hidden" }, gridWrap),
  });

  load().catch(err => { status.textContent = `Error: ${err.message}`; });
  return node;
}

export function SeriesDetailPage(appState, seriesId) {
  const status = el("div", { class:"text-xs text-zinc-500" }, "Cargando…");
  const wrap = el("div", { class:"h-full min-h-0 overflow-auto hz-scroll" }, [
    el("div", { class:"hz-glass rounded-2xl p-5 border border-white/10" }, "Loading…")
  ]);

  async function load() {
    status.textContent = "Cargando…";
    wrap.innerHTML = "";

    let s = null;
    try {
      const key = String(seriesId);
      if (appState.seriesCache?.has(key)) {
        s = appState.seriesCache.get(key);
      } else {
        s = await api.series.get(seriesId);
        appState.seriesCache?.set(key, s);
      }

    } catch (e) {
      wrap.appendChild(el("div", { class:"hz-glass rounded-2xl p-5 border border-white/10" }, [
        el("div", { class:"text-zinc-100 font-medium" }, "No se pudo cargar esta serie"),
        el("div", { class:"text-sm text-zinc-400 mt-1" }, e.message),
        el("div", { class:"mt-4 flex gap-2" }, [
          button("Back to Series", { tone:"zinc", onClick: () => go("/series") }),
        ])
      ]));
      status.textContent = "Error";
      return;
    }

    const title = s.tmdb_title || s.normalized_name || s.name || "Series";
    const coverUrl = s.custom_cover_url || tmdbImg(s.tmdb_poster_path, "w500") || s.cover;
    const backdropUrl = tmdbImg(s.tmdb_backdrop_path, "w1280");

    // ✅ Breadcrumb con “placa” legible (igual que Movies)
    const crumbs = el("div", {
      class: "inline-flex items-center rounded-xl bg-black/45 backdrop-blur px-3 py-2 border border-white/10"
    }, [
      breadcrumb([
        { label:"Home", onClick: () => go("/live") },
        { label:"Series", onClick: () => go("/series") },
        { label: title }
      ])
    ]);

    // ✅ Hero rediseñado (igual que Movies)
    const hero = el("div", {
      class:"hz-glass rounded-2xl border border-white/10 overflow-visible",
    }, [
      // Backdrop con encuadre + overlay real
      el("div", {
        class:"relative w-full rounded-2xl overflow-hidden z-0",
        style:"min-height:220px;"
      }, [
        backdropUrl
          ? el("div", {
              class:"absolute inset-0 z-0",
              style:`background:url(${backdropUrl}) center 25%/cover no-repeat;`
            })
          : el("div", {
              class:"absolute inset-0 z-0",
              style:"background:linear-gradient(to right, rgba(24,24,27,.8), rgba(0,0,0,.2))"
            }),

        el("div", {
          class:"absolute inset-0 z-10 pointer-events-none bg-gradient-to-b from-black/70 via-black/10 to-black/90"
        }),

        el("div", { class:"relative z-20 p-5" }, crumbs),
      ]),

      // Contenido (la subida la hace el contenedor, no el cover)
      el("div", { class:"relative z-30 p-5 pt-0" }, [
        el("div", { class:"flex flex-col md:flex-row gap-5 -mt-16 md:-mt-20" }, [
          el("div", { class:"relative z-40 w-[180px] h-[270px] shrink-0" },
            coverBox(coverUrl, { rounded:"rounded-2xl" })
          ),

          el("div", { class:"flex-1 min-w-0" }, [
            el("div", { class:"flex items-start justify-between gap-3" }, [
              el("div", { class:"min-w-0" }, [
                el("div", { class:"text-2xl font-semibold text-zinc-100 truncate" }, title),
                el("div", { class:"text-sm text-zinc-400 mt-1" }, [
                  s.tmdb_release_date ? `First air: ${s.tmdb_release_date}` : "First air: —",
                  " • ",
                  `Provider: ${s.provider_name || "—"}`,
                ]),
              ]),
                            el("div", { class:"flex items-center gap-2 shrink-0" }, [
                approvalPill(!!s.approved),

                button("Play (first ep)", { tone:"blue", onClick: async () => {
                  status.textContent = "Buscando episodio…";
                  try {
                    const seasons = await api.series.seasons(s.id);
                    const firstSeason = (seasons.seasons || []).slice().sort((a,b)=> (a.season_number??0)-(b.season_number??0))[0];
                    const firstEp = firstSeason?.episodes?.slice().sort((a,b)=> (a.episode_num??0)-(b.episode_num??0))[0];
                    if (!firstEp?.episode_id) {
                      status.textContent = "⚠️ No hay episodios (provider)";
                      return;
                    }
                    const r = await api.series.playEpisode(seasons.provider_id, firstEp.episode_id, firstEp.container_extension || "mkv");
                    const ok = await copyToClipboard(r.url);
                    status.textContent = ok ? "✅ URL copiada" : "⚠️ No pude copiar";
                    setTimeout(() => { status.textContent = ""; }, 1200);
                  } catch (e) {
                    status.textContent = `Error: ${e.message}`;
                  }
                }}),

                (s.tmdb_trailer?.site === "YouTube" && s.tmdb_trailer?.key)
                  ? button("Trailer", { tone:"zinc", onClick: () => window.open(`https://www.youtube.com/watch?v=${s.tmdb_trailer.key}`, "_blank") })
                  : null,

                button("Edit", { tone:"zinc", onClick: () => openSeriesEditModal(s, load) }),
                button("Back", { tone:"zinc", onClick: () => go("/series") }),
              ].filter(Boolean))

            ]),

            el("div", { class:"mt-3 flex flex-wrap gap-2" }, [
              ...(Array.isArray(s.tmdb_genres) ? s.tmdb_genres.slice(0, 12).map(g => badge(g, "zinc")) : []),
              s.tmdb_vote_average != null ? badge(`⭐ ${s.tmdb_vote_average}`, "amber") : null,
              s.tmdb_status ? badge(`TMDB: ${s.tmdb_status}`, s.tmdb_status === "synced" ? "green" : (s.tmdb_status === "failed" ? "red" : "zinc")) : null,
            ].filter(Boolean)),

            el("div", { class:"mt-4 hz-glass rounded-2xl p-4 border border-white/10" }, [
              el("div", { class:"text-xs text-zinc-400" }, "Overview"),
              el("div", { class:"text-sm text-zinc-200 mt-2 whitespace-pre-wrap" }, s.tmdb_overview || "No overview disponible (aún)."),
              s.tmdb_error ? el("div", { class:"text-xs text-rose-200 mt-3" }, `TMDB error: ${s.tmdb_error}`) : null,
            ])
          ])
        ])
      ])
    ]);

        const seasonsPanel = el("div", { class:"hz-glass rounded-2xl p-5 border border-white/10" }, [
      el("div", { class:"text-sm text-zinc-300 font-medium" }, "Seasons / Episodes"),
      el("div", { class:"text-xs text-zinc-500 mt-1" }, "Se carga desde el provider (si lo da)."),
      el("div", { class:"mt-3" }, [
        el("details", { class:"rounded-xl border border-white/10 bg-black/20 p-3" }, [
          el("summary", { class:"cursor-pointer text-sm text-zinc-200 select-none" }, "Show seasons"),
          el("div", { class:"mt-3 text-sm text-zinc-300" }, "Loading…")
        ])
      ])
    ]);

    // lazy-load al abrir
    const detailsEl = seasonsPanel.querySelector("details");
    const bodyEl = seasonsPanel.querySelector("details > div.mt-3");
    let loaded = false;

    detailsEl.addEventListener("toggle", async () => {
      if (!detailsEl.open || loaded) return;
      loaded = true;
      bodyEl.textContent = "Loading…";
      try {
        const data = await api.series.seasons(s.id);
        const seasons = data.seasons || [];
        if (!seasons.length) {
          bodyEl.textContent = "No seasons/episodes disponibles desde el provider.";
          return;
        }

        bodyEl.innerHTML = "";
        for (const sea of seasons) {
          const seasonBox = el("div", { class:"mt-3 rounded-xl border border-white/10 bg-black/15 p-3" }, [
            el("div", { class:"text-sm text-zinc-100 font-medium" }, `Season ${sea.season_number ?? "—"} • ${sea.episodes?.length ?? 0} eps`),
          ]);

          const epsWrap = el("div", { class:"mt-2 space-y-2" });
          for (const ep of (sea.episodes || [])) {
            epsWrap.appendChild(
              el("div", { class:"flex items-center justify-between gap-3 p-2 rounded-lg bg-black/20 border border-white/5" }, [
                el("div", { class:"min-w-0" }, [
                  el("div", { class:"text-sm text-zinc-100 truncate" }, ep.title || `Episode ${ep.episode_num ?? ""}`),
                  el("div", { class:"text-xs text-zinc-500" }, `E${ep.episode_num ?? "—"} • ${ep.container_extension || "mkv"}`),
                ]),
                button("Play", { tone:"blue", small:true, onClick: async () => {
                  status.textContent = "Generando URL…";
                  try {
                    const r = await api.series.playEpisode(data.provider_id, ep.episode_id, ep.container_extension || "mkv");
                    const ok = await copyToClipboard(r.url);
                    status.textContent = ok ? "✅ URL copiada" : "⚠️ No pude copiar";
                    setTimeout(() => { status.textContent = ""; }, 1200);
                  } catch (e) {
                    status.textContent = `Error: ${e.message}`;
                  }
                }})
              ])
            );
          }

          seasonBox.appendChild(epsWrap);
          bodyEl.appendChild(seasonBox);
        }
      } catch (e) {
        bodyEl.textContent = `Error: ${e.message}`;
      }
    });

    wrap.appendChild(el("div", { class:"space-y-4" }, [
      hero,
      seasonsPanel,
      el("div", { class:"hz-glass rounded-2xl p-5 border border-white/10" }, [
        el("div", { class:"text-sm text-zinc-300 font-medium" }, "Tech / IDs"),
        el("div", { class:"mt-2 text-xs text-zinc-400 space-y-1" }, [
          el("div", {}, `Series ID: ${s.id}`),
          el("div", {}, `Provider Series ID: ${s.provider_series_id}`),
          el("div", {}, `TMDB ID: ${s.tmdb_id ?? "—"}`),
        ])
      ])
    ]));


    status.textContent = "";
  }

  const node = pageShell({
    title: "Series",
    subtitle: "Detalle + TMDB metadata + breadcrumb",
    right: el("div", { class:"flex items-center gap-3" }, [ status ]),
    content: wrap,
  });

  load().catch(e => { status.textContent = `Error: ${e.message}`; });
  return node;
}
