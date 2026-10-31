async function send(message, timeoutMs = 3000) {
  let timer;
  try {
    return await Promise.race([
      chrome.runtime.sendMessage(message),
      new Promise((_, reject) => {
        timer = setTimeout(
          () => reject(new Error("Service Worker response timeout")),
          timeoutMs
        );
      })
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

function esc(s) {
  return String(s ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function render(r) {
  const c = document.getElementById("cdp");
  const p = document.getElementById("plugin");
  const h = document.getElementById("captures");

  if (!r?.ok) {
    c.innerHTML = `<span class="err">${esc(r?.error || "status error")}</span>`;
    p.innerHTML = '<span class="err">状態取得失敗</span>';
    return;
  }

  const t = r.activeTab;
  const s = r.activeState;
  const ps = r.pluginState || {};

  if (!t?.isGame) {
    c.innerHTML =
      '<span class="muted">艦これのDMMタブを前面にしてください。</span>';
  } else if (s?.attached) {
    c.innerHTML =
      `<span class="ok">CDP接続中</span>\n取得件数: ${esc(s.captureCount || 0)}\nエラー: ${esc(s.lastError || "なし")}`;
  } else {
    c.innerHTML =
      `<span class="err">CDP未接続</span>\n${esc(s?.lastError || "")}`;
  }

  if (ps.status === "connected") {
    p.innerHTML =
      `<span class="ok">connected</span>\n${esc(ps.lastHealth || "")}\n送信: ${esc(ps.sent || 0)}\n監視: ${ps.monitoring ? "ON" : "OFF"} / 連続失敗: ${esc(ps.consecutiveFailures || 0)}`;
  } else if (ps.status === "unknown") {
    p.innerHTML = '<span class="muted">health確認中…</span>';
  } else {
    p.innerHTML =
      `<span class="err">${esc(ps.status || "unknown")}</span>\nエラー: ${esc(ps.lastError || "なし")}\n監視: ${ps.monitoring ? "ON" : "OFF"} / 連続失敗: ${esc(ps.consecutiveFailures || 0)}`;
  }

  if (!r.captures?.length) {
    h.innerHTML = '<div class="capture muted">まだ取得していません。</div>';
    return;
  }

  h.innerHTML = r.captures.slice(0, 20).map(x =>
    `<div class="capture">${esc(x.method)} ${esc(x.path)}<br>TYPE=${esc(x.type || "api")} ENCODING=${esc(x.encoding || "text")} HTTP=${esc(x.httpStatus)} RESPONSE=${esc(x.responseBytes)}B POST=${esc(x.postDataBytes)}B SVDATA=${x.looksLikeSvdata ? "YES" : "NO"} SESSION=${esc(x.session)} DIRECT=${x.direct ? "YES" : "NO"}</div>`
  ).join("");
}

async function refresh() {
  const c = document.getElementById("cdp");
  const p = document.getElementById("plugin");

  try {
    const r = await send({ type: "status" });
    render(r);
  } catch (e) {
    const msg = esc(e?.message || e);
    c.innerHTML = `<span class="err">Service Worker通信失敗</span>\n${msg}`;
    p.innerHTML = '<span class="err">状態取得不可</span>';
  }
}

document.getElementById("start").onclick = async () => {
  try {
    const r = await send({ type: "startActive" });
    if (!r?.ok) alert(r?.error);
  } catch (e) {
    alert(e?.message || e);
  }
  refresh();
};

document.getElementById("stop").onclick = async () => {
  try { await send({ type: "stopActive" }); } catch (_) {}
  refresh();
};

document.getElementById("health").onclick = async () => {
  try { await send({ type: "health" }, 4000); } catch (_) {}
  refresh();
};

document.getElementById("clear").onclick = async () => {
  try { await send({ type: "clearCaptures" }); } catch (_) {}
  refresh();
};

refresh();
setTimeout(refresh, 1000);

async function refreshKai2Mirror() {
  const {kai2MirrorState: state} = await chrome.storage.local.get("kai2MirrorState");
  document.getElementById("kai2MirrorStatus").textContent = state
    ? `:${state.port} ${state.status} / 送信成功 ${state.accepted} / 失敗 ${state.failed} / 欠落 ${state.dropped} / 待機 ${state.pending}`
    : "まだ送信されていません";
}
void refreshKai2Mirror();
chrome.storage.onChanged.addListener(() => { void refreshKai2Mirror(); });
