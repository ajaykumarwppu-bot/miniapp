# 18 — Mini Apps (Super-App Platform)

Mini Apps turn AntiGem from an AI assistant into a **super-app platform**: small, fast, standalone
web applications (a study planner, habit tracker, kanban board…) that live inside AntiGem, open
full-screen in one tap, and save their data right on your phone. They need no internet and no
server — everything runs natively on the device.

This guide explains what Mini Apps are, where they live, how to open them, and how the AI agent can
build one for you live.

---

## 1. What Is a Mini App?

A Mini App is a folder of plain web files (HTML, CSS, JavaScript) with a `manifest.json` that
describes it. AntiGem opens these files in a special full-screen viewer with a native bridge, so the
app feels like a real Android app:

- **Instant opening** — the viewer serves files directly from disk (no local web server, no network
  round trip), so a Mini App is on screen the moment you tap it.
- **Real storage** — every save goes through an atomic write to a JSON file you can actually read in
  the terminal.
- **Per-account privacy** — each AntiGem account (email) gets its own separate data folder.
- **AI-powered** — a Mini App can call AntiGem's local MCP tools (notes, terminal, browser…) through
  the same bridge your agents use.

### Where Mini Apps live

All Mini Apps live in your terminal home under `~/mini-apps/`, one folder per app:

```
~/
├── mini-apps/                        ← app code (editable by the AI agent)
│   └── achiva/
│       ├── manifest.json             ← identity: name, version, entry file, icon, permissions
│       ├── index.html                ← entry point
│       ├── style/                    ← styles
│       ├── features/                 ← JavaScript modules
│       └── icon.png                  ← 128×128 icon shown in the Mini Apps drawer
│
└── antigem-data/mini-apps/           ← saved data, mirrored as readable JSON
    └── com.achiva.studyos/
        └── user_ajay_at_gmail_com/
            └── data.json             ← this account's app state
```

Because the code is plain files in your home folder, you can inspect it in the terminal, edit it in
the IDE, and the AI agent can rewrite any part of it on request.

### The manifest (`manifest.json`)

```json
{
  "manifest_version": 1,
  "id": "com.achiva.studyos",
  "name": "Achiva Study OS",
  "version": "1.0.0",
  "entry": "index.html",
  "icon": "icon.png",
  "theme_color": "#0f172a",
  "permissions": ["storage", "mcp"]
}
```

| Field | Meaning |
|---|---|
| `id` | Unique identity; used for the data folder name |
| `name` | Shown under the icon in the Mini Apps drawer |
| `entry` | Which HTML file opens first |
| `icon` | 128×128 PNG for the drawer grid |
| `theme_color` | Tints the status bar while the app is open |
| `permissions` | `storage` (mirrored JSON storage) and `mcp` (local tool calls) |

---

## 2. Opening Mini Apps

### The Mini Apps drawer

1. Open the sidebar (☰) in the chat.
2. Tap the **grid icon** in the bottom row (next to Settings ⚙ and the palette 🎨).
3. You see every installed Mini App as a card with its icon and version. Tap one to open it
   full-screen; press back to return to the drawer (or use the app's own back navigation).
4. The refresh button (⟳) re-scans `~/mini-apps/` — useful right after the agent creates a new app.

### The sample app

AntiGem ships with a sample, **Achiva Study OS**, installed automatically the first time you open
the Mini Apps drawer (if no folder with that name exists yet). It demonstrates everything:

- a **focus timer** with automatic session counting,
- a **task list** with a daily streak,
- its state saved through the native bridge into
  `~/antigem-data/mini-apps/com.achiva.studyos/<account>/data.json` — open that file in the terminal
  and you will see the JSON change as you use the app,
- a **Ping notes MCP** button showing how a Mini App calls AntiGem's local tools.

---

## 3. Storage That You Can Read (Dual-Write)

Mini App saves are **atomic** and **human-readable**:

- The app writes `data.json.tmp` first and renames it over `data.json`, so a crash mid-save can
  never corrupt your data.
- Each file is wrapped in an envelope with the app id and save time:

```json
{
  "_meta": { "appId": "com.achiva.studyos", "savedAt": 1730000000000, "version": 1 },
  "data": { "tasks": [ ... ], "streak": 3, "sessionsToday": 2 }
}
```

- Data is separate **per account**: `user_ajay_at_gmail_com` and `user_rahul_at_gmail_com` never see
  each other's tasks. Switching accounts in AntiGem switches the Mini App's data with it.

### Backing up or resetting

- **Backup:** copy `~/antigem-data/mini-apps/` — that folder is all the app data there is.
- **Reset one app's data:** delete its `data.json` (or the account folder) in the terminal.
- **Remove an app:** delete its folder under `~/mini-apps/<app-name>/` and tap refresh in the drawer.

---

## 4. Hot Reload: Watch the AI Build Your App Live

This is the fun part. Ask the agent in chat:

> *"Add a dark mode toggle to Achiva"*

The agent edits the files in `~/mini-apps/<app-name>/`, and **the open Mini App refreshes by
itself** (a file watcher reloads the screen about half a second after a change). You watch the
update appear live while the agent works — no reinstalling, no restarting.

The Mini App itself can also call `AntiGem.reload()` (the sample app has a Reload button).

### Rollback with Git

Each Mini App folder can be its own Git repository. When the agent makes a change it commits it, so
undoing a change you dislike is one command in the terminal:

```bash
git -C ~/mini-apps/achiva checkout HEAD~1
```

The app reloads back to the previous state immediately.

---

## 5. The JavaScript API (`window.AntiGem`)

Mini Apps loaded inside AntiGem receive an SDK script (`/__antigem/antigem-sdk.js`) exposing
`window.AntiGem`:

| Call | What it does |
|---|---|
| `AntiGem.storage.save(state)` | Saves the whole app state atomically (returns a Promise) |
| `AntiGem.storage.load()` | Loads the saved state (`{}` on first run) |
| `AntiGem.callTool(name, args, endpoint?)` | Runs one MCP tool on the local bridge (`notes` by default; also `terminal`, `browser`, `android`, …) |
| `AntiGem.getAccount()` | `{ email, account, appId, appFolder }` for the current account |
| `AntiGem.reload()` | Reloads the app screen |
| `AntiGem.isNative` | `true` inside AntiGem, `false` if you open the folder in a normal browser |

A Mini App that also works in a desktop browser simply falls back to `localStorage` when
`AntiGem.isNative` is false — the sample app shows the pattern.

---

## 6. Asking the Agent for a Mini App

Any request phrased naturally works. Useful prompts:

- *"Create a Mini App called Habit Tracker in ~/mini-apps/habits — daily habits, streaks, and a
  weekly summary."*
- *"Add a pomodoro breakdown chart to Achiva."*
- *"Fix the timer in Achiva: it keeps running when I reopen the app."*

The agent will create/edit the files, the drawer refresh will show the new app, and hot reload
applies every following change live.

---

## 7. Troubleshooting

| Problem | Fix |
|---|---|
| App missing from the drawer | Tap ⟳ (refresh). Check that `~/mini-apps/<folder>/manifest.json` exists and is valid JSON. |
| App opens and closes instantly | The manifest `entry` file is missing — the entry (default `index.html`) must exist in the folder. |
| Data disappeared | Data is per-account: you switched AntiGem accounts. Sign back to the account that saved it. |
| MCP button fails | The local bridge server is not running — open the chat (the bridge starts with the app) and try again. |
| Icon not showing | `icon` must point to a real PNG inside the app folder; the drawer then shows the app's first letter instead. |
| App feels broken after an agent edit | Roll back with Git (`git -C ~/mini-apps/<app> checkout HEAD~1`) or ask the agent to revert. |
