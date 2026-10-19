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

// The cover, fetched here and shrunk to a small JPEG so the tablet needs no access to the picture's site.
let art = { url: "", data: "", pending: "", sentUrl: "", sentAt: 0 };

async function ensureArt(url) {
  if (!url || art.url === url || art.pending === url) return;
  art.pending = url;
  try {
    const res = await fetch(url);
    const bitmap = await createImageBitmap(await res.blob());
    const scale = Math.min(1, 480 / Math.max(bitmap.width, bitmap.height));
    const w = Math.max(1, Math.round(bitmap.width * scale));
    const h = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = new OffscreenCanvas(w, h);
    canvas.getContext("2d").drawImage(bitmap, 0, 0, w, h);
    const blob = await canvas.convertToBlob({ type: "image/jpeg", quality: 0.85 });
    const bytes = new Uint8Array(await blob.arrayBuffer());
    let bin = "";
    for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    art.url = url;
    art.data = "data:image/jpeg;base64," + btoa(bin);
    art.sentUrl = "";
  } catch (e) {
    art.url = url; // do not retry every second; the tablet falls back to the address
    art.data = "";
  } finally {
    art.pending = "";
  }
}

function withArt(data) {
  ensureArt(data.artwork);
  if (art.url === data.artwork && art.data && (art.sentUrl !== art.url || Date.now() - art.sentAt > 30000)) {
    art.sentUrl = art.url;
    art.sentAt = Date.now();
    return { ...data, artworkData: art.data };
  }
  return data;
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
      post(withArt(msg.data)).then((reply) => {
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
