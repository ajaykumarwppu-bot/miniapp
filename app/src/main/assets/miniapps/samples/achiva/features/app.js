/* Achiva Study OS — sample Mini App logic.
 * Demonstrates the AntiGem SDK: mirrored storage, account identity, MCP tool calls,
 * and living happily in a normal browser (window.AntiGem missing) with localStorage fallback.
 */
(function () {
  'use strict';

  var state = {
    tasks: [],            // [{ id, label, done }]
    streak: 0,            // consecutive days with at least one done task
    lastDoneDate: null,   // yyyy-mm-dd of the last completion
    sessionsToday: 0,     // completed focus sessions today
    timerSeconds: 25 * 60
  };

  var hasAntiGem = typeof window.AntiGem !== 'undefined' && window.AntiGem.storage;
  var $ = function (id) { return document.getElementById(id); };

  // ---------------------------------------------------------------- storage

  function persist() {
    if (hasAntiGem) {
      window.AntiGem.storage.save(state);
      return;
    }
    try { localStorage.setItem('achiva-state', JSON.stringify(state)); } catch (e) {}
  }

  function load() {
    if (hasAntiGem) {
      return window.AntiGem.storage.load().then(function (data) {
        hydrate(data);
        renderAll();
      });
    }
    var saved = null;
    try { saved = JSON.parse(localStorage.getItem('achiva-state') || '{}'); } catch (e) {}
    hydrate(saved);
    renderAll();
    return Promise.resolve();
  }

  /** Re-draws every card from the loaded state (called once after boot, after hydrate). */
  function renderAll() {
    renderTasks();
    renderStreak();
    if (!running) {
      remaining = state.timerSeconds;
      renderTimer();
    }
  }

  function hydrate(data) {
    if (!data) return;
    state.tasks = Array.isArray(data.tasks) ? data.tasks : [];
    state.streak = typeof data.streak === 'number' ? data.streak : 0;
    state.lastDoneDate = typeof data.lastDoneDate === 'string' ? data.lastDoneDate : null;
    state.sessionsToday = typeof data.sessionsToday === 'number' ? data.sessionsToday : 0;
    state.timerSeconds = typeof data.timerSeconds === 'number' ? data.timerSeconds : 25 * 60;
  }

  // ---------------------------------------------------------------- account

  function showAccount() {
    var line = $('account-line');
    try {
      if (hasAntiGem && window.AntiGem.getAccount) {
        var acc = window.AntiGem.getAccount();
        line.textContent = acc.email || 'guest@local';
      } else {
        line.textContent = 'browser mode (no bridge)';
      }
    } catch (e) {
      line.textContent = 'guest@local';
    }
  }

  // ---------------------------------------------------------------- tasks

  function renderTasks() {
    var list = $('task-list');
    list.innerHTML = '';
    state.tasks.forEach(function (task) {
      var li = document.createElement('li');
      li.className = 'task-item' + (task.done ? ' done' : '');

      var checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.checked = task.done;
      checkbox.addEventListener('change', function () {
        task.done = checkbox.checked;
        if (task.done) bumpStreak();
        persist();
        renderTasks();
        renderStreak();
      });

      var label = document.createElement('span');
      label.className = 'label';
      label.textContent = task.label;

      var del = document.createElement('button');
      del.className = 'delete';
      del.textContent = '✕';
      del.addEventListener('click', function () {
        state.tasks = state.tasks.filter(function (t) { return t.id !== task.id; });
        persist();
        renderTasks();
        renderProgress();
      });

      li.appendChild(checkbox);
      li.appendChild(label);
      li.appendChild(del);
      list.appendChild(li);
    });
    renderProgress();
  }

  function renderProgress() {
    var done = state.tasks.filter(function (t) { return t.done; }).length;
    $('task-progress').textContent = done + ' / ' + state.tasks.length + ' done';
  }

  function todayKey() {
    var d = new Date();
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  }

  function bumpStreak() {
    var today = todayKey();
    if (state.lastDoneDate === today) return;
    var yesterday = new Date(Date.now() - 86400000);
    var yKey = yesterday.getFullYear() + '-' + String(yesterday.getMonth() + 1).padStart(2, '0') + '-' + String(yesterday.getDate()).padStart(2, '0');
    state.streak = state.lastDoneDate === yKey ? state.streak + 1 : 1;
    state.lastDoneDate = today;
  }

  function renderStreak() {
    $('streak-chip').textContent = '🔥 ' + state.streak;
  }

  $('task-form').addEventListener('submit', function (ev) {
    ev.preventDefault();
    var input = $('task-input');
    var label = input.value.trim();
    if (!label) return;
    state.tasks.push({ id: 't' + Date.now(), label: label, done: false });
    input.value = '';
    persist();
    renderTasks();
  });

  // ---------------------------------------------------------------- timer

  var remaining = 25 * 60;
  var running = false;
  var tickHandle = null;

  function renderTimer() {
    var m = Math.floor(remaining / 60);
    var s = remaining % 60;
    $('timer-display').textContent = String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
  }

  function stopTick() {
    if (tickHandle) { clearInterval(tickHandle); tickHandle = null; }
    running = false;
    $('timer-display').classList.remove('running');
    var startBtn = $('btn-start');
    startBtn.textContent = 'Start';
    startBtn.classList.remove('paused');
  }

  function startTick() {
    stopTick();
    running = true;
    $('timer-display').classList.add('running');
    $('btn-start').textContent = 'Pause';
    $('btn-start').classList.add('paused');
    tickHandle = setInterval(function () {
      remaining -= 1;
      renderTimer();
      if (remaining <= 0) {
        stopTick();
        remaining = state.timerSeconds;
        state.sessionsToday += 1;
        persist();
        renderTimer();
        logBridge('✅ Focus session complete (' + state.sessionsToday + ' today)');
      }
    }, 1000);
  }

  $('btn-start').addEventListener('click', function () {
    if (running) stopTick(); else startTick();
  });

  $('btn-reset').addEventListener('click', function () {
    stopTick();
    remaining = state.timerSeconds;
    renderTimer();
  });

  // ---------------------------------------------------------------- bridge demo

  function logBridge(text) {
    var el = $('bridge-log');
    var stamp = new Date().toLocaleTimeString();
    el.textContent = '[' + stamp + '] ' + text + '\n' + el.textContent;
  }

  $('btn-reload').addEventListener('click', function () {
    if (hasAntiGem && window.AntiGem.reload) window.AntiGem.reload();
    else window.location.reload();
  });

  $('btn-ping').addEventListener('click', function () {
    if (!hasAntiGem || !window.AntiGem.callTool) {
      logBridge('✖ No AntiGem bridge in plain browser mode');
      return;
    }
    logBridge('… calling notes MCP tools/list …');
    window.AntiGem.callTool('list_notes', {}, 'notes')
      .then(function (result) {
        var preview = typeof result === 'string' ? result : JSON.stringify(result);
        logBridge('✅ MCP answered: ' + String(preview).slice(0, 220));
      })
      .catch(function (err) {
        logBridge('✖ MCP error: ' + err.message);
      });
  });

  // ---------------------------------------------------------------- boot

  renderTimer();
  renderStreak();
  load().then(showAccount);
})();
