const $ = (id) => document.getElementById(id);
chrome.storage.local.get(["address", "token", "ignore"]).then((s) => {
  $("address").value = s.address || "";
  $("token").value = s.token || "";
  $("ignore").value = s.ignore || "";
});
$("save").addEventListener("click", async () => {
  const address = $("address").value.trim();
  const token = $("token").value.trim();
  await chrome.storage.local.set({ address, token, ignore: $("ignore").value });
  const status = $("status");
  status.textContent = "Testing…";
  let base = address.replace(/^https?:\/\//, "").replace(/\/+$/, "");
  if (base && !/:\d+$/.test(base)) base += ":8765";
  try {
    const res = await fetch("http://" + base + "/ping", { headers: { "X-Token": token } });
    status.textContent = res.ok ? "Connected to the tablet." : res.status === 401 ? "The tablet refused the code." : "Unexpected answer: " + res.status;
  } catch (e) {
    status.textContent = "Could not reach the tablet. Same Wi-Fi? Is \"Receive from the browser\" on?";
  }
});

// What the browser is reporting at the moment, so a page that does not show up can be told apart from a connection problem.
async function showNow() {
  let r = null;
  try { r = await chrome.runtime.sendMessage({ type: "status" }); } catch (e) { /* worker asleep: try again */ }
  const out = $("nowText");
  if (!r) { out.textContent = "Checking\u2026"; return; }
  const sent = r.last && r.last.at ? "Last message to the tablet: " + r.last.result + "." : "Nothing has been sent to the tablet yet.";
  if (r.playing) {
    const p = r.playing;
    out.textContent = (p.isPlaying ? "Playing: " : "Paused: ") + p.title + (p.artist ? " \u2013 " + p.artist : "")
      + " (" + p.site + (p.kind ? ", " + p.kind : "") + "). " + sent;
  } else {
    out.textContent = "No playing page found. Open a video or song and press play. " + sent;
  }
}
showNow();
setInterval(showNow, 2000);
