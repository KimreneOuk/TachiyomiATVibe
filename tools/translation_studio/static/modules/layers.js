/* Load-order module: layers.js. Shared classic-script scope, no build step. */
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
  const translationKeys = new Set(Object.keys(translationData || {})
    .filter((key) => !key.startsWith("_")));
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
    const stableId = String(region.stable_id || region.id);
    const translationRecord = translationData?.[stableId];
    const translation = translationText(stableId);
    const cachedTranslation = translationKeys.has(stableId);
    const translationOrigin = typeof translationRecord === "object"
      ? String(translationRecord?.origin || "legacy") : "legacy";
    const detector = byArtifactId.get(region.artifact_id);
    const window = detector?.source?.window ?? null;
    const commonAttrs = {
      label: region.label, score: region.score, confidence: region.confidence,
      text, engine, outcome: out, region_id: region.id,
      artifact_id: region.artifact_id,
      translation, translation_cached: cachedTranslation,
      translation_id: stableId, translation_origin: translationOrigin,
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

  const prunedTranslations = Array.isArray(translationData?._pruned)
    ? translationData._pruned : [];
  prunedTranslations.forEach((entry, index) => {
    const snapshot = entry?.region || {};
    const box = boxGeometry(snapshot.box);
    const score = Number(snapshot.score);
    const recordId = `translation-pruned-${index}-${String(entry?.stable_id || "unknown")}`;
    add({ id: recordId, page, kind: "region", _group: "ocr",
      _regionId: snapshot.id || entry?.stable_id || recordId,
      source: { model: "ocr:translation-cache", asset: "translations.json",
        asset_sha: null, window: null },
      geometry: box || { x1: 0, y1: 0, x2: 0, y2: 0 },
      attrs: {
        label: snapshot.label ?? 1,
        score: Number.isFinite(score) ? score : null,
        text: String(snapshot.text || ""),
        translation: String(entry?.text || ""),
        translation_cached: false,
        translation_origin: String(entry?.origin || "legacy"),
        translation_status: "pruned", translation_id: entry?.stable_id || "",
        prune_reason: String(entry?.reason || "unmapped-region"),
        shape: box ? boxShape(snapshot.box) : { w: 0, h: 0, ar: null },
      },
      lifecycle: { state: "pruned", trace: [{ step: "translation-prune",
        kept: false, reason: String(entry?.reason || "unmapped-region") }] },
    });
  });

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
    const hasPrunedTranslations = Array.isArray(src.translations?.data?._pruned)
      && src.translations.data._pruned.length > 0;
    if (src.ocr?.status !== "ready" && !hasPrunedTranslations) {
      return { ok: false, reason: src.ocr?.reason || "missing source: .studio/ocr.json" };
    }
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
  const activeTranslationEntries = Object.entries(translationData || {})
    .filter(([key]) => !key.startsWith("_"));
  const prunedTranslations = Array.isArray(translationData?._pruned)
    ? translationData._pruned : [];
  const hasUserEdited = activeTranslationEntries.some(([, value]) =>
    value && typeof value === "object" && value.origin === "user");
  const hasTranslatedText = activeTranslationEntries.some(([, value]) =>
    String(typeof value === "string" ? value : value?.text || "").trim());
  const translationReady = payload.sources?.translations?.status === "ready"
    && translationData && typeof translationData === "object"
    && (activeTranslationEntries.length > 0 || prunedTranslations.length > 0);
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
      consumed: inpaintReady, pruned: prunedTranslations.length > 0,
    };
    const reasons = {
      raw: detectionReady ? "" : sourceMissingReason(payload, "detections", ".studio/detections.json (capture_version 1 lifecycle)"),
      kept: detectionReady ? "" : sourceMissingReason(payload, "detections", ".studio/detections.json (capture_version 1 lifecycle)"),
      suppressed: detectionReady ? "" : sourceMissingReason(payload, "detections", ".studio/detections.json suppression_records"),
      merged: detectionReady ? "" : sourceMissingReason(payload, "detections", ".studio/detections.json merge_records"),
      context: detectionReady ? "" : sourceMissingReason(payload, "detections", ".studio/detections.json context_ids"),
      blank: ocrReady ? "" : sourceMissingReason(payload, "ocr", ".studio/ocr.json regions"),
      consumed: inpaintReady ? "" : sourceMissingReason(payload, "inpaint", ".studio/inpaint/<page>.json"),
      pruned: prunedTranslations.length
        ? `${prunedTranslations.length} translation record(s) were pruned and retained for audit.`
        : "No pruned translation records are present in this cache.",
    };
    stateRoot.innerHTML = FILTER_STATES.map((state) => `<label title="${esc(reasons[state] || "Lifecycle state from cached records")}"><input type="checkbox" data-filter-state="${state}" ${filters.states.includes(state) ? "checked" : ""} ${supports[state] ? "" : "disabled"}>${FILTER_STATE_LABELS[state]}</label>`).join("");
    if (reasons.pruned) stateRoot.querySelector('[data-filter-state="pruned"]')?.setAttribute("title", reasons.pruned);
    if (!translationReady) $("filterTranslationNote").textContent = sourceMissingReason(payload, "translations", ".studio/translations.json");
    else $("filterTranslationNote").textContent = `${hasUserEdited ? "User edits are recorded" : "No user edits recorded"}; ${prunedTranslations.length} pruned translation(s) retained for audit.`;
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
    const available = input.value === "cached" ? activeTranslationEntries.length > 0
      : (input.value === "translated" ? hasTranslatedText
        : (input.value === "user-edited" ? hasUserEdited : prunedTranslations.length > 0));
    input.disabled = !available;
    input.title = available ? `Filter by ${input.value} translation records`
      : (translationReady ? `No ${input.value} translation records in this cache`
        : sourceMissingReason(payload, "translations", ".studio/translations.json"));
    input.checked = filters.translation.includes(input.value);
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
  const prunedTranslation = group === "ocr" && record.attrs?.translation_status === "pruned";
  if (f.preset === "production" && group === "text-detector" && record._phase === "raw-output") return false;
  const label = artifactLabel(record);
  if (!prunedTranslation && Array.isArray(groupState.labels)
      && !groupState.labels.map(String).includes(String(label))) return false;

  const score = artifactScore(record);
  if (!prunedTranslation && score !== null
      && (score < groupState.scoreMin || score > groupState.scoreMax)) return false;
  if (!prunedTranslation && score === null
      && (groupState.scoreMin > 0 || groupState.scoreMax < 1)) return false;

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
    if (attrs.translation_origin === "user") states.push("user-edited");
    if (attrs.translation_status === "pruned") states.push("pruned");
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

function badgeRectCollides(rect, placed) {
  return placed.some((other) => rect.left < other.right + 2 && rect.right + 2 > other.left
    && rect.top < other.bottom + 2 && rect.bottom + 2 > other.top);
}

function artifactBadgeMarkup(badge, box, pageWidth, pageHeight, placed) {
  const fullText = String(badge || "");
  if (!fullText || !box) return "";
  const charWidth = 7.2;
  const maxWidth = Math.max(1, pageWidth - 4);
  const maxChars = Math.max(1, Math.floor(maxWidth / charWidth));
  const visibleText = fullText.length > maxChars
    ? `${fullText.slice(0, Math.max(0, maxChars - 1))}…` : fullText;
  const labelWidth = Math.min(maxWidth, Math.max(1, visibleText.length * charWidth));
  const labelHeight = 14;
  const minX = 2;
  const maxX = Math.max(minX, pageWidth - labelWidth - 2);
  const anchorX = Math.max(minX, Math.min(maxX, box[0]));
  const xCandidates = [...new Set([
    anchorX,
    Math.max(minX, Math.min(maxX, box[0] - labelWidth - 4)),
    Math.max(minX, Math.min(maxX, box[2] + 4)),
    minX,
    maxX,
  ])];
  const preferredY = Math.max(12, Math.min(pageHeight - 2, box[1] - 3));
  const yCandidates = [];
  const yStep = labelHeight + 2;
  for (let offset = 0; offset <= pageHeight; offset += yStep) {
    if (preferredY + offset <= pageHeight - 2) yCandidates.push(preferredY + offset);
    if (offset && preferredY - offset >= 12) yCandidates.push(preferredY - offset);
  }
  if (!yCandidates.length) yCandidates.push(12);

  let placement = null;
  for (const y of yCandidates) {
    for (const x of xCandidates) {
      const rect = { left: x - 1, right: x + labelWidth + 1, top: y - 11, bottom: y + 3 };
      if (!badgeRectCollides(rect, placed)) {
        placement = { x, y, rect };
        break;
      }
    }
    if (placement) break;
  }
  if (!placement) return "";
  placed.push(placement.rect);
  return `<text class="artifact-badge" x="${placement.x}" y="${placement.y}"><title>${esc(fullText)}</title>${esc(visibleText)}</text>`;
}

function artifactTextBadge(record, box, pageWidth, pageHeight, placed = []) {
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
  const minX = 1;
  const maxX = Math.max(minX, pageWidth - badgeWidth - 1);
  const preferredX = Math.max(minX, Math.min(maxX, box[0]));
  const xCandidates = [...new Set([
    preferredX,
    Math.max(minX, Math.min(maxX, box[0] - badgeWidth - 4)),
    Math.max(minX, Math.min(maxX, box[2] + 4)),
    minX,
    maxX,
  ])];
  let preferredTop = box[3] + 3;
  if (preferredTop + badgeHeight > pageHeight) preferredTop = Math.max(0, box[1] - badgeHeight - 3);
  const yCandidates = [];
  const yStep = badgeHeight + 3;
  for (let offset = 0; offset <= pageHeight; offset += yStep) {
    if (preferredTop + offset + badgeHeight <= pageHeight) yCandidates.push(preferredTop + offset);
    if (offset && preferredTop - offset >= 0) yCandidates.push(preferredTop - offset);
  }
  let placement = null;
  for (const top of yCandidates) {
    for (const x of xCandidates) {
      const rect = { left: x - 2, right: x + badgeWidth + 2, top: top - 2, bottom: top + badgeHeight + 2 };
      if (!badgeRectCollides(rect, placed)) {
        placement = { x, top, rect };
        break;
      }
    }
    if (placement) break;
  }
  if (!placement) return "";
  placed.push(placement.rect);
  const tspans = labels.map((line, index) => `<tspan x="${placement.x + fontSize * 0.5}" y="${placement.top + fontSize + index * lineHeight}">${esc(line)}</tspan>`).join("");
  return `<g class="artifact-text-badge"><title>${esc(fullText)}</title><rect x="${placement.x}" y="${placement.top}" width="${badgeWidth}" height="${badgeHeight}" rx="3"/><text font-size="${fontSize}">${tspans}</text></g>`;
}

function artifactSvgMarkup(page, records, evaluated = null) {
  const [width, height] = S.dims[page] || [1, 1];
  const id = `artifact-hatch-${++artifactSvgCounter}`;
  const groups = [];
  const placedBadges = [];
  const visibleRecords = records.map((record) => ({
    record,
    state: evaluated?.get(record.id) || evaluateArtifact(record, page),
    box: record._maskBounds || artifactBox(record),
  })).filter((item) => item.state.show);
  const textBadges = new Map();
  for (const { record, box } of visibleRecords) {
    const markup = artifactTextBadge(record, box, width, height, placedBadges);
    if (markup) textBadges.set(record.id, markup);
  }
  for (const { record, state, box } of visibleRecords) {
    const group = record._group || modelGroupFor(record.source?.model);
    const color = MODEL_COLORS[group] || "#e5e7eb";
    const selected = S.selection?.page === page && S.selection?.id === record.id;
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
    const label = artifactBadgeMarkup(badge, boxForTag, width, height, placedBadges);
    const textBadge = textBadges.get(record.id) || "";
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
