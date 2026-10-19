// Finds the media playing on this page and reports it to the background worker once a second
// (every 3 s while paused). Also carries out play / pause / next / previous / seek from the tablet.
(() => {
  if (window.__tabletNowPlaying) return;
  window.__tabletNowPlaying = true;

  let lastMedia = null;
  let lastActive = 0;
  let wasReporting = false;
  const remember = (e) => {
    if (e.target instanceof HTMLMediaElement) { lastMedia = e.target; lastActive = Date.now(); }
  };
  // Media events do not bubble, but they can be caught on the way down.
  for (const ev of ["playing", "play", "seeked"]) document.addEventListener(ev, remember, true);

  const isPlaying = (m) => !m.paused && !m.ended && m.readyState > 2;

  function pickMedia() {
    const all = [...document.querySelectorAll("video,audio")]
      .filter((m) => Number.isFinite(m.duration) ? m.duration >= 5 : true);
    const playing = all.filter(isPlaying).filter((m) => !(m.muted && !navigator.mediaSession.metadata));
    if (playing.length) return playing.sort((a, b) => (b.duration || 0) - (a.duration || 0))[0];
    if (lastMedia && document.contains(lastMedia) && Date.now() - lastActive < 10 * 60 * 1000) return lastMedia;
    return null;
  }

  function cleanTitle(t) {
    return (t || "").replace(/^\(\d+\)\s*/, "").replace(/\s*[-|–]\s*(YouTube( Music)?|SoundCloud|Bandcamp)\s*$/i, "").trim();
  }

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
    const og = document.querySelector('meta[property="og:image"]');
    return og ? og.content : "";
  }

  function snapshot() {
    const m = pickMedia();
    if (!m) return null;
    const md = navigator.mediaSession.metadata;
    const title = (md && md.title) || cleanTitle(document.title);
    if (!title) return null;
    const v = new URLSearchParams(location.search).get("v");
    return {
      key: location.hostname + "|" + (v || title),
      title,
      artist: (md && md.artist) || "",
      album: (md && md.album) || "",
      artwork: artworkUrl(md),
      site: location.hostname,
      positionMs: Math.round((m.currentTime || 0) * 1000),
      durationMs: Number.isFinite(m.duration) ? Math.round(m.duration * 1000) : 0,
      playing: isPlaying(m)
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
    timer = setTimeout(tick, snap && !snap.playing ? 3000 : 1000);
  }
  tick();
  window.addEventListener("pagehide", () => {
    if (wasReporting) { try { chrome.runtime.sendMessage({ type: "gone" }); } catch (e) { /* ignore */ } }
  });

  const click = (...selectors) => {
    for (const s of selectors) {
      const el = document.querySelector(s);
      if (el) { el.click(); return true; }
    }
    return false;
  };

  function run(cmd) {
    const m = pickMedia();
    switch (cmd.c) {
      case "play": if (m) m.play(); break;
      case "pause": if (m) m.pause(); break;
      case "seek": if (m && Number.isFinite(cmd.ms)) m.currentTime = cmd.ms / 1000; break;
      case "next":
        if (!click(".ytp-next-button", "ytmusic-player-bar .next-button", ".skipControl__next",
                   '[data-testid="control-button-skip-forward"]', '[data-testid="next-button"]') && m && Number.isFinite(m.duration)) {
          m.currentTime = m.duration;
        }
        break;
      case "previous":
        // Past the first few seconds "previous" restarts the track, as players do.
        if (m && m.currentTime > 5) { m.currentTime = 0; break; }
        if (!click(".ytp-prev-button", "ytmusic-player-bar .previous-button", ".skipControl__previous",
                   '[data-testid="control-button-skip-back"]', '[data-testid="previous-button"]') && m) {
          m.currentTime = 0;
        }
        break;
      default: break;
    }
    clearTimeout(timer);
    setTimeout(tick, 150);
  }

  chrome.runtime.onMessage.addListener((msg) => {
    if (msg && msg.type === "command" && msg.cmd) run(msg.cmd);
  });
})();
