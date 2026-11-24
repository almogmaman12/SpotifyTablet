// Runs inside the page itself (not the extension's sandbox), before the page's own scripts, so it can see what the page
// tells the browser through the Media Session API: the buttons it handles (play, pause, next track, seek...) and the
// position it reports. The content script asks for them with "__tnp_get" and acts with "__tnp_cmd".
(() => {
  if (window.__tnpMain) return;
  window.__tnpMain = true;

  const handlers = {};
  let pos = null;
  const ms = navigator.mediaSession;

  if (ms && typeof ms.setActionHandler === "function") {
    const origSet = ms.setActionHandler.bind(ms);
    ms.setActionHandler = function (action, handler) {
      handlers[action] = handler || null;
      return origSet(action, handler);
    };
  }
  if (ms && typeof ms.setPositionState === "function") {
    const origPos = ms.setPositionState.bind(ms);
    ms.setPositionState = function (state) {
      if (state && Number.isFinite(state.duration)) {
        pos = { position: Number(state.position) || 0, duration: state.duration, rate: Number(state.playbackRate) || 1, at: Date.now() };
      } else {
        pos = null;
      }
      return origPos.apply(ms, arguments);
    };
  }

  function netflixPlayer() {
    try {
      const vp = window.netflix.appContext.state.playerApp.getAPI().videoPlayer;
      const ids = vp.getAllPlayerSessionIds();
      return vp.getVideoPlayerBySessionId(ids[ids.length - 1]);
    } catch (e) {
      return null;
    }
  }

  document.addEventListener("__tnp_get", () => {
    document.dispatchEvent(new CustomEvent("__tnp_ms", {
      detail: JSON.stringify({ pos, actions: Object.keys(handlers).filter((k) => handlers[k]) })
    }));
  });

  document.addEventListener("__tnp_cmd", (e) => {
    let c;
    try { c = JSON.parse(e.detail); } catch (err) { return; }
    try {
      if (c.a === "nfseek") {
        // Netflix refuses a plain currentTime change (it shows an error), but its own player object can seek.
        const p = netflixPlayer();
        if (p && Number.isFinite(c.ms)) p.seek(Math.max(0, Math.round(c.ms)));
      } else if (handlers[c.a]) {
        handlers[c.a](c.a === "seekto" ? { action: "seekto", seekTime: c.t } : { action: c.a });
      }
    } catch (err) { /* the page's own handler failed; nothing to do */ }
  });
})();
