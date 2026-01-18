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
        const autoSyncInput = el("input", {
          class: "w-20 rounded-lg bg-zinc-900/70 border border-white/10 px-2 py-1 text-xs text-zinc-100 placeholder:text-zinc-500 focus:outline-none focus-visible:hz-focus",
          type: "number",
          min: "0",
          step: "1",
          value: String(p.auto_sync_interval_minutes ?? 60),
        });
        const autoSyncStatus = el(
          "div",
          { class: "text-[11px] text-zinc-500" },
          (p.auto_sync_interval_minutes ?? 60) > 0
            ? `Auto Sync: ${p.auto_sync_interval_minutes ?? 60} min`
            : "Auto Sync: desactivado"
        );

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
            el("div", { class:"flex flex-col items-end gap-2" }, [
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
              ]),
              el("div", { class:"flex items-center gap-2" }, [
                el("div", { class:"text-xs text-zinc-500" }, "Auto Sync (min)"),
                autoSyncInput,
                button("Guardar", { tone:"zinc", small:true, onClick: async ()=>{
                  const raw = Number.parseInt(autoSyncInput.value, 10);
                  const interval = Number.isFinite(raw) ? Math.max(0, raw) : 0;
                  status.textContent = "Guardando Auto Sync…";
                  try {
                    const cfg = await api.providers.saveAutoSync(p.id, { interval_minutes: interval });
                    autoSyncInput.value = String(cfg.interval_minutes ?? interval);
                    autoSyncStatus.textContent = cfg.interval_minutes > 0
                      ? `Auto Sync: ${cfg.interval_minutes} min`
                      : "Auto Sync: desactivado";
                    status.textContent = "Auto Sync actualizado";
                  } catch (e) {
                    status.textContent = `Error: ${e.message}`;
                  }
                }}),
              ]),
              autoSyncStatus,
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
