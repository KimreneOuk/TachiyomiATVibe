const state = {
  image: null,
  imageName: "",
  imageFile: null,
  imageData: null,
  backend: null,
  boxes: [],
  drawing: false,
  drawStart: null,
  draftBox: null,
};

const els = {
  fileInput: document.getElementById("fileInput"),
  imageMeta: document.getElementById("imageMeta"),
  originalCanvas: document.getElementById("originalCanvas"),
  boxCanvas: document.getElementById("boxCanvas"),
  maskCanvas: document.getElementById("maskCanvas"),
  inpaintCanvas: document.getElementById("inpaintCanvas"),
  renderedCanvas: document.getElementById("renderedCanvas"),
  translationResults: document.getElementById("translationResults"),
  canvasToggles: document.getElementById("canvasToggles"),
  canvasStack: document.getElementById("canvasStack"),
  addBoxBtn: document.getElementById("addBoxBtn"),
  detectBtn: document.getElementById("detectBtn"),
  ocrBtn: document.getElementById("ocrBtn"),
  inpaintBtn: document.getElementById("inpaintBtn"),
  translateBtn: document.getElementById("translateBtn"),
  runAllBtn: document.getElementById("runAllBtn"),
  clearBoxesBtn: document.getElementById("clearBoxesBtn"),
  boxList: document.getElementById("boxList"),
  downloadBtn: document.getElementById("downloadBtn"),
  paddleCropPadInput: document.getElementById("paddleCropPadInput"),
  paddleCropPadOut: document.getElementById("paddleCropPadOut"),
  paddleThreshInput: document.getElementById("paddleThreshInput"),
  paddleThreshOut: document.getElementById("paddleThreshOut"),
  paddleBoxThreshInput: document.getElementById("paddleBoxThreshInput"),
  paddleBoxThreshOut: document.getElementById("paddleBoxThreshOut"),
  recConfidenceInput: document.getElementById("recConfidenceInput"),
  recConfidenceOut: document.getElementById("recConfidenceOut"),
  padInput: document.getElementById("padInput"),
  padOut: document.getElementById("padOut"),
  modeSelect: document.getElementById("modeSelect"),
  featherInput: document.getElementById("featherInput"),
  featherOut: document.getElementById("featherOut"),
  scaleInput: document.getElementById("scaleInput"),
  scaleOut: document.getElementById("scaleOut"),
  passesInput: document.getElementById("passesInput"),
  passesOut: document.getElementById("passesOut"),
  grayFillThreshInput: document.getElementById("grayFillThreshInput"),
  grayFillThreshOut: document.getElementById("grayFillThreshOut"),
  algoSelect: document.getElementById("algoSelect"),
  tinyExpandInput: document.getElementById("tinyExpandInput"),
  poissonItersInput: document.getElementById("poissonItersInput"),
  poissonItersOut: document.getElementById("poissonItersOut"),
  lmUrlInput: document.getElementById("lmUrlInput"),
  fetchModelsBtn: document.getElementById("fetchModelsBtn"),
  lmModelSelect: document.getElementById("lmModelSelect"),
  maxTokensInput: document.getElementById("maxTokensInput"),
};

const linkedOutputs = [
  ["padInput", "padOut", (v) => v],
  ["paddleCropPadInput", "paddleCropPadOut", (v) => v],
  ["paddleThreshInput", "paddleThreshOut", (v) => (Number(v) / 100).toFixed(2)],
  ["paddleBoxThreshInput", "paddleBoxThreshOut", (v) => (Number(v) / 100).toFixed(2)],
  ["recConfidenceInput", "recConfidenceOut", (v) => (Number(v) / 100).toFixed(2)],
  ["featherInput", "featherOut", (v) => v],
  ["scaleInput", "scaleOut", (v) => `${v}%`],
  ["passesInput", "passesOut", (v) => v],
  ["grayFillThreshInput", "grayFillThreshOut", (v) => Number(v).toFixed(1)],
  ["poissonItersInput", "poissonItersOut", (v) => v],
];

linkedOutputs.forEach(([inputId, outputId, format]) => {
  els[inputId].addEventListener("input", () => {
    els[outputId].value = format(els[inputId].value);
    renderAll();
  });
});

els.fileInput.addEventListener("change", async (event) => {
  const file = event.target.files?.[0];
  if (!file) return;
  await loadImageFile(file);
});

els.addBoxBtn.addEventListener("click", () => {
  state.drawing = true;
  state.draftBox = null;
  els.addBoxBtn.textContent = "Drag now";
});

els.clearBoxesBtn.addEventListener("click", () => {
  state.boxes = [];
  state.backend = null;
  state.draftBox = null;
  state.drawing = false;
  els.addBoxBtn.textContent = "Draw box";
  renderAll();
});

els.downloadBtn.addEventListener("click", () => {
  if (!state.backend?.inpaint_png) return;
  const link = document.createElement("a");
  link.download = "inpainted.png";
  link.href = state.backend.inpaint_png;
  link.click();
});

els.fetchModelsBtn.addEventListener("click", async () => {
  els.fetchModelsBtn.textContent = "Fetching...";
  try {
    const res = await fetch(`/api/models?baseUrl=${encodeURIComponent(els.lmUrlInput.value)}`);
    if (!res.ok) throw new Error("Failed");
    const data = await res.json();
    if (data.error) throw new Error(data.error);
    const models = data.models || [];
    els.lmModelSelect.innerHTML = "";
    models.forEach(m => {
      const opt = document.createElement("option");
      opt.value = m;
      opt.textContent = m;
      els.lmModelSelect.appendChild(opt);
    });
  } catch (err) {
    alert(`Failed to fetch models: ${err.message}`);
  } finally {
    els.fetchModelsBtn.textContent = "Fetch";
  }
});

// --- Canvas Toggling ---
if (els.canvasToggles) {
  els.canvasToggles.addEventListener("change", (e) => {
    if (e.target.name === "layer") {
      const selected = e.target.value;
      const canvases = {
        original: els.originalCanvas,
        box: els.boxCanvas,
        mask: els.maskCanvas,
        inpaint: els.inpaintCanvas,
        rendered: els.renderedCanvas
      };
      
      // Remove active from all
      Object.values(canvases).forEach(c => c.classList.remove("active"));
      
      // Add to selected
      if (canvases[selected]) {
        canvases[selected].classList.add("active");
      }
    }
  });
}

// --- Draw Box Events ---
els.boxCanvas.addEventListener("pointerdown", (event) => {
  if (!state.image || !state.drawing) return;
  const point = canvasPoint(els.boxCanvas, event);
  state.drawStart = point;
  state.draftBox = { x: point.x, y: point.y, w: 0, h: 0 };
  els.boxCanvas.setPointerCapture(event.pointerId);
});

els.boxCanvas.addEventListener("pointermove", (event) => {
  if (!state.drawStart || !state.drawing) return;
  const point = canvasPoint(els.boxCanvas, event);
  state.draftBox = normalizeBox({
    x: state.drawStart.x,
    y: state.drawStart.y,
    w: point.x - state.drawStart.x,
    h: point.y - state.drawStart.y,
  });
  drawBoxPane();
});

els.boxCanvas.addEventListener("pointerup", (event) => {
  if (!state.drawStart || !state.drawing) return;
  const box = normalizeBox(state.draftBox);
  if (box.w >= 4 && box.h >= 4) {
    state.boxes.push(clampBox(box, state.image.width, state.image.height));
  }
  state.backend = null;
  state.drawStart = null;
  state.draftBox = null;
  state.drawing = false;
  els.addBoxBtn.textContent = "Draw box";
  els.boxCanvas.releasePointerCapture(event.pointerId);
  renderAll();
});

async function loadImageFile(file) {
  const url = URL.createObjectURL(file);
  const img = new Image();
  img.decoding = "async";
  img.onload = () => {
    state.image = img;
    state.imageFile = file;
    state.imageName = file.name;
    state.boxes = [];
    state.backend = null;
    setCanvasSize(els.originalCanvas, img.width, img.height);
    setCanvasSize(els.boxCanvas, img.width, img.height);
    setCanvasSize(els.maskCanvas, img.width, img.height);
    setCanvasSize(els.inpaintCanvas, img.width, img.height);
    setCanvasSize(els.renderedCanvas, img.width, img.height);

    const ctx = els.originalCanvas.getContext("2d", { willReadFrequently: true });
    ctx.drawImage(img, 0, 0);
    state.imageData = ctx.getImageData(0, 0, img.width, img.height);
    els.imageMeta.textContent = `${file.name} - ${img.width} x ${img.height}`;
    renderAll();
  };
  img.src = url;
}

function switchCanvas(name) {
  const radio = document.querySelector(`input[name="layer"][value="${name}"]`);
  if (radio) {
    radio.checked = true;
    radio.dispatchEvent(new Event("change", { bubbles: true }));
  }
}

async function runDetection() {
  if (!state.imageFile) return;
  els.detectBtn.disabled = true;
  els.detectBtn.textContent = "Detecting...";
  els.imageMeta.textContent = `${state.imageName} - detecting boxes`;
  
  const form = new FormData();
  form.append("image", state.imageFile);
  form.append("paddle_crop_pad", els.paddleCropPadInput.value);
  form.append("paddle_thresh", (Number(els.paddleThreshInput.value) / 100).toString());
  form.append("paddle_box_thresh", (Number(els.paddleBoxThreshInput.value) / 100).toString());
  
  try {
    const res = await fetch("/api/detect", { method: "POST", body: form });
    if (!res.ok) throw new Error(`backend returned ${res.status}`);
    const data = await res.json();
    state.backend = state.backend || {};
    Object.assign(state.backend, data);
    
    state.boxes = state.backend.mask_boxes.map((item) => {
      const [x1, y1, x2, y2] = item.bbox;
      return { x: x1, y: y1, w: x2 - x1, h: y2 - y1 };
    });
    
    els.imageMeta.textContent = `${state.imageName} - ${data.detections.length} boxes detected`;
    switchCanvas("box");
  } catch (error) {
    console.error(error);
    els.imageMeta.textContent = `Detection failed: ${error.message}`;
  } finally {
    els.detectBtn.disabled = false;
    els.detectBtn.textContent = "1. Detect";
    renderAll();
  }
}

async function runOCR() {
  if (!state.imageFile || !state.backend?.text_dets) return;
  els.ocrBtn.disabled = true;
  els.ocrBtn.textContent = "Extracting...";
  els.imageMeta.textContent = `${state.imageName} - extracting text via OCR`;
  
  const form = new FormData();
  form.append("image", state.imageFile);
  form.append("text_dets_json", JSON.stringify(state.backend.text_dets));
  form.append("paddle_boxes_json", JSON.stringify(state.backend.paddle_boxes));
  form.append("rec_confidence", (Number(els.recConfidenceInput.value) / 100).toString());
  
  try {
    const res = await fetch("/api/ocr", { method: "POST", body: form });
    if (!res.ok) throw new Error(`backend returned ${res.status}`);
    const data = await res.json();
    state.backend = state.backend || {};
    Object.assign(state.backend, data);
    
    if (data.ocr_texts.length === 0) {
      els.imageMeta.textContent = `${state.imageName} - OCR found NO text!`;
    } else {
      els.imageMeta.textContent = `${state.imageName} - ${data.ocr_texts.length} text blocks extracted`;
    }
    
    // Simulate translations array so sidebar renders OCR text
    state.backend.translations = data.ocr_texts.map((t, i) => {
      const b = data.det_list[i] ? data.det_list[i].bbox : [0,0,0,0];
      return {
        bbox: b,
        ocr_text: t,
        translation: "..."
      };
    });
    
  } catch (error) {
    console.error("OCR API Error:", error);
    els.imageMeta.textContent = `OCR failed: ${error.message}`;
  } finally {
    els.ocrBtn.disabled = false;
    els.ocrBtn.textContent = "2. OCR";
    renderAll();
  }
}

async function runInpaint() {
  if (!state.imageFile || !state.backend?.text_dets) return;
  els.inpaintBtn.disabled = true;
  els.inpaintBtn.textContent = "Inpainting...";
  els.imageMeta.textContent = `${state.imageName} - generating background`;
  
  const form = new FormData();
  form.append("image", state.imageFile);
  form.append("text_dets_json", JSON.stringify(state.backend.text_dets));
  form.append("paddle_boxes_json", JSON.stringify(state.backend.paddle_boxes));
  form.append("fallback_boxes_json", JSON.stringify(state.backend.fallback_boxes));
  form.append("mask_pad", els.padInput.value);
  form.append("feather", els.featherInput.value);
  form.append("lowres_scale", els.scaleInput.value);
  form.append("smooth_passes", els.passesInput.value);
  form.append("mode", els.modeSelect.value);
  form.append("gray_fill_thresh", els.grayFillThreshInput.value);
  form.append("algo", els.algoSelect.value);
  form.append("tiny_expand", els.tinyExpandInput.checked ? "true" : "false");
  form.append("poisson_iters", els.poissonItersInput.value);
  
  try {
      const res = await fetch("/api/inpaint", { method: "POST", body: form });
      if (!res.ok) throw new Error(`backend returned ${res.status}`);
      const data = await res.json();
      state.backend = state.backend || {};
      Object.assign(state.backend, data);
      if (data.diagnostics) {
        console.log("coherent diag", data.diagnostics);
      }

      els.imageMeta.textContent = `${state.imageName} - background generated`;
      switchCanvas("inpaint");
    } catch (error) {
      console.error("Inpaint API Error:", error);
      els.imageMeta.textContent = `Inpaint failed: ${error.message}`;
    } finally {
      els.inpaintBtn.disabled = false;
      els.inpaintBtn.textContent = "3. Inpaint";
    renderAll();
  }
}

async function runTranslation() {
  if (!state.backend?.inpaint_png || !state.backend?.ocr_texts) return;
  els.translateBtn.disabled = true;
  els.translateBtn.textContent = "Translating...";
  els.imageMeta.textContent = `${state.imageName} - translating text with LLM`;
  
  const form = new FormData();
  // Convert base64 data URL to blob
  const inpaintBlob = await (await fetch(state.backend.inpaint_png)).blob();
  form.append("inpaint_image", inpaintBlob, "inpaint.png");
  form.append("ocr_texts_json", JSON.stringify(state.backend.ocr_texts));
  form.append("det_list_json", JSON.stringify(state.backend.det_list));
  form.append("lm_url", els.lmUrlInput.value);
  form.append("lm_model", els.lmModelSelect.value);
  form.append("max_tokens", els.maxTokensInput.value);
  
  try {
    const res = await fetch("/api/translate", { method: "POST", body: form });
    if (!res.ok) throw new Error(`backend returned ${res.status}`);
    const data = await res.json();
    state.backend = state.backend || {};
    Object.assign(state.backend, data);
    
    els.imageMeta.textContent = `${state.imageName} - translation complete`;
    switchCanvas("rendered");
  } catch (error) {
    console.error(error);
    els.imageMeta.textContent = `Translation failed: ${error.message}`;
  } finally {
    els.translateBtn.disabled = false;
    els.translateBtn.textContent = "4. Translate";
    renderAll();
  }
}

els.detectBtn.addEventListener("click", runDetection);
els.ocrBtn.addEventListener("click", runOCR);
els.inpaintBtn.addEventListener("click", runInpaint);
els.translateBtn.addEventListener("click", runTranslation);

els.runAllBtn.addEventListener("click", async () => {
  els.runAllBtn.disabled = true;
  els.runAllBtn.textContent = "Running Pipeline...";
  try {
    await runDetection();
    await runOCR();
    await runInpaint();
    await runTranslation();
  } finally {
    els.runAllBtn.disabled = false;
    els.runAllBtn.textContent = "Run Entire Pipeline";
  }
});

function renderAll() {
  if (!state.image || !state.imageData) {
    renderEmpty();
    return;
  }
  drawOriginal();
  drawBoxPane();
  const mask = buildMask();
  drawMask(mask);
  drawInpaint(mask);
  drawRendered();
  renderBoxList();
  renderTranslations();
}

function renderEmpty() {
  [els.originalCanvas, els.boxCanvas, els.maskCanvas, els.inpaintCanvas, els.renderedCanvas].forEach((canvas) => {
    const ctx = canvas.getContext("2d");
    if (!canvas.width) setCanvasSize(canvas, 800, 520);
    ctx.clearRect(0, 0, canvas.width, canvas.height);
  });
  els.translationResults.innerHTML = '<p class="hint">No translation data yet.</p>';
}

function drawOriginal() {
  const ctx = els.originalCanvas.getContext("2d");
  ctx.clearRect(0, 0, els.originalCanvas.width, els.originalCanvas.height);
  ctx.drawImage(state.image, 0, 0);
}

function drawBoxPane() {
  if (!state.image) return;
  const ctx = els.boxCanvas.getContext("2d");
  ctx.clearRect(0, 0, els.boxCanvas.width, els.boxCanvas.height);
  ctx.drawImage(state.image, 0, 0);
  if (state.backend) {
    state.backend.detections.forEach((det) => {
      const [x1, y1, x2, y2] = det.bbox;
      ctx.save();
      ctx.lineWidth = Math.max(2, Math.round(state.image.width / 600));
      ctx.strokeStyle = det.label === 0 ? "#2f70db" : "#ff00ff";
      ctx.setLineDash(det.label === 0 ? [12, 7] : []);
      ctx.strokeRect(x1 + 0.5, y1 + 0.5, x2 - x1, y2 - y1);
      ctx.restore();
    });
    state.backend.paddle_boxes.forEach((item) => {
      const [x1, y1, x2, y2] = item.bbox;
      ctx.save();
      ctx.lineWidth = Math.max(2, Math.round(state.image.width / 900));
      ctx.strokeStyle = "#00ff00";
      ctx.fillStyle = "rgba(0, 255, 0, 0.08)";
      ctx.fillRect(x1, y1, x2 - x1, y2 - y1);
      ctx.strokeRect(x1 + 0.5, y1 + 0.5, x2 - x1, y2 - y1);
      ctx.restore();
    });
    if (state.backend.fallback_boxes) {
      state.backend.fallback_boxes.forEach((item) => {
        const [x1, y1, x2, y2] = item.bbox;
        ctx.save();
        ctx.lineWidth = Math.max(2, Math.round(state.image.width / 800));
        ctx.strokeStyle = "#f59e0b";
        ctx.fillStyle = "rgba(245,158,11,0.10)";
        ctx.fillRect(x1, y1, x2 - x1, y2 - y1);
        ctx.strokeRect(x1 + 0.5, y1 + 0.5, x2 - x1, y2 - y1);
        ctx.restore();
      });
    }
    if (state.backend.text_dets) {
      state.backend.text_dets.forEach((item) => {
        const [x1, y1, x2, y2] = item.bbox;
        ctx.save();
        ctx.lineWidth = Math.max(1, Math.round(state.image.width / 1000));
        ctx.strokeStyle = "#8b5cf6";
        ctx.setLineDash([4, 4]);
        ctx.strokeRect(x1 + 0.5, y1 + 0.5, x2 - x1, y2 - y1);
        ctx.restore();
      });
    }
    if (state.backend.aot_crop_box) {
      const [cx1, cy1, cx2, cy2] = state.backend.aot_crop_box;
      ctx.save();
      ctx.lineWidth = Math.max(3, Math.round(state.image.width / 500));
      ctx.strokeStyle = "#ef4444";
      ctx.setLineDash([8, 4]);
      ctx.strokeRect(cx1 + 0.5, cy1 + 0.5, cx2 - cx1, cy2 - cy1);
      ctx.fillStyle = "#ef4444";
      ctx.font = "bold 14px sans-serif";
      ctx.fillText("AOT Neural Crop (~33% BBox)", cx1 + 6, cy1 + 20);
      ctx.restore();
    }
  }
  [...state.boxes, state.draftBox].filter(Boolean).forEach((box, index) => {
    ctx.save();
    ctx.lineWidth = Math.max(2, Math.round(state.image.width / 700));
    ctx.strokeStyle = index === state.boxes.length ? "#f59e0b" : "#cf3f4a";
    ctx.fillStyle = index === state.boxes.length ? "rgba(245,158,11,0.14)" : "rgba(207,63,74,0.12)";
    ctx.fillRect(box.x, box.y, box.w, box.h);
    ctx.strokeRect(box.x + 0.5, box.y + 0.5, box.w, box.h);
    ctx.restore();
  });
}

function renderBoxList() {
  els.boxList.innerHTML = "";
  if (!state.boxes.length) {
    const empty = document.createElement("div");
    empty.className = "box-item";
    empty.innerHTML = '<span class="box-swatch"></span><span>No boxes</span>';
    els.boxList.appendChild(empty);
    return;
  }
  state.boxes.slice(0, 80).forEach((box, index) => {
    const row = document.createElement("div");
    row.className = "box-item";
    const label = `${Math.round(box.x)}, ${Math.round(box.y)} - ${Math.round(box.w)} x ${Math.round(box.h)}`;
    row.innerHTML = `<span class="box-swatch"></span><span>${label}</span>`;
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = "Remove";
    button.addEventListener("click", () => {
      state.boxes.splice(index, 1);
      renderAll();
    });
    row.appendChild(button);
    els.boxList.appendChild(row);
  });
  if (state.boxes.length > 80) {
    const row = document.createElement("div");
    row.className = "box-item";
    row.innerHTML = `<span class="box-swatch"></span><span>+${state.boxes.length - 80} more boxes</span>`;
    els.boxList.appendChild(row);
  }
}

function buildMask() {
  const { width, height } = state.imageData;
  const mask = new Uint8Array(width * height);
  const pad = Number(els.padInput.value);

  state.boxes.forEach((rawBox) => {
    const box = clampBox({
      x: rawBox.x - pad,
      y: rawBox.y - pad,
      w: rawBox.w + pad * 2,
      h: rawBox.h + pad * 2,
    }, width, height);
    fillMaskRect(mask, width, box);
  });

  return dilateMask(mask, width, height, 2);
}

function autoDetectBoxes() {
  const { width, height, data } = state.imageData;
  const targetW = 520;
  const scale = Math.min(1, targetW / width);
  const sw = Math.max(1, Math.round(width * scale));
  const sh = Math.max(1, Math.round(height * scale));
  const gray = new Float32Array(sw * sh);
  const localMean = new Float32Array(sw * sh);

  for (let y = 0; y < sh; y += 1) {
    const py = Math.min(height - 1, Math.floor(y / scale));
    for (let x = 0; x < sw; x += 1) {
      const px = Math.min(width - 1, Math.floor(x / scale));
      const src = (py * width + px) * 4;
      gray[y * sw + x] = data[src] * 0.299 + data[src + 1] * 0.587 + data[src + 2] * 0.114;
    }
  }

  const integral = new Float32Array((sw + 1) * (sh + 1));
  for (let y = 0; y < sh; y += 1) {
    let row = 0;
    for (let x = 0; x < sw; x += 1) {
      row += gray[y * sw + x];
      integral[(y + 1) * (sw + 1) + x + 1] = integral[y * (sw + 1) + x + 1] + row;
    }
  }

  const radius = Math.max(5, Math.round(Math.min(sw, sh) / 70));
  const candidate = new Uint8Array(sw * sh);
  for (let y = 0; y < sh; y += 1) {
    const y1 = Math.max(0, y - radius);
    const y2 = Math.min(sh, y + radius + 1);
    for (let x = 0; x < sw; x += 1) {
      const x1 = Math.max(0, x - radius);
      const x2 = Math.min(sw, x + radius + 1);
      const area = (x2 - x1) * (y2 - y1);
      const mean = rectSum(integral, sw + 1, x1, y1, x2, y2) / area;
      localMean[y * sw + x] = mean;
      const value = gray[y * sw + x];
      if ((value < mean - 34 && value < 150) || (value > mean + 42 && value > 210 && mean < 185)) {
        candidate[y * sw + x] = 255;
      }
    }
  }

  const comps = collectComponents(candidate, sw, sh);
  const rawBoxes = [];
  for (const c of comps) {
    const bw = c.maxX - c.minX + 1;
    const bh = c.maxY - c.minY + 1;
    const area = c.pixels.length;
    const boxArea = bw * bh;
    const fill = area / Math.max(1, boxArea);
    const pageFrac = boxArea / (sw * sh);
    const tooSmall = area < 4 || bw < 2 || bh < 2;
    const tooHuge = pageFrac > 0.035 || bw > sw * 0.38 || bh > sh * 0.18;
    const likelyTone = area <= 10 && bw <= 4 && bh <= 4;
    const likelyLineArt = fill > 0.65 && (bw > 18 || bh > 18);
    if (tooSmall || tooHuge || likelyTone || likelyLineArt) continue;
    rawBoxes.push({
      x: c.minX / scale,
      y: c.minY / scale,
      w: bw / scale,
      h: bh / scale,
    });
  }

  return mergeAutoBoxes(rawBoxes, width, height)
    .map((box) => clampBox({
      x: box.x - 5,
      y: box.y - 5,
      w: box.w + 10,
      h: box.h + 10,
    }, width, height))
    .filter((box) => box.w >= 6 && box.h >= 6)
    .slice(0, 80);
}

function collectComponents(mask, width, height) {
  const visited = new Uint8Array(mask.length);
  const queue = [];
  const components = [];
  for (let start = 0; start < mask.length; start += 1) {
    if (!mask[start] || visited[start]) continue;
    queue.length = 0;
    let head = 0;
    const pixels = [];
    let minX = width;
    let minY = height;
    let maxX = 0;
    let maxY = 0;
    visited[start] = 1;
    queue.push(start);
    while (head < queue.length) {
      const idx = queue[head];
      head += 1;
      pixels.push(idx);
      const x = idx % width;
      const y = Math.floor(idx / width);
      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      maxX = Math.max(maxX, x);
      maxY = Math.max(maxY, y);
      for (let dy = -1; dy <= 1; dy += 1) {
        for (let dx = -1; dx <= 1; dx += 1) {
          if (!dx && !dy) continue;
          const nx = x + dx;
          const ny = y + dy;
          if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
          const ni = ny * width + nx;
          if (mask[ni] && !visited[ni]) {
            visited[ni] = 1;
            queue.push(ni);
          }
        }
      }
    }
    components.push({ pixels, minX, minY, maxX, maxY });
  }
  return components;
}

function mergeAutoBoxes(boxes, pageW, pageH) {
  let current = boxes;
  for (let pass = 0; pass < 4; pass += 1) {
    const used = new Uint8Array(current.length);
    const next = [];
    for (let i = 0; i < current.length; i += 1) {
      if (used[i]) continue;
      let box = { ...current[i] };
      used[i] = 1;
      let changed = true;
      while (changed) {
        changed = false;
        for (let j = 0; j < current.length; j += 1) {
          if (used[j]) continue;
          if (!shouldMergeAutoBox(box, current[j])) continue;
          box = unionBox(box, current[j]);
          used[j] = 1;
          changed = true;
        }
      }
      if (box.w * box.h < pageW * pageH * 0.045) next.push(box);
    }
    if (next.length === current.length) break;
    current = next;
  }
  return current.sort((a, b) => (a.y - b.y) || (a.x - b.x));
}

function shouldMergeAutoBox(a, b) {
  const ax2 = a.x + a.w;
  const ay2 = a.y + a.h;
  const bx2 = b.x + b.w;
  const by2 = b.y + b.h;
  const overlapX = Math.max(0, Math.min(ax2, bx2) - Math.max(a.x, b.x));
  const overlapY = Math.max(0, Math.min(ay2, by2) - Math.max(a.y, b.y));
  const gapX = Math.max(0, Math.max(a.x, b.x) - Math.min(ax2, bx2));
  const gapY = Math.max(0, Math.max(a.y, b.y) - Math.min(ay2, by2));
  const rowAligned = overlapY > Math.min(a.h, b.h) * 0.35 && gapX < Math.max(a.h, b.h) * 1.8;
  const colAligned = overlapX > Math.min(a.w, b.w) * 0.35 && gapY < Math.max(a.w, b.w) * 2.2;
  const close = gapX < 8 && gapY < 8;
  return rowAligned || colAligned || close;
}

function unionBox(a, b) {
  const x1 = Math.min(a.x, b.x);
  const y1 = Math.min(a.y, b.y);
  const x2 = Math.max(a.x + a.w, b.x + b.w);
  const y2 = Math.max(a.y + a.h, b.y + b.h);
  return { x: x1, y: y1, w: x2 - x1, h: y2 - y1 };
}

function detectTextLikePixels(data, width, height, box) {
  const x1 = Math.max(0, Math.floor(box.x));
  const y1 = Math.max(0, Math.floor(box.y));
  const x2 = Math.min(width, Math.ceil(box.x + box.w));
  const y2 = Math.min(height, Math.ceil(box.y + box.h));
  const w = Math.max(0, x2 - x1);
  const h = Math.max(0, y2 - y1);
  const mask = new Uint8Array(w * h);
  if (!w || !h) return { mask, x: x1, y: y1, w, h };

  const gray = new Float32Array(w * h);
  const integral = new Float32Array((w + 1) * (h + 1));
  const integralSq = new Float32Array((w + 1) * (h + 1));
  for (let y = 0; y < h; y += 1) {
    let row = 0;
    let rowSq = 0;
    for (let x = 0; x < w; x += 1) {
      const src = ((y1 + y) * width + (x1 + x)) * 4;
      const g = data[src] * 0.299 + data[src + 1] * 0.587 + data[src + 2] * 0.114;
      gray[y * w + x] = g;
      row += g;
      rowSq += g * g;
      const ii = (y + 1) * (w + 1) + (x + 1);
      integral[ii] = integral[ii - (w + 1)] + row;
      integralSq[ii] = integralSq[ii - (w + 1)] + rowSq;
    }
  }

  const radius = Math.max(4, Math.min(14, Math.round(Math.min(w, h) / 7)));
  const contrast = Number(els.contrastInput.value);
  const median = medianGray(gray);
  for (let y = 0; y < h; y += 1) {
    for (let x = 0; x < w; x += 1) {
      const sx1 = Math.max(0, x - radius);
      const sy1 = Math.max(0, y - radius);
      const sx2 = Math.min(w, x + radius + 1);
      const sy2 = Math.min(h, y + radius + 1);
      const area = (sx2 - sx1) * (sy2 - sy1);
      const sum = rectSum(integral, w + 1, sx1, sy1, sx2, sy2);
      const sumSq = rectSum(integralSq, w + 1, sx1, sy1, sx2, sy2);
      const mean = sum / area;
      const variance = Math.max(0, sumSq / area - mean * mean);
      const std = Math.sqrt(variance);
      const value = gray[y * w + x];
      const adaptive = Math.max(contrast, Math.min(52, std * 0.85 + contrast * 0.55));
      const darkStroke = value < mean - adaptive || (value < 88 && mean > 116);
      const lightStroke = value > mean + adaptive && value > 176 && median < 210;
      const ringDelta = Math.abs(value - median) > Math.max(contrast * 0.8, 10);
      if ((darkStroke || lightStroke) && ringDelta) mask[y * w + x] = 255;
    }
  }

  return { mask, x: x1, y: y1, w, h };
}

function filterComponents(mask, width, height) {
  const result = new Uint8Array(mask.length);
  const visited = new Uint8Array(mask.length);
  const minArea = Number(els.minAreaInput.value);
  const maxArea = Math.max(16, Math.round(mask.length * Number(els.maxAreaInput.value) / 100));
  const queue = [];

  for (let start = 0; start < mask.length; start += 1) {
    if (!mask[start] || visited[start]) continue;
    queue.length = 0;
    let head = 0;
    const pixels = [];
    let minX = width;
    let minY = height;
    let maxX = 0;
    let maxY = 0;
    let touchesEdge = false;
    visited[start] = 1;
    queue.push(start);

    while (head < queue.length) {
      const idx = queue[head];
      head += 1;
      pixels.push(idx);
      const x = idx % width;
      const y = Math.floor(idx / width);
      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      maxX = Math.max(maxX, x);
      maxY = Math.max(maxY, y);
      if (x <= 1 || y <= 1 || x >= width - 2 || y >= height - 2) touchesEdge = true;

      for (let dy = -1; dy <= 1; dy += 1) {
        for (let dx = -1; dx <= 1; dx += 1) {
          if (!dx && !dy) continue;
          const nx = x + dx;
          const ny = y + dy;
          if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
          const ni = ny * width + nx;
          if (mask[ni] && !visited[ni]) {
            visited[ni] = 1;
            queue.push(ni);
          }
        }
      }
    }

    const area = pixels.length;
    const cw = maxX - minX + 1;
    const ch = maxY - minY + 1;
    const tinyToneDot = area < minArea && cw <= 4 && ch <= 4;
    const giantBlob = area > maxArea || (cw > width * 0.72 && ch > height * 0.72);
    const edgePanel = touchesEdge && (cw > width * 0.7 || ch > height * 0.7);
    const longBand = (ch <= 3 && cw > width * 0.55) || (cw <= 3 && ch > height * 0.55);
    if (!tinyToneDot && !giantBlob && !edgePanel && !longBand) {
      pixels.forEach((idx) => { result[idx] = 255; });
    }
  }
  return result;
}

function drawMask(mask) {
  if (state.backend?.mask_png) {
    drawDataUrlToCanvas(state.backend.mask_png, els.maskCanvas);
    return;
  }
  const { width, height } = state.imageData;
  const ctx = els.maskCanvas.getContext("2d");
  const out = ctx.createImageData(width, height);
  for (let i = 0; i < mask.length; i += 1) {
    const dst = i * 4;
    out.data[dst] = mask[i];
    out.data[dst + 1] = mask[i];
    out.data[dst + 2] = mask[i];
    out.data[dst + 3] = 255;
  }
  ctx.putImageData(out, 0, 0);
}

function drawInpaint(mask) {
  if (state.backend?.inpaint_png) {
    drawDataUrlToCanvas(state.backend.inpaint_png, els.inpaintCanvas);
    return;
  }
  const { width, height, data } = state.imageData;
  const ctx = els.inpaintCanvas.getContext("2d");
  const out = ctx.createImageData(width, height);
  out.data.set(data);
  const feather = Number(els.featherInput.value);
  const bounds = maskBounds(mask, width, height, Math.max(24, feather * 3));
  if (!bounds) {
    ctx.putImageData(out, 0, 0);
    return;
  }

  const roi = extractRoi(data, mask, width, bounds);
  const alpha = featherMask(roi.mask, bounds.w, bounds.h, feather);
  const background = smoothGradientBackground(roi.data, roi.mask, bounds.w, bounds.h);

  for (let y = 0; y < bounds.h; y += 1) {
    for (let x = 0; x < bounds.w; x += 1) {
      const i = y * bounds.w + x;
      const src = i * 4;
      const page = ((bounds.y + y) * width + bounds.x + x) * 4;
      const a = alpha[i];
      if (a <= 0) continue;
      const inv = 1 - a;
      out.data[page] = Math.round(roi.data[src] * inv + background[src] * a);
      out.data[page + 1] = Math.round(roi.data[src + 1] * inv + background[src + 1] * a);
      out.data[page + 2] = Math.round(roi.data[src + 2] * inv + background[src + 2] * a);
      out.data[page + 3] = 255;
    }
  }
  ctx.putImageData(out, 0, 0);
}

function drawRendered() {
  if (state.backend?.rendered_png) {
    drawDataUrlToCanvas(state.backend.rendered_png, els.renderedCanvas);
  } else {
    const ctx = els.renderedCanvas.getContext("2d");
    ctx.clearRect(0, 0, els.renderedCanvas.width, els.renderedCanvas.height);
    if (state.image) {
      ctx.drawImage(state.image, 0, 0);
    }
  }
}

function renderTranslations() {
  if (!state.backend?.translations || state.backend.translations.length === 0) {
    els.translationResults.innerHTML = '<p class="hint">No translation data found.</p>';
    return;
  }
  let html = "";
  state.backend.translations.forEach((t, i) => {
    html += `
      <div class="translation-item" data-index="${i}">
        <div class="ocr-text">${t.ocr_text || "No text detected"}</div>
        <div class="trans-text">${t.translation || "Translation failed"}</div>
      </div>
    `;
  });
  els.translationResults.innerHTML = html;

  // Add click to highlight
  document.querySelectorAll(".translation-item").forEach(item => {
    item.addEventListener("click", () => {
      const idx = item.getAttribute("data-index");
      const t = state.backend.translations[idx];
      if (t && t.bbox) {
        // Switch to the Rendered canvas to show where the translation is
        const renderedRadio = document.querySelector('input[name="layer"][value="rendered"]');
        if (renderedRadio) {
           renderedRadio.checked = true;
           renderedRadio.dispatchEvent(new Event("change", { bubbles: true }));
        }

        // Calculate position based on CSS scaling
        const canvas = els.renderedCanvas;
        const scale = canvas.getBoundingClientRect().width / canvas.width;
        const x = t.bbox[0] * scale;
        const y = t.bbox[1] * scale;
        const w = (t.bbox[2] - t.bbox[0]) * scale;
        const h = (t.bbox[3] - t.bbox[1]) * scale;

        // Draw temporary highlight div
        let highlight = document.getElementById("transHighlight");
        if (!highlight) {
          highlight = document.createElement("div");
          highlight.id = "transHighlight";
          highlight.style.position = "absolute";
          highlight.style.border = "3px solid var(--accent)";
          highlight.style.boxShadow = "var(--glow)";
          highlight.style.borderRadius = "4px";
          highlight.style.pointerEvents = "none";
          highlight.style.transition = "all 0.3s";
          highlight.style.zIndex = "50";
          els.canvasStack.appendChild(highlight);
        }
        
        highlight.style.left = `${x}px`;
        highlight.style.top = `${y}px`;
        highlight.style.width = `${w}px`;
        highlight.style.height = `${h}px`;
        highlight.style.opacity = "1";

        // Fade out after 2 seconds
        setTimeout(() => {
          highlight.style.opacity = "0";
        }, 2000);
      }
    });
  });
}

function drawDataUrlToCanvas(url, canvas) {
  const img = new Image();
  img.onload = () => {
    const ctx = canvas.getContext("2d");
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
  };
  img.src = url;
}

function maskBounds(mask, width, height, margin) {
  let minX = width;
  let minY = height;
  let maxX = -1;
  let maxY = -1;
  for (let i = 0; i < mask.length; i += 1) {
    if (!mask[i]) continue;
    const x = i % width;
    const y = Math.floor(i / width);
    if (x < minX) minX = x;
    if (y < minY) minY = y;
    if (x > maxX) maxX = x;
    if (y > maxY) maxY = y;
  }
  if (maxX < minX || maxY < minY) return null;
  const x = Math.max(0, minX - margin);
  const y = Math.max(0, minY - margin);
  const x2 = Math.min(width, maxX + margin + 1);
  const y2 = Math.min(height, maxY + margin + 1);
  return { x, y, w: x2 - x, h: y2 - y };
}

function extractRoi(data, mask, pageW, bounds) {
  const roiData = new Uint8ClampedArray(bounds.w * bounds.h * 4);
  const roiMask = new Uint8Array(bounds.w * bounds.h);
  for (let y = 0; y < bounds.h; y += 1) {
    const pageRow = (bounds.y + y) * pageW + bounds.x;
    const roiRow = y * bounds.w;
    for (let x = 0; x < bounds.w; x += 1) {
      const pageIdx = pageRow + x;
      const roiIdx = roiRow + x;
      roiMask[roiIdx] = mask[pageIdx];
      const src = pageIdx * 4;
      const dst = roiIdx * 4;
      roiData[dst] = data[src];
      roiData[dst + 1] = data[src + 1];
      roiData[dst + 2] = data[src + 2];
      roiData[dst + 3] = 255;
    }
  }
  return { data: roiData, mask: roiMask };
}

function smoothGradientBackground(data, mask, width, height) {
  const scale = Number(els.scaleInput.value) / 100;
  const smallW = Math.max(4, Math.round(width * scale));
  const smallH = Math.max(4, Math.round(height * scale));
  const count = smallW * smallH;
  const r = new Float32Array(count);
  const g = new Float32Array(count);
  const b = new Float32Array(count);
  const n = new Float32Array(count);

  for (let y = 0; y < height; y += 1) {
    const sy = Math.min(smallH - 1, Math.floor(y * smallH / height));
    for (let x = 0; x < width; x += 1) {
      const idx = y * width + x;
      if (mask[idx]) continue;
      const sx = Math.min(smallW - 1, Math.floor(x * smallW / width));
      const si = sy * smallW + sx;
      const src = idx * 4;
      r[si] += data[src];
      g[si] += data[src + 1];
      b[si] += data[src + 2];
      n[si] += 1;
    }
  }

  let avgR = 0;
  let avgG = 0;
  let avgB = 0;
  let known = 0;
  for (let i = 0; i < count; i += 1) {
    if (n[i] > 0) {
      r[i] /= n[i];
      g[i] /= n[i];
      b[i] /= n[i];
      avgR += r[i];
      avgG += g[i];
      avgB += b[i];
      known += 1;
    }
  }
  if (!known) {
    avgR = avgG = avgB = 255;
  } else {
    avgR /= known;
    avgG /= known;
    avgB /= known;
  }
  for (let i = 0; i < count; i += 1) {
    if (n[i] === 0) {
      r[i] = avgR;
      g[i] = avgG;
      b[i] = avgB;
    }
  }

  const passes = Number(els.passesInput.value);
  let rr = r;
  let gg = g;
  let bb = b;
  for (let pass = 0; pass < passes; pass += 1) {
    const nr = new Float32Array(rr);
    const ng = new Float32Array(gg);
    const nb = new Float32Array(bb);
    for (let y = 0; y < smallH; y += 1) {
      for (let x = 0; x < smallW; x += 1) {
        const i = y * smallW + x;
        if (n[i] > 0) continue;
        let sr = rr[i];
        let sg = gg[i];
        let sb = bb[i];
        let weight = 1;
        [[1, 0], [-1, 0], [0, 1], [0, -1]].forEach(([dx, dy]) => {
          const nx = x + dx;
          const ny = y + dy;
          if (nx < 0 || ny < 0 || nx >= smallW || ny >= smallH) return;
          const ni = ny * smallW + nx;
          sr += rr[ni];
          sg += gg[ni];
          sb += bb[ni];
          weight += 1;
        });
        nr[i] = sr / weight;
        ng[i] = sg / weight;
        nb[i] = sb / weight;
      }
    }
    rr = nr;
    gg = ng;
    bb = nb;
  }

  const out = new Uint8ClampedArray(width * height * 4);
  for (let y = 0; y < height; y += 1) {
    const fy = (y + 0.5) * smallH / height - 0.5;
    const y0 = Math.max(0, Math.floor(fy));
    const y1 = Math.min(smallH - 1, y0 + 1);
    const ty = Math.max(0, Math.min(1, fy - y0));
    for (let x = 0; x < width; x += 1) {
      const fx = (x + 0.5) * smallW / width - 0.5;
      const x0 = Math.max(0, Math.floor(fx));
      const x1 = Math.min(smallW - 1, x0 + 1);
      const tx = Math.max(0, Math.min(1, fx - x0));
      const i00 = y0 * smallW + x0;
      const i10 = y0 * smallW + x1;
      const i01 = y1 * smallW + x0;
      const i11 = y1 * smallW + x1;
      const dst = (y * width + x) * 4;
      out[dst] = bilerp(rr[i00], rr[i10], rr[i01], rr[i11], tx, ty);
      out[dst + 1] = bilerp(gg[i00], gg[i10], gg[i01], gg[i11], tx, ty);
      out[dst + 2] = bilerp(bb[i00], bb[i10], bb[i01], bb[i11], tx, ty);
      out[dst + 3] = 255;
    }
  }
  return out;
}

function featherMask(mask, width, height, radius) {
  const alpha = new Float32Array(mask.length);
  if (radius <= 0) {
    for (let i = 0; i < mask.length; i += 1) alpha[i] = mask[i] ? 1 : 0;
    return alpha;
  }
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const idx = y * width + x;
      if (mask[idx]) {
        alpha[idx] = 1;
        continue;
      }
      let covered = 0;
      let total = 0;
      for (let dy = -radius; dy <= radius; dy += 1) {
        const yy = y + dy;
        if (yy < 0 || yy >= height) continue;
        for (let dx = -radius; dx <= radius; dx += 1) {
          const xx = x + dx;
          if (xx < 0 || xx >= width) continue;
          total += 1;
          if (mask[yy * width + xx]) covered += 1;
        }
      }
      alpha[idx] = total ? covered / total : 0;
    }
  }
  return alpha;
}

function dilateMask(mask, width, height, radius) {
  if (radius <= 0) return mask;
  const out = new Uint8Array(mask);
  const offsets = [];
  for (let dy = -radius; dy <= radius; dy += 1) {
    for (let dx = -radius; dx <= radius; dx += 1) {
      if (dx * dx + dy * dy <= radius * radius) offsets.push([dx, dy]);
    }
  }
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      if (!mask[y * width + x]) continue;
      offsets.forEach(([dx, dy]) => {
        const nx = x + dx;
        const ny = y + dy;
        if (nx >= 0 && ny >= 0 && nx < width && ny < height) out[ny * width + nx] = 255;
      });
    }
  }
  return out;
}

function fillMaskRect(mask, width, box) {
  const x1 = Math.max(0, Math.floor(box.x));
  const y1 = Math.max(0, Math.floor(box.y));
  const x2 = Math.min(width, Math.ceil(box.x + box.w));
  const y2 = Math.min(mask.length / width, Math.ceil(box.y + box.h));
  for (let y = y1; y < y2; y += 1) {
    for (let x = x1; x < x2; x += 1) mask[y * width + x] = 255;
  }
}

function rectSum(integral, stride, x1, y1, x2, y2) {
  return integral[y2 * stride + x2] - integral[y1 * stride + x2] -
    integral[y2 * stride + x1] + integral[y1 * stride + x1];
}

function medianGray(values) {
  const sample = [];
  const step = Math.max(1, Math.floor(values.length / 2000));
  for (let i = 0; i < values.length; i += step) sample.push(values[i]);
  sample.sort((a, b) => a - b);
  return sample[Math.floor(sample.length / 2)] || 255;
}

function bilerp(a, b, c, d, tx, ty) {
  const top = a * (1 - tx) + b * tx;
  const bottom = c * (1 - tx) + d * tx;
  return Math.round(top * (1 - ty) + bottom * ty);
}

function setCanvasSize(canvas, width, height) {
  canvas.width = width;
  canvas.height = height;
}

function canvasPoint(canvas, event) {
  const rect = canvas.getBoundingClientRect();
  return {
    x: (event.clientX - rect.left) * canvas.width / rect.width,
    y: (event.clientY - rect.top) * canvas.height / rect.height,
  };
}

function normalizeBox(box) {
  if (!box) return null;
  const x = box.w < 0 ? box.x + box.w : box.x;
  const y = box.h < 0 ? box.y + box.h : box.y;
  return { x, y, w: Math.abs(box.w), h: Math.abs(box.h) };
}

function clampBox(box, width, height) {
  const x1 = Math.max(0, Math.min(width, box.x));
  const y1 = Math.max(0, Math.min(height, box.y));
  const x2 = Math.max(0, Math.min(width, box.x + box.w));
  const y2 = Math.max(0, Math.min(height, box.y + box.h));
  return normalizeBox({ x: x1, y: y1, w: x2 - x1, h: y2 - y1 });
}

function stripExtension(name) {
  return name.replace(/\.[^.]+$/, "");
}

renderEmpty();
