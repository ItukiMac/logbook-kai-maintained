importScripts("mirror.js");
const kai2Mirror = new Kai2Mirror({report: state => {
  void chrome.storage.local.set({kai2MirrorState: state}).catch(() => {});
}});
void chrome.storage.local.get("kai2MirrorPort").then(value => {
  if (value.kai2MirrorPort !== undefined) kai2Mirror.setPort(value.kai2MirrorPort);
}).catch(() => {});
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && changes.kai2MirrorPort) {
    try { kai2Mirror.setPort(changes.kai2MirrorPort.newValue); } catch (_) {}
  }
});
const DMM_PREFIX = "https://play.games.dmm.com/game/kancolle";
const KANCOLLE_HOST_SUFFIX = ".kancolle-server.com";
const PLUGIN_BASE = "http://127.0.0.1:8891";
const MAX_CAPTURES = 40;
const HEARTBEAT_ALARM = "bridge-heartbeat";
const HEARTBEAT_PERIOD_MINUTES = 0.5;
const FAILURE_THRESHOLD = 1;
const NOTIFICATION_ICON_DATA_URL = "icon128.png";

const KCS2_PREFIXES = [
  "/kcs2/resources/ship/",
  "/kcs2/resources/map/",
  "/kcs2/resources/gauge/",
  "/kcs2/img/common/",
  "/kcs2/img/duty/",
  "/kcs2/img/sally/"
];

const tabState = new Map();
const disabledTabs = new Set();

let pluginState = {
  status: "unknown",
  sent: 0,
  accepted: 0,
  lastError: null,
  lastHealth: null,
  monitoring: false,
  consecutiveFailures: 0,
  alerted: false,
  updatedAt: null
};

let stateLoadPromise = null;

async function ensurePluginStateLoaded() {
  if (!stateLoadPromise) {
    stateLoadPromise = (async () => {
      const stored = await chrome.storage.local.get("pluginState");
      if (stored?.pluginState && typeof stored.pluginState === "object") {
        pluginState = {
          ...pluginState,
          ...stored.pluginState
        };
      }
    })().catch(() => {});
  }
  await stateLoadPromise;
}

function nowIso() {
  return new Date().toISOString();
}

function isGamePage(url) {
  return typeof url === "string" && url.startsWith(DMM_PREFIX);
}

function parseCaptureUrl(url) {
  try {
    const u = new URL(url);
    const hostOk =
      u.hostname === "kancolle-server.com" ||
      u.hostname.endsWith(KANCOLLE_HOST_SUFFIX);
    if (!hostOk) return null;

    if (u.pathname.startsWith("/kcsapi/")) {
      return { path: u.pathname, queryString: u.search || "", family: "api" };
    }

    for (const prefix of KCS2_PREFIXES) {
      if (u.pathname.startsWith(prefix)) {
        return { path: u.pathname, queryString: u.search || "", family: "kcs2" };
      }
    }

    return null;
  } catch (_) {
    return null;
  }
}

function stateFor(tabId) {
  if (!tabState.has(tabId)) {
    tabState.set(tabId, {
      attached: false,
      requests: new Map(),
      sessions: new Set(),
      captureCount: 0,
      lastCapture: null,
      lastError: null,
      updatedAt: nowIso()
    });
  }
  return tabState.get(tabId);
}

function sourceKey(source, requestId) {
  return `${source.sessionId || "root"}:${requestId}`;
}

function sourceToDebuggee(source) {
  const d = { tabId: source.tabId };
  if (source.sessionId) d.sessionId = source.sessionId;
  return d;
}

async function setBadge(tabId, text) {
  try {
    await chrome.action.setBadgeText({ tabId, text });
  } catch (_) {}
}

async function saveState() {
  const tabs = {};
  for (const [tabId, s] of tabState.entries()) {
    tabs[String(tabId)] = {
      attached: s.attached,
      captureCount: s.captureCount,
      lastCapture: s.lastCapture,
      lastError: s.lastError,
      updatedAt: s.updatedAt
    };
  }
  pluginState.updatedAt = nowIso();
  await chrome.storage.local.set({ pocTabs: tabs, pluginState });
}

async function appendCapture(tabId, capture) {
  const s = stateFor(tabId);
  s.captureCount += 1;
  s.lastCapture = capture;
  s.updatedAt = nowIso();

  const { captures = [] } = await chrome.storage.local.get("captures");
  captures.unshift(capture);
  captures.splice(MAX_CAPTURES);
  await chrome.storage.local.set({ captures });

  await setBadge(tabId, String(Math.min(s.captureCount, 99)));
  await saveState();
}

async function recordTabError(tabId, e) {
  const s = stateFor(tabId);
  s.lastError = String(e?.message || e);
  s.updatedAt = nowIso();
  await setBadge(tabId, "!");
  await saveState();
}

async function notifyBridge(id, title, message) {
  try {
    if (id === "bridge-down") {
      await chrome.notifications.clear("bridge-up");
    } else if (id === "bridge-up") {
      await chrome.notifications.clear("bridge-down");
    }
    await chrome.notifications.create(id, {
      type: "basic",
      iconUrl: NOTIFICATION_ICON_DATA_URL,
      title,
      message,
      priority: 2
    });
  } catch (_) {}
}

function updateAcceptedFromHealth(text) {
  if (!text) return;
  pluginState.lastHealth = text;
  const m = text.match(/\baccepted=(\d+)/);
  if (m) pluginState.accepted = Number(m[1]);
}

async function markPluginSuccess(healthText = null) {
  await ensurePluginStateLoaded();
  const hadAlert = Boolean(pluginState.alerted);
  pluginState.status = "connected";
  pluginState.lastError = null;
  pluginState.consecutiveFailures = 0;
  updateAcceptedFromHealth(healthText);

  if (hadAlert) {
    pluginState.alerted = false;
    await notifyBridge(
      "bridge-up",
      "KLB",
      "航海日誌改との接続が復旧しました。"
    );
  }

  await saveState();
}

async function markPluginFailure(error, immediate = false) {
  await ensurePluginStateLoaded();
  pluginState.status = "disconnected";
  pluginState.lastError = String(error?.message || error);
  pluginState.consecutiveFailures =
    Number(pluginState.consecutiveFailures || 0) + 1;

  const shouldAlert =
    immediate || pluginState.consecutiveFailures >= FAILURE_THRESHOLD;

  if (shouldAlert && !pluginState.alerted) {
    pluginState.alerted = true;
    await notifyBridge(
      "bridge-down",
      "KLB 接続エラー",
      immediate
        ? "航海日誌改へのデータ送信に失敗しました。ログ取得状態を確認してください。"
        : "航海日誌改プラグインへ約30秒接続できません。ログ取得状態を確認してください。"
    );
  }

  await saveState();
}

async function clearMonitoringAlert() {
  await ensurePluginStateLoaded();
  pluginState.monitoring = false;
  pluginState.consecutiveFailures = 0;
  pluginState.alerted = false;
  await chrome.notifications.clear("bridge-down").catch(() => {});
  await saveState();
}

async function fetchWithTimeout(url, options = {}, timeoutMs = 2500) {
  // Enqueue without awaiting: kai2 failure/latency never delays legacy delivery.
  if (url.startsWith(PLUGIN_BASE + "/")) kai2Mirror.enqueue(url.slice(PLUGIN_BASE.length), options);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

async function pluginHealth(trackFailure = false) {
  await ensurePluginStateLoaded();
  try {
    const r = await fetchWithTimeout(
      PLUGIN_BASE + "/health",
      { cache: "no-store" }
    );
    const text = await r.text();
    if (!r.ok || !text.startsWith("OK ")) {
      throw new Error(`HTTP ${r.status}: ${text}`);
    }
    await markPluginSuccess(text);
    return true;
  } catch (e) {
    if (trackFailure && pluginState.monitoring) {
      await markPluginFailure(e, false);
    } else {
      pluginState.status = "disconnected";
      pluginState.lastError = String(e?.message || e);
      await saveState();
    }
    return false;
  }
}

async function sendHeartbeat(active) {
  await ensurePluginStateLoaded();
  pluginState.monitoring = active;

  if (!active && pluginState.alerted) {
    await clearMonitoringAlert();
  }

  try {
    const r = await fetchWithTimeout(
      PLUGIN_BASE + "/heartbeat",
      {
        method: "POST",
        headers: { "Content-Type": "text/plain; charset=UTF-8" },
        body: active ? "active=true" : "active=false",
        cache: "no-store"
      }
    );
    const text = await r.text();
    if (!r.ok || !text.startsWith("OK ")) {
      throw new Error(`HTTP ${r.status}: ${text}`);
    }

    if (active) {
      await markPluginSuccess(text);
    } else {
      pluginState.status = "connected";
      pluginState.lastError = null;
      updateAcceptedFromHealth(text);
      await saveState();
    }
    return true;
  } catch (e) {
    if (active) {
      await markPluginFailure(e, false);
    } else {
      pluginState.status = "disconnected";
      pluginState.lastError = String(e?.message || e);
      await saveState();
    }
    return false;
  }
}

async function heartbeatTick() {
  await ensurePluginStateLoaded();

  // Prefer actual CDP attachment state. The top-level tab URL can transition
  // while the Kancolle iframe/OOPIF remains the monitored source.
  let active = [...tabState.entries()].some(
    ([tabId, state]) => state.attached && !disabledTabs.has(tabId)
  );

  // On MV3 service-worker restart tabState is empty until scanTabs finishes,
  // so fall back to currently visible Kancolle tabs.
  if (!active) {
    const tabs = await chrome.tabs.query({});
    active = tabs.some(
      tab => tab.id && isGamePage(tab.url || "") && !disabledTabs.has(tab.id)
    );
  }

  await sendHeartbeat(active);
}

async function ensureHeartbeatAlarm() {
  await chrome.alarms.create(HEARTBEAT_ALARM, {
    periodInMinutes: HEARTBEAT_PERIOD_MINUTES
  });
}

function encodePacket(info, postData, result) {
  const enc = new TextEncoder();
  const fields = [
    info.method || "",
    info.path || "",
    info.queryString || "",
    postData || "",
    result?.base64Encoded ? "base64" : "",
    info.mimeType || "",
    String(info.status ?? 200),
    result?.body || ""
  ].map(s => enc.encode(s));

  let total = 4 + 4 + 8;
  for (const b of fields) total += 4 + b.length;

  const buffer = new ArrayBuffer(total);
  const bytes = new Uint8Array(buffer);
  const view = new DataView(buffer);

  view.setUint32(0, 0x4B435031, false); // KCP1
  view.setUint32(4, 2, false);          // protocol v2: encoding/mime/status support
  view.setBigUint64(8, BigInt(Date.now()), false);

  let off = 16;
  for (const b of fields) {
    view.setUint32(off, b.length, false);
    off += 4;
    bytes.set(b, off);
    off += b.length;
  }

  return buffer;
}

async function sendDirect(info, postData, result) {
  await ensurePluginStateLoaded();
  try {
    const body = encodePacket(info, postData, result);
    const r = await fetchWithTimeout(
      PLUGIN_BASE + "/ingest",
      {
        method: "POST",
        headers: { "Content-Type": "application/octet-stream" },
        body
      },
      5000
    );
    const text = await r.text();

    if (!r.ok || text.trim() !== "ok") {
      throw new Error(`HTTP ${r.status}: ${text}`);
    }

    pluginState.sent += 1;
    pluginState.monitoring = true;
    await markPluginSuccess();
    return true;
  } catch (e) {
    await markPluginFailure(e, true);
    return false;
  }
}

async function enableNetwork(debuggee) {
  await chrome.debugger.sendCommand(debuggee, "Network.enable", {
    maxTotalBufferSize: 32 * 1024 * 1024,
    maxResourceBufferSize: 16 * 1024 * 1024,
    maxPostDataSize: 4 * 1024 * 1024
  });
}

async function enableAutoAttach(debuggee) {
  await chrome.debugger.sendCommand(debuggee, "Target.setAutoAttach", {
    autoAttach: true,
    waitForDebuggerOnStart: false,
    flatten: true,
    filter: [{ type: "iframe", exclude: false }]
  });
}

async function attachTab(tabId) {
  if (disabledTabs.has(tabId)) return;
  const s = stateFor(tabId);
  if (s.attached) return;

  try {
    await chrome.debugger.attach({ tabId }, "1.3");
    s.attached = true;
    s.lastError = null;
    s.updatedAt = nowIso();
    await setBadge(tabId, "ON");

    await enableNetwork({ tabId });
    await enableAutoAttach({ tabId });
    // A successful CDP attach means this tab is actively monitored.
    // Arm the plugin watchdog immediately instead of waiting for the alarm.
    await sendHeartbeat(true);
    await saveState();
  } catch (e) {
    await recordTabError(tabId, e);
  }
}

async function detachTab(tabId, disableUntilReload = true) {
  if (disableUntilReload) disabledTabs.add(tabId);
  const s = stateFor(tabId);

  try {
    if (s.attached) await chrome.debugger.detach({ tabId });
  } catch (_) {}

  s.attached = false;
  s.requests.clear();
  s.sessions.clear();
  s.updatedAt = nowIso();
  await setBadge(tabId, "");
  await saveState();
}

async function scanTabs() {
  const tabs = await chrome.tabs.query({});
  for (const tab of tabs) {
    if (tab.id && isGamePage(tab.url || "")) {
      await attachTab(tab.id);
    }
  }
}

chrome.runtime.onInstalled.addListener(() => {
  ensureHeartbeatAlarm().catch(() => {});
  scanTabs().catch(() => {});
  heartbeatTick().catch(() => {});
});

chrome.runtime.onStartup.addListener(() => {
  ensureHeartbeatAlarm().catch(() => {});
  scanTabs().catch(() => {});
  heartbeatTick().catch(() => {});
});

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === HEARTBEAT_ALARM) {
    heartbeatTick().catch(() => {});
  }
});

chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  if (changeInfo.url || changeInfo.status === "complete") {
    if (isGamePage(tab.url || "")) {
      if (changeInfo.url) disabledTabs.delete(tabId);
      attachTab(tabId).catch(() => {});
      heartbeatTick().catch(() => {});
    } else {
      const s = tabState.get(tabId);
      if (s?.attached) detachTab(tabId, false).catch(() => {});
      heartbeatTick().catch(() => {});
    }
  }
});

chrome.tabs.onRemoved.addListener((tabId) => {
  tabState.delete(tabId);
  disabledTabs.delete(tabId);
  saveState().catch(() => {});
  heartbeatTick().catch(() => {});
});

chrome.debugger.onDetach.addListener((source, reason) => {
  if (!source.tabId) return;
  const s = stateFor(source.tabId);
  s.attached = false;
  s.requests.clear();
  s.sessions.clear();
  s.lastError = `detached: ${reason}`;
  s.updatedAt = nowIso();
  setBadge(source.tabId, "").catch(() => {});
  saveState().catch(() => {});
});

chrome.debugger.onEvent.addListener(async (source, method, params) => {
  if (!source.tabId) return;
  const s = stateFor(source.tabId);

  try {
    if (method === "Target.attachedToTarget") {
      const child = { tabId: source.tabId, sessionId: params.sessionId };
      s.sessions.add(params.sessionId);
      await enableNetwork(child);
      try { await enableAutoAttach(child); } catch (_) {}
      return;
    }

    if (method === "Target.detachedFromTarget") {
      if (params.sessionId) s.sessions.delete(params.sessionId);
      return;
    }

    if (method === "Network.requestWillBeSent") {
      const req = params.request || {};
      const capture = parseCaptureUrl(req.url || "");
      if (!capture) return;

      s.requests.set(sourceKey(source, params.requestId), {
        requestId: params.requestId,
        method: req.method || "",
        path: capture.path,
        queryString: capture.queryString,
        family: capture.family,
        postData: typeof req.postData === "string" ? req.postData : "",
        status: null,
        mimeType: ""
      });
      return;
    }

    if (method === "Network.responseReceived") {
      const info = s.requests.get(sourceKey(source, params.requestId));
      if (info) {
        info.status = params.response?.status ?? null;
        info.mimeType = params.response?.mimeType || "";
      }
      return;
    }

    if (method === "Network.loadingFailed") {
      s.requests.delete(sourceKey(source, params.requestId));
      return;
    }

    if (method === "Network.loadingFinished") {
      const key = sourceKey(source, params.requestId);
      const info = s.requests.get(key);
      if (!info) return;
      s.requests.delete(key);

      const debuggee = sourceToDebuggee(source);

      let postData = info.postData;
      if (info.method === "POST" && !postData) {
        try {
          const p = await chrome.debugger.sendCommand(
            debuggee,
            "Network.getRequestPostData",
            { requestId: params.requestId }
          );
          postData = p?.postData || "";
        } catch (_) {}
      }

      try {
        const result = await chrome.debugger.sendCommand(
          debuggee,
          "Network.getResponseBody",
          { requestId: params.requestId }
        );

        // Match the original MessageFlow behavior:
        // /kcsapi/ => text API body
        // /kcs2/ => base64 resources (images) or *.json text only
        let resourceType = "api";
        if (info.family === "kcs2") {
          if (result?.base64Encoded) {
            resourceType = "image";
          } else if (info.path.endsWith(".json")) {
            resourceType = "json";
          } else {
            return;
          }
        }

        const responseBody = result?.body || "";
        const direct = await sendDirect(info, postData, result);

        await appendCapture(source.tabId, {
          at: nowIso(),
          method: info.method,
          path: info.path,
          type: resourceType,
          encoding: result?.base64Encoded ? "base64" : "text",
          httpStatus: info.status,
          postDataBytes: postData.length,
          responseBytes: responseBody.length,
          looksLikeSvdata:
            !result?.base64Encoded && responseBody.startsWith("svdata="),
          session: source.sessionId ? "iframe" : "root",
          direct
        });
      } catch (e) {
        await recordTabError(
          source.tabId,
          `direct ${info.path}: ${e.message || e}`
        );
      }
    }
  } catch (e) {
    await recordTabError(source.tabId, e);
  }
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  (async () => {
    if (message?.type === "status") {
      await ensurePluginStateLoaded();
      pluginHealth().catch(() => {});
      const [active] = await chrome.tabs.query({
        active: true,
        currentWindow: true
      });
      const { captures = [], pocTabs = {}, pluginState: saved = {} } =
        await chrome.storage.local.get(["captures", "pocTabs", "pluginState"]);

      sendResponse({
        ok: true,
        activeTab: active ? {
          id: active.id,
          isGame: isGamePage(active.url || "")
        } : null,
        activeState: active?.id ? pocTabs[String(active.id)] || null : null,
        captures,
        pluginState: saved
      });
      return;
    }

    if (message?.type === "startActive") {
      const [active] = await chrome.tabs.query({
        active: true,
        currentWindow: true
      });
      if (!active?.id || !isGamePage(active.url || "")) {
        sendResponse({ ok: false, error: "艦これのDMMタブを前面にしてください。" });
        return;
      }
      disabledTabs.delete(active.id);
      await attachTab(active.id);
      await heartbeatTick();
      sendResponse({ ok: true });
      return;
    }

    if (message?.type === "stopActive") {
      const [active] = await chrome.tabs.query({
        active: true,
        currentWindow: true
      });
      if (active?.id) await detachTab(active.id, true);
      await heartbeatTick();
      sendResponse({ ok: true });
      return;
    }

    if (message?.type === "health") {
      const ok = await pluginHealth();
      sendResponse({ ok, pluginState });
      return;
    }

    if (message?.type === "clearCaptures") {
      await chrome.storage.local.set({ captures: [] });
      sendResponse({ ok: true });
      return;
    }

    sendResponse({ ok: false, error: "unknown message" });
  })().catch(e => {
    sendResponse({ ok: false, error: String(e?.message || e) });
  });

  return true;
});

ensureHeartbeatAlarm().catch(() => {});
scanTabs().catch(() => {});
heartbeatTick().catch(() => {});
