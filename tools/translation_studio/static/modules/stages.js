/* Load-order module: stages.js. Shared classic-script scope, no build step. */
const STUDIO_STAGES = ["detect", "ocr", "translate", "inpaint", "render"];

function ensureStageDispatchStore() {
  if (!S.chapter || S.stageDispatches?.chapter === S.chapter) return;
  try {
    const saved = JSON.parse(sessionStorage.getItem("studio.stage-dispatches") || "null");
    if (saved?.chapter === S.chapter && saved.pages && typeof saved.pages === "object") {
      S.stageDispatches = saved;
      return;
    }
  } catch {}
  S.stageDispatches = { chapter: S.chapter, pages: {} };
}

function ensureStageTimeStore() {
  if (!S.chapter || S.stageTimes?.chapter === S.chapter) return;
  try {
    const saved = JSON.parse(sessionStorage.getItem("studio.stage-times") || "null");
    if (saved?.chapter === S.chapter && saved.pages && typeof saved.pages === "object") {
      S.stageTimes = saved;
      return;
    }
  } catch {}
  S.stageTimes = { chapter: S.chapter, pages: {} };
}

function recordStageElapsed(page, stage, elapsedMs) {
  const duration = Number(elapsedMs);
  if (!page || !STUDIO_STAGES.includes(stage) || !Number.isFinite(duration) || duration < 0) return;
  ensureStageTimeStore();
  if (!S.stageTimes.pages[page]) S.stageTimes.pages[page] = {};
  S.stageTimes.pages[page][stage] = Number(S.stageTimes.pages[page][stage] || 0) + duration;
  try { sessionStorage.setItem("studio.stage-times", JSON.stringify(S.stageTimes)); } catch {}
  refreshStagePipeline(page);
}

function stageElapsedMs(page, stage) {
  ensureStageTimeStore();
  return Number(S.stageTimes?.pages?.[page]?.[stage] || 0);
}

function recordStageDispatch(page, stage) {
  if (!page || !STUDIO_STAGES.includes(stage)) return;
  ensureStageDispatchStore();
  if (!S.stageDispatches.pages[page]) S.stageDispatches.pages[page] = {};
  S.stageDispatches.pages[page][stage] = (S.stageDispatches.pages[page][stage] || 0) + 1;
  try { sessionStorage.setItem("studio.stage-dispatches", JSON.stringify(S.stageDispatches)); } catch {}
}

function recordPageProcessDispatches(page, translate) {
  ["detect", "ocr", ...(translate ? ["translate"] : []), "inpaint", "render"]
    .forEach((stage) => recordStageDispatch(page, stage));
}

function stageDispatchCount(page, stage) {
  return Number(S.stageDispatches?.pages?.[page]?.[stage] || 0);
}

function chapterStageDispatchCount(stage) {
  return Object.values(S.stageDispatches?.pages || {})
    .reduce((total, counts) => total + Number(counts?.[stage] || 0), 0);
}

function stageTimeMs(stage, row, page) {
  if (!row) return null;
  if (stage === "translate") {
    const elapsed = stageElapsedMs(page, stage);
    return elapsed > 0 ? elapsed : null;
  }
  const values = stage === "detect" ? [row.det_ms, row.seg_ms]
    : stage === "ocr" ? [row.ocr_ms]
      : stage === "inpaint" ? [row.inp_ms]
        : stage === "render" ? [row.ren_ms] : [];
  const present = values.some((value) => Number.isFinite(Number(value)) && Number(value) > 0);
  return present ? values.reduce((total, value) => total + (Number(value) || 0), 0) : null;
}

function stageStatus(stage, row) {
  if (!row) return { label: "Not run", state: "idle" };
  if (stage === "detect" && row.detected) return { label: "Complete", state: "done" };
  if (stage === "ocr" && row.ocr) return { label: "Complete", state: "done" };
  if (stage === "translate" && Number(row.translated) > 0) {
    const complete = Number(row.translated) >= Number(row.regions || 0);
    return { label: complete ? "Complete" : `Partial · ${row.translated}/${row.regions}`, state: complete ? "done" : "partial" };
  }
  if (stage === "inpaint" && row.inpainted) return { label: "Complete", state: "done" };
  if (stage === "render" && row.rendered) return { label: "Complete", state: "done" };
  if (stage === "translate" && row.ocr) return { label: "Ready", state: "ready" };
  if (stage === "inpaint" && row.ocr) return { label: "Ready", state: "ready" };
  if (stage === "render" && row.ocr) return { label: "Ready", state: "ready" };
  return { label: "Waiting", state: "idle" };
}

function refreshStagePipeline(page = S.current) {
  const pipeline = $("stagePipeline");
  if (!pipeline) return;
  ensureStageDispatchStore();
  const row = S.overview?.pages?.find((item) => item.page === page) || null;
  const pageIndex = S.pages.indexOf(page);
  const pageLabel = $("stagePipelinePage");
  if (pageLabel) {
    pageLabel.textContent = pageIndex >= 0 ? `${pageIndex + 1}/${S.pages.length}` : "No page";
    pageLabel.title = page || "No page selected";
  }
  STUDIO_STAGES.forEach((stage) => {
    const stageRow = pipeline.querySelector(`[data-stage-row="${stage}"]`);
    const statusNode = pipeline.querySelector(`[data-stage-status="${stage}"]`);
    const timeNode = pipeline.querySelector(`[data-stage-time="${stage}"]`);
    const dispatchNode = pipeline.querySelector(`[data-stage-dispatches="${stage}"]`);
    const button = stageRow?.querySelector(".stage-run");
    const running = Boolean(page && S.processing[page]
      && (S.activeStage === stage || S.activeStage === "all"));
    const status = running ? { label: "Running", state: "running" } : stageStatus(stage, row);
    if (statusNode) { statusNode.textContent = status.label; statusNode.dataset.state = status.state; }
    if (stageRow) stageRow.dataset.state = status.state;
    if (timeNode) {
      const time = stageTimeMs(stage, row, page);
      timeNode.textContent = time === null ? "—" : `${Math.round(time)} ms`;
      timeNode.title = stage === "translate"
        ? "Cumulative client-measured time for direct Translate requests in this browser session."
        : "Elapsed time from the selected page's cached pipeline metrics.";
    }
    if (dispatchNode) {
      const count = page ? stageDispatchCount(page, stage) : 0;
      dispatchNode.textContent = `${count} dispatch${count === 1 ? "" : "es"}`;
      dispatchNode.title = "Studio requests issued in this browser session; backend model calls are not counted here.";
    }
    if (button) {
      button.disabled = !page || Boolean(S.processing[page]);
      button.textContent = ["done", "partial"].includes(status.state) ? "Rerun" : "Run";
    }
  });
}

/* ==================================== Right Sidebar Pipeline Configuration */
function resetInpaintVariantParams() {
  if ($("setInpaintOpenCvMethod")) $("setInpaintOpenCvMethod").value = "telea";
  if ($("setInpaintTeleaRadius")) $("setInpaintTeleaRadius").value = 3;
  if ($("setBubbleErosion")) $("setBubbleErosion").value = 5;
  if ($("setInpaintFeatherPx")) $("setInpaintFeatherPx").value = "";
  syncInpaintVariantStatus();
}

function syncInpaintVariantStatus() {
  const status = $("inpaintVariantStatus");
  if (!status) return;
  const engine = $("setInpaintEngine")?.value || "legacy";
  const bubble = $("setInpaintBubbleLeg")?.value || "android-fill";
  const free = $("setInpaintFreeLeg")?.value || "opencv";
  const opencvMethod = $("setInpaintOpenCvMethod")?.value || "telea";
  const paramsMatch = opencvMethod === "telea"
    && Number($("setInpaintTeleaRadius")?.value) === 3
    && Number($("setBubbleErosion")?.value) === 5
    && !String($("setInpaintFeatherPx")?.value || "").trim();
  const preset = bubble === "android-fill" && free === "opencv"
    ? "Android FAST" : bubble === "android-fill" && free === "aot"
      ? "Android QUALITY" : "";
  const parity = engine === "android" && preset && paramsMatch;
  status.textContent = parity ? `${preset} · Android defaults` : "Experimental/non-parity variant";
  status.classList.toggle("parity", Boolean(parity));
}

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
  const conf = s.conf ?? 0.6;
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
  const savedOpacity = Number(localStorage.getItem("studio.maskOpacity"));
  if (Number.isFinite(savedOpacity) && savedOpacity >= 10 && savedOpacity <= 100) {
    $("maskOpacitySlider").value = String(savedOpacity);
  }
  const initialOpacity = Number($("maskOpacitySlider").value) || 85;
  document.documentElement.style.setProperty("--mask-opacity", (initialOpacity / 100).toString());
  if ($("maskOpacityVal")) $("maskOpacityVal").textContent = `${initialOpacity}%`;
  $("maskOpacitySlider").oninput = (e) => {
    const val = e.target.value;
    if ($("maskOpacityVal")) $("maskOpacityVal").textContent = val + "%";
    document.documentElement.style.setProperty("--mask-opacity", (val / 100).toString());
    localStorage.setItem("studio.maskOpacity", val);
  };
}

document.querySelectorAll("[data-open-settings]").forEach((button) => {
  button.addEventListener("click", () => $("btnSettings")?.click());
});

document.querySelectorAll("#advViewsSeg button").forEach((b) => {
  b.onclick = () => {
    S.viz = b.dataset.viz;
    document.querySelectorAll("#advViewsSeg button").forEach((x) => x.classList.toggle("active", x === b));
    if (b.dataset.viz === "render") setModeQuiet("ours");
    else setModeQuiet(null);
    rebuildVisible();
  };
});


/* ========================================================== processing */
async function runStage(stage) {
  const page = S.current;
  if (!page) return;
  const P = S.processing;
  P[page] = true;
  S.activeStage = stage;
  if (stage === "all") recordPageProcessDispatches(page, $("chkTranslate").checked);
  else recordStageDispatch(page, stage);
  refreshStagePipeline(page);
  refreshSectionStatus(page);
  refreshRailDots();
  try {
    if (stage === "all") await api("/api/process", {
      page, translate: $("chkTranslate").checked, force: true,
    });
    else if (stage === "detect") await api("/api/detect", { page, conf: S.settings.conf });
    else if (stage === "ocr") await api("/api/ocr", { page });
    else if (stage === "inpaint") await api("/api/inpaint", {
      page, mode: S.settings.inpaint_mode, force: true,
    });
    else if (stage === "translate") {
      const started = performance.now();
      await api("/api/translate", { page });
      recordStageElapsed(page, stage, performance.now() - started);
    }
    else if (stage === "render") await api("/api/render", { page });
    S.pageData.delete(page);
    clearArtifactData(page);
    delete S.pageError[page];
    await ensurePageData(page);
    await ensureArtifactData(page);
    applyArtifactFilters();
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
    S.activeStage = null;
    refreshStagePipeline(page);
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
        const activeName = String(active.stage || "").toLowerCase();
        S.activeStage = STUDIO_STAGES.find((stage) => activeName.includes(stage)) || "all";
        $("progressChip")?.classList.remove("hidden");
        if ($("pcText")) $("pcText").textContent = active.stage || "Working…";
        if ($("pcStage")) $("pcStage").textContent = active.page ? `${active.page} (${active.i + 1}/${active.n})` : "";
        const pct = active.n ? ((active.i / active.n) * 100).toFixed(0) : "0";
        if ($("pcFill")) $("pcFill").style.width = pct + "%";
        refreshStagePipeline(active.page || S.current);
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
    S.activeStage = "all";
    recordPageProcessDispatches(p, $("chkTranslate").checked);
    refreshStagePipeline(p);
    refreshSectionStatus(p);
    refreshRailDots();
    try {
      await api("/api/process", { page: p, conf: S.settings.conf, translate: $("chkTranslate").checked });
      S.pageData.delete(p);
      clearArtifactData(p);
      delete S.pageError[p];
      await ensureArtifactData(p);
      refreshRenderedImages(p);
      const sec = secEl(p);
      if (sec) { sec._builtKey = null; ensureSection(p); }
    } catch (e) {
      failed++;
      S.pageError[p] = e.message.slice(0, 80);
      logLine(`!! process ${p}: ${e.message}`, "err");
    }
    delete S.processing[p];
    S.activeStage = null;
    refreshStagePipeline(p);
    await refreshOverview();
  }
  if ($("pcText")) $("pcText").textContent = failed ? `Done — ${failed} failed` : "Done";
  setTimeout(() => $("progressChip")?.classList.add("hidden"), 2200);
  stopStagePolling();
  S.busy = false;
  S.activeStage = null;
  refreshStagePipeline();
  $("btnProcessChapter").disabled = false;
  logLine(`studio: chapter processed (${total} pages, ${failed} failed)`);
}
$("btnProcessChapter")?.addEventListener("click", processChapter);


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
      clearArtifactData();
      S.pageError = {};
      deselectRegion();
    } else {
      S.pageData.delete(page);
      clearArtifactData(page);
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
      await ensureArtifactData(S.current);
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
    html += `<span class="sb-speed-chip" title="Inpaint: ${inp.infer_ms}ms"><span class="lbl">Inpaint</span><span class="val">${inp.infer_ms}ms</span></span>`;
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
  refreshStagePipeline(S.current);
}

function renderStageBenchmarkRows(rows, loads) {
  const stages = [
    { key: "detect", label: "Detect", duration: (row) => Number(row.det_ms || 0) + Number(row.seg_ms || 0), ran: (row) => row.detected, load: ["detector", "segmenter"] },
    { key: "ocr", label: "OCR", duration: (row) => Number(row.ocr_ms || 0), ran: (row) => row.ocr, load: Object.keys(loads).filter((name) => name.startsWith("ocr_")) },
    { key: "translate", label: "Translate", duration: (row) => stageElapsedMs(row.page, "translate"), ran: (row) => stageElapsedMs(row.page, "translate") > 0, load: [] },
    { key: "inpaint", label: "Inpaint", duration: (row) => Number(row.inp_ms || 0), ran: (row) => row.inpainted, load: ["aot"] },
    { key: "render", label: "Render", duration: (row) => Number(row.ren_ms || 0), ran: (row) => row.rendered, load: [] },
  ];
  return stages.map((stage) => {
    const measured = rows.filter(stage.ran).reduce((total, row) => total + (stage.duration(row) || 0), 0);
    const hasMeasurement = rows.some(stage.ran) && stage.duration(rows.find(stage.ran)) !== null;
    const stageLoad = (Array.isArray(stage.load) ? stage.load : [])
      .reduce((total, key) => total + Number(loads[key] || 0), 0);
    const dispatches = chapterStageDispatchCount(stage.key);
    const timeCell = hasMeasurement ? `${Math.round(measured)} ms` : "—";
    const loadCell = stageLoad > 0 ? `${Math.round(stageLoad)} ms` : "—";
    return `<tr><th scope="row">${stage.label}</th><td>${timeCell}</td><td>${loadCell}</td><td>${dispatches}</td></tr>`;
  }).join("");
}


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
  const stageRows = renderStageBenchmarkRows(rows, sum.model_load_times || {});

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

    <div class="sb-label benchmark-heading">Stage Benchmark</div>
    <table class="ov-perf-table ov-stage-table">
      <thead><tr><th>Stage</th><th>Time</th><th>Model load</th><th>Dispatches</th></tr></thead>
      <tbody>${stageRows}</tbody>
    </table>
    <p class="ov-table-note">Pipeline times and model loads come from cached metrics. Translate sums client-measured direct Studio request time in this browser session; bundled page runs do not expose separate translation timing. Dispatches count Studio requests in this browser session.</p>

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
  clearTimeout(S.filterSaveTimer);
  S.chapter = st.chapter;
  S.reference = st.reference;
  S.pages = st.pages || [];
  S.dims = st.dims || {};
  S.settings = st.settings || {};
  S.filters = normalizeFilterState(S.settings.filters);
  if (st.recent) S.recent = st.recent;
  S.pageData.clear();
  S.artifactData.clear();
  S.artifactRecords.clear();
  S.maskData.clear();
  S.overview = null;
  S.current = null;
  S.selection = null;
  S.activeStage = null;
  ensureStageDispatchStore();
  ensureStageTimeStore();

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
  renderFilterControls(S.current);
  updateStatusBar();
  refreshStagePipeline(S.current);
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
  $("setInpaintOpenCvMethod").value = s.inpaint_opencv_method || "telea";
  $("setInpaintTeleaRadius").value = s.inpaint_telea_radius ?? 3;
  $("setInpaintFeatherPx").value = s.inpaint_feather_px ?? "";
  $("setTranslateBackend").value = s.translate_backend || "google";
  $("setConf").value = s.conf;
  $("setMaxBatch").value = s.max_batch;
  $("setMangaOcrSerial").checked = Boolean(s.mangaocr_serial_timing);
  $("setErase").value = s.erase;
  $("setFont").value = s.font_path || "";
  $("setFontScale").value = s.font_scale;
  $("setBubbleErosion").value = s.bubble_mask_erosion ?? 5;
  $("setEndpoint").value = s.endpoint;
  $("setModel").value = s.model;
  $("setLang").value = s.target_lang;
  syncInpaintVariantStatus();
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
  resetInpaintVariantParams();
});
$("btnResetInpaintParams")?.addEventListener("click", resetInpaintVariantParams);
["setInpaintEngine", "setInpaintBubbleLeg", "setInpaintFreeLeg",
  "setInpaintOpenCvMethod", "setInpaintTeleaRadius", "setBubbleErosion",
  "setInpaintFeatherPx"]
  .forEach((id) => $(id)?.addEventListener("input", syncInpaintVariantStatus));
["setInpaintEngine", "setInpaintBubbleLeg", "setInpaintFreeLeg",
  "setInpaintOpenCvMethod"]
  .forEach((id) => $(id)?.addEventListener("change", syncInpaintVariantStatus));
$("setClose")?.addEventListener("click", () => $("settingsDlg").close());
$("setSave")?.addEventListener("click", async () => {
  const previousVariant = S.settings || {};
  const s = await api("/api/settings", {
    ocr_engine: $("setOcrEngine").value,
    inpaint_mode: $("setInpaintMode").value,
    inpaint_engine: $("setInpaintEngine").value,
    inpaint_bubble_leg: $("setInpaintBubbleLeg").value,
    inpaint_free_leg: $("setInpaintFreeLeg").value,
    inpaint_opencv_method: $("setInpaintOpenCvMethod").value,
    inpaint_telea_radius: parseInt($("setInpaintTeleaRadius").value, 10) || 3,
    inpaint_feather_px: String($("setInpaintFeatherPx").value || "").trim()
      ? parseInt($("setInpaintFeatherPx").value, 10) : null,
    translate_backend: $("setTranslateBackend").value,
    conf: parseFloat($("setConf").value),
    max_batch: parseInt($("setMaxBatch").value),
    mangaocr_serial_timing: $("setMangaOcrSerial").checked,
    erase: $("setErase").value,
    font_path: $("setFont").value.trim(),
    font_scale: parseFloat($("setFontScale").value) || 1.0,
    bubble_mask_erosion: Number.isFinite(parseInt($("setBubbleErosion").value, 10))
      ? parseInt($("setBubbleErosion").value, 10) : 5,
    endpoint: $("setEndpoint").value.trim(),
    model: $("setModel").value.trim(),
    target_lang: $("setLang").value.trim(),
  });
  S.settings = s.settings;
  syncSettingsUI();
  const variantChanged = ["inpaint_mode", "inpaint_engine", "inpaint_bubble_leg",
    "inpaint_free_leg", "inpaint_opencv_method", "inpaint_telea_radius",
    "bubble_mask_erosion",
    "inpaint_feather_px"].some((key) => previousVariant[key] !== S.settings[key]);
  if (variantChanged && S.current) {
    recordStageDispatch(S.current, "inpaint");
    const result = await api("/api/inpaint", {
      page: S.current, mode: S.settings.inpaint_mode, force: false,
    });
    logLine(`inpaint variant active: ${result.cache_hit ? "cache hit" : "computed"}`);
  }
  S.cacheBust = Date.now();
  S.pageData.clear();
  if (S.current) clearArtifactData(S.current);
  rebuildVisible();
  $("settingsDlg").close();
  logLine("studio: settings saved");
});
