"use strict";

// Every server-supplied value is rendered with textContent, never as HTML.
const $ = (id) => document.getElementById(id);
const state = {
  csrf: null,
  view: "overview",
  directory: "",
  editor: null,
  toastTimer: null,
  filesRequest: 0,
  editorRequest: 0,
  editorRevision: 0,
  setupCheckNonce: null,
  selectedFiles: new Set(),
  fileEntries: [],
  metricRange: "realtime",
  metricName: "cpuPercent",
  metricSamples: [],
  metricWindow: null,
  metricRequest: 0,
  consoleCursor: null,
  consoleLoading: false,
  backupJobId: null,
  backupPolling: false,
  backupJobTerminal: false,
};

// The setup-check token stays in the URL fragment, never in an HTTP request URL.
// Remove it from the address bar before making any other request.
const setupCheckParams = new URLSearchParams(window.location.hash.slice(1));
const setupCheckValue = setupCheckParams.get("nab-check");
if (setupCheckValue !== null) {
  window.history.replaceState(null, "", window.location.pathname + window.location.search);
  if (/^[A-Za-z0-9_-]{16,128}$/.test(setupCheckValue)) state.setupCheckNonce = setupCheckValue;
}

if (window.location.protocol === "https:") {
  $("transport-warning").hidden = true;
  document.querySelector(".server-pill small").textContent = "HTTPS connection";
  $("access-heading").textContent = "Connected over HTTPS";
  $("access-help").textContent = `This panel is open at ${window.location.origin}. Run nab relay status in the server console to check its pairing. An SSH tunnel remains available as a local fallback.`;
  $("access-footnote").textContent = "Your HTTPS relay operator can observe panel traffic. Keep your panel password private.";
} else {
  document.querySelector(".server-pill small").textContent = "HTTP connection · unencrypted";
  if (!["localhost", "127.0.0.1", "::1", "[::1]"].includes(window.location.hostname)) {
    $("access-heading").textContent = "This public HTTP connection is unencrypted";
    $("access-help").textContent = "Passwords, session cookies and commands can be read by anyone on the network path. For private access, use an SSH tunnel instead.";
  }
}

function node(tag, className, content) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (content !== undefined) element.textContent = String(content);
  return element;
}

function toast(message, error = false) {
  const target = $("toast");
  clearTimeout(state.toastTimer);
  target.textContent = String(message);
  target.classList.toggle("error", error);
  target.setAttribute("role", error ? "alert" : "status");
  target.hidden = false;
  state.toastTimer = setTimeout(() => { target.hidden = true; }, error ? 8500 : 4500);
}

function fail(error) {
  toast(error instanceof Error ? error.message : String(error), true);
}

async function request(path, options = {}) {
  const method = options.method || "GET";
  const headers = new Headers(options.headers || {});
  headers.set("Accept", "application/json");
  if (!["GET", "HEAD"].includes(method.toUpperCase()) && state.csrf) {
    headers.set("X-CSRF-Token", state.csrf);
  }
  let body = options.body;
  if (body !== undefined && !(body instanceof Blob) && typeof body !== "string") {
    headers.set("Content-Type", "application/json");
    body = JSON.stringify(body);
  }
  let response;
  try {
    response = await fetch(path, {method, headers, body, credentials: "same-origin", cache: "no-store"});
  } catch {
    throw new Error("Could not reach the panel. Check the server connection and try again.");
  }
  const result = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 && !["/api/login", "/api/session", "/api/setup-check"].includes(path)) {
      state.csrf = null;
      showAuth(true);
    }
    throw new Error(result.error || `Request failed (${response.status})`);
  }
  return result;
}

async function reportSetupCheck(step) {
  if (!state.setupCheckNonce) return;
  await request("/api/setup-check", {
    method: "POST", body: {nonce: state.setupCheckNonce, step},
  });
  if (step === "login") state.setupCheckNonce = null;
}

function showAuth(configured) {
  $("auth-view").hidden = false;
  $("app-view").hidden = true;
  $("setup-form").hidden = configured;
  $("login-form").hidden = !configured;
  $(configured ? "login-form" : "setup-form").querySelector("input")?.focus();
}

function showApp() {
  $("auth-view").hidden = true;
  $("app-view").hidden = false;
  openView(state.view).catch(fail);
}

async function boot() {
  if (state.setupCheckNonce) {
    try { await reportSetupCheck("reach"); }
    catch (error) { fail(error); }
  }
  try {
    const status = await request("/api/status");
    if (!status.configured) { showAuth(false); return; }
    try {
      const session = await request("/api/session");
      state.csrf = session.csrf;
      showApp();
      if (state.setupCheckNonce) {
        try { await reportSetupCheck("login"); }
        catch (error) { fail(error); }
      }
    } catch (error) {
      showAuth(true);
      if (!String(error.message).includes("Sign in")) fail(error);
    }
  } catch (error) {
    showAuth(true);
    fail(error);
  }
}

function setBusy(button, busy, text) {
  if (!button) return;
  if (busy) {
    button.dataset.previousText = button.textContent;
    button.disabled = true;
    if (text) button.textContent = text;
  } else {
    button.disabled = false;
    if (button.dataset.previousText) button.textContent = button.dataset.previousText;
    delete button.dataset.previousText;
  }
}

function field(label, value = "", options = {}) {
  const wrapper = node("label", "", label);
  const input = node(options.select ? "select" : "input");
  if (options.select) {
    for (const [key, text] of options.select) {
      const option = node("option", "", text);
      option.value = key;
      input.append(option);
    }
  } else {
    input.type = options.type || "text";
    input.value = value;
    input.autocomplete = "off";
    input.spellcheck = false;
  }
  input.required = options.required !== false;
  if (options.maxLength) input.maxLength = options.maxLength;
  if (options.placeholder) input.placeholder = options.placeholder;
  wrapper.append(input);
  return {wrapper, input};
}

function review({eyebrow = "REVIEW ACTION", title, description, confirm = "Confirm", danger = false, fields = []}) {
  const dialog = $("action-dialog");
  $("dialog-eyebrow").textContent = eyebrow;
  $("dialog-title").textContent = title;
  $("dialog-description").textContent = description;
  const container = $("dialog-fields");
  container.replaceChildren(...fields.map(item => item.wrapper));
  const button = $("dialog-confirm");
  button.textContent = confirm;
  button.className = danger ? "button danger" : "button primary";
  const cancel = dialog.querySelector('button[value="cancel"]');
  cancel.type = "button";
  cancel.onclick = () => dialog.close("cancel");
  dialog.returnValue = "";
  dialog.showModal();
  (fields[0]?.input || button).focus();
  return new Promise(resolve => {
    dialog.addEventListener("close", () => {
      resolve(dialog.returnValue === "confirm" ? fields.map(item => item.input.value) : null);
    }, {once: true});
  });
}

function childPath(parent, name) {
  const part = name.trim();
  if (!part || part === "." || part === ".." || /[\\/\x00-\x1f:]/.test(part)) {
    throw new Error("Enter one file or folder name without slashes or special path characters.");
  }
  return parent ? `${parent}/${part}` : part;
}

function formatSize(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return "—";
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes;
  let index = -1;
  do { value /= 1024; index++; } while (value >= 1024 && index < units.length - 1);
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[index]}`;
}

function formatDate(value) {
  const date = new Date(value);
  return Number.isNaN(date.valueOf()) ? "—" : date.toLocaleString(undefined, {dateStyle: "medium", timeStyle: "short"});
}

async function openView(view) {
  if (!["overview", "files", "console", "players", "backups"].includes(view)) return;
  if (state.view === "files" && view !== "files" && !(await canCloseEditor())) return;
  if (state.view === "files" && view !== "files") closeEditorNow();
  state.view = view;
  for (const section of document.querySelectorAll(".view")) section.hidden = section.id !== `view-${view}`;
  for (const button of document.querySelectorAll(".nav-item[data-view]")) {
    const active = button.dataset.view === view;
    button.classList.toggle("active", active);
    if (active) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  }
  $("current-section").textContent = view[0].toUpperCase() + view.slice(1);
  if (view === "overview") await loadMetrics();
  if (view === "files") await loadFiles(state.directory);
  if (view === "console") await loadLogs();
  if (view === "players") await loadPlayers();
  if (view === "backups") await loadBackups();
}

async function refreshView() {
  switch (state.view) {
    case "overview": await loadMetrics(); break;
    case "files": await loadFiles(state.directory); break;
    case "console": await loadLogs(); break;
    case "players": await loadPlayers(); break;
    case "backups": await loadBackups(); break;
  }
}

const metricLabels = {
  cpuPercent: "CPU usage", tps: "Ticks per second", mspt: "Time per tick",
  memoryUsedBytes: "Java memory",
};
const metricDurations = {
  realtime: 60_000, "1m": 60_000, "5m": 300_000, "10m": 600_000,
  "30m": 1_800_000, "1h": 3_600_000, "12h": 43_200_000,
  "1d": 86_400_000, "1w": 604_800_000,
};

function metricText(name, value) {
  if (!Number.isFinite(value)) return "—";
  if (name === "cpuPercent") return `${Math.round(value)}%`;
  if (name === "tps") return value.toFixed(1);
  if (name === "mspt") return `${value.toFixed(1)} ms`;
  return formatSize(value);
}

function shortTime(value, longRange = false) {
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return "—";
  return date.toLocaleString(undefined, longRange
    ? {month: "short", day: "numeric", hour: "2-digit", minute: "2-digit"}
    : {hour: "2-digit", minute: "2-digit", second: "2-digit"});
}

async function loadMetrics() {
  const serial = ++state.metricRequest;
  const range = state.metricRange;
  const data = await request(`/api/metrics?range=${encodeURIComponent(range)}`);
  if (serial !== state.metricRequest || range !== state.metricRange) return;
  state.metricSamples = Array.isArray(data.samples) ? data.samples : [];
  state.metricWindow = data;
  state.lastMetricPoll = Date.now();
  renderMetrics();
}

function renderMetrics() {
  const samples = state.metricSamples;
  const latest = state.metricWindow?.latest || samples.at(-1);
  $("stat-cpu").textContent = metricText("cpuPercent", latest?.cpuPercent == null ? NaN : Number(latest.cpuPercent));
  $("stat-tps").textContent = metricText("tps", latest?.tps == null ? NaN : Number(latest.tps));
  $("stat-mspt").textContent = metricText("mspt", latest?.mspt == null ? NaN : Number(latest.mspt));
  $("stat-memory").textContent = metricText("memoryUsedBytes", latest?.memoryUsedBytes == null ? NaN : Number(latest.memoryUsedBytes));
  $("stat-memory-limit").textContent = latest?.memoryMaxBytes ? `of ${formatSize(latest.memoryMaxBytes)} allocated` : "Heap in use";
  const players = latest?.players == null ? NaN : Number(latest.players);
  $("metric-players").textContent = `${Number.isFinite(players) ? players : "—"} ${players === 1 ? "player" : "players"} online`;
  $("metric-title").textContent = metricLabels[state.metricName];
  $("metric-updated").textContent = latest ? `Last sample ${shortTime(latest.timestamp)}` : "Waiting for first sample";
  const coverage = Number(state.metricWindow?.coverageStart);
  $("metric-coverage").textContent = coverage > 0
    ? `History available since ${shortTime(coverage, true)}`
    : "History begins when the panel starts collecting.";
  if (state.metricWindow?.storageError) $("metric-coverage").textContent += " · History storage unavailable";
  for (const button of document.querySelectorAll("[data-metric]")) {
    const active = button.dataset.metric === state.metricName;
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", String(active));
  }
  for (const button of document.querySelectorAll("[data-range]")) {
    const active = button.dataset.range === state.metricRange;
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", String(active));
  }
  renderMetricChart();
}

function renderMetricChart() {
  const name = state.metricName;
  const values = state.metricSamples.filter(item => item[name] != null && Number.isFinite(Number(item[name])) && Number.isFinite(Number(item.timestamp)));
  const now = Date.now();
  const duration = metricDurations[state.metricRange];
  const from = now - duration;
  const longRange = duration >= 3_600_000;
  $("chart-start").textContent = shortTime(from, longRange);
  $("chart-end").textContent = shortTime(now, longRange);
  const maxObserved = Math.max(0, ...values.map(item => Number(item[name])));
  const upper = name === "tps" ? 20 : name === "cpuPercent" ? Math.max(100, Math.ceil(maxObserved / 20) * 20)
    : name === "mspt" ? Math.max(50, Math.ceil(maxObserved / 10) * 10)
    : Math.max(Number(values.at(-1)?.memoryMaxBytes) || 0, maxObserved * 1.1, 1);
  $("chart-max").textContent = metricText(name, upper);
  $("chart-mid").textContent = metricText(name, upper / 2);
  $("chart-min").textContent = metricText(name, 0);
  const plotted = values.filter(item => item.timestamp >= from && item.timestamp <= now + 1000);
  $("chart-empty").hidden = plotted.length > 0;
  $("metric-chart").setAttribute("aria-label", `${metricLabels[name]} over the selected ${state.metricRange} range`);
  if (!plotted.length) {
    $("chart-line").setAttribute("d", "");
    $("chart-area").setAttribute("d", "");
    state.chartPoints = [];
    return;
  }
  const points = plotted.map(item => ({x: Math.max(0, Math.min(900, (item.timestamp - from) / duration * 900)),
    y: Math.max(0, Math.min(240, 240 - Number(item[name]) / upper * 240)), sample: item}));
  state.chartPoints = points;
  const expectedSpacing = {realtime: 1000, "1m": 1000, "5m": 1000, "10m": 2000,
    "30m": 5000, "1h": 10_000, "12h": 120_000, "1d": 180_000, "1w": 1_200_000}[state.metricRange];
  const groups = [];
  for (const point of points) {
    if (!groups.length || point.sample.timestamp - groups.at(-1).at(-1).sample.timestamp > expectedSpacing * 2.5) groups.push([]);
    groups.at(-1).push(point);
  }
  const shape = group => group.map((point, index) => `${index ? "L" : "M"}${point.x.toFixed(1)},${point.y.toFixed(1)}`).join(" ");
  $("chart-line").setAttribute("d", groups.map(shape).join(" "));
  $("chart-area").setAttribute("d", groups.map(group => `${shape(group)} L${group.at(-1).x.toFixed(1)},240 L${group[0].x.toFixed(1)},240 Z`).join(" "));
}

function renderBreadcrumbs() {
  const container = $("file-breadcrumbs");
  const segments = state.directory ? state.directory.split("/") : [];
  container.replaceChildren();
  const root = node("button", "crumb", "Server files");
  root.type = "button";
  root.addEventListener("click", () => loadFiles("").catch(fail));
  container.append(root);
  segments.forEach((segment, index) => {
    container.append(node("span", "crumb-separator", "/"));
    const path = segments.slice(0, index + 1).join("/");
    const button = node("button", "crumb", segment);
    button.type = "button";
    button.title = segment;
    button.addEventListener("click", () => loadFiles(path).catch(fail));
    container.append(button);
  });
}

async function loadFiles(directory) {
  const changingFolder = directory !== state.directory;
  if (changingFolder && !(await canCloseEditor())) return;
  const approvedEditor = state.editor;
  const approvedRevision = state.editorRevision;
  const requestNumber = ++state.filesRequest;
  const data = await request(`/api/files?path=${encodeURIComponent(directory)}`);
  if (requestNumber !== state.filesRequest) return;
  if (changingFolder && (state.editor !== approvedEditor || state.editorRevision !== approvedRevision)
      && !(await canCloseEditor())) return;
  if (requestNumber !== state.filesRequest) return;
  state.directory = directory;
  if (changingFolder) { closeEditorNow(); state.selectedFiles.clear(); }
  renderBreadcrumbs();
  renderFiles(Array.isArray(data.entries) ? data.entries : []);
}

function actionButton(text, action, options = {}) {
  const button = node("button", `button small ${options.danger ? "danger" : "subtle"}`, text);
  button.type = "button";
  button.title = options.title || text;
  button.addEventListener("click", event => {
    event.stopPropagation();
    Promise.resolve().then(action).catch(fail);
  });
  return button;
}

function renderFiles(entries) {
  state.fileEntries = entries;
  const visible = new Set(entries.map(entry => entry.path));
  for (const path of state.selectedFiles) if (!visible.has(path)) state.selectedFiles.delete(path);
  const rows = entries.map(entry => {
    const row = node("tr");
    row.dataset.path = entry.path;
    row.classList.toggle("selected", state.selectedFiles.has(entry.path));
    const selectCell = node("td", "select-cell");
    const checkbox = node("input");
    checkbox.type = "checkbox";
    checkbox.checked = state.selectedFiles.has(entry.path);
    checkbox.setAttribute("aria-label", `Select ${entry.directory ? "folder" : "file"} ${entry.name}`);
    checkbox.addEventListener("change", () => {
      if (checkbox.checked) state.selectedFiles.add(entry.path);
      else state.selectedFiles.delete(entry.path);
      row.classList.toggle("selected", checkbox.checked);
      renderSelection();
    });
    selectCell.append(checkbox);
    const nameCell = node("td");
    const name = node("button", `file-name${entry.directory ? " folder" : ""}`);
    name.type = "button";
    name.title = entry.path;
    name.append(node("span", "file-glyph", entry.directory ? "▰" : "▤"), node("span", "", entry.name));
    name.addEventListener("click", () => (entry.directory ? loadFiles(entry.path) : openEditor(entry.path)).catch(fail));
    nameCell.append(name);
    const size = node("td", "", entry.directory ? "Folder" : formatSize(entry.size));
    const changed = node("td", "", formatDate(entry.modifiedAt));
    const actions = node("td");
    const group = node("div", "row-actions");
    if (!entry.directory) {
      group.append(actionButton("Download", () => download(`/api/download?path=${encodeURIComponent(entry.path)}`, entry.name)));
    }
    group.append(actionButton("Move", () => moveFile(entry.path)));
    if (entry.directory || entry.name.toLowerCase().endsWith(".zip")) {
      group.append(actionButton(entry.directory ? "Zip" : "Unzip", () => entry.directory ? zipFile(entry.path) : unzipFile(entry.path)));
    }
    group.append(actionButton("Delete", () => deleteFile(entry.path), {danger: true}));
    actions.append(group);
    row.append(selectCell, nameCell, size, changed, actions);
    return row;
  });
  $("file-list").replaceChildren(...rows);
  $("files-empty").hidden = entries.length > 0;
  renderSelection();
}

function renderSelection() {
  const count = state.selectedFiles.size;
  $("selection-bar").hidden = count === 0;
  $("selection-count").textContent = `${count} ${count === 1 ? "item" : "items"} selected`;
  const all = $("select-all-files");
  all.disabled = state.fileEntries.length === 0;
  all.checked = count > 0 && count === state.fileEntries.length;
  all.indeterminate = count > 0 && count < state.fileEntries.length;
}

function selectedPaths() { return [...state.selectedFiles]; }

async function zipSelection() {
  const paths = selectedPaths();
  if (!paths.length) return;
  const defaultName = childPath(state.directory, `selected-${new Date().toISOString().slice(0, 10)}.zip`);
  const archive = field("New ZIP path from server root", defaultName, {maxLength: 1024});
  const answer = await review({title: `Archive ${paths.length} selected ${paths.length === 1 ? "item" : "items"}?`,
    description: "The selected files and folders will be copied into one ZIP archive. Originals stay in place.",
    confirm: "Create ZIP", fields: [archive]});
  if (!answer) return;
  await request("/api/files/zip", {method: "POST", body: {paths, archive: answer[0].trim()}});
  toast("ZIP archive created.");
  state.selectedFiles.clear();
  await loadFiles(state.directory);
}

async function downloadSelection() {
  const paths = selectedPaths();
  if (!paths.length) return;
  const button = $("bulk-download");
  setBusy(button, true, "Preparing TAR…");
  try {
    const response = await fetch("/api/files/tar", {method: "POST", credentials: "same-origin", cache: "no-store",
      headers: {"Accept": "application/octet-stream", "Content-Type": "application/json", "X-CSRF-Token": state.csrf},
      body: JSON.stringify({paths})});
    if (!response.ok) {
      const result = await response.json().catch(() => ({}));
      if (response.status === 401) { state.csrf = null; showAuth(true); }
      throw new Error(result.error || `Download failed (${response.status})`);
    }
    const blob = await response.blob();
    const address = URL.createObjectURL(blob);
    const link = node("a");
    link.href = address;
    link.download = "selected-server-files.tar";
    link.hidden = true;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(address), 60_000);
    toast(`${paths.length} selected ${paths.length === 1 ? "item" : "items"} downloaded as TAR.`);
  } finally { setBusy(button, false); }
}

async function deleteSelection() {
  const paths = selectedPaths();
  if (!paths.length) return;
  const preview = paths.slice(0, 4).join(", ") + (paths.length > 4 ? ` and ${paths.length - 4} more` : "");
  const answer = await review({eyebrow: "PERMANENT CHANGE", title: `Delete ${paths.length} selected ${paths.length === 1 ? "item" : "items"}?`,
    description: `This removes ${preview}, including the contents of selected folders. There is no undo in the panel.`,
    confirm: "Delete selected", danger: true});
  if (!answer) return;
  const closesEditor = state.editor && paths.some(path => state.editor.path === path || state.editor.path.startsWith(`${path}/`));
  if (closesEditor && !(await canCloseEditor())) return;
  await request("/api/files/bulk", {method: "DELETE", body: {paths, confirmPaths: paths}});
  if (closesEditor) closeEditorNow();
  state.selectedFiles.clear();
  toast(`${paths.length} ${paths.length === 1 ? "item" : "items"} deleted.`);
  await loadFiles(state.directory);
}

async function createFile(directory) {
  const name = field("Name", "", {maxLength: 255, placeholder: directory ? "new-folder" : "new-file.txt"});
  const answer = await review({title: directory ? "Create a folder" : "Create a file", description: `It will be added inside ${state.directory || "the server root"}.`, confirm: "Create", fields: [name]});
  if (!answer) return;
  const path = childPath(state.directory, answer[0]);
  await request("/api/files", {method: "POST", body: {path, directory}});
  toast(`${directory ? "Folder" : "File"} created.`);
  await loadFiles(state.directory);
  if (!directory) await openEditor(path);
}

async function moveFile(from) {
  const destination = field("New path from server root", from, {maxLength: 1024});
  const answer = await review({title: "Move or rename", description: `Move ${from} to a new path. The destination must not already exist.`, confirm: "Move", fields: [destination]});
  if (!answer) return;
  const to = answer[0].trim();
  if (to === from) { toast("Choose a different destination.", true); return; }
  const closesEditor = state.editor && (state.editor.path === from || state.editor.path.startsWith(`${from}/`));
  if (closesEditor && !(await canCloseEditor())) return;
  const approvedEditor = state.editor;
  if (closesEditor) { $("editor-text").readOnly = true; $("save-editor").disabled = true; }
  try {
    await request("/api/move", {method: "POST", body: {from, to}});
    if (closesEditor && state.editor === approvedEditor) closeEditorNow();
  } finally {
    $("editor-text").readOnly = false;
    $("save-editor").disabled = false;
  }
  toast("File moved.");
  await loadFiles(state.directory);
}

async function deleteFile(path) {
  const answer = await review({eyebrow: "PERMANENT CHANGE", title: "Delete this item?", description: `This removes ${path} and, if it is a folder, everything inside it. There is no undo in the panel.`, confirm: "Delete", danger: true});
  if (!answer) return;
  const closesEditor = state.editor && (state.editor.path === path || state.editor.path.startsWith(`${path}/`));
  if (closesEditor && !(await canCloseEditor())) return;
  const approvedEditor = state.editor;
  if (closesEditor) { $("editor-text").readOnly = true; $("save-editor").disabled = true; }
  try {
    await request(`/api/file?path=${encodeURIComponent(path)}`, {method: "DELETE", headers: {"X-Confirm-Path": path}});
    if (closesEditor && state.editor === approvedEditor) closeEditorNow();
  } finally {
    $("editor-text").readOnly = false;
    $("save-editor").disabled = false;
  }
  toast(`${path} deleted.`);
  await loadFiles(state.directory);
}

async function zipFile(source = "") {
  const sourceField = field("Source path from server root", source, {maxLength: 1024});
  const archiveField = field("New archive path", source ? `${source}.zip` : "", {maxLength: 1024, placeholder: "archives/server-files.zip"});
  const answer = await review({title: "Create an archive", description: "Compress a file or folder into a new ZIP file. The archive must not already exist.", confirm: "Create ZIP", fields: [sourceField, archiveField]});
  if (!answer) return;
  await request("/api/zip", {method: "POST", body: {source: answer[0].trim(), archive: answer[1].trim()}});
  toast("ZIP archive created.");
  await loadFiles(state.directory);
}

async function unzipFile(archive = "") {
  const archiveField = field("ZIP archive path", archive, {maxLength: 1024});
  const destinationField = field("New destination folder", archive ? archive.replace(/\.zip$/i, "") : "", {maxLength: 1024, placeholder: "restored-files"});
  const answer = await review({title: "Extract an archive", description: "Extract this ZIP into a new folder. The destination must not already exist.", confirm: "Extract", fields: [archiveField, destinationField]});
  if (!answer) return;
  await request("/api/unzip", {method: "POST", body: {archive: answer[0].trim(), destination: answer[1].trim()}});
  toast("Archive extracted.");
  await loadFiles(state.directory);
}

async function uploadFile(file) {
  if (!file) return;
  if (file.size > 128 * 1024 * 1024) { toast("Files must be 128 MB or smaller.", true); return; }
  const path = childPath(state.directory, file.name);
  const answer = await review({title: "Upload this file?", description: `${file.name} (${formatSize(file.size)}) will be saved as ${path}. Existing files will not be overwritten.`, confirm: "Upload"});
  if (!answer) return;
  const button = $("upload-file");
  setBusy(button, true, "Uploading…");
  try {
    await request(`/api/upload?path=${encodeURIComponent(path)}`, {method: "POST", body: file});
    toast(`${file.name} uploaded.`);
    await loadFiles(state.directory);
  } finally { setBusy(button, false); }
}

function editorDirty() {
  return state.editor && $("editor-text").value !== state.editor.original;
}

async function canCloseEditor() {
  if (!editorDirty()) return true;
  const answer = await review({eyebrow: "UNSAVED CHANGES", title: "Discard your edits?", description: `Changes to ${state.editor.path} have not been saved.`, confirm: "Discard changes", danger: true});
  return Boolean(answer);
}

function closeEditorNow() {
  state.editorRequest++;
  state.editorRevision++;
  $("editor").hidden = true;
  state.editor = null;
  $("editor-text").value = "";
  $("editor-text").readOnly = false;
}

async function openEditor(path) {
  if (state.editor?.path === path) { $("editor-text").focus(); return; }
  const requestNumber = ++state.editorRequest;
  if (!(await canCloseEditor())) return;
  const approvedEditor = state.editor;
  const approvedRevision = state.editorRevision;
  const data = await request(`/api/file?path=${encodeURIComponent(path)}`);
  if (requestNumber !== state.editorRequest || state.view !== "files") return;
  if ((state.editor !== approvedEditor || state.editorRevision !== approvedRevision)
      && !(await canCloseEditor())) return;
  if (requestNumber !== state.editorRequest || state.view !== "files") return;
  state.editor = {path, original: data.content, sha256: data.sha256};
  state.editorRevision++;
  $("editor-name").textContent = path;
  $("editor-text").value = data.content;
  $("editor-state").textContent = "No changes";
  $("editor").hidden = false;
  $("editor-text").focus();
}

async function saveEditor() {
  if (!state.editor || !editorDirty()) { toast("There are no changes to save."); return; }
  const button = $("save-editor");
  setBusy(button, true, "Saving…");
  const {path, sha256} = state.editor;
  const content = $("editor-text").value;
  try {
    const result = await request(`/api/file?path=${encodeURIComponent(path)}`, {method: "PUT", body: {content, sha256}});
    if (state.editor?.path === path) {
      state.editor.sha256 = result.sha256;
      state.editor.original = content;
      $("editor-state").textContent = editorDirty() ? "New unsaved changes" : "Saved just now";
    }
    toast("File saved.");
    await loadFilesWithoutClosing();
  } catch (error) {
    $("editor-state").textContent = "Save failed — edits are still here";
    throw error;
  } finally { setBusy(button, false); }
}

async function loadFilesWithoutClosing() {
  const data = await request(`/api/files?path=${encodeURIComponent(state.directory)}`);
  renderFiles(Array.isArray(data.entries) ? data.entries : []);
}

async function download(url, filename) {
  let response;
  try {
    response = await fetch(url, {headers: {Accept: "application/octet-stream"},
      credentials: "same-origin", cache: "no-store"});
  } catch {
    throw new Error("Could not reach the panel. Check the server connection and try again.");
  }
  if (!response.ok) {
    const result = await response.json().catch(() => ({}));
    if (response.status === 401) { state.csrf = null; showAuth(true); }
    throw new Error(result.error || `Download failed (${response.status})`);
  }
  const size = Number(response.headers.get("Content-Length"));
  if (size > 256 * 1024 * 1024) {
    await response.body?.cancel();
    const link = node("a");
    link.href = url;
    link.download = filename;
    link.target = "_blank";
    link.rel = "noopener";
    link.hidden = true;
    document.body.append(link);
    link.click();
    link.remove();
    return;
  }
  const blob = await response.blob();
  const objectUrl = URL.createObjectURL(blob);
  const link = node("a");
  link.href = objectUrl;
  link.download = filename;
  link.hidden = true;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000);
}

async function loadLogs(reset = false) {
  if (state.consoleLoading) return;
  state.consoleLoading = true;
  try {
    if (reset) state.consoleCursor = null;
    const suffix = state.consoleCursor ? `?cursor=${encodeURIComponent(state.consoleCursor)}` : "";
    const data = await request(`/api/console/output${suffix}`);
    const container = $("console-lines");
    const nearBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 80;
    if (reset || data.reset) container.replaceChildren();
    const lines = Array.isArray(data.lines) ? data.lines : [];
    for (const line of lines) {
      const text = String(line);
      const severity = /\b(?:ERROR|SEVERE|FATAL)\b/.test(text) ? "severe" : /\bWARN(?:ING)?\b/.test(text) ? "warning" : "";
      container.append(node("div", `console-line ${severity}`, text));
    }
    while (container.childElementCount > 1000) container.firstElementChild.remove();
    state.consoleCursor = data.cursor || null;
    if (!data.available && !container.childElementCount) {
      container.append(node("div", "empty-state", "The Minecraft log is not available yet. Start the server and refresh."));
    }
    if (data.available && !container.childElementCount) {
      container.append(node("div", "empty-state", "No console output yet."));
    }
    if (lines.length && container.firstElementChild?.classList.contains("empty-state")) container.firstElementChild.remove();
    if ($("console-follow").checked && nearBottom) container.scrollTop = container.scrollHeight;
    $("console-status").textContent = data.available
      ? ` Live · ${shortTime(Date.now())}${data.truncated ? " · Older lines omitted" : ""}`
      : " Waiting for latest.log";
  } finally { state.consoleLoading = false; }
}

async function runCommand(form) {
  const command = String(new FormData(form).get("command") || "").trim();
  if (!command) return;
  const button = form.querySelector("button[type=submit]");
  setBusy(button, true, "Sending…");
  try {
    const result = await request("/api/console", {method: "POST", body: {command}});
    if (!result.accepted) throw new Error("The server did not accept that command. Check spelling and the console log.");
    form.reset();
    toast("Command sent to the server.");
    setTimeout(() => loadLogs().catch(fail), 500);
  } finally { setBusy(button, false); }
}

async function loadPlayers() {
  const data = await request("/api/players");
  const players = Array.isArray(data.players) ? data.players : [];
  const rows = players.map(player => {
    const row = node("div", "list-row");
    const info = node("div");
    const title = node("strong", "", player.name);
    if (player.op) title.append(node("span", "tag", "OP"));
    info.append(title, node("small", "", player.uuid));
    const actions = node("div", "button-row");
    actions.append(actionButton("Manage", () => {
      $("player-form").elements.name.value = player.name;
      $("player-form").elements.action.focus();
      $("player-form").scrollIntoView({behavior: "smooth", block: "center"});
    }));
    row.append(info, actions);
    return row;
  });
  $("player-list").replaceChildren(...(rows.length ? rows : [node("div", "empty-state", "No players are online right now. You can still manage an offline player below.")]));
}

async function playerAction(form) {
  const data = new FormData(form);
  const name = String(data.get("name") || "").trim();
  const action = String(data.get("action") || "");
  const labels = {
    "whitelist-add": "add to the whitelist", "whitelist-remove": "remove from the whitelist",
    ban: "ban", pardon: "unban", op: "grant operator access to", deop: "remove operator access from"
  };
  if (!/^[A-Za-z0-9_]{3,16}$/.test(name) || !labels[action]) throw new Error("Enter a valid Minecraft username and action.");
  const answer = await review({title: "Review player action", description: `The server will ${labels[action]} ${name}.`, confirm: "Run action", danger: ["ban", "op", "whitelist-remove"].includes(action)});
  if (!answer) return;
  const result = await request("/api/players", {method: "POST", body: {name, action}});
  if (!result.accepted) throw new Error("The server did not accept that player action. Check the console log.");
  toast(`Player action sent for ${name}.`);
  await loadPlayers();
}

async function loadBackups() {
  const data = await request("/api/backups");
  const backups = Array.isArray(data.backups) ? data.backups : [];
  const rows = backups.map(backup => {
    const row = node("div", "list-row");
    const info = node("div");
    info.append(node("strong", "", backup.name), node("small", "", `${formatSize(backup.size)} · ${formatDate(backup.createdAt)}`));
    const actions = node("div", "button-row");
    actions.append(actionButton("Download", () => download(`/api/backups/download?name=${encodeURIComponent(backup.name)}`, backup.name)));
    actions.append(actionButton("Delete", () => deleteBackup(backup.name), {danger: true}));
    row.append(info, actions);
    return row;
  });
  $("backup-list").replaceChildren(...(rows.length ? rows : [node("div", "empty-state", "No backups yet. Create one before your next big change.")]));
  if (!state.backupJobId) {
    const current = await request("/api/backups/latest");
    if (current.job) { state.backupJobId = current.job.id; renderBackupJob(current.job); }
  }
}

function renderBackupJob(job) {
  state.backupJobTerminal = ["completed", "failed"].includes(job.phase);
  const box = $("backup-progress");
  box.hidden = false;
  const phaseNames = {queued: "Waiting to start", saving: "Saving loaded worlds", scanning: "Measuring files",
    archiving: "Creating ZIP archive", completed: "Backup ready", failed: "Backup failed"};
  $("backup-phase").textContent = phaseNames[job.phase] || "Preparing backup";
  const bar = $("backup-progress-bar");
  const total = Number(job.totalBytes);
  const done = Number(job.bytesDone);
  if (job.phase === "completed") {
    bar.value = 100;
    $("backup-percent").textContent = "100%";
    $("backup-progress-detail").textContent = `${job.backup?.name || "Archive"} · ${formatSize(job.backup?.size)}`;
  } else if (job.phase === "archiving" && total > 0) {
    const percent = Math.min(99, Math.max(0, Math.round(done / total * 100)));
    bar.value = percent;
    $("backup-percent").textContent = `${percent}%`;
    $("backup-progress-detail").textContent = `${formatSize(done)} of ${formatSize(total)} · ${job.filesDone || 0} of ${job.totalFiles || 0} files`;
  } else if (job.phase === "failed") {
    bar.removeAttribute("value");
    $("backup-percent").textContent = "Failed";
    $("backup-progress-detail").textContent = job.error || "The archive could not be completed. Check the server log.";
  } else {
    bar.removeAttribute("value");
    $("backup-percent").textContent = "…";
    $("backup-progress-detail").textContent = job.phase === "scanning"
      ? "Counting files and bytes before archiving." : "Paper is saving loaded worlds before the archive begins.";
  }
  $("create-backup").disabled = !["completed", "failed"].includes(job.phase);
}

async function pollBackupJob() {
  if (!state.backupJobId || state.backupJobTerminal || state.backupPolling || !state.csrf) return;
  state.backupPolling = true;
  try {
    const job = await request(`/api/backups/job?id=${encodeURIComponent(state.backupJobId)}`);
    const previous = $("backup-phase").textContent;
    renderBackupJob(job);
    if (job.phase === "completed" && previous !== "Backup ready") {
      toast(`${job.backup?.name || "Backup"} created.`);
      if (state.view === "backups") await loadBackups();
    }
    if (job.phase === "failed" && previous !== "Backup failed") toast(job.error || "Backup failed.", true);
  } finally { state.backupPolling = false; }
}

async function createBackup() {
  const answer = await review({title: "Create a live server archive?", description: "Paper saves loaded worlds, then this panel archives the running server's process folder. Worlds outside that folder are excluded, and files can change during copying.", confirm: "Create backup"});
  if (!answer) return;
  const button = $("create-backup");
  setBusy(button, true, "Starting…");
  try {
    const job = await request("/api/backups", {method: "POST"});
    state.backupJobId = job.id;
    state.backupJobTerminal = false;
    renderBackupJob(job);
    toast("Backup started. Progress will appear here.");
  } finally { setBusy(button, false); if (state.backupJobId && !state.backupJobTerminal) $("create-backup").disabled = true; }
}

async function deleteBackup(name) {
  const answer = await review({eyebrow: "PERMANENT CHANGE", title: "Delete this backup?", description: `${name} will be permanently removed. Download it first if you need a copy.`, confirm: "Delete backup", danger: true});
  if (!answer) return;
  await request(`/api/backups?name=${encodeURIComponent(name)}`, {method: "DELETE", headers: {"X-Confirm-Path": name}});
  toast("Backup deleted.");
  await loadBackups();
}

function listen(id, event, handler) {
  $(id).addEventListener(event, (...args) => Promise.resolve().then(() => handler(...args)).catch(fail));
}

listen("setup-form", "submit", async event => {
  event.preventDefault();
  const form = event.currentTarget;
  const button = form.querySelector("button[type=submit]");
  setBusy(button, true, "Securing…");
  try {
    const data = new FormData(form);
    await request("/api/setup", {method: "POST", body: {code: data.get("code"), password: data.get("password")}});
    form.reset();
    showAuth(true);
    toast("Password created. Sign in to open your panel.");
  } finally { setBusy(button, false); }
});

listen("login-form", "submit", async event => {
  event.preventDefault();
  const form = event.currentTarget;
  const button = form.querySelector("button[type=submit]");
  setBusy(button, true, "Signing in…");
  try {
    const data = new FormData(form);
    const login = await request("/api/login", {method: "POST", body: {password: data.get("password")}});
    state.csrf = login.csrf;
    form.reset();
    showApp();
    if (state.setupCheckNonce) {
      try { await reportSetupCheck("login"); }
      catch (error) { fail(error); }
    }
  } finally { setBusy(button, false); }
});

for (const button of document.querySelectorAll("[data-view], [data-go]")) {
  button.addEventListener("click", () => openView(button.dataset.view || button.dataset.go).catch(fail));
}
listen("refresh-view", "click", refreshView);
listen("logout", "click", async () => {
  if (!(await canCloseEditor())) return;
  const approvedEditor = state.editor;
  const approvedRevision = state.editorRevision;
  await request("/api/logout", {method: "POST"});
  state.csrf = null;
  if (state.editor === approvedEditor && state.editorRevision === approvedRevision) closeEditorNow();
  showAuth(true);
  toast(state.editor ? "Signed out. Your unsaved text remains in this tab; sign in again to save it." : "Signed out.");
});
listen("new-file", "click", () => createFile(false));
listen("new-folder", "click", () => createFile(true));
listen("upload-file", "click", () => $("upload-input").click());
listen("upload-input", "change", async event => {
  const file = event.target.files?.[0];
  event.target.value = "";
  await uploadFile(file);
});
listen("refresh-files", "click", () => loadFiles(state.directory));
listen("select-all-files", "change", event => {
  if (event.currentTarget.checked) state.fileEntries.forEach(entry => state.selectedFiles.add(entry.path));
  else state.selectedFiles.clear();
  renderFiles(state.fileEntries);
});
listen("clear-selection", "click", () => { state.selectedFiles.clear(); renderFiles(state.fileEntries); });
listen("bulk-zip", "click", zipSelection);
listen("bulk-download", "click", downloadSelection);
listen("bulk-delete", "click", deleteSelection);
listen("zip-file", "click", () => zipFile());
listen("unzip-file", "click", () => unzipFile());
listen("close-editor", "click", async () => { if (await canCloseEditor()) closeEditorNow(); });
listen("save-editor", "click", saveEditor);
listen("editor-text", "input", () => {
  state.editorRevision++;
  $("editor-state").textContent = editorDirty() ? "Unsaved changes" : "No changes";
});
listen("editor-text", "keydown", event => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
    event.preventDefault();
    saveEditor().catch(fail);
  }
});
listen("refresh-logs", "click", () => loadLogs(true));
listen("command-form", "submit", event => { event.preventDefault(); return runCommand(event.currentTarget); });
listen("refresh-players", "click", loadPlayers);
listen("player-form", "submit", event => { event.preventDefault(); return playerAction(event.currentTarget); });
listen("refresh-backups", "click", loadBackups);
listen("create-backup", "click", createBackup);
for (const button of document.querySelectorAll("[data-metric]")) {
  button.addEventListener("click", () => { state.metricName = button.dataset.metric; renderMetrics(); });
}
for (const button of document.querySelectorAll("[data-range]")) {
  button.addEventListener("click", () => {
    state.metricRange = button.dataset.range;
    renderMetrics();
    loadMetrics().catch(fail);
  });
}
$("metric-chart").addEventListener("mousemove", event => {
  const points = state.chartPoints || [];
  if (!points.length) return;
  const plot = $("metric-chart").getBoundingClientRect();
  const x = (event.clientX - plot.left) / plot.width * 900;
  const nearest = points.reduce((best, point) => Math.abs(point.x - x) < Math.abs(best.x - x) ? point : best);
  const tooltip = $("chart-tooltip");
  tooltip.textContent = `${shortTime(nearest.sample.timestamp, state.metricRange === "1d" || state.metricRange === "1w")} · ${metricText(state.metricName, Number(nearest.sample[state.metricName]))}`;
  tooltip.style.left = `${Math.max(0, Math.min(plot.width - 170, event.clientX - plot.left + 12))}px`;
  tooltip.hidden = false;
});
$("metric-chart").addEventListener("mouseleave", () => { $("chart-tooltip").hidden = true; });
setInterval(() => {
  if (!state.csrf) return;
  if (state.view === "console") loadLogs().catch(fail);
  if (state.view === "overview" && Date.now() - (state.lastMetricPoll || 0) >= (state.metricRange === "realtime" ? 1000 : 15_000)) loadMetrics().catch(fail);
  if (state.backupJobId && !state.backupJobTerminal) pollBackupJob().catch(fail);
}, 1500);
boot();
