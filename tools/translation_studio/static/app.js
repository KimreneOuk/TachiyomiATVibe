/* Translation Studio — studio-grade viewer over the existing pipeline.
   Vanilla JS, no build step. UI shell only: all processing stays in
   pipeline.py via the existing /api + /img endpoints. */
"use strict";

const $ = (id) => document.getElementById(id);
const enc = encodeURIComponent;
const el = (tag, cls, text) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
};
const esc = (s) => String(s ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;")
  .replace(/>/g, "&gt;").replace(/"/g, "&quot;");

async function api(path, body) {
  const opts = body ? { method: "POST", headers: { "Content-Type": "application/json" },
                       body: JSON.stringify(body) } : {};
  const res = await fetch(path, opts);
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.error) throw new Error(data.error || res.statusText);
  return data;
}

/* ------------------------------------------------------------------ state */
const S = {
  chapter: null, reference: null, pages: [], dims: {}, settings: {},
  overview: null,
  mode: "ours",            // original | goal | ours | compare
  compare: "orig-ours",    // orig-ours | goal-ours | all | overlay
  overlayOpacity: 0.55,
  viz: "render",           // primary visualization (View ▾)
  layers: { regions: false, ocr: false },
  pageData: new Map(),     // page -> /api/page json (kept: small, avoids recompute)
  current: null,
  selection: null,         // { page, id }
  zoom: 1,
  focus: null,             // page name while in focused page mode
  fz: { z: 1, tx: 0, ty: 0 },
  busy: false,
  processing: {},          // page -> true while in flight
  pageError: {},           // page -> message
  confTimer: null,
};

/* Primary visualization table. "unavailable" entries are exposed honestly
   (spec §14): the harness does not produce them, and we do not fake them. */
const VIZ = {
  render:       { label: "Render",        src: (p) => `/img/render?p=${enc(p)}` },
  original:     { label: "Original",      src: (p) => `/img/original?p=${enc(p)}` },
  goal:         { label: "Goal",          src: (p) => `/img/goal?p=${enc(p)}` },
  detection:    { label: "Detection",     src: (p) => `/img/overlay?p=${enc(p)}` },
  ocr:          { label: "OCR",           src: (p) => `/img/original?p=${enc(p)}` },
  inpaint_mask: { label: "Inpaint Mask",  unavailable: "not produced by this harness" },
  inpainted:    { label: "Inpainted",     unavailable: "raw inpaint not produced (render = erase + text)" },
  translation:  { label: "Translation",   src: (p) => `/img/render?p=${enc(p)}` },
  regions:      { label: "Regions",       src: (p) => `/img/original?p=${enc(p)}` },
  segmentation: { label: "Segmentation",  unavailable: "not produced by this harness" },
  overlay:      { label: "Overlay",       src: (p) => `/img/render?p=${enc(p)}` },
};

function imgSrcFor(kind, page) {
  if (kind === "original") return VIZ.original.src(page);
  if (kind === "goal") return VIZ.goal.src(page);
  return `/img/render?p=${enc(page)}`;
}

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

/* ================================================================= rail */
function buildRail() {
  const rail = $("railInner");
  rail.innerHTML = "";
  $("railEmpty").classList.toggle("hidden", S.pages.length > 0);
  S.pages.forEach((p, i) => {
    const item = el("div", "nav-item");
    item.dataset.page = p;
    item.innerHTML =
      `<div class="nav-thumb"><img loading="lazy" decoding="async" ` +
      `src="/img/thumb?p=${enc(p)}&s=128"></div>` +
      `<div class="nav-meta"><span>${String(i + 1).padStart(2, "0")}</span>` +
      `<span class="nav-dot" data-dot></span></div>`;
    item.title = p;
    item.onclick = () => scrollToPage(p);
    rail.appendChild(item);
  });
}

function dotState(p) {
  if (S.processing[p]) return "processing";
  if (S.pageError[p]) return "error";
  const row = S.overview?.pages.find((r) => r.page === p);
  if (!row) return "";
  if (row.rendered && row.ocr) return "done";
  if (row.ocr || row.detected) return "part";
  return "";
}

function refreshRailDots() {
  document.querySelectorAll(".nav-item").forEach((item) => {
    const dot = item.querySelector("[data-dot]");
    dot.className = "nav-dot " + dotState(item.dataset.page);
  });
}

function scrollToPage(p) {
  const sec = secEl(p);
  if (sec) sec.scrollIntoView({ behavior: "smooth", block: "start" });
}

/* ================================================================ strip */
function buildStrip() {
  const strip = $("strip");
  strip.innerHTML = "";
  $("emptyState").classList.toggle("hidden", S.pages.length > 0);
  S.pages.forEach((p, i) => {
    const [w, h] = S.dims[p] || [1000, 1400];
    const sec = el("section", "pg");
    sec.dataset.page = p;
    sec.dataset.idx = i;
    sec.innerHTML =
      `<header class="pg-head">` +
      `  <span class="pg-num">PAGE ${String(i + 1).padStart(2, "0")}</span>` +
      `  <span class="pg-line"></span>` +
      `  <span class="pg-status" data-status></span>` +
      `</header>` +
      `<div class="pg-frame"><div class="pg-media" style="aspect-ratio:${w}/${h}"></div></div>`;
    strip.appendChild(sec);
  });
}

const secEl = (p) => document.querySelector(`.pg[data-page="${CSS.escape(p)}"]`);

/* -------------------------------------------------------- media builder */
function wantKey() {
  return S.mode === "compare" ? `cmp:${S.compare}` : `viz:${S.viz}`;
}

function addImg(parent, cls, src) {
  const img = document.createElement("img");
  img.className = cls;
  img.decoding = "async";
  img.onerror = () => img.classList.add("missing");
  img.src = src;
  parent.appendChild(img);
  return img;
}

/* Builds the media content for one page per the current mode/viz.
   Returns the layer that region overlays attach to. */
function buildMediaInto(media, page) {
  media.innerHTML = "";
  const [w, h] = S.dims[page] || [1000, 1400];
  media.style.aspectRatio = `${w}/${h}`;
  let hit = media;
  let notice = "";

  if (S.mode === "compare" && S.compare === "overlay") {
    media.classList.add("stack");
    addImg(media, "ph", imgSrcFor("original", page));
    const top = addImg(media, "ph top", imgSrcFor("ours", page));
    top.style.opacity = S.overlayOpacity;
  } else if (S.mode === "compare") {
    const cols = S.compare === "all" ? [["original", "Original"], ["goal", "Goal"], ["ours", "Ours"]]
               : S.compare === "goal-ours" ? [["goal", "Goal"], ["ours", "Ours"]]
               : [["original", "Original"], ["ours", "Ours"]];
    media.classList.add("cols", `c${cols.length}`);
    media.style.aspectRatio = "";
    for (const [kind, tag] of cols) {
      const cell = el("div", "pg-cell");
      cell.style.setProperty("--ar", `${w}/${h}`);
      addImg(cell, "ph", imgSrcFor(kind, page));
      if (kind === "goal") {
        const img = cell.querySelector("img");
        img.onerror = () => {
          img.classList.add("missing");
          notice = "goal: page missing from reference folder";
          showNotice(media, notice);
        };
      }
      cell.appendChild(el("div", "pg-cell-tag", tag));
      media.appendChild(cell);
      if (kind === "ours") hit = cell;
    }
  } else {
    const v = VIZ[S.viz] || VIZ.render;
    let src = v.src(page);
    if (v.unavailable) {
      src = VIZ.original.src(page);
      notice = `${v.label}: ${v.unavailable}`;
    }
    addImg(media, "ph", src);
    if (S.viz === "overlay") {
      // quick A/B: original under render at slider opacity
      addImg(media, "ph", VIZ.original.src(page)).style.zIndex = 1;
      media.lastChild.style.zIndex = 0;
      const top = media.firstChild;
      top.style.zIndex = 2;
      top.style.opacity = S.overlayOpacity;
    }
    if (notice) showNotice(media, notice);
  }

  const ov = el("div", "pg-overlays");
  media.appendChild(ov);
  hit.appendChild(ov);
  ov.style.position = "absolute";
  media._overlays = ov;
  media._hasOverlays = false;
  updateLayerClasses(media);
  return media;
}

function showNotice(media, text) {
  let nz = media.querySelector(".pg-notice");
  if (!nz) {
    nz = el("div", "pg-notice");
    media.appendChild(nz);
  }
  nz.textContent = text;
  nz.classList.remove("hidden");
}

function updateLayerClasses(media) {
  media.classList.toggle("show-regions", !!S.layers.regions);
  media.classList.toggle("show-ocr", !!S.layers.ocr);
}

/* ---------------------------------------------------- virtualization */
function ensureSection(p) {
  const sec = secEl(p);
  if (!sec || sec._builtKey === wantKey()) return;
  const media = sec.querySelector(".pg-media");
  buildMediaInto(media, p);
  sec._builtKey = wantKey();
  const d = S.pageData.get(p);
  if (d) applyOverlays(p);
}

function evictSection(p) {
  const sec = secEl(p);
  if (!sec || sec._builtKey === null) return;
  const media = sec.querySelector(".pg-media");
  media.innerHTML = "";
  media.className = "pg-media";
  media.removeAttribute("style");
  const [w, h] = S.dims[p] || [1000, 1400];
  media.style.aspectRatio = `${w}/${h}`;
  sec._builtKey = null;
}

async function ensurePageData(p) {
  let d = S.pageData.get(p);
  if (!d) {
    try {
      d = await api(`/api/page?p=${enc(p)}`);
      S.pageData.set(p, d);
    } catch (e) {
      logLine(`!! page data ${p}: ${e.message}`, "err");
      return null;
    }
  }
  return d;
}

function applyOverlays(p) {
  const d = S.pageData.get(p);
  if (!d) return;
  const sec = secEl(p);
  const media = sec?.querySelector(".pg-media");
  if (!media || !media._overlays || media._hasOverlays) return;
  const [w, h] = S.dims[p] || [1000, 1400];
  d.regions.forEach((r) => {
    const [x1, y1, x2, y2] = r.box;
    const box = el("div", "rbox");
    box.dataset.id = r.id;
    box.style.left = (x1 / w) * 100 + "%";
    box.style.top = (y1 / h) * 100 + "%";
    box.style.width = ((x2 - x1) / w) * 100 + "%";
    box.style.height = ((y2 - y1) / h) * 100 + "%";
    box.title = `${r.id} · ${r.class} · ${r.score}`;
    box.innerHTML =
      `<span class="rbox-tag">${r.id} · ${esc(r.score)}</span>` +
      `<span class="rbox-text">${esc(r.text || "")}${r.text ? "<br>" : ""}` +
      `<i>${esc((d.translations[r.id] || "").trim())}</i></span>`;
    box.onclick = (e) => { e.stopPropagation(); selectRegion(p, r.id); };
    media._overlays.appendChild(box);
  });
  media._hasOverlays = true;
  if (S.selection && S.selection.page === p) markSelection();
}

/* media click → region hit-test (works even with layer boxes hidden) */
$("strip").addEventListener("click", (e) => {
  const media = e.target.closest(".pg-media");
  if (!media || S.mode === "compare" && S.compare !== "overlay") return;
  const sec = media.closest(".pg");
  const page = sec?.dataset.page;
  if (!page) return;
  const d = S.pageData.get(page);
  if (!d || !d.regions.length) { deselectRegion(); return; }
  const rect = (media._overlays || media).getBoundingClientRect();
  const nx = (e.clientX - rect.left) / rect.width;
  const ny = (e.clientY - rect.top) / rect.height;
  const hit = d.regions.find((r) => {
    const [x1, y1, x2, y2] = r.box;
    const [w, h] = S.dims[page] || [1, 1];
    return nx >= x1 / w && nx <= x2 / w && ny >= y1 / h && ny <= y2 / h;
  });
  if (hit) selectRegion(page, hit.id);
  else deselectRegion();
});
$("strip").addEventListener("dblclick", (e) => {
  const sec = e.target.closest(".pg");
  if (sec) enterFocus(sec.dataset.page);
});

/* ------------------------------------------------- scroll virtualizer */
let offsets = [];
let virtQueued = false;

function recalcOffsets() {
  const viewer = $("viewer");
  const vt = viewer.scrollTop;
  const vtop = viewer.getBoundingClientRect().top;
  offsets = S.pages.map((p) => {
    const sec = secEl(p);
    return sec ? sec.getBoundingClientRect().top - vtop + vt : 0;
  });
}

function queueVirtualize() {
  if (!virtQueued) {
    virtQueued = true;
    requestAnimationFrame(virtualize);
  }
}

function virtualize() {
  virtQueued = false;
  if (S.focus || !S.pages.length) return;
  const viewer = $("viewer");
  const st = viewer.scrollTop, sb = st + viewer.clientHeight;
  const n = S.pages.length;
  let lo = 0;
  while (lo < n - 1 && offsets[lo + 1] <= st) lo++;
  let hi = lo;
  while (hi < n - 1 && offsets[hi + 1] < sb) hi++;

  for (let i = Math.max(0, lo - 2); i <= Math.min(n - 1, hi + 2); i++)
    ensureSection(S.pages[i]);
  for (let i = 0; i < n; i++)
    if (i < lo - 5 || i > hi + 5) evictSection(S.pages[i]);
  for (let i = Math.max(0, lo - 1); i <= Math.min(n - 1, hi + 1); i++)
    ensurePageData(S.pages[i]).then((d) => {
      if (d) { applyOverlays(S.pages[i]); refreshSectionStatus(S.pages[i]); }
    });

  setCurrent(S.pages[lo]);
}

function setCurrent(p) {
  if (S.current === p) return;
  S.current = p;
  document.querySelectorAll(".nav-item").forEach((item) =>
    item.classList.toggle("current", item.dataset.page === p));
  updateStatusBar();
}

$("viewer").addEventListener("scroll", queueVirtualize, { passive: true });
window.addEventListener("resize", () => { recalcOffsets(); queueVirtualize(); });

/* ----------------------------------------------- page status headers */
function refreshSectionStatus(p) {
  const sec = secEl(p);
  if (!sec) return;
  const stEl = sec.querySelector("[data-status]");
  if (!stEl) return;
  const idx = S.pages.indexOf(p) + 1;
  if (S.processing[p]) {
    stEl.className = "pg-status processing";
    stEl.textContent = "◐ processing…";
    return;
  }
  if (S.pageError[p]) {
    stEl.className = "pg-status error";
    stEl.textContent = `! failed — ${S.pageError[p]} (click for log)`;
    stEl.onclick = () => { openLogs(); };
    return;
  }
  const row = S.overview?.pages.find((r) => r.page === p);
  if (!row) { stEl.className = "pg-status"; stEl.textContent = ""; return; }
  if (row.rendered && row.ocr) {
    stEl.className = "pg-status done";
    const tr = row.translated ? ` · ${row.translated}/${row.regions} translated` : "";
    stEl.textContent = `✓ Complete${row.regions ? ` · ${row.regions} regions${tr}` : ""}`;
  } else if (row.ocr) {
    stEl.className = "pg-status";
    stEl.textContent = `OCR ✓ · ${row.regions} regions · not rendered`;
  } else if (row.detected) {
    stEl.className = "pg-status";
    stEl.textContent = "detected · not processed";
  } else {
    stEl.className = "pg-status";
    stEl.textContent = "○ not processed";
  }
  void idx;
}

function refreshAllStatus() {
  S.pages.forEach(refreshSectionStatus);
  refreshRailDots();
}

async function refreshOverview() {
  try {
    S.overview = await api("/api/overview");
    refreshAllStatus();
    updateStatusBar();
  } catch {}
}

/* =========================================================== inspector */
function selectRegion(page, id) {
  S.selection = { page, id };
  markSelection();
  openInspector();
}

function deselectRegion() {
  S.selection = null;
  markSelection();
  $("inspector").classList.add("hidden");
  syncInspectorBtn();
}

function markSelection() {
  document.querySelectorAll(".rbox.sel").forEach((b) => b.classList.remove("sel"));
  if (!S.selection) return;
  const sec = secEl(S.selection.page);
  const box = sec?.querySelector(`.rbox[data-id="${S.selection.id}"]`);
  box?.classList.add("sel");
  // focused-mode copy
  const fbox = document.querySelector(`#focusInner .rbox[data-id="${S.selection.id}"]`);
  fbox?.classList.add("sel");
}

function toggleInspector() {
  const insp = $("inspector");
  insp.classList.toggle("hidden");
  syncInspectorBtn();
}
function syncInspectorBtn() {
  $("btnInspector").classList.toggle("active", !$("inspector").classList.contains("hidden"));
}

async function openInspector() {
  const { page, id } = S.selection;
  const d = await ensurePageData(page);
  if (!d) return;
  const r = d.regions.find((x) => x.id === id);
  if (!r) return;
  const insp = $("inspector");
  insp.classList.remove("hidden");
  syncInspectorBtn();
  $("inspTitle").textContent = `Region ${id.replace("r", "")} — page ${page}`;
  const q = `p=${enc(page)}&id=${enc(id)}`;
  const trText = (d.translations[id] || "").trim();
  const bs = Math.max(1, parseInt(S.settings.max_batch) || 8);
  const idx = d.regions.indexOf(r);
  const mb = Math.floor(idx / bs);
  const winsize = Math.min(bs, d.regions.length - mb * bs);
  const kv = (k, v, cls) =>
    `<div class="insp-kv"><span class="k">${k}</span><span class="v ${cls || ""}">${v}</span></div>`;

  $("inspBody").innerHTML = `
    <div class="insp-crop-label">OCR input crop</div>
    <img class="insp-crop" src="/img/crop?${q}">
    <div class="insp-crop-label">Detected text</div>
    <div class="insp-text">${esc(r.text || "(empty)")}</div>
    <div class="insp-crop-label">Translation <span class="muted">(auto-saved, re-renders)</span></div>
    <textarea id="trText" placeholder="type translation…">${esc(trText)}</textarea>
    <div class="insp-crop-label">Rendered on page</div>
    ${trText
      ? `<img class="insp-crop" id="renderedCrop" src="/img/render_crop?${q}">`
      : `<div class="muted" style="padding:4px 2px">no translation — region left as-is in render</div>`}

    <details class="insp-sec"><summary>Detection</summary>
      ${kv("class", esc(r.class))}
      ${kv("score", esc(r.score))}
      ${kv("box", esc(r.box.join(", ")))}
      ${kv("ocr box", esc(r.ocr_box.join(", ")) + " <span class='muted'>(pad 12)</span>")}
    </details>
    <details class="insp-sec"><summary>OCR</summary>
      ${kv("confidence", r.confidence ?? "—")}
      ${kv("tokens", r.tokens ?? "—")}
      ${kv("outcome", r.position_limit
          ? "POSITION LIMIT"
          : r.eos ? `EOS @ ${r.eos_position}` : "—", r.position_limit ? "bad" : "good")}
      ${kv("raw text", esc(r.raw_text || "—"))}
      ${kv("token ids", `<span class="mono">${esc((r.token_ids || []).join(" "))}</span>`)}
      <img class="insp-img" src="/img/ocr_input?${q}" title="exact 224×224 normalized OCR input">
    </details>
    <details class="insp-sec"><summary>Batching</summary>
      ${kv("microbatch", `${mb + 1} of ${Math.ceil(d.regions.length / bs)} <span class='muted'>(region ${idx + 1} of ${d.regions.length})</span>`)}
      ${kv("window size", winsize)}
      ${kv("max batch setting", bs)}
      ${kv("encoder runs", d.runs?.encoder ?? "—")}
      ${kv("decoder init runs", d.runs?.decoder_init ?? "—")}
      ${kv("decoder step runs", d.runs?.decoder_step ?? "—")}
    </details>
    <details class="insp-sec"><summary>Translation</summary>
      ${kv("status", trText ? "saved in translation cache" : "not set", trText ? "good" : "")}
      ${r.carried_from ? kv("carried from", esc(r.carried_from) + " <span class='muted'>(box survived re-OCR)</span>") : ""}
    </details>
    <details class="insp-sec"><summary>Render</summary>
      ${kv("text drawn", trText ? "yes" : "no (untranslated)", trText ? "good" : "")}
      ${kv("erase fill", esc(S.settings.erase || "auto"))}
      ${kv("font scale", esc(S.settings.font_scale ?? 1))}
    </details>
    <details class="insp-sec" open><summary>Page performance</summary>
      ${kv("ocr time", `${d.ocr_ms} ms`)}
      ${kv("regions", d.regions.length)}
      ${kv("detected", d.detected ? "yes" : "no")}
    </details>`;

  wireTranslationEditor(page, id);
}

let saveTimer = null;
function wireTranslationEditor(page, id) {
  const ta = $("trText");
  if (!ta) return;
  ta.addEventListener("input", () => {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(async () => {
      try {
        await api("/api/translation", { page, region_id: id, text: ta.value });
        const d = S.pageData.get(page);
        if (d) d.translations[id] = ta.value;
        updateRboxText(page, id, ta.value);
        refreshRenderedImages(page);
        refreshOverview();
      } catch (e) {
        logLine("!! save failed: " + e.message, "err");
      }
    }, 450);
  });
}

function updateRboxText(page, id, text) {
  document.querySelectorAll(`.pg[data-page] .rbox[data-id="${id}"] i, #focusInner .rbox[data-id="${id}"] i`)
    .forEach((i) => { i.textContent = text; });
}

/* Re-fetch render imagery for a page after a translation change. */
function refreshRenderedImages(page) {
  const bust = `&v=${Date.now()}`;
  document.querySelectorAll(".pg-media img").forEach((img) => {
    if (img.src.includes("/img/render?p=") && img.src.includes(`p=${enc(page)}`)) {
      const [base] = img.src.split("&v=");
      img.src = base + bust;
    }
  });
}

$("inspClose").onclick = deselectRegion;

/* ============================================================== modes */
function setMode(mode) {
  S.mode = mode;
  if (mode === "original") S.viz = "original";
  else if (mode === "goal") S.viz = "goal";
  else if (mode === "ours") S.viz = "render";
  document.querySelectorAll("#modeSeg button").forEach((b) =>
    b.classList.toggle("active", b.dataset.mode === mode));
  $("compareBar").classList.toggle("hidden", mode !== "compare");
  syncVizMenu();
  rebuildVisible();
}

function rebuildVisible() {
  document.querySelectorAll(".pg").forEach((sec) => {
    if (sec._builtKey) { sec._builtKey = null; ensureSection(sec.dataset.page); }
  });
  if (S.focus) buildFocus(S.focus);
  recalcOffsets();
  queueVirtualize();
}

document.querySelectorAll("#modeSeg button").forEach((b) => {
  b.onclick = () => setMode(b.dataset.mode);
});

/* ---------------------------------------------------------- compare bar */
document.querySelectorAll("#cmpSeg button").forEach((b) => {
  b.onclick = () => {
    S.compare = b.dataset.cmp;
    document.querySelectorAll("#cmpSeg button").forEach((x) =>
      x.classList.toggle("active", x === b));
    $("overlayCtl").classList.toggle("hidden", S.compare !== "overlay");
    rebuildVisible();
  };
});
$("ovOpacity").oninput = (e) => {
  S.overlayOpacity = e.target.value / 100;
  document.querySelectorAll(".pg-media img.top, .pg-media img[src*='/img/render']").forEach((img) => {
    if (img.classList.contains("top")) img.style.opacity = S.overlayOpacity;
  });
};
let flipTimer = null;
$("ovFlip").onpointerdown = () => {
  document.querySelectorAll(".pg-media img.top").forEach((i) => (i.style.opacity = 0));
};
$("ovFlip").onpointerup = $("ovFlip").onpointerleave = () => {
  document.querySelectorAll(".pg-media img.top").forEach((i) => (i.style.opacity = S.overlayOpacity));
};

/* ======================================================= layers menu */
function buildVizMenu() {
  const list = $("vizList");
  list.innerHTML = "";
  Object.entries(VIZ).forEach(([key, v]) => {
    const b = el("button", "dd-item" + (key === S.viz ? " active" : ""));
    b.innerHTML = v.label + (v.unavailable ? ` <span class="sub">${esc(v.unavailable)}</span>` : "");
    b.onclick = () => {
      S.viz = key;
      if (key === "original") setModeQuiet("original");
      else if (key === "goal") setModeQuiet("goal");
      else if (key === "render") setModeQuiet("ours");
      else { setModeQuiet(null); }
      syncVizMenu();
      closeMenus();
      rebuildVisible();
    };
    list.appendChild(b);
  });
}
function setModeQuiet(mode) {
  if (mode) {
    S.mode = mode;
    document.querySelectorAll("#modeSeg button").forEach((b) =>
      b.classList.toggle("active", b.dataset.mode === mode));
    $("compareBar").classList.add("hidden");
  } else {
    S.mode = "single";
    document.querySelectorAll("#modeSeg button").forEach((b) => b.classList.remove("active"));
    $("compareBar").classList.add("hidden");
  }
}
function syncVizMenu() {
  $("btnLayers").textContent =
    (VIZ[S.viz]?.label || "Render") + (anyMenuOpen($("layersMenu")) ? " ▴" : " ▾");
  buildVizMenu();
}
function anyMenuOpen(m) { return m && !m.classList.contains("hidden"); }

$("btnLayers").onclick = (e) => { e.stopPropagation(); $("layersMenu").classList.toggle("hidden"); };
$("lyRegions").onchange = (e) => { S.layers.regions = e.target.checked; applyLayerFlags(); };
$("lyOcr").onchange = (e) => { S.layers.ocr = e.target.checked; applyLayerFlags(); };
$("confSlider").oninput = (e) => {
  $("confVal").textContent = e.target.value;
  clearTimeout(S.confTimer);
  S.confTimer = setTimeout(async () => {
    const conf = parseFloat(e.target.value);
    S.settings.conf = conf;
    await api("/api/settings", { conf });
    S.pageData.clear();
    refreshOverview();
    rebuildVisible();
    queueVirtualize();
  }, 350);
};

function applyLayerFlags() {
  document.querySelectorAll(".pg-media").forEach(updateLayerClasses);
}

document.addEventListener("click", (e) => {
  if (!e.target.closest(".dd")) closeMenus();
});
function closeMenus() {
  document.querySelectorAll(".dd-menu").forEach((m) => m.classList.add("hidden"));
}

/* ========================================================== processing */
async function runStage(stage) {
  const page = S.current;
  if (!page) return;
  const P = S.processing;
  P[page] = true;
  refreshSectionStatus(page);
  refreshRailDots();
  try {
    if (stage === "all") await api("/api/process", { page, translate: $("chkTranslate").checked });
    else await api(`/api/${stage}`, { page });
    S.pageData.delete(page);
    logLine(`studio: ${stage} done — ${page}`);
  } catch (e) {
    S.pageError[page] = e.message;
    logLine(`!! ${stage} ${page}: ${e.message}`, "err");
  } finally {
    delete P[page];
  }
  S.pageData.delete(page);
  const sec = secEl(page);
  if (sec?._builtKey) { sec._builtKey = null; }
  await refreshOverview();
  queueVirtualize();
}

document.querySelectorAll("#processMenu .dd-item[data-stage]").forEach((b) => {
  b.onclick = () => { closeMenus(); runStage(b.dataset.stage); };
});

$("btnProcess").onclick = (e) => { e.stopPropagation(); $("processMenu").classList.toggle("hidden"); };

/* --------------------------------------------- process chapter loop */
let stageTimer = null;
function startStagePolling() {
  stopStagePolling();
  stageTimer = setInterval(async () => {
    try {
      const l = await api("/api/log");
      for (let i = l.lines.length - 1; i >= 0; i--) {
        const m = l.lines[i].match(/\] (detect|ocr|translate|render) (.+?):/);
        if (m) { $("pcStage").textContent = `${m[1].toUpperCase()} — ${m[2]}`; return; }
      }
    } catch {}
  }, 600);
}
function stopStagePolling() { if (stageTimer) { clearInterval(stageTimer); stageTimer = null; } }

function showProgress(done, total, page) {
  $("progressChip").classList.remove("hidden");
  $("pcText").textContent = `Processing ${done} / ${total}`;
  $("pcFill").style.width = total ? `${(done / total) * 100}%` : "0%";
  $("pcStage").textContent = page ? String(page) : "";
}

async function processChapter() {
  if (S.busy || !S.pages.length) return;
  S.busy = true;
  $("btnProcessChapter").disabled = true;
  startStagePolling();
  const total = S.pages.length;
  let failed = 0;
  for (let i = 0; i < total; i++) {
    const p = S.pages[i];
    S.pageError[p] = null;
    S.processing[p] = true;
    showProgress(i, total, p);
    refreshSectionStatus(p);
    refreshRailDots();
    try {
      await api("/api/process", { page: p, translate: $("chkTranslate").checked });
      S.pageData.delete(p);
      const sec = secEl(p);
      if (sec?._builtKey) sec._builtKey = null;
    } catch (e) {
      failed++;
      S.pageError[p] = e.message.slice(0, 80);
      logLine(`!! process ${p}: ${e.message}`, "err");
    }
    delete S.processing[p];
    await refreshOverview();
    queueVirtualize();
  }
  showProgress(total, total, null);
  $("pcText").textContent = failed ? `Done — ${failed} failed` : "Done";
  setTimeout(() => $("progressChip").classList.add("hidden"), 2200);
  stopStagePolling();
  S.busy = false;
  $("btnProcessChapter").disabled = false;
  logLine(`studio: chapter processed (${total} pages, ${failed} failed)`);
}
$("btnProcessChapter").onclick = processChapter;

/* ========================================================== focus mode */
function enterFocus(page) {
  S.focus = page;
  $("focusTitle").textContent =
    `PAGE ${String(S.pages.indexOf(page) + 1).padStart(2, "0")} — ${page}`;
  $("focusView").classList.remove("hidden");
  buildFocus(page);
  fitFocus();
}

function buildFocus(page) {
  const inner = $("focusInner");
  const frame = el("div", "pg-frame");
  const media = el("div", "pg-media");
  frame.appendChild(media);
  inner.innerHTML = "";
  inner.appendChild(frame);
  buildMediaInto(media, page);
  media.style.width = "100%";
  const d = S.pageData.get(page);
  if (d) { applyFocusOverlays(media, page, d); }
  ensurePageData(page).then((dd) => {
    if (dd && S.focus === page) applyFocusOverlays(media, page, dd);
  });
}

function applyFocusOverlays(media, page, d) {
  if (media._hasOverlays) return;
  const [w, h] = S.dims[page] || [1000, 1400];
  d.regions.forEach((r) => {
    const [x1, y1, x2, y2] = r.box;
    const box = el("div", "rbox");
    box.dataset.id = r.id;
    box.style.left = (x1 / w) * 100 + "%";
    box.style.top = (y1 / h) * 100 + "%";
    box.style.width = ((x2 - x1) / w) * 100 + "%";
    box.style.height = ((y2 - y1) / h) * 100 + "%";
    box.innerHTML =
      `<span class="rbox-tag">${r.id} · ${esc(r.score)}</span>` +
      `<span class="rbox-text">${esc(r.text || "")}</span>`;
    box.onclick = (e) => { e.stopPropagation(); selectRegion(page, r.id); };
    media._overlays.appendChild(box);
  });
  media._hasOverlays = true;
  updateLayerClasses(media);
  markSelection();
}

function exitFocus() {
  S.focus = null;
  $("focusView").classList.add("hidden");
  queueVirtualize();
}
$("focusBack").onclick = exitFocus;

function applyFocusTransform() {
  $("focusInner").style.transform =
    `translate(${S.fz.tx}px, ${S.fz.ty}px) scale(${S.fz.z})`;
}
function fitFocus() {
  const stage = $("focusStage");
  const inner = $("focusInner");
  const [w, h] = S.dims[S.focus] || [1000, 1400];
  const iw = Math.min(stage.clientWidth, 980);
  const ih = (iw * h) / w;
  inner.style.width = iw + "px";
  inner.style.height = ih + "px";
  S.fz.z = Math.min(stage.clientWidth / iw, stage.clientHeight / ih);
  S.fz.tx = (stage.clientWidth - iw * S.fz.z) / 2;
  S.fz.ty = (stage.clientHeight - ih * S.fz.z) / 2;
  applyFocusTransform();
}

/* Ctrl+wheel zoom (strip + focus), plain wheel scrolls strip normally */
function wheelZoom(e) {
  if (!e.ctrlKey) return;
  e.preventDefault();
  const factor = e.deltaY < 0 ? 1.1 : 0.9;
  if (S.focus) {
    const stage = $("focusStage").getBoundingClientRect();
    const mx = e.clientX - stage.left, my = e.clientY - stage.top;
    const nz = Math.min(8, Math.max(0.15, S.fz.z * factor));
    S.fz.tx = mx - ((mx - S.fz.tx) * nz) / S.fz.z;
    S.fz.ty = my - ((my - S.fz.ty) * nz) / S.fz.z;
    S.fz.z = nz;
    applyFocusTransform();
  } else {
    S.zoom = Math.min(3, Math.max(0.35, S.zoom * factor));
    $("strip").style.setProperty("--zoom", S.zoom);
    recalcOffsets();
    queueVirtualize();
  }
}
window.addEventListener("wheel", wheelZoom, { passive: false });

/* Space + drag panning in focus mode */
let pan = null;
$("focusStage").addEventListener("pointerdown", (e) => {
  if (!spaceHeld) return;
  pan = { x: e.clientX, y: e.clientY, tx: S.fz.tx, ty: S.fz.ty };
  $("focusStage").classList.add("panning");
  e.preventDefault();
});
window.addEventListener("pointermove", (e) => {
  if (!pan) return;
  S.fz.tx = pan.tx + e.clientX - pan.x;
  S.fz.ty = pan.ty + e.clientY - pan.y;
  applyFocusTransform();
});
window.addEventListener("pointerup", () => {
  pan = null;
  $("focusStage")?.classList.remove("panning");
});

/* ============================================================== status */
function updateStatusBar() {
  const i = S.pages.indexOf(S.current);
  const d = i >= 0 ? S.pageData.get(S.current) : null;
  const row = S.overview?.pages[i];
  const bits = [];
  if (i >= 0) bits.push(`Page ${i + 1} / ${S.pages.length}`);
  if (d) {
    bits.push(VIZ[S.viz]?.label || "Render");
    if (d.ocr_ms) bits.push(`OCR ${d.ocr_ms} ms`);
    bits.push(`${d.regions.length} regions`);
  } else if (row) {
    if (row.ocr_ms) bits.push(`OCR ${row.ocr_ms} ms`);
    if (row.regions) bits.push(`${row.regions} regions`);
  }
  bits.push(`Batch ${S.settings.max_batch ?? 8}`);
  $("sbLeft").textContent = bits.join("   ·   ");
  $("sbRight").textContent = S.chapter ? S.chapter.split(/[\\/]/).filter(Boolean).pop() : "";
}

/* =============================================================== logs */
function openLogs() {
  $("consolePanel").classList.remove("hidden");
  $("btnLogs").textContent = "Hide Logs";
  tailServerLogs();
}
function closeLogs() {
  $("consolePanel").classList.add("hidden");
  $("btnLogs").textContent = "Show Logs";
}
$("btnLogs").onclick = () =>
  $("consolePanel").classList.contains("hidden") ? openLogs() : closeLogs();
$("consoleClear").onclick = () => { $("console").innerHTML = ""; logBuffer.length = 0; };

/* drag-resize console */
(() => {
  let drag = null;
  $("consoleDrag").addEventListener("pointerdown", (e) => {
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

/* ========================================================= overview dlg */
$("btnOverview").onclick = async () => {
  const ov = await api("/api/overview");
  S.overview = ov;
  refreshAllStatus();
  const rows = ov.pages;
  const n = rows.length;
  const sum = (f) => rows.reduce((a, r) => a + (f(r) || 0), 0);
  const det = sum((r) => r.detected), ocr = sum((r) => r.ocr), ren = sum((r) => r.rendered);
  const trT = sum((r) => r.translated), trN = sum((r) => r.regions);
  const bar = (done, total) =>
    `<div class="bar"><div style="width:${total ? (done / total) * 100 : 0}%"></div></div>`;
  const row = (k, done, total, label) =>
    `<div class="ov-row"><span class="k">${k}</span>${bar(done, total)}` +
    `<span class="n">${total ? `${done} / ${total}` : "—"}${label ? ` ${label}` : ""}</span></div>`;
  const slow = ov.slowest && ov.slowest.ocr_ms
    ? `<button class="ov-link" data-go="${esc(ov.slowest.page)}">` +
      `<span class="badge slow">SLOW</span> Page ${S.pages.indexOf(ov.slowest.page) + 1} — ` +
      `OCR ${(ov.slowest.ocr_ms / 1000).toFixed(1)}s</button>`
    : '<div class="muted" style="padding:4px">no timings yet</div>';
  const errs = ov.errors.length
    ? ov.errors.map((e) =>
        `<button class="ov-link" data-go="${esc(e.page)}">` +
        `<span class="badge">ERR</span> Page ${S.pages.indexOf(e.page) + 1} — ${esc(e.reason)}</button>`).join("")
    : '<div class="muted" style="padding:4px">none</div>';
  $("ovBody").innerHTML =
    `<div class="ov-row"><span class="k">Pages</span><span class="n" style="text-align:left">${n}</span></div>` +
    row("Detect", det, n) + row("OCR", ocr, n) +
    row("Translate", trT, trN, "regions") + row("Render", ren, n) +
    `<div class="ov-row"><span class="k">Inpaint</span><span class="muted">not produced by this harness</span></div>` +
    `<div class="ov-sub">Slowest page</div>${slow}` +
    `<div class="ov-sub">Errors</div>${errs}`;
  $("ovBody").querySelectorAll("[data-go]").forEach((b) => {
    b.onclick = () => { $("overviewDlg").close(); scrollToPage(b.dataset.go); };
  });
  $("overviewDlg").showModal();
};
$("ovClose").onclick = () => $("overviewDlg").close();

/* ================================================== open + goto + settings */
function openChapterDlg() {
  $("chapterDir").value = $("chapterDir").value || S.chapter || localStorage.getItem("studio.chapter") || "";
  $("referenceDir").value = $("referenceDir").value || S.reference || localStorage.getItem("studio.reference") || "";
  $("openDlg").showModal();
}
$("btnOpen").onclick = openChapterDlg;
$("btnOpenEmpty").onclick = openChapterDlg;
$("openCancel").onclick = () => $("openDlg").close();
$("btnDoOpen").onclick = doOpen;
$("chapterDir").addEventListener("keydown", (e) => { if (e.key === "Enter") doOpen(); });

async function doOpen() {
  const chapter = $("chapterDir").value.trim();
  if (!chapter) return;
  try {
    const st = await api("/api/open", {
      chapter, reference: $("referenceDir").value.trim() || null,
    });
    $("openDlg").close();
    applyState(st);
    await refreshOverview();
    recalcOffsets();
    $("viewer").scrollTop = 0;
    queueVirtualize();
    logLine(`studio: opened ${st.chapter} — ${st.pages.length} pages`);
  } catch (e) {
    logLine("!! open: " + e.message, "err");
  }
}

function applyState(st) {
  S.chapter = st.chapter;
  S.reference = st.reference;
  S.pages = st.pages;
  S.dims = st.dims || {};
  S.settings = st.settings || {};
  S.pageData.clear();
  S.overview = null;
  S.current = null;
  S.selection = null;
  $("confSlider").value = S.settings.conf ?? 0.45;
  $("confVal").textContent = S.settings.conf ?? 0.45;
  const name = S.chapter ? S.chapter.split(/[\\/]/).filter(Boolean).pop() : null;
  $("chapterInfo").innerHTML = name
    ? `<b>${esc(name)}</b> · ${S.pages.length} pages` +
      (S.reference ? ` · goal: ${esc(S.reference.split(/[\\/]/).filter(Boolean).pop())}` : "")
    : "";
  if (S.chapter) {
    localStorage.setItem("studio.chapter", S.chapter);
    localStorage.setItem("studio.reference", S.reference || "");
  }
  buildRail();
  buildStrip();
  buildVizMenu();
  updateStatusBar();
  $("inspector").classList.add("hidden");
  syncInspectorBtn();
}

/* folder picker (unchanged pipeline: /api/fs) */
let folderTarget = null;
async function browseFs(path) {
  const d = await api(`/api/fs?path=${encodeURIComponent(path)}`);
  $("folderPath").value = d.path || "";
  $("folderCurrent").textContent = d.error ? `!! ${d.error}` : d.path;
  const ul = $("folderList");
  ul.innerHTML = d.entries.length ? "" : '<li class="muted">(no subfolders)</li>';
  d.entries.forEach((e2) => {
    const li = el("li", null, "📁 " + e2.name);
    li.onclick = () => browseFs(e2.path);
    ul.appendChild(li);
  });
  const quick = $("folderQuick");
  quick.innerHTML = "";
  const mk = (label, path2) => {
    const b = el("button", null, label);
    b.onclick = () => browseFs(path2);
    quick.appendChild(b);
  };
  mk("Home", d.home);
  (d.roots || []).forEach((r) => mk(r.name, r.path));
}
function openFolderPicker(target) {
  folderTarget = target;
  $("folderTitle").textContent =
    target === "chapterDir" ? "Pick chapter folder" : "Pick reference (goal) folder";
  $("folderDlg").showModal();
  browseFs($(target).value.trim() || "");
}
$("browseChapter").onclick = () => openFolderPicker("chapterDir");
$("browseReference").onclick = () => openFolderPicker("referenceDir");
$("folderGo").onclick = () => browseFs($("folderPath").value);
$("folderPath").addEventListener("keydown", (e) => { if (e.key === "Enter") browseFs($("folderPath").value); });
$("folderUp").onclick = async () => {
  const d = await api(`/api/fs?path=${encodeURIComponent($("folderPath").value)}`);
  browseFs(d.parent || d.path);
};
$("folderCancel").onclick = () => $("folderDlg").close();
$("folderSelect").onclick = () => {
  const picked = $("folderPath").value;
  if (!picked) return;
  $(folderTarget).value = picked;
  $("folderDlg").close();
};

/* goto page */
$("btnSettings").onclick = async () => {
  const s = (await api("/api/state")).settings;
  $("setOcrEngine").value = s.ocr_engine || "mangaocr";
  $("setInpaintMode").value = s.inpaint_mode || "fast";
  $("setTranslateBackend").value = s.translate_backend || "google";
  $("setConf").value = s.conf;
  $("setMaxBatch").value = s.max_batch;
  $("setErase").value = s.erase;
  $("setFont").value = s.font_path || "";
  $("setFontScale").value = s.font_scale;
  $("setEndpoint").value = s.endpoint;
  $("setModel").value = s.model;
  $("setLang").value = s.target_lang;
  $("settingsDlg").showModal();
};
$("setClose").onclick = () => $("settingsDlg").close();
$("setSave").onclick = async () => {
  const s = await api("/api/settings", {
    ocr_engine: $("setOcrEngine").value,
    inpaint_mode: $("setInpaintMode").value,
    translate_backend: $("setTranslateBackend").value,
    conf: parseFloat($("setConf").value),
    max_batch: parseInt($("setMaxBatch").value),
    erase: $("setErase").value,
    font_path: $("setFont").value.trim(),
    font_scale: parseFloat($("setFontScale").value) || 1.0,
    endpoint: $("setEndpoint").value.trim(),
    model: $("setModel").value.trim(),
    target_lang: $("setLang").value.trim(),
  });
  S.settings = s.settings;
  S.pageData.clear();
  rebuildVisible();
  $("settingsDlg").close();
  logLine("studio: settings saved");
};

document.addEventListener("keydown", (e) => {
  if (e.key === "g" && e.ctrlKey) {
    e.preventDefault();
    $("gotoInput").value = String(S.pages.indexOf(S.current) + 1 || 1);
    $("gotoDlg").showModal();
    $("gotoInput").select();
  }
});
$("gotoGo").onclick = doGoto;
$("gotoInput").addEventListener("keydown", (e) => { if (e.key === "Enter") doGoto(); });
function doGoto() {
  const n = parseInt($("gotoInput").value);
  if (n >= 1 && n <= S.pages.length) scrollToPage(S.pages[n - 1]);
  $("gotoDlg").close();
}

/* ======================================================= keyboard maps */
let spaceHeld = false;
window.addEventListener("keydown", (e) => {
  const typing = e.target.closest?.("input, textarea, select");
  const dialogOpen = document.querySelector("dialog[open]");
  if (e.code === "Space" && S.focus && !typing) { spaceHeld = true; $("focusStage").classList.add("pannable"); }
  if (typing || dialogOpen) return;
  if (e.ctrlKey || e.metaKey || e.altKey) return;   // keep browser shortcuts alive
  switch (e.key) {
    case "1": setMode("original"); break;
    case "2": setMode("goal"); break;
    case "3": setMode("ours"); break;
    case "c": case "C": setMode("compare"); break;
    case "i": case "I": toggleInspector(); break;
    case "l": case "L": $("layersMenu").classList.toggle("hidden"); break;
    case "f": case "F":
      if (S.focus) fitFocus();
      else { S.zoom = 1; $("strip").style.setProperty("--zoom", 1); recalcOffsets(); queueVirtualize(); }
      break;
    case "+": case "=":
      if (S.focus) { S.fz.z = Math.min(8, S.fz.z * 1.2); applyFocusTransform(); }
      else { S.zoom = Math.min(3, S.zoom * 1.2); $("strip").style.setProperty("--zoom", S.zoom); recalcOffsets(); queueVirtualize(); }
      break;
    case "-":
      if (S.focus) { S.fz.z = Math.max(0.15, S.fz.z / 1.2); applyFocusTransform(); }
      else { S.zoom = Math.max(0.35, S.zoom / 1.2); $("strip").style.setProperty("--zoom", S.zoom); recalcOffsets(); queueVirtualize(); }
      break;
    case "Escape":
      if (anyMenuOpen($("layersMenu")) || anyMenuOpen($("processMenu"))) closeMenus();
      else if (S.focus) exitFocus();
      else deselectRegion();
      break;
  }
});
window.addEventListener("keyup", (e) => {
  if (e.code === "Space") { spaceHeld = false; $("focusStage")?.classList.remove("pannable"); }
});

/* ================================================================= boot */
(async function boot() {
  buildVizMenu();
  try {
    const st = await api("/api/state");
    if (st.chapter) {
      applyState(st);
      await refreshOverview();
      recalcOffsets();
      queueVirtualize();
    } else {
      $("emptyState").classList.remove("hidden");
    }
  } catch {
    $("emptyState").classList.remove("hidden");
  }
  try {
    const l = await api("/api/log");
    l.lines.forEach((x) => logLine(x));
  } catch {}
})();
