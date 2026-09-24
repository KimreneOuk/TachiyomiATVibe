/* Load-order module: console.js. Shared classic-script scope, no build step. */
/* --------------------------------------------------------------- logging */
const logBuffer = [];
function logLine(text, cls) {
  const stamp = typeof text === "string" ? text : JSON.stringify(text);
  logBuffer.push({ text: stamp, cls });
  const c = $("console");
  if (c) {
    const div = el("div", cls);
    div.textContent = stamp;
    c.appendChild(div);
    c.scrollTop = c.scrollHeight;
    while (c.children.length > 600) c.removeChild(c.firstChild);
  }
}
async function tailServerLogs() {
  try {
    const l = await api("/api/log");
    const have = logBuffer.length;
    l.lines.slice(have).forEach((x) => logLine(x));
  } catch {}
}


/* =============================================================== logs */
function openLogs() {
  $("consolePanel")?.classList.remove("hidden");
  if ($("btnLogs")) $("btnLogs").textContent = "Hide Logs";
  tailServerLogs();
}
function closeLogs() {
  $("consolePanel")?.classList.add("hidden");
  if ($("btnLogs")) $("btnLogs").textContent = "Show Logs";
}
$("btnLogs")?.addEventListener("click", () =>
  $("consolePanel")?.classList.contains("hidden") ? openLogs() : closeLogs());
$("consoleClear")?.addEventListener("click", () => { $("console").innerHTML = ""; logBuffer.length = 0; });

/* drag-resize console */
(() => {
  let drag = null;
  $("consoleDrag")?.addEventListener("pointerdown", (e) => {
    drag = { y: e.clientY, h: $("consolePanel").offsetHeight };
    e.preventDefault();
  });
  window.addEventListener("pointermove", (e) => {
    if (!drag) return;
    const h = Math.min(window.innerHeight - 180,
                       Math.max(64, drag.h + (drag.y - e.clientY)));
    $("consolePanel").style.height = h + "px";
  });
  window.addEventListener("pointerup", () => { drag = null; });
})();
