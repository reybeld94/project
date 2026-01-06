export function parseRoute() {
  // hash style: #/live, #/settings/providers, etc
  const raw = (location.hash || "#/live").replace(/^#/, "");
  const clean = raw.startsWith("/") ? raw : `/${raw}`;
  const parts = clean.split("/").filter(Boolean);
  return {
    path: `/${parts.join("/")}`,
    parts,
    root: parts[0] || "live",
    sub: parts[1] || null,
  };
}

export function go(path) {
  location.hash = path.startsWith("#") ? path : `#${path.startsWith("/") ? path : `/${path}`}`;
}
