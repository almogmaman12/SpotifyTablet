const $ = (id) => document.getElementById(id);
chrome.storage.local.get(["address", "token"]).then((s) => {
  $("address").value = s.address || "";
  $("token").value = s.token || "";
});
$("save").addEventListener("click", async () => {
  const address = $("address").value.trim();
  const token = $("token").value.trim();
  await chrome.storage.local.set({ address, token });
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
