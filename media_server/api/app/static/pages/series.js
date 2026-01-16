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

function formatDuration(seconds) {
  if (!Number.isFinite(seconds) || seconds <= 0) return null;
  const mins = Math.round(seconds / 60);
  if (mins < 60) return `${mins}m`;
  const hours = Math.floor(mins / 60);
  const rem = mins % 60;
  return `${hours}h ${rem}m`;
}

function formatYear(dateString) {
  if (!dateString) return null;
  return dateString.split("-")[0] || null;
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

function openSeriesEditModal(item, onSaved) {
  let draftNorm = item.normalized_name || "";
  let draftCover = item.custom_cover_url || "";

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

  let scrollTop = 0;
  let restoreScrollTop = null;
  const restored = readGridState(SERIES_GRID_KEY);
  if (restored) {
    if (typeof restored.q === "string") { q = restored.q; appState.series.q = restored.q; }
    if (Number.isFinite(restored.offset)) { offset = restored.offset; appState.series.offset = restored.offset; }
    restoreScrollTop = Number.isFinite(restored.scrollTop) ? restored.scrollTop : 0;
  }


  const status = el("div", { class:"text-xs text-zinc-500" }, "Cargando…");
  const gridWrap = el("div", { class:"hz-glass rounded-2xl h-full min-h-0 flex flex-col overflow-hidden" }, [
    el("div", { class:"p-5 text-sm text-zinc-300" }, "Cargando series…")
  ]);

  const reload = () => load().catch(err => { status.textContent = `Error: ${err.message}`; });

  async function load() {

    status.textContent = "Cargando…";
    renderSkeletonGrid(gridWrap, Math.min(limit, 24));
    const data = await api.series.listAll({ q, limit, offset });
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
          writeGridState(SERIES_GRID_KEY, { q, offset, scrollTop });
          go(`/series/${item.id}`);
        },
      });


      const imgWrap = el("div", { class:"relative w-full", style:"padding-top:150%;" }, [
        el("div", { class:"absolute inset-0" }, coverBox(item.cover)),
        el("button", {
          class:"absolute top-2 right-2 w-9 h-9 rounded-xl bg-black/40 border border-white/10 text-zinc-100 hover:bg-black/60 transition",
          type:"button",
          title:"Edit",
          onclick: (e) => { e.stopPropagation(); openSeriesEditModal(item, reload); }
        }, "✎"),
      ]);

      const meta = el("div", { class:"p-3" }, [
        el("div", { class:"text-sm font-medium text-zinc-100" }, item.normalized_name || item.name || "—"),
        el("div", { class:"text-[11px] text-zinc-500 mt-1 truncate" }, item.provider_name || ""),
        el("div", { class:"mt-2 flex flex-wrap gap-2" }, [
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
            reload();
          }}),
          button("Next", { tone:"zinc", small:true, onClick: () => {
            if (!canNext) return;
            offset = offset + limit;
            appState.series.offset = offset;
            reload();
          }}),
        ])
      ])
    );
  }

  const filterBar = el("div", { class:"inline-flex gap-2" });

  const topRight = el("div", { class:"w-[560px] max-w-full flex flex-col items-end gap-2" }, [
    el("div", { class:"w-full flex items-center gap-2" }, [
      el("div", { class:"flex-1" }, input({
        placeholder:"Search series (name / normalized)…",
        value:q,
        onInput:(v)=>{ q=v; appState.series.q=v; }
      })),
      button("Search", { tone:"blue", onClick:()=>{ offset=0; appState.series.offset=0; reload(); } }),
    ]),
    el("div", { class:"w-full flex items-center justify-between" }, [ filterBar, status ])
  ]);

  const node = pageShell({
    title: "Series",
    subtitle: "Grid tipo Plex. Click abre la página detalle. El ✎ abre editor rápido.",
    right: topRight,
    content: el("div", { class:"h-full min-h-0 overflow-hidden" }, gridWrap),
  });

  reload();
  return node;
}

export function SeriesDetailPage(appState, seriesId) {
  let seasonsState = {
    loaded: false,
    loading: false,
    data: null,
    selectedIndex: 0,
  };

  const status = el("div", { class:"text-xs text-zinc-500" }, "Cargando…");
  const wrap = el("div", { class:"h-full min-h-0 overflow-auto hz-scroll" }, [
    el("div", { class:"hz-glass rounded-2xl p-5 border border-white/10" }, "Loading…")
  ]);

  function renderMetaChips(series) {
    const metaChips = [
      series.tmdb_vote_average ? `⭐ ${series.tmdb_vote_average.toFixed(1)}` : null,
      formatYear(series.tmdb_first_air_date || series.tmdb_release_date),
      series.tmdb_number_of_seasons ? `${series.tmdb_number_of_seasons} Seasons` : null,
      series.tmdb_runtime ? `${series.tmdb_runtime}min/ep` : null,
    ].filter(Boolean);

    return el("div", { class:"flex flex-wrap gap-2 text-xs text-zinc-300" },
      metaChips.map((chip) => badge(chip, "zinc"))
    );
  }

  function renderNetworks(series) {
    const networks = Array.isArray(series.tmdb_networks) ? series.tmdb_networks : [];
    if (!networks.length) return null;
    return el("div", { class:"mt-3" }, [
      el("div", { class:"text-xs text-zinc-400 mb-2" }, "Networks"),
      el("div", { class:"flex flex-wrap gap-2" },
        networks.slice(0, 6).map((net) => badge(typeof net === "string" ? net : net?.name || "—", "blue"))
      ),
    ]);
  }

  function renderCast(series) {
    const cast = Array.isArray(series.tmdb_cast) ? series.tmdb_cast.slice(0, 6) : [];
    if (!cast.length) return null;
    return el("div", { class:"mt-4" }, [
      el("div", { class:"text-xs text-zinc-400 mb-2" }, "Cast"),
      el("div", { class:"flex gap-3 overflow-x-auto pb-2" },
        cast.map((actor) => {
          const actorImg = actor?.profile_path ? tmdbImg(actor.profile_path, "w185") : null;
          return el("div", { class:"flex flex-col items-center min-w-[60px]" }, [
            el("div", {
              class:"w-12 h-12 rounded-full bg-zinc-700 bg-cover bg-center ring-2 ring-white/10",
              style: actorImg ? `background-image:url(${actorImg})` : ""
            }),
            el("div", { class:"text-xs text-zinc-300 mt-1 text-center truncate w-full" },
              actor?.name?.split(" ")[0] || "—"
            ),
          ]);
        })
      ),
    ]);
  }

  function renderEpisodesList(listEl, seasonsData, providerId, selectedIndex) {
    listEl.innerHTML = "";
    const season = seasonsData?.[selectedIndex];
    if (!season) {
      listEl.appendChild(el("div", { class:"text-sm text-zinc-500" }, "No hay episodios disponibles."));
      return;
    }

    const seasonLabel = `Season ${season.season_number ?? "—"}`;
    const seasonMeta = [
      season.episodes?.length ? `${season.episodes.length} Episodes` : null,
      formatYear(season.air_date),
    ].filter(Boolean).join(" • ");

    listEl.appendChild(
      el("div", { class:"text-sm text-zinc-300 mb-3" }, `${seasonLabel}${seasonMeta ? ` • ${seasonMeta}` : ""}`)
    );

    const episodesWrap = el("div", { class:"space-y-2" });
    for (const ep of (season.episodes || [])) {
      const epTitle = ep.title || `Episode ${ep.episode_num ?? "—"}`;
      const epDuration = formatDuration(ep.duration_secs);
      const epExt = ep.container_extension ? ep.container_extension.toUpperCase() : null;
      const thumbUrl = tmdbImg(ep.tmdb_still_path, "w300") || ep.cover || null;

      episodesWrap.appendChild(
        el("div", {
          class:"flex items-center gap-4 p-3 rounded-xl bg-black/20 border border-white/5 hover:border-white/15 transition-all group"
        }, [
          el("div", { class:"w-16 h-10 rounded-lg bg-zinc-800/70 border border-white/5 bg-cover bg-center shrink-0" },
            thumbUrl ? el("div", { class:"w-full h-full rounded-lg", style:`background-image:url(${thumbUrl}); background-size:cover; background-position:center;` }) : null
          ),
          el("div", {
            class:"w-10 h-10 rounded-lg bg-zinc-800 flex items-center justify-center text-zinc-400 font-mono text-sm shrink-0"
          }, ep.episode_num ?? "—"),
          el("div", { class:"flex-1 min-w-0" }, [
            el("div", { class:"text-sm text-zinc-100 truncate" }, epTitle),
            el("div", { class:"text-xs text-zinc-500 mt-0.5" },
              [epDuration, epExt ? `• ${epExt}` : null].filter(Boolean).join(" ")
            ),
          ]),
          el("div", { class:"flex gap-2 opacity-0 group-hover:opacity-100 transition-opacity" }, [
            button("▶", { tone:"blue", small:true, onClick: async () => {
              status.textContent = "Generando URL…";
              try {
                const r = await api.series.playEpisode(providerId, ep.episode_id, ep.container_extension || "mkv");
                const ok = await copyToClipboard(r.url);
                status.textContent = ok ? "✅ URL copiada" : "⚠️ No pude copiar";
                setTimeout(() => { status.textContent = ""; }, 1200);
              } catch (e) {
                status.textContent = `Error: ${e.message}`;
              }
            }}),
            button("📋", { tone:"zinc", small:true, onClick: async () => {
              status.textContent = "Copiando URL…";
              try {
                const r = await api.series.playEpisode(providerId, ep.episode_id, ep.container_extension || "mkv");
                const ok = await copyToClipboard(r.url);
                status.textContent = ok ? "✅ URL copiada" : "⚠️ No pude copiar";
                setTimeout(() => { status.textContent = ""; }, 1200);
              } catch (e) {
                status.textContent = `Error: ${e.message}`;
              }
            }}),
          ])
        ])
      );
    }

    if (!season.episodes?.length) {
      episodesWrap.appendChild(el("div", { class:"text-sm text-zinc-500" }, "No hay episodios en esta temporada."));
    }

    listEl.appendChild(episodesWrap);
  }

  function renderSeasonsPanel(series) {
    const panel = el("div", { class:"hz-glass rounded-2xl p-5 border border-white/10" }, [
      el("div", { class:"flex items-center justify-between" }, [
        el("div", { class:"text-sm text-zinc-300 font-medium" }, "Seasons"),
        el("div", { class:"text-xs text-zinc-500" }, "Selecciona una temporada"),
      ]),
    ]);

    const tabsWrap = el("div", { class:"flex gap-2 overflow-x-auto pb-2 mt-4 border-b border-white/10" });
    const episodesList = el("div", { class:"mt-4" }, [
      el("div", { class:"text-sm text-zinc-500" }, "Selecciona una temporada para ver episodios.")
    ]);

    panel.appendChild(tabsWrap);
    panel.appendChild(episodesList);

    async function loadSeasons() {
      if (seasonsState.loaded || seasonsState.loading) return;
      seasonsState.loading = true;
      episodesList.innerHTML = "";
      episodesList.appendChild(el("div", { class:"text-sm text-zinc-500" }, "Cargando temporadas…"));
      try {
        const data = await api.series.seasons(series.id);
        seasonsState.loaded = true;
        seasonsState.loading = false;
        seasonsState.data = data;

        const seasons = data.seasons || [];
        tabsWrap.innerHTML = "";

        if (!seasons.length) {
          episodesList.innerHTML = "";
          episodesList.appendChild(el("div", { class:"text-sm text-zinc-500" }, "No seasons/episodes disponibles desde el provider."));
          return;
        }

        seasonsState.selectedIndex = Math.min(seasonsState.selectedIndex, seasons.length - 1);

        seasons.forEach((sea, idx) => {
          const isActive = idx === seasonsState.selectedIndex;
          tabsWrap.appendChild(
            el("button", {
              class: `px-4 py-2 rounded-t-lg text-sm font-medium transition-all ${isActive ? "bg-gradient-to-r from-blue-600 to-blue-500 text-white shadow-lg shadow-blue-500/20" : "bg-zinc-800/50 text-zinc-400 hover:bg-zinc-700/50"}`,
              type: "button",
              role: "tab",
              "aria-selected": isActive ? "true" : "false",
              onclick: () => {
                seasonsState.selectedIndex = idx;
                renderTabs();
              }
            }, `S${sea.season_number ?? idx + 1}`)
          );
        });

        renderTabs();
      } catch (e) {
        seasonsState.loading = false;
        episodesList.innerHTML = "";
        episodesList.appendChild(el("div", { class:"text-sm text-zinc-500" }, `Error: ${e.message}`));
      }
    }

    function renderTabs() {
      const data = seasonsState.data;
      const seasons = data?.seasons || [];
      const providerId = data?.provider_id;
      [...tabsWrap.children].forEach((btn, idx) => {
        const isActive = idx === seasonsState.selectedIndex;
        btn.className = `px-4 py-2 rounded-t-lg text-sm font-medium transition-all ${isActive ? "bg-gradient-to-r from-blue-600 to-blue-500 text-white shadow-lg shadow-blue-500/20" : "bg-zinc-800/50 text-zinc-400 hover:bg-zinc-700/50"}`;
        btn.setAttribute("aria-selected", isActive ? "true" : "false");
      });
      renderEpisodesList(episodesList, seasons, providerId, seasonsState.selectedIndex);
    }

    panel.addEventListener("mouseenter", loadSeasons, { once: true });
    panel.addEventListener("focusin", loadSeasons, { once: true });
    loadSeasons();

    return panel;
  }

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
    const metaChips = renderMetaChips(s);

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
                  s.tmdb_first_air_date || s.tmdb_release_date
                    ? `First air: ${s.tmdb_first_air_date || s.tmdb_release_date}`
                    : "First air: —",
                  " • ",
                  `Provider: ${s.provider_name || "—"}`,
                ]),
              ]),
            ]),

            el("div", { class:"mt-2" }, metaChips),

            el("div", { class:"mt-3 flex flex-wrap gap-2" }, [
              ...(Array.isArray(s.tmdb_genres) ? s.tmdb_genres.slice(0, 12).map(g => badge(g, "zinc")) : []),
              s.tmdb_status ? badge(`TMDB: ${s.tmdb_status}`, s.tmdb_status === "synced" ? "green" : (s.tmdb_status === "failed" ? "red" : "zinc")) : null,
              s.tmdb_status_detail ? badge(s.tmdb_status_detail, "amber") : null,
            ].filter(Boolean)),

            renderNetworks(s),
            renderCast(s),

            el("div", { class:"mt-4 flex flex-wrap items-center gap-2" }, [
              button("▶ Play S1E1", { tone:"blue", onClick: async () => {
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
                ? button("🎬 Trailer", { tone:"zinc", onClick: () => window.open(`https://www.youtube.com/watch?v=${s.tmdb_trailer.key}`, "_blank") })
                : null,
              button("✏️ Edit", { tone:"zinc", onClick: () => openSeriesEditModal(s, load) }),
              button("📋 Copy URL", { tone:"zinc", onClick: async () => {
                const url = `${window.location.origin}/series/${s.id}`;
                const ok = await copyToClipboard(url);
                status.textContent = ok ? "✅ URL copiada" : "⚠️ No pude copiar";
                setTimeout(() => { status.textContent = ""; }, 1200);
              }}),
              button("← Back", { tone:"zinc", onClick: () => go("/series") }),
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

    const seasonsPanel = renderSeasonsPanel(s);

    wrap.appendChild(el("div", { class:"space-y-4" }, [
      hero,
      seasonsPanel,
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
