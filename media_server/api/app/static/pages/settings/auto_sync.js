import { el, button } from "../../ui.js";
import { api } from "../../api.js";

export function AutoSyncTab() {
  const status = el("div", { class:"text-xs text-zinc-500" }, "—");

  const intervalInput = el("input", {
    class: "w-full rounded-lg bg-zinc-900/70 border border-white/10 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:outline-none focus-visible:hz-focus",
    type: "number",
    min: "0",
    step: "1",
    placeholder: "Ej: 60",
  });

  async function load() {
    status.textContent = "Cargando configuración…";
    const cfg = await api.settings.getProviderAutoSync();
    intervalInput.value = String(cfg.interval_minutes ?? 0);
    status.textContent = cfg.interval_minutes > 0
      ? `Auto Sync activo cada ${cfg.interval_minutes} min`
      : "Auto Sync desactivado";
  }

  async function save() {
    const raw = Number.parseInt(intervalInput.value, 10);
    const interval = Number.isFinite(raw) ? Math.max(0, raw) : 0;
    status.textContent = "Guardando…";
    try {
      const cfg = await api.settings.saveProviderAutoSync({ interval_minutes: interval });
      intervalInput.value = String(cfg.interval_minutes ?? 0);
      status.textContent = cfg.interval_minutes > 0
        ? `Guardado: cada ${cfg.interval_minutes} min`
        : "Guardado: Auto Sync desactivado";
    } catch (e) {
      status.textContent = `Error: ${e.message}`;
    }
  }

  const card = el("div", { class:"hz-glass rounded-2xl p-5" }, [
    el("div", { class:"text-sm text-zinc-200 font-medium mb-2" }, "Auto Sync con Provider"),
    el("div", { class:"text-xs text-zinc-400 mb-4" }, "Define cada cuánto tiempo se sincroniza automáticamente con el provider. Usa 0 para desactivar."),
    el("div", { class:"grid grid-cols-1 md:grid-cols-2 gap-3" }, [
      el("div", {}, [
        el("div", { class:"text-xs text-zinc-500 mb-1" }, "Intervalo (minutos)"),
        intervalInput,
      ]),
    ]),
    el("div", { class:"mt-4 flex items-center justify-between gap-3" }, [
      status,
      button("Guardar", { tone:"blue", onClick: save }),
    ]),
  ]);

  load().catch(e => status.textContent = `Error: ${e.message}`);

  return el("div", { class:"h-full min-h-0 flex flex-col gap-4" }, [
    card,
  ]);
}
