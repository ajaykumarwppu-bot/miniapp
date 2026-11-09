package com.example.gemini.miniapp

import android.app.Activity
import android.os.FileObserver
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.example.gemini.data.remote.AndroidLocalBridgeServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.util.concurrent.TimeUnit

/**
 * The Mini Apps JS↔Android bridge, exposed to the WebView as `window.AntiGemNative`.
 *
 * Gives a Mini App four native powers (see ANTIGEM_MINI_APPS_IMPLEMENTATION_SPEC.md):
 * 1. Dual-storage sync — atomic JSON writes into `~/antigem-data/mini-apps/<appId>/<user>/data.json`,
 *    human-readable and mirrored, one folder per AntiGem account.
 * 2. MCP tool execution — `tools/call` against AntiGem's local bridge server (the same server the
 *    agents use), results delivered back into the page through `window.AntiGem._onMcpResult`.
 * 3. Account identity — the signed-in AntiGem email, sandboxing storage per account.
 * 4. Hot reload — manual `reload()` plus a file watcher: when the AI agent edits any file under the
 *    app folder, the screen refreshes by itself.
 */
class AntiGemBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val app: MiniAppInfo
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentUserSegment: String = MiniAppManager.sanitizeUserSegment(null)

    // ---------------------------------------------------------------- account identity

    /** Called by the container with the signed-in AntiGem email (or null for the default account). */
    fun setUserEmail(email: String?) {
        currentUserSegment = MiniAppManager.sanitizeUserSegment(email)
    }

    @JavascriptInterface
    fun getAccountInfo(): String {
        return JSONObject()
            .put("email", currentUserSegment.removePrefix("user_").replace("_at_", "@"))
            .put("account", currentUserSegment)
            .put("appId", app.id)
            .put("appFolder", app.folder)
            .toString()
    }

    // ---------------------------------------------------------------- dual-storage sync

    private fun storageFile(): File =
        File(MiniAppManager.storageDir(activity, app.id, currentUserSegment), "data.json")

    /**
     * Atomic write: the JSON lands in `data.json.tmp` first and is renamed over the real file, so a
     * crash mid-write can never corrupt the mirrored state. Returns true when the data is on disk.
     */
    @JavascriptInterface
    fun persistState(stateJsonString: String): Boolean {
        return try {
            val file = storageFile()
            val parent = file.parentFile ?: return false
            if (!parent.exists()) parent.mkdirs()
            val tempFile = File(parent, "data.json.tmp")
            FileWriter(tempFile).use { it.write(stateJsonString) }
            if (tempFile.renameTo(file)) {
                true
            } else {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "persistState failed for ${app.id}: ${e.message}")
            false
        }
    }

    /** Reads the mirrored state; `{}` when nothing was saved yet (first launch of a fresh account). */
    @JavascriptInterface
    fun loadPersistedState(): String {
        return try {
            val file = storageFile()
            if (file.exists()) file.readText() else "{}"
        } catch (e: Exception) {
            Log.w(TAG, "loadPersistedState failed for ${app.id}: ${e.message}")
            "{}"
        }
    }

    // ---------------------------------------------------------------- MCP tool execution

    /** Spec form: tools run against the notes MCP by default. */
    @JavascriptInterface
    fun executeMcpTool(toolName: String, argsJsonString: String, callbackId: String) {
        executeMcpTool(DEFAULT_MCP_ENDPOINT, toolName, argsJsonString, callbackId)
    }

    /**
     * Extended form used by the SDK: [endpoint] picks the local bridge MCP server (`notes`, `browser`,
     * `terminal`, `android`, ...). The result is posted back to the page as JSON-encoded strings, so
     * any quotes or newlines in tool output survive the round trip.
     */
    @JavascriptInterface
    fun executeMcpTool(endpoint: String, toolName: String, argsJsonString: String, callbackId: String) {
        Thread {
            val (error, result) = callLocalMcpHost(endpoint, toolName, argsJsonString)
            val errorJs = JSONObject.quote(error ?: "")
            val resultJs = JSONObject.quote(result ?: "")
            activity.runOnUiThread {
                webView.evaluateJavascript(
                    "window.AntiGem && window.AntiGem._onMcpResult(" +
                        "${JSONObject.quote(callbackId)}, $errorJs, $resultJs);",
                    null
                )
            }
        }.start()
    }

    /**
     * JSON-RPC `tools/call` against AntiGem's local bridge server (`AndroidLocalBridgeServer`, bound to
     * 127.0.0.1). The server checks the same `x-device-id` header the agents use, so Mini Apps get
     * exactly the tools (and only the tools) the AntiGem MCP endpoints expose.
     */
    private fun callLocalMcpHost(endpoint: String, tool: String, argsJson: String): Pair<String?, String?> {
        return try {
            val port = AndroidLocalBridgeServer.instance.boundPort
            val deviceId = try {
                Settings.Secure.getString(activity.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
            } catch (_: Exception) {
                ""
            }
            val payload = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", System.currentTimeMillis())
                .put("method", "tools/call")
                .put(
                    "params",
                    JSONObject()
                        .put("name", tool)
                        .put("arguments", JSONObject(argsJson.ifBlank { "{}" }))
                )
            val request = Request.Builder()
                .url("http://127.0.0.1:$port/${endpoint.trimStart('/')}")
                .header("x-device-id", deviceId)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use "HTTP ${response.code}: ${body.take(200)}" to null
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return@use "Invalid JSON from local bridge" to body.take(2000)
                json.optJSONObject("error")?.let { err ->
                    return@use err.optString("message").ifBlank { "MCP error" } to null
                }
                val result = json.optJSONObject("result")
                // MCP wraps output in content blocks: hand back the first text block, else the raw result
                val text = result?.optJSONArray("content")?.let { content ->
                    (0 until content.length()).firstNotNullOfOrNull { i ->
                        val item = content.optJSONObject(i)
                        item?.takeIf { it.optString("type") == "text" }?.optString("text")
                    }
                }
                null to (text ?: result?.toString(2) ?: body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "MCP tool '$tool' on /$endpoint failed: ${e.message}")
            "Local bridge not reachable: ${e.message ?: e.javaClass.simpleName}" to null
        }
    }

    // ---------------------------------------------------------------- hot reload

    /** Manual reload, callable from the Mini App (e.g. a "Refresh" button). */
    @JavascriptInterface
    fun triggerReload() {
        activity.runOnUiThread { webView.reload() }
    }

    /**
     * Watches the app folder (recursively) so an agent edit in `~/mini-apps/<folder>/` reloads the
     * screen by itself. Call from the container's onCreate/onDestroy. Events are debounced: an editor
     * usually touches several files in one save.
     */
    fun startHotReloadWatcher() {
        stopHotReloadWatcher()
        registerRecursive(app.dir)
    }

    fun stopHotReloadWatcher() {
        synchronized(observers) {
            observers.forEach { it.stopWatching() }
            observers.clear()
        }
        reloadPending = false
    }

    private val observers = mutableListOf<FileObserver>()

    @Volatile
    private var reloadPending = false

    private fun registerRecursive(dir: File) {
        if (!dir.isDirectory) return
        val mask = FileObserver.CREATE or FileObserver.MODIFY or FileObserver.DELETE or
            FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.CLOSE_WRITE
        val observer = object : FileObserver(dir, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && (path == "data.json.tmp" || path.endsWith(".tmp"))) return
                scheduleReload()
            }
        }
        synchronized(observers) {
            observers.add(observer)
            observer.startWatching()
        }
        dir.listFiles()?.filter { it.isDirectory }?.forEach { registerRecursive(it) }
    }

    private fun scheduleReload() {
        if (reloadPending) return
        reloadPending = true
        Thread {
            try {
                Thread.sleep(HOT_RELOAD_DEBOUNCE_MS)
            } catch (_: InterruptedException) {}
            reloadPending = false
            activity.runOnUiThread { webView.reload() }
        }.start()
    }

    companion object {
        private const val TAG = "AntiGemBridge"
        private const val HOT_RELOAD_DEBOUNCE_MS = 400L
        /** Spec default: the notes MCP is the first stop for Mini App tool calls. */
        const val DEFAULT_MCP_ENDPOINT = "notes"
    }
}
