/* Load-order module: viewer.js. Shared classic-script scope, no build step. */
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
    const cols = S.compare === "orig-inpainted" ? [["original", "Original"], ["inpainted", "Inpainted"]]
               : S.compare === "all" ? [["original", "Original"], ["goal", "Goal"], ["ours", "Ours"]]
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
      if (kind === "inpainted" && !S.artifactData.get(page)?.assets?.inpaint_image) {
        notice = "inpainted cache not present; showing original page";
      }
      cell.appendChild(el("div", "pg-cell-tag", tag));
      media.appendChild(cell);
      if (kind === "ours" || (S.compare === "orig-inpainted" && kind === "inpainted")) hit = cell;
    }
  } else {
    const v = VIZ[S.viz] || VIZ.render;
    let src = S.viz === "inpainted" ? imgSrcFor("inpainted", page) : v.src(page);
    if (S.viz === "inpainted" && !S.artifactData.get(page)?.assets?.inpaint_image) {
      notice = "inpainted cache not present; showing original page";
    }
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
  if (notice) showNotice(media, notice);
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

  // Legacy classes for older overlays still present in persisted sessions.
  media.classList.toggle("show-regions", !!(t.boxBubble || t.boxBubbleText || t.boxFreeText));
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
  const sec = secEl(p);
  const media = sec?.querySelector(".pg-media");
  if (!media || !media._overlays || media._hasOverlays) return;
  Promise.all([ensurePageData(p), ensureArtifactData(p)]).then(([d]) => {
    if (!d || !media.isConnected) return;
    renderArtifactOverlays(p);
  });
}

/* media click → region hit-test */
$("strip")?.addEventListener("click", (e) => {
  const media = e.target.closest(".pg-media");
  if (!media || e.target.closest(".artifact-svg")) return;
  deselectRegion();
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
  renderFilterControls(p);
  if (p) ensureArtifactData(p);
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
  ensureArtifactData(page).then(() => {
    if (S.focus === page) renderArtifactOverlays(page);
  });
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
    recordStageDispatch(page, "render");
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
    refreshStagePipeline(page);
    logLine(`re-rendered ${page} in ${res.render_ms || 0}ms (cached translation typography updated)`);
  } catch (e) {
    alert("Re-render failed: " + e.message);
  } finally {
    if (btn) {
      btn.disabled = false;
      btn.textContent = "↻";
    }
  }
}
$("btnRerender")?.addEventListener("click", rerenderCurrentPage);


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
