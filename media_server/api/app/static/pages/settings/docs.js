import { el, pageShell } from "../../ui.js";
import { api } from "../../api.js";

export function DocsTab(appState) {
  // This tab is rendered inside a fixed-height Settings panel.
  // Without a flex container + min-h-0, the endpoints list will grow forever
  // and never become internally scrollable.
  const box = el(
    "div",
    { class: "hz-glass rounded-2xl p-5 text-sm text-zinc-300 h-full min-h-0 flex flex-col" },
    "Cargando OpenAPI…"
  );

  async function load() {
    const spec = await api.openapi();
    const paths = spec.paths || {};
    const items = [];

    for (const [p, methods] of Object.entries(paths)) {
      for (const [m, meta] of Object.entries(methods)) {
        items.push({
          method: m.toUpperCase(),
          path: p,
          summary: meta.summary || meta.operationId || "",
        });
      }
    }

    items.sort((a,b)=> (a.path.localeCompare(b.path) || a.method.localeCompare(b.method)));

    box.innerHTML = "";
    box.appendChild(el("div", { class:"text-sm text-zinc-200 font-medium mb-2" }, "Default Server API"));
    box.appendChild(el("div", { class:"text-xs text-zinc-500 mb-4" }, "Auto desde /openapi.json. No modificable."));

    const table = el("div", { class:"flex-1 min-h-0 overflow-auto hz-scroll rounded-xl border border-white/10" }, [
      el("table", { class:"min-w-full text-sm" }, [
        el("thead", { class:"text-zinc-400" }, [
          el("tr", { class:"border-b border-white/10" }, [
            el("th", { class:"text-left font-medium px-3 py-2 w-[90px]" }, "Method"),
            el("th", { class:"text-left font-medium px-3 py-2 w-[360px]" }, "Path"),
            el("th", { class:"text-left font-medium px-3 py-2" }, "Summary"),
          ])
        ]),
        el("tbody", {}, items.map(x =>
          el("tr", { class:"border-b border-white/5" }, [
            el("td", { class:"px-3 py-2 text-zinc-200 font-medium" }, x.method),
            el("td", { class:"px-3 py-2 text-zinc-100" }, x.path),
            el("td", { class:"px-3 py-2 text-zinc-400" }, x.summary),
          ])
        ))
      ])
    ]);

    box.appendChild(table);
  }

  load().catch(e => { box.textContent = `Error: ${e.message}`; });

  return el("div", { class:"h-full min-h-0" }, box);
}
