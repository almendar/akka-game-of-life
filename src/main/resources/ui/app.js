const NODE_PALETTE = [
  "#10b981", // Emerald
  "#38bdf8", // Sky
  "#f59e0b", // Amber
  "#a78bfa", // Violet
  "#f43f5e", // Rose
  "#2dd4bf", // Teal
  "#fb923c", // Orange
  "#84cc16", // Lime
];

const state = {
  status: null,
  patterns: [],
  clusterMembers: new Map(),    // address -> ClusterMemberInfo
  managedBackends: new Map(),   // id -> ManagedBackendInfo
  epochHistory: [],             // EpochSnapshot[]
  eventLog: [],                 // EventLogEntry[]
  logFilter: "all",
  replayIndex: null,            // null => LIVE mode, number => index in epochHistory
  nodeColors: new Map(),        // address -> hex color
  prevOwners: new Map(),        // "x,y" -> nodeAddress (for rebalance animation)
  rebalancedCells: new Set(),   // "x,y"
  crashedCells: new Map(),      // "x,y" -> expiry timestamp
  gridDims: { width: 0, height: 0 },
  cellElements: new Map(),      // "x,y" -> HTMLElement
  activeLogModalId: null,
};

// DOM refs
const $ = (id) => document.getElementById(id);
const connBadge = $("conn-badge");
const kpiEpoch = $("kpi-epoch");
const kpiAlive = $("kpi-alive");
const kpiBackends = $("kpi-backends");
const kpiCrashes = $("kpi-crashes");
const btnPlayPause = $("btn-play-pause");
const btnStep = $("btn-step");
const btnCrashRandom = $("btn-crash-random");
const btnAddBackend = $("btn-add-backend");
const boardMeta = $("board-meta");
const modeBadge = $("mode-badge");
const toggleShardMap = $("toggle-shard-map");
const shardLegend = $("shard-legend");
const boardGrid = $("board-grid");
const boardEmpty = $("board-empty");
const cellInspector = $("cell-inspector");
const epochSlider = $("epoch-slider");
const scrubberLabel = $("scrubber-label");
const btnPrevEpoch = $("btn-prev-epoch");
const btnNextEpoch = $("btn-next-epoch");
const btnJumpLive = $("btn-jump-live");
const backendsList = $("backends-list");
const frontendNodeFooter = $("frontend-node-footer");
const tickSlider = $("tick-slider");
const tickValue = $("tick-value");
const toggleAutoCrash = $("toggle-auto-crash");
const crashIntervalSelect = $("crash-interval");
const resetForm = $("reset-form");
const resetPattern = $("reset-pattern");
const resetWidth = $("reset-width");
const resetHeight = $("reset-height");
const resetSeed = $("reset-seed");
const resetAutostart = $("reset-autostart");
const btnRandomSeed = $("btn-random-seed");
const eventLogEl = $("event-log");
const logFiltersEl = $("log-filters");
const logsModal = $("logs-modal");
const modalTitle = $("modal-title");
const modalLogContent = $("modal-log-content");
const btnRefreshLogs = $("btn-refresh-logs");
const btnCloseModal = $("btn-close-modal");
const toastContainer = $("toast-container");

function shortAddr(addr) {
  if (!addr) return "unassigned";
  const idx = addr.lastIndexOf("@");
  return idx >= 0 ? addr.slice(idx + 1) : addr;
}

function getNodeColor(address) {
  if (!address) return "#475569";
  if (!state.nodeColors.has(address)) {
    const color = NODE_PALETTE[state.nodeColors.size % NODE_PALETTE.length];
    state.nodeColors.set(address, color);
  }
  return state.nodeColors.get(address);
}

function showToast(msg, isError = false) {
  const el = document.createElement("div");
  el.className = `toast${isError ? " error" : ""}`;
  el.textContent = msg;
  toastContainer.appendChild(el);
  setTimeout(() => el.remove(), 3200);
}

async function apiPost(path, payload = {}) {
  try {
    const res = await fetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      showToast(data.error || `Request failed (${res.status})`, true);
      return null;
    }
    return data;
  } catch (err) {
    showToast(`Network error: ${err.message}`, true);
    return null;
  }
}

function currentSnapshot() {
  if (state.epochHistory.length === 0) return null;
  if (state.replayIndex !== null && state.replayIndex < state.epochHistory.length) {
    return state.epochHistory[state.replayIndex];
  }
  return state.epochHistory[state.epochHistory.length - 1];
}

function formatTime(ts) {
  const d = new Date(ts);
  return d.toTimeString().slice(0, 8);
}

function ensureGridStructure(width, height) {
  if (state.gridDims.width === width && state.gridDims.height === height) return;
  state.gridDims = { width, height };
  state.cellElements.clear();
  boardGrid.innerHTML = "";

  const maxBoardPx = Math.min(720, window.innerWidth - 100);
  const cellPx = Math.max(12, Math.min(44, Math.floor((maxBoardPx - (width - 1) * 3) / width)));
  boardGrid.style.gridTemplateColumns = `repeat(${width}, ${cellPx}px)`;
  boardGrid.style.gridTemplateRows = `repeat(${height}, ${cellPx}px)`;

  const frag = document.createDocumentFragment();
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const cell = document.createElement("div");
      cell.className = "cell dead";
      cell.dataset.x = String(x);
      cell.dataset.y = String(y);
      cell.setAttribute("role", "gridcell");
      cell.addEventListener("mouseenter", () => renderInspector(x, y));
      cell.addEventListener("click", () => handleCellClick(x, y));
      state.cellElements.set(`${x},${y}`, cell);
      frag.appendChild(cell);
    }
  }
  boardGrid.appendChild(frag);
}

function handleCellClick(x, y) {
  markCellCrashed(x, y);
  apiPost("/api/cells/crash", { x, y });
}

function markCellCrashed(x, y) {
  const key = `${x},${y}`;
  state.crashedCells.set(key, Date.now() + 1200);
  const el = state.cellElements.get(key);
  if (el) {
    el.classList.remove("crashed");
    void el.offsetWidth; // restart animation
    el.classList.add("crashed");
  }
}

function renderInspector(x, y) {
  const snap = currentSnapshot();
  if (!snap) return;
  const idx = y * snap.width + x;
  const cell = snap.cells[idx];
  if (!cell) return;
  const owner = shortAddr(cell.nodeAddress);
  const status = cell.alive ? "ALIVE" : "DEAD";
  cellInspector.textContent = `Cell (${x}, ${y}) · ${status} · Epoch ${snap.epoch} · Owner: ${owner} — click to inject DoCrash`;
}

function renderBoard() {
  boardGrid.classList.toggle("shard-map-on", toggleShardMap.checked);
  const snap = currentSnapshot();
  if (!snap) {
    boardEmpty.classList.remove("hidden");
    boardGrid.classList.add("hidden");
    kpiEpoch.textContent = "—";
    kpiAlive.textContent = "—";
    shardLegend.innerHTML = "";
    renderScrubber();
    return;
  }

  boardEmpty.classList.add("hidden");
  boardGrid.classList.remove("hidden");
  ensureGridStructure(snap.width, snap.height);

  let aliveCount = 0;
  const ownerCounts = new Map();
  const now = Date.now();

  for (const c of snap.cells) {
    const key = `${c.x},${c.y}`;
    if (c.alive) aliveCount++;
    if (c.nodeAddress) {
      ownerCounts.set(c.nodeAddress, (ownerCounts.get(c.nodeAddress) || 0) + 1);
    }
    const el = state.cellElements.get(key);
    if (!el) continue;

    const color = getNodeColor(c.nodeAddress);
    el.style.setProperty("--node-color", color);
    el.classList.toggle("alive", c.alive);
    el.classList.toggle("dead", !c.alive);

    if (state.replayIndex === null && state.rebalancedCells.has(key)) {
      el.classList.remove("rebalanced");
      void el.offsetWidth;
      el.classList.add("rebalanced");
    }
    const crashExp = state.crashedCells.get(key);
    if (crashExp && crashExp < now) {
      state.crashedCells.delete(key);
      el.classList.remove("crashed");
    }
  }
  state.rebalancedCells.clear();

  kpiEpoch.textContent = String(snap.epoch);
  kpiAlive.textContent = `${aliveCount}/${snap.cells.length}`;

  // Legend
  const sortedOwners = Array.from(ownerCounts.keys()).sort();
  shardLegend.innerHTML = sortedOwners
    .map((addr) => {
      const count = ownerCounts.get(addr) || 0;
      const color = getNodeColor(addr);
      return `<span class="legend-item"><span class="color-dot" style="background:${color}"></span>${shortAddr(addr)} (${count})</span>`;
    })
    .join("");

  renderScrubber();
}

function renderScrubber() {
  const total = state.epochHistory.length;
  const isLive = state.replayIndex === null;
  modeBadge.textContent = isLive ? "LIVE" : "REPLAY";
  modeBadge.className = `badge ${isLive ? "badge-live" : "badge-replay"}`;
  btnJumpLive.classList.toggle("hidden", isLive);

  if (total === 0) {
    epochSlider.disabled = true;
    epochSlider.min = "0";
    epochSlider.max = "0";
    epochSlider.value = "0";
    btnPrevEpoch.disabled = true;
    btnNextEpoch.disabled = true;
    scrubberLabel.textContent = "No snapshots yet";
    return;
  }

  const currentIdx = isLive ? total - 1 : Math.min(state.replayIndex, total - 1);
  epochSlider.disabled = total <= 1;
  epochSlider.min = "0";
  epochSlider.max = String(total - 1);
  epochSlider.value = String(currentIdx);
  btnPrevEpoch.disabled = currentIdx <= 0;
  btnNextEpoch.disabled = currentIdx >= total - 1;

  const snap = state.epochHistory[currentIdx];
  const latest = state.epochHistory[total - 1];
  scrubberLabel.textContent = `Epoch ${snap.epoch} / ${latest.epoch} (${total} buffered)`;
}

function renderStatusControls() {
  const st = state.status;
  if (!st) return;

  kpiCrashes.textContent = String(st.crashCount);
  const patObj = state.patterns.find((p) => p.id === st.boardSpec.pattern);
  const patLabel = patObj ? patObj.label : st.boardSpec.pattern;
  boardMeta.textContent = `${st.boardSpec.size ? st.boardSpec.size.width : st.boardSpec.width}×${st.boardSpec.size ? st.boardSpec.size.height : st.boardSpec.height} · ${patLabel}`;

  if (!st.started) {
    btnPlayPause.textContent = "▶ Start";
    btnPlayPause.className = "btn btn-primary";
  } else if (st.paused) {
    btnPlayPause.textContent = "▶ Resume";
    btnPlayPause.className = "btn btn-primary";
  } else {
    btnPlayPause.textContent = "⏸ Pause";
    btnPlayPause.className = "btn btn-warn-outline";
  }

  if (document.activeElement !== tickSlider) {
    tickSlider.value = String(st.tickMs);
  }
  tickValue.textContent = `${st.tickMs} ms`;
  toggleAutoCrash.checked = Boolean(st.autoCrashEnabled);
}

function renderBackends() {
  const snap = currentSnapshot();
  const totalCells = snap ? snap.cells.length : 0;
  const ownerCounts = new Map();
  if (snap) {
    for (const c of snap.cells) {
      if (c.nodeAddress) {
        ownerCounts.set(c.nodeAddress, (ownerCounts.get(c.nodeAddress) || 0) + 1);
      }
    }
  }

  // Merge managed backends and cluster backend members by address
  const byAddress = new Map();
  for (const b of state.managedBackends.values()) {
    byAddress.set(b.address, { managed: b, cluster: null });
  }
  let frontendMember = null;
  let upBackendCount = 0;

  for (const m of state.clusterMembers.values()) {
    if (m.roles.includes("backend")) {
      if (m.status === "Up" && m.reachable) upBackendCount++;
      const existing = byAddress.get(m.address) || { managed: null, cluster: null };
      existing.cluster = m;
      byAddress.set(m.address, existing);
    } else if (m.roles.includes("frontend")) {
      frontendMember = m;
    }
  }

  kpiBackends.textContent = String(upBackendCount);

  const entries = Array.from(byAddress.entries()).sort((a, b) => {
    const portA = a[1].managed ? a[1].managed.port : 99999;
    const portB = b[1].managed ? b[1].managed.port : 99999;
    return portA - portB || a[0].localeCompare(b[0]);
  });

  if (entries.length === 0) {
    backendsList.innerHTML = `<div class="board-empty">No backends running. Click <strong>+ Start Backend</strong> above.</div>`;
  } else {
    backendsList.innerHTML = "";
    for (const [address, { managed, cluster }] of entries) {
      const color = getNodeColor(address);
      const owned = ownerCounts.get(address) || 0;
      const pct = totalCells > 0 ? Math.round((owned / totalCells) * 100) : 0;
      const isUnreachable = cluster && !cluster.reachable;
      const isProcessDead = managed && ["Stopped", "Killed", "Exited"].includes(managed.status);
      const isInactive = isProcessDead && !cluster;

      const card = document.createElement("div");
      card.className = `backend-card${isUnreachable ? " unreachable" : ""}${isInactive ? " inactive" : ""}`;
      card.style.setProperty("--node-color", color);

      const clusterBadge = cluster
        ? `<span class="badge ${
            !cluster.reachable
              ? "badge-danger"
              : cluster.status === "Up"
              ? "badge-ok"
              : "badge-warn"
          }">${!cluster.reachable ? "Unreachable" : cluster.status}</span>`
        : "";

      const procBadge = managed
        ? `<span class="badge ${
            managed.status === "Running"
              ? "badge-ok"
              : managed.status === "Starting" || managed.status === "Stopping"
              ? "badge-warn"
              : managed.status === "Killed"
              ? "badge-danger"
              : "badge-muted"
          }">JVM: ${managed.status}</span>`
        : `<span class="badge badge-muted">External</span>`;

      const title = managed ? `${managed.id} · ${shortAddr(address)}` : shortAddr(address);
      const metaLeft = managed ? `PID ${managed.pid}` : "External CLI process";
      const metaRight = `${owned}/${totalCells} cells (${pct}%)`;

      card.innerHTML = `
        <div class="backend-top">
          <div class="backend-title">
            <span class="color-dot" style="background:${color}"></span>
            <span>${title}</span>
          </div>
          <div class="backend-badges">${clusterBadge}${procBadge}</div>
        </div>
        <div class="backend-meta">
          <span>${metaLeft}</span>
          <span>${metaRight}</span>
        </div>
        <div class="share-bar">
          <div class="share-bar-fill" style="width:${pct}%"></div>
        </div>
      `;

      if (managed) {
        const actions = document.createElement("div");
        actions.className = "backend-actions";
        const canControl = !isProcessDead && managed.status !== "Stopping" && managed.status !== "Killing";

        const stopBtn = document.createElement("button");
        stopBtn.className = "btn btn-xs btn-warn-outline";
        stopBtn.textContent = "Stop (SIGTERM)";
        stopBtn.disabled = !canControl;
        stopBtn.addEventListener("click", () => apiPost(`/api/backends/${managed.id}/stop`));

        const killBtn = document.createElement("button");
        killBtn.className = "btn btn-xs btn-danger-outline";
        killBtn.textContent = "Kill (SIGKILL)";
        killBtn.disabled = !canControl;
        killBtn.addEventListener("click", () => apiPost(`/api/backends/${managed.id}/kill`));

        const logsBtn = document.createElement("button");
        logsBtn.className = "btn btn-xs btn-secondary";
        logsBtn.textContent = "Logs";
        logsBtn.addEventListener("click", () => openLogsModal(managed.id));

        actions.append(stopBtn, killBtn, logsBtn);
        card.appendChild(actions);
      }

      backendsList.appendChild(card);
    }
  }

  frontendNodeFooter.textContent = frontendMember
    ? `Frontend Seed: ${shortAddr(frontendMember.address)} (${frontendMember.status})`
    : "Frontend Seed: starting…";
}

function renderEventLog() {
  const filtered =
    state.logFilter === "all"
      ? state.eventLog
      : state.eventLog.filter((e) => e.category === state.logFilter);

  const recent = filtered.slice(-120).reverse();
  eventLogEl.innerHTML = recent
    .map(
      (e) =>
        `<div class="log-row">` +
        `<span class="log-time">${formatTime(e.timestampMs)}</span>` +
        `<span class="log-cat log-cat-${e.category}">${e.category}</span>` +
        `<span class="log-msg">${escapeHtml(e.message)}</span>` +
        `</div>`
    )
    .join("");
}

function escapeHtml(str) {
  return String(str)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function populatePatterns(patterns, currentSpec) {
  state.patterns = patterns || [];
  resetPattern.innerHTML = state.patterns
    .map((p) => `<option value="${p.id}">${p.label}</option>`)
    .join("");
  if (currentSpec) {
    resetPattern.value = currentSpec.pattern || "random";
    resetWidth.value = String(currentSpec.width);
    resetHeight.value = String(currentSpec.height);
    resetSeed.value = String(currentSpec.seed);
  }
}

async function openLogsModal(backendId) {
  state.activeLogModalId = backendId;
  modalTitle.textContent = `JVM Logs — ${backendId}`;
  modalLogContent.textContent = "Loading…";
  logsModal.classList.remove("hidden");
  await refreshLogsModal();
}

async function refreshLogsModal() {
  const id = state.activeLogModalId;
  if (!id) return;
  try {
    const res = await fetch(`/api/backends/${encodeURIComponent(id)}/logs`);
    const data = await res.json();
    if (!res.ok) {
      modalLogContent.textContent = data.error || "Failed to load logs";
      return;
    }
    modalLogContent.textContent =
      data.lines && data.lines.length > 0 ? data.lines.join("\n") : "(no output yet)";
    modalLogContent.scrollTop = modalLogContent.scrollHeight;
  } catch (err) {
    modalLogContent.textContent = `Error: ${err.message}`;
  }
}

function closeLogsModal() {
  logsModal.classList.add("hidden");
  state.activeLogModalId = null;
}

function recordRebalances(snapshot) {
  const nextOwners = new Map();
  for (const c of snapshot.cells) {
    const key = `${c.x},${c.y}`;
    if (c.nodeAddress) {
      const prev = state.prevOwners.get(key);
      if (prev && prev !== c.nodeAddress) {
        state.rebalancedCells.add(key);
      }
      nextOwners.set(key, c.nodeAddress);
    }
  }
  state.prevOwners = nextOwners;
}

async function hydrateInitialState() {
  const res = await fetch("/api/state");
  const data = await res.json();
  state.status = data.status;
  populatePatterns(data.patterns, data.status.boardSpec);

  state.clusterMembers.clear();
  for (const m of data.clusterMembers || []) {
    state.clusterMembers.set(m.address, m);
    if (m.roles.includes("backend")) getNodeColor(m.address);
  }

  state.managedBackends.clear();
  for (const b of data.managedBackends || []) {
    state.managedBackends.set(b.id, b);
    getNodeColor(b.address);
  }

  state.epochHistory = data.epochHistory || [];
  if (state.epochHistory.length > 0) {
    recordRebalances(state.epochHistory[state.epochHistory.length - 1]);
    state.rebalancedCells.clear();
  }
  state.eventLog = data.eventLog || [];
  state.replayIndex = null;

  renderStatusControls();
  renderBoard();
  renderBackends();
  renderEventLog();
}

function connectSse() {
  const es = new EventSource("/api/events");

  es.onopen = () => {
    connBadge.textContent = "Connected";
    connBadge.className = "badge badge-ok";
  };

  es.onerror = () => {
    connBadge.textContent = "Reconnecting…";
    connBadge.className = "badge badge-warn";
  };

  es.addEventListener("epoch", (ev) => {
    const snap = JSON.parse(ev.data);
    recordRebalances(snap);
    const existingIdx = state.epochHistory.findIndex((e) => e.epoch === snap.epoch);
    if (existingIdx >= 0) {
      state.epochHistory[existingIdx] = snap;
    } else {
      state.epochHistory.push(snap);
      if (state.epochHistory.length > 200) {
        state.epochHistory.shift();
        if (state.replayIndex !== null && state.replayIndex > 0) {
          state.replayIndex -= 1;
        }
      }
    }
    renderBoard();
    renderBackends();
  });

  es.addEventListener("status", (ev) => {
    state.status = JSON.parse(ev.data);
    renderStatusControls();
  });

  es.addEventListener("cluster-member", (ev) => {
    const member = JSON.parse(ev.data);
    state.clusterMembers.set(member.address, member);
    if (member.roles.includes("backend")) getNodeColor(member.address);
    renderBackends();
  });

  es.addEventListener("cluster-member-removed", (ev) => {
    const { address } = JSON.parse(ev.data);
    state.clusterMembers.delete(address);
    renderBackends();
  });

  es.addEventListener("backend-process", (ev) => {
    const backend = JSON.parse(ev.data);
    state.managedBackends.set(backend.id, backend);
    getNodeColor(backend.address);
    renderBackends();
  });

  es.addEventListener("cell-crash", (ev) => {
    const crash = JSON.parse(ev.data);
    markCellCrashed(crash.x, crash.y);
  });

  es.addEventListener("reset", (ev) => {
    const { simulationId, boardSpec } = JSON.parse(ev.data);
    state.epochHistory = [];
    state.replayIndex = null;
    state.prevOwners.clear();
    state.rebalancedCells.clear();
    state.gridDims = { width: 0, height: 0 };
    if (state.status) {
      state.status.simulationId = simulationId;
      state.status.boardSpec = boardSpec;
      state.status.step = 0;
    }
    renderStatusControls();
    renderBoard();
    renderBackends();
  });

  es.addEventListener("log", (ev) => {
    const entry = JSON.parse(ev.data);
    state.eventLog.push(entry);
    if (state.eventLog.length > 200) state.eventLog.shift();
    renderEventLog();
  });
}

// Wire UI listeners
btnPlayPause.addEventListener("click", () => {
  const st = state.status;
  if (!st || !st.started) {
    apiPost("/api/simulation/start");
  } else if (st.paused) {
    apiPost("/api/simulation/resume");
  } else {
    apiPost("/api/simulation/pause");
  }
});

btnStep.addEventListener("click", () => {
  state.replayIndex = null;
  apiPost("/api/simulation/step");
});

btnCrashRandom.addEventListener("click", () => {
  apiPost("/api/cells/crash", { random: true });
});

btnAddBackend.addEventListener("click", async () => {
  btnAddBackend.disabled = true;
  const created = await apiPost("/api/backends");
  if (created) {
    showToast(`Spawning ${created.id} on port ${created.port} (PID ${created.pid})`);
  }
  setTimeout(() => {
    btnAddBackend.disabled = false;
  }, 600);
});

toggleShardMap.addEventListener("change", renderBoard);

epochSlider.addEventListener("input", () => {
  const idx = Number(epochSlider.value);
  state.replayIndex = idx >= state.epochHistory.length - 1 ? null : idx;
  renderBoard();
  renderBackends();
});

btnPrevEpoch.addEventListener("click", () => {
  if (state.epochHistory.length === 0) return;
  const current = state.replayIndex === null ? state.epochHistory.length - 1 : state.replayIndex;
  if (current > 0) {
    state.replayIndex = current - 1;
    renderBoard();
    renderBackends();
  }
});

btnNextEpoch.addEventListener("click", () => {
  if (state.epochHistory.length === 0) return;
  const current = state.replayIndex === null ? state.epochHistory.length - 1 : state.replayIndex;
  if (current < state.epochHistory.length - 1) {
    const next = current + 1;
    state.replayIndex = next >= state.epochHistory.length - 1 ? null : next;
    renderBoard();
    renderBackends();
  }
});

btnJumpLive.addEventListener("click", () => {
  state.replayIndex = null;
  renderBoard();
  renderBackends();
});

let tickDebounce = null;
tickSlider.addEventListener("input", () => {
  const ms = Number(tickSlider.value);
  tickValue.textContent = `${ms} ms`;
  clearTimeout(tickDebounce);
  tickDebounce = setTimeout(() => {
    apiPost("/api/simulation/tick", { tickMs: ms });
  }, 150);
});

function sendAutoCrashUpdate() {
  apiPost("/api/simulation/auto-crash", {
    enabled: toggleAutoCrash.checked,
    everyMs: Number(crashIntervalSelect.value),
  });
}

toggleAutoCrash.addEventListener("change", sendAutoCrashUpdate);
crashIntervalSelect.addEventListener("change", () => {
  if (toggleAutoCrash.checked) sendAutoCrashUpdate();
});

resetPattern.addEventListener("change", () => {
  const pat = state.patterns.find((p) => p.id === resetPattern.value);
  if (pat && pat.recommendedWidth && pat.recommendedHeight) {
    resetWidth.value = String(pat.recommendedWidth);
    resetHeight.value = String(pat.recommendedHeight);
  }
});

btnRandomSeed.addEventListener("click", () => {
  resetSeed.value = String(Math.floor(Math.random() * 100000));
});

resetForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  const res = await apiPost("/api/simulation/reset", {
    width: Number(resetWidth.value),
    height: Number(resetHeight.value),
    seed: Number(resetSeed.value),
    pattern: resetPattern.value,
    autoStart: resetAutostart.checked,
  });
  if (res) {
    showToast("Board reset across cluster");
  }
});

logFiltersEl.addEventListener("click", (e) => {
  const btn = e.target.closest("button[data-filter]");
  if (!btn) return;
  state.logFilter = btn.dataset.filter;
  for (const pill of logFiltersEl.querySelectorAll(".pill")) {
    pill.classList.toggle("active", pill === btn);
  }
  renderEventLog();
});

btnRefreshLogs.addEventListener("click", refreshLogsModal);
btnCloseModal.addEventListener("click", closeLogsModal);
logsModal.querySelector(".modal-backdrop").addEventListener("click", closeLogsModal);

// Boot
hydrateInitialState().then(connectSse);
