package com.example.gemini.miniapp

import org.json.JSONObject
import java.io.File

/**
 * One installed Mini App, parsed from `manifest.json` inside `~/mini-apps/<folder>/`.
 *
 * Two names matter and must not be confused:
 * - [folder]: the directory name under `~/mini-apps/` (short, e.g. "achiva"); it is the URL segment
 *   the container serves (`https://antigem.local/<folder>/...`).
 * - [id]: the manifest identity (e.g. "com.achiva.studyos"); it namespaces the mirrored storage
 *   under `~/antigem-data/mini-apps/<id>/<user>/`.
 */
data class MiniAppInfo(
    val id: String,
    val folder: String,
    val name: String,
    val version: String,
    val entry: String,
    val icon: String,
    val themeColor: String,
    val permissions: List<String>,
    val dir: File
) {
    /** The URL the container loads first (the manifest entry point). */
    val entryUrl: String get() = "https://antigem.local/$folder/${entry.ifBlank { "index.html" }}"

    /** The drawer icon file inside the app folder (may not exist: callers fall back to a letter tile). */
    val iconFile: File get() = File(dir, icon.ifBlank { "icon.png" })

    fun hasPermission(permission: String): Boolean = permissions.contains(permission)

    companion object {
        /** Parses `manifest.json` in [dir]; null when the folder has no readable manifest. */
        fun parse(dir: File): MiniAppInfo? {
            if (!dir.isDirectory) return null
            val manifestFile = File(dir, "manifest.json")
            if (!manifestFile.isFile) return null
            return runCatching {
                val json = JSONObject(manifestFile.readText())
                MiniAppInfo(
                    id = json.optString("id").ifBlank { dir.name },
                    folder = dir.name,
                    name = json.optString("name").ifBlank { dir.name },
                    version = json.optString("version").ifBlank { "1.0.0" },
                    entry = json.optString("entry").ifBlank { "index.html" },
                    icon = json.optString("icon").ifBlank { "icon.png" },
                    themeColor = json.optString("theme_color").ifBlank { "#0f172a" },
                    permissions = json.optJSONArray("permissions")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                    } ?: emptyList(),
                    dir = dir
                )
            }.getOrNull()
        }
    }
}
