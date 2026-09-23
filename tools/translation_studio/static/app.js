/* Translation Studio — Studio-grade viewer over the existing pipeline.
   Vanilla JS, zero build step. Modernized UI/UX architecture. */
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
  mode: "ours",            // original | inpainted | goal | ours | compare
  compare: "orig-ours",    // orig-ours | goal-ours | all | overlay
  overlayOpacity: 0.55,
  viz: "render",           // primary visualization
  layers: { regions: true, ocr: true, masks: true },
  tree: {
    boxBubble: true,
    boxBubbleText: true,
    boxFreeText: true,
    maskSeg: true,
    maskInpaint: false,
    typeBadges: true,
    textBadges: true,
  },
  pageData: new Map(),     // page -> /api/page json
  current: null,
  selection: null,         // { page, id }
  zoom: 1,
  focus: null,             // page name while in focused page mode
  fz: { z: 1, tx: 0, ty: 0 },
  busy: false,
  processing: {},          // page -> true while in flight
  pageError: {},           // page -> message
  confTimer: null,
  recent: [],
  cacheBust: 0,
};

/* Primary visualization table. */
const VIZ = {
  render:       { label: "Render",        src: (p) => `/img/render?p=${enc(p)}${S.cacheBust ? `&t=${S.cacheBust}` : ""}` },
  original:     { label: "Original",      src: (p) => `/img/original?p=${enc(p)}` },
  inpainted:    { label: "Inpainted",     src: (p) => `/img/inpainted?p=${enc(p)}${S.cacheBust ? `&t=${S.cacheBust}` : ""}` },
  goal:         { label: "Goal",          src: (p) => `/img/goal?p=${enc(p)}` },
  detection:    { label: "Detection",     src: (p) => `/img/overlay?p=${enc(p)}${S.cacheBust ? `&t=${S.cacheBust}` : ""}` },
  ocr:          { label: "OCR",           src: (p) => `/img/original?p=${enc(p)}` },
  inpaint_mask: { label: "Inpaint Mask",  src: (p) => `/img/inpaint_mask?p=${enc(p)}${S.cacheBust ? `&t=${S.cacheBust}` : ""}` },
  segmentation: { label: "Segmentation",  src: (p) => `/img/segmentation?p=${enc(p)}${S.cacheBust ? `&t=${S.cacheBust}` : ""}` },
  translation:  { label: "Translation",   src: (p) => `/img/render?p=${enc(p)}${S.cacheBust ? `&t=${S.cacheBust}` : ""}` },
  regions:      { label: "Regions",       src: (p) => `/img/original?p=${enc(p)}` },
  overlay:      { label: "Overlay",       src: (p) => `/img/render?p=${enc(p)}${S.cacheBust ? `&t=${S.cacheBust}` : ""}` },
};

function imgSrcFor(kind, page) {
  if (kind === "original") return VIZ.original.src(page);
  if (kind === "inpainted") return VIZ.inpainted.src(page);
  if (kind === "goal") return VIZ.goal.src(page);
  if (kind === "inpaint_mask") return VIZ.inpaint_mask.src(page);
  if (kind === "segmentation") return VIZ.segmentation.src(page);
  return VIZ.render.src(page);
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
  if (!rail) return;
  rail.innerHTML = "";
  $("railEmpty")?.classList.toggle("hidden", S.pages.length > 0);
  S.pages.forEach((p, i) => {
    const item = el("div", "nav-item" + (p === S.current ? " current" : ""));
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
  refreshRailDots();
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
    if (dot) dot.className = "nav-dot " + dotState(item.dataset.page);
  });
}

function scrollToPage(p) {
  const sec = secEl(p);
  if (sec) {
    sec.scrollIntoView({ behavior: "smooth", block: "start" });
    setCurrent(p);
  }
}

/* ================================================================ strip */
let stripObserver = null;
let currentObserver = null;

function buildStrip() {
  const strip = $("strip");
  if (!strip) return;
  strip.innerHTML = "";
  $("emptyState")?.classList.toggle("hidden", S.pages.length > 0);

  if (!S.pages.length) return;

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

  // Eagerly populate initial pages immediately (guarantees image display instantly)
  const preloadCount = Math.min(5, S.pages.length);
  for (let i = 0; i < preloadCount; i++) {
    const p = S.pages[i];
    ensureSection(p);
    ensurePageData(p).then((d) => {
      if (d) { applyOverlays(p); refreshSectionStatus(p); }
    });
  }

  setupVirtualization();
  if (S.pages.length > 0 && !S.current) {
    setCurrent(S.pages[0]);
  }
}

const secEl = (p) => document.querySelector(`.pg[data-page="${CSS.escape(p)}"]`);

function wantKey() {
  return S.mode === "compare" ? `cmp:${S.compare}` : `viz:${S.viz}`;
}

function addImg(parent, cls, src, fallbackSrc) {
  const img = document.createElement("img");
  img.className = cls;
  img.decoding = "async";
  img.onerror = () => {
    if (fallbackSrc && img.src !== fallbackSrc && !img._triedFallback) {
      img._triedFallback = true;
      img.src = fallbackSrc;
      return;
    }
    img.classList.add("missing");
  };
  img.src = src;
  parent.appendChild(img);
  return img;
}

/* Builds media imagery for one page. Guarantees fallback to original manga page. */
function buildMediaInto(media, page) {
  media.innerHTML = "";
  const [w, h] = S.dims[page] || [1000, 1400];
  media.style.aspectRatio = `${w}/${h}`;
  let hit = media;
  let notice = "";
  const origSrc = VIZ.original.src(page);

  if (S.mode === "compare" && S.compare === "overlay") {
    media.classList.add("stack");
    addImg(media, "ph", origSrc);
    const top = addImg(media, "ph top", imgSrcFor("ours", page), origSrc);
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
      const fallback = kind !== "original" ? origSrc : null;
      addImg(cell, "ph", imgSrcFor(kind, page), fallback);
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
      src = origSrc;
      notice = `${v.label}: ${v.unavailable}`;
    }
    addImg(media, "ph", src, origSrc);
    if (S.viz === "overlay") {
      addImg(media, "ph", origSrc).style.zIndex = 1;
      media.lastChild.style.zIndex = 0;
      const top = media.firstChild;
      top.style.zIndex = 2;
      top.style.opacity = S.overlayOpacity;
    }
    if (notice) showNotice(media, notice);
  }

  // Transparent segmentation mask overlay layer
  const maskImg = el("img", "pg-mask-layer");
  maskImg.dataset.src = `/img/seg_overlay?p=${enc(page)}${S.cacheBust ? `&t=${S.cacheBust}` : ""}`;
  if (S.tree?.maskSeg) maskImg.src = maskImg.dataset.src;
  hit.appendChild(maskImg);

  // Inpaint erase mask layer
  const inpaintMaskImg = el("img", "pg-inpaint-mask-layer");
  inpaintMaskImg.dataset.src = `/img/inpaint_mask?p=${enc(page)}${S.cacheBust ? `&t=${S.cacheBust}` : ""}`;
  if (S.tree?.maskInpaint) inpaintMaskImg.src = inpaintMaskImg.dataset.src;
  hit.appendChild(inpaintMaskImg);

  const ov = el("div", "pg-overlays");
  ov.style.position = "absolute";
  hit.appendChild(ov);
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
  const t = S.tree || {};
  media.classList.toggle("show-box-bubble", !!t.boxBubble);
  media.classList.toggle("show-box-text", !!t.boxBubbleText);
  media.classList.toggle("show-box-freetext", !!t.boxFreeText);
  media.classList.toggle("show-type-badges", !!t.typeBadges);
  media.classList.toggle("show-text-badges", !!t.textBadges);
  media.classList.toggle("show-mask-seg", !!t.maskSeg);
  media.classList.toggle("show-mask-inpaint", !!t.maskInpaint);

  // Legacy classes for backwards compatibility
  media.classList.toggle("show-regions", !!(t.boxBubble || t.boxBubbleText || t.boxFreeText));
  media.classList.toggle("show-ocr", !!t.textBadges);
  media.classList.toggle("show-masks", !!t.maskSeg);

  if (t.maskSeg) {
    const maskImg = media.querySelector(".pg-mask-layer");
    if (maskImg && !maskImg.src && maskImg.dataset.src) {
      maskImg.src = maskImg.dataset.src;
    }
  }
  if (t.maskInpaint) {
    const inpaintImg = media.querySelector(".pg-inpaint-mask-layer");
    if (inpaintImg && !inpaintImg.src && inpaintImg.dataset.src) {
      inpaintImg.src = inpaintImg.dataset.src;
    }
  }
}

/* ---------------------------------------------------- virtualization */
function ensureSection(p) {
  const sec = secEl(p);
  if (!sec || sec._builtKey === wantKey()) return;
  const media = sec.querySelector(".pg-media");
  if (!media) return;
  buildMediaInto(media, p);
  sec._builtKey = wantKey();
  const d = S.pageData.get(p);
  if (d) applyOverlays(p);
}

function evictSection(p) {
  const sec = secEl(p);
  if (!sec || sec._builtKey === null) return;
  const media = sec.querySelector(".pg-media");
  if (!media) return;
  media.innerHTML = "";
  media.className = "pg-media";
  media.removeAttribute("style");
  const [w, h] = S.dims[p] || [1000, 1400];
  media.style.aspectRatio = `${w}/${h}`;
  sec._builtKey = null;
}

function setupVirtualization() {
  if (stripObserver) stripObserver.disconnect();
  if (currentObserver) currentObserver.disconnect();

  const viewer = $("viewer");
  if (!viewer) return;

  // Viewport virtualizer: renders pages within 800px of view
  stripObserver = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      const p = entry.target.dataset.page;
      if (entry.isIntersecting) {
        ensureSection(p);
        ensurePageData(p).then((d) => {
          if (d) { applyOverlays(p); refreshSectionStatus(p); }
        });
      } else if (S.pages.length > 40) {
        const r = entry.boundingClientRect;
        const vr = viewer.getBoundingClientRect();
        if (Math.abs(r.top - vr.top) > 2500) {
          evictSection(p);
        }
      }
    });
  }, {
    root: viewer,
    rootMargin: "800px 0px 800px 0px",
  });

  // Current page tracker for rail dot & status bar
  currentObserver = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        setCurrent(entry.target.dataset.page);
      }
    });
  }, {
    root: viewer,
    rootMargin: "-10% 0px -70% 0px",
  });

  document.querySelectorAll(".pg").forEach((sec) => {
    stripObserver.observe(sec);
    currentObserver.observe(sec);
  });
}

function queueVirtualize() {
  // Retained for manual triggers (e.g. zoom, rebuild)
  document.querySelectorAll(".pg").forEach((sec) => {
    const rect = sec.getBoundingClientRect();
    const vrect = $("viewer").getBoundingClientRect();
    if (rect.bottom >= vrect.top - 800 && rect.top <= vrect.bottom + 800) {
      ensureSection(sec.dataset.page);
    }
  });
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
  const conf = S.settings?.conf ?? 0.45;

  // 1. Parent Speech Bubbles (detector label 0)
  (d.raw_boxes || [])
    .filter((b) => b.label === 0 && (b.score === undefined || b.score >= conf))
    .forEach((b, idx) => {
      const [x1, y1, x2, y2] = b.box;
      const box = el("div", "rbox rbox-bubble");
      box.style.left = (x1 / w) * 100 + "%";
      box.style.top = (y1 / h) * 100 + "%";
      box.style.width = ((x2 - x1) / w) * 100 + "%";
      box.style.height = ((y2 - y1) / h) * 100 + "%";
      box.title = `Speech Bubble ${idx + 1} · score ${b.score}`;
      box.innerHTML = `<span class="rbox-tag">[BUBBLE #${idx + 1}] · ${esc(b.score)}</span>`;
      media._overlays.appendChild(box);
    });

  // 2. Text Regions
  if (d.regions && d.regions.length > 0) {
    d.regions
      .filter((r) => r.score === undefined || r.score >= conf)
      .forEach((r) => {
        const [x1, y1, x2, y2] = r.box;
        const isFree = r.label === 2;
        const kindCls = isFree ? "rbox-freetext" : "rbox-text";
        const typeTag = isFree ? `[FREE_TEXT ${r.id}]` : `[BUBBLE_TEXT ${r.id}]`;
        const box = el("div", "rbox " + kindCls);
        box.dataset.id = r.id;
        box.style.left = (x1 / w) * 100 + "%";
        box.style.top = (y1 / h) * 100 + "%";
        box.style.width = ((x2 - x1) / w) * 100 + "%";
        box.style.height = ((y2 - y1) / h) * 100 + "%";
        const tr = (d.translations[r.id] || "").trim();
        box.title = `${r.class} · score ${r.score}` + (r.text ? `\nOCR: ${r.text}` : "") + (tr ? `\nTR: ${tr}` : "");
        box.innerHTML =
          `<span class="rbox-tag">${typeTag} · ${esc(r.score)}</span>` +
          `<span class="rbox-text"><b>${esc(r.text || "")}</b>${tr ? ` <i>${esc(tr)}</i>` : ""}</span>`;
        box.onclick = (e) => { e.stopPropagation(); selectRegion(p, r.id); };
        media._overlays.appendChild(box);
      });
  } else if (d.raw_boxes && d.raw_boxes.length > 0) {
    d.raw_boxes
      .filter((b) => b.label !== 0 && (b.score === undefined || b.score >= conf))
      .forEach((b, idx) => {
        const [x1, y1, x2, y2] = b.box;
        const isFree = b.label === 2;
        const kindCls = isFree ? "rbox-freetext" : "rbox-text";
        const typeTag = isFree ? `[FREE_TEXT #${idx + 1}]` : `[BUBBLE_TEXT #${idx + 1}]`;
        const box = el("div", "rbox " + kindCls);
        box.style.left = (x1 / w) * 100 + "%";
        box.style.top = (y1 / h) * 100 + "%";
        box.style.width = ((x2 - x1) / w) * 100 + "%";
        box.style.height = ((y2 - y1) / h) * 100 + "%";
        box.title = `Text ${idx + 1} (${b.class}) · ${b.score}`;
        box.innerHTML = `<span class="rbox-tag">${typeTag} · ${esc(b.score)}</span>`;
        media._overlays.appendChild(box);
      });
  }

  media._hasOverlays = true;
  if (S.selection && S.selection.page === p) markSelection();
}

/* media click → region hit-test */
$("strip")?.addEventListener("click", (e) => {
  const media = e.target.closest(".pg-media");
  if (!media || (S.mode === "compare" && S.compare !== "overlay")) return;
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

$("strip")?.addEventListener("dblclick", (e) => {
  const sec = e.target.closest(".pg");
  if (sec) enterFocus(sec.dataset.page);
});

function setCurrent(p) {
  if (S.current === p) return;
  S.current = p;
  document.querySelectorAll(".nav-item").forEach((item) =>
    item.classList.toggle("current", item.dataset.page === p));
  updateStatusBar();
}

/* ----------------------------------------------- page status headers */
function refreshSectionStatus(p) {
  const sec = secEl(p);
  if (!sec) return;
  const stEl = sec.querySelector("[data-status]");
  if (!stEl) return;
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
  switchSidebarTab("insp");
  syncInspectorBtn();
}

function deselectRegion() {
  S.selection = null;
  markSelection();
  syncInspectorBtn();
  const b = $("inspBody");
  if (b) b.innerHTML = '<div class="muted pad">Click a text region on a page to inspect it.</div>';
}

function syncInspectorBtn() {
  const hasSel = !!S.selection;
  const badge = $("inspBadge");
  if (badge) {
    badge.textContent = hasSel ? S.selection.id : "0";
    badge.classList.toggle("hidden", !hasSel);
  }
}

function markSelection() {
  document.querySelectorAll(".rbox.sel").forEach((b) => b.classList.remove("sel"));
  if (!S.selection) return;
  const sec = secEl(S.selection.page);
  const box = sec?.querySelector(`.rbox[data-id="${S.selection.id}"]`);
  box?.classList.add("sel");
  const fbox = document.querySelector(`#focusInner .rbox[data-id="${S.selection.id}"]`);
  fbox?.classList.add("sel");
}

async function openInspector() {
  const { page, id } = S.selection;
  const d = await ensurePageData(page);
  if (!d) return;
  const r = d.regions.find((x) => x.id === id);
  if (!r) return;
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

function refreshRenderedImages(page) {
  S.cacheBust = Date.now();
  const bust = `&t=${S.cacheBust}`;
  document.querySelectorAll(".pg-media img").forEach((img) => {
    if (img.src.includes("/img/render?p=") && (!page || img.src.includes(`p=${enc(page)}`))) {
      const [base] = img.src.split("&t=")[0].split("&v=");
      img.src = base + bust;
    }
  });
}

/* ============================================================== modes */
function setMode(mode) {
  S.mode = mode;
  if (mode === "original") S.viz = "original";
  else if (mode === "inpainted") S.viz = "inpainted";
  else if (mode === "goal") S.viz = "goal";
  else if (mode === "ours") S.viz = "render";
  document.querySelectorAll("#modeSeg button").forEach((b) =>
    b.classList.toggle("active", b.dataset.mode === mode));
  $("sbCompareCard")?.classList.toggle("hidden", mode !== "compare");
  syncOverlayButtons();
  rebuildVisible();
}

function setModeQuiet(mode) {
  if (mode) {
    S.mode = mode;
    document.querySelectorAll("#modeSeg button").forEach((b) =>
      b.classList.toggle("active", b.dataset.mode === mode));
    $("sbCompareCard")?.classList.add("hidden");
  } else {
    S.mode = "single";
    document.querySelectorAll("#modeSeg button").forEach((b) => b.classList.remove("active"));
    $("sbCompareCard")?.classList.add("hidden");
  }
}

function rebuildVisible() {
  document.querySelectorAll(".pg").forEach((sec) => {
    sec._builtKey = null;
    ensureSection(sec.dataset.page);
  });
  if (S.focus) buildFocus(S.focus);
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
    $("overlayCtl")?.classList.toggle("hidden", S.compare !== "overlay");
    rebuildVisible();
  };
});

if ($("ovOpacity")) {
  $("ovOpacity").oninput = (e) => {
    S.overlayOpacity = e.target.value / 100;
    document.querySelectorAll(".pg-media img.top").forEach((img) => {
      img.style.opacity = S.overlayOpacity;
    });
  };
}

if ($("ovFlip")) {
  $("ovFlip").onpointerdown = () => {
    document.querySelectorAll(".pg-media img.top").forEach((i) => (i.style.opacity = 0));
  };
  $("ovFlip").onpointerup = $("ovFlip").onpointerleave = () => {
    document.querySelectorAll(".pg-media img.top").forEach((i) => (i.style.opacity = S.overlayOpacity));
  };
}

/* ======================================================= layer toggles & viz tree */
function initVizTree() {
  const tree = S.tree;

  // Branch collapse toggles
  document.querySelectorAll(".tree-header").forEach((hdr) => {
    hdr.addEventListener("click", (e) => {
      if (e.target.tagName === "INPUT") return;
      hdr.closest(".tree-branch")?.classList.toggle("collapsed");
    });
  });

  // Group Checkboxes
  $("treeBoxAll")?.addEventListener("change", (e) => {
    const val = e.target.checked;
    tree.boxBubble = val;
    tree.boxBubbleText = val;
    tree.boxFreeText = val;
    syncVizTreeCheckboxes();
    applyLayerFlags();
  });

  $("treeMaskAll")?.addEventListener("change", (e) => {
    const val = e.target.checked;
    tree.maskSeg = val;
    tree.maskInpaint = val;
    syncVizTreeCheckboxes();
    applyLayerFlags();
  });

  $("treeLabelAll")?.addEventListener("change", (e) => {
    const val = e.target.checked;
    tree.typeBadges = val;
    tree.textBadges = val;
    syncVizTreeCheckboxes();
    applyLayerFlags();
  });

  // Leaf Checkboxes
  const leafMap = [
    ["treeBoxBubble", "boxBubble"],
    ["treeBoxBubbleText", "boxBubbleText"],
    ["treeBoxFreeText", "boxFreeText"],
    ["treeMaskSeg", "maskSeg"],
    ["treeMaskInpaint", "maskInpaint"],
    ["treeTypeBadges", "typeBadges"],
    ["treeTextBadges", "textBadges"],
  ];

  leafMap.forEach(([elId, key]) => {
    $(elId)?.addEventListener("change", (e) => {
      tree[key] = e.target.checked;
      syncVizTreeCheckboxes();
      applyLayerFlags();
    });
  });

  // Reset Button
  $("btnTreeReset")?.addEventListener("click", () => {
    tree.boxBubble = true;
    tree.boxBubbleText = true;
    tree.boxFreeText = true;
    tree.maskSeg = true;
    tree.maskInpaint = false;
    tree.typeBadges = true;
    tree.textBadges = true;
    syncVizTreeCheckboxes();
    applyLayerFlags();
  });

  syncVizTreeCheckboxes();
}

function syncVizTreeCheckboxes() {
  const tree = S.tree;
  if (!tree) return;
  if ($("treeBoxBubble")) $("treeBoxBubble").checked = !!tree.boxBubble;
  if ($("treeBoxBubbleText")) $("treeBoxBubbleText").checked = !!tree.boxBubbleText;
  if ($("treeBoxFreeText")) $("treeBoxFreeText").checked = !!tree.boxFreeText;

  const boxCount = (tree.boxBubble ? 1 : 0) + (tree.boxBubbleText ? 1 : 0) + (tree.boxFreeText ? 1 : 0);
  if ($("treeBoxCount")) $("treeBoxCount").textContent = boxCount;
  if ($("treeBoxAll")) {
    $("treeBoxAll").checked = boxCount > 0;
    $("treeBoxAll").indeterminate = boxCount > 0 && boxCount < 3;
  }

  if ($("treeMaskSeg")) $("treeMaskSeg").checked = !!tree.maskSeg;
  if ($("treeMaskInpaint")) $("treeMaskInpaint").checked = !!tree.maskInpaint;
  const maskCount = (tree.maskSeg ? 1 : 0) + (tree.maskInpaint ? 1 : 0);
  if ($("treeMaskCount")) $("treeMaskCount").textContent = maskCount;
  if ($("treeMaskAll")) {
    $("treeMaskAll").checked = maskCount > 0;
    $("treeMaskAll").indeterminate = maskCount > 0 && maskCount < 2;
  }

  if ($("treeTypeBadges")) $("treeTypeBadges").checked = !!tree.typeBadges;
  if ($("treeTextBadges")) $("treeTextBadges").checked = !!tree.textBadges;
  const labelCount = (tree.typeBadges ? 1 : 0) + (tree.textBadges ? 1 : 0);
  if ($("treeLabelCount")) $("treeLabelCount").textContent = labelCount;
  if ($("treeLabelAll")) {
    $("treeLabelAll").checked = labelCount > 0;
    $("treeLabelAll").indeterminate = labelCount > 0 && labelCount < 2;
  }
}

function syncOverlayButtons() {
  syncVizTreeCheckboxes();
}

function applyLayerFlags() {
  document.querySelectorAll(".pg-media").forEach(updateLayerClasses);
}

/* ==================================== Right Sidebar Pipeline Configuration */
function syncSettingsUI() {
  const s = S.settings || {};
  // OCR Engine
  const ocr = s.ocr_engine || "mangaocr";
  if ($("cfgOcrTag")) $("cfgOcrTag").textContent = ocr;
  document.querySelectorAll("#cfgOcrSeg button").forEach((b) =>
    b.classList.toggle("active", b.dataset.engine === ocr));

  // Inpaint Mode
  const inp = s.inpaint_mode || "quality";
  if ($("cfgInpaintTag")) $("cfgInpaintTag").textContent = inp;
  document.querySelectorAll("#cfgInpaintSeg button").forEach((b) =>
    b.classList.toggle("active", b.dataset.inpaint === inp));

  // Translate Backend
  const tr = s.translate_backend || "google";
  if ($("cfgTranslateTag")) $("cfgTranslateTag").textContent = tr;
  document.querySelectorAll("#cfgTranslateSeg button").forEach((b) =>
    b.classList.toggle("active", b.dataset.backend === tr));

  // Confidence
  const conf = s.conf ?? 0.45;
  if ($("confSlider")) $("confSlider").value = conf;
  if ($("confVal")) $("confVal").textContent = conf;

  // Bubble Mask Erosion
  const ero = s.bubble_mask_erosion ?? 5;
  if ($("erosionSlider")) $("erosionSlider").value = ero;
  if ($("erosionVal")) $("erosionVal").textContent = `${ero} px`;

  // Font Scale
  const fs = s.font_scale ?? 1.0;
  if ($("fontScaleSlider")) $("fontScaleSlider").value = fs;
  if ($("fontScaleVal")) $("fontScaleVal").textContent = `${Number(fs).toFixed(1)}x`;
}

document.querySelectorAll("#cfgOcrSeg button").forEach((b) => {
  b.onclick = async () => {
    const engine = b.dataset.engine;
    S.settings.ocr_engine = engine;
    syncSettingsUI();
    await api("/api/settings", { ocr_engine: engine });
    logLine(`settings: OCR engine set to ${engine}`);
  };
});

document.querySelectorAll("#cfgInpaintSeg button").forEach((b) => {
  b.onclick = async () => {
    const mode = b.dataset.inpaint;
    S.settings.inpaint_mode = mode;
    syncSettingsUI();
    await api("/api/settings", { inpaint_mode: mode });
    logLine(`settings: inpaint mode set to ${mode.toUpperCase()}`);
  };
});

document.querySelectorAll("#cfgTranslateSeg button").forEach((b) => {
  b.onclick = async () => {
    const backend = b.dataset.backend;
    S.settings.translate_backend = backend;
    syncSettingsUI();
    await api("/api/settings", { translate_backend: backend });
    logLine(`settings: translation backend set to ${backend}`);
  };
});

let erosionTimer = null;
if ($("erosionSlider")) {
  $("erosionSlider").oninput = (e) => {
    const val = parseInt(e.target.value, 10);
    if ($("erosionVal")) $("erosionVal").textContent = `${val} px`;
    clearTimeout(erosionTimer);
    erosionTimer = setTimeout(async () => {
      S.settings.bubble_mask_erosion = val;
      await api("/api/settings", { bubble_mask_erosion: val });
      logLine(`settings: bubble mask edge inset set to ${val}px`);
    }, 350);
  };
}

let fontScaleTimer = null;
if ($("fontScaleSlider")) {
  $("fontScaleSlider").oninput = (e) => {
    const val = parseFloat(e.target.value);
    if ($("fontScaleVal")) $("fontScaleVal").textContent = `${val.toFixed(1)}x`;
    clearTimeout(fontScaleTimer);
    fontScaleTimer = setTimeout(async () => {
      S.settings.font_scale = val;
      await api("/api/settings", { font_scale: val });
      logLine(`settings: font scale set to ${val.toFixed(1)}x`);
    }, 350);
  };
}

if ($("confSlider")) {
  $("confSlider").oninput = (e) => {
    if ($("confVal")) $("confVal").textContent = e.target.value;
    clearTimeout(S.confTimer);
    S.confTimer = setTimeout(async () => {
      const conf = parseFloat(e.target.value);
      S.settings.conf = conf;
      await api("/api/settings", { conf });
      S.pageData.clear();
      refreshOverview();
      rebuildVisible();
    }, 350);
  };
}

if ($("maskOpacitySlider")) {
  $("maskOpacitySlider").oninput = (e) => {
    const val = e.target.value;
    if ($("maskOpacityVal")) $("maskOpacityVal").textContent = val + "%";
    document.documentElement.style.setProperty("--mask-opacity", (val / 100).toString());
  };
}

document.querySelectorAll("#advViewsSeg button").forEach((b) => {
  b.onclick = () => {
    S.viz = b.dataset.viz;
    document.querySelectorAll("#advViewsSeg button").forEach((x) => x.classList.toggle("active", x === b));
    if (b.dataset.viz === "render") setModeQuiet("ours");
    else setModeQuiet(null);
    rebuildVisible();
  };
});

/* right sidebar (visualization + inspector tabs) */
function switchSidebarTab(tab) {
  const isViz = tab === "viz";
  $("tabViz")?.classList.toggle("active", isViz);
  $("tabInsp")?.classList.toggle("active", !isViz);
  $("paneViz")?.classList.toggle("hidden", !isViz);
  $("paneInsp")?.classList.toggle("hidden", isViz);
  toggleSidebar(true);
}

function toggleSidebar(force) {
  const sb = $("sidebar");
  if (!sb) return;
  const willCollapse = force !== undefined ? !force : !sb.classList.contains("collapsed");
  sb.classList.toggle("collapsed", willCollapse);
  $("btnSidebarToggle")?.classList.toggle("active", !willCollapse);
}

$("tabViz")?.addEventListener("click", () => switchSidebarTab("viz"));
$("tabInsp")?.addEventListener("click", () => switchSidebarTab("insp"));
$("sbClose")?.addEventListener("click", () => toggleSidebar(false));
$("btnSidebarToggle")?.addEventListener("click", () => toggleSidebar());

/* ===================================== Chapter Flyout & Switcher */
let currentBrowsePath = "";

async function toggleChapterFlyout(force) {
  const flyout = $("chapterFlyout");
  if (!flyout) return;
  const willShow = force !== undefined ? force : flyout.classList.contains("hidden");
  flyout.classList.toggle("hidden", !willShow);
  if (willShow) {
    if ($("flyoutChapterPath")) $("flyoutChapterPath").value = S.chapter || "";
    if ($("flyoutReferencePath")) $("flyoutReferencePath").value = S.reference || "";
    renderFlyoutRecents();
    await loadSiblingChapters();
  }
}

function closeFlyout() {
  $("chapterFlyout")?.classList.add("hidden");
  $("inlineBrowser")?.classList.add("hidden");
}

function renderFlyoutRecents() {
  const list = S.recent || [];
  const container = $("flyoutRecent");
  const wrapper = $("cfRecentSection");
  if (!container || !wrapper) return;
  if (!list.length) {
    wrapper.classList.add("hidden");
    return;
  }
  wrapper.classList.remove("hidden");
  container.innerHTML = "";
  list.forEach((item) => {
    const chPath = typeof item === "string" ? item : item.chapter;
    const refPath = typeof item === "object" ? (item.reference || "") : "";
    if (!chPath) return;
    const name = chPath.split(/[\\/]/).filter(Boolean).pop() || chPath;
    const chip = el("div", "recent-chip");
    const isCurrent = S.chapter && (S.chapter.replace(/\\/g, "/") === chPath.replace(/\\/g, "/"));
    if (isCurrent) chip.classList.add("active");
    chip.innerHTML = `<span class="rc-name">${esc(name)}</span><span class="rc-path">${esc(chPath)}</span>`;
    chip.onclick = () => openChapterPath(chPath, refPath);
    container.appendChild(chip);
  });
}

async function loadSiblingChapters() {
  const section = $("cfSiblingSection");
  const listEl = $("siblingChapters");
  if (!section || !listEl || !S.chapter) {
    section?.classList.add("hidden");
    return;
  }
  const norm = S.chapter.replace(/\\/g, "/");
  const parts = norm.split("/").filter(Boolean);
  if (parts.length < 2) { section.classList.add("hidden"); return; }
  const currentName = parts.pop();
  const parentPath = norm.substring(0, norm.lastIndexOf("/"));
  try {
    const d = await api(`/api/fs?path=${enc(parentPath)}`);
    const siblings = (d.entries || []).filter((e) => e.name);
    if (siblings.length < 2) {
      section.classList.add("hidden");
      return;
    }
    section.classList.remove("hidden");
    listEl.innerHTML = "";
    siblings.forEach((s) => {
      const btn = el("button", "sibling-btn" + (s.name === currentName ? " active" : ""));
      btn.textContent = s.name;
      btn.title = s.path;
      btn.onclick = () => openChapterPath(s.path, S.reference);
      listEl.appendChild(btn);
    });
  } catch {
    section.classList.add("hidden");
  }
}

async function openChapterPath(chapterPath, refPath) {
  if (!chapterPath) return;
  try {
    const st = await api("/api/open", {
      chapter: chapterPath,
      reference: refPath || null,
    });
    closeFlyout();
    applyState(st);
    await refreshOverview();
    $("viewer").scrollTop = 0;
    logLine(`studio: opened ${st.chapter} — ${st.pages.length} pages`);
  } catch (e) {
    logLine("!! open failed: " + e.message, "err");
  }
}

/* Inline directory browser */
async function browseInline(path) {
  try {
    const d = await api(`/api/fs?path=${enc(path || "")}`);
    currentBrowsePath = d.path || path || "";
    if ($("browserCurPath")) $("browserCurPath").textContent = d.path || "";
    if ($("flyoutChapterPath")) $("flyoutChapterPath").value = d.path || "";
    const list = $("browserList");
    if (list) {
      list.innerHTML = d.entries.length ? "" : '<li class="muted pad">(no subfolders)</li>';
      d.entries.forEach((e) => {
        const li = el("li", null, "📁 " + e.name);
        // stopPropagation: browseInline() rebuilds the list (removes this li),
        // so by the time the click bubbles to document, e.target is detached —
        // closest("#chapterPickerWrap") returns null and the flyout closes.
        li.addEventListener("click", (evt) => { evt.stopPropagation(); browseInline(e.path); });
        list.appendChild(li);
      });
    }
    const rootsEl = $("browserRoots");
    if (rootsEl) {
      rootsEl.innerHTML = "";
      const mkRoot = (name, p) => {
        const b = el("button", null, name);
        b.addEventListener("click", (evt) => { evt.stopPropagation(); browseInline(p); });
        rootsEl.appendChild(b);
      };
      if (d.home) mkRoot("Home", d.home);
      (d.roots || []).forEach((r) => mkRoot(r.name, r.path));
    }
    if ($("browserUp")) {
      if (d.parent) {
        $("browserUp").onclick = () => browseInline(d.parent);
        $("browserUp").disabled = false;
      } else {
        $("browserUp").disabled = true;
      }
    }
  } catch (e) {
    logLine("!! fs browse: " + e.message, "err");
  }
}


$("btnOpen")?.addEventListener("click", (e) => {
  e.stopPropagation();
  toggleChapterFlyout();
});
$("btnOpenEmpty")?.addEventListener("click", (e) => {
  e.stopPropagation();
  toggleChapterFlyout(true);
});
$("cfClose")?.addEventListener("click", () => closeFlyout());
$("btnFlyoutOpen")?.addEventListener("click", () => {
  const ch = $("flyoutChapterPath")?.value.trim();
  const ref = $("flyoutReferencePath")?.value.trim();
  openChapterPath(ch, ref);
});
$("flyoutChapterPath")?.addEventListener("keydown", (e) => {
  if (e.key === "Enter") {
    openChapterPath($("flyoutChapterPath")?.value.trim(), $("flyoutReferencePath")?.value.trim());
  }
});
$("btnFlyoutBrowse")?.addEventListener("click", () => {
  const b = $("inlineBrowser");
  if (!b) return;
  const willShow = b.classList.contains("hidden");
  b.classList.toggle("hidden", !willShow);
  if (willShow) {
    browseInline(currentBrowsePath || S.chapter || "");
  }
});
$("browserSelectThis")?.addEventListener("click", () => {
  if (currentBrowsePath) {
    openChapterPath(currentBrowsePath, $("flyoutReferencePath")?.value.trim());
  }
});

document.addEventListener("click", (e) => {
  if (!e.target.closest("#chapterPickerWrap")) {
    closeFlyout();
  }
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
    else if (stage === "detect") await api("/api/detect", { page, conf: S.settings.conf });
    else if (stage === "ocr") await api("/api/ocr", { page });
    else if (stage === "inpaint") await api("/api/inpaint", {
      page, mode: S.settings.inpaint_mode, force: true,
      engine: S.settings.inpaint_engine || "legacy",
      bubble_leg: S.settings.inpaint_bubble_leg || "android-fill",
      free_leg: S.settings.inpaint_free_leg || "opencv",
    });
    else if (stage === "translate") await api("/api/translate", { page });
    else if (stage === "render") await api("/api/render", { page });
    S.pageData.delete(page);
    delete S.pageError[page];
    await ensurePageData(page);
    refreshRenderedImages(page);
    const sec = secEl(page);
    if (sec) {
      sec._builtKey = null;
      ensureSection(page);
    }
    if (S.focus === page) buildFocus(page);
  } catch (e) {
    S.pageError[page] = e.message;
    logLine(`!! stage ${stage} ${page}: ${e.message}`, "err");
  } finally {
    delete P[page];
    await refreshOverview();
  }
}

document.querySelectorAll("button[data-stage]").forEach((b) => {
  b.onclick = () => {
    closeMenus();
    runStage(b.dataset.stage);
  };
});

$("btnStepperMenu")?.addEventListener("click", (e) => {
  e.stopPropagation();
  const menu = $("stepperDropdown");
  const willShow = menu?.classList.contains("hidden");
  closeMenus();
  if (willShow) menu?.classList.remove("hidden");
});

$("btnRerenderDropdown")?.addEventListener("click", () => {
  closeMenus();
  rerenderCurrentPage();
});

function initTranslateToggle() {
  const btn = $("btnToggleTranslate");
  const chk = $("chkTranslate");
  if (!btn || !chk) return;
  const saved = localStorage.getItem("ts_auto_translate");
  const active = saved !== null ? saved === "true" : true;
  chk.checked = active;
  btn.classList.toggle("active", active);
  btn.title = `Auto-translate is ${active ? "ON" : "OFF"} during pipeline runs (click to toggle)`;

  btn.onclick = () => {
    chk.checked = !chk.checked;
    btn.classList.toggle("active", chk.checked);
    btn.title = `Auto-translate is ${chk.checked ? "ON" : "OFF"} during pipeline runs (click to toggle)`;
    localStorage.setItem("ts_auto_translate", chk.checked);
    logLine(`pipeline: auto-translate ${chk.checked ? "enabled" : "disabled"}`);
  };
}

let stagePoll = null;
function startStagePolling() {
  stopStagePolling();
  stagePoll = setInterval(async () => {
    tailServerLogs();
    try {
      const st = await api("/api/state");
      const active = st.active_task || null;
      if (active) {
        $("progressChip")?.classList.remove("hidden");
        if ($("pcText")) $("pcText").textContent = active.stage || "Working…";
        if ($("pcStage")) $("pcStage").textContent = active.page ? `${active.page} (${active.i + 1}/${active.n})` : "";
        const pct = active.n ? ((active.i / active.n) * 100).toFixed(0) : "0";
        if ($("pcFill")) $("pcFill").style.width = pct + "%";
      }
    } catch {}
  }, 450);
}
function stopStagePolling() {
  if (stagePoll) { clearInterval(stagePoll); stagePoll = null; }
}

async function processChapter() {
  if (S.busy || !S.pages.length) return;
  S.busy = true;
  $("btnProcessChapter").disabled = true;
  $("progressChip")?.classList.remove("hidden");
  startStagePolling();
  const total = S.pages.length;
  let failed = 0;
  for (let i = 0; i < total; i++) {
    const p = S.pages[i];
    S.processing[p] = true;
    refreshSectionStatus(p);
    refreshRailDots();
    try {
      await api("/api/process", { page: p, conf: S.settings.conf, translate: $("chkTranslate").checked });
      S.pageData.delete(p);
      delete S.pageError[p];
      refreshRenderedImages(p);
      const sec = secEl(p);
      if (sec) { sec._builtKey = null; ensureSection(p); }
    } catch (e) {
      failed++;
      S.pageError[p] = e.message.slice(0, 80);
      logLine(`!! process ${p}: ${e.message}`, "err");
    }
    delete S.processing[p];
    await refreshOverview();
  }
  if ($("pcText")) $("pcText").textContent = failed ? `Done — ${failed} failed` : "Done";
  setTimeout(() => $("progressChip")?.classList.add("hidden"), 2200);
  stopStagePolling();
  S.busy = false;
  $("btnProcessChapter").disabled = false;
  logLine(`studio: chapter processed (${total} pages, ${failed} failed)`);
}
$("btnProcessChapter")?.addEventListener("click", processChapter);

/* ========================================================== focus mode */
function enterFocus(page) {
  S.focus = page;
  $("focusTitle").textContent =
    `PAGE ${String(S.pages.indexOf(page) + 1).padStart(2, "0")} — ${page}`;
  $("focusView")?.classList.remove("hidden");
  buildFocus(page);
  fitFocus();
}

function buildFocus(page) {
  const inner = $("focusInner");
  if (!inner) return;
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
  const conf = S.settings?.conf ?? 0.45;

  // 1. Parent Speech Bubbles
  (d.raw_boxes || [])
    .filter((b) => b.label === 0 && (b.score === undefined || b.score >= conf))
    .forEach((b, idx) => {
      const [x1, y1, x2, y2] = b.box;
      const box = el("div", "rbox rbox-bubble");
      box.style.left = (x1 / w) * 100 + "%";
      box.style.top = (y1 / h) * 100 + "%";
      box.style.width = ((x2 - x1) / w) * 100 + "%";
      box.style.height = ((y2 - y1) / h) * 100 + "%";
      box.title = `Speech Bubble ${idx + 1} · score ${b.score}`;
      box.innerHTML = `<span class="rbox-tag">[BUBBLE #${idx + 1}] · ${esc(b.score)}</span>`;
      media._overlays.appendChild(box);
    });

  // 2. Text Regions
  (d.regions || [])
    .filter((r) => r.score === undefined || r.score >= conf)
    .forEach((r) => {
      const [x1, y1, x2, y2] = r.box;
      const isFree = r.label === 2;
      const kindCls = isFree ? "rbox-freetext" : "rbox-text";
      const typeTag = isFree ? `[FREE_TEXT ${r.id}]` : `[BUBBLE_TEXT ${r.id}]`;
      const box = el("div", "rbox " + kindCls);
      box.dataset.id = r.id;
      box.style.left = (x1 / w) * 100 + "%";
      box.style.top = (y1 / h) * 100 + "%";
      box.style.width = ((x2 - x1) / w) * 100 + "%";
      box.style.height = ((y2 - y1) / h) * 100 + "%";
      const tr = (d.translations[r.id] || "").trim();
      box.innerHTML =
        `<span class="rbox-tag">${typeTag} · ${esc(r.score)}</span>` +
        `<span class="rbox-text"><b>${esc(r.text || "")}</b>${tr ? ` <i>${esc(tr)}</i>` : ""}</span>`;
      box.onclick = (e) => { e.stopPropagation(); selectRegion(page, r.id); };
      media._overlays.appendChild(box);
    });

  media._hasOverlays = true;
  updateLayerClasses(media);
  markSelection();
}

function exitFocus() {
  S.focus = null;
  $("focusView")?.classList.add("hidden");
  queueVirtualize();
}
$("focusBack")?.addEventListener("click", exitFocus);

function applyFocusTransform() {
  const fi = $("focusInner");
  if (fi) fi.style.transform = `translate(${S.fz.tx}px, ${S.fz.ty}px) scale(${S.fz.z})`;
}
function fitFocus() {
  const stage = $("focusStage");
  const inner = $("focusInner");
  if (!stage || !inner || !S.focus) return;
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

function wheelZoom(e) {
  if (!e.ctrlKey && !e.metaKey) return;
  e.preventDefault();
  // Buttery-smooth exponential damping curve:
  // Dampen wheel steps so looking for fine visual details is completely stable
  const delta = Math.min(Math.max(e.deltaY, -100), 100);
  const factor = Math.exp(-delta * 0.0018);
  if (S.focus) {
    const stage = $("focusStage").getBoundingClientRect();
    const mx = e.clientX - stage.left, my = e.clientY - stage.top;
    const nz = Math.min(8, Math.max(0.15, S.fz.z * factor));
    S.fz.tx = mx - ((mx - S.fz.tx) * nz) / S.fz.z;
    S.fz.ty = my - ((my - S.fz.ty) * nz) / S.fz.z;
    S.fz.z = nz;
    applyFocusTransform();
  } else {
    S.zoom = Math.min(3.5, Math.max(0.25, S.zoom * factor));
    $("strip")?.style.setProperty("--zoom", S.zoom);
    if ($("stripZoomVal")) $("stripZoomVal").textContent = Math.round(S.zoom * 100) + "%";
    queueVirtualize();
  }
}
window.addEventListener("wheel", wheelZoom, { passive: false });

function initZoomControls() {
  $("btnStripZoomOut")?.addEventListener("click", () => {
    S.zoom = Math.max(0.25, S.zoom * 0.85);
    $("strip")?.style.setProperty("--zoom", S.zoom);
    if ($("stripZoomVal")) $("stripZoomVal").textContent = Math.round(S.zoom * 100) + "%";
    queueVirtualize();
  });
  $("btnStripZoomIn")?.addEventListener("click", () => {
    S.zoom = Math.min(3.5, S.zoom * 1.15);
    $("strip")?.style.setProperty("--zoom", S.zoom);
    if ($("stripZoomVal")) $("stripZoomVal").textContent = Math.round(S.zoom * 100) + "%";
    queueVirtualize();
  });
  $("btnStripZoomFit")?.addEventListener("click", () => {
    S.zoom = 1.0;
    $("strip")?.style.setProperty("--zoom", S.zoom);
    if ($("stripZoomVal")) $("stripZoomVal").textContent = "100%";
    queueVirtualize();
  });

  $("btnZoomOut")?.addEventListener("click", () => {
    if (!S.focus) return;
    S.fz.z = Math.max(0.15, S.fz.z * 0.85);
    applyFocusTransform();
  });
  $("btnZoomIn")?.addEventListener("click", () => {
    if (!S.focus) return;
    S.fz.z = Math.min(8, S.fz.z * 1.15);
    applyFocusTransform();
  });
  $("btnZoomReset")?.addEventListener("click", () => {
    if (!S.focus) return;
    S.fz.z = 1.0;
    applyFocusTransform();
  });
  $("btnZoomFit")?.addEventListener("click", () => {
    if (!S.focus) return;
    fitFocus();
  });
}

async function rerenderCurrentPage() {
  const page = S.current || (S.pages.length > 0 ? S.pages[0] : null);
  if (!page) {
    alert("No page selected or open.");
    return;
  }

  // Pre-check: Ensure OCR data is present before attempting to re-render
  let d = S.pageData.get(page);
  if (!d) {
    d = await ensurePageData(page);
  }
  if (!d || !d.regions || d.regions.length === 0) {
    alert(`Page ${page} has no OCR text data to render.\n\nPlease run "⚡ Page" (or Detect & OCR) first before re-rendering.`);
    return;
  }

  const btn = $("btnRerender");
  if (btn) {
    btn.disabled = true;
    btn.textContent = "Rendering…";
  }
  try {
    const res = await api("/api/render", { page, force: true });
    // Invalidate rendered images on DOM
    S.cacheBust = Date.now();
    const bust = "?t=" + S.cacheBust;
    document.querySelectorAll(`.pg[data-page="${page}"] img.ph, #focusInner img.ph`).forEach((img) => {
      if (img.src && img.src.includes("/img/render")) {
        img.src = img.src.split("?")[0] + "?p=" + enc(page) + bust;
      }
    });
    // Update speed metrics with fresh render timing
    if (res.render_ms !== undefined) {
      const curData = S.pageData.get(page);
      if (curData && curData.metrics) {
        curData.metrics.render = { render_ms: res.render_ms };
        curData.metrics.total_infer_ms = round1((curData.metrics.detector?.infer_ms || 0) +
                                         (curData.metrics.ocr?.infer_ms || 0) +
                                         (curData.metrics.segmenter?.infer_ms || 0) +
                                         (curData.metrics.inpaint?.infer_ms || 0) +
                                         res.render_ms);
      }
      updateSpeedMetrics(page);
    }
    logLine(`re-rendered ${page} in ${res.render_ms || 0}ms (cached translation typography updated)`);
  } catch (e) {
    alert("Re-render failed: " + e.message);
  } finally {
    if (btn) {
      btn.disabled = false;
      btn.textContent = "↻ Re-render";
    }
  }
}
$("btnRerender")?.addEventListener("click", rerenderCurrentPage);

/* ── Reset artifact handlers ─────────────────────────────────────────────── */
async function resetArtifacts(page, keepTranslations) {
  const scope = page ? `page ${page}` : "entire chapter";
  const xlNote = keepTranslations ? " (translations preserved)" : " (translations also cleared)";
  const msg = keepTranslations
    ? `Reset ${scope} — keep translations?\n\nThis will delete detect/OCR/render/inpaint caches.\nYour translations are preserved.`
    : `Reset ${scope}?\n\nThis will delete ALL artifacts including translations.\nHold Shift while clicking to keep translations.`;
  if (!confirm(msg)) return;

  const body = page ? { page, keep_translations: keepTranslations }
                    : { keep_translations: keepTranslations };
  try {
    const r = await fetch("/api/reset", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const data = await r.json();
    if (!r.ok) { alert("Reset failed: " + (data.error || r.status)); return; }
    logLine(`✔ Reset${xlNote}: ${data.count} items removed`);

    // 1. Clear memory caches & deselect any active region inspector
    if (!page) {
      S.pageData.clear();
      S.pageError = {};
      deselectRegion();
    } else {
      S.pageData.delete(page);
      delete S.pageError?.[page];
      if (S.selection?.page === page) {
        deselectRegion();
      }
    }

    // 2. Clear all DOM overlays, bounding boxes, and mask image layers
    S.cacheBust = Date.now();
    const sections = page
      ? [secEl(page)].filter(Boolean)
      : Array.from(document.querySelectorAll(".pg"));

    sections.forEach((sec) => {
      sec._builtKey = null;
      const media = sec.querySelector(".pg-media");
      if (media) {
        if (media._overlays) {
          media._overlays.innerHTML = "";
        }
        media._hasOverlays = false;

        const maskImg = media.querySelector(".pg-mask-layer");
        if (maskImg) {
          maskImg.src = "";
          maskImg.removeAttribute("src");
        }
        const inpaintMaskImg = media.querySelector(".pg-inpaint-mask-layer");
        if (inpaintMaskImg) {
          inpaintMaskImg.src = "";
          inpaintMaskImg.removeAttribute("src");
        }
      }
    });

    // 3. Rebuild visible page sections with clean original backgrounds
    if (!page) {
      rebuildVisible();
      document.querySelectorAll(".pg").forEach((sec) => refreshSectionStatus(sec.dataset.page));
    } else {
      ensureSection(page);
      if (S.focus === page) buildFocus(page);
      refreshSectionStatus(page);
    }

    // 4. Update sidebar metrics and navigation dots
    refreshRailDots();
    await refreshOverview();
    if (S.current) {
      await ensurePageData(S.current);
      updateSpeedMetrics(S.current);
    }

    // 5. Explicit user confirmation alert
    alert(`✔ Reset ${scope} succeeded!\n\n${data.count} cached artifacts removed.\nReady to run Detect / OCR from scratch.`);
  } catch (e) {
    alert("Reset error: " + e.message);
  }
}

$("btnResetMenu")?.addEventListener("click", (e) => {
  e.stopPropagation();
  const menu = $("resetDropdown");
  const willShow = menu?.classList.contains("hidden");
  closeMenus();
  if (willShow) menu?.classList.remove("hidden");
});

$("btnResetPage")?.addEventListener("click", (e) => {
  closeMenus();
  if (!S.chapter) { alert("Open a chapter first."); return; }
  if (!S.current) { alert("Select a page first."); return; }
  const clearTr = $("chkResetIncludeTr")?.checked || e.shiftKey;
  resetArtifacts(S.current, clearTr);
});

$("btnResetChapter")?.addEventListener("click", (e) => {
  closeMenus();
  if (!S.chapter) { alert("Open a chapter first."); return; }
  const clearTr = $("chkResetIncludeTr")?.checked || e.shiftKey;
  resetArtifacts(null, clearTr);
});

function round1(v) { return Math.round(v * 10) / 10; }

/* Space + drag panning in focus mode */
let spaceHeld = false;
let pan = null;
$("focusStage")?.addEventListener("pointerdown", (e) => {
  if (!spaceHeld) return;
  pan = { x: e.clientX, y: e.clientY, tx: S.fz.tx, ty: S.fz.ty };
  $("focusStage")?.classList.add("panning");
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
function updateSpeedMetrics(page) {
  const container = $("sbSpeedMetrics");
  if (!container) return;
  if (!page) {
    container.innerHTML = "";
    return;
  }
  const d = S.pageData.get(page);
  const m = d?.metrics;
  if (!m) {
    container.innerHTML = "";
    return;
  }
  const det = m.detector || {};
  const ocr = m.ocr || {};
  const seg = m.segmenter || {};
  const inp = m.inpaint || {};
  const ren = m.render || {};

  let html = "";
  if (det.infer_ms) {
    html += `<span class="sb-speed-chip" title="Detector inference: ${det.infer_ms}ms (load: ${det.load_ms || 0}ms)"><span class="lbl">Det</span><span class="val">${det.infer_ms}ms</span></span>`;
  }
  if (ocr.infer_ms) {
    const rCount = ocr.regions_count !== undefined ? ` (${ocr.regions_count}r)` : "";
    html += `<span class="sb-speed-chip" title="OCR inference: ${ocr.infer_ms}ms for ${ocr.regions_count || 0} regions (load: ${ocr.load_ms || 0}ms)"><span class="lbl">OCR${rCount}</span><span class="val">${ocr.infer_ms}ms</span></span>`;
  }
  if (seg.infer_ms) {
    html += `<span class="sb-speed-chip" title="Bubble segmentation inference: ${seg.infer_ms}ms (load: ${seg.load_ms || 0}ms)"><span class="lbl">Seg</span><span class="val">${seg.infer_ms}ms</span></span>`;
  }
  if (inp.infer_ms) {
    html += `<span class="sb-speed-chip" title="Clean background: ${inp.infer_ms}ms"><span class="lbl">Clean</span><span class="val">${inp.infer_ms}ms</span></span>`;
  }
  if (ren.render_ms) {
    html += `<span class="sb-speed-chip" title="Typography layout & render: ${ren.render_ms}ms"><span class="lbl">Render</span><span class="val">${ren.render_ms}ms</span></span>`;
  }
  if (m.total_infer_ms) {
    html += `<span class="sb-speed-chip tot-chip" title="Total inference time for this page"><span class="lbl">Page Total</span><span class="val">${m.total_infer_ms}ms</span></span>`;
  }
  const loads = m.load_times || {};
  const totalLoad = Object.values(loads).reduce((a, b) => a + (b || 0), 0);
  if (totalLoad > 0) {
    const tip = Object.entries(loads).map(([k, v]) => `${k}: ${v}ms`).join(", ");
    html += `<span class="sb-speed-chip load-chip" title="Models initialized in memory: ${tip}"><span class="lbl">Init</span><span class="val">${Math.round(totalLoad)}ms</span></span>`;
  }

  container.innerHTML = html;
}

function updateStatusBar() {
  const i = S.pages.indexOf(S.current);
  const d = i >= 0 ? S.pageData.get(S.current) : null;
  const row = S.overview?.pages[i];
  const bits = [];
  if (i >= 0) bits.push(`Page ${i + 1} / ${S.pages.length}`);
  if (d) {
    bits.push(VIZ[S.viz]?.label || "Render");
    bits.push(`${d.regions.length} regions`);
  } else if (row) {
    if (row.regions) bits.push(`${row.regions} regions`);
  }
  bits.push(`Batch ${S.settings.max_batch ?? 8}`);
  if ($("sbLeft")) $("sbLeft").textContent = bits.join("   ·   ");
  if ($("sbRight")) $("sbRight").textContent = S.chapter ? S.chapter.split(/[\\/]/).filter(Boolean).pop() : "";

  updateSpeedMetrics(S.current);
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

/* ========================================================= overview dlg */
$("btnOverview")?.addEventListener("click", async () => {
  const ov = await api("/api/overview");
  S.overview = ov;
  refreshAllStatus();
  const sum = ov.summary || {};
  const rows = ov.pages || [];
  const n = rows.length;

  const totalInferSec = ((sum.total_chapter_infer_ms || 0) / 1000).toFixed(2);
  const avgPageSec = ((sum.avg_page_infer_ms || 0) / 1000).toFixed(2);
  const avgRegs = sum.avg_regions_per_page || 0;
  const totalRegs = sum.total_regions || 0;
  const totalLoadSec = ((sum.total_model_load_ms || 0) / 1000).toFixed(2);

  // Model loading table
  const loads = sum.model_load_times || {};
  let modelRows = "";
  for (const [modelName, loadMs] of Object.entries(loads)) {
    modelRows += `<tr><td>${esc(modelName)}</td><td style="color:#fbbf24">${loadMs} ms</td><td>${(loadMs / 1000).toFixed(2)}s</td></tr>`;
  }
  if (!modelRows) {
    modelRows = `<tr><td colspan="3" class="muted">No cold models initialized in this session</td></tr>`;
  }

  const slow = ov.slowest && ov.slowest.ocr_ms
    ? `<button class="ov-link" data-go="${esc(ov.slowest.page)}">` +
      `<span class="badge slow">SLOW</span> Page ${S.pages.indexOf(ov.slowest.page) + 1} — ` +
      `OCR ${(ov.slowest.ocr_ms / 1000).toFixed(2)}s</button>`
    : '<div class="muted" style="padding:4px">no timings yet</div>';
  const errs = ov.errors && ov.errors.length
    ? ov.errors.map((e) =>
        `<button class="ov-link" data-go="${esc(e.page)}">` +
        `<span class="badge">ERR</span> Page ${S.pages.indexOf(e.page) + 1} — ${esc(e.reason)}</button>`).join("")
    : '<div class="muted" style="padding:4px">none</div>';

  $("ovBody").innerHTML = `
    <!-- Top Summary Grid -->
    <div class="ov-stats-grid">
      <div class="ov-stat-box">
        <div class="ov-stat-title">Total Chapter Time</div>
        <div class="ov-stat-val">${totalInferSec}s</div>
        <div class="ov-stat-sub">pure inference</div>
      </div>
      <div class="ov-stat-box">
        <div class="ov-stat-title">Avg / Page</div>
        <div class="ov-stat-val">${avgPageSec}s</div>
        <div class="ov-stat-sub">${sum.pages_with_data || 0} active pages</div>
      </div>
      <div class="ov-stat-box">
        <div class="ov-stat-title">Total Text Regions</div>
        <div class="ov-stat-val">${totalRegs}</div>
        <div class="ov-stat-sub">${avgRegs} avg / page</div>
      </div>
      <div class="ov-stat-box">
        <div class="ov-stat-title">Cold Model Init</div>
        <div class="ov-stat-val" style="color:#fbbf24">${totalLoadSec}s</div>
        <div class="ov-stat-sub">1st run memory load</div>
      </div>
    </div>

    <!-- Models Load vs Inference Breakdown -->
    <div class="sb-label" style="margin-top:12px">Model Loading (Cold Initialization)</div>
    <table class="ov-perf-table">
      <thead>
        <tr><th>Engine / Model</th><th>Cold Load (ms)</th><th>Cold Load (sec)</th></tr>
      </thead>
      <tbody>
        ${modelRows}
      </tbody>
    </table>

    <div class="ov-sub">Slowest page</div>${slow}
    <div class="ov-sub">Errors</div>${errs}
  `;

  $("ovBody").querySelectorAll("[data-go]").forEach((b) => {
    b.onclick = () => { $("overviewDlg").close(); scrollToPage(b.dataset.go); };
  });
  $("overviewDlg").showModal();
});
$("ovClose")?.addEventListener("click", () => $("overviewDlg").close());

/* ================================================== applyState */
function applyState(st) {
  S.chapter = st.chapter;
  S.reference = st.reference;
  S.pages = st.pages || [];
  S.dims = st.dims || {};
  S.settings = st.settings || {};
  if (st.recent) S.recent = st.recent;
  S.pageData.clear();
  S.overview = null;
  S.current = null;
  S.selection = null;

  syncSettingsUI();
  const name = S.chapter ? S.chapter.split(/[\\/]/).filter(Boolean).pop() : null;
  $("chapterInfo").innerHTML = name
    ? `<b>${esc(name)}</b> · ${S.pages.length} pages` +
      (S.reference ? ` · goal: ${esc(S.reference.split(/[\\/]/).filter(Boolean).pop())}` : "")
    : "Open Chapter…";

  if (S.chapter) {
    localStorage.setItem("studio.chapter", S.chapter);
    localStorage.setItem("studio.reference", S.reference || "");
  }
  buildRail();
  buildStrip();
  updateStatusBar();
  syncInspectorBtn();
}

/* ================================================== settings dialog */
$("btnSettings")?.addEventListener("click", async () => {
  const s = (await api("/api/state")).settings;
  $("setOcrEngine").value = s.ocr_engine || "mangaocr";
  $("setInpaintMode").value = s.inpaint_mode || "quality";
  $("setInpaintEngine").value = s.inpaint_engine || "legacy";
  $("setInpaintBubbleLeg").value = s.inpaint_bubble_leg || "android-fill";
  $("setInpaintFreeLeg").value = s.inpaint_free_leg || "opencv";
  $("setTranslateBackend").value = s.translate_backend || "google";
  $("setConf").value = s.conf;
  $("setMaxBatch").value = s.max_batch;
  $("setErase").value = s.erase;
  $("setFont").value = s.font_path || "";
  $("setFontScale").value = s.font_scale;
  $("setBubbleErosion").value = s.bubble_mask_erosion ?? 5;
  $("setEndpoint").value = s.endpoint;
  $("setModel").value = s.model;
  $("setLang").value = s.target_lang;
  $("settingsDlg").showModal();
});
$("inpaintPresetButtons")?.addEventListener("click", (event) => {
  const preset = event.target.closest("[data-inpaint-preset]")?.dataset.inpaintPreset;
  if (!preset) return;
  $("setInpaintEngine").value = "android";
  if (preset === "android-fast") {
    $("setInpaintBubbleLeg").value = "android-fill";
    $("setInpaintFreeLeg").value = "opencv";
  } else if (preset === "android-quality") {
    $("setInpaintBubbleLeg").value = "android-fill";
    $("setInpaintFreeLeg").value = "aot";
  }
});
$("setClose")?.addEventListener("click", () => $("settingsDlg").close());
$("setSave")?.addEventListener("click", async () => {
  const s = await api("/api/settings", {
    ocr_engine: $("setOcrEngine").value,
    inpaint_mode: $("setInpaintMode").value,
    inpaint_engine: $("setInpaintEngine").value,
    inpaint_bubble_leg: $("setInpaintBubbleLeg").value,
    inpaint_free_leg: $("setInpaintFreeLeg").value,
    translate_backend: $("setTranslateBackend").value,
    conf: parseFloat($("setConf").value),
    max_batch: parseInt($("setMaxBatch").value),
    erase: $("setErase").value,
    font_path: $("setFont").value.trim(),
    font_scale: parseFloat($("setFontScale").value) || 1.0,
    bubble_mask_erosion: parseInt($("setBubbleErosion").value) ?? 5,
    endpoint: $("setEndpoint").value.trim(),
    model: $("setModel").value.trim(),
    target_lang: $("setLang").value.trim(),
  });
  S.settings = s.settings;
  syncSettingsUI();
  S.pageData.clear();
  rebuildVisible();
  $("settingsDlg").close();
  logLine("studio: settings saved");
});

/* goto page */
document.addEventListener("keydown", (e) => {
  if (e.key === "g" && e.ctrlKey) {
    e.preventDefault();
    $("gotoInput").value = String(S.pages.indexOf(S.current) + 1 || 1);
    $("gotoDlg").showModal();
    $("gotoInput").select();
  }
});
$("gotoGo")?.addEventListener("click", doGoto);
$("gotoInput")?.addEventListener("keydown", (e) => { if (e.key === "Enter") doGoto(); });
function doGoto() {
  const n = parseInt($("gotoInput").value);
  if (n >= 1 && n <= S.pages.length) scrollToPage(S.pages[n - 1]);
  $("gotoDlg").close();
}

/* ======================================================= keyboard maps */
window.addEventListener("keydown", (e) => {
  const typing = e.target.closest?.("input, textarea, select");
  const dialogOpen = document.querySelector("dialog[open]");

  if ((e.ctrlKey || e.metaKey) && (e.key === "o" || e.key === "O")) {
    e.preventDefault();
    toggleChapterFlyout();
    return;
  }

  if (e.code === "Space" && S.focus && !typing) {
    spaceHeld = true;
    $("focusStage")?.classList.add("panning");
  }
  if (typing || dialogOpen) return;
  if (e.ctrlKey || e.metaKey || e.altKey) return;

  switch (e.key) {
    case "1": setMode("original"); break;
    case "2": setMode("inpainted"); break;
    case "3": setMode("ours"); break;
    case "4": setMode("goal"); break;
    case "5":
      S.viz = "inpaint_mask";
      setModeQuiet(null);
      syncOverlayButtons();
      rebuildVisible();
      break;
    case "b": case "B":
      {
        const anyOn = S.tree.boxBubble || S.tree.boxBubbleText || S.tree.boxFreeText;
        S.tree.boxBubble = !anyOn;
        S.tree.boxBubbleText = !anyOn;
        S.tree.boxFreeText = !anyOn;
        syncVizTreeCheckboxes();
        applyLayerFlags();
      }
      break;
    case "t": case "T":
      S.tree.textBadges = !S.tree.textBadges;
      syncVizTreeCheckboxes();
      applyLayerFlags();
      break;
    case "m": case "M":
      S.tree.maskSeg = !S.tree.maskSeg;
      syncVizTreeCheckboxes();
      applyLayerFlags();
      break;
    case "r": case "R":
      rerenderCurrentPage();
      break;
    case "c": case "C": setMode("compare"); break;
    case "i": case "I": switchSidebarTab("insp"); break;
    case "v": case "V": toggleSidebar(); break;
    case "f": case "F":
      if (S.focus) fitFocus();
      else { S.zoom = 1; $("strip")?.style.setProperty("--zoom", 1); if ($("stripZoomVal")) $("stripZoomVal").textContent = "100%"; queueVirtualize(); }
      break;
    case "+": case "=":
      if (S.focus) { S.fz.z = Math.min(8, S.fz.z * 1.2); applyFocusTransform(); }
      else { S.zoom = Math.min(3.5, S.zoom * 1.2); $("strip")?.style.setProperty("--zoom", S.zoom); if ($("stripZoomVal")) $("stripZoomVal").textContent = Math.round(S.zoom * 100) + "%"; queueVirtualize(); }
      break;
    case "-":
      if (S.focus) { S.fz.z = Math.max(0.15, S.fz.z / 1.2); applyFocusTransform(); }
      else { S.zoom = Math.max(0.25, S.zoom / 1.2); $("strip")?.style.setProperty("--zoom", S.zoom); if ($("stripZoomVal")) $("stripZoomVal").textContent = Math.round(S.zoom * 100) + "%"; queueVirtualize(); }
      break;
    case "Escape":
      if (!$("chapterFlyout")?.classList.contains("hidden")) closeFlyout();
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
  initVizTree();
  initZoomControls();
  syncVizTreeCheckboxes();
  initTranslateToggle();
  try {
    const st = await api("/api/state");
    if (st.recent) S.recent = st.recent;
    if (st.chapter) {
      applyState(st);
      await refreshOverview();
    } else {
      // Auto-restore: server was restarted — silently reopen the last chapter
      // from the recent list so the user doesn't have to re-select.
      const last = (st.recent || [])[0];
      if (last && last.chapter) {
        try {
          const restored = await api("/api/open", {
            chapter: last.chapter,
            reference: last.reference || null,
          });
          applyState(restored);
          await refreshOverview();
          logLine(`studio: auto-restored ${last.chapter}`);
        } catch {
          $("emptyState")?.classList.remove("hidden");
        }
      } else {
        $("emptyState")?.classList.remove("hidden");
      }
    }
  } catch (e) {
    logLine("!! boot failed: " + e.message, "err");
    $("emptyState")?.classList.remove("hidden");
  }
  try {
    const l = await api("/api/log");
    l.lines.forEach((x) => logLine(x));
  } catch {}
})();
