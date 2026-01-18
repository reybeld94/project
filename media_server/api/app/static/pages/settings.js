import { el } from "../ui.js";
import { go } from "../router.js";
import { ProvidersTab } from "./settings/providers.js";
import { EpgTab } from "./settings/epg.js";
import { DocsTab } from "./settings/docs.js";
import { TmdbTab } from "./settings/tmdb.js";
import { CollectionsTab } from "./settings/collections.js";
import { AutoSyncTab } from "./settings/auto_sync.js";

const TABS = [
  { key:"providers", label:"Providers" },
  { key:"auto-sync", label:"Auto Sync" },
  { key:"epg", label:"EPG" },
  { key:"docs", label:"API" },
  { key:"tmdb", label:"TMDB" },
  { key:"collections", label:"Collections" },
];

export function SettingsPage(appState, subRoute) {
  const current = subRoute || "providers";

  const tabBar = el("div", { class:"hz-glass rounded-2xl p-2 inline-flex gap-2" },
    TABS.map(t => el("button", {
      class: `px-3 py-2 rounded-xl text-sm transition border ${
        t.key === current
          ? "bg-white/10 border-white/10 text-zinc-100"
          : "bg-transparent border-transparent text-zinc-400 hover:text-zinc-100 hover:bg-white/5"
      }`,
      onclick: () => go(`/settings/${t.key}`)
    }, t.label))
  );

  let content = null;
  if (current === "providers") content = ProvidersTab(appState);
  else if (current === "auto-sync") content = AutoSyncTab(appState);
  else if (current === "epg") content = EpgTab(appState);
  else if (current === "docs") content = DocsTab(appState);
  else if (current === "tmdb") content = TmdbTab(appState);
  else if (current === "collections") content = CollectionsTab(appState);
  else content = el("div", { class:"text-sm text-zinc-300" }, "Unknown tab");

  return el("div", { class:"h-full flex flex-col" }, [
    el("div", { class:"px-6 pt-6" }, [
      el("h1", { class:"text-2xl font-semibold" }, "Settings"),
      el("div", { class:"text-sm text-zinc-400 mt-1" }, "Providers, EPG, documentación de API, y estado TMDB."),
      el("div", { class:"mt-4" }, tabBar),
    ]),
    el("div", { class:"px-6 pb-6 pt-5 flex-1 min-h-0" }, content),
  ]);
}
