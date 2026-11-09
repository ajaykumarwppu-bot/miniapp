package com.example.gemini.miniapp

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.example.gemini.data.local.LocalEnvironmentManager
import java.io.File

/**
 * Finds installed Mini Apps (`~/mini-apps/<folder>/manifest.json`), installs the bundled samples on
 * first run and owns the mirrored-data layout (`~/antigem-data/mini-apps/<appId>/<user>/data.json`).
 *
 * Everything lives inside the Termux home (`LocalEnvironmentManager.getHomeDir`), so Mini Apps are
 * readable and editable from the terminal and by the AI agent, exactly like the rest of AntiGem.
 */
object MiniAppManager {

    private const val TAG = "MiniAppManager"

    /** `~/mini-apps` — source of truth for installed Mini App code (shown as `~/mini-apps` in the terminal). */
    fun appsRoot(context: Context): File =
        File(LocalEnvironmentManager.getHomeDir(context), "mini-apps")

    /** `~/antigem-data/mini-apps` — root of the human-readable mirrored storage. */
    fun dataRoot(context: Context): File =
        File(LocalEnvironmentManager.getHomeDir(context), "antigem-data/mini-apps")

    /** `~/antigem-data/mini-apps/<appId>/<user>/` — created on demand; [userSegment] comes from [sanitizeUserSegment]. */
    fun storageDir(context: Context, appId: String, userSegment: String): File {
        val dir = File(dataRoot(context), "$appId/$userSegment")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Turns an account email into a filesystem-safe folder segment (`ajay@gmail.com` → `user_ajay_at_gmail_com`). */
    fun sanitizeUserSegment(email: String?): String {
        val raw = email?.trim().orEmpty()
        if (raw.isBlank()) return "user_default_at_local"
        val cleaned = buildString {
            for (c in raw.lowercase()) when {
                c == '@' -> append("_at_")
                c.isLetterOrDigit() -> append(c)
                else -> append('_')
            }
        }.trimEnd('_')
        return "user_$cleaned"
    }

    /** Every installed Mini App (folder with a readable manifest), sorted by display name. */
    fun listApps(context: Context): List<MiniAppInfo> {
        val root = appsRoot(context)
        if (!root.exists()) root.mkdirs()
        return root.listFiles { f -> f.isDirectory && File(f, "manifest.json").isFile }
            ?.mapNotNull { MiniAppInfo.parse(it) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    /** Looks one app up by its `~/mini-apps/` folder name (the URL segment, e.g. "achiva"). */
    fun findApp(context: Context, folder: String): MiniAppInfo? =
        listApps(context).firstOrNull { it.folder == folder }

    /**
     * Copies the sample apps bundled in `assets/miniapps/samples/<name>` into `~/mini-apps/<name>`
     * the first time (existing folders are never touched: the user or agent may have edited them).
     */
    fun installBundledSamples(context: Context) {
        val manager = context.assets
        val names = runCatching { manager.list(SAMPLES_ASSET_DIR).orEmpty() }.getOrDefault(emptyArray())
        for (name in names) {
            val target = File(appsRoot(context), name)
            if (target.isDirectory && File(target, "manifest.json").isFile) continue
            runCatching { copyAssetDir(manager, "$SAMPLES_ASSET_DIR/$name", target) }
                .onFailure { Log.w(TAG, "Mini App sample install failed for $name: ${it.message}") }
        }
    }

    private fun copyAssetDir(manager: AssetManager, assetPath: String, target: File) {
        target.mkdirs()
        for (name in manager.list(assetPath).orEmpty()) {
            val childAsset = "$assetPath/$name"
            val childFile = File(target, name)
            if (manager.list(childAsset).orEmpty().isNotEmpty()) {
                copyAssetDir(manager, childAsset, childFile)
            } else {
                manager.open(childAsset).use { input ->
                    childFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private const val SAMPLES_ASSET_DIR = "miniapps/samples"
}
