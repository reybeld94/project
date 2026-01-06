import { el, button, input, badge } from "../../ui.js";
import { api } from "../../api.js";

export function ProvidersTab(appState) {
  const status = el("div", { class:"text-xs text-zinc-500" }, "—");
  const list = el("div", { class:"space-y-3" });

  let name = "", base_url = "", username = "", password = "";

  async function load() {
    status.textContent = "Cargando providers…";
    const items = await api.providers.list();
    list.innerHTML = "";

    if (!items.length) {
      list.appendChild(el("div", { class:"hz-glass rounded-2xl p-5 text-sm text-zinc-300" }, "No hay providers aún."));
    } else {
      for (const p of items) {
        list.appendChild(
          el("div", { class:"hz-glass rounded-2xl p-4 flex items-center justify-between gap-4" }, [
            el("div", {}, [
              el("div", { class:"flex items-center gap-2" }, [
                el("div", { class:"font-medium text-zinc-100" }, p.name),
                p.is_active ? badge("ACTIVE","green") : badge("INACTIVE","red"),
              ]),
              el("div", { class:"text-xs text-zinc-500 mt-1" }, p.base_url),
              el("div", { class:"text-xs text-zinc-500" }, `user: ${p.username}`),
            ]),
            el("div", { class:"flex items-center gap-2" }, [
              button("Test", { tone:"zinc", small:true, onClick: async ()=>{
                status.textContent = "Probando…";
                try {
                  const r = await api.providers.test(p.id);
                  status.textContent = `OK: ${r.count} live categories`;
                } catch(e) {
                  status.textContent = `Error: ${e.message}`;
                }
              }}),
              button("Sync", { tone:"blue", small:true, onClick: async ()=>{
                status.textContent = "Sincronizando…";
                try {
                  const r = await api.providers.syncAll(p.id);
                  status.textContent = `Sync OK (${Math.round(r.seconds)}s)`;
                } catch(e) {
                  status.textContent = `Error: ${e.message}`;
                }
              }}),
              button(p.is_active ? "Disable" : "Enable", {
                tone: p.is_active ? "zinc" : "blue",
                small: true,
                onClick: async () => {
                    status.textContent = "Actualizando provider…";
                    try {
                        await api.providers.patch(p.id, { is_active: !p.is_active });
                        await load();
                      } catch (e) {
                        status.textContent = `Error: ${e.message}`;
                      }
                    }
                }),
            ])
          ])
        );
      }
    }

    status.textContent = `Providers: ${items.length}`;
  }

  const form = el("div", { class:"hz-glass rounded-2xl p-5" }, [
    el("div", { class:"text-sm text-zinc-200 font-medium mb-3" }, "Add Provider"),
    el("div", { class:"grid grid-cols-1 md:grid-cols-2 gap-3" }, [
      input({ placeholder:"Name", onInput:(v)=> name=v }),
      input({ placeholder:"Base URL (https://...)", onInput:(v)=> base_url=v }),
      input({ placeholder:"Username", onInput:(v)=> username=v }),
      input({ placeholder:"Password", onInput:(v)=> password=v }),
    ]),
    el("div", { class:"mt-4 flex items-center justify-between gap-3" }, [
      el("div", { class:"text-xs text-zinc-500" }, "En el siguiente paso: activar/desactivar provider desde aquí + delete + edit."),
      button("Create", { tone:"blue", onClick: async ()=>{
        status.textContent = "Creando…";
        try {
          await api.providers.create({ name, base_url, username, password });
          name=base_url=username=password="";
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
      el("div", { class:"text-xs text-zinc-500" }, "Providers controlan qué contenido entra al agregador."),
      status
    ]),
    form,
    el("div", { class:"flex-1 min-h-0 overflow-auto hz-scroll pr-1" }, list),
  ]);
}
