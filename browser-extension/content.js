// Finds the media playing on this page and reports it to the background worker every 2 s
// (every 10 s while paused, and at once on play, pause, seek or a new video). Also carries out play / pause / next / previous / seek from the tablet.
// Works with a <video> or <audio> element (also inside open shadow roots), and with pages that only use the Media Session
// API (via content-main.js). Netflix gets its own reading of the title and its own seek.
(() => {
  if (window.__tabletNowPlaying) return;
  window.__tabletNowPlaying = true;
  const T = window.TNP;

  // ---- settings: sites the person does not want reported ----
  let ignoreList = "";
  try {
    chrome.storage.local.get("ignore").then((s) => { ignoreList = s.ignore || ""; }).catch(() => {});
    chrome.storage.onChanged.addListener((changes) => { if (changes.ignore) ignoreList = changes.ignore.newValue || ""; });
  } catch (e) { /* extension reloaded */ }
  const ignored = () => T.isIgnored(location.hostname, ignoreList);

  // ---- which media element ----
  let lastMedia = null;
  let lastActive = 0;
  let wasReporting = false;
  const remember = (e) => {
    if (e.target instanceof HTMLMediaElement) { lastMedia = e.target; lastActive = Date.now(); }
  };
  // Media events do not bubble, but they can be caught on the way down.
  for (const ev of ["playing", "play", "seeked"]) document.addEventListener(ev, remember, true);
  // These make the next report go out at once instead of waiting for the timer (see reportSoon below).
  for (const ev of ["playing", "play", "pause", "seeked", "loadedmetadata", "emptied"]) {
    document.addEventListener(ev, (e) => { if (e.target instanceof HTMLMediaElement) reportSoon(); }, true);
  }
  document.addEventListener("yt-navigate-finish", () => reportSoon(400));

  const isPlaying = (m) => !m.paused && !m.ended && m.readyState > 2;

  // Media inside open shadow roots is not found by document.querySelectorAll. Scanning every element is expensive, so it
  // happens only when the normal search finds nothing, and at most every 15 s.
  let shadowFound = [];
  let shadowScanAt = 0;
  function shadowMedia() {
    const now = Date.now();
    if (now - shadowScanAt < 15000) return shadowFound.filter((m) => m.isConnected);
    shadowScanAt = now;
    const out = [];
    const walk = (root, depth) => {
      if (depth > 6) return;
      for (const el of root.querySelectorAll("*")) {
        const sr = el.shadowRoot;
        if (!sr) continue;
        out.push(...sr.querySelectorAll("video,audio"));
        walk(sr, depth + 1);
      }
    };
    try { walk(document, 0); } catch (e) { /* ignore */ }
    shadowFound = out;
    return out;
  }

  function pickMedia() {
    let list = [...document.querySelectorAll("video,audio")];
    if (!list.length) list = shadowMedia();
    const all = list.filter((m) => Number.isFinite(m.duration) ? m.duration >= 5 : true);
    const hasSession = !!(navigator.mediaSession && navigator.mediaSession.metadata);
    const playing = all.filter(isPlaying).filter((m) => !(m.muted && !hasSession));
    if (playing.length) return playing.sort((a, b) => (b.duration || 0) - (a.duration || 0))[0];
    if (lastMedia && lastMedia.isConnected && Date.now() - lastActive < 10 * 60 * 1000) return lastMedia;
    return null;
  }

  // ---- what the page says through the Media Session API (content-main.js answers) ----
  function askMain() {
    let r = null;
    const h = (e) => { try { r = JSON.parse(e.detail); } catch (err) { /* ignore */ } };
    document.addEventListener("__tnp_ms", h);
    document.dispatchEvent(new CustomEvent("__tnp_get"));
    document.removeEventListener("__tnp_ms", h);
    return r || { pos: null, actions: [] };
  }
  const sendMain = (o) => document.dispatchEvent(new CustomEvent("__tnp_cmd", { detail: JSON.stringify(o) }));

  function artworkUrl(md) {
    const list = md && md.artwork ? [...md.artwork] : [];
    if (list.length) {
      list.sort((a, b) => (parseInt(b.sizes) || 0) - (parseInt(a.sizes) || 0));
      try {
        const u = new URL(list[0].src, location.href);
        // The tablet cannot open blob: or data: addresses from this page.
        if (u.protocol === "http:" || u.protocol === "https:") return u.href;
      } catch (e) { /* fall through */ }
    }
    const v = new URLSearchParams(location.search).get("v");
    if (v && /youtube\.com$/.test(location.hostname)) return "https://i.ytimg.com/vi/" + v + "/hqdefault.jpg";
    const og = document.querySelector('meta[property="og:image"]') || document.querySelector('link[rel="image_src"]');
    return og ? (og.content || og.href || "") : "";
  }

  // ---- Netflix ----
  const isNetflix = /(^|\.)netflix\.com$/.test(location.hostname);
  const onNetflixWatch = () => isNetflix && /^\/watch\/\d+/.test(location.pathname);
  const nf = { id: "", title: "", artist: "", image: "", fetchedTitle: "", since: 0, asked: "" };

  // The overlay with the title only exists while the controls are showing, so what it said is kept per video; a title
  // page lookup (background.js) fills in the show name and the picture.
  function netflixRead() {
    const id = (location.pathname.match(/\/watch\/(\d+)/) || [])[1] || "";
    if (!id) return null;
    if (nf.id !== id) Object.assign(nf, { id, title: "", artist: "", image: "", fetchedTitle: "", since: Date.now(), asked: "" });
    const el = document.querySelector('[data-uia="video-title"]');
    if (el) {
      const p = T.parseNetflixTitle([...el.children].map((c) => c.textContent), el.textContent);
      if (p.title) { nf.title = p.title; nf.artist = p.artist; }
    }
    if (nf.asked !== id) {
      nf.asked = id;
      try {
        chrome.runtime.sendMessage({ type: "netflixInfo", id }).then((r) => {
          if (r && nf.id === id) { nf.image = r.image || ""; nf.fetchedTitle = r.title || ""; }
        }).catch(() => {});
      } catch (e) { /* extension reloaded */ }
    }
    let title = nf.title || nf.fetchedTitle;
    // Neither the overlay nor the lookup gave a name: after a few seconds say so instead of sending nothing.
    if (!title && Date.now() - nf.since > 6000) title = "Netflix";
    if (!title) return null;
    return { key: "netflix|" + id, title, artist: nf.artist || "Netflix", artwork: nf.image || "" };
  }

  // ---- the report ----
  function snapshot() {
    if (ignored()) return null;
    if (isNetflix && !onNetflixWatch()) return null; // the browse pages play muted previews
    const m = pickMedia();
    const md = navigator.mediaSession && navigator.mediaSession.metadata;
    const siteName = T.friendlySite(location.hostname);
    const base = { site: location.hostname, siteName };

    let positionMs, durationMs, playing, kind;
    if (m) {
      positionMs = Math.round((m.currentTime || 0) * 1000);
      durationMs = Number.isFinite(m.duration) ? Math.round(m.duration * 1000) : 0;
      playing = isPlaying(m);
      kind = m instanceof HTMLVideoElement && m.videoWidth > 0 ? "video" : "audio";
    } else {
      // Pages with no media element we can reach (some players keep it out of sight): the session alone.
      const state = navigator.mediaSession && navigator.mediaSession.playbackState;
      if (!(md && md.title && (state === "playing" || state === "paused"))) return null;
      const pos = askMain().pos;
      playing = state === "playing";
      durationMs = pos ? Math.round(pos.duration * 1000) : 0;
      positionMs = pos ? Math.round((pos.position + (playing ? (Date.now() - pos.at) / 1000 * pos.rate : 0)) * 1000) : 0;
      if (durationMs > 0) positionMs = Math.min(positionMs, durationMs);
      kind = "audio";
    }

    if (onNetflixWatch()) {
      const n = netflixRead();
      if (!n) return null;
      return { ...base, ...n, album: "Netflix", positionMs, durationMs, playing, kind: "video" };
    }

    const title = (md && md.title) || T.cleanTitle(document.title);
    if (!title) return null;
    const v = new URLSearchParams(location.search).get("v");
    return {
      ...base,
      key: location.hostname + "|" + (v || title),
      title,
      artist: (md && md.artist) || "",
      album: (md && md.album) || "",
      artwork: artworkUrl(md),
      positionMs, durationMs, playing, kind
    };
  }

  let timer = 0;
  function tick() {
    let snap = null;
    try { snap = snapshot(); } catch (e) { /* page in transition */ }
    if (snap) {
      wasReporting = true;
      try { chrome.runtime.sendMessage({ type: "report", data: snap }).catch(() => {}); } catch (e) { /* extension reloaded */ }
    } else if (wasReporting) {
      wasReporting = false;
      try { chrome.runtime.sendMessage({ type: "gone" }).catch(() => {}); } catch (e) { /* ignore */ }
    }
    timer = setTimeout(tick, snap && !snap.playing ? 10000 : 2000);
  }
  // A burst of media events (a new video fires several) becomes one report.
  let soon = 0;
  function reportSoon(ms) {
    clearTimeout(soon);
    soon = setTimeout(() => { clearTimeout(timer); tick(); }, ms || 120);
  }
  tick();
  window.addEventListener("pagehide", () => {
    if (wasReporting) { try { chrome.runtime.sendMessage({ type: "gone" }); } catch (e) { /* ignore */ } }
  });
  // A single-page site changes the address without loading a page (Netflix moving to the next episode, for one).
  let lastHref = location.href;
  setInterval(() => { if (location.href !== lastHref) { lastHref = location.href; reportSoon(500); } }, 1000);

  // ---- commands from the tablet ----
  const click = (...selectors) => {
    for (const s of selectors) {
      const el = document.querySelector(s);
      if (el) { el.click(); return true; }
    }
    return false;
  };

  // Netflix only builds its control bar while the pointer is moving, so it is woken first.
  function netflixNext() {
    const sel = ['[data-uia="control-next"]', '[data-uia="next-episode-seamless-button"]'];
    if (click(...sel)) return;
    const target = document.querySelector(".watch-video--player-view") || document.body;
    target.dispatchEvent(new MouseEvent("mousemove", { bubbles: true, clientX: 200, clientY: 200 }));
    setTimeout(() => {
      if (!click(...sel)) {
        // Netflix's own shortcut for the next episode.
        const opts = { key: "N", code: "KeyN", keyCode: 78, which: 78, shiftKey: true, bubbles: true };
        document.dispatchEvent(new KeyboardEvent("keydown", opts));
        document.dispatchEvent(new KeyboardEvent("keyup", opts));
      }
    }, 300);
  }

  function run(cmd) {
    const m = pickMedia();
    const main = askMain();
    const has = (a) => main.actions.includes(a);
    const nfWatch = onNetflixWatch();
    switch (cmd.c) {
      case "play": if (m) m.play(); else if (has("play")) sendMain({ a: "play" }); break;
      case "pause": if (m) m.pause(); else if (has("pause")) sendMain({ a: "pause" }); break;
      case "seek":
        if (!Number.isFinite(cmd.ms)) break;
        if (nfWatch) sendMain({ a: "nfseek", ms: cmd.ms });
        else if (m) m.currentTime = cmd.ms / 1000;
        else if (has("seekto")) sendMain({ a: "seekto", t: cmd.ms / 1000 });
        break;
      case "next":
        if (nfWatch) { netflixNext(); break; }
        if (click(".ytp-next-button", "ytmusic-player-bar .next-button", ".skipControl__next",
                  '[data-testid="control-button-skip-forward"]', '[data-testid="next-button"]')) break;
        if (has("nexttrack")) { sendMain({ a: "nexttrack" }); break; }
        if (m && Number.isFinite(m.duration)) m.currentTime = m.duration;
        break;
      case "previous": {
        // Past the first few seconds "previous" restarts the track, as players do.
        const cur = m ? m.currentTime : main.pos ? main.pos.position + (Date.now() - main.pos.at) / 1000 * main.pos.rate : 0;
        if (cur > 5) {
          if (nfWatch) sendMain({ a: "nfseek", ms: 0 });
          else if (m) m.currentTime = 0;
          else if (has("seekto")) sendMain({ a: "seekto", t: 0 });
          break;
        }
        if (nfWatch) { sendMain({ a: "nfseek", ms: 0 }); break; } // Netflix has no previous-episode button
        if (click(".ytp-prev-button", "ytmusic-player-bar .previous-button", ".skipControl__previous",
                  '[data-testid="control-button-skip-back"]', '[data-testid="previous-button"]')) break;
        if (has("previoustrack")) { sendMain({ a: "previoustrack" }); break; }
        if (m) m.currentTime = 0;
        break;
      }
      default: break;
    }
    clearTimeout(timer);
    setTimeout(tick, 150);
  }

  chrome.runtime.onMessage.addListener((msg) => {
    if (msg && msg.type === "command" && msg.cmd) run(msg.cmd);
  });
})();
