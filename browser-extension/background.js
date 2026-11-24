// Posts the chosen tab's report to the tablet app and hands the tablet's commands back to that tab.
importScripts("lib.js");
const reports = new Map(); // "tabId:frameId" -> { data, at, tabId, frameId }
let chosen = null;
let settings = { address: "", token: "" };
let loaded = false;
let lastPost = { at: 0, result: "nothing sent yet" };

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
    if (Date.now() - r.at > 25000) continue;
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
    const scale = Math.min(1, 960 / Math.max(bitmap.width, bitmap.height));
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

// The cover is attached once when it is ready or changes, and again only if the tablet asks for it (needArt).
let wantArt = false;
function withArt(data) {
  ensureArt(data.artwork);
  if (art.url === data.artwork && art.data && (art.sentUrl !== art.url || (wantArt && Date.now() - art.sentAt > 5000))) {
    art.sentUrl = art.url;
    art.sentAt = Date.now();
    wantArt = false;
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
  if (!url || !settings.token) { badge("?", "#888888"); lastPost = { at: Date.now(), result: "no address or code saved" }; return null; }
  try {
    const res = await fetch(url + "/now", {
      method: "POST",
      headers: { "X-Token": settings.token, "Content-Type": "text/plain" },
      body: JSON.stringify(body)
    });
    if (res.status === 401) { badge("code", "#d93025"); lastPost = { at: Date.now(), result: "the tablet refused the code" }; return null; }
    badge("on", "#1db954");
    lastPost = { at: Date.now(), result: "ok" };
    const reply = await res.json();
    wantArt = !!(reply && reply.needArt);
    return reply;
  } catch (e) {
    badge("!", "#d93025");
    lastPost = { at: Date.now(), result: "could not reach the tablet" };
    return null;
  }
}

// Netflix's watch page names neither the show nor shows a picture, so the title page of the same id is read (once per video).
const netflixCache = new Map();
async function netflixInfo(id) {
  if (netflixCache.has(id)) return netflixCache.get(id);
  let info = { title: "", image: "" };
  try {
    const res = await fetch("https://www.netflix.com/title/" + encodeURIComponent(id), { credentials: "include" });
    if (res.ok) info = self.TNP.parseOpenGraph(await res.text());
  } catch (e) { /* offline or blocked: the page's own overlay still names the video */ }
  if (netflixCache.size > 50) netflixCache.clear();
  netflixCache.set(id, info);
  return info;
}

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg && msg.type === "netflixInfo") {
    netflixInfo(String(msg.id || "")).then(sendResponse);
    return true; // the answer comes later
  }
  if (msg && msg.type === "status") {
    const c = choose();
    sendResponse({
      playing: c ? { title: c.data.title, artist: c.data.artist, site: c.data.siteName || c.data.site, kind: c.data.kind || "", isPlaying: !!c.data.playing } : null,
      tabs: reports.size,
      last: lastPost
    });
    return;
  }
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
