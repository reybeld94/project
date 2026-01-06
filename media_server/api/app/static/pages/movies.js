import { el, badge, button, input, modal, pageShell, breadcrumb } from "../ui.js";
import { api } from "../api.js";
import { go } from "../router.js";

const MOVIES_GRID_KEY = "grid:movies";

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
  // fallback
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
          el("div", { class:"h-3 rounded bg-white/5 w-2/3" }),
        ])
      ])
    );
  }

  scrollArea.appendChild(grid);
  gridWrap.appendChild(scrollArea);
}

async function prefetchMovie(appState, id) {
  const key = String(id);
  if (appState.moviesCache?.has(key)) return;
  try {
    const data = await api.vod.get(id);
    appState.moviesCache?.set(key, data);
  } catch {}
}


function tmdbImg(path, size="w780") {
  if (!path) return null;
  return `https://image.tmdb.org/t/p/${size}${path}`;
}

function posterBox(url, { rounded="rounded-xl" } = {}) {
  if (!url) {
    return el("div", {
      class: `w-full h-full ${rounded} bg-zinc-900/70 border border-white/10 flex items-center justify-center text-xs text-zinc-500`
    }, "NO POSTER");
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
function movieMainTitle(x) {
  const hasTmdbTitle = x?.tmdb_status === "synced" && x?.tmdb_title;
  return hasTmdbTitle ? x.tmdb_title : (x.normalized_name || x.name || "—");
}


function openMovieEditModal(item, onSaved) {
  let draftNorm = item.normalized_name || "";
  let draftPoster = item.custom_poster_url || "";
  let draftApproved = !!item.approved;

  const msg = el("div", { class:"text-xs text-zinc-500" }, "");
  const posterUrl = item.custom_poster_url || tmdbImg(item.tmdb_poster_path, "w500") || item.poster;
  const poster = el("div", { class:"w-[180px] h-[270px]" }, posterBox(posterUrl, { rounded:"rounded-2xl" }));

  const normInput = el("input", {
    class:"w-full bg-black/30 border border-white/10 rounded-xl px-3 py-2 text-sm text-zinc-100 outline-none focus:ring-2 focus:ring-white/10",
    value: draftNorm,
    placeholder: "Normalized name…",
    oninput: (e) => { draftNorm = e.target.value; }
  });

  const posterInput = el("input", {
    class:"w-full bg-black/30 border border-white/10 rounded-xl px-3 py-2 text-sm text-zinc-100 outline-none focus:ring-2 focus:ring-white/10",
    value: draftPoster,
    placeholder: "Custom poster URL (optional)…",
    oninput: (e) => { draftPoster = e.target.value; }
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
    el("div", { class:"shrink-0" }, poster),
    el("div", { class:"flex-1 space-y-4" }, [
      el("div", {}, [
        el("div", { class:"text-xs text-zinc-400" }, "Title"),
        el("div", { class:"text-base text-zinc-100 font-medium mt-1" }, item.name || "—"),
        el("div", { class:"text-xs text-zinc-500 mt-1" }, `Provider: ${item.provider_name || "—"} • Stream ID: ${item.provider_stream_id}`),
      ]),
      el("div", { class:"grid grid-cols-1 gap-3" }, [
        el("div", {}, [ el("div", { class:"text-xs text-zinc-400 mb-1" }, "Normalized name"), normInput ]),
        el("div", {}, [
          el("div", { class:"text-xs text-zinc-400 mb-1" }, "Custom poster URL"),
          posterInput,
          el("div", { class:"text-[11px] text-zinc-500 mt-1" }, "Si lo dejas vacío, usa el poster del provider / TMDB."),
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
    title: movieMainTitle(item),
    body,
    footer: [
      button("Save", {
        tone:"blue",
        onClick: async () => {
          msg.textContent = "Guardando…";
          try {
            await api.vod.patch(item.id, {
              normalized_name: (draftNorm || "").trim() || null,
              custom_poster_url: (draftPoster || "").trim() || null,
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

export function MoviesPage(appState) {
  appState.movies = appState.movies || {};

  let q = appState.movies?.q || "";
  let limit = appState.movies?.limit || 60;
  let offset = appState.movies?.offset || 0;
  let approved = (appState.movies && "approved" in appState.movies) ? appState.movies.approved : null;
  let synced = (appState.movies && "synced" in appState.movies) ? appState.movies.synced : null;


  let scrollTop = 0;
  let restoreScrollTop = null;
  const restored = readGridState(MOVIES_GRID_KEY);
  if (restored) {
    if (typeof restored.q === "string") { q = restored.q; appState.movies.q = restored.q; }
    if (restored.synced === true || restored.synced === false || restored.synced === null) {
        synced = restored.synced; appState.movies.synced = restored.synced;
    }

    if (restored.approved === true || restored.approved === false || restored.approved === null) {
      approved = restored.approved; appState.movies.approved = restored.approved;
    }
    if (Number.isFinite(restored.offset)) { offset = restored.offset; appState.movies.offset = restored.offset; }
    restoreScrollTop = Number.isFinite(restored.scrollTop) ? restored.scrollTop : 0;
  }


  const status = el("div", { class:"text-xs text-zinc-500" }, "Cargando…");
  const gridWrap = el("div", { class:"hz-glass rounded-2xl h-full min-h-0 flex flex-col overflow-hidden" }, [
    el("div", { class:"p-5 text-sm text-zinc-300" }, "Cargando películas…")
  ]);

  async function load() {
    status.textContent = "Cargando…";
    renderSkeletonGrid(gridWrap, Math.min(limit, 24));

    const data = await api.vod.listAll({ q, limit, offset, approved, synced });

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
        tabindex: "0", // ✅ focus (TV-style)
        onmouseenter: () => prefetchMovie(appState, item.id), // ✅ prefetch desktop
        onfocus: () => prefetchMovie(appState, item.id),      // ✅ prefetch TV
        onclick: () => {
          writeGridState(MOVIES_GRID_KEY, { q, approved, synced, offset, scrollTop });
          go(`/movies/${item.id}`);
        },
      });


      const imgWrap = el("div", { class:"relative w-full", style:"padding-top:150%;" }, [
        el("div", { class:"absolute inset-0" }, posterBox(item.poster)),
        el("button", {
          class:"absolute top-2 right-2 w-9 h-9 rounded-xl bg-black/40 border border-white/10 text-zinc-100 hover:bg-black/60 transition",
          type:"button",
          title:"Edit",
          onclick: (e) => { e.stopPropagation(); openMovieEditModal(item, load); }
        }, "✎"),
      ]);

      const tmdbStatusBadge =
  item.tmdb_status === "synced" ? badge("SYNCED", "green") :
  item.tmdb_status === "missing" ? badge("NO TMDB", "amber") :
  item.tmdb_status === "failed" ? badge("TMDB FAIL", "red") :
  badge("NO TMDB", "zinc");




      const meta = el("div", { class:"p-3" }, [
        el("div", { class:"text-sm font-medium text-zinc-100" }, movieMainTitle(item)),
        el("div", { class:"text-[11px] text-zinc-500 mt-1 truncate" }, item.provider_name || ""),
        el("div", { class:"mt-2 flex flex-wrap gap-2" }, [
  approvalPill(!!item.approved),
  (item.poster ? badge("POSTER", "amber") : badge("NO POSTER", "zinc")),
  tmdbStatusBadge,
].filter(Boolean))

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
            appState.movies.offset = offset;
            load();
          }}),
          button("Next", { tone:"zinc", small:true, onClick: () => {
            if (!canNext) return;
            offset = offset + limit;
            appState.movies.offset = offset;
            load();
          }}),
        ])
      ])
    );
  }

  const filterBar = el("div", { class:"inline-flex gap-2" });

function renderFilters() {
  filterBar.innerHTML = "";

  filterBar.appendChild(button("All", {
    tone: approved === null ? "blue" : "zinc",
    small:true,
    onClick: () => {
      approved = null; appState.movies.approved = null;
      offset = 0; appState.movies.offset = 0;
      renderFilters();
      load();
    }
  }));

  filterBar.appendChild(button("Approved", {
    tone: approved === true ? "blue" : "zinc",
    small:true,
    onClick: () => {
      approved = true; appState.movies.approved = true;
      offset = 0; appState.movies.offset = 0;
      renderFilters();
      load();
    }
  }));

  filterBar.appendChild(button("Pending", {
    tone: approved === false ? "blue" : "zinc",
    small:true,
    onClick: () => {
      approved = false; appState.movies.approved = false;
      offset = 0; appState.movies.offset = 0;
      renderFilters();
      load();
    }
  }));

  filterBar.appendChild(button("Synced", {
    tone: synced === true ? "blue" : "zinc",
    small:true,
    onClick: () => {
      synced = (synced === true) ? null : true;
      appState.movies.synced = synced;
      offset = 0; appState.movies.offset = 0;
      renderFilters();
      load();
    }
  }));

  filterBar.appendChild(button("Needs Sync", {
    tone: synced === false ? "blue" : "zinc",
    small:true,
    onClick: () => {
      synced = (synced === false) ? null : false;
      appState.movies.synced = synced;
      offset = 0; appState.movies.offset = 0;
      renderFilters();
      load();
    }
  }));
}

renderFilters();


  const topRight = el("div", { class:"w-[560px] max-w-full flex flex-col items-end gap-2" }, [
    el("div", { class:"w-full flex items-center gap-2" }, [
      el("div", { class:"flex-1" }, input({
        placeholder:"Search movies (name / normalized)…",
        value:q,
        onInput:(v)=>{ q=v; appState.movies.q=v; }
      })),
      button("Search", { tone:"blue", onClick:()=>{ offset=0; appState.movies.offset=0; load(); } }),
    ]),
    el("div", { class:"w-full flex items-center justify-between" }, [ filterBar, status ])
  ]);

  const node = pageShell({
    title: "Movies",
    subtitle: "Grid tipo Plex. Click abre la página detalle. El ✎ abre editor rápido.",
    right: topRight,
    content: el("div", { class:"h-full min-h-0 overflow-hidden" }, gridWrap),
  });

  load().catch(err => { status.textContent = `Error: ${err.message}`; });
  return node;
}

export function MovieDetailPage(appState, movieId) {
  const status = el("div", { class:"text-xs text-zinc-500" }, "Cargando…");
  const wrap = el("div", { class:"h-full min-h-0 overflow-auto hz-scroll" }, [
    el("div", { class:"hz-glass rounded-2xl p-5 border border-white/10" }, "Loading…")
  ]);

  async function load() {
    status.textContent = "Cargando…";
    wrap.innerHTML = "";

        let m = null;
    try {
      const key = String(movieId);
      if (appState.moviesCache?.has(key)) {
        m = appState.moviesCache.get(key); // ✅ no re-fetch al volver
      } else {
        m = await api.vod.get(movieId);
        appState.moviesCache?.set(key, m);
      }
    } catch (e) {
      wrap.appendChild(el("div", { class:"hz-glass rounded-2xl p-5 border border-white/10" }, [
        el("div", { class:"text-zinc-100 font-medium" }, "No se pudo cargar esta película"),
        el("div", { class:"text-sm text-zinc-400 mt-1" }, e.message),
        el("div", { class:"mt-4 flex gap-2" }, [
          button("Back to Movies", { tone:"zinc", onClick: () => go("/movies") }),
        ])
      ]));
      status.textContent = "Error";
      return;
    }


    const title = movieMainTitle(m);
    const posterUrl = m.custom_poster_url || tmdbImg(m.tmdb_poster_path, "w500") || m.poster;
    const backdropUrl = tmdbImg(m.tmdb_backdrop_path, "w1280");

    const crumbs = el("div", {
  class: "inline-flex items-center rounded-xl bg-black/45 backdrop-blur px-3 py-2 border border-white/10"
}, [
  breadcrumb([
    { label:"Home", onClick: () => go("/live") },
    { label:"Movies", onClick: () => go("/movies") },
    { label: title }
  ])
]);


    const hero = el("div", {
  class:"hz-glass rounded-2xl border border-white/10 overflow-visible",
}, [
  // Backdrop “cortado” pero con mejor encuadre y overlay
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

    // ✅ Overlay real (nodo), no un "class:" suelto
    el("div", {
      class:"absolute inset-0 z-10 pointer-events-none bg-gradient-to-b from-black/70 via-black/10 to-black/90"
    }),

    el("div", { class:"relative z-20 p-5" }, crumbs),
  ]),


  // Contenido
  el("div", { class:"relative z-30 p-5 pt-0" }, [
    el("div", { class:"flex flex-col md:flex-row gap-5 -mt-16 md:-mt-20" }, [
      // ✅ ya no usamos -mt en el poster; lo hace el contenedor
      el("div", { class:"relative z-40 w-[180px] h-[270px] shrink-0" },
        posterBox(posterUrl, { rounded:"rounded-2xl" })
      ),

      el("div", { class:"flex-1 min-w-0" }, [
        el("div", { class:"flex items-start justify-between gap-3" }, [
          el("div", { class:"min-w-0" }, [
            el("div", { class:"text-2xl font-semibold text-zinc-100 truncate" }, title),
            el("div", { class:"text-sm text-zinc-400 mt-1" }, [
              m.tmdb_release_date ? `Release: ${m.tmdb_release_date}` : "Release: —",
              " • ",
              `Provider: ${m.provider_name || "—"}`,
            ]),
          ]),
           el("div", { class:"flex items-center gap-2 shrink-0" }, [
                approvalPill(!!m.approved),

                button("Play", { tone:"blue", onClick: async () => {
                  status.textContent = "Generando URL…";
                  try {
                    const r = await api.vod.play(m.id);
                    const ok = await copyToClipboard(r.url);
                    status.textContent = ok ? "✅ URL copiada" : "⚠️ No pude copiar (clipboard)";
                    setTimeout(() => { status.textContent = ""; }, 1200);
                  } catch (e) {
                    status.textContent = `Error: ${e.message}`;
                  }
                }}),

                // opcional: trailer si backend lo manda
                (m.tmdb_trailer?.site === "YouTube" && m.tmdb_trailer?.key)
                  ? button("Trailer", { tone:"zinc", onClick: () => window.open(`https://www.youtube.com/watch?v=${m.tmdb_trailer.key}`, "_blank") })
                  : null,

                button("Edit", { tone:"zinc", onClick: () => openMovieEditModal(m, load) }),
                button("Back", { tone:"zinc", onClick: () => go("/movies") }),
              ].filter(Boolean))

        ]),

                    el("div", { class:"mt-3 flex flex-wrap gap-2" }, [
              ...(Array.isArray(m.tmdb_genres) ? m.tmdb_genres.slice(0, 12).map(g => badge(g, "zinc")) : []),
              (m.tmdb_runtime != null) ? badge(`${m.tmdb_runtime} min`, "zinc") : null,
              m.tmdb_vote_average != null ? badge(`⭐ ${m.tmdb_vote_average}`, "amber") : null,
              m.tmdb_status ? badge(`TMDB: ${m.tmdb_status}`, m.tmdb_status === "synced" ? "green" : (m.tmdb_status === "failed" ? "red" : "zinc")) : null,
            ].filter(Boolean)),




                el("div", { class:"mt-4 hz-glass rounded-2xl p-4 border border-white/10" }, [
          el("div", { class:"text-xs text-zinc-400" }, "Overview"),
          el("div", { class:"text-sm text-zinc-200 mt-2 whitespace-pre-wrap" },
            m.tmdb_overview || "No overview disponible (aún)."
          ),
          m.tmdb_error
            ? el("div", { class:"text-xs text-rose-200 mt-3" }, `TMDB error: ${m.tmdb_error}`)
            : null,
        ]),

        (Array.isArray(m.tmdb_cast) && m.tmdb_cast.length)
          ? el("div", { class:"mt-4 hz-glass rounded-2xl p-4 border border-white/10" }, [
              el("div", { class:"text-xs text-zinc-400" }, "Cast"),
              el("div", { class:"mt-2 flex flex-wrap gap-2" },
                m.tmdb_cast.slice(0, 10).map(c =>
                  badge(c.character ? `${c.name} (${c.character})` : c.name, "zinc")
                )
              )
            ])
          : null,



      ])
    ])
  ])
]);


    wrap.appendChild(el("div", { class:"space-y-4" }, [
      hero,
      el("div", { class:"hz-glass rounded-2xl p-5 border border-white/10" }, [
        el("div", { class:"text-sm text-zinc-300 font-medium" }, "Tech / IDs"),
        el("div", { class:"mt-2 text-xs text-zinc-400 space-y-1" }, [
          el("div", {}, `Movie ID: ${m.id}`),
          el("div", {}, `Provider Stream ID: ${m.provider_stream_id}`),
          el("div", {}, `TMDB ID: ${m.tmdb_id ?? "—"}`),
        ])
      ])
    ]));

    status.textContent = "";
  }

  const node = pageShell({
    title: "Movie",
    subtitle: "Detalle + TMDB metadata + breadcrumb",
    right: el("div", { class:"flex items-center gap-3" }, [ status ]),
    content: wrap,
  });

  load().catch(e => { status.textContent = `Error: ${e.message}`; });
  return node;
}
