// Posts the chosen tab's report to the tablet app and hands the tablet's commands back to that tab.
const reports = new Map(); // "tabId:frameId" -> { data, at, tabId, frameId }
let chosen = null;
let settings = { address: "", token: "" };
let loaded = false;

async function loadSettings() {
  const s = await chrome.storage.local.get(["address", "token"]);
  settings = { address: (s.address || "").trim(), token: (s.token || "").trim() };
  loaded = true;
}
chrome.storage.onChanged.addListener(() => { loaded = false; });

function baseUrl() {
  let a = settings.address.replace(/^https?:\/\//, "").replace(/\/+$/, "");
  if (!a) return "";
  if (!/:\d+$/.test(a)) a += ":8765";
  return "http://" + a;
}

function choose() {
  let best = null;
  for (const r of reports.values()) {
    if (Date.now() - r.at > 15000) continue;
    if (!best) { best = r; continue; }
    if (r.data.playing !== best.data.playing) { if (r.data.playing) best = r; continue; }
    if (r.at > best.at) best = r;
  }
  return best;
}

function badge(text, color) {
  chrome.action.setBadgeText({ text });
  chrome.action.setBadgeBackgroundColor({ color });
}

async function post(body) {
  if (!loaded) await loadSettings();
  const url = baseUrl();
  if (!url || !settings.token) { badge("?", "#888888"); return null; }
  try {
    const res = await fetch(url + "/now", {
      method: "POST",
      headers: { "X-Token": settings.token, "Content-Type": "text/plain" },
      body: JSON.stringify(body)
    });
    if (res.status === 401) { badge("code", "#d93025"); return null; }
    badge("on", "#1db954");
    return await res.json();
  } catch (e) {
    badge("!", "#d93025");
    return null;
  }
}

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  const tabId = sender.tab && sender.tab.id;
  if (tabId == null) return;
  const key = tabId + ":" + (sender.frameId || 0);
  if (msg.type === "report") {
    reports.set(key, { data: msg.data, at: Date.now(), tabId, frameId: sender.frameId || 0 });
    const c = choose();
    const mine = c && c.tabId === tabId && c.frameId === (sender.frameId || 0);
    if (mine) {
      chosen = c;
      post(msg.data).then((reply) => {
        if (reply && Array.isArray(reply.cmds)) {
          for (const cmd of reply.cmds) {
            chrome.tabs.sendMessage(tabId, { type: "command", cmd }, { frameId: sender.frameId || 0 }).catch(() => {});
          }
        }
      });
    }
  } else if (msg.type === "gone") {
    reports.delete(key);
    if (chosen && chosen.tabId === tabId && chosen.frameId === (sender.frameId || 0)) {
      chosen = null;
      if (!choose()) post({ gone: true });
    }
  }
  sendResponse && sendResponse();
});

chrome.tabs.onRemoved.addListener((tabId) => {
  for (const k of [...reports.keys()]) if (k.startsWith(tabId + ":")) reports.delete(k);
  if (chosen && chosen.tabId === tabId) {
    chosen = null;
    if (!choose()) post({ gone: true });
  }
});

loadSettings();
