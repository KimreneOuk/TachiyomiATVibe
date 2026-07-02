// ═══════════════════════════════════════════════════════════════
// Manga Translation Server — Web UI
// ═══════════════════════════════════════════════════════════════

const LABEL_COLORS = { 0: "#3b82f6", 1: "#22c55e", 2: "#f97316" };
const LABEL_NAMES = { 0: "Bubble", 1: "Text Bubble", 2: "Free Text" };

let state = {
    chapterId: null,
    chapterName: "",
    pages: [],
    currentPage: -1,
    mode: "original",
    showBoxes: true,
    pageData: null,
    health: null,
    batchRunning: false,
    batchPollTimer: null,
    zoomLevels: [0, 0.5, 1.0, 1.5, 2.0, 3.0],
    zoomIndex: 0,
};

const $ = (id) => document.getElementById(id);

function showView(name) {
    document.querySelectorAll(".view").forEach((v) => v.classList.remove("active"));
    $(name).classList.add("active");
}

function showToast(msg, type = "") {
    const toast = $("toast");
    toast.textContent = msg;
    toast.className = "toast show " + type;
    setTimeout(() => toast.classList.remove("show"), 3000);
}

function showLoading(text) {
    $("loading-text").textContent = text || "Processing...";
    $("loading-overlay").classList.remove("hidden");
}

function hideLoading() { $("loading-overlay").classList.add("hidden"); }

function formatSize(b) {
    if (b < 1024) return b + " B";
    if (b < 1048576) return (b / 1024).toFixed(1) + " KB";
    return (b / 1048576).toFixed(1) + " MB";
}

function formatDate(iso) {
    if (!iso) return "";
    try {
        const d = new Date(iso);
        const now = new Date();
        const diff = (now - d) / 1000;
        if (diff < 60) return "just now";
        if (diff < 3600) return Math.floor(diff / 60) + "m ago";
        if (diff < 86400) return Math.floor(diff / 3600) + "h ago";
        if (diff < 604800) return Math.floor(diff / 86400) + "d ago";
        return d.toLocaleDateString();
    } catch { return ""; }
}

function escapeHtml(str) {
    const div = document.createElement("div");
    div.textContent = str;
    return div.innerHTML;
}

// ════════ Library View ════════
function initLibrary() {
    $("lib-upload-btn").addEventListener("click", () => showView("upload-view"));
    document.querySelectorAll(".lib-empty-upload").forEach((b) =>
        b.addEventListener("click", () => showView("upload-view"))
    );
    $("lib-settings-btn").addEventListener("click", openSettings);
    $("upload-back-lib").addEventListener("click", () => showView("library-view"));
}

async function loadLibrary() {
    try {
        const res = await fetch("/web/chapters");
        const data = await res.json();
        renderLibrary(data.chapters || []);
    } catch {}
}

function renderLibrary(chapters) {
    const grid = $("lib-grid");
    const empty = $("lib-empty");

    if (!chapters.length) {
        grid.classList.add("hidden");
        empty.classList.remove("hidden");
        return;
    }

    empty.classList.add("hidden");
    grid.classList.remove("hidden");
    grid.innerHTML = "";

    chapters.forEach((ch) => {
        const card = document.createElement("div");
        card.className = "lib-card";
        const lastPage = (ch.last_page || 0) + 1;
        card.innerHTML = `
            <img class="lib-card-thumb" src="/web/${ch.chapter_id}/page/0/image/original" alt="" onerror="this.style.opacity=0.1">
            <div class="lib-card-body">
                <div class="lib-card-name">${escapeHtml(ch.name || "Chapter")}</div>
                <div class="lib-card-meta">
                    <span class="meta-lang">${escapeHtml(ch.target_lang || "?")}</span>
                    <span>${ch.page_count || (ch.pages || []).length}p</span>
                    <span>Last: p.${lastPage}</span>
                    <span>${formatDate(ch.last_viewed || ch.created_at)}</span>
                </div>
            </div>
            <button class="lib-card-delete" title="Delete">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-2 14a2 2 0 01-2 2H9a2 2 0 01-2-2L5 6"/></svg>
            </button>
        `;

        card.addEventListener("click", (e) => {
            if (e.target.closest(".lib-card-delete")) return;
            resumeChapter(ch);
        });

        card.querySelector(".lib-card-delete").addEventListener("click", async (e) => {
            e.stopPropagation();
            if (!confirm(`Delete "${ch.name || "this chapter"}"?`)) return;
            try {
                await fetch(`/web/chapters/${ch.chapter_id}`, { method: "DELETE" });
                card.remove();
                showToast("Deleted", "success");
                loadLibrary();
            } catch { showToast("Failed", "error"); }
        });

        grid.appendChild(card);
    });
}

// ════════ Health Check ════════
async function checkHealth() {
    try {
        const res = await fetch("/v1/health");
        const data = await res.json();
        state.health = data;
        const dotEl = $("lib-health-dot");
        const textEl = $("lib-health-text");
        if (dotEl) {
            dotEl.className = "health-dot ok";
            const loaded = Object.values(data.models || {}).filter((v) => !v.includes("placeholder")).length;
            const total = Object.keys(data.models || {}).length;
            textEl.textContent = `${loaded}/${total} models`;
        }
        const models = data.models || {};
        $("models-badge").innerHTML = Object.entries(models).map(([k, v]) => {
            const ph = v.includes("placeholder");
            return `<span class="model-tag ${ph ? "fallback" : "live"}">${k}: ${v}</span>`;
        }).join("");
    } catch {
        const dotEl = $("lib-health-dot");
        if (dotEl) dotEl.className = "health-dot error";
    }
}

// ════════ Upload ════════
let selectedFile = null;

function initUpload() {
    const dz = $("dropzone");
    const fi = $("file-input");
    dz.addEventListener("click", () => fi.click());
    dz.addEventListener("dragover", (e) => { e.preventDefault(); dz.classList.add("dragover"); });
    dz.addEventListener("dragleave", () => dz.classList.remove("dragover"));
    dz.addEventListener("drop", (e) => { e.preventDefault(); dz.classList.remove("dragover"); if (e.dataTransfer.files.length) handleFile(e.dataTransfer.files[0]); });
    fi.addEventListener("change", (e) => { if (e.target.files.length) handleFile(e.target.files[0]); });
    $("upload-btn").addEventListener("click", uploadChapter);
    $("target-lang").addEventListener("change", savePreferences);
}

function handleFile(file) {
    if (!file.name.toLowerCase().endsWith(".zip")) { showToast("Please select a .zip file", "error"); return; }
    selectedFile = file;
    $("upload-error").classList.add("hidden");
    $("file-name").textContent = file.name;
    $("file-size").textContent = formatSize(file.size);
    $("file-info").classList.remove("hidden");
    $("upload-btn").classList.remove("hidden");
}

async function uploadChapter() {
    if (!selectedFile) return;
    const btn = $("upload-btn");
    btn.disabled = true;
    btn.querySelector("span").textContent = "Uploading...";
    savePreferences();
    const fd = new FormData();
    fd.append("file", selectedFile);
    fd.append("target_lang", $("target-lang").value);
    try {
        const res = await fetch("/web/upload", { method: "POST", body: fd });
        if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error(e.detail || "Upload failed"); }
        const data = await res.json();
        state.chapterId = data.chapter_id;
        state.chapterName = selectedFile.name.replace(/\.zip$/i, "");
        state.pages = data.pages;
        enterReader();
        loadLibrary();
    } catch (e) {
        $("upload-error").textContent = e.message;
        $("upload-error").classList.remove("hidden");
        btn.disabled = false;
        btn.querySelector("span").textContent = "Start";
    }
}

// ════════ Preferences ════════
function loadPreferences() {
    fetch("/web/settings").then((r) => r.json()).then((d) => {
        if (d.target_lang) $("target-lang").value = d.target_lang;
    }).catch(() => {});
}

function savePreferences() {
    fetch("/web/settings", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ target_lang: $("target-lang").value }),
    }).catch(() => {});
}

// ════════ Reader ════════
function resumeChapter(ch) {
    state.chapterId = ch.chapter_id;
    state.chapterName = ch.name || "Chapter";
    state.pages = ch.pages || [];
    enterReader();
}

function enterReader() {
    $("chapter-title").textContent = state.chapterName;
    showView("reader-view");
    renderThumbnails();
    loadPage(0);
    loadStats();
    zoomReset();
}

function renderThumbnails() {
    const list = $("thumb-list");
    list.innerHTML = "";
    state.pages.forEach((name, idx) => {
        const t = document.createElement("div");
        t.className = "thumb";
        t.dataset.index = idx;
        t.innerHTML = `<img src="/web/${state.chapterId}/page/${idx}/image/original" alt="${name}" loading="lazy"><span class="thumb-num">${idx + 1}</span><span class="thumb-status pending" title="Not processed"></span>`;
        t.addEventListener("click", () => loadPage(idx));
        list.appendChild(t);
    });
}

function updateThumbStatus(idx, status) {
    const thumb = document.querySelector(`.thumb[data-index="${idx}"]`);
    if (!thumb) return;
    const badge = thumb.querySelector(".thumb-status");
    if (!badge) return;
    badge.className = `thumb-status ${status}`;
    const titles = { pending: "Not processed", processing: "Processing...", done: "Processed", partial: "Partial — see failures", error: "Error" };
    badge.title = titles[status] || status;
}

function updateActiveThumb() {
    document.querySelectorAll(".thumb").forEach((t) => t.classList.toggle("active", parseInt(t.dataset.index) === state.currentPage));
    const a = document.querySelector(".thumb.active");
    if (a) a.scrollIntoView({ block: "nearest", behavior: "smooth" });
}

async function loadPage(index) {
    if (index < 0 || index >= state.pages.length) return;
    state.currentPage = index;
    state.editingBlock = null;
    updateActiveThumb();
    $("page-info").textContent = `${index + 1} / ${state.pages.length}`;
    $("prev-page").disabled = index <= 0;
    $("next-page").disabled = index >= state.pages.length - 1;
    renderBlocks(null);
    clearBoxOverlay();
    state.mode = "original";
    document.querySelectorAll(".mode-btn[data-mode]").forEach((b) => b.classList.toggle("active", b.dataset.mode === "original"));
    updateImage();

    // Check if page is already processed (no auto-processing)
    try {
        const res = await fetch(`/web/${state.chapterId}/page/${index}`);
        const data = await res.json();
        if (data.processed === false) {
            updateThumbStatus(index, "pending");
            renderBlocks(null);
            $("blocks-list").innerHTML = `<p class="blocks-empty">${escapeHtml(data.errorMessage || "Click Detect to find text")}</p>`;
            return;
        }
        state.pageData = data;
        updateThumbStatus(index, data.failures && data.failures.length ? "partial" : "done");
        renderBlocks(data);
        renderFailures(data);
        if (state.mode === "original" && state.showBoxes) drawBoxOverlay(data);
    } catch (e) { showToast(e.message, "error"); }
}

// ════════ Auto-Translate single page (full pipeline) ════════
async function autoTranslateCurrentPage() {
    if (state.currentPage < 0 || !state.chapterId) return;
    const btn = $("auto-translate-btn");
    btn.disabled = true;
    btn.querySelector("span").textContent = "Processing...";
    updateThumbStatus(state.currentPage, "processing");
    showLoading("Detecting + OCR + cleaning + translating...");
    try {
        const inpaintMode = $("set-inpaint-mode") ? $("set-inpaint-mode").value : "QUALITY";
        const res = await fetch(`/web/${state.chapterId}/page/${state.currentPage}/auto?mode=${inpaintMode}`, { method: "POST" });
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            throw new Error(err.detail || "Auto-translate failed");
        }
        const data = await res.json();
        state.pageData = data;
        updateThumbStatus(state.currentPage, data.failures && data.failures.length ? "partial" : "done");
        renderBlocks(data);
        renderFailures(data);
        const hasTranslations = (data.blocks || []).some((b) => b.translation && b.translation.trim());
        if (hasTranslations) {
            switchMode("rendered");
        } else if (state.showBoxes) {
            drawBoxOverlay(data);
        }
        updateImage();
        reportStageOutcome(data, "Auto-translate");
        loadStats();
    } catch (e) {
        updateThumbStatus(state.currentPage, "error");
        showToast(e.message, "error");
    } finally {
        btn.disabled = false;
        btn.querySelector("span").textContent = "Auto-Translate";
        hideLoading();
    }
}

// ════════ Stage actions: Detect / Clean / Translate ════════
async function stageAction(stage, opts = {}) {
    if (state.currentPage < 0 || !state.chapterId) return;
    const loadingMsgs = { detect: "Detecting + OCR...", inpaint: "Cleaning text...", translate: "Translating..." };
    showLoading(loadingMsgs[stage] || "Processing...");
    updateThumbStatus(state.currentPage, "processing");
    try {
        let url = `/web/${state.chapterId}/page/${state.currentPage}/${stage}`;
        if (stage === "inpaint") {
            const mode = $("set-inpaint-mode") ? $("set-inpaint-mode").value : "QUALITY";
            url += `?mode=${mode}`;
        }
        const res = await fetch(url, { method: "POST" });
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            throw new Error(err.detail || `${stage} failed`);
        }
        const data = await res.json();
        state.pageData = data;
        const hasFailures = data.failures && data.failures.length;
        updateThumbStatus(state.currentPage, hasFailures ? "partial" : "done");
        renderBlocks(data);
        renderFailures(data);
        // Stage-aware view switch.
        if (stage === "detect" && state.showBoxes) {
            switchMode("original");
            drawBoxOverlay(data);
        } else if (stage === "inpaint") {
            switchMode("cleaned");
        } else if (stage === "translate") {
            const hasTranslations = (data.blocks || []).some((b) => b.translation && b.translation.trim());
            if (hasTranslations) switchMode("rendered");
        }
        updateImage();
        reportStageOutcome(data, stage.charAt(0).toUpperCase() + stage.slice(1));
        loadStats();
    } catch (e) {
        updateThumbStatus(state.currentPage, "error");
        showToast(e.message, "error");
    } finally {
        hideLoading();
    }
}

function reportStageOutcome(data, label) {
    // Never show a success toast for a stage that failed or was partial.
    const failures = data.failures || [];
    if (failures.length) {
        const reasons = failures.map((f) => `${f.stage}: ${f.reason}`).join("; ");
        showToast(`${label} PARTIAL — ${failures.length} failure(s): ${reasons}`, "error");
        return;
    }
    const engine = data.inpaint_engine ? ` (${data.inpaint_engine})` : "";
    showToast(`${label} complete${engine}`, "success");
}

function renderFailures(data) {
    const banner = $("failure-banner");
    if (!banner) return;
    const failures = (data && data.failures) || [];
    if (!failures.length) {
        banner.classList.add("hidden");
        banner.innerHTML = "";
        return;
    }
    banner.classList.remove("hidden");
    banner.innerHTML = `<strong>${failures.length} issue(s):</strong> ` +
        failures.map((f) => `<span class="fail-item">${escapeHtml(f.stage)}: ${escapeHtml(f.reason)}</span>`).join("");
}

function switchMode(mode) {
    state.mode = mode;
    document.querySelectorAll(".mode-btn[data-mode]").forEach((b) => b.classList.toggle("active", b.dataset.mode === mode));
    if (mode !== "original") clearBoxOverlay();
}

function updateImage() {
    const img = $("main-image");
    if (state.currentPage < 0 || !state.chapterId) return;
    const mode = state.mode;
    img.onerror = () => {
        if (mode !== "original") {
            showToast(`${mode.charAt(0).toUpperCase() + mode.slice(1)} image is missing. Reprocess this page.`, "error");
        }
    };
    img.src = `/web/${state.chapterId}/page/${state.currentPage}/image/${mode}?t=${Date.now()}`;
}

// ════════ Blocks ════════
function renderBlocks(data) {
    const list = $("blocks-list");
    if (!data || !data.blocks || !data.blocks.length) {
        list.innerHTML = '<p class="blocks-empty">No text blocks detected</p>';
        $("block-count").textContent = "0";
        return;
    }
    $("block-count").textContent = data.blocks.length;
    list.innerHTML = "";
    data.blocks.forEach((block) => {
        const card = document.createElement("div");
        card.className = "block-card";
        card.dataset.index = block.index;
        const c = LABEL_COLORS[block.label] || "#ef4444";
        card.innerHTML = `
            <div class="block-card-header">
                <span class="block-index">#${block.index}</span>
                <span class="block-label" style="color:${c}">${LABEL_NAMES[block.label] || "?"}</span>
                <button class="block-edit-btn" title="Edit"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></button>
            </div>
            <div class="block-src">${escapeHtml(block.text || "(empty)")}</div>
            <div class="block-arrow">\u2193</div>
            <div class="block-trans">${escapeHtml(block.translation || "(not translated — click Translate)")}</div>`;
        card.addEventListener("mouseenter", () => highlightBox(block.index));
        card.addEventListener("mouseleave", () => highlightBox(null));
        card.querySelector(".block-edit-btn").addEventListener("click", (e) => {
            e.stopPropagation();
            toggleBlockEditor(card, block);
        });
        list.appendChild(card);
    });
}

function toggleBlockEditor(card, block) {
    if (card.querySelector(".block-editor")) {
        renderBlocks(state.pageData);
        return;
    }
    const editor = document.createElement("div");
    editor.className = "block-editor";
    editor.innerHTML = `
        <div class="editor-field">
            <label>OCR Text</label>
            <textarea class="edit-text" rows="2">${escapeHtml(block.text || "")}</textarea>
        </div>
        <div class="editor-field">
            <label>Translation</label>
            <textarea class="edit-trans" rows="2">${escapeHtml(block.translation || "")}</textarea>
        </div>
        <div class="editor-actions">
            <button class="btn-action secondary btn-sm editor-cancel">Cancel</button>
            <button class="btn-primary btn-sm editor-save">Save & Re-render</button>
        </div>`;
    card.appendChild(editor);
    card.querySelector(".editor-cancel").addEventListener("click", () => renderBlocks(state.pageData));
    card.querySelector(".editor-save").addEventListener("click", async () => {
        const text = card.querySelector(".edit-text").value;
        const trans = card.querySelector(".edit-trans").value;
        const saveBtn = card.querySelector(".editor-save");
        saveBtn.disabled = true;
        saveBtn.textContent = "Saving...";
        try {
            const res = await fetch(`/web/${state.chapterId}/page/${state.currentPage}/block/${block.index}`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ text, translation: trans }),
            });
            if (!res.ok) throw new Error("Save failed");
            const data = await res.json();
            state.pageData = data;
            renderBlocks(data);
            if (state.mode === "rendered") updateImage();
            showToast("Block updated", "success");
            loadStats();
        } catch (e) {
            showToast(e.message, "error");
            saveBtn.disabled = false;
            saveBtn.textContent = "Save & Re-render";
        }
    });
}

// ════════ Box Overlay ════════
function clearBoxOverlay() { $("box-overlay").innerHTML = ""; }

function drawBoxOverlay(data) {
    clearBoxOverlay();
    if (!data || !data.blocks) return;
    const img = $("main-image");
    const ov = $("box-overlay");
    function draw() {
        if (!img.naturalWidth) return;
        const sx = img.clientWidth / img.naturalWidth;
        const sy = img.clientHeight / img.naturalHeight;
        const ox = (img.parentElement.clientWidth - img.clientWidth) / 2;
        const oy = (img.parentElement.clientHeight - img.clientHeight) / 2;
        ov.style.left = ox + "px"; ov.style.top = oy + "px";
        ov.style.width = img.clientWidth + "px"; ov.style.height = img.clientHeight + "px";
        ov.innerHTML = "";
        data.blocks.forEach((b) => {
            const bb = b.bbox; const c = LABEL_COLORS[b.label] || "#ef4444";
            const box = document.createElement("div");
            box.className = "overlay-box"; box.dataset.index = b.index;
            box.style.left = (bb.x1*sx)+"px"; box.style.top = (bb.y1*sy)+"px";
            box.style.width = ((bb.x2-bb.x1)*sx)+"px"; box.style.height = ((bb.y2-bb.y1)*sy)+"px";
            box.style.borderColor = c;
            const lb = document.createElement("span");
            lb.className = "box-label"; lb.textContent = "#"+b.index; lb.style.background = c;
            box.appendChild(lb);
            box.addEventListener("mouseenter", () => highlightBlockCard(b.index));
            box.addEventListener("mouseleave", () => highlightBlockCard(null));
            ov.appendChild(box);
        });
    }
    if (img.complete && img.naturalWidth) draw();
    else img.addEventListener("load", draw, { once: true });
    window.addEventListener("resize", () => { if (state.pageData && state.showBoxes) drawBoxOverlay(state.pageData); }, { passive: true });
}

function highlightBox(i) {
    document.querySelectorAll(".block-card").forEach((c) => c.classList.toggle("highlighted", parseInt(c.dataset.index) === i));
    const card = document.querySelector(`.block-card[data-index="${i}"]`);
    if (card) card.scrollIntoView({ block: "nearest", behavior: "smooth" });
}

function highlightBlockCard(i) {
    document.querySelectorAll(".overlay-box").forEach((b) => b.classList.toggle("highlighted", parseInt(b.dataset.index) === i));
    document.querySelectorAll(".block-card").forEach((c) => c.classList.remove("highlighted"));
    const card = document.querySelector(`.block-card[data-index="${i}"]`);
    if (card) card.classList.add("highlighted");
}

// ════════ Mode / Zoom / Nav ════════
function initModeBar() {
    document.querySelectorAll(".mode-btn[data-mode]").forEach((btn) => {
        btn.addEventListener("click", () => {
            document.querySelectorAll(".mode-btn[data-mode]").forEach((b) => b.classList.remove("active"));
            btn.classList.add("active");
            state.mode = btn.dataset.mode;
            updateImage();
            const img = $("main-image");
            img.addEventListener("load", () => {
                if (state.mode === "original" && state.showBoxes && state.pageData) {
                    drawBoxOverlay(state.pageData);
                } else {
                    clearBoxOverlay();
                }
            }, { once: true });
            if (state.mode !== "original") clearBoxOverlay();
        });
    });
    $("toggle-boxes").addEventListener("click", () => {
        state.showBoxes = !state.showBoxes;
        $("toggle-boxes").classList.toggle("active", state.showBoxes);
        if (state.showBoxes && state.mode === "original" && state.pageData) drawBoxOverlay(state.pageData); else clearBoxOverlay();
    });
    $("zoom-in").addEventListener("click", () => zoomAdjust(1));
    $("zoom-out").addEventListener("click", () => zoomAdjust(-1));
    $("zoom-level").addEventListener("click", zoomReset);
}

function zoomAdjust(d) { state.zoomIndex = Math.max(0, Math.min(state.zoomLevels.length-1, state.zoomIndex+d)); applyZoom(); }
function zoomReset() { state.zoomIndex = 0; applyZoom(); }
function applyZoom() {
    const img = $("main-image"); const l = state.zoomLevels[state.zoomIndex];
    if (l === 0) { img.style.maxWidth="100%"; img.style.maxHeight="100%"; img.style.width=""; img.style.height=""; $("zoom-level").textContent="Fit"; }
    else { img.style.maxWidth="none"; img.style.maxHeight="none"; img.style.width=(img.naturalWidth*l)+"px"; img.style.height=""; $("zoom-level").textContent=Math.round(l*100)+"%"; }
    if (state.showBoxes && state.pageData) setTimeout(() => drawBoxOverlay(state.pageData), 50);
}

function initNavigation() {
    $("prev-page").addEventListener("click", () => loadPage(state.currentPage - 1));
    $("next-page").addEventListener("click", () => loadPage(state.currentPage + 1));
    $("back-btn").addEventListener("click", () => {
        if (state.batchPollTimer) clearTimeout(state.batchPollTimer);
        state.batchRunning = false;
        state.chapterId = null; state.pages = []; state.currentPage = -1; state.pageData = null;
        selectedFile = null;
        $("batch-progress").classList.add("hidden");
        $("stats-bar").classList.add("hidden");
        $("file-info").classList.add("hidden");
        $("upload-btn").classList.add("hidden");
        $("file-input").value = "";
        zoomReset();
        showView("library-view");
        loadLibrary();
        checkHealth();
    });
    // Panel collapse toggles
    $("toggle-thumb-panel").addEventListener("click", () => {
        $("thumb-panel").classList.add("collapsed");
        $("show-thumb-panel").classList.remove("hidden");
    });
    $("show-thumb-panel").addEventListener("click", () => {
        $("thumb-panel").classList.remove("collapsed");
        $("show-thumb-panel").classList.add("hidden");
    });
    $("toggle-blocks-panel").addEventListener("click", () => {
        $("blocks-panel").classList.add("collapsed");
        $("show-blocks-panel").classList.remove("hidden");
    });
    $("show-blocks-panel").addEventListener("click", () => {
        $("blocks-panel").classList.remove("collapsed");
        $("show-blocks-panel").classList.add("hidden");
    });
    document.addEventListener("keydown", (e) => {
        if (!$("reader-view").classList.contains("active")) return;
        if (e.target.tagName === "INPUT" || e.target.tagName === "SELECT" || e.target.tagName === "TEXTAREA") return;
        if (e.key === "ArrowLeft") loadPage(state.currentPage - 1);
        if (e.key === "ArrowRight") loadPage(state.currentPage + 1);
        if (e.key === "=" || e.key === "+") zoomAdjust(1);
        if (e.key === "-") zoomAdjust(-1);
        if (e.key === "0") zoomReset();
        if (e.key === "b" || e.key === "B") $("toggle-boxes").click();
        if (e.key === "1") document.querySelector('.mode-btn[data-mode="original"]').click();
        if (e.key === "2") document.querySelector('.mode-btn[data-mode="cleaned"]').click();
        if (e.key === "3") document.querySelector('.mode-btn[data-mode="rendered"]').click();
        if (e.key === "t" || e.key === "T") stageAction("translate");
        if (e.key === "c" || e.key === "C") stageAction("inpaint");
        if (e.key === "d" || e.key === "D") stageAction("detect");
        if (e.key === "a" || e.key === "A") autoTranslateCurrentPage();
    });
}

// ════════ Batch ════════
function initBatch() {
    $("batch-btn").addEventListener("click", startBatch);
    $("batch-cancel").addEventListener("click", cancelBatch);
    $("export-btn").addEventListener("click", exportChapter);
    $("auto-translate-btn").addEventListener("click", autoTranslateCurrentPage);
    $("detect-btn").addEventListener("click", () => stageAction("detect"));
    $("clean-btn").addEventListener("click", () => stageAction("inpaint"));
    $("translate-btn").addEventListener("click", () => stageAction("translate"));
    $("reader-settings-btn").addEventListener("click", openSettings);
}

async function startBatch() {
    if (!state.chapterId || state.batchRunning) return;
    if (state.pages.length > 10 && !confirm(`Process + translate all ${state.pages.length} pages?`)) return;
    state.batchRunning = true;
    $("batch-btn").disabled = true;
    $("batch-btn").querySelector("span").textContent = "Processing...";
    $("batch-progress").classList.remove("hidden");
    try {
        await fetch(`/web/${state.chapterId}/batch?translate=true`, { method: "POST" });
        pollBatch();
    } catch (e) { showToast("Batch failed: " + e.message, "error"); resetBatchUI(); }
}

async function pollBatch() {
    if (!state.chapterId) return;
    try {
        const res = await fetch(`/web/${state.chapterId}/batch/status`);
        const s = await res.json();
        const total = s.total || 1; const pct = Math.round(((s.processed||0) / total) * 100);
        $("batch-progress-text").textContent = `${s.processed||0} / ${total}`;
        $("batch-progress-pct").textContent = pct + "%";
        $("batch-progress-fill").style.width = pct + "%";
        document.querySelectorAll(".thumb").forEach((t) => {
            const i = parseInt(t.dataset.index);
            if (s.in_progress && s.in_progress.includes(i)) updateThumbStatus(i, "processing");
            else if (s.failed && s.failed.includes(i)) updateThumbStatus(i, "error");
            else if (i < (s.processed||0) || i < (s.cached||0)) updateThumbStatus(i, "done");
        });
        if (s.running && !s.cancelled) { state.batchPollTimer = setTimeout(pollBatch, 1000); }
        else {
            state.batchRunning = false;
            if (s.done && !s.cancelled) { showToast(`Done: ${s.processed}/${s.total}`, "success"); loadStats(); }
            resetBatchUI();
        }
    } catch { state.batchPollTimer = setTimeout(pollBatch, 2000); }
}

function resetBatchUI() {
    $("batch-btn").disabled = false;
    $("batch-btn").querySelector("span").textContent = "Translate All";
    setTimeout(() => $("batch-progress").classList.add("hidden"), 2000);
}

async function cancelBatch() {
    if (!state.chapterId) return;
    try { await fetch(`/web/${state.chapterId}/batch/cancel`, { method: "POST" }); showToast("Cancelled", ""); } catch {}
}

async function exportChapter() {
    if (!state.chapterId) return;
    const t = state.mode === "original" ? "rendered" : state.mode;
    showToast("Preparing export...", "");
    window.location.href = `/web/${state.chapterId}/export?type=${t}`;
}

// ════════ Stats ════════
async function loadStats() {
    if (!state.chapterId) return;
    try {
        const res = await fetch(`/web/${state.chapterId}/stats`);
        const s = await res.json();
        $("stat-pages").textContent = s.processed_pages + "/" + s.total_pages;
        $("stat-blocks").textContent = s.total_blocks;
        $("stat-translated").textContent = s.translated_blocks;
        $("stats-bar").classList.remove("hidden");
    } catch {}
}

// ════════ Settings Modal ════════
function initSettings() {
    $("settings-close").addEventListener("click", closeSettings);
    $("settings-cancel").addEventListener("click", closeSettings);
    $("settings-save").addEventListener("click", saveSettings);
    $("set-engine").addEventListener("change", (e) => {
        updateTranslatorFields(e.target.value);
        $("test-connection-btn").classList.toggle("hidden", e.target.value === "none");
    });
    $("settings-modal").addEventListener("click", (e) => {
        if (e.target === $("settings-modal")) closeSettings();
    });
    // Tab switching
    document.querySelectorAll(".tab-btn").forEach((btn) => {
        btn.addEventListener("click", () => {
            document.querySelectorAll(".tab-btn").forEach((b) => b.classList.remove("active"));
            document.querySelectorAll(".tab-content").forEach((c) => c.classList.remove("active"));
            btn.classList.add("active");
            document.querySelector(`.tab-content[data-tab="${btn.dataset.tab}"]`).classList.add("active");
        });
    });
    // Model selection — apply immediately on change
    ["detector", "ocr", "inpaint"].forEach((stage) => {
        $(`set-${stage}-model`).addEventListener("change", async (e) => {
            const modelId = e.target.value;
            if (!modelId) return;
            $(`status-${stage}`).className = "conn-status pending";
            try {
                const res = await fetch("/web/models", {
                    method: "PUT",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ stage, model_id: modelId }),
                });
                if (res.ok) {
                    $(`status-${stage}`).className = "conn-status ok";
                    showToast(`${stage} model updated`, "success");
                    checkHealth();
                } else {
                    $(`status-${stage}`).className = "conn-status fail";
                    showToast(`Failed to set ${stage} model`, "error");
                }
            } catch {
                $(`status-${stage}`).className = "conn-status fail";
                showToast("Failed", "error");
            }
        });
    });
    // Fetch models from LLM server
    $("fetch-models-btn").addEventListener("click", fetchLLMModels);
    // Test connection
    $("test-connection-btn").addEventListener("click", testConnection);
}

async function fetchLLMModels() {
    const engine = $("set-engine").value;
    const apiKey = $("set-api-key").value.trim();
    const baseUrl = $("set-base-url").value.trim();
    if (engine === "lmstudio" || engine === "openai_compatible") {
        if (!baseUrl) { showToast("Enter Base URL first", "error"); return; }
    }
    if (["gemini", "openrouter", "deepseek"].includes(engine) && !apiKey) { showToast("Enter API key first", "error"); return; }
    const btn = $("fetch-models-btn");
    btn.disabled = true;
    btn.textContent = "Fetching...";
    try {
        const res = await fetch("/web/llm/models", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ engine, api_key: apiKey, base_url: baseUrl }),
        });
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            throw new Error(err.detail || "Fetch failed");
        }
        const data = await res.json();
        const models = data.models || [];
        const list = $("llm-model-list");
        list.innerHTML = models.map((m) => `<option value="${m}">`).join("");
        showToast(`Found ${models.length} models`, "success");
        $("set-model-name").focus();
    } catch (e) {
        showToast(e.message, "error");
    } finally {
        btn.disabled = false;
        btn.textContent = "Fetch";
    }
}

async function testConnection() {
    const engine = $("set-engine").value;
    if (engine === "none") return;
    const btn = $("test-connection-btn");
    btn.disabled = true;
    const origText = btn.textContent;
    btn.textContent = "Testing...";
    const result = $("llm-test-result");
    result.classList.remove("hidden");
    result.className = "llm-test-result testing";
    result.textContent = "Sending test translation...";
    const config = buildTranslatorConfig();
    try {
        const res = await fetch("/web/llm/test", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(config),
        });
        const data = await res.json();
        if (data.ok) {
            result.className = "llm-test-result success";
            result.innerHTML = `<span class="result-icon">✓</span> Success — "hello" → "${escapeHtml(data.translation)}" (${data.latency_ms}ms)`;
        } else {
            result.className = "llm-test-result error";
            result.innerHTML = `<span class="result-icon">✗</span> ${escapeHtml(data.error || "Translation returned placeholder — check config")}`;
        }
    } catch (e) {
        result.className = "llm-test-result error";
        result.innerHTML = `<span class="result-icon">✗</span> ${escapeHtml(e.message)}`;
    } finally {
        btn.disabled = false;
        btn.textContent = origText;
    }
}

function buildTranslatorConfig() {
    const engine = $("set-engine").value;
    return {
        engine,
        base_url: $("set-base-url").value,
        api_key: $("set-api-key").value,
        model: $("set-model-name").value,
        temperature: parseFloat($("set-temp").value) || 0.3,
        max_output_tokens: parseInt($("set-max-tokens").value) || 8192,
    };
}

function updateTranslatorFields(engine) {
    const fields = $("set-translator-fields");
    const testBtn = $("test-connection-btn");
    if (engine === "none") {
        fields.classList.add("hidden");
        testBtn.classList.add("hidden");
        $("llm-test-result").classList.add("hidden");
        return;
    }
    fields.classList.remove("hidden");
    testBtn.classList.remove("hidden");
    document.querySelectorAll(".translator-field").forEach((el) => {
        const engines = el.dataset.engines.split(",");
        el.style.display = engines.includes(engine) ? "" : "none";
    });
}

async function openSettings() {
    await checkHealth();
    const models = (state.health || {}).models || {};
    $("settings-models-status").innerHTML = Object.entries(models).map(([k, v]) => {
        const ph = v.includes("placeholder");
        return `<div class="model-row"><span class="model-row-name">${k}</span><span class="model-row-value"><span class="model-status ${ph ? "fallback" : "live"}"></span>${v}</span></div>`;
    }).join("") || "<p>No models loaded</p>";

    // Load available models for selection
    try {
        const res = await fetch("/web/models");
        const data = await res.json();
        const available = data.available || {};
        const selected = data.selected || {};
        ["detector", "ocr", "inpaint"].forEach((stage) => {
            const sel = $(`set-${stage}-model`);
            const current = selected[stage];
            sel.innerHTML = '<option value="">— Select —</option>' +
                (available[stage] || []).map((m) =>
                    `<option value="${m.id}" ${m.loaded ? "selected" : ""}>${m.name}${m.loaded ? " ✓" : ""}</option>`
                ).join("");
            $(`status-${stage}`).className = (available[stage] || []).some((m) => m.loaded) ? "conn-status ok" : "conn-status fail";
        });
    } catch {}

    // Load translator config
    try {
        const res = await fetch("/web/settings");
        const d = await res.json();
        const tc = d.translator || {};
        $("set-engine").value = tc.engine || "none";
        $("set-base-url").value = tc.base_url || "";
        $("set-api-key").value = tc.api_key || "";
        $("set-model-name").value = tc.model || "";
        $("set-temp").value = tc.temperature || 0.3;
        $("set-max-tokens").value = tc.max_output_tokens || 8192;
        updateTranslatorFields(tc.engine || "none");
    } catch {}

    $("llm-test-result").classList.add("hidden");
    $("settings-modal").classList.remove("hidden");
}

function closeSettings() { $("settings-modal").classList.add("hidden"); }

async function saveSettings() {
    const config = { translator: buildTranslatorConfig() };
    try {
        await fetch("/web/settings", {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(config),
        });
        showToast("Settings saved", "success");
        closeSettings();
        checkHealth();
    } catch { showToast("Failed to save", "error"); }
}

// ════════ Init ════════
document.addEventListener("DOMContentLoaded", () => {
    initLibrary();
    initUpload();
    initModeBar();
    initNavigation();
    initBatch();
    initSettings();
    $("toggle-boxes").classList.add("active");
    loadPreferences();
    checkHealth();
    loadLibrary();
});
