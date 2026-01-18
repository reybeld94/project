import { el } from "../../ui.js";
import { apiGet, apiPost, apiPatch, apiDelete } from "../../api.js";

let cachedUsers = null;
let cachedProviders = null;

export function UsersTab(appState) {
  const container = el("div", { class: "h-full flex flex-col gap-4" });

  async function loadData() {
    try {
      const [users, providers] = await Promise.all([
        apiGet("/provider-users"),
        apiGet("/providers"),
      ]);
      cachedUsers = users;
      cachedProviders = providers;
      renderUsersList();
    } catch (err) {
      console.error("Error loading users:", err);
      container.replaceChildren(
        el("div", { class: "text-red-400" }, `Error: ${err.message}`)
      );
    }
  }

  function renderUsersList() {
    const header = el("div", { class: "flex justify-between items-center" }, [
      el("h2", { class: "text-xl font-semibold text-zinc-100" }, "Provider Users"),
      el(
        "button",
        {
          class:
            "px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded-lg text-sm font-medium transition",
          onclick: () => showAddUserModal(),
        },
        "Add User"
      ),
    ]);

    const table = el(
      "div",
      { class: "hz-glass rounded-2xl overflow-hidden flex-1 min-h-0 flex flex-col" },
      [
        el(
          "div",
          {
            class:
              "grid grid-cols-6 gap-4 px-4 py-3 bg-white/5 text-xs font-medium text-zinc-400 border-b border-white/10",
          },
          [
            el("div", {}, "Provider"),
            el("div", {}, "Alias"),
            el("div", {}, "Username"),
            el("div", {}, "Unique Code"),
            el("div", {}, "Status"),
            el("div", { class: "text-right" }, "Actions"),
          ]
        ),
        el(
          "div",
          { class: "overflow-y-auto flex-1" },
          cachedUsers && cachedUsers.length > 0
            ? cachedUsers.map((user) => renderUserRow(user))
            : [el("div", { class: "p-4 text-center text-zinc-400" }, "No users found")]
        ),
      ]
    );

    container.replaceChildren(header, table);
  }

  function renderUserRow(user) {
    const provider = cachedProviders?.find((p) => p.id === user.provider_id);
    const providerName = provider ? provider.name : "Unknown";

    return el(
      "div",
      {
        class:
          "grid grid-cols-6 gap-4 px-4 py-3 text-sm border-b border-white/5 hover:bg-white/5 transition items-center",
      },
      [
        el("div", { class: "text-zinc-300 truncate" }, providerName),
        el("div", { class: "text-zinc-300 truncate" }, user.alias),
        el("div", { class: "text-zinc-400 font-mono text-xs truncate" }, user.username),
        el(
          "div",
          { class: "font-mono text-xs text-blue-400 font-semibold" },
          user.unique_code
        ),
        el(
          "div",
          {},
          el(
            "span",
            {
              class: `px-2 py-1 rounded text-xs ${
                user.is_enabled
                  ? "bg-green-500/20 text-green-400"
                  : "bg-red-500/20 text-red-400"
              }`,
            },
            user.is_enabled ? "Enabled" : "Disabled"
          )
        ),
        el("div", { class: "flex gap-2 justify-end" }, [
          el(
            "button",
            {
              class: "px-3 py-1 bg-blue-600/20 hover:bg-blue-600/30 rounded text-xs text-blue-400 transition",
              onclick: () => showEditUserModal(user),
            },
            "Edit"
          ),
          el(
            "button",
            {
              class: "px-3 py-1 bg-red-600/20 hover:bg-red-600/30 rounded text-xs text-red-400 transition",
              onclick: () => deleteUser(user),
            },
            "Delete"
          ),
        ]),
      ]
    );
  }

  function showAddUserModal() {
    if (!cachedProviders || cachedProviders.length === 0) {
      alert("Please add a provider first");
      return;
    }

    const modal = el(
      "div",
      {
        class:
          "fixed inset-0 bg-black/50 flex items-center justify-center z-50",
        onclick: (e) => {
          if (e.target === modal) modal.remove();
        },
      },
      [
        el(
          "div",
          { class: "hz-glass rounded-2xl p-6 w-full max-w-md", onclick: (e) => e.stopPropagation() },
          [
            el("h3", { class: "text-xl font-semibold mb-4" }, "Add Provider User"),
            el("form", { class: "flex flex-col gap-4", onsubmit: handleAddUser }, [
              // Provider select
              el("div", { class: "flex flex-col gap-2" }, [
                el("label", { class: "text-sm text-zinc-400" }, "Provider"),
                el(
                  "select",
                  {
                    name: "provider_id",
                    class:
                      "bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-blue-500",
                    required: true,
                  },
                  cachedProviders.map((p) =>
                    el("option", { value: p.id }, p.name)
                  )
                ),
              ]),
              // Alias
              el("div", { class: "flex flex-col gap-2" }, [
                el("label", { class: "text-sm text-zinc-400" }, "Alias (Friendly Name)"),
                el("input", {
                  type: "text",
                  name: "alias",
                  class:
                    "bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-blue-500",
                  placeholder: "e.g., Client 1, John Doe",
                  required: true,
                }),
              ]),
              // Username
              el("div", { class: "flex flex-col gap-2" }, [
                el("label", { class: "text-sm text-zinc-400" }, "Xtream Username"),
                el("input", {
                  type: "text",
                  name: "username",
                  class:
                    "bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-blue-500",
                  placeholder: "Xtream username",
                  required: true,
                }),
              ]),
              // Password
              el("div", { class: "flex flex-col gap-2" }, [
                el("label", { class: "text-sm text-zinc-400" }, "Xtream Password"),
                el("input", {
                  type: "text",
                  name: "password",
                  class:
                    "bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-blue-500",
                  placeholder: "Xtream password",
                  required: true,
                }),
              ]),
              // Enabled checkbox
              el("div", { class: "flex items-center gap-2" }, [
                el("input", {
                  type: "checkbox",
                  name: "is_enabled",
                  id: "is_enabled",
                  checked: true,
                  class: "w-4 h-4",
                }),
                el("label", { for: "is_enabled", class: "text-sm text-zinc-400" }, "Enabled"),
              ]),
              // Buttons
              el("div", { class: "flex gap-2 mt-2" }, [
                el(
                  "button",
                  {
                    type: "submit",
                    class:
                      "flex-1 px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded-lg text-sm font-medium transition",
                  },
                  "Create User"
                ),
                el(
                  "button",
                  {
                    type: "button",
                    class:
                      "px-4 py-2 bg-white/10 hover:bg-white/20 rounded-lg text-sm transition",
                    onclick: () => modal.remove(),
                  },
                  "Cancel"
                ),
              ]),
            ]),
          ]
        ),
      ]
    );

    async function handleAddUser(e) {
      e.preventDefault();
      const formData = new FormData(e.target);
      const payload = {
        provider_id: formData.get("provider_id"),
        alias: formData.get("alias"),
        username: formData.get("username"),
        password: formData.get("password"),
        is_enabled: formData.get("is_enabled") === "on",
      };

      try {
        await apiPost("/provider-users", payload);
        modal.remove();
        loadData();
      } catch (err) {
        alert(`Error creating user: ${err.message}`);
      }
    }

    document.body.appendChild(modal);
  }

  function showEditUserModal(user) {
    const modal = el(
      "div",
      {
        class:
          "fixed inset-0 bg-black/50 flex items-center justify-center z-50",
        onclick: (e) => {
          if (e.target === modal) modal.remove();
        },
      },
      [
        el(
          "div",
          { class: "hz-glass rounded-2xl p-6 w-full max-w-md", onclick: (e) => e.stopPropagation() },
          [
            el("h3", { class: "text-xl font-semibold mb-4" }, "Edit Provider User"),
            el("form", { class: "flex flex-col gap-4", onsubmit: handleEditUser }, [
              // Alias
              el("div", { class: "flex flex-col gap-2" }, [
                el("label", { class: "text-sm text-zinc-400" }, "Alias (Friendly Name)"),
                el("input", {
                  type: "text",
                  name: "alias",
                  value: user.alias,
                  class:
                    "bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-blue-500",
                  placeholder: "e.g., Client 1, John Doe",
                }),
              ]),
              // Username
              el("div", { class: "flex flex-col gap-2" }, [
                el("label", { class: "text-sm text-zinc-400" }, "Xtream Username"),
                el("input", {
                  type: "text",
                  name: "username",
                  value: user.username,
                  class:
                    "bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-blue-500",
                  placeholder: "Xtream username",
                }),
              ]),
              // Password
              el("div", { class: "flex flex-col gap-2" }, [
                el("label", { class: "text-sm text-zinc-400" }, "Xtream Password"),
                el("input", {
                  type: "text",
                  name: "password",
                  placeholder: "Leave empty to keep current",
                  class:
                    "bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-blue-500",
                }),
              ]),
              // Enabled checkbox
              el("div", { class: "flex items-center gap-2" }, [
                el("input", {
                  type: "checkbox",
                  name: "is_enabled",
                  id: "is_enabled_edit",
                  checked: user.is_enabled,
                  class: "w-4 h-4",
                }),
                el("label", { for: "is_enabled_edit", class: "text-sm text-zinc-400" }, "Enabled"),
              ]),
              // Unique Code (read-only)
              el("div", { class: "flex flex-col gap-2" }, [
                el("label", { class: "text-sm text-zinc-400" }, "Unique Code"),
                el("div", { class: "bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm font-mono text-blue-400" }, user.unique_code),
              ]),
              // Buttons
              el("div", { class: "flex gap-2 mt-2" }, [
                el(
                  "button",
                  {
                    type: "submit",
                    class:
                      "flex-1 px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded-lg text-sm font-medium transition",
                  },
                  "Update User"
                ),
                el(
                  "button",
                  {
                    type: "button",
                    class:
                      "px-4 py-2 bg-white/10 hover:bg-white/20 rounded-lg text-sm transition",
                    onclick: () => modal.remove(),
                  },
                  "Cancel"
                ),
              ]),
            ]),
          ]
        ),
      ]
    );

    async function handleEditUser(e) {
      e.preventDefault();
      const formData = new FormData(e.target);
      const payload = {};

      const alias = formData.get("alias");
      if (alias && alias !== user.alias) payload.alias = alias;

      const username = formData.get("username");
      if (username && username !== user.username) payload.username = username;

      const password = formData.get("password");
      if (password) payload.password = password;

      const isEnabled = formData.get("is_enabled") === "on";
      if (isEnabled !== user.is_enabled) payload.is_enabled = isEnabled;

      if (Object.keys(payload).length === 0) {
        modal.remove();
        return;
      }

      try {
        await apiPatch(`/provider-users/${user.id}`, payload);
        modal.remove();
        loadData();
      } catch (err) {
        alert(`Error updating user: ${err.message}`);
      }
    }

    document.body.appendChild(modal);
  }

  async function deleteUser(user) {
    if (!confirm(`Are you sure you want to delete user "${user.alias}"?`)) {
      return;
    }

    try {
      await apiDelete(`/provider-users/${user.id}`);
      loadData();
    } catch (err) {
      alert(`Error deleting user: ${err.message}`);
    }
  }

  loadData();
  return container;
}
