import { el, button, input, badge } from "../../ui.js";
import { api } from "../../api.js";

export function EpgTab(appState) {
  const status = el("div", { class:"text-xs text-zinc-500" }, "—");
  const list = el("div", { class:"space-y-3" });

  let name="", xmltv_url="";

  async function load() {
    status.textContent = "Cargando fuentes…";
    const data = await api.epg.sources();
    const items = data.items || [];
    list.innerHTML = "";

    for (const s of items) {
      list.appendChild(
        el("div", { class:"hz-glass rounded-2xl p-4 flex items-center justify-between gap-4" }, [
          el("div", {}, [
            el("div", { class:"flex items-center gap-2" }, [
              el("div", { class:"font-medium text-zinc-100" }, s.name),
              s.is_active ? badge("ACTIVE","green") : badge("INACTIVE","red"),
            ]),
            el("div", { class:"text-xs text-zinc-500 mt-1 break-all" }, s.xmltv_url),
          ]),
          el("div", { class:"flex items-center gap-2" }, [
            button("Sync 36h", { tone:"blue", small:true, onClick: async ()=>{
              status.textContent = "Sync EPG…";
              try {
                const r = await api.epg.syncSource(s.id, 36);
                status.textContent = `OK: programs new=${r.programs?.new ?? "?"}, purged=${r.purged_programs ?? "?"}`;
              } catch(e) {
                status.textContent = `Error: ${e.message}`;
              }
            }}),
          ])
        ])
      );
    }

    status.textContent = `EPG Sources: ${items.length}`;
  }

  const form = el("div", { class:"hz-glass rounded-2xl p-5" }, [
    el("div", { class:"text-sm text-zinc-200 font-medium mb-3" }, "Add EPG Source (XMLTV)"),
    el("div", { class:"grid grid-cols-1 md:grid-cols-2 gap-3" }, [
      input({ placeholder:"Name", onInput:(v)=> name=v }),
      input({ placeholder:"XMLTV URL", onInput:(v)=> xmltv_url=v }),
    ]),
    el("div", { class:"mt-4 flex items-center justify-between gap-3" }, [
      el("div", { class:"text-xs text-zinc-500" }, "En el siguiente paso: programar intervalo (server-side) y activar/desactivar fuentes."),
      button("Create", { tone:"blue", onClick: async ()=>{
        status.textContent = "Creando…";
        try {
          await api.epg.createSource({ name, xmltv_url });
          await load();
        } catch(e) {
          status.textContent = `Error: ${e.message}`;
        }
      }})
    ])
  ]);

  load().catch(e => status.textContent = `Error: ${e.message}`);

  return el("div", { class:"h-full min-h-0 flex flex-col gap-4" }, [
    el("div", { class:"flex items-center justify-between" }, [
      el("div", { class:"text-xs text-zinc-500" }, "EPG: fuentes XMLTV + sync manual."),
      status
    ]),
    form,
    el("div", { class:"flex-1 min-h-0 overflow-auto hz-scroll pr-1" }, list),
  ]);
}
