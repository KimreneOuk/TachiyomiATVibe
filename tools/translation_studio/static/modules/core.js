/* Load-order module: core.js. Shared classic-script scope, no build step. */
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
  activeStage: null,
  stageDispatches: { chapter: null, pages: {} },
  stageTimes: { chapter: null, pages: {} },
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
