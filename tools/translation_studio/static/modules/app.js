/* Load-order module: app.js. Shared classic-script scope, no build step. */
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