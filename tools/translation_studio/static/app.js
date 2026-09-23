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
  artifactData: new Map(), // page -> read-only /api/artifacts cache payload
  artifactRecords: new Map(), // page -> normalized FILTER_SPEC records
  maskData: new Map(), // page -> component RLEs for vector overlay drawing
  maskOutlinePaths: new Map(), // bounded LRU of rendered mask component paths
  filters: null,
  filterSaveTimer: null,
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
  if (kind === "inpainted") return S.artifactData.get(page)?.assets?.inpaint_image
    ? VIZ.inpainted.src(page) : VIZ.original.src(page);
  if (kind === "goal") return VIZ.goal.src(page);
  if (kind === "inpaint_mask") return VIZ.inpaint_mask.src(page);
  if (kind === "segmentation") return VIZ.segmentation.src(page);
  return VIZ.render.src(page);
}

/* =============================== cached artifact filter model */
const FILTER_MODELS = [
  { id: "text-detector", title: "Text detector", source: "detections" },
  { id: "panel-detector", title: "Panel detector", source: "detections" },
  { id: "bubble-segmenter", title: "Bubble segmenter", source: "detections" },
  { id: "paddle-det", title: "Paddle refined lines", source: "inpaint" },
  { id: "ocr", title: "OCR", source: "ocr" },
  { id: "inpaint", title: "Inpaint routes", source: "inpaint" },
];
const FILTER_LABELS = {
  "text-detector": [[0, "Bubble"], [1, "Bubble text"], [2, "Free text"]],
  "panel-detector": [["panel", "Panel"]],
  "bubble-segmenter": [["mask-component", "Mask component"]],
  "paddle-det": [["refined-line", "Refined line"]],
  ocr: [[0, "Bubble text"], [1, "Bubble text"], [2, "Free text"]],
  inpaint: [["bubble", "Bubble leg"], ["freetext", "Free text leg"], ["detector-only", "Detector proposal"]],
};
const FILTER_STATES = ["raw", "kept", "suppressed", "merged", "context", "blank", "consumed", "pruned"];
const FILTER_STATE_LABELS = {
  raw: "Raw", kept: "Kept", suppressed: "Suppressed", merged: "Merged",
  context: "Context", blank: "Blank", consumed: "Consumed", pruned: "Pruned",
};
const MODEL_COLORS = {
  "text-detector": "#53d6b6", "panel-detector": "#65a9ff",
  "bubble-segmenter": "#c084fc", "paddle-det": "#22d3ee",
  ocr: "#fbbf24", inpaint: "#fb7185",
};

function filterDefaults() {
  const conf = Number(S.settings?.conf ?? 0.6);
  const models = {};
  FILTER_MODELS.forEach(({ id }) => {
    models[id] = {
      enabled: true,
      dimFiltered: ["text-detector", "panel-detector", "bubble-segmenter", "ocr"].includes(id),
      labels: null,
      scoreMin: id === "panel-detector" ? 0.5 : (id === "text-detector" || id === "ocr" || id === "inpaint" ? conf : 0),
      scoreMax: 1,
    };
  });
  return {
    preset: "production", models,
    states: ["kept", "consumed", "context"],
    suppressionRule: "",
    geometry: { widthMin: "", widthMax: "", heightMin: "", heightMax: "", aspectMin: "", aspectMax: "", tall: "" },
    ocr: { text: "", confidenceMin: 0, confidenceMax: 1, engine: "", outcome: "" },
    route: "", window: "", translation: [],
  };
}

function normalizeFilterState(raw) {
  const defaults = filterDefaults();
  if (!raw || typeof raw !== "object") return defaults;
  const out = { ...defaults, ...raw, models: { ...defaults.models },
    geometry: { ...defaults.geometry, ...(raw.geometry || {}) },
    ocr: { ...defaults.ocr, ...(raw.ocr || {}) } };
  FILTER_MODELS.forEach(({ id }) => {
    const model = { ...defaults.models[id], ...(raw.models?.[id] || {}) };
    const min = Number(model.scoreMin), max = Number(model.scoreMax);
    model.scoreMin = Math.max(0, Math.min(1, Number.isFinite(min) ? min : defaults.models[id].scoreMin));
    model.scoreMax = Math.max(model.scoreMin, Math.min(1, Number.isFinite(max) ? max : 1));
    if (!Array.isArray(model.labels)) model.labels = null;
    out.models[id] = model;
  });
  out.preset = ["production", "all-raw", "suppressed", "inpaint-audit", "custom"].includes(out.preset) ? out.preset : "custom";
  out.states = Array.isArray(out.states) ? out.states.filter((s) => FILTER_STATES.includes(s)) : defaults.states;
  out.translation = Array.isArray(out.translation) ? out.translation : [];
  out.suppressionRule = typeof out.suppressionRule === "string" ? out.suppressionRule : "";
  out.route = typeof out.route === "string" ? out.route : "";
  out.window = typeof out.window === "string" ? out.window : "";
  const confidenceMin = Number(out.ocr.confidenceMin), confidenceMax = Number(out.ocr.confidenceMax);
  out.ocr.confidenceMin = Math.max(0, Math.min(1, Number.isFinite(confidenceMin) ? confidenceMin : 0));
  out.ocr.confidenceMax = Math.max(out.ocr.confidenceMin, Math.min(1, Number.isFinite(confidenceMax) ? confidenceMax : 1));
  return out;
}

function presetFilterState(preset) {
  const next = filterDefaults();
  next.preset = preset;
  if (preset === "all-raw") {
    next.states = [...FILTER_STATES];
    FILTER_MODELS.forEach(({ id }) => { next.models[id].scoreMin = 0; next.models[id].dimFiltered = true; });
    next.ocr.confidenceMin = 0;
  } else if (preset === "suppressed") {
    next.states = ["suppressed"];
    FILTER_MODELS.forEach(({ id }) => { next.models[id].scoreMin = 0; next.models[id].dimFiltered = true; });
  } else if (preset === "inpaint-audit") {
    next.states = ["kept", "consumed"];
    FILTER_MODELS.forEach(({ id }) => { next.models[id].enabled = ["inpaint", "bubble-segmenter"].includes(id); });
    next.models["inpaint"].scoreMin = 0;
    next.models["bubble-segmenter"].scoreMin = 0;
    next.models["inpaint"].dimFiltered = false;
  }
  return next;
}

function ensureFilterState() {
  if (!S.filters) S.filters = normalizeFilterState(S.settings?.filters);
  return S.filters;
}

function modelGroupFor(model) {
  const name = String(model || "");
  if (name.startsWith("ocr:")) return "ocr";
  if (name.startsWith("inpaint:")) return "inpaint";
  return name;
}

function artifactBox(record) {
  const g = record?.geometry || {};
  if (Array.isArray(g)) return g.length >= 4 ? g.slice(0, 4).map(Number) : null;
  if (Array.isArray(g.box)) return g.box.slice(0, 4).map(Number);
  if ([g.x1, g.y1, g.x2, g.y2].every((v) => Number.isFinite(Number(v)))) {
    return [Number(g.x1), Number(g.y1), Number(g.x2), Number(g.y2)];
  }
  return null;
}

function artifactScore(record) {
  const n = Number(record?.attrs?.score);
  return Number.isFinite(n) ? n : null;
}

function artifactLabel(record) {
  const group = modelGroupFor(record?.source?.model);
  if (group === "bubble-segmenter") return "mask-component";
  if (group === "panel-detector") return "panel";
  if (group === "paddle-det") return "refined-line";
  if (group === "inpaint") return record?.attrs?.leg || (String(record?.source?.model).endsWith(":bubble") ? "bubble" : "freetext");
  return record?.attrs?.label ?? "region";
}

function boxGeometry(box) {
  if (!Array.isArray(box) || box.length < 4) return null;
  const [x1, y1, x2, y2] = box.slice(0, 4).map(Number);
  if (![x1, y1, x2, y2].every(Number.isFinite) || x2 <= x1 || y2 <= y1) return null;
  return { x1, y1, x2, y2 };
}

function boxShape(box) {
  const b = boxGeometry(box);
  return b ? { w: b.x2 - b.x1, h: b.y2 - b.y1, ar: (b.x2 - b.x1) / (b.y2 - b.y1) } : {};
}

function rleComponentBounds(runs, width, height) {
  if (width <= 0 || height <= 0 || !Array.isArray(runs)) return null;
  let left = width, top = height, right = 0, bottom = 0;
  for (let i = 0; i + 1 < (runs || []).length; i += 2) {
    let start = Math.max(0, Number(runs[i]) || 0);
    const end = Math.min(width * height, start + Math.max(0, Number(runs[i + 1]) || 0));
    while (start < end) {
      const y = Math.floor(start / width), x = start % width;
      const count = Math.min(end - start, width - x);
      left = Math.min(left, x); right = Math.max(right, x + count);
      top = Math.min(top, y); bottom = Math.max(bottom, y + 1);
      start += count;
    }
  }
  return right > left && bottom > top ? [left, top, right, bottom] : null;
}

function ensureArtifactData(page) {
  if (S.artifactData.has(page)) return Promise.resolve(S.artifactData.get(page));
  return api(`/api/artifacts?p=${enc(page)}`).then((payload) => {
    S.artifactData.set(page, payload || {});
    S.artifactRecords.set(page, buildArtifactRecords(page, payload || {}));
    if (page === S.current) renderFilterControls(page);
    if ((S.mode === "inpainted" || (S.mode === "compare" && S.compare === "orig-inpainted")) && page === S.current) {
      const section = secEl(page);
      if (section) { section._builtKey = null; ensureSection(page); }
      if (S.focus === page) buildFocus(page);
    }
    return payload;
  }).catch((error) => {
    logLine(`!! artifact cache ${page}: ${error.message}`, "err");
    const payload = { page, reason: error.message, sources: {} };
    S.artifactData.set(page, payload);
    S.artifactRecords.set(page, buildArtifactRecords(page, payload));
    if (page === S.current) renderFilterControls(page);
    return payload;
  });
}

function clearArtifactData(page = null) {
  if (page) {
    S.artifactData.delete(page);
    S.artifactRecords.delete(page);
    S.maskData.delete(page);
    S.maskOutlinePaths.delete(page);
  } else {
    S.artifactData.clear();
    S.artifactRecords.clear();
    S.maskData.clear();
    S.maskOutlinePaths.clear();
  }
  if (S.current) renderFilterControls(S.current);
}

function sourceRecord(record, group) {
  const copy = { ...record, _group: group };
  copy.page ||= S.current;
  copy.source = { ...(record.source || {}), model: record.source?.model || group };
  copy.attrs = { ...(record.attrs || {}) };
  copy.lifecycle = { state: record.lifecycle?.state || "kept", trace: [...(record.lifecycle?.trace || [])] };
  return copy;
}

function buildArtifactRecords(page, payload) {
  const records = [];
  const masks = new Map();
  const sources = payload.sources || {};
  const capture = sources.detections?.data || {};
  const ocrData = sources.ocr?.data || {};
  const translationData = sources.translations?.data || {};
  const inpaintData = sources.inpaint?.data || {};
  const modelRecords = new Map();
  const byArtifactId = new Map();
  const decisions = capture.decisions || {};
  const mergeEvents = Array.isArray(decisions.merge_records) ? decisions.merge_records : [];
  const decisionEvents = [
    ...(Array.isArray(decisions.suppression_records) ? decisions.suppression_records : []),
    ...mergeEvents,
  ];
  const addDecisionTrace = (record) => {
    const events = decisionEvents.filter((event) => event.loser_id === record.id || event.winner_id === record.id);
    if (!events.length) return record;
    record.lifecycle ||= { state: "kept", trace: [] };
    record.lifecycle.trace ||= [];
    for (const event of events) {
      const merge = mergeEvents.includes(event);
      record.lifecycle.trace.push({
        step: merge ? "merge" : event.rule,
        kept: event.winner_id === record.id,
        loser_id: event.loser_id,
        winner_id: event.winner_id,
        rule: event.rule,
        threshold: event.threshold,
        decision_record: true,
      });
    }
    return record;
  };
  const add = (record) => {
    if (!record || !record.id || !record.source?.model) return;
    records.push(record);
    const artifactId = record.attrs?.artifact_id || record.id;
    if (artifactId) byArtifactId.set(artifactId, record);
  };

  for (const group of ["text-detector", "panel-detector", "bubble-segmenter"]) {
    const model = capture.models?.[group] || {};
    const outputs = Array.isArray(model.outputs) ? model.outputs : [];
    const baseRecords = outputs.map((row) => {
      const copy = addDecisionTrace(sourceRecord(row, group));
      copy._phase = "raw-output";
      return copy;
    });
    if (group === "text-detector") {
      for (const row of model.derived_outputs || []) {
        const copy = addDecisionTrace(sourceRecord(row, group));
        copy._phase = "derived-output";
        baseRecords.push(copy);
      }
    }
    modelRecords.set(group, baseRecords);
    if (group !== "bubble-segmenter") {
      baseRecords.forEach((row) => add({ ...row, page }));
      continue;
    }
    const maskCache = capture.mask_cache?.["bubble-segmenter"] || {};
    for (const row of outputs) {
      const cacheEntry = maskCache[row.mask_ref] || {};
      const components = cacheEntry.components || {};
      const componentEntries = Object.keys(components).length
        ? Object.entries(components) : [["", cacheEntry.runs || []]];
      for (const [componentId, runs] of componentEntries) {
        const box = rleComponentBounds(runs, Number(cacheEntry.width || capture.page_wh?.[0] || 0),
                                       Number(cacheEntry.height || capture.page_wh?.[1] || 0));
        const id = componentId ? `${row.id}-c${componentId}` : row.id;
        const record = sourceRecord({ ...row, id, kind: "mask",
          attrs: { ...(row.attrs || {}), label: "mask-component", component_id: componentId || null,
            shape: boxShape(box) }, mask_ref: row.mask_ref }, group);
        record.page = page;
        record._maskBounds = box;
        masks.set(record.id, { runs, width: Number(cacheEntry.width || capture.page_wh?.[0] || 0),
          height: Number(cacheEntry.height || capture.page_wh?.[1] || 0), bounds: box });
        add(record);
      }
    }
  }

  // Older detection caches have no artifact schema; keep their boxes drawable.
  if (!records.some((r) => r._group === "text-detector") && Array.isArray(capture.boxes)) {
    capture.boxes.forEach((row, index) => {
      if (!Array.isArray(row) || row.length < 6) return;
      const geometry = boxGeometry(row.slice(2, 6));
      if (!geometry) return;
      const id = `legacy-det-${index}`;
      add({ id, page, kind: "box", _group: "text-detector",
        source: { model: "text-detector", asset: "legacy detection cache", window: null },
        geometry, attrs: { label: Number(row[0]), score: Number(row[1]), shape: boxShape(row.slice(2, 6)) },
        lifecycle: { state: Number(row[1]) >= Number(S.settings.conf ?? 0.6) ? "kept" : "raw", trace: [] } });
    });
  }

  const regions = Array.isArray(ocrData.regions) ? ocrData.regions : [];
  const regionById = new Map(regions.map((r) => [String(r.id), r]));
  const inpaintRegions = Array.isArray(inpaintData.regions) ? inpaintData.regions : [];
  const routeById = new Map(inpaintRegions.map((r) => [String(r.id), r]));
  const translationKeys = new Set(Object.keys(translationData || {}));
  const translationText = (id) => {
    const value = translationData?.[id];
    return typeof value === "string" ? value : String(value?.text || "");
  };
  const engine = String(ocrData.engine || "unknown");

  for (const region of regions) {
    const box = boxGeometry(region.box);
    if (!box) continue;
    const text = String(region.text || "");
    const confidence = Number(region.confidence);
    const blank = !text.trim() || (Number.isFinite(confidence) && confidence < 0.5);
    const out = region.error ? "error" : (!text.trim() ? "blank" : (blank ? "filtered" : "ok"));
    const route = routeById.get(String(region.id));
    const consumed = !!route && !String(route.route_taken || "").startsWith("skipped:");
    const state = blank ? "blank" : (consumed ? "consumed" : "kept");
    const translation = translationText(region.id);
    const cachedTranslation = translationKeys.has(String(region.id));
    const detector = byArtifactId.get(region.artifact_id);
    const window = detector?.source?.window ?? null;
    const commonAttrs = {
      label: region.label, score: region.score, confidence: region.confidence,
      text, engine, outcome: out, region_id: region.id,
      artifact_id: region.artifact_id,
      translation, translation_cached: cachedTranslation,
      translation_status: translation.trim() ? "translated" : (cachedTranslation ? "cached" : "missing"),
      shape: boxShape(region.box),
    };
    const trace = [{ step: "ocr", kept: !blank, engine, text,
      conf: Number.isFinite(confidence) ? confidence : null, outcome: out }];
    add({ id: `ocr-${region.id}`, page, kind: "region", _group: "ocr", _regionId: region.id,
      source: { model: `ocr:${engine}`, asset: engine, asset_sha: null, window },
      geometry: box, attrs: commonAttrs, lifecycle: { state, trace } });
    const ocrBox = boxGeometry(region.ocr_box || region.box);
    if (ocrBox) add({ id: `ocr-box-${region.id}`, page, kind: "box", _group: "ocr", _regionId: region.id,
      source: { model: `ocr:${engine}`, asset: engine, asset_sha: null, window },
      geometry: ocrBox, attrs: { ...commonAttrs, shape: boxShape(region.ocr_box || region.box), role: "ocr-input-box" },
      lifecycle: { state, trace: [...trace, { step: "ocr-box", kept: true }] } });
  }

  const detectionByArtifact = new Map();
  for (const row of modelRecords.get("text-detector") || []) {
    detectionByArtifact.set(row.id, row);
    if (row.id.startsWith("dt-")) detectionByArtifact.set(row.id, row);
  }
  for (const routeRecord of inpaintRegions) {
    const linkedRegion = regionById.get(String(routeRecord.id));
    const linkedDetector = detectionByArtifact.get(routeRecord.artifact_id) || byArtifactId.get(routeRecord.artifact_id);
    const sourceBoxes = Array.isArray(routeRecord.source_boxes) ? routeRecord.source_boxes : [];
    const primary = sourceBoxes.find((b) => b && (b.role === "ocr-origin" || b.role === "proposal"))
      || sourceBoxes.find((b) => b && b.role === "erase-box-plus-3px")
      || sourceBoxes.find((b) => b && Array.isArray(b.box));
    const box = boxGeometry(primary?.box || linkedRegion?.box);
    const route = String(routeRecord.route_taken || "");
    const label = routeRecord.kind === "detector-only" ? "detector-only"
      : (Number(routeRecord.label) === 1 ? "bubble" : "freetext");
    const sourceLeg = route.startsWith("bubble/") ? "bubble" : (route.startsWith("freetext/") ? "freetext" : label);
    const blank = route === "skipped:blank-ocr";
    const state = blank ? "blank" : (route.startsWith("bubble/") || route.startsWith("freetext/") ? "consumed" : "kept");
    const assignment = linkedRegion?.segmenter_assignment || linkedDetector?.segmenter_assignment || {};
    const sourceModel = sourceLeg === "detector-only" ? "inpaint:freetext" : `inpaint:${sourceLeg}`;
    const routeArtifact = {
      id: `inpaint-${routeRecord.id}`, page, kind: "region", _group: "inpaint",
      _regionId: linkedRegion?.id || routeRecord.id, _inpaintRecord: routeRecord,
      source: { model: sourceModel, asset: "android inpaint provenance", asset_sha: null,
        window: linkedDetector?.source?.window ?? null },
      geometry: box,
      mask_ref: routeRecord.mask_resolution?.mask_ref || assignment.mask_ref || null,
      attrs: { label: routeRecord.kind === "detector-only" ? "detector-only" : routeRecord.label,
        leg: sourceLeg, score: routeRecord.score ?? linkedRegion?.score ?? linkedDetector?.attrs?.score ?? null,
        route, route_taken: route, artifact_id: routeRecord.artifact_id,
        parent_region: routeRecord.id, text: routeRecord.text || linkedRegion?.text || "",
        mask_ref: routeRecord.mask_resolution?.mask_ref || assignment.mask_ref || null,
        segmenter_component_id: routeRecord.segmenter_component_id ?? assignment.mask_component_id ?? null,
        erase_mask_component_id: routeRecord.erase_mask_component_id ?? null,
        mask_component_id: routeRecord.mask_component_id ?? routeRecord.erase_mask_component_id ?? null,
        context_crop_bbox: routeRecord.context_crop_bbox || null,
        timing_ms: routeRecord.timing_ms || {}, timing_scope: routeRecord.timing_scope || "region",
        source_boxes: sourceBoxes, shape: boxShape(box) },
      lifecycle: { state, trace: [{ step: "inpaint-plan", kept: !blank, route,
        mask_component_id: routeRecord.mask_component_id ?? null }] },
    };
    add(routeArtifact);
    for (let i = 0; i < sourceBoxes.length; i++) {
      const refined = sourceBoxes[i];
      if (!refined || refined.source !== "paddle-refined" || !Array.isArray(refined.box)) continue;
      const refinedBox = boxGeometry(refined.box);
      if (!refinedBox) continue;
      add({ id: `paddle-${routeRecord.id}-${i}`, page, kind: "line", _group: "paddle-det",
        _regionId: linkedRegion?.id || routeRecord.id,
        source: { model: "paddle-det", asset: "Paddle detector", asset_sha: null,
          window: linkedDetector?.source?.window ?? null },
        geometry: refinedBox,
        attrs: { label: "refined-line", score: routeRecord.score ?? linkedRegion?.score ?? null,
          parent_region: routeRecord.id, artifact_id: routeRecord.artifact_id,
          shape: boxShape(refined.box), route },
        lifecycle: { state: "consumed", trace: [{ step: "paddle-refine", kept: true,
          parent_region: routeRecord.id }] } });
    }
  }

  S.maskData.set(page, masks);
  S.maskOutlinePaths.delete(page);
  return records;
}

function artifactGroupAvailability(page, group, payload, records) {
  const src = payload.sources || {};
  const capture = src.detections?.data || {};
  const model = capture.models?.[group] || {};
  const entries = (records || []).filter((r) => r._group === group);
  if (group === "text-detector") {
    if (src.detections?.status !== "ready") return { ok: false, reason: src.detections?.reason || "missing source: .studio/detections.json" };
    if (!entries.length) return { ok: false, reason: "no text-detector outputs in .studio/detections.json" };
  } else if (group === "panel-detector" || group === "bubble-segmenter") {
    if (src.detections?.status !== "ready") return { ok: false, reason: src.detections?.reason || "missing source: .studio/detections.json" };
    if (!entries.length) return { ok: false, reason: `.studio/detections.json has no ${group} outputs (${model.reason || model.status || "outputs empty"})` };
  } else if (group === "ocr") {
    if (src.ocr?.status !== "ready") return { ok: false, reason: src.ocr?.reason || "missing source: .studio/ocr.json" };
    if (!entries.length) return { ok: false, reason: "no OCR regions in .studio/ocr.json" };
  } else if (group === "inpaint") {
    if (src.inpaint?.status !== "ready") return { ok: false, reason: src.inpaint?.reason || "missing source: .studio/inpaint/<page>.json" };
    if (!entries.length) return { ok: false, reason: "no per-region routes in .studio/inpaint/<page>.json" };
  } else if (group === "paddle-det") {
    if (src.inpaint?.status !== "ready") return { ok: false, reason: src.inpaint?.reason || "missing source: .studio/inpaint/<page>.json" };
    if (!entries.length) return { ok: false, reason: "no Paddle-refined line records in .studio/inpaint/<page>.json" };
  }
  return { ok: true, reason: "" };
}

function sourceMissingReason(payload, name, filename) {
  const src = payload.sources?.[name];
  return src?.status === "ready" ? `required field unavailable in ${filename}`
    : (src?.reason || `missing source: ${filename}`);
}

function renderFilterControls(page) {
  const filters = ensureFilterState();
  const payload = S.artifactData.get(page) || {};
  const records = S.artifactRecords.get(page) || [];
  const capture = payload.sources?.detections?.data || {};
  const ocrRegions = Array.isArray(payload.sources?.ocr?.data?.regions) ? payload.sources.ocr.data.regions : [];
  const inpaintRegions = Array.isArray(payload.sources?.inpaint?.data?.regions) ? payload.sources.inpaint.data.regions : [];
  const translationData = payload.sources?.translations?.data;
  const translationReady = payload.sources?.translations?.status === "ready"
    && translationData && typeof translationData === "object" && Object.keys(translationData).length > 0;
  const groups = $("artifactModelGroups");
  if (!groups) return;
  groups.innerHTML = FILTER_MODELS.map(({ id, title }) => {
    const availability = artifactGroupAvailability(page, id, payload, records);
    const modelState = filters.models[id];
    const labels = FILTER_LABELS[id] || [];
    const scoreAvailable = records.some((r) => r._group === id && artifactScore(r) !== null);
    const disableTitle = availability.ok ? "" : ` title="${esc(availability.reason)}"`;
    const labelsHtml = labels.map(([value, label]) => {
      const checked = modelState.labels === null || modelState.labels.map(String).includes(String(value));
      return `<label title="${esc(availability.ok ? "Filter this label" : availability.reason)}"><input type="checkbox" data-filter-label-model="${id}" value="${esc(value)}" ${checked ? "checked" : ""} ${availability.ok ? "" : "disabled"}>${esc(label)}</label>`;
    }).join("");
    const min = modelState.scoreMin.toFixed(2), max = modelState.scoreMax.toFixed(2);
    const count = records.filter((r) => r._group === id).length;
    const scoreSource = id === "ocr" ? ".studio/ocr.json"
      : (id === "inpaint" || id === "paddle-det" ? ".studio/inpaint/<page>.json" : ".studio/detections.json");
    const scoreTitle = availability.ok && scoreAvailable ? "Filter cached model scores"
      : (availability.reason || `score field unavailable in ${scoreSource}`);
    const scoreDisabled = availability.ok && scoreAvailable ? "" : "disabled";
    return `<section class="filter-model-group" data-model-group="${id}">
      <div class="filter-model-head">
        <label${disableTitle}><input type="checkbox" data-filter-model="${id}" ${modelState.enabled ? "checked" : ""} ${availability.ok ? "" : "disabled"}><span class="filter-model-name">${esc(title)}</span></label>
        <span class="filter-model-count" data-filter-count="${id}">0/${count}</span>
        <label class="filter-dim-toggle" title="Show filtered artifacts as faint near-misses"><input type="checkbox" data-filter-dim="${id}" ${modelState.dimFiltered ? "checked" : ""} ${availability.ok ? "" : "disabled"}>dim</label>
      </div>
      <div class="filter-model-labels">${labelsHtml}</div>
      <div class="filter-score">
        <div class="filter-score-label"><span>score ${scoreAvailable ? "" : `(disabled: ${esc(availability.reason || "no scores in cache")})`}</span><span data-score-label="${id}">${min} – ${max}</span></div>
        <div class="filter-score-sliders">
          <input type="range" min="0" max="1" step="0.01" value="${min}" data-filter-score-min="${id}" aria-label="${esc(title)} score minimum" title="${esc(scoreTitle)}" ${scoreDisabled}>
          <input type="range" min="0" max="1" step="0.01" value="${max}" data-filter-score-max="${id}" aria-label="${esc(title)} score maximum" title="${esc(scoreTitle)}" ${scoreDisabled}>
        </div>
      </div>
    </section>`;
  }).join("");

  const stateRoot = $("filterStates");
  if (stateRoot) {
    const detectionReady = payload.sources?.detections?.status === "ready" &&
      Number(capture.capture_version || 0) === 1;
    const ocrReady = payload.sources?.ocr?.status === "ready" && ocrRegions.length > 0;
    const inpaintReady = payload.sources?.inpaint?.status === "ready" && inpaintRegions.length > 0;
    const supports = {
      raw: detectionReady, kept: detectionReady, suppressed: detectionReady,
      merged: detectionReady, context: detectionReady, blank: ocrReady,
      consumed: inpaintReady, pruned: false,
    };
    const reasons = {
      raw: detectionReady ? "" : sourceMissingReason(payload, "detections", ".studio/detections.json (capture_version 1 lifecycle)"),
      kept: detectionReady ? "" : sourceMissingReason(payload, "detections", ".studio/detections.json (capture_version 1 lifecycle)"),
      suppressed: detectionReady ? "" : sourceMissingReason(payload, "detections", ".studio/detections.json suppression_records"),
      merged: detectionReady ? "" : sourceMissingReason(payload, "detections", ".studio/detections.json merge_records"),
      context: detectionReady ? "" : sourceMissingReason(payload, "detections", ".studio/detections.json context_ids"),
      blank: ocrReady ? "" : sourceMissingReason(payload, "ocr", ".studio/ocr.json regions"),
      consumed: inpaintReady ? "" : sourceMissingReason(payload, "inpaint", ".studio/inpaint/<page>.json"),
      pruned: "C2 prune records are not present in the current translation cache.",
    };
    stateRoot.innerHTML = FILTER_STATES.map((state) => `<label title="${esc(reasons[state] || "Lifecycle state from cached records")}"><input type="checkbox" data-filter-state="${state}" ${filters.states.includes(state) ? "checked" : ""} ${supports[state] ? "" : "disabled"}>${FILTER_STATE_LABELS[state]}</label>`).join("");
    if (reasons.pruned) stateRoot.querySelector('[data-filter-state="pruned"]')?.setAttribute("title", reasons.pruned);
    if (!translationReady) $("filterTranslationNote").textContent = sourceMissingReason(payload, "translations", ".studio/translations.json");
    else $("filterTranslationNote").textContent = "User edit and prune provenance unavailable: translations.json stores text without origin metadata.";
  }

  const suppression = $("filterSuppression");
  if (suppression) {
    const enabled = payload.sources?.detections?.status === "ready" &&
      (Array.isArray(capture.decisions?.suppression_records) || Array.isArray(capture.decisions?.merge_records));
    suppression.disabled = !enabled;
    suppression.title = enabled ? "Filter by recorded suppression/merge rule" : sourceMissingReason(payload, "detections", ".studio/detections.json decisions.suppression_records / merge_records");
    suppression.value = filters.suppressionRule || "";
  }
  const geometryAvailable = records.some((r) => artifactBox(r) || r.attrs?.shape?.w);
  ["filterWidthMin", "filterWidthMax", "filterHeightMin", "filterHeightMax", "filterAspectMin", "filterAspectMax"].forEach((id) => {
    const control = $(id); if (control) { control.disabled = !geometryAvailable; control.title = geometryAvailable ? "Filter cached geometry" : "Geometry unavailable in .studio/detections.json, .studio/ocr.json, and .studio/inpaint/<page>.json"; }
  });
  const tallControl = $("filterTall");
  if (tallControl) {
    const tallAvailable = payload.sources?.detections?.status === "ready" && typeof capture.is_tall === "boolean";
    tallControl.disabled = !tallAvailable;
    tallControl.title = tallAvailable ? "Filter by cached page shape" : sourceMissingReason(payload, "detections", ".studio/detections.json is_tall");
  }
  const ocrEnabled = payload.sources?.ocr?.status === "ready" && ocrRegions.length > 0;
  const ocrDimensions = {
    filterOcrText: [ocrEnabled && ocrRegions.some((r) => typeof r.text === "string"), ".studio/ocr.json regions[].text"],
    filterOcrEngine: [ocrEnabled && (payload.sources.ocr.data.engine || ocrRegions.some((r) => r.engine)), ".studio/ocr.json engine"],
      filterOcrOutcome: [ocrEnabled && ocrRegions.some((r) => typeof r.text === "string" || r.error !== undefined), ".studio/ocr.json regions[].text/error"],
      filterOcrConfidenceMin: [ocrEnabled && ocrRegions.some((r) => r.confidence !== null && r.confidence !== "" && Number.isFinite(Number(r.confidence))), ".studio/ocr.json regions[].confidence"],
      filterOcrConfidenceMax: [ocrEnabled && ocrRegions.some((r) => r.confidence !== null && r.confidence !== "" && Number.isFinite(Number(r.confidence))), ".studio/ocr.json regions[].confidence"],
  };
  Object.entries(ocrDimensions).forEach(([id, [available, field]]) => {
    const control = $(id); if (control) { control.disabled = !available; control.title = available ? "Filter cached OCR records" : sourceMissingReason(payload, "ocr", field); }
  });
  const ocrSelect = $("filterOcrEngine");
  if (ocrSelect) {
    const engines = [...new Set(records.filter((r) => r._group === "ocr").map((r) => r.attrs?.engine).filter(Boolean))];
    ocrSelect.innerHTML = `<option value="">All engines</option>${engines.map((e) => `<option value="${esc(e)}">${esc(e)}</option>`).join("")}`;
    ocrSelect.value = filters.ocr.engine || "";
  }
  const routeSelect = $("filterRoute");
  if (routeSelect) {
    const routes = [...new Set(records.filter((r) => r._group === "inpaint").map((r) => r.attrs?.route).filter(Boolean))].sort();
    routeSelect.innerHTML = `<option value="">All routes</option>${routes.map((v) => `<option value="${esc(v)}">${esc(v)}</option>`).join("")}`;
    const ready = payload.sources?.inpaint?.status === "ready" && inpaintRegions.length > 0 && routes.length > 0;
    routeSelect.disabled = !ready;
    routeSelect.title = ready ? "Filter cached inpaint routes" : sourceMissingReason(payload, "inpaint", ".studio/inpaint/<page>.json");
    routeSelect.value = filters.route || "";
  }
  const windowSelect = $("filterWindow");
  if (windowSelect) {
    const windows = Array.isArray(capture.windows) ? capture.windows : [];
    const hasWindowData = payload.sources?.detections?.status === "ready" && windows.some((w) => w.index !== null);
    windowSelect.innerHTML = `<option value="">All windows</option>${windows.map((w) => `<option value="${w.index === null ? "full" : w.index}">${w.index === null ? "Full page" : `Window ${w.index}`}</option>`).join("")}`;
    windowSelect.disabled = !hasWindowData;
    windowSelect.title = hasWindowData ? "Filter cached sliding-window outputs" : sourceMissingReason(payload, "detections", ".studio/detections.json windows (tall-page capture)");
    windowSelect.value = filters.window || "";
  }
  const translationInputs = $("filterTranslations")?.querySelectorAll("input[type=checkbox]") || [];
  translationInputs.forEach((input) => {
    if (input.value === "cached" || input.value === "translated") {
      const ready = translationReady;
      input.disabled = !ready;
      input.title = ready ? "Filter by cached translation state" : sourceMissingReason(payload, "translations", ".studio/translations.json");
      input.checked = filters.translation.includes(input.value);
    }
  });
  const preset = $("filterPreset");
  if (preset) preset.value = filters.preset;
  syncFilterInputs(filters);
  updateFilterCounts(page);
}

function syncFilterInputs(filters) {
  const g = filters.geometry, o = filters.ocr;
  const values = {
    filterWidthMin: g.widthMin, filterWidthMax: g.widthMax, filterHeightMin: g.heightMin,
    filterHeightMax: g.heightMax, filterAspectMin: g.aspectMin, filterAspectMax: g.aspectMax,
    filterTall: g.tall, filterOcrText: o.text, filterOcrConfidenceMin: o.confidenceMin,
    filterOcrConfidenceMax: o.confidenceMax, filterOcrOutcome: o.outcome,
  };
  Object.entries(values).forEach(([id, value]) => { if ($(id) && document.activeElement !== $(id)) $(id).value = value; });
}

function currentExecutionConfidence(page) {
  const payload = S.artifactData.get(page) || {};
  const capture = payload.sources?.detections?.data || {};
  const inpaint = payload.sources?.inpaint?.data || {};
  const value = inpaint.execution?.confidence_threshold
    ?? capture.models?.["text-detector"]?.decisions?.conf
    ?? capture.decisions?.conf
    ?? S.settings?.conf ?? 0.6;
  return Number(value);
}

function isDisplayNoDownstream(record, page) {
  if (record._group !== "text-detector" || record.lifecycle?.state !== "raw") return false;
  if (Number(record.attrs?.label) === 0) return false;
  const score = artifactScore(record);
  const execution = currentExecutionConfidence(page);
  const minimum = ensureFilterState().models["text-detector"].scoreMin;
  return score !== null && Number.isFinite(execution) && score >= minimum && score < execution;
}

function traceRules(record) {
  const rules = new Set();
  for (const step of record.lifecycle?.trace || []) {
    if (step.rule) {
      const rule = String(step.rule);
      rules.add(rule);
      if (rule.startsWith("win-merge")) rules.add("win-merge");
    }
    if (step.step === "merge") rules.add("win-merge");
    if (step.step && ["det-dedup", "ocr-dedup", "xlabel", "win-merge"].includes(step.step)) rules.add(String(step.step));
  }
  return rules;
}

function matchesArtifactFilters(record, page) {
  const f = ensureFilterState();
  const group = record._group || modelGroupFor(record.source?.model);
  const groupState = f.models[group];
  if (!groupState || !groupState.enabled) return false;
  if (f.preset === "production" && group === "text-detector" && record._phase === "raw-output") return false;
  const label = artifactLabel(record);
  if (Array.isArray(groupState.labels) && !groupState.labels.map(String).includes(String(label))) return false;

  const score = artifactScore(record);
  if (score !== null && (score < groupState.scoreMin || score > groupState.scoreMax)) return false;
  if (score === null && (groupState.scoreMin > 0 || groupState.scoreMax < 1)) return false;

  const state = String(record.lifecycle?.state || "kept");
  const noDownstream = isDisplayNoDownstream(record, page);
  const admitNoDownstream = noDownstream && (f.preset === "production" || f.preset === "all-raw" || f.states.includes("kept"));
  if (!f.states.includes(state) && !admitNoDownstream) return false;
  if (f.suppressionRule && !traceRules(record).has(f.suppressionRule)) return false;

  const geometry = f.geometry;
  const geometryActive = [geometry.widthMin, geometry.widthMax, geometry.heightMin,
    geometry.heightMax, geometry.aspectMin, geometry.aspectMax].some((v) => v !== "" && v !== null)
    || !!geometry.tall;
  if (geometryActive) {
    const box = artifactBox(record);
    const shape = record.attrs?.shape || (box ? boxShape(box) : null);
    if (!shape) return false;
    const checks = [
      [geometry.widthMin, shape.w, (v, n) => n >= v], [geometry.widthMax, shape.w, (v, n) => n <= v],
      [geometry.heightMin, shape.h, (v, n) => n >= v], [geometry.heightMax, shape.h, (v, n) => n <= v],
      [geometry.aspectMin, shape.ar, (v, n) => n >= v], [geometry.aspectMax, shape.ar, (v, n) => n <= v],
    ];
    if (checks.some(([raw, value, test]) => raw !== "" && raw !== null && (!Number.isFinite(Number(value)) || !test(Number(raw), Number(value))))) return false;
    if (geometry.tall) {
      const capture = (S.artifactData.get(page)?.sources?.detections?.data) || {};
      const tall = capture.is_tall === true;
      if ((geometry.tall === "tall") !== tall) return false;
    }
  }

  const o = f.ocr;
  const ocrActive = !!o.text || !!o.engine || !!o.outcome || o.confidenceMin > 0 || o.confidenceMax < 1;
  if (ocrActive) {
    if (group !== "ocr") return false;
    const attrs = record.attrs || {};
    if (o.text && !String(attrs.text || "").toLocaleLowerCase().includes(o.text.toLocaleLowerCase())) return false;
    if (o.engine && attrs.engine !== o.engine) return false;
    if (o.outcome && attrs.outcome !== o.outcome) return false;
    const confidence = Number(attrs.confidence);
    if (!Number.isFinite(confidence) || confidence < o.confidenceMin || confidence > o.confidenceMax) return false;
  }
  if (f.route && (group !== "inpaint" || record.attrs?.route !== f.route)) return false;

  if (f.window) {
    const value = record.source?.window === null || record.source?.window === undefined
      ? "full" : String(record.source.window);
    if (value !== f.window) return false;
  }
  if (f.translation.length) {
    const attrs = record.attrs || {};
    if (group !== "ocr" && group !== "inpaint") return false;
    const states = [];
    if (attrs.translation_cached) states.push("cached");
    if (String(attrs.translation || "").trim()) states.push("translated");
    if (!f.translation.some((value) => states.includes(value))) return false;
  }

  if (f.preset === "inpaint-audit" && group === "bubble-segmenter") {
    const routes = (S.artifactRecords.get(page) || []).filter((r) => r._group === "inpaint" && r.attrs?.mask_ref);
    const refs = new Set(routes.map((r) => String(r.attrs.mask_ref)));
    if (!record.mask_ref || !refs.has(String(record.mask_ref))) return false;
  }
  return true;
}

function layerAllowsArtifact(record) {
  if (record.kind === "mask") return !!S.tree.maskSeg;
  const label = artifactLabel(record);
  if (record._group === "inpaint" || record._group === "paddle-det") {
    if (label === "freetext" || label === "detector-only" || Number(record.attrs?.label) === 2) return !!S.tree.boxFreeText;
    return !!S.tree.boxBubbleText;
  }
  if (record._group === "panel-detector") return true;
  if (Number(label) === 0) return !!S.tree.boxBubble;
  if (Number(label) === 2) return !!S.tree.boxFreeText;
  return !!S.tree.boxBubbleText;
}

function evaluateArtifact(record, page) {
  const f = ensureFilterState();
  const group = record._group || modelGroupFor(record.source?.model);
  const modelState = f.models[group];
  if (!modelState?.enabled || !layerAllowsArtifact(record)) return { show: false, match: false, ghost: false, noDownstream: false };
  const match = matchesArtifactFilters(record, page);
  const state = record.lifecycle?.state || "kept";
  const ghost = !match && !!modelState.dimFiltered && (
    ["raw", "suppressed", "merged"].includes(state)
    || f.preset === "suppressed"
    || (f.states.length === 1 && f.states[0] === "suppressed")
    || f.preset === "all-raw"
  );
  const noDownstream = isDisplayNoDownstream(record, page);
  return { show: match || ghost, match, ghost, noDownstream };
}

function updateFilterCounts(page, evaluated = null) {
  const records = S.artifactRecords.get(page) || [];
  let matchedTotal = 0;
  for (const { id } of FILTER_MODELS) {
    const items = records.filter((r) => r._group === id);
    const count = items.filter((r) => (evaluated?.get(r.id) || evaluateArtifact(r, page)).show).length;
    matchedTotal += count;
    const node = document.querySelector(`[data-filter-count="${id}"]`);
    if (node) node.textContent = `${count}/${items.length}`;
  }
  const total = document.querySelector("#filterTotalCount");
  if (total) total.textContent = `${matchedTotal}/${records.length}`;
  const exportButton = $("btnFilterExport");
  if (exportButton) exportButton.disabled = !page || records.length === 0;
}

let artifactSvgCounter = 0;
function rleOutlinePath(runs, width, height) {
  if (!Array.isArray(runs) || width <= 0 || height <= 0) return "";
  const rows = new Map();
  const total = width * height;
  for (let i = 0; i + 1 < runs.length; i += 2) {
    let start = Math.max(0, Number(runs[i]) || 0);
    const end = Math.min(total, start + Math.max(0, Number(runs[i + 1]) || 0));
    while (start < end) {
      const y = Math.floor(start / width), x = start % width;
      const count = Math.min(end - start, width - x);
      if (!rows.has(y)) rows.set(y, []);
      rows.get(y).push([x, x + count]);
      start += count;
    }
  }
  const normalize = (ranges) => {
    const sorted = [...(ranges || [])].sort((a, b) => a[0] - b[0]);
    const merged = [];
    for (const [start, end] of sorted) {
      const last = merged[merged.length - 1];
      if (last && start <= last[1]) last[1] = Math.max(last[1], end);
      else merged.push([start, end]);
    }
    return merged;
  };
  for (const [y, spans] of rows) rows.set(y, normalize(spans));
  const uncovered = (x1, x2, ranges) => {
    let cursor = x1;
    const gaps = [];
    for (const [a, b] of ranges || []) {
      if (b <= cursor || a >= x2) continue;
      if (a > cursor) gaps.push([cursor, Math.min(a, x2)]);
      cursor = Math.max(cursor, b);
      if (cursor >= x2) break;
    }
    if (cursor < x2) gaps.push([cursor, x2]);
    return gaps;
  };
  const d = [];
  for (const [y, spans] of rows) {
    const prev = rows.get(y - 1) || [], next = rows.get(y + 1) || [];
    spans.forEach(([x1, x2]) => {
      d.push(`M${x1} ${y}V${y + 1} M${x2} ${y}V${y + 1}`);
      uncovered(x1, x2, prev).forEach(([a, b]) => d.push(`M${a} ${y}H${b}`));
      uncovered(x1, x2, next).forEach(([a, b]) => d.push(`M${a} ${y + 1}H${b}`));
    });
  }
  return d.join("");
}

const MASK_PATH_PAGE_LIMIT = 2;
const MASK_PATH_PAGE_BUDGET = 2_000_000;
function cachedMaskOutlinePath(page, recordId, data) {
  let cache = S.maskOutlinePaths.get(page);
  if (cache) {
    S.maskOutlinePaths.delete(page);
    S.maskOutlinePaths.set(page, cache);
  } else {
    cache = { paths: new Map(), size: 0 };
    S.maskOutlinePaths.set(page, cache);
    while (S.maskOutlinePaths.size > MASK_PATH_PAGE_LIMIT) {
      S.maskOutlinePaths.delete(S.maskOutlinePaths.keys().next().value);
    }
  }
  if (cache.paths.has(recordId)) return cache.paths.get(recordId);
  const path = rleOutlinePath(data.runs, data.width, data.height);
  if (path.length <= MASK_PATH_PAGE_BUDGET && cache.size + path.length <= MASK_PATH_PAGE_BUDGET) {
    cache.paths.set(recordId, path);
    cache.size += path.length;
  }
  return path;
}

function artifactBadge(record) {
  if (!S.tree.typeBadges) return "";
  const source = String(record.source?.model || "artifact");
  const route = record.attrs?.route || "";
  const suffix = route ? ` · ${route}` : "";
  const artifactId = record.attrs?.artifact_id || record.artifact_id || record.id;
  const decision = (record.lifecycle?.trace || []).find((step) => step.kept === false && (step.rule || step.step));
  const rule = decision ? ` · ${decision.rule || decision.step}` : "";
  return `${source}${suffix} · ${artifactId}${rule}`;
}

function artifactTextBadge(record, box, pageWidth, pageHeight) {
  if (!S.tree.textBadges || record._group !== "ocr" || record.kind !== "region" || !box) return "";
  const sourceText = String(record.attrs?.text || "").trim();
  const translation = String(record.attrs?.translation || "").trim();
  const lines = [sourceText, translation ? `→ ${translation}` : ""].filter(Boolean);
  const fullText = lines.join(" → ");
  if (!fullText) return "";
  const fontSize = Math.max(14, pageWidth * 0.018);
  const maxWidth = Math.min(pageWidth * 0.55, Math.max(box[2] - box[0], fontSize * 8));
  const maxChars = Math.max(4, Math.floor((maxWidth - fontSize) / (fontSize * 0.58)));
  const labels = lines.map((line) => {
    const chars = Array.from(line);
    return chars.length > maxChars ? `${chars.slice(0, maxChars - 1).join("")}…` : line;
  });
  const lineHeight = fontSize * 1.12;
  const badgeWidth = Math.min(maxWidth, Math.max(fontSize * 6,
    ...labels.map((line) => line.length * fontSize * 0.58 + fontSize)));
  const badgeHeight = labels.length * lineHeight + fontSize * 0.45;
  const x = Math.max(0, Math.min(pageWidth - badgeWidth, box[0]));
  let top = box[3] + 3;
  if (top + badgeHeight > pageHeight) top = Math.max(0, box[1] - badgeHeight - 3);
  const tspans = labels.map((line, index) => `<tspan x="${x + fontSize * 0.5}" y="${top + fontSize + index * lineHeight}">${esc(line)}</tspan>`).join("");
  return `<g class="artifact-text-badge"><title>${esc(fullText)}</title><rect x="${x}" y="${top}" width="${badgeWidth}" height="${badgeHeight}" rx="3"/><text font-size="${fontSize}">${tspans}</text></g>`;
}

function artifactSvgMarkup(page, records, evaluated = null) {
  const [width, height] = S.dims[page] || [1, 1];
  const id = `artifact-hatch-${++artifactSvgCounter}`;
  const groups = [];
  for (const record of records) {
    const state = evaluated?.get(record.id) || evaluateArtifact(record, page);
    if (!state.show) continue;
    const group = record._group || modelGroupFor(record.source?.model);
    const color = MODEL_COLORS[group] || "#e5e7eb";
    const selected = S.selection?.page === page && S.selection?.id === record.id;
    const box = record._maskBounds || artifactBox(record);
    let shape = "";
    if (record.kind === "mask") {
      const data = S.maskData.get(page)?.get(record.id);
      const path = data ? cachedMaskOutlinePath(page, record.id, data) : "";
      if (!path || !box) continue;
      shape = `<path class="artifact-shape" d="${path}" fill="none" stroke="${color}" stroke-width="1.4"/>` +
        `<rect class="artifact-hit" x="${box[0]}" y="${box[1]}" width="${box[2] - box[0]}" height="${box[3] - box[1]}" fill="transparent" stroke="transparent" pointer-events="all"/>`;
    } else if (box) {
      const dash = record.kind === "line" ? ` stroke-dasharray="5 3"` : "";
      const fill = state.noDownstream ? `url(#${id})` : "transparent";
      shape = `<rect class="artifact-shape" x="${box[0]}" y="${box[1]}" width="${box[2] - box[0]}" height="${box[3] - box[1]}" fill="${fill}" stroke="${color}" stroke-width="1.6"${dash} pointer-events="all"/>`;
    } else continue;
    const classes = `artifact-item${state.ghost ? " ghost" : ""}${selected ? " sel" : ""}${state.noDownstream ? " no-downstream" : ""}`;
    const boxForTag = box;
    const badge = artifactBadge(record);
    const label = badge ? `<text class="artifact-badge" x="${boxForTag[0]}" y="${Math.max(12, boxForTag[1] - 3)}">${esc(badge.slice(0, 100))}</text>` : "";
    const textBadge = artifactTextBadge(record, boxForTag, width, height);
    const title = `${record.source?.model || "artifact"} · ${record.id} · ${record.lifecycle?.state || "kept"}` +
      (record.attrs?.route ? ` · ${record.attrs.route}` : "") +
      (record.lifecycle?.trace || []).filter((step) => step.kept === false && (step.rule || step.step))
        .map((step) => ` · ${step.rule || step.step} → ${step.winner_id || step.suppressed_by || (step.merged_with || []).join(",")} @ ${JSON.stringify(step.threshold ?? null)}`).join("") +
      (state.noDownstream ? " · not processed at this threshold — rerun stage to cover" : "");
    groups.push(`<g class="${classes}" data-artifact-id="${esc(record.id)}" data-region-id="${esc(record._regionId || record.attrs?.region_id || "")}"><title>${esc(title)}</title>${shape}${label}${textBadge}</g>`);
  }
  return `<svg class="artifact-svg" viewBox="0 0 ${width} ${height}" preserveAspectRatio="none" aria-label="Cached pipeline artifacts">
    <defs><pattern id="${id}" width="8" height="8" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><line x1="0" y1="0" x2="0" y2="8" stroke="#ffdf7e" stroke-width="2"/></pattern></defs>
    ${groups.join("")}
  </svg>`;
}

function attachArtifactClick(svg, page) {
  svg?.addEventListener("click", (event) => {
    const group = event.target.closest("[data-artifact-id]");
    if (!group) return;
    event.stopPropagation();
    selectArtifact(page, group.dataset.artifactId);
  });
}

function evaluateArtifactStates(page, records = S.artifactRecords.get(page) || []) {
  return new Map(records.map((record) => [record.id, evaluateArtifact(record, page)]));
}

function renderArtifactOverlays(page, evaluated = null, updateCounts = true) {
  const records = S.artifactRecords.get(page) || [];
  evaluated ||= evaluateArtifactStates(page, records);
  const mediaNodes = [secEl(page)?.querySelector(".pg-media")];
  if (S.focus === page) mediaNodes.push($("focusInner")?.querySelector(".pg-media"));
  [...new Set(mediaNodes.filter(Boolean))].forEach((media) => {
    if (!media._overlays) return;
    media._overlays.innerHTML = artifactSvgMarkup(page, records, evaluated);
    attachArtifactClick(media._overlays.querySelector(".artifact-svg"), page);
    media._hasOverlays = true;
  });
  if (S.selection?.page === page) markSelection();
  if (updateCounts && page === S.current) updateFilterCounts(page, evaluated);
}

function pageMediaVisible(page) {
  const mediaNodes = [secEl(page)?.querySelector(".pg-media")];
  if (S.focus === page) mediaNodes.push($("focusInner")?.querySelector(".pg-media"));
  return mediaNodes.some((media) => {
    if (!media) return false;
    const rect = media.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0 && rect.bottom > 0 && rect.right > 0
      && rect.top < window.innerHeight && rect.left < window.innerWidth;
  });
}

function applyArtifactFilters() {
  let currentEvaluation = null;
  for (const p of S.pages) {
    const media = secEl(p)?.querySelector(".pg-media");
    const focusMedia = S.focus === p ? $("focusInner")?.querySelector(".pg-media") : null;
    if (!(media?._overlays || focusMedia?._overlays) || !S.artifactRecords.has(p)) continue;
    if (p === S.current || p === S.focus || pageMediaVisible(p)) {
      const evaluated = evaluateArtifactStates(p);
      renderArtifactOverlays(p, evaluated, false);
      if (p === S.current) currentEvaluation = evaluated;
    } else {
      if (media?._overlays) media._hasOverlays = false;
      if (focusMedia?._overlays) focusMedia._hasOverlays = false;
    }
  }
  if (S.current) updateFilterCounts(S.current, currentEvaluation);
}

function setFilterPreset(preset) {
  S.filters = presetFilterState(preset);
  renderFilterControls(S.current);
  applyArtifactFilters();
  persistFilterState();
}

function markFilterCustom() {
  const select = $("filterPreset");
  if (S.filters.preset !== "custom") {
    S.filters.preset = "custom";
    if (select && !select.querySelector('option[value="custom"]')) {
      select.insertAdjacentHTML("beforeend", '<option value="custom">Custom filters</option>');
    }
    if (select) select.value = "custom";
  }
}

function persistFilterState() {
  clearTimeout(S.filterSaveTimer);
  const chapter = S.chapter;
  const filters = JSON.parse(JSON.stringify(S.filters || filterDefaults()));
  S.filterSaveTimer = setTimeout(async () => {
    if (!chapter || S.chapter !== chapter) return;
    try {
      await api("/api/settings", { filters });
      if (S.chapter === chapter) S.settings.filters = filters;
    } catch (error) { logLine(`!! filter settings save failed: ${error.message}`, "err"); }
  }, 350);
}

function onFilterControlChange(event) {
  const target = event.target;
  if (target.id === "filterPreset") { setFilterPreset(target.value); return; }
  const f = ensureFilterState();
  if (target.dataset.filterModel) {
    f.models[target.dataset.filterModel].enabled = target.checked;
  } else if (target.dataset.filterDim) {
    f.models[target.dataset.filterDim].dimFiltered = target.checked;
  } else if (target.dataset.filterLabelModel) {
    const id = target.dataset.filterLabelModel;
    if (!Array.isArray(f.models[id].labels)) f.models[id].labels = (FILTER_LABELS[id] || []).map(([v]) => v);
    const value = (target.type === "checkbox" && /^\d+$/.test(target.value)) ? Number(target.value) : target.value;
    f.models[id].labels = target.checked
      ? [...new Set([...f.models[id].labels, value])]
      : f.models[id].labels.filter((item) => String(item) !== String(value));
  } else if (target.dataset.filterScoreMin) {
    const id = target.dataset.filterScoreMin;
    f.models[id].scoreMin = Math.min(Number(target.value), f.models[id].scoreMax);
    target.value = f.models[id].scoreMin;
  } else if (target.dataset.filterScoreMax) {
    const id = target.dataset.filterScoreMax;
    f.models[id].scoreMax = Math.max(Number(target.value), f.models[id].scoreMin);
    target.value = f.models[id].scoreMax;
  } else if (target.dataset.filterState) {
    f.states = target.checked ? [...new Set([...f.states, target.dataset.filterState])]
      : f.states.filter((state) => state !== target.dataset.filterState);
  } else if (target.id === "filterSuppression") f.suppressionRule = target.value;
  else if (target.id === "filterRoute") f.route = target.value;
  else if (target.id === "filterWindow") f.window = target.value;
  else if (target.closest("#filterTranslations")) {
    f.translation = [...$("filterTranslations").querySelectorAll("input:checked")].map((input) => input.value);
  } else if (["filterWidthMin", "filterWidthMax", "filterHeightMin", "filterHeightMax", "filterAspectMin", "filterAspectMax", "filterTall"].includes(target.id)) {
    const key = { filterWidthMin: "widthMin", filterWidthMax: "widthMax", filterHeightMin: "heightMin", filterHeightMax: "heightMax", filterAspectMin: "aspectMin", filterAspectMax: "aspectMax", filterTall: "tall" }[target.id];
    f.geometry[key] = target.value;
  } else if (["filterOcrText", "filterOcrEngine", "filterOcrOutcome", "filterOcrConfidenceMin", "filterOcrConfidenceMax"].includes(target.id)) {
    const key = { filterOcrText: "text", filterOcrEngine: "engine", filterOcrOutcome: "outcome", filterOcrConfidenceMin: "confidenceMin", filterOcrConfidenceMax: "confidenceMax" }[target.id];
    f.ocr[key] = key.startsWith("confidence") ? Number(target.value) : target.value;
    if (f.ocr.confidenceMin > f.ocr.confidenceMax) {
      if (key === "confidenceMin") f.ocr.confidenceMax = f.ocr.confidenceMin;
      else f.ocr.confidenceMin = f.ocr.confidenceMax;
      $("filterOcrConfidenceMin").value = f.ocr.confidenceMin;
      $("filterOcrConfidenceMax").value = f.ocr.confidenceMax;
    }
  }
  markFilterCustom();
  document.querySelectorAll("[data-score-label]").forEach((node) => {
    const model = f.models[node.dataset.scoreLabel];
    if (model) node.textContent = `${model.scoreMin.toFixed(2)} – ${model.scoreMax.toFixed(2)}`;
  });
  applyArtifactFilters();
  persistFilterState();
}

function initArtifactFilters() {
  ensureFilterState();
  const card = $("artifactFilterCard");
  card?.addEventListener("change", onFilterControlChange);
  card?.addEventListener("input", (event) => {
    if (event.target.matches('input[type="range"], input[type="search"], input[type="number"]')) onFilterControlChange(event);
  });
  $("btnFilterExport")?.addEventListener("click", exportFilteredView);
  if (S.current) renderFilterControls(S.current);
}

async function exportFilteredView() {
  const page = S.current;
  if (!page) return;
  const records = (S.artifactRecords.get(page) || []).filter((record) => matchesArtifactFilters(record, page))
    .map((record) => Object.fromEntries(Object.entries(record).filter(([key]) => !key.startsWith("_"))));
  const status = $("filterExportStatus");
  if (status) status.textContent = "Writing export…";
  try {
    const result = await api("/api/export-filtered", {
      page, preset: S.filters?.preset || "custom", filters: S.filters, records,
    });
    if (status) status.textContent = `Saved ${result.record_count} records to ${result.path}`;
  } catch (error) {
    if (status) status.textContent = `Export failed: ${error.message}`;
    logLine(`!! filtered artifact export failed: ${error.message}`, "err");
  }
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

/* =========================================================== inspector */
function selectRegion(page, id) {
  const record = (S.artifactRecords.get(page) || []).find((r) => r._regionId === id && r._group === "ocr");
  selectArtifact(page, record?.id || id, id);
}

function selectArtifact(page, id, regionId = null) {
  S.selection = { page, id, regionId };
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
    badge.textContent = hasSel ? String(S.selection.id).slice(0, 12) : "0";
    badge.classList.toggle("hidden", !hasSel);
  }
}

function markSelection() {
  document.querySelectorAll(".artifact-item.sel").forEach((b) => b.classList.remove("sel"));
  if (!S.selection) return;
  document.querySelectorAll(".artifact-item[data-artifact-id]").forEach((item) => {
    if (item.closest(".pg")?.dataset.page === S.selection.page || (S.focus === S.selection.page && item.closest("#focusInner"))) {
      item.classList.toggle("sel", item.dataset.artifactId === S.selection.id);
    }
  });
}

async function openInspector() {
  const selection = S.selection;
  if (!selection) return;
  const { page, id } = selection;
  const [d, payload] = await Promise.all([ensurePageData(page), ensureArtifactData(page)]);
  if (!d || S.selection?.id !== id) return;
  const records = S.artifactRecords.get(page) || [];
  const record = records.find((x) => x.id === id);
  const regionId = selection.regionId || record?._regionId || record?.attrs?.region_id || id;
  const r = (d.regions || []).find((x) => String(x.id) === String(regionId))
    || payload?.sources?.ocr?.data?.regions?.find((x) => String(x.id) === String(regionId));
  if (!record && !r) return;
  const noDownstream = record && isDisplayNoDownstream(record, page);
  const q = `p=${enc(page)}&id=${enc(regionId)}`;
  const trText = String(d.translations?.[regionId] || record?.attrs?.translation || "").trim();
  const bs = Math.max(1, parseInt(S.settings.max_batch) || 8);
  const idx = r ? (d.regions || []).indexOf(r) : -1;
  const mb = idx < 0 ? null : Math.floor(idx / bs);
  const winsize = idx < 0 ? 0 : Math.min(bs, d.regions.length - mb * bs);
  const kv = (k, v, cls) =>
    `<div class="insp-kv"><span class="k">${k}</span><span class="v ${cls || ""}">${v}</span></div>`;
  const recordJson = JSON.stringify(Object.fromEntries(Object.entries(record || {}).filter(([key]) => !key.startsWith("_"))), null, 2);
  const payloadRoute = (payload?.sources?.inpaint?.data?.regions || []).find((item) => String(item.id) === String(regionId));
  const inpaintRecord = record?._inpaintRecord || payloadRoute;
  const cropBox = artifactBox(record) || (r ? boxGeometry(r.box) : null);
  const pageCrop = (label, route, box) => {
    if (!route || !box) return `<div class="artifact-crop-card"><span>${label}</span><div class="artifact-source-note">Unavailable in current cache.</div></div>`;
    const [pw, ph] = S.dims[page] || [1, 1];
    const bw = Math.max(1, box[2] - box[0]), bh = Math.max(1, box[3] - box[1]);
    const scale = Math.min(150 / bw, 112 / bh);
    const style = `background-image:url('${route}');background-size:${pw * scale}px ${ph * scale}px;background-position:-${box[0] * scale}px -${box[1] * scale}px`;
    return `<div class="inpaint-crop-card"><span>${label}</span><div class="inpaint-crop-frame" style="${style}" role="img" aria-label="${label} crop"></div></div>`;
  };
  const assets = payload?.assets || {};
  const [pw, ph] = S.dims[page] || [1, 1];
  const cropSource = boxGeometry(inpaintRecord?.source_boxes?.find((b) => b.role === "ocr-origin")?.box || r?.box);
  const cropDisplayBox = cropSource || cropBox;
  const originalRoute = cropDisplayBox ? `/img/original?p=${enc(page)}` : "";
  const outputRoute = assets.inpaint_image && cropDisplayBox ? `/img/inpainted?p=${enc(page)}` : "";
  const maskRoute = assets.inpaint_mask && cropDisplayBox ? `/img/inpaint_mask?p=${enc(page)}` : "";
  const inpaintDetails = inpaintRecord ? `
    ${kv("route", esc(inpaintRecord.route_taken || "—"))}
    ${kv("artifact id", esc(inpaintRecord.artifact_id || "—"))}
    ${kv("segmenter mask", esc(inpaintRecord.mask_resolution?.mask_ref || inpaintRecord._segmenter_assignment?.mask_ref || record?.attrs?.mask_ref || "—"))}
    ${kv("segmenter component", esc(inpaintRecord.segmenter_component_id ?? "—"))}
    ${kv("erase-mask component", esc(inpaintRecord.erase_mask_component_id ?? inpaintRecord.mask_component_id ?? "—"))}
    ${kv("context crop", esc(JSON.stringify(inpaintRecord.context_crop_bbox || null)))}
    ${kv("timing (ms)", `<span class="mono">${esc(JSON.stringify(inpaintRecord.timing_ms || {}))}</span>`)}
    ${kv("timing scope", esc(inpaintRecord.timing_scope || "—"))}
    <details class="insp-sec"><summary>Source boxes</summary><pre class="artifact-json">${esc(JSON.stringify(inpaintRecord.source_boxes || [], null, 2))}</pre></details>` : "";

  $("inspBody").innerHTML = `
    <div class="artifact-inspector-tabs">
      <button type="button" class="active" data-inspector-tab="artifact">Artifact</button>
      <button type="button" data-inspector-tab="inpaint">Inpaint</button>
    </div>
    <section data-inspector-pane="artifact">
      ${r ? `<div class="insp-crop-label">OCR input crop</div>
        <img class="insp-crop" src="/img/crop?${q}">
        <div class="insp-crop-label">Detected text</div>
        <div class="insp-text">${esc(r.text || "(empty)")}</div>
        <div class="insp-crop-label">Translation <span class="muted">(auto-saved, re-renders)</span></div>
        <textarea id="trText" placeholder="type translation…">${esc(trText)}</textarea>
        <div class="insp-crop-label">Rendered on page</div>
        ${trText ? `<img class="insp-crop" id="renderedCrop" src="/img/render_crop?${q}">` : `<div class="muted" style="padding:4px 2px">no translation — region left as-is in render</div>`}
        <details class="insp-sec"><summary>OCR details</summary>
          ${kv("confidence", r.confidence ?? "—")}${kv("engine", esc(r.engine || payload?.sources?.ocr?.data?.engine || "—"))}
          ${kv("outcome", r.error ? "error" : (r.position_limit ? "POSITION LIMIT" : r.eos ? `EOS @ ${r.eos_position}` : "ok"), r.error || r.position_limit ? "bad" : "good")}
          ${kv("raw text", esc(r.raw_text || "—"))}
          <img class="insp-img" src="/img/ocr_input?${q}" title="exact normalized OCR input"></details>` : ""}
      ${record ? `<div class="insp-crop-label">Full artifact record</div><pre class="artifact-json">${esc(recordJson)}</pre>` : `<div class="artifact-source-note">No artifact record is available in the current cache.</div>`}
      ${r && mb !== null ? `<details class="insp-sec"><summary>OCR batch</summary>
        ${kv("microbatch", `${mb + 1} of ${Math.ceil(d.regions.length / bs)} (region ${idx + 1} of ${d.regions.length})`)}
        ${kv("window size", winsize)}${kv("max batch setting", bs)}
        ${kv("encoder runs", d.runs?.encoder ?? "—")}${kv("decoder init runs", d.runs?.decoder_init ?? "—")}${kv("decoder step runs", d.runs?.decoder_step ?? "—")}
      </details>` : ""}
      ${noDownstream ? `<div class="artifact-source-note">Not processed at this threshold — rerun stage to cover. Score ${esc(artifactScore(record))}; cached execution threshold ${esc(currentExecutionConfidence(page))}.</div>` : ""}
    </section>
    <section data-inspector-pane="inpaint" class="hidden">
      ${inpaintDetails || `<div class="artifact-source-note">Per-region inpaint provenance is unavailable: .studio/inpaint/${esc(String(page).split(/[\\/]/).pop().replace(/\.[^.]+$/, ""))}.json has not been emitted. This inspector reads the existing A4 provenance file and does not run inpainting.</div>`}
      ${cropDisplayBox ? `<div class="inpaint-crops">
        ${pageCrop("Input region", originalRoute, cropDisplayBox)}
        ${pageCrop("Inpainted output", outputRoute, cropDisplayBox)}
        ${pageCrop("Erase mask", maskRoute, cropDisplayBox)}
      </div>` : ""}
      ${!assets.inpaint_image || !assets.inpaint_mask ? `<div class="artifact-source-note">Per-region B1 crop files are not present; page-level inpaint and mask crops are shown only when their saved images exist.</div>` : ""}
      ${inpaintRecord ? `<details class="insp-sec"><summary>Full A4 provenance record</summary><pre class="artifact-json">${esc(JSON.stringify(inpaintRecord, null, 2))}</pre></details>` : ""}
    </section>`;
  $("inspBody").querySelectorAll("[data-inspector-tab]").forEach((button) => {
    button.addEventListener("click", () => {
      $("inspBody").querySelectorAll("[data-inspector-tab]").forEach((item) => item.classList.toggle("active", item === button));
      $("inspBody").querySelectorAll("[data-inspector-pane]").forEach((pane) => pane.classList.toggle("hidden", pane.dataset.inspectorPane !== button.dataset.inspectorTab));
    });
  });
  if (r) wireTranslationEditor(page, regionId);
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
        const records = S.artifactRecords.get(page) || [];
        const translated = String(ta.value).trim();
        records.forEach((record) => {
          if (String(record._regionId || record.attrs?.region_id || "") !== String(id)) return;
          record.attrs.translation = ta.value;
          record.attrs.translation_cached = true;
          record.attrs.translation_status = translated ? "translated" : "cached";
        });
        applyArtifactFilters();
        refreshRenderedImages(page);
        refreshOverview();
      } catch (e) {
        logLine("!! save failed: " + e.message, "err");
      }
    }, 450);
  });
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
  applyArtifactFilters();
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
  $("setMangaOcrSerial").checked = Boolean(s.mangaocr_serial_timing);
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
    mangaocr_serial_timing: $("setMangaOcrSerial").checked,
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
  initArtifactFilters();
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
