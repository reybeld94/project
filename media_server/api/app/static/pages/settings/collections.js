import { el, badge, button, input } from "../../ui.js";
import { api } from "../../api.js";

const SOURCE_TYPES = [
  { value: "trending", label: "Trending" },
  { value: "list", label: "List" },
  { value: "discover", label: "Discover" },
  { value: "collection", label: "TMDB Collection" },
];

const KINDS = [
  { value: "movie", label: "Movie" },
  { value: "tv", label: "TV" },
];

const TIME_WINDOWS = [
  { value: "day", label: "Day" },
  { value: "week", label: "Week" },
];

const LIST_KEYS = {
  movie: ["popular", "top_rated", "now_playing", "upcoming", "latest"],
  tv: ["popular", "top_rated", "airing_today", "on_the_air", "latest"],
};

const SORT_BY = {
  movie: [
    "popularity.desc",
    "popularity.asc",
    "revenue.desc",
    "revenue.asc",
    "primary_release_date.desc",
    "primary_release_date.asc",
    "release_date.desc",
    "release_date.asc",
    "original_title.asc",
    "original_title.desc",
    "title.asc",
    "title.desc",
    "vote_average.desc",
    "vote_average.asc",
    "vote_count.desc",
    "vote_count.asc",
  ],
  tv: [
    "popularity.desc",
    "popularity.asc",
    "first_air_date.desc",
    "first_air_date.asc",
    "name.asc",
    "name.desc",
    "original_name.asc",
    "original_name.desc",
    "vote_average.desc",
    "vote_average.asc",
    "vote_count.desc",
    "vote_count.asc",
  ],
};

const TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w342";

function textArea({ value = "", placeholder = "", onInput = null } = {}) {
  return el("textarea", {
    class: "w-full min-h-[120px] rounded-lg bg-zinc-900/70 border border-white/10 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:outline-none focus-visible:hz-focus",
    placeholder,
    oninput: (e) => onInput?.(e.target.value),
  }, value);
}

function selectInput(options, { value = "", onChange = null } = {}) {
  const node = el("select", {
    class: "w-full rounded-lg bg-zinc-900/70 border border-white/10 px-3 py-2 text-sm text-zinc-100 focus:outline-none focus-visible:hz-focus",
    onchange: (e) => onChange?.(e.target.value),
  });
  options.forEach((opt) => {
    node.appendChild(el("option", { value: opt.value }, opt.label));
  });
  node.value = value;
  return node;
}

function numberInput({ value = "", onInput = null, min = null } = {}) {
  const props = {
    class: "w-full rounded-lg bg-zinc-900/70 border border-white/10 px-3 py-2 text-sm text-zinc-100 focus:outline-none focus-visible:hz-focus",
    type: "number",
    value: value ?? "",
    oninput: (e) => onInput?.(e.target.value),
  };
  if (min !== null) props.min = String(min);
  return el("input", props);
}

function smallToggle({ checked = false, onChange = null } = {}) {
  return el("input", {
    type: "checkbox",
    class: "scale-110 accent-blue-500",
    checked: checked ? true : null,
    onchange: (e) => onChange?.(e.target.checked),
  });
}

function prettyJson(value) {
  return JSON.stringify(value || {}, null, 2);
}

function parseGenres(raw) {
  if (Array.isArray(raw)) return raw.map((v) => Number(v)).filter(Boolean);
  if (typeof raw === "number") return [raw];
  if (typeof raw === "string") {
    return raw
      .split(",")
      .map((v) => parseInt(v.trim(), 10))
      .filter((v) => Number.isFinite(v));
  }
  return [];
}

function renderBadgeKind(kind) {
  if (!kind) return badge("—", "zinc");
  return badge(kind.toUpperCase(), "blue");
}

function buildEmptyDraft() {
  return {
    id: null,
    title: "",
    slug: "",
    kind: "movie",
    enabled: true,
    order_index: 0,
    source_type: "trending",
    source_id: "",
    time_window: "day",
    list_key: "popular",
    sort_by: "popularity.desc",
    filters_json: "{}",
    limit: "",
    cache_ttl_seconds: "",
    vote_count_gte: "",
    genre_ids: [],
  };
}

export function CollectionsTab(appState) {
  appState.collections = appState.collections || {};

  let items = [];
  let selectedId = null;
  let previousSelectedId = null;
  let draft = buildEmptyDraft();
  let loading = false;

  const genresByKind = { movie: null, tv: null };

  const listStatus = el("div", { class: "text-xs text-zinc-500" }, "—");
  const editorMsg = el("div", { class: "text-xs text-zinc-500" }, "");
  const previewMsg = el("div", { class: "text-xs text-zinc-500" }, "");
  const slugError = el("div", { class: "text-xs text-rose-200/90" }, "");
  const voteWarning = el("div", { class: "text-xs text-amber-200/90" }, "");
  const filtersError = el("div", { class: "text-xs text-rose-200/90" }, "");
  const limitWarning = el("div", { class: "text-xs text-amber-200/90" }, "");

  const searchInput = input({
    placeholder: "Search by title or slug…",
    value: appState.collections.q || "",
    onInput: (v) => {
      appState.collections.q = v;
      triggerSearch();
    },
  });

  const listWrap = el("div", { class: "space-y-2" });

  const titleInput = input({ placeholder: "Title", onInput: (v) => (draft.title = v) });
  const slugInput = input({ placeholder: "slug", onInput: (v) => (draft.slug = v) });
  const kindSelect = selectInput(KINDS, { value: draft.kind, onChange: onKindChange });
  const enabledToggle = smallToggle({
    checked: draft.enabled,
    onChange: (v) => (draft.enabled = v),
  });
  const orderInput = numberInput({ value: draft.order_index, onInput: (v) => (draft.order_index = v) });

  const sourceTypeSelect = selectInput(SOURCE_TYPES, {
    value: draft.source_type,
    onChange: (v) => {
      draft.source_type = v;
      updateSourceVisibility();
      updateLimitWarning();
      syncFiltersTextarea();
    },
  });
  const sourceIdInput = numberInput({ value: draft.source_id, onInput: (v) => (draft.source_id = v) });
  const timeWindowSelect = selectInput(TIME_WINDOWS, { value: draft.time_window, onChange: (v) => (draft.time_window = v) });
  const listKeySelect = selectInput([], { value: draft.list_key, onChange: (v) => (draft.list_key = v) });
  const sortBySelect = selectInput([], {
    value: draft.sort_by,
    onChange: (v) => {
      draft.sort_by = v;
      updateVoteWarning();
    },
  });

  const voteCountInput = numberInput({
    value: draft.vote_count_gte,
    min: 0,
    onInput: (v) => {
      draft.vote_count_gte = v;
      updateVoteWarning();
      syncFiltersTextarea();
    },
  });
  const limitInput = numberInput({
    value: draft.limit,
    min: 1,
    onInput: (v) => {
      draft.limit = v;
      updateLimitWarning();
      syncFiltersTextarea();
    },
  });
  const cacheTtlInput = numberInput({
    value: draft.cache_ttl_seconds,
    min: 0,
    onInput: (v) => (draft.cache_ttl_seconds = v),
  });

  const filtersTextarea = textArea({
    value: draft.filters_json,
    placeholder: "{\n  \"with_watch_providers\": \"8|9\"\n}",
    onInput: (v) => {
      draft.filters_json = v;
      filtersError.textContent = "";
    },
  });

  const genresWrap = el("div", { class: "grid grid-cols-2 gap-2" });

  const previewWrap = el("div", { class: "grid grid-cols-2 md:grid-cols-3 xl:grid-cols-4 gap-3" });

  const editorBadge = badge("New", "green");

  const newButton = button("+ New Collection", {
    tone: "blue",
    onClick: () => {
      previousSelectedId = selectedId;
      selectedId = null;
      applyDraft(buildEmptyDraft());
    },
  });

  const listColumn = el("div", { class: "hz-glass rounded-2xl p-4 flex flex-col gap-3 min-h-0" }, [
    el("div", { class: "flex items-center justify-between" }, [
      el("div", { class: "text-sm text-zinc-200 font-medium" }, "Collections"),
      newButton,
    ]),
    searchInput,
    listStatus,
    el("div", { class: "flex-1 min-h-0 overflow-auto hz-scroll pr-1" }, listWrap),
  ]);

  const basicsSection = section("Basics", [
    field("Title", titleInput),
    field("Slug", slugInput, slugError),
    el("div", { class: "grid grid-cols-1 md:grid-cols-3 gap-3" }, [
      field("Kind", kindSelect),
      field("Order", orderInput),
      field("Enabled", enabledToggle),
    ]),
  ]);

  const sourceSection = section("Source", [
    el("div", { class: "grid grid-cols-1 md:grid-cols-2 gap-3" }, [
      field("Source type", sourceTypeSelect),
      field("TMDB Collection ID", sourceIdInput),
      field("Time window", timeWindowSelect),
      field("List key", listKeySelect),
      field("Sort by", sortBySelect),
    ]),
  ]);

  const filtersSection = section("Filters", [
    el("div", { class: "grid grid-cols-1 md:grid-cols-3 gap-3" }, [
      field("Vote count ≥", voteCountInput),
      field("Limit", limitInput, limitWarning),
      field("Genres", genresWrap),
    ]),
    field("Filters JSON", filtersTextarea, filtersError),
    voteWarning,
  ]);

  const outputSection = section("Output", [
    el("div", { class: "grid grid-cols-1 md:grid-cols-2 gap-3" }, [
      field("Cache TTL (seconds)", cacheTtlInput),
    ]),
  ]);

  const previewSection = section("Preview", [
    el("div", { class: "flex items-center justify-between" }, [
      el("div", { class: "text-xs text-zinc-500" }, "Preview uses /collections/preview"),
      button("Preview", { tone: "zinc", onClick: runPreview }),
    ]),
    previewMsg,
    previewWrap,
  ]);

  const editorBody = el("div", { class: "flex-1 min-h-0 overflow-auto hz-scroll pr-2 space-y-4" }, [
    basicsSection,
    sourceSection,
    filtersSection,
    outputSection,
    previewSection,
  ]);

  const saveButton = button("Save", { tone: "blue", onClick: saveCollection });
  const cancelButton = button("Cancel", { tone: "zinc", onClick: cancelDraft });
  const duplicateButton = button("Duplicate", { tone: "zinc", onClick: duplicateDraft });

  const stickyActions = el("div", { class: "sticky bottom-0 pt-3 pb-2 bg-zinc-950/70 backdrop-blur border-t border-white/10" }, [
    el("div", { class: "flex items-center justify-between" }, [
      editorMsg,
      el("div", { class: "flex items-center gap-2" }, [saveButton, cancelButton, duplicateButton]),
    ]),
  ]);

  const editorColumn = el("div", { class: "hz-glass rounded-2xl p-5 flex flex-col min-h-0" }, [
    el("div", { class: "flex items-center justify-between" }, [
      el("div", { class: "text-sm text-zinc-200 font-medium" }, "Editor"),
      editorBadge,
    ]),
    editorBody,
    stickyActions,
  ]);

  function field(label, control, helper = null) {
    return el("div", { class: "space-y-1" }, [
      el("div", { class: "text-xs text-zinc-400" }, label),
      control,
      helper || null,
    ]);
  }

  function section(title, body) {
    return el("div", { class: "hz-glass rounded-2xl p-4 border border-white/10" }, [
      el("div", { class: "text-sm text-zinc-200 font-medium mb-3" }, title),
      ...body,
    ]);
  }

  function updateVoteWarning() {
    const needsVoteCount = draft.sort_by === "vote_average.desc";
    const parsed = parseFiltersJson();
    const existing = parsed.value?.["vote_count.gte"];
    if (needsVoteCount && !draft.vote_count_gte && !existing) {
      voteWarning.textContent = "sort_by=vote_average.desc requiere vote_count_gte.";
    } else {
      voteWarning.textContent = "";
    }
  }

  function updateLimitWarning() {
    if (draft.source_type === "discover" && draft.limit) {
      limitWarning.textContent = "Limit se ignora en Discover para evitar errores de TMDB.";
    } else {
      limitWarning.textContent = "";
    }
  }

  function updateSourceVisibility() {
    sourceIdInput.parentElement.style.display = draft.source_type === "collection" ? "" : "none";
    timeWindowSelect.parentElement.style.display = draft.source_type === "trending" ? "" : "none";
    listKeySelect.parentElement.style.display = draft.source_type === "list" ? "" : "none";
    sortBySelect.parentElement.style.display = draft.source_type === "discover" ? "" : "none";
  }

  function updateListKeyOptions() {
    const options = (LIST_KEYS[draft.kind] || []).map((value) => ({ value, label: value }));
    listKeySelect.innerHTML = "";
    options.forEach((opt) => listKeySelect.appendChild(el("option", { value: opt.value }, opt.label)));
    if (!options.find((opt) => opt.value === draft.list_key)) {
      draft.list_key = options[0]?.value || "";
    }
    listKeySelect.value = draft.list_key || "";
  }

  function updateSortByOptions() {
    const options = (SORT_BY[draft.kind] || []).map((value) => ({ value, label: value }));
    sortBySelect.innerHTML = "";
    options.forEach((opt) => sortBySelect.appendChild(el("option", { value: opt.value }, opt.label)));
    if (!options.find((opt) => opt.value === draft.sort_by)) {
      draft.sort_by = options[0]?.value || "";
    }
    sortBySelect.value = draft.sort_by || "";
    updateVoteWarning();
  }

  async function onKindChange(next) {
    draft.kind = next;
    draft.genre_ids = [];
    updateListKeyOptions();
    updateSortByOptions();
    await loadGenres(next);
    syncFiltersTextarea();
  }

  async function loadGenres(kind) {
    if (genresByKind[kind]) {
      renderGenres(genresByKind[kind]);
      return;
    }
    try {
      const data = await api.tmdb.genres({ kind });
      genresByKind[kind] = data?.genres || [];
      renderGenres(genresByKind[kind]);
    } catch (e) {
      genresByKind[kind] = [];
      renderGenres([]);
    }
  }

  function renderGenres(genres) {
    genresWrap.innerHTML = "";
    if (!genres.length) {
      genresWrap.appendChild(el("div", { class: "text-xs text-zinc-500 col-span-2" }, "No genres disponibles."));
      return;
    }
    genres.forEach((genre) => {
      const checkbox = smallToggle({
        checked: draft.genre_ids.includes(genre.id),
        onChange: (checked) => {
          if (checked) {
            if (!draft.genre_ids.includes(genre.id)) draft.genre_ids.push(genre.id);
          } else {
            draft.genre_ids = draft.genre_ids.filter((id) => id !== genre.id);
          }
          syncFiltersTextarea();
        },
      });
      const label = el("label", { class: "flex items-center gap-2 text-xs text-zinc-300" }, [
        checkbox,
        el("span", {}, genre.name),
      ]);
      genresWrap.appendChild(label);
    });
  }

  function parseFiltersJson() {
    try {
      const raw = (draft.filters_json || "").trim();
      if (!raw) return { value: {}, error: null };
      return { value: JSON.parse(raw), error: null };
    } catch (e) {
      return { value: null, error: "Filters JSON inválido." };
    }
  }

  function syncFiltersTextarea() {
    const parsed = parseFiltersJson();
    if (parsed.error || !parsed.value) return;
    const next = { ...parsed.value };
    delete next.limit;
    delete next["vote_count.gte"];
    delete next.with_genres;

    const voteCount = parseInt(draft.vote_count_gte || parsed.value["vote_count.gte"] || "", 10);
    if (Number.isFinite(voteCount)) next["vote_count.gte"] = voteCount;

    if (draft.genre_ids.length) {
      next.with_genres = draft.genre_ids.join(",");
    } else if (parsed.value.with_genres) {
      next.with_genres = parsed.value.with_genres;
    }

    const limitValue = parseInt(draft.limit || parsed.value.limit || "", 10);
    if (Number.isFinite(limitValue) && draft.source_type !== "discover") {
      next.limit = limitValue;
    }

    draft.filters_json = prettyJson(next);
    filtersTextarea.value = draft.filters_json;
  }

  function buildFiltersObject() {
    const parsed = parseFiltersJson();
    if (parsed.error || !parsed.value) {
      filtersError.textContent = parsed.error || "Filters JSON inválido.";
      return null;
    }

    filtersError.textContent = "";
    const next = { ...parsed.value };

    const voteCount = parseInt(draft.vote_count_gte || next["vote_count.gte"] || "", 10);
    if (Number.isFinite(voteCount)) next["vote_count.gte"] = voteCount;

    if (draft.genre_ids.length) next.with_genres = draft.genre_ids.join(",");

    const limitValue = parseInt(draft.limit || next.limit || "", 10);
    if (draft.source_type === "discover") {
      delete next.limit;
    } else if (Number.isFinite(limitValue)) {
      next.limit = limitValue;
    }

    return next;
  }

  function buildPayload() {
    slugError.textContent = "";
    editorMsg.textContent = "";

    if (!draft.title.trim() || !draft.slug.trim()) {
      editorMsg.textContent = "Title y slug son obligatorios.";
      return null;
    }

    const needsVoteCount = draft.sort_by === "vote_average.desc";
    const parsed = parseFiltersJson();
    const existingVote = parsed.value?.["vote_count.gte"];
    if (needsVoteCount && !draft.vote_count_gte && !existingVote) {
      voteWarning.textContent = "sort_by=vote_average.desc requiere vote_count_gte.";
      return null;
    }

    if (draft.source_type === "collection" && !draft.source_id) {
      editorMsg.textContent = "TMDB Collection ID es requerido.";
      return null;
    }

    const filtersObject = buildFiltersObject();
    if (filtersObject === null) return null;

    const filtersPayload = {
      kind: draft.kind,
    };

    if (draft.source_type === "trending") filtersPayload.time_window = draft.time_window;
    if (draft.source_type === "list") filtersPayload.list_key = draft.list_key;
    if (draft.source_type === "discover") filtersPayload.sort_by = draft.sort_by;
    if (Object.keys(filtersObject).length) filtersPayload.filters = filtersObject;

    const payload = {
      name: draft.title.trim(),
      slug: draft.slug.trim(),
      source_type: draft.source_type,
      source_id: draft.source_type === "collection" ? (draft.source_id ? Number(draft.source_id) : null) : null,
      filters: filtersPayload,
      cache_ttl_seconds: draft.cache_ttl_seconds ? Number(draft.cache_ttl_seconds) : null,
      enabled: !!draft.enabled,
      order_index: draft.order_index ? Number(draft.order_index) : 0,
    };

    return payload;
  }

  function applyDraft(next) {
    draft = { ...draft, ...next };

    titleInput.value = draft.title || "";
    slugInput.value = draft.slug || "";
    kindSelect.value = draft.kind || "movie";
    enabledToggle.checked = !!draft.enabled;
    orderInput.value = draft.order_index ?? 0;

    sourceTypeSelect.value = draft.source_type || "trending";
    sourceIdInput.value = draft.source_id || "";
    timeWindowSelect.value = draft.time_window || "day";
    listKeySelect.value = draft.list_key || "popular";
    sortBySelect.value = draft.sort_by || "popularity.desc";

    voteCountInput.value = draft.vote_count_gte || "";
    limitInput.value = draft.limit || "";
    cacheTtlInput.value = draft.cache_ttl_seconds || "";
    filtersTextarea.value = draft.filters_json || "{}";

    updateListKeyOptions();
    updateSortByOptions();
    updateSourceVisibility();
    updateVoteWarning();
    updateLimitWarning();
    editorBadge.textContent = selectedId ? "Editing" : "New";
    editorBadge.className = selectedId
      ? badge("Editing", "blue").className
      : badge("New", "green").className;
    loadGenres(draft.kind);
  }

  function selectCollection(item) {
    if (!item) return;
    selectedId = item.id;
    previousSelectedId = null;

    const filters = item.filters || {};
    const extraFilters = { ...(filters.filters || {}) };
    const limitValue = extraFilters.limit;
    const voteCountValue = extraFilters["vote_count.gte"];
    const genreValue = extraFilters.with_genres;
    delete extraFilters.limit;
    delete extraFilters["vote_count.gte"];
    delete extraFilters.with_genres;

    applyDraft({
      id: item.id,
      title: item.name || "",
      slug: item.slug || "",
      kind: filters.kind || "movie",
      enabled: !!item.enabled,
      order_index: item.order_index ?? 0,
      source_type: item.source_type || "trending",
      source_id: item.source_id || "",
      time_window: filters.time_window || "day",
      list_key: filters.list_key || "popular",
      sort_by: filters.sort_by || "popularity.desc",
      filters_json: prettyJson(extraFilters || {}),
      limit: limitValue ?? "",
      cache_ttl_seconds: item.cache_ttl_seconds ?? "",
      vote_count_gte: voteCountValue ?? "",
      genre_ids: parseGenres(genreValue),
    });
    renderList();
  }

  function duplicateDraft() {
    if (!draft.title && !draft.slug) return;
    previousSelectedId = selectedId;
    selectedId = null;
    applyDraft({
      id: null,
      title: `${draft.title || "Collection"} (Copy)`,
      slug: "",
    });
    renderList();
  }

  function cancelDraft() {
    if (selectedId && items.length) {
      const current = items.find((item) => item.id === selectedId);
      if (current) {
        selectCollection(current);
        editorMsg.textContent = "Changes discarded.";
        return;
      }
    }
    if (previousSelectedId) {
      const previous = items.find((item) => item.id === previousSelectedId);
      if (previous) {
        selectCollection(previous);
        editorMsg.textContent = "Changes discarded.";
        return;
      }
    }
    applyDraft(buildEmptyDraft());
    editorMsg.textContent = "Changes discarded.";
  }

  function renderList() {
    listWrap.innerHTML = "";
    if (!items.length) {
      listWrap.appendChild(el("div", { class: "text-sm text-zinc-400" }, "No collections yet."));
      return;
    }
    items.forEach((item) => {
      const kind = item.filters?.kind || "";
      const isActive = selectedId === item.id;
      const row = el("button", {
        class: `w-full text-left p-3 rounded-xl border transition ${
          isActive
            ? "border-blue-400/30 bg-blue-500/10"
            : "border-white/10 bg-white/5 hover:bg-white/10"
        }`,
        onclick: () => selectCollection(item),
        type: "button",
      }, [
        el("div", { class: "flex items-center justify-between gap-3" }, [
          el("div", { class: "min-w-0" }, [
            el("div", { class: "flex items-center gap-2" }, [
              el("div", { class: "font-medium text-zinc-100 truncate" }, item.name || "Untitled"),
              renderBadgeKind(kind),
            ]),
            el("div", { class: "text-xs text-zinc-500 truncate" }, item.slug || "—"),
          ]),
          el("div", { class: "flex items-center gap-3" }, [
            el("div", { class: "text-xs text-zinc-500" }, `#${item.order_index ?? 0}`),
            smallToggle({
              checked: !!item.enabled,
              onChange: async (checked) => {
                try {
                  await api.collections.update(item.id, { enabled: checked });
                  item.enabled = checked;
                  if (draft.id === item.id) {
                    draft.enabled = checked;
                    enabledToggle.checked = checked;
                  }
                } catch (e) {
                  listStatus.textContent = `Error: ${e.message}`;
                }
              },
            }),
          ]),
        ]),
      ]);
      listWrap.appendChild(row);
    });
  }

  function tmdbPoster(item) {
    if (!item) return null;
    const path = item.poster_path || item.poster || item.backdrop_path;
    if (!path) return null;
    if (String(path).startsWith("http")) return path;
    return `${TMDB_POSTER_BASE}${path}`;
  }

  function renderPreview(data) {
    previewWrap.innerHTML = "";
    const payload = data?.payload || {};
    const items = payload.results || payload.parts || payload.items || [];
    if (!items.length) {
      previewWrap.appendChild(el("div", { class: "text-sm text-zinc-400" }, "No results…"));
      return;
    }
    items.forEach((item) => {
      const poster = tmdbPoster(item);
      previewWrap.appendChild(
        el("div", { class: "rounded-xl border border-white/10 bg-white/5 overflow-hidden" }, [
          poster
            ? el("img", {
                src: poster,
                loading: "lazy",
                class: "w-full h-[210px] object-cover bg-black/30",
                onerror: (e) => {
                  e.target.style.display = "none";
                },
              })
            : el("div", { class: "w-full h-[210px] bg-zinc-900/70 flex items-center justify-center text-xs text-zinc-500" }, "NO POSTER"),
          el("div", { class: "p-2 text-xs text-zinc-200 truncate" }, item.title || item.name || item.original_title || "Untitled"),
        ])
      );
    });
  }

  async function runPreview() {
    previewMsg.textContent = "";
    previewWrap.innerHTML = "";
    const payload = buildPayload();
    if (!payload) return;
    previewMsg.textContent = "Loading preview…";
    try {
      const data = await api.collections.preview(payload);
      previewMsg.textContent = "";
      renderPreview(data);
    } catch (e) {
      previewMsg.textContent = `TMDB error: ${e.message}`;
    }
  }

  async function saveCollection() {
    if (loading) return;
    const payload = buildPayload();
    if (!payload) return;
    editorMsg.textContent = "Saving…";
    loading = true;
    try {
      let saved;
      if (draft.id) {
        saved = await api.collections.update(draft.id, payload);
      } else {
        saved = await api.collections.create(payload);
      }
      editorMsg.textContent = "Saved ✅";
      await load();
      selectCollection(saved);
    } catch (e) {
      if (e.message?.includes("Collection slug already exists")) {
        slugError.textContent = "Slug already exists.";
      } else {
        editorMsg.textContent = `Error: ${e.message}`;
      }
    } finally {
      loading = false;
    }
  }

  let searchDebounce = null;
  function triggerSearch() {
    if (searchDebounce) clearTimeout(searchDebounce);
    searchDebounce = setTimeout(() => load(), 250);
  }

  async function load() {
    listStatus.textContent = "Cargando collections…";
    try {
      const data = await api.collections.list({
        q: appState.collections.q || "",
        limit: 200,
        offset: 0,
      });
      items = data || [];
      listStatus.textContent = `Collections: ${items.length}`;
      renderList();
      if (!selectedId && items.length) selectCollection(items[0]);
    } catch (e) {
      listStatus.textContent = `Error: ${e.message}`;
    }
  }

  updateListKeyOptions();
  updateSortByOptions();
  updateSourceVisibility();
  updateVoteWarning();
  updateLimitWarning();
  loadGenres(draft.kind);

  load();

  return el("div", { class: "h-full min-h-0 grid grid-cols-1 xl:grid-cols-[320px_minmax(0,1fr)] gap-4" }, [
    listColumn,
    editorColumn,
  ]);
}
