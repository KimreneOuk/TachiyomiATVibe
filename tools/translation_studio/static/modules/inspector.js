/* Load-order module: inspector.js. Shared classic-script scope, no build step. */
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
        const artifactPayload = S.artifactData.get(page);
        const translationSource = artifactPayload?.sources?.translations;
        if (translationSource && (!translationSource.data || typeof translationSource.data !== "object")) {
          translationSource.status = "ready";
          translationSource.reason = null;
          translationSource.data = {};
        }
        const artifactTranslations = translationSource?.data;
        const ocrRegion = artifactPayload?.sources?.ocr?.data?.regions?.find(
          (region) => String(region.id) === String(id));
        const translationId = String(ocrRegion?.stable_id || id);
        if (artifactTranslations && typeof artifactTranslations === "object") {
          artifactTranslations[translationId] = {
            ...(artifactTranslations[translationId] && typeof artifactTranslations[translationId] === "object"
              ? artifactTranslations[translationId] : {}),
            text: ta.value, origin: "user",
            ocr_fingerprint: ocrRegion?.ocr_fingerprint || null,
          };
        }
        const records = S.artifactRecords.get(page) || [];
        const translated = String(ta.value).trim();
        records.forEach((record) => {
          if (String(record._regionId || record.attrs?.region_id || "") !== String(id)) return;
          record.attrs.translation = ta.value;
          record.attrs.translation_cached = true;
          record.attrs.translation_id = translationId;
          record.attrs.translation_origin = "user";
          record.attrs.translation_status = translated ? "translated" : "cached";
        });
        renderFilterControls(page);
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