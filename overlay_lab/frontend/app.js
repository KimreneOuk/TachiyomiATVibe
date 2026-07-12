// Manga Render Lab — spec layer-based frontend
// Sidebar (left) | workspace canvas (center) | info panel (right)
// Layers drawn stacked on one canvas, gated by sidebar toggles.
// Compare mode: original-vs-baked split slider.

const state = {
  // images
  originalImage: null,
  cleanedImage: null,     // cleaned PNG from /api/inpaint (text erased, no translation)
  layouts: null,          // layout JSON from /api/render/overlay for live Canvas2D text
  // structured data from /api/process
  layers: null,           // {detector, segmentation, panel}
  ocrData: [],            // [{id, box, raw_text, translated_text}]
  imageDims: null,        // {width, height}
  warnings: [],
  // view
  view: 'original',       // 'original' | 'detected' | 'translated'
  // view pan/zoom
  zoom: 1,
  panX: 0, panY: 0,
  isDragging: false,
  dragStart: null,
  // overlay text scale (live, applied to Canvas2D text in translated view)
  fontScale: 1.0,
  strokeScale: 1.0,
  // mask re-bake debounce
  maskParamsTimer: null,
  // ocr editor
  editingOcrId: null,
  // LLM translation config (persisted in localStorage)
  llmConfig: null,
};

const $ = (id) => document.getElementById(id);
const canvas = $('mainCanvas');
const ctx = canvas.getContext('2d');

// ── Canvas sizing (DPR-aware — sharp on high-DPI/retina screens) ─────

const DPR = window.devicePixelRatio || 1;

function resizeCanvas() {
  const rect = $('canvasContainer').getBoundingClientRect();
  // Backing store = physical pixels (CSS size × DPR), so the canvas
  // renders at full screen resolution instead of being upscaled (blurry).
  canvas.width = Math.round(rect.width * DPR);
  canvas.height = Math.round(rect.height * DPR);
  canvas.style.width = rect.width + 'px';
  canvas.style.height = rect.height + 'px';
  // Draw in CSS-pixel coordinate space.
  ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
  draw();
}
window.addEventListener('resize', resizeCanvas);

// ── Loading + status ─────────────────────────────────────────────────

function showLoading(msg) {
  $('loadingText').textContent = msg;
  $('loadingOverlay').classList.remove('hidden');
}
function hideLoading() { $('loadingOverlay').classList.add('hidden'); }
function setStatus(msg, kind = '') {
  const el = $('statusDisplay');
  el.textContent = msg;
  el.className = 'status-msg ' + kind;
}

// ── Page select ──────────────────────────────────────────────────────

async function loadSample(name) {
  state.originalImage = null;
  state.cleanedImage = null;
  state.layouts = null;
  state.layers = null;
  state.ocrData = [];
  state.warnings = [];
  state.imageDims = null;
  $('btnBake').disabled = true;
  $('btnReBake').disabled = true;
  $('btnTranslate').disabled = true;
  hidePanels();
  setStatus('Loading page...');
  const img = new Image();
  img.onload = () => {
    state.originalImage = img;
    state.imageDims = { width: img.naturalWidth, height: img.naturalHeight };
    setStatus(`Loaded ${name} (${img.naturalWidth}×${img.naturalHeight}). Click ▶ Process.`);
    draw();
  };
  img.onerror = () => setStatus(`Failed to load ${name}`, 'error');
  img.src = `/api/sample/${encodeURIComponent(name)}`;
}

$('sampleSelect').addEventListener('change', (e) => {
  if (e.target.value) loadSample(e.target.value);
});

// ── Process (/api/process) ───────────────────────────────────────────

async function runProcess() {
  if (!state.originalImage) return;
  showLoading('Running detector + segmentation + panel + OCR...\n(first run loads models, ~20-40s)');
  setStatus('Processing...');

  const blob = await fetch(state.originalImage.src).then(r => r.blob());
  const fd = new FormData();
  fd.append('file', blob, 'page.png');

  try {
    const res = await fetch('/api/process', { method: 'POST', body: fd });
    if (!res.ok) throw new Error(`Process failed: ${res.status}`);
    const data = await res.json();
    state.layers = data.layers;
    state.ocrData = data.ocr_data || [];
    state.imageDims = data.image_dimensions;
    state.warnings = data.warnings || [];
    $('btnBake').disabled = false;
    $('btnReBake').disabled = !state.cleanedImage;
    $('btnTranslate').disabled = state.ocrData.length === 0;

    // Status summary
    const det = data.layers.detector;
    const seg = data.layers.segmentation;
    const pan = data.layers.panel;
    const parts = [];
    if (det) parts.push(`det:${det.bubbles.length + det.bubble_text.length + det.free_text.length}`);
    if (seg) parts.push(`seg:${seg.bubble_masks.length}`);
    if (pan) parts.push(`panel:${pan.frames.length}`);
    setStatus(`Processed: ${parts.join(' ') || 'no layers'}. Toggle layers or click Bake.`, 'success');
    hideLoading();
    renderInfoPanel();
    draw();
  } catch (e) {
    hideLoading();
    setStatus(`Process failed: ${e.message}`, 'error');
  }
}

$('btnProcess').addEventListener('click', runProcess);

// ── Generate Final Translation (overlay path: /api/inpaint + /api/render/overlay) ──

async function runTranslate() {
  if (!state.originalImage) return;

  // Forcefully translate everything using the selected LLM before inpainting
  if (state.ocrData.length > 0) {
    await runTranslateLLM();
  }

  showLoading('Inpainting (erasing source text)...');
  setStatus('Inpainting...');

  const blob = await fetch(state.originalImage.src).then(r => r.blob());
  // Build blocks from ocrData. Preserve each block's label so label-2 free-text
  // reaches the free-text erase path (was hardcoded to 1).
  const blocks = state.ocrData.map(o => ({
    bbox: { x1: o.box[0], y1: o.box[1], x2: o.box[2], y2: o.box[3] },
    text: o.raw_text,
    translation: o.translated_text,
    label: o.label != null ? o.label : 1,
    score: 0.9,
    direction: 'LTR',
    parentW: o.box[2] - o.box[0],
    parentH: o.box[3] - o.box[1],
  }));
  const blocksJson = JSON.stringify(blocks);
  const maskParams = JSON.stringify(buildMaskParams());

  try {
    // 1. Inpaint: get the cleaned image (source text erased, NO translation text).
    const inpFd = new FormData();
    inpFd.append('file', blob, 'page.png');
    inpFd.append('blocks_json', blocksJson);
    inpFd.append('detections_json', JSON.stringify(
      blocks.map(b => ({ ...b.bbox, label: b.label, score: b.score }))
    ));
    inpFd.append('bubble_source', 'segmentation');
    inpFd.append('free_text_source', $('mpFreeStrategy').value || 'telea');
    inpFd.append('mask_params', maskParams);
    const inpRes = await fetch('/api/inpaint', { method: 'POST', body: inpFd });
    if (!inpRes.ok) throw new Error(`Inpaint failed: ${inpRes.status}`);
    const cleanedBlob = await inpRes.blob();

    // Load the cleaned image into state.
    await new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => { state.cleanedImage = img; resolve(); };
      img.onerror = reject;
      img.src = URL.createObjectURL(cleanedBlob);
    });

    // 2. Render overlay: get the layout JSON for live Canvas2D text drawing.
    //    Pass the CLEANED image so color estimation + measurement run on erased bg.
    showLoading('Planning text overlay...');
    const cleanBlob = await fetch(state.cleanedImage.src).then(r => r.blob());
    const ovFd = new FormData();
    ovFd.append('file', cleanBlob, 'cleaned.png');
    ovFd.append('blocks_json', blocksJson);
    if (state.layers && state.layers.segmentation && state.layers.segmentation.bubble_masks) {
      ovFd.append('masks_json', JSON.stringify(state.layers.segmentation.bubble_masks));
    }
    const ovRes = await fetch('/api/render/overlay', { method: 'POST', body: ovFd });
    if (!ovRes.ok) throw new Error(`Overlay plan failed: ${ovRes.status}`);
    const ovData = await ovRes.json();
    state.layouts = ovData.layouts || [];
    buildTextOverlayDOM();

    $('btnReBake').disabled = false;
    hideLoading();
    setStatus(`Translated view ready: ${state.layouts.length} text blocks overlaid.`, 'success');
    setView('translated');
  } catch (e) {
    hideLoading();
    setStatus(`Generate failed: ${e.message}`, 'error');
  }
}

$('btnBake').addEventListener('click', runTranslate);
$('btnReBake').addEventListener('click', runTranslate);

// ── View toggle: Original / Detected / Translated ────────────────────

function setView(v) {
  state.view = v;
  $('viewOriginal').classList.toggle('active', v === 'original');
  $('viewDetected').classList.toggle('active', v === 'detected');
  $('viewTranslated').classList.toggle('active', v === 'translated');
  if (v === 'translated' && !state.cleanedImage) {
    setStatus('No translated view yet. Click Generate Final Translation first.', 'error');
  }
  draw();
}
$('viewOriginal').addEventListener('click', () => setView('original'));
$('viewDetected').addEventListener('click', () => setView('detected'));
$('viewTranslated').addEventListener('click', () => setView('translated'));

// ── Layer toggles ────────────────────────────────────────────────────

['lyrDetector','lyrBubbles','lyrBubbleText','lyrFreeText','lyrFreeTextLines',
 'lyrSegmentation','lyrPanel','lyrFrames'
].forEach(id => $(id).addEventListener('change', draw));

// ── Drawing — the layer stack ────────────────────────────────────────

function draw() {
  drawView();
}

function drawView() {
  // Context has a DPR transform set, so draw in CSS-pixel space.
  const w = canvas.clientWidth, h = canvas.clientHeight;
  ctx.clearRect(0, 0, w, h);
  ctx.fillStyle = '#11121a';
  ctx.fillRect(0, 0, w, h);

  const img = state.originalImage;
  if (!img) {
    ctx.fillStyle = '#565f89';
    ctx.font = '14px monospace';
    ctx.textAlign = 'center';
    ctx.fillText('← Choose a page from the sidebar', w / 2, h / 2);
    return;
  }

  const scale = computeDrawScale(img, w, h) * state.zoom;
  const offset = computeDrawOffset(img, w, h, scale);

  // Base image (always the original — nothing is baked flat).
  ctx.imageSmoothingEnabled = state.zoom <= 2;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(img, offset.x, offset.y, img.naturalWidth * scale, img.naturalHeight * scale);

  if (state.view === 'original') return;

  if (state.view === 'detected') {
    // original + seg masks + detector boxes + panel frames
    if (state.layers) {
      if ($('lyrSegmentation').checked && state.layers.segmentation) {
        drawSegmentationMasks(ctx, state.layers.segmentation.bubble_masks, scale, offset);
      }
      if ($('lyrDetector').checked && state.layers.detector) {
        drawDetectorBoxes(ctx, state.layers.detector, scale, offset);
      }
      if ($('lyrPanel').checked && state.layers.panel) {
        drawPanelBoxes(ctx, state.layers.panel, scale, offset);
      }
    }
    return;
  }

  // view === 'translated': cleaned overlay (clipped to masks) + live DOM text
  if (state.cleanedImage) {
    drawCleanedOverlay(ctx, scale, offset);
  }
  
  const overlayLayer = $('textOverlayLayer');
  if (state.view === 'translated' && state.layouts && state.layouts.length) {
    overlayLayer.style.display = 'block';
    overlayLayer.style.transform = `translate(${offset.x}px, ${offset.y}px) scale(${scale})`;
    syncTextOverlayStyles(scale);
  } else {
    if (overlayLayer) overlayLayer.style.display = 'none';
  }
}

function drawCleanedOverlay(ctx, scale, offset) {
  // Draw the cleaned image ONLY inside the precise erase regions: segmentation
  // polygons (bubbles) + free-text boxes. The original shows through everywhere
  // else = the "precise masking". Achieved with ctx.clip() per region.
  const cleaned = state.cleanedImage;
  ctx.save();
  // Build a composite clip path over all erase regions, then draw the cleaned
  // image once (clipped). Canvas clip is intersection of the current path, so
  // we begin one path with all region subpaths.
  ctx.beginPath();
  let any = false;
  // Segmentation polygons (bubbles) — pixel-accurate.
  const seg = state.layers?.segmentation;
  if (seg && seg.bubble_masks) {
    for (const poly of seg.bubble_masks) {
      if (!poly || poly.length < 3) continue;
      ctx.moveTo(offset.x + poly[0][0] * scale, offset.y + poly[0][1] * scale);
      for (let i = 1; i < poly.length; i++) {
        ctx.lineTo(offset.x + poly[i][0] * scale, offset.y + poly[i][1] * scale);
      }
      ctx.closePath();
      any = true;
    }
  }
  // Free-text boxes — axis-aligned (no polygon from detector).
  const det = state.layers?.detector;
  if (det && det.free_text) {
    for (const box of det.free_text) {
      const [x1, y1, x2, y2] = box;
      ctx.rect(offset.x + x1 * scale, offset.y + y1 * scale,
               (x2 - x1) * scale, (y2 - y1) * scale);
      any = true;
    }
  }
  if (any) {
    ctx.clip();
    ctx.imageSmoothingEnabled = state.zoom <= 2;
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(cleaned, offset.x, offset.y,
                  cleaned.naturalWidth * scale, cleaned.naturalHeight * scale);
  }
  ctx.restore();
}

function buildTextOverlayDOM() {
  const layer = $('textOverlayLayer');
  if (!layer) return;
  layer.innerHTML = '';
  if (!state.layouts) return;

  const CJK_FONT = '"Anime Ace", "Noto Sans CJK JP", "Yu Gothic", "Meiryo", "MS Gothic", "Hiragino Sans", sans-serif';

  state.layouts.forEach(l => {
    if (!l.text) return;
    const div = document.createElement('div');
    div.className = 'text-block-overlay';
    div.textContent = l.text;
    
    const color = l.text_color === 0 ? 'white' : 'black';

    div.style.fontFamily = CJK_FONT;
    div.style.color = color;
    div.style.paintOrder = 'stroke fill';
    
    div.style.left = `${l.origin_x}px`;
    div.style.top = `${l.origin_y}px`;
    div.style.transform = `translate(-50%, -50%)`;
    
    // Natively constraint the DOM node to the safe area so text wraps correctly
    div.style.width = `${l.safe_w}px`;

    if (l.is_vertical) {
      div.style.writingMode = 'vertical-rl';
      div.style.height = `${l.safe_h}px`; // cap height to force column wrapping
      div.style.width = 'auto'; // allow columns to grow horizontally
      div.style.alignItems = 'center';
      div.style.justifyContent = 'center';
    } else {
      div.style.textAlign = l.draw_align || 'center';
      div.style.justifyContent = 'center';
      div.style.alignItems = 'center';
    }

    makeDraggable(div, l);
    layer.appendChild(div);
  });
}

function syncTextOverlayStyles(currentScale = 1.0) {
  const layer = $('textOverlayLayer');
  if (!layer || !state.layouts) return;
  const divs = layer.querySelectorAll('.text-block-overlay');
  let idx = 0;
  state.layouts.forEach(l => {
    if (!l.text) return;
    const div = divs[idx++];
    if (div) {
      const fs = (l.font_size_px || 16) * state.fontScale;
      const sw = Math.max(1, (l.stroke_width || 1) * state.strokeScale);
      div.style.fontSize = `${fs}px`;
      
      div.style.transform = `translate(-50%, -50%)`;
      
      const textColor = l.text_color === 0 ? 'white' : 'black';
      const outlineColor = l.text_color === 0 ? 'black' : 'white';
      div.style.color = textColor;
      
      if (sw > 0) {
        div.style.textShadow = `
          -${sw}px -${sw}px 0 ${outlineColor},
           ${sw}px -${sw}px 0 ${outlineColor},
          -${sw}px  ${sw}px 0 ${outlineColor},
           ${sw}px  ${sw}px 0 ${outlineColor},
           0px -${sw}px 0 ${outlineColor},
           0px  ${sw}px 0 ${outlineColor},
          -${sw}px  0px 0 ${outlineColor},
           ${sw}px  0px 0 ${outlineColor}
        `;
      } else {
        div.style.textShadow = 'none';
      }
    }
  });
}

function makeDraggable(el, layout) {
  let isDown = false;
  let startX = 0, startY = 0;
  let startLeft = 0, startTop = 0;

  el.addEventListener('pointerdown', e => {
    isDown = true;
    startX = e.clientX;
    startY = e.clientY;
    startLeft = parseFloat(el.style.left) || 0;
    startTop = parseFloat(el.style.top) || 0;
    // bring to front using z-index instead of appendChild to preserve DOM order
    document.querySelectorAll('.text-block-overlay').forEach(node => node.style.zIndex = '1');
    el.style.zIndex = '100';
    el.setPointerCapture(e.pointerId);
    el.style.cursor = 'grabbing';
    e.stopPropagation(); // prevent canvas pan
  });

  el.addEventListener('pointermove', e => {
    if (!isDown) return;
    const dx = e.clientX - startX;
    const dy = e.clientY - startY;
    const rect = $('mainCanvas').getBoundingClientRect();
    const currentScale = computeDrawScale(state.originalImage, rect.width, rect.height) * state.zoom;
    
    el.style.left = `${startLeft + dx / currentScale}px`;
    el.style.top = `${startTop + dy / currentScale}px`;
  });

  el.addEventListener('pointerup', e => {
    isDown = false;
    el.releasePointerCapture(e.pointerId);
  });
}

function drawSegmentationMasks(ctx, masks, scale, offset) {
  ctx.save();
  ctx.fillStyle = 'rgba(158, 206, 106, 0.35)';
  ctx.strokeStyle = 'rgba(158, 206, 106, 0.8)';
  ctx.lineWidth = 1.5;
  for (const poly of masks) {
    if (!poly || poly.length < 3) continue;
    ctx.beginPath();
    ctx.moveTo(offset.x + poly[0][0] * scale, offset.y + poly[0][1] * scale);
    for (let i = 1; i < poly.length; i++) {
      ctx.lineTo(offset.x + poly[i][0] * scale, offset.y + poly[i][1] * scale);
    }
    ctx.closePath();
    ctx.fill();
    ctx.stroke();
  }
  ctx.restore();
}

function drawDetectorBoxes(ctx, det, scale, offset) {
  // Spec: Detector Layer = Blue outlined boxes for all sub-types
  const groups = [
    { key: 'bubbles',    show: $('lyrBubbles').checked, stroke: '#7aa2f7', fill: 'rgba(122,162,247,0.10)' },
    { key: 'bubble_text',show: $('lyrBubbleText').checked, stroke: '#7aa2f7', fill: 'rgba(122,162,247,0.10)' },
    { key: 'free_text',  show: $('lyrFreeText').checked, stroke: '#7aa2f7', fill: 'rgba(122,162,247,0.10)' },
    { key: 'free_text_lines', show: $('lyrFreeTextLines').checked, stroke: '#ff9e64', fill: 'rgba(255,158,100,0.10)' }, // orange to stand out
  ];
  ctx.lineWidth = Math.max(2, scale * 2.5);

  for (const g of groups) {
    if (!g.show) continue;
    ctx.strokeStyle = g.stroke;
    ctx.fillStyle = g.fill;
    for (const box of (det[g.key] || [])) {
      const [x1, y1, x2, y2] = box;
      const x = offset.x + x1 * scale, y = offset.y + y1 * scale;
      const w = (x2 - x1) * scale, h = (y2 - y1) * scale;
      ctx.fillRect(x, y, w, h);
      ctx.strokeRect(x, y, w, h);
    }
  }
}

function drawPanelBoxes(ctx, panel, scale, offset) {
  // Spec: Panel Layer = frames only (purple). Texts from the panel model are
  // filtered out upstream (run_process) and never drawn.
  ctx.lineWidth = Math.max(2, scale * 2.5);
  ctx.strokeStyle = '#bb9af7';   // purple
  ctx.fillStyle = 'rgba(187,154,247,0.08)';
  for (const box of (panel.frames || [])) {
    const [x1, y1, x2, y2] = box;
    const x = offset.x + x1 * scale, y = offset.y + y1 * scale;
    ctx.fillRect(x, y, (x2 - x1) * scale, (y2 - y1) * scale);
    ctx.strokeRect(x, y, (x2 - x1) * scale, (y2 - y1) * scale);
  }
}

// ── Image fit helpers ────────────────────────────────────────────────

function computeDrawScale(img, cw, ch) {
  if (!img) return 1;
  return Math.min(cw / img.naturalWidth, ch / img.naturalHeight);
}

function computeDrawOffset(img, cw, ch, scale) {
  if (!img) return { x: 0, y: 0 };
  return {
    x: (cw - img.naturalWidth * scale) / 2 + state.panX,
    y: (ch - img.naturalHeight * scale) / 2 + state.panY,
  };
}

// ── Zoom / pan ───────────────────────────────────────────────────────

function updateZoomDisplay() {
  $('zoomDisplay').textContent = state.zoom.toFixed(1) + 'x';
  document.querySelectorAll('.zoom-btn').forEach(b => {
    b.classList.toggle('active', parseFloat(b.dataset.zoom) === state.zoom);
  });
}

document.querySelectorAll('.zoom-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    state.zoom = parseFloat(btn.dataset.zoom);
    state.panX = 0; state.panY = 0;
    updateZoomDisplay();
    draw();
  });
});

canvas.addEventListener('wheel', (e) => {
  e.preventDefault();
  const delta = -e.deltaY / 100;
  state.zoom = Math.max(0.25, Math.min(32, state.zoom * (1 + delta * 0.15)));
  updateZoomDisplay();
  draw();
}, { passive: false });

// Pan — single canvas for all three views
function getActiveCanvas() {
  return canvas;
}

$('canvasContainer').addEventListener('mousedown', (e) => {
  state.isDragging = true;
  state.dragStart = { x: e.clientX, y: e.clientY, panX: state.panX, panY: state.panY };
});

window.addEventListener('mousemove', (e) => {
  if (state.isDragging) {
    state.panX = state.dragStart.panX + (e.clientX - state.dragStart.x);
    state.panY = state.dragStart.panY + (e.clientY - state.dragStart.y);
    draw();
  }
});

window.addEventListener('mouseup', () => {
  state.isDragging = false;
});

// ── Mask params (sliders update display; Re-bake button applies) ─────

function buildMaskParams() {
  return {
    strategy: $('mpStrategy').value,
    free_text_strategy: $('mpFreeStrategy').value,
    skip_px: parseInt($('mpSkipPx').value),
    ring_w: parseInt($('mpRingW').value),
    dilate: parseInt($('mpDilate').value),
    inset_px: parseInt($('mpInsetPx').value),
    luma_floor: parseInt($('mpLumaFloor').value),
    feather: parseInt($('mpFeather').value),
  };
}

['mpSkipPx','mpRingW','mpLumaFloor','mpFeather','mpDilate','mpInsetPx'].forEach(id => {
  $(id).addEventListener('input', () => {
    $(id + 'Val').textContent = $(id).value;
  });
});

// ── Overlay text scale (live — affects Canvas2D text in translated view) ─

$('textFontScale').addEventListener('input', () => {
  state.fontScale = parseInt($('textFontScale').value) / 100;
  $('textFontScaleVal').textContent = state.fontScale.toFixed(2);
  draw();
});
$('textStrokeScale').addEventListener('input', () => {
  state.strokeScale = parseInt($('textStrokeScale').value) / 100;
  $('textStrokeScaleVal').textContent = state.strokeScale.toFixed(2);
  draw();
});

// ── Info panel rendering ─────────────────────────────────────────────

function hidePanels() {
  ['metricsBox','warningsBox','ocrBox'].forEach(id => $(id).classList.add('hidden'));
}

function renderInfoPanel() {
  // warnings
  if (state.warnings.length > 0) {
    $('warningsContent').innerHTML = state.warnings.map(w => `<div>${escapeHtml(w)}</div>`).join('');
    $('warningsBox').classList.remove('hidden');
  } else {
    $('warningsBox').classList.add('hidden');
  }

  // metrics table
  const det = state.layers?.detector;
  const seg = state.layers?.segmentation;
  const pan = state.layers?.panel;
  const rows = [];
  rows.push(['Detector Bubbles', det ? det.bubbles.length : '—']);
  rows.push(['Bubble Text', det ? det.bubble_text.length : '—']);
  rows.push(['Free Text', det ? det.free_text.length : '—']);
  rows.push(['Segmentation Masks', seg ? seg.bubble_masks.length : '—']);
  rows.push(['Panel Frames', pan ? pan.frames.length : '—']);
  rows.push(['OCR Entries', state.ocrData.length]);
  if (state.imageDims) rows.push(['Image', `${state.imageDims.width}×${state.imageDims.height}`]);
  if (state.cleanedImage) rows.push(['Cleaned', '✓']);
  if (state.layouts) rows.push(['Text Layouts', state.layouts.length]);

  $('metricsContent').innerHTML =
    `<table class="metric-table">` +
    rows.map(([l, v]) => `<tr><td>${l}</td><td>${v}</td></tr>`).join('') +
    `</table>`;
  $('metricsBox').classList.remove('hidden');

  // OCR list — clean Japanese text display
  $('ocrCount').textContent = state.ocrData.length;
  if (state.ocrData.length > 0) {
    $('ocrList').innerHTML = state.ocrData.map(o => `
      <div class="ocr-item" data-id="${o.id}">
        <span class="ocr-item-id">#${o.id}</span><span class="ocr-item-jp">${escapeHtml(o.raw_text || '(empty)')}</span>
        ${o.translated_text ? `<div class="ocr-item-trans">${escapeHtml(o.translated_text)}</div>` : ''}
      </div>
    `).join('');
    $('ocrBox').classList.remove('hidden');
    document.querySelectorAll('.ocr-item').forEach(el => {
      el.addEventListener('click', () => openOcrEditor(parseInt(el.dataset.id)));
    });
  } else {
    $('ocrBox').classList.add('hidden');
  }
}

function row(label, val) {
  return `<div class="metric-row"><span class="metric-label">${label}</span><span class="metric-val">${val}</span></div>`;
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

// ── OCR translation editor ───────────────────────────────────────────

function openOcrEditor(id) {
  const entry = state.ocrData.find(o => o.id === id);
  if (!entry) return;
  state.editingOcrId = id;
  $('ocrModalRaw').textContent = entry.raw_text || '(empty)';
  $('ocrModalEdit').value = entry.translated_text || '';
  $('ocrModal').classList.remove('hidden');
}

$('ocrModalSave').addEventListener('click', () => {
  const entry = state.ocrData.find(o => o.id === state.editingOcrId);
  if (entry) {
    entry.translated_text = $('ocrModalEdit').value;
    renderInfoPanel();
    setStatus('Translation updated. Click Generate to re-overlay.', 'success');
  }
  $('ocrModal').classList.add('hidden');
});
$('ocrModalClose').addEventListener('click', () => $('ocrModal').classList.add('hidden'));

// ── LLM translation (LM Studio / OpenAI-compatible) ──────────────────

const LLM_STORAGE_KEY = 'lab_llm_config';

function loadLlmConfig() {
  try {
    const saved = JSON.parse(localStorage.getItem(LLM_STORAGE_KEY) || '{}');
    state.llmConfig = {
      engine: saved.engine || 'lmstudio',
      base_url: saved.base_url || 'http://localhost:3456/v1',
      model: saved.model || '',
      from_lang: saved.from_lang || 'ja',
      to_lang: saved.to_lang || 'en',
    };
  } catch { state.llmConfig = { engine: 'lmstudio', base_url: 'http://localhost:3456/v1', model: '', from_lang: 'ja', to_lang: 'en' }; }
  // Reflect into the UI controls.
  $('llmEngine').value = state.llmConfig.engine;
  $('llmBaseUrl').value = state.llmConfig.base_url;
  $('llmFromLang').value = state.llmConfig.from_lang;
  $('llmToLang').value = state.llmConfig.to_lang;
  if (state.llmConfig.model) {
    const sel = $('llmModel');
    const opt = document.createElement('option');
    opt.value = state.llmConfig.model; opt.textContent = state.llmConfig.model;
    sel.innerHTML = '';
    sel.appendChild(opt);
    sel.value = state.llmConfig.model;
  }
}

function saveLlmConfig() {
  state.llmConfig = {
    engine: $('llmEngine').value,
    base_url: $('llmBaseUrl').value.trim(),
    model: $('llmModel').value,
    from_lang: $('llmFromLang').value,
    to_lang: $('llmToLang').value,
  };
  localStorage.setItem(LLM_STORAGE_KEY, JSON.stringify(state.llmConfig));
}

['llmEngine','llmBaseUrl','llmModel','llmFromLang','llmToLang'].forEach(id => {
  $(id).addEventListener('change', saveLlmConfig);
});

async function fetchLlmModels() {
  const engine = $('llmEngine').value;
  const baseUrl = $('llmBaseUrl').value.trim();
  if (!baseUrl) { $('llmStatus').textContent = '⚠ base_url required'; return; }
  $('llmStatus').textContent = 'Fetching models...';
  $('btnFetchModels').disabled = true;
  try {
    const res = await fetch(`/api/llm/models?engine=${encodeURIComponent(engine)}&base_url=${encodeURIComponent(baseUrl)}`);
    const data = await res.json();
    if (data.error) {
      $('llmStatus').textContent = `⚠ ${data.error}`;
    } else {
      const sel = $('llmModel');
      const current = state.llmConfig?.model || '';
      sel.innerHTML = '';
      const models = data.models || [];
      if (models.length === 0) {
        sel.innerHTML = '<option value="">— no models —</option>';
        $('llmStatus').textContent = '⚠ no models returned';
      } else {
        for (const m of models) {
          const opt = document.createElement('option');
          opt.value = m; opt.textContent = m;
          if (m === current) opt.selected = true;
          sel.appendChild(opt);
        }
        $('llmStatus').textContent = `✓ ${models.length} models`;
      }
      saveLlmConfig();
    }
  } catch (e) {
    $('llmStatus').textContent = `⚠ ${e.message}`;
  } finally {
    $('btnFetchModels').disabled = false;
  }
}

$('btnFetchModels').addEventListener('click', fetchLlmModels);

async function runTranslateLLM() {
  if (state.ocrData.length === 0) return;
  saveLlmConfig();
  const cfg = state.llmConfig;
  if (!cfg.model) { $('llmStatus').textContent = '⚠ select a model first (Fetch)'; return; }
  $('llmStatus').textContent = `Translating ${state.ocrData.length} blocks via ${cfg.model}...`;
  $('btnTranslate').disabled = true;
  showLoading(`Translating via ${cfg.model}...`);

  // Build blocks from ocrData (in reading order, as returned by /api/process).
  const blocks = state.ocrData.map(o => ({
    bbox: { x1: o.box[0], y1: o.box[1], x2: o.box[2], y2: o.box[3] },
    text: o.raw_text,
    translation: o.translated_text || '',
    label: o.label != null ? o.label : 1,
  }));

  try {
    const fd = new FormData();
    fd.append('blocks_json', JSON.stringify(blocks));
    fd.append('engine', cfg.engine);
    fd.append('base_url', cfg.base_url);
    fd.append('model', cfg.model);
    fd.append('from_lang', cfg.from_lang);
    fd.append('to_lang', cfg.to_lang);
    const res = await fetch('/api/translate', { method: 'POST', body: fd });
    const data = await res.json();
    if (data.error) {
      $('llmStatus').textContent = `⚠ ${data.error}`;
      setStatus(`Translation failed: ${data.error}`, 'error');
    } else {
      // Write translations back to ocrData (blocks echoed back with translation set).
      const outBlocks = data.blocks || [];
      outBlocks.forEach((b, i) => {
        if (i < state.ocrData.length && b.translation) {
          state.ocrData[i].translated_text = b.translation;
        }
      });
      const n = data.translated_count || 0;
      $('llmStatus').textContent = `✓ ${n}/${data.block_count} translated in ${data.ms}ms`;
      setStatus(`Translated ${n}/${data.block_count} blocks via ${data.model}.`, 'success');
      renderInfoPanel();
    }
  } catch (e) {
    $('llmStatus').textContent = `⚠ ${e.message}`;
    setStatus(`Translation failed: ${e.message}`, 'error');
  } finally {
    hideLoading();
    $('btnTranslate').disabled = false;
  }
}

$('btnTranslate').addEventListener('click', runTranslateLLM);

// ── Export ───────────────────────────────────────────────────────────

$('exportBtn').addEventListener('click', () => {
  const report = {
    timestamp: new Date().toISOString(),
    page: $('sampleSelect').value,
    image_dimensions: state.imageDims,
    mask_params: buildMaskParams(),
    layers_summary: {
      detector: state.layers?.detector ? {
        bubbles: state.layers.detector.bubbles.length,
        bubble_text: state.layers.detector.bubble_text.length,
        free_text: state.layers.detector.free_text.length,
      } : null,
      segmentation: state.layers?.segmentation ? state.layers.segmentation.bubble_masks.length : null,
      panel: state.layers?.panel ? { frames: state.layers.panel.frames.length } : null,
    },
    ocr_count: state.ocrData.length,
    warnings: state.warnings,
  };
  const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `lab_report_${Date.now()}.json`;
  a.click();
});

// ── Init ─────────────────────────────────────────────────────────────

async function init() {
  resizeCanvas();
  updateZoomDisplay();
  loadLlmConfig();
  try {
    const res = await fetch('/api/samples');
    const data = await res.json();
    const sel = $('sampleSelect');
    for (const name of data.samples) {
      const opt = document.createElement('option');
      opt.value = name; opt.textContent = name;
      sel.appendChild(opt);
    }
  } catch (e) {
    setStatus('Cannot connect to backend. Is the server running?', 'error');
  }
  draw();
}

init();
