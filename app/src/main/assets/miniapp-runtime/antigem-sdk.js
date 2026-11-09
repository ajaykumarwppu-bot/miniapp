/**
 * AntiGem Mini Apps SDK — served to every Mini App at https://antigem.local/__antigem/antigem-sdk.js
 *
 * Load it with a plain script tag before your app code:
 *   <script src="/__antigem/antigem-sdk.js"></script>
 *
 * Exposes window.AntiGem:
 *   AntiGem.storage.save(obj)  -> Promise<boolean>   atomic, mirrored to ~/antigem-data/...
 *   AntiGem.storage.load()     -> Promise<object>    {} on first run
 *   AntiGem.callTool(name, args, endpoint?) -> Promise<any>   local MCP tools/call
 *   AntiGem.getAccount()       -> {email, account, appId, appFolder}
 *   AntiGem.reload()           -> hot reload (also happens automatically on file changes)
 *
 * Without the native bridge (plain browser preview) storage transparently falls back to
 * localStorage, so the same files run identically everywhere. Pure ES5: no transpiler needed.
 */
(function () {
  'use strict';

  var callbacks = {};
  var callbackCounter = 0;

  function hasNative() {
    return typeof window.AntiGemNative !== 'undefined' && window.AntiGemNative !== null;
  }

  function tryParse(text) {
    try {
      return JSON.parse(text);
    } catch (e) {
      return null;
    }
  }

  /**
   * localStorage fallback for previews outside the AntiGem container (plain browser tab, desktop).
   * Keyed by appId so several Mini Apps previewed in one browser never overwrite each other.
   */
  var FALLBACK_PREFIX = 'antigem.miniapp.';

  function fallbackKey() {
    return FALLBACK_PREFIX + ((window.AntiGem && window.AntiGem.appId) || 'unknown') + '.state';
  }

  function writeFallback(envelope) {
    try {
      window.localStorage.setItem(fallbackKey(), JSON.stringify(envelope));
      return true;
    } catch (e) {
      return false;
    }
  }

  function readFallback() {
    try {
      return window.localStorage.getItem(fallbackKey());
    } catch (e) {
      return null;
    }
  }

  /**
   * Normalizes what the native bridge hands back:
   * - JSON text -> parsed object
   * - a raw MCP tools/call envelope ({content:[{type:'text',text:'…'}]}) -> its first text block
   * - anything else -> the string unchanged
   */
  function unwrapResult(result) {
    if (typeof result !== 'string') return result;
    var parsed = tryParse(result);
    if (parsed === null || typeof parsed !== 'object') return result;
    if (Object.prototype.toString.call(parsed.content) === '[object Array]') {
      for (var i = 0; i < parsed.content.length; i++) {
        var block = parsed.content[i];
        if (block && block.type === 'text' && typeof block.text === 'string') {
          var inner = tryParse(block.text);
          return inner === null ? block.text : inner;
        }
      }
    }
    return parsed;
  }

  window.AntiGem = {
    /** True when running inside the AntiGem container (false in a normal browser tab). */
    isNative: hasNative(),

    appId: (hasNative() && window.AntiGemNative.getAccountInfo)
      ? (tryParse(window.AntiGemNative.getAccountInfo()) || {}).appFolder || ''
      : '',

    // ---------------------------------------------------------------- 1. Storage API
    storage: {
      /**
       * Saves the whole app state atomically. An envelope with _meta is written around the data so
       * humans inspecting data.json in the terminal can see which app saved it and when. Without the
       * native bridge (a plain browser preview) the envelope falls back to localStorage.
       */
      save: function (stateObject) {
        var envelope = {
          _meta: {
            appId: window.AntiGem.appId,
            savedAt: Date.now(),
            version: 1
          },
          data: stateObject
        };
        if (!hasNative()) return Promise.resolve(writeFallback(envelope));
        return Promise.resolve(
          window.AntiGemNative.persistState(JSON.stringify(envelope, null, 2)) === true
        );
      },

      /** Loads the saved state; resolves {} when nothing was saved yet. */
      load: function () {
        var raw = null;
        if (hasNative()) {
          raw = window.AntiGemNative.loadPersistedState();
        } else {
          raw = readFallback();
        }
        if (raw === null || raw === undefined) return Promise.resolve({});
        try {
          var parsed = JSON.parse(raw);
          return Promise.resolve((parsed && parsed.data) || {});
        } catch (e) {
          return Promise.resolve({});
        }
      }
    },

    // ---------------------------------------------------------------- 2. MCP tool execution
    /**
     * Runs one tool on AntiGem's local MCP bridge. endpoint is optional and defaults to "notes";
     * other endpoints: browser, terminal, google_flow, android, extension_developer, jarvis, theme.
     * Resolves with the tool's text output (JSON when the tool returns JSON), rejects on error.
     */
    callTool: function (toolName, args, endpoint) {
      if (!hasNative()) {
        return Promise.reject(new Error('AntiGem native bridge not available'));
      }
      return new Promise(function (resolve, reject) {
        var id = 'cb_' + (++callbackCounter);
        callbacks[id] = { resolve: resolve, reject: reject };
        try {
          window.AntiGemNative.executeMcpTool(
            endpoint || 'notes',
            toolName,
            JSON.stringify(args || {}),
            id
          );
        } catch (e) {
          delete callbacks[id];
          reject(e);
        }
      });
    },

    /** Internal dispatcher — the native bridge answers here. Do not call directly. */
    _onMcpResult: function (id, err, result) {
      var cb = callbacks[id];
      if (!cb) return;
      delete callbacks[id];
      if (err) {
        cb.reject(new Error(err));
        return;
      }
      cb.resolve(unwrapResult(result));
    },

    // ---------------------------------------------------------------- 3. Account identity
    /** The AntiGem account running this Mini App: {email, account, appId, appFolder}. */
    getAccount: function () {
      if (!hasNative() || !window.AntiGemNative.getAccountInfo) {
        return { email: 'guest@local', account: 'user_default_at_local', appId: '', appFolder: '' };
      }
      return tryParse(window.AntiGemNative.getAccountInfo()) || { email: 'guest@local' };
    },

    // ---------------------------------------------------------------- 4. Hot reload
    /** Manual refresh. The container also reloads by itself when files in the app folder change. */
    reload: function () {
      if (hasNative() && window.AntiGemNative.triggerReload) {
        window.AntiGemNative.triggerReload();
      } else {
        window.location.reload();
      }
    }
  };
})();
