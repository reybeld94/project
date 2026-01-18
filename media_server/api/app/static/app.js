import { el } from "./ui.js";
import { parseRoute, go } from "./router.js";
import { LivePage } from "./pages/live.js";
import { MoviesPage, MovieDetailPage } from "./pages/movies.js";
import { SeriesPage, SeriesDetailPage } from "./pages/series.js";
import { EpgPage } from "./pages/epg.js";
import { SettingsPage } from "./pages/settings.js";

const appState = {
  live: { q:"", limit:50, offset:0, providerId:null, approved:null },
  movies: { q:"", limit:60, offset:0 },
  series: { q:"", limit:60, offset:0 },
  epg: { q:"", limit:80, offset:0, hours:8, approvedOnly:true, providerId:null, sourceId:null },

  moviesCache: new Map(),
  seriesCache: new Map(),
};



function navItem(label, path, isActive) {
  return el("button", {
    class: `w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm transition border ${
      isActive ? "bg-white/10 border-white/10 text-zinc-100" : "bg-transparent border-transparent text-zinc-400 hover:text-zinc-100 hover:bg-white/5"
    }`,
    onclick: () => go(path),
    type: "button",
  }, [
    el("div", { class:"w-2 h-2 rounded-full " + (isActive ? "bg-blue-400" : "bg-zinc-700") }),
    el("div", { class:"font-medium" }, label),
  ]);
}

function render() {
  const r = parseRoute();

  const sidebar = el("div", { class:"w-[240px] min-w-[240px] h-full p-4 flex flex-col gap-4" }, [
    el("div", { class:"hz-glass rounded-2xl p-4" }, [
      el("div", { class:"text-base font-semibold" }, "Mini Media Server"),
      el("div", { class:"text-xs text-zinc-400 mt-1" }, "Plex-style control panel"),
    ]),
    el("div", { class:"hz-glass rounded-2xl p-2 space-y-1" }, [
      navItem("Live TV", "/live", r.root === "live"),
      navItem("Movies", "/movies", r.root === "movies"),
      navItem("Series", "/series", r.root === "series"),
      navItem("EPG", "/epg", r.root === "epg"),
      navItem("Settings", "/settings/providers", r.root === "settings"),
    ]),
    el("div", { class:"mt-auto text-xs text-zinc-600 px-2" }, "v0 UI shell"),
  ]);

  const main = el("div", { class:"flex-1 min-w-0 h-full overflow-hidden" });

  // Render page
  let page = null;
  if (r.root === "live") page = LivePage(appState);
  else if (r.root === "movies") page = r.sub ? MovieDetailPage(appState, r.sub) : MoviesPage(appState);
  else if (r.root === "series") page = r.sub ? SeriesDetailPage(appState, r.sub) : SeriesPage(appState);
  else if (r.root === "epg") page = EpgPage(appState);
  else if (r.root === "settings") page = SettingsPage(appState, r.sub);
  else page = LivePage(appState);

  main.appendChild(page);

  const root = document.getElementById("app");
  root.innerHTML = "";
  root.appendChild(
    el("div", { class:"h-screen w-screen flex overflow-hidden" }, [
      el("div", { class:"h-full" }, sidebar),
      el("div", { class:"h-full flex-1 min-w-0 border-l border-white/5" }, main),
    ])
  );
}

window.addEventListener("hashchange", render);
if (!location.hash) go("/live");
render();
