# AntiGem Mini Apps Engine — Implementation Specification & Developer Guide

> **Document Type:** Production Engineering Specification  
> **Target Implementer:** AI Coding Agent / AntiGem Core Developer  
> **Status:** Ready for Implementation  
> **Scope:** Add native, zero-server Mini Apps execution, real-time dual-storage sync, multi-account sandboxing, and hot-reloading to the AntiGem Android codebase.

---

## 1. System Architecture Overview

AntiGem transforms into a **Super-App Platform** capable of hosting lightweight, standalone web applications (e.g., Achiva Study OS, Habit Tracker, Kanban Board) inside a high-performance native container.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                              ANTIGEM ANDROID SHELL                           │
│                                                                              │
│  ┌─────────────────────────┐          ┌───────────────────────────────────┐  │
│  │ AntiGem Main Activity   │          │ MiniAppActivity (Full-screen)     │  │
│  │ (Chat / AI Engine)      │          │ • Custom Android WebView          │  │
│  │                         │          │ • WebViewAssetLoader (0ms boot)   │  │
│  └────────────┬────────────┘          └─────────────────┬─────────────────┘  │
│               │                                         │                    │
│               │                                         │ @JavascriptInterface
│               │                                         ▼                    │
│               │                      ┌────────────────────────────────────┐  │
│               │                      │ AntiGemBridge (window.AntiGem)     │  │
│               │                      ├────────────────────────────────────┤  │
│               │                      │ • callMcpTool()                    │  │
│               │                      │ • askAgent()                       │  │
│               │                      │ • storage (Dual-Write Engine)      │  │
│               │                      │ • getAccount()                     │  │
│               │                      └──────────────────┬─────────────────┘  │
│               │                                         │                    │
└───────────────┼─────────────────────────────────────────┼────────────────────┘
                │                                         │
                ▼                                         ▼
┌────────────────────────────────┐       ┌─────────────────────────────────────┐
│ Node.js MCP Host (:8865)       │       │ Local Filesystem (~/ Termux Home)   │
│ (Existing AntiGem Runtime)     │       │                                     │
│ • google-flow                  │       │ 📁 ~/mini-apps/<appId>/ (Source)    │
│ • custom_plugin_developer      │       │ 📁 ~/antigem-data/<appId>/<email>/  │
│ • dynamic user MCPs            │       │    └── data.json (Mirrored Storage) │
└────────────────────────────────┘       └─────────────────────────────────────┘
```

---

## 2. Directory & Storage Structure on Device

All files reside inside Termux Home (`/data/user/0/com.termux/files/home` or `~/`):

```bash
~/
├── mini-apps/                               # Source code of installed Mini Apps
│   └── achiva/
│       ├── manifest.json                    # App identity & permissions
│       ├── index.html                       # Entry point
│       ├── style/                           # CSS
│       ├── features/                        # JS modules
│       └── icon.png                         # 128x128 icon for drawer
│
└── antigem-data/                            # Persistent human-readable storage
    └── mini-apps/
        └── com.achiva.studyos/              # Namespaced by appId
            ├── user_ajay_at_gmail_com/      # Multi-account isolation
            │   └── data.json                # Real-time mirrored JSON data
            └── user_rahul_at_gmail_com/
                └── data.json
```

---

## 3. Mini App Manifest Specification (`manifest.json`)

Every Mini App folder must include a `manifest.json`:

```json
{
  "manifest_version": 1,
  "id": "com.achiva.studyos",
  "name": "Achiva Study OS",
  "version": "1.0.0",
  "entry": "index.html",
  "icon": "icon.png",
  "theme_color": "#0f172a",
  "permissions": [
    "storage",
    "mcp"
  ]
}
```

---

## 4. Android Implementation Blueprint (Kotlin)

### Component 1: `MiniAppContainerActivity.kt`
Renders the app without any HTTP server using `androidx.webkit.WebViewAssetLoader`.

```kotlin
package com.antigem.app.miniapp

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.*
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler
import java.io.File

class MiniAppContainerActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var appId: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appId = intent.getStringExtra("EXTRA_APP_ID") ?: return finish()
        val appDir = File("/data/user/0/com.termux/files/home/mini-apps/$appId")
        if (!appDir.exists()) return finish()

        webView = WebView(this)
        setContentView(webView)

        // Configure WebView for maximum performance & 60fps
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false // Security: disable raw file access
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        // Virtual domain: https://antigem.local/<appId>/...
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain("antigem.local")
            .addPathHandler("/$appId/", InternalStoragePathHandler(this, appDir))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        // Inject Native JS Bridge
        val bridge = AntiGemBridge(this, webView, appId)
        webView.addJavascriptInterface(bridge, "AntiGemNative")

        // Load entry point instantly (0ms latency)
        webView.loadUrl("https://antigem.local/$appId/index.html")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
```

---

### Component 2: `AntiGemBridge.kt` (The Bi-Directional Bridge)
Bridges Mini App JavaScript to Android, Local Disk Storage, and the AI Agent.

```kotlin
package com.antigem.app.miniapp

import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

class AntiGemBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val appId: String
) {

    // Current active user account (matches AntiGem session)
    private var currentUserEmail: String = "default_user@local"

    fun setUserAccount(email: String) {
        this.currentUserEmail = email.replace("@", "_at_").replace(".", "_")
    }

    private fun getStorageFile(): File {
        val dir = File("/data/user/0/com.termux/files/home/antigem-data/mini-apps/$appId/$currentUserEmail")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "data.json")
    }

    // -------------------------------------------------------------
    // 1. Dual-Storage Sync: Write to Disk
    // -------------------------------------------------------------
    @JavascriptInterface
    fun persistState(stateJsonString: String): Boolean {
        return try {
            val file = getStorageFile()
            val tempFile = File(file.parentFile, "data.json.tmp")
            
            // Atomic write to prevent file corruption
            FileWriter(tempFile).use { it.write(stateJsonString) }
            if (tempFile.renameTo(file)) {
                true
            } else {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // -------------------------------------------------------------
    // 2. Dual-Storage Sync: Read from Disk
    // -------------------------------------------------------------
    @JavascriptInterface
    fun loadPersistedState(): String {
        val file = getStorageFile()
        return if (file.exists()) {
            file.readText()
        } else {
            "{}"
        }
    }

    // -------------------------------------------------------------
    // 3. User Identity / Account
    // -------------------------------------------------------------
    @JavascriptInterface
    fun getAccountInfo(): String {
        val json = JSONObject()
        json.put("email", currentUserEmail)
        json.put("appId", appId)
        return json.toString()
    }

    // -------------------------------------------------------------
    // 4. MCP Tool Execution Bridge
    // -------------------------------------------------------------
    @JavascriptInterface
    fun executeMcpTool(toolName: String, argsJsonString: String, callbackId: String) {
        // Dispatches to AntiGem's existing Node.js Loopback Host on port 8865
        Thread {
            try {
                val result = callNodeMcpHost(toolName, argsJsonString)
                activity.runOnUiThread {
                    webView.evaluateJavascript(
                        "window.AntiGem._onMcpResult('$callbackId', null, $result);",
                        null
                    )
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    webView.evaluateJavascript(
                        "window.AntiGem._onMcpResult('$callbackId', '${e.message}', null);",
                        null
                    )
                }
            }
        }.start()
    }

    private fun callNodeMcpHost(tool: String, args: String): String {
        // HTTP POST to http://127.0.0.1:8865/mcp
        val url = java.net.URL("http://127.0.0.1:8865/mcp")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        
        val payload = JSONObject()
        payload.put("type", "mcp_tool_call")
        payload.put("tool", tool)
        payload.put("args", JSONObject(args))
        
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        return conn.inputStream.bufferedReader().readText()
    }

    // -------------------------------------------------------------
    // 5. Hot Reload Trigger
    // -------------------------------------------------------------
    @JavascriptInterface
    fun triggerReload() {
        activity.runOnUiThread {
            webView.reload()
        }
    }
}
```

---

### Component 3: Client-Side SDK Injected in Mini Apps (`antigem-sdk.js`)
Exposes the clean, promise-based `window.AntiGem` API to the Mini App:

```javascript
(function () {
  'use strict';

  var callbacks = {};
  var callbackCounter = 0;

  window.AntiGem = {
    // 1. Storage API
    storage: {
      save: function (stateObject) {
        if (!window.AntiGemNative) return Promise.resolve(false);
        var envelope = {
          _meta: {
            appId: window.AntiGem.appId,
            savedAt: Date.now(),
            version: 1
          },
          data: stateObject
        };
        return Promise.resolve(window.AntiGemNative.persistState(JSON.stringify(envelope, null, 2)));
      },
      load: function () {
        if (!window.AntiGemNative) return Promise.resolve(null);
        try {
          var raw = window.AntiGemNative.loadPersistedState();
          var parsed = JSON.parse(raw);
          return Promise.resolve(parsed.data || parsed);
        } catch (e) {
          return Promise.resolve(null);
        }
      }
    },

    // 2. MCP Tool Execution
    callTool: function (toolName, args) {
      return new Promise(function (resolve, reject) {
        var id = 'cb_' + (++callbackCounter);
        callbacks[id] = { resolve: resolve, reject: reject };
        window.AntiGemNative.executeMcpTool(toolName, JSON.stringify(args || {}), id);
      });
    },

    // Internal MCP callback dispatcher
    _onMcpResult: function (id, err, result) {
      var cb = callbacks[id];
      if (!cb) return;
      delete callbacks[id];
      if (err) cb.reject(new Error(err));
      else cb.resolve(result);
    },

    // 3. User Identity
    getAccount: function () {
      if (!window.AntiGemNative) return { email: 'guest@local' };
      return JSON.parse(window.AntiGemNative.getAccountInfo());
    },

    // 4. Hot Reload
    reload: function () {
      if (window.AntiGemNative) window.AntiGemNative.triggerReload();
      else window.location.reload();
    }
  };
})();
```

---

## 5. Live Development & Hot Reload Loop

```
┌────────────────────────────────────────────────────────┐
│  AI AGENT (Antigravity) in Chat                        │
│  "User requests: Add daily streak badge in Achiva"     │
└──────────────────────────┬─────────────────────────────┘
                           │ 1. AI edits feature.js in
                           │    ~/mini-apps/achiva/features/
                           ▼
┌────────────────────────────────────────────────────────┐
│  ~/mini-apps/achiva/ (Termux Filesystem)               │
└──────────────────────────┬─────────────────────────────┘
                           │ 2. Signal reload to
                           │    WebView Container
                           ▼
┌────────────────────────────────────────────────────────┐
│  AntiGem MiniAppActivity (Live Screen)                 │
│  • Screen reloads in 100ms                             │
│  • New badge visible immediately!                      │
└────────────────────────────────────────────────────────┘
```

---

## 6. Git Version Control & Rollback Protocol

Because `~/mini-apps/<appId>/` is a Git repository:
1. Every time the AI Agent makes a change, it commits with a descriptive message:
   ```bash
   git -C ~/mini-apps/<appId> commit -am "feat: added daily streak widget"
   ```
2. **Instant Rollback:** If the user dislikes a change:
   ```bash
   git -C ~/mini-apps/<appId> checkout HEAD~1
   ```
   Mini App reloads, returning instantly to the previous working state.
