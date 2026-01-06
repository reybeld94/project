
export function el(tag, props = {}, children = []) {
  const node = document.createElement(tag);

  for (const [k, v] of Object.entries(props || {})) {
    if (k === "class") node.className = v;
    else if (k === "style") node.setAttribute("style", v);
    else if (k.startsWith("on") && typeof v === "function") node.addEventListener(k.slice(2).toLowerCase(), v);
    else if (v === true) node.setAttribute(k, "");
    else if (v !== false && v != null) node.setAttribute(k, String(v));
  }

  const arr = Array.isArray(children) ? children : [children];
  for (const c of arr) {
    if (c == null) continue;
    node.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
  }
  return node;
}

export function badge(text, tone="zinc") {
  const tones = {
    zinc: "bg-zinc-800/70 text-zinc-100 border-white/10",
    blue: "bg-blue-500/15 text-blue-200 border-blue-400/20",
    green:"bg-emerald-500/15 text-emerald-200 border-emerald-400/20",
    amber:"bg-amber-500/15 text-amber-200 border-amber-400/20",
    red:  "bg-rose-500/15 text-rose-200 border-rose-400/20",
  };
  return el("span", {
    class: `inline-flex items-center px-2 py-0.5 rounded-md text-[11px] border ${tones[tone] || tones.zinc}`
  }, text);
}

export function button(text, { tone="blue", onClick=null, small=false }={}) {
  const tones = {
    blue: "bg-blue-600 hover:bg-blue-500 text-white",
    zinc: "bg-zinc-800 hover:bg-zinc-700 text-zinc-100",
    red:  "bg-rose-600 hover:bg-rose-500 text-white",
  };
  return el("button", {
    class: `${small ? "px-2.5 py-1 text-xs" : "px-3 py-2 text-sm"} rounded-lg transition ${tones[tone]} focus:outline-none focus-visible:hz-focus`,
    onclick: onClick,
    type: "button",
  }, text);
}

export function input({ placeholder="", value="", onInput=null }={}) {
  return el("input", {
    class: "w-full rounded-lg bg-zinc-900/70 border border-white/10 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:outline-none focus-visible:hz-focus",
    placeholder,
    value,
    oninput: (e) => onInput?.(e.target.value),
  });
}

export function modal({ title="Details", body=null, footer=null, onClose=null }={}) {
  const overlay = el("div", {
    class: "fixed inset-0 z-50 bg-black/60 flex items-center justify-center p-4",
    onclick: (e) => { if (e.target === overlay) close(); }
  });

  function close() {
    overlay.remove();
    onClose?.();
    document.removeEventListener("keydown", onKey);
  }
  function onKey(e) {
    if (e.key === "Escape") close();
  }
  document.addEventListener("keydown", onKey);

  const card = el("div", { class: "hz-glass w-full max-w-3xl rounded-2xl shadow-2xl overflow-hidden" }, [
    el("div", { class:"flex items-center justify-between px-5 py-4 border-b border-white/10" }, [
      el("div", { class:"font-semibold text-zinc-100" }, title),
      el("button", { class:"text-zinc-400 hover:text-zinc-100", onclick: close, type:"button" }, "✕")
    ]),
    el("div", { class:"p-5" }, body || ""),
    footer ? el("div", { class:"px-5 py-4 border-t border-white/10 flex items-center justify-end gap-2" }, footer) : null
  ]);

  overlay.appendChild(card);
  document.body.appendChild(overlay);
  return { close, overlay };
}

export function pageShell({ title, subtitle=null, right=null, content=null }) {
  return el("div", { class:"h-full flex flex-col" }, [
    el("div", { class:"px-6 pt-6" }, [
      el("div", { class:"flex items-start justify-between gap-4" }, [
        el("div", {}, [
          el("h1", { class:"text-2xl font-semibold" }, title),
          subtitle ? el("div", { class:"text-sm text-zinc-400 mt-1" }, subtitle) : null,
        ]),
        right || null,
      ]),
    ]),
    el("div", { class:"px-6 pb-6 pt-5 flex-1 min-h-0 overflow-hidden" }, content || ""),
  ]);
}
export function breadcrumb(items = []) {
  // items: [{ label, onClick? }]
  return el("nav", { class:"text-xs text-zinc-400 flex flex-wrap items-center gap-2" },
    items.flatMap((it, idx) => {
      const parts = [];
      if (idx > 0) parts.push(el("span", { class:"text-zinc-600" }, "›"));
      if (it.onClick) {
        parts.push(el("button", {
          class:"hover:text-zinc-100 transition",
          type:"button",
          onclick: it.onClick
        }, it.label));
      } else {
        parts.push(el("span", { class:"text-zinc-200" }, it.label));
      }
      return parts;
    })
  );
}
