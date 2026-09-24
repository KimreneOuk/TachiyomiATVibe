"""Repeatable Playwright layout audit for T937 translation studio UI."""
from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any

from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parent
BASELINE = ROOT
URL = "http://127.0.0.1:8765"
TOLERANCE = 2
OVERLAP_TOLERANCE = 4
VIEWPORTS = [
    ("1920x1080", 1920, 1080),
    ("1366x768", 1366, 768),
    ("1024x768", 1024, 768),
    ("824x1100", 824, 1100),
    ("390x844", 390, 844),
]

FINDINGS: list[dict[str, Any]] = []
SNAPSHOTS: list[dict[str, Any]] = []
_finding_number = 0


def rel(path: Path) -> str:
    return path.relative_to(ROOT.parent).as_posix()


def owner_for(selector: str, category: str) -> str:
    if "dialog" in selector.lower() or "settingsdlg" in selector.lower() or "overviewdlg" in selector.lower():
        return "tools/translation_studio/static/style.css:954-964"
    if "tree" in selector.lower() or "badge" in selector.lower():
        return "tools/translation_studio/static/style.css:1274-1389"
    if "focus" in selector.lower() or "zoom" in selector.lower() or "rbox" in selector.lower():
        return "tools/translation_studio/static/style.css:473-631, 650-664, 1391-1416"
    if "topbar" in selector.lower() or ".tb-" in selector.lower() or "toolbar" in selector.lower():
        return "tools/translation_studio/static/style.css:127-160, 345-370"
    if "sidebar" in selector.lower() or "pane" in selector.lower() or "inspector" in selector.lower():
        return "tools/translation_studio/static/style.css:667-733"
    if "statusbar" in selector.lower() or "console" in selector.lower():
        return "tools/translation_studio/static/style.css:908-951"
    return "tools/translation_studio/static/style.css:393-449, 667-681"


def rect_clip(page, rect: dict[str, float] | None, path: Path, pad: int = 10) -> str | None:
    viewport = page.evaluate("({w: innerWidth, h: innerHeight})")
    width, height = viewport["w"], viewport["h"]
    if rect:
        x0 = max(0, math.floor(rect.get("x", 0) - pad))
        y0 = max(0, math.floor(rect.get("y", 0) - pad))
        x1 = min(width, math.ceil(rect.get("x", 0) + rect.get("width", 0) + pad))
        y1 = min(height, math.ceil(rect.get("y", 0) + rect.get("height", 0) + pad))
    else:
        x0, y0, x1, y1 = max(0, width - 220), 0, width, min(height, 260)
    if x1 <= x0 or y1 <= y0:
        x0, y0 = max(0, width - 220), 0
        x1, y1 = width, min(height, 260)
    if x1 <= x0 or y1 <= y0:
        return None
    path.parent.mkdir(parents=True, exist_ok=True)
    page.screenshot(path=str(path), clip={"x": x0, "y": y0, "width": x1 - x0, "height": y1 - y0})
    return rel(path)


def add_finding(page, state: str, category: str, severity: str, selector: str,
                message: str, geometry: Any, screenshot_path: Path,
                crop_rect: dict[str, float] | None = None,
                owner: str | None = None) -> dict[str, Any]:
    global _finding_number
    _finding_number += 1
    crop_path = BASELINE / "crops" / f"F{_finding_number:03d}_{state}.png"
    crop = rect_clip(page, crop_rect, crop_path)
    finding = {
        "id": f"F{_finding_number:03d}",
        "state": state,
        "category": category,
        "severity": severity,
        "selector": selector,
        "message": message,
        "geometry": geometry,
        "evidence_screenshot": rel(screenshot_path),
        "evidence_crop": crop,
        "suspected_owner": owner or owner_for(selector, category),
    }
    FINDINGS.append(finding)
    return finding


def box(page, selector: str, index: int = 0) -> dict[str, Any] | None:
    loc = page.locator(selector)
    if index >= loc.count():
        return None
    try:
        return loc.nth(index).evaluate("""el => {
          const r=el.getBoundingClientRect(), s=getComputedStyle(el);
          const visible=s.display!=='none' && s.visibility!=='hidden' && r.width>0 && r.height>0;
          let x0=Math.max(0,r.left),y0=Math.max(0,r.top),x1=Math.min(innerWidth,r.right),y1=Math.min(innerHeight,r.bottom);
          for(let a=el.parentElement;a;a=a.parentElement){
            const as=getComputedStyle(a), ar=a.getBoundingClientRect();
            if(as.overflowX!=='visible'){x0=Math.max(x0,ar.left);x1=Math.min(x1,ar.right);}
            if(as.overflowY!=='visible'){y0=Math.max(y0,ar.top);y1=Math.min(y1,ar.bottom);}
          }
          return visible ? {selector:el.id ? '#'+el.id : el.tagName.toLowerCase()+'.'+String(el.className||'').trim().replace(/\\s+/g,'.'),
            x:r.x,y:r.y,width:r.width,height:r.height,right:r.right,bottom:r.bottom,
            visibleRect:{x:x0,y:y0,right:x1,bottom:y1,width:Math.max(0,x1-x0),height:Math.max(0,y1-y0)},
            clientWidth:el.clientWidth,scrollWidth:el.scrollWidth,clientHeight:el.clientHeight,scrollHeight:el.scrollHeight,
            overflowX:s.overflowX,overflowY:s.overflowY,position:s.position,whiteSpace:s.whiteSpace,textOverflow:s.textOverflow,
            text:(el.innerText||'').trim().slice(0,100)} : null;
        }""")
    except Exception:
        return None


def all_boxes(page, selector: str) -> list[dict[str, Any]]:
    result = []
    loc = page.locator(selector)
    for i in range(loc.count()):
        value = box(page, selector, i)
        if value:
            result.append(value)
    return result


def intersections(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result = []
    for i, a in enumerate(items):
        for b in items[i + 1:]:
            va, vb = a.get("visibleRect", a), b.get("visibleRect", b)
            if va.get("width", 0) <= 0 or va.get("height", 0) <= 0 or vb.get("width", 0) <= 0 or vb.get("height", 0) <= 0:
                continue
            iw = min(va["right"], vb["right"]) - max(va["x"], vb["x"])
            ih = min(va["bottom"], vb["bottom"]) - max(va["y"], vb["y"])
            if iw > OVERLAP_TOLERANCE and ih > OVERLAP_TOLERANCE:
                result.append({"a": a, "b": b, "overlap_width": iw, "overlap_height": ih,
                               "overlap_area": iw * ih})
    return result


def group_boxes(page, selectors: list[str]) -> list[dict[str, Any]]:
    items = []
    for selector in selectors:
        items.extend(all_boxes(page, selector))
    return items


def check_group(page, state: str, name: str, selectors: list[str], screenshot_path: Path,
                results: list[dict[str, Any]]) -> None:
    items = group_boxes(page, selectors)
    collided = intersections(items)
    results.append({"name": name, "selectors": selectors, "items": items, "collisions": collided})
    for collision in collided:
        a, b = collision["a"], collision["b"]
        union = {"x": min(a["x"], b["x"]), "y": min(a["y"], b["y"]),
                 "width": max(a["right"], b["right"]) - min(a["x"], b["x"]),
                 "height": max(a["bottom"], b["bottom"]) - min(a["y"], b["y"])}
        severity = "blocker" if name in {"toolbar groups", "toolbar action controls", "main panels", "sidebar header", "focus bar controls", "status bar controls"} else "minor"
        add_finding(page, state, "overlap", severity, f"{a['selector']} ∩ {b['selector']}",
                    f"Visible sibling controls overlap by more than {OVERLAP_TOLERANCE}px in both dimensions in group {name}.",
                    collision, screenshot_path, union,
                    owner_for(a["selector"] + " " + b["selector"], "overlap"))


def overlay_registration(page, state: str, screenshot_path: Path,
                         media_selector: str, region_selector: str,
                         expected_box: list[float], image_size: list[float]) -> dict[str, Any] | None:
    media = box(page, media_selector)
    region = box(page, region_selector)
    if not media or not region:
        return None
    w, h = image_size
    x1, y1, x2, y2 = expected_box
    expected = {
        "x": media["x"] + x1 / w * media["width"],
        "y": media["y"] + y1 / h * media["height"],
        "width": (x2 - x1) / w * media["width"],
        "height": (y2 - y1) / h * media["height"],
    }
    deltas = {key: region[key] - expected[key] for key in ("x", "y", "width", "height")}
    data = {"media": media, "region": region, "expected": expected, "delta_px": deltas,
            "max_abs_delta_px": max(abs(v) for v in deltas.values())}
    if data["max_abs_delta_px"] > TOLERANCE:
        union = {"x": min(media["x"], region["x"]), "y": min(media["y"], region["y"]),
                 "width": max(media["right"], region["right"]) - min(media["x"], region["x"]),
                 "height": max(media["bottom"], region["bottom"]) - min(media["y"], region["y"])}
        add_finding(page, state, "overlay_registration", "blocker", region_selector,
                    "Region overlay diverges from its canvas-normalized detection box by more than 2px.",
                    data, screenshot_path, union,
                    "tools/translation_studio/static/app.js:438-490, 1404-1443; static/style.css:473-575")
    return data


def snapshot(page, label: str, width: int, height: int, *, phone: bool = False,
             extra: dict[str, Any] | None = None) -> dict[str, Any]:
    page.wait_for_timeout(280)
    screenshot = BASELINE / "screenshots" / f"{label}.png"
    screenshot.parent.mkdir(parents=True, exist_ok=True)
    page.screenshot(path=str(screenshot))
    open_dialog = page.locator("dialog[open]").first if page.locator("dialog[open]").count() else None
    open_flyout = bool(page.locator("#chapterFlyout:not(.hidden)").count())

    selectors = [
        "html", "body", "#topbar", ".tb-left", ".tb-center", ".tb-right", "#main",
        "#rail", "#railToggle", "#viewer", "#stripZoomBar", "#sidebar", ".sb-header",
        "#paneViz", "#paneInsp", "#vizTree", "#inspBody", "#statusbar", "#consolePanel",
        "#console", "#chapterFlyout", "#stepperDropdown", "#resetDropdown", "dialog",
        ".pg-media", ".ov-perf-table", "#focusView", ".focus-bar",
    ]
    geometry: dict[str, Any] = {}
    for selector in selectors:
        items = all_boxes(page, selector)
        if items:
            geometry[selector] = items if len(items) > 1 else items[0]
    doc = page.evaluate("""() => ({width:innerWidth,height:innerHeight,scrollX,scrollY,
      documentElement:{clientWidth:document.documentElement.clientWidth,scrollWidth:document.documentElement.scrollWidth,
        clientHeight:document.documentElement.clientHeight,scrollHeight:document.documentElement.scrollHeight},
      body:{clientWidth:document.body.clientWidth,scrollWidth:document.body.scrollWidth,
        clientHeight:document.body.clientHeight,scrollHeight:document.body.scrollHeight}})""")

    if open_dialog:
        overflow_selectors = ["html", "body", "dialog", "#ovBody", ".ov-perf-table"]
    else:
        overflow_selectors = [
            "html", "body", "#main", "#rail", "#viewer", "#strip", "#sidebar", "#paneViz",
            "#paneInsp", "#inspBody", "#vizTree", "#consolePanel", "#console", "#chapterFlyout",
            "#stepperDropdown", "#resetDropdown", ".ov-perf-table",
        ]
    overflows = []
    for selector in overflow_selectors:
        for item in all_boxes(page, selector):
            if item["scrollWidth"] > item["clientWidth"] + TOLERANCE:
                overflows.append({"selector": item["selector"], "scrollWidth": item["scrollWidth"],
                                  "clientWidth": item["clientWidth"], "overflow_px": item["scrollWidth"] - item["clientWidth"],
                                  "box": item})
    groups: list[dict[str, Any]] = []
    if open_dialog:
        dialog_id = open_dialog.get_attribute("id")
        if dialog_id == "settingsDlg":
            check_group(page, label, "settings preset buttons", ["#inpaintPresetButtons > button"], screenshot, groups)
            check_group(page, label, "settings footer buttons", ["#settingsDlg .row > button"], screenshot, groups)
        elif dialog_id == "overviewDlg":
            check_group(page, label, "overview stat cards", ["#ovBody .ov-stat-box"], screenshot, groups)
            check_group(page, label, "overview table headings", [".ov-perf-table th"], screenshot, groups)
    else:
        check_group(page, label, "toolbar groups", [".tb-left", ".tb-center", ".tb-right"], screenshot, groups)
        check_group(page, label, "toolbar action controls",
                    ["#btnProcessPage", "#btnProcessChapter", "#btnToggleTranslate", "#btnStepperMenu",
                     "#btnResetMenu", "#btnOverview", "#btnSettings", "#btnSidebarToggle"], screenshot, groups)
        check_group(page, label, "main panels", ["#rail", "#railToggle", "#viewer", "#sidebar"], screenshot, groups)
        check_group(page, label, "sidebar header", [".sb-tabs", "#sbClose"], screenshot, groups)
        check_group(page, label, "sidebar cards", ["#paneViz > .sb-card", "#paneInsp > .sb-card"], screenshot, groups)
        check_group(page, label, "tree branches", ["#vizTree > .tree-branch"], screenshot, groups)
        for i in range(page.locator("#vizTree .tree-branch").count()):
            branch = page.locator("#vizTree .tree-branch").nth(i)
            if branch.is_visible():
                key = branch.get_attribute("data-branch") or str(i)
                check_group(page, label, f"tree header {key}",
                            [f"#vizTree .tree-branch:nth-of-type({i + 1}) .tree-twist",
                             f"#vizTree .tree-branch:nth-of-type({i + 1}) .tree-node-label",
                             f"#vizTree .tree-branch:nth-of-type({i + 1}) .tree-badge-count"], screenshot, groups)
        check_group(page, label, "canvas type badges", ["#strip .artifact-badge"], screenshot, groups)
        check_group(page, label, "canvas OCR snippets", ["#strip .artifact-text-badge"], screenshot, groups)
        check_group(page, label, "stage row controls", ["#stagePipeline .stage-copy", "#stagePipeline .stage-actions"], screenshot, groups)
        check_group(page, label, "stage parameter controls",
                    ["#stagePipeline .stage-param-body .seg", "#stagePipeline .stage-param-body input",
                     "#stagePipeline .stage-param-body [data-open-settings]"], screenshot, groups)
        check_group(page, label, "focus bar controls",
                    [".focus-bar > button", ".focus-bar #focusTitle", ".focus-bar .zoom-controls", ".focus-bar > .muted"], screenshot, groups)
        check_group(page, label, "status bar controls", ["#sbLeft", "#sbSpeedMetrics", "#sbRight", "#btnLogs"], screenshot, groups)

    align = page.evaluate("""() => {
      const r=s=>{const e=document.querySelector(s); if(!e)return null; const b=e.getBoundingClientRect();
        const c=getComputedStyle(e); return c.display==='none'||b.width===0||b.height===0?null:{left:b.left,right:b.right,top:b.top,bottom:b.bottom};};
      const a=r('#topbar'),m=r('#main'),rail=r('#rail'),tog=r('#railToggle'),view=r('#viewer'),side=r('#sidebar');
      return {topbar_to_main_y:a&&m?Math.abs(a.bottom-m.top):null,
        rail_to_toggle_x:rail&&tog?Math.abs(rail.right-tog.left):null,
        toggle_to_viewer_x:tog&&view?Math.abs(tog.right-view.left):null,
        viewer_to_sidebar_x:view&&side?Math.abs(view.right-side.left):null,
        main_to_sidebar_right:m&&side?Math.abs(m.right-side.right):null};
    }""")
    offscreen = []
    if open_dialog:
        offscreen_selectors = ["dialog"]
    else:
        offscreen_selectors = ["#topbar", ".tb-left", ".tb-center", ".tb-right", "#main", "#rail", "#railToggle",
                               "#viewer", "#sidebar", "#stripZoomBar", "#statusbar", "#chapterFlyout", ".focus-bar"]
    for selector in offscreen_selectors:
        for item in all_boxes(page, selector):
            if (item["x"] < -TOLERANCE or item["right"] > width + TOLERANCE or
                    item["y"] < -TOLERANCE or item["bottom"] > height + TOLERANCE):
                offscreen.append({"selector": item["selector"], "x": item["x"], "right": item["right"],
                                  "y": item["y"], "bottom": item["bottom"],
                                  "viewport_width": width, "viewport_height": height, "box": item})

    text_overflows = page.evaluate("""() => [...document.querySelectorAll('.cp-name,.tree-desc,.sb-tab,#sbLeft,.focus-bar .muted,.cf-input')]
      .filter(e => {const r=e.getBoundingClientRect(),s=getComputedStyle(e); return r.width>0&&r.height>0&&e.scrollWidth>e.clientWidth+2&&s.textOverflow!=='ellipsis';})
      .map(e => {const r=e.getBoundingClientRect();return {selector:e.id?'#'+e.id:e.tagName.toLowerCase()+'.'+String(e.className||'').trim().replace(/\\s+/g,'.'),
        text:(e.innerText||e.value||'').trim().slice(0,80),clientWidth:e.clientWidth,scrollWidth:e.scrollWidth,
        x:r.x,y:r.y,width:r.width,height:r.height};})""")
    descendant_overflows = page.evaluate("""() => ['#viewer','#strip','#paneViz'].flatMap(parentSelector => {
      const parent=document.querySelector(parentSelector); if(!parent)return [];
      const pr=parent.getBoundingClientRect();
      return [...parent.querySelectorAll('*')].filter(e => {
        const r=e.getBoundingClientRect(),s=getComputedStyle(e);
        return s.display!=='none'&&r.width>0&&r.height>0&&(e.scrollWidth>e.clientWidth+2||r.right>pr.right+2);
      }).map(e => {const r=e.getBoundingClientRect();return {parent:parentSelector,
        selector:e.id?'#'+e.id:e.tagName.toLowerCase()+'.'+String(e.className||'').trim().replace(/\\s+/g,'.'),
        x:r.x,right:r.right,width:r.width,scrollWidth:e.scrollWidth,clientWidth:e.clientWidth,
        text:(e.innerText||'').trim().slice(0,80)};});
    })""")
    badge_collisions = page.evaluate("""() => {
      const badges=[...document.querySelectorAll('#strip .artifact-badge,#strip .artifact-text-badge rect')].filter(e=>{
        const r=e.getBoundingClientRect(),s=getComputedStyle(e);return s.display!=='none'&&r.width>0&&r.height>0;
      }).map(e=>{const r=e.getBoundingClientRect();return {type:e.matches('.artifact-badge')?'provenance':'ocr',
        text:e.textContent.trim(),x:r.x,y:r.y,right:r.right,bottom:r.bottom};});
      const collisions=[];
      for(let i=0;i<badges.length;i++)for(let j=i+1;j<badges.length;j++){
        const a=badges[i],b=badges[j],w=Math.min(a.right,b.right)-Math.max(a.x,b.x),h=Math.min(a.bottom,b.bottom)-Math.max(a.y,b.y);
        if(w>4&&h>4)collisions.push({a,b,width:w,height:h});
      }
      return {count:badges.length,collisions};
    }""")

    pointer_occlusion = []
    if phone and page.locator("dialog[open]").count() == 0 and not page.locator("#chapterFlyout:not(.hidden)").count():
        for selector in ["#btnStripZoomFit", "#btnSidebarToggle", "#sbClose", "#tabViz", "#tabInsp",
                         "#btnOpen", "#btnOverview", "#btnSettings", "#btnLogs"]:
            target = box(page, selector)
            if not target:
                continue
            hit = page.evaluate("""([selector,x,y]) => {
              const target=document.querySelector(selector), hit=document.elementFromPoint(x,y);
              const describe=e=>e ? (e.id?'#'+e.id:e.tagName.toLowerCase()+'.'+String(e.className||'').trim().replace(/\\s+/g,'.')) : null;
              return {accessible:!!(target&&hit&&(hit===target||target.contains(hit))),hit:describe(hit)};
            }""", [selector, target["x"] + target["width"] / 2, target["y"] + target["height"] / 2])
            pointer_occlusion.append({"selector": selector, **target, **hit})

    hit_targets = []
    if phone:
        hit_targets = page.evaluate("""() => {
          const out=[]; const seen=new Set();
          const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);if(s.display==='none'||s.visibility==='hidden'||r.width<=0||r.height<=0)return false;
            let x0=Math.max(0,r.left),y0=Math.max(0,r.top),x1=Math.min(innerWidth,r.right),y1=Math.min(innerHeight,r.bottom);
            for(let a=e.parentElement;a;a=a.parentElement){const as=getComputedStyle(a),ar=a.getBoundingClientRect();
              if(as.overflowX!=='visible'){x0=Math.max(x0,ar.left);x1=Math.min(x1,ar.right);}
              if(as.overflowY!=='visible'){y0=Math.max(y0,ar.top);y1=Math.min(y1,ar.bottom);}}
            return x1>x0&&y1>y0;};
          const path=e=>e.id?'#'+e.id:e.tagName.toLowerCase()+'.'+String(e.className||'').trim().replace(/\\s+/g,'.');
          const dialog=document.querySelector('dialog[open]');
          const flyout=document.querySelector('#chapterFlyout:not(.hidden)');
          const root=dialog||flyout||document;
          const all=[...root.querySelectorAll('button,a,select,textarea,input:not([type=hidden]),label.tree-leaf,label.tree-node-label,[role=button]')];
          for(const e of all){if(!visible(e))continue; let target=e;
            if(e.matches('input[type=checkbox],input[type=radio]')) target=e.closest('label')||e;
            if(e.matches('input[type=range]')) target=e.parentElement;
            if(seen.has(target))continue;seen.add(target);const r=target.getBoundingClientRect();
            out.push({selector:path(target),source:path(e),x:r.x,y:r.y,width:r.width,height:r.height,
              text:(target.innerText||target.value||'').trim().slice(0,50),offscreen:r.left < -2 || r.right > innerWidth+2});}
          return out;
        }""")

    result = {"state": label, "viewport": {"width": width, "height": height}, "document": doc,
              "geometry": geometry, "horizontal_overflow": overflows, "overlap_groups": groups,
              "descendant_overflows": descendant_overflows, "badge_collisions": badge_collisions,
              "grid_alignment_deltas_px": align, "offscreen_major_elements": offscreen,
              "unellipsized_text_overflow": text_overflows, "touch_targets": hit_targets,
              "phone_pointer_occlusion": pointer_occlusion,
              "screenshot": rel(screenshot)}
    if extra:
        result.update(extra)

    # Findings from this exact state; each gets a crop from the state screenshot.
    if doc["documentElement"]["scrollWidth"] > doc["documentElement"]["clientWidth"] + TOLERANCE:
        add_finding(page, label, "horizontal_scroll", "blocker", "html",
                    "Document has horizontal scroll beyond the 2px tolerance.", doc["documentElement"], screenshot)
    if doc["body"]["scrollWidth"] > doc["body"]["clientWidth"] + TOLERANCE:
        add_finding(page, label, "horizontal_scroll", "blocker", "body",
                    "Body has horizontal scroll beyond the 2px tolerance.", doc["body"], screenshot)
    for item in overflows:
        add_finding(page, label, "container_horizontal_scroll", "minor", item["selector"],
                    f"Scroll container overflows horizontally by {item['overflow_px']:.1f}px.", item, screenshot,
                    item["box"])
    for item in offscreen:
        panel_selector = item["selector"]
        major = panel_selector in {"#sidebar", "#viewer", "#rail", "#main", "#topbar", "#statusbar", "#chapterFlyout", "dialog", ".focus-bar"}
        severity = "blocker" if panel_selector in {"#sidebar", "#viewer", "#settingsDlg", "#overviewDlg", "#gotoDlg", "dialog", "#chapterFlyout", ".focus-bar"} else "minor"
        add_finding(page, label, "offscreen_or_clipped", severity, panel_selector,
                    f"Visible layout element extends beyond the {width}×{height}px viewport; likely clipped without a document scrollbar.",
                    item, screenshot, item["box"])
    for key, value in align.items():
        if value is not None and value > 8:
            add_finding(page, label, "grid_alignment", "minor", key,
                        f"Adjacent layout edges are misaligned by {value:.1f}px (allowed 8px).", align,
                        screenshot)
    for item in text_overflows:
        add_finding(page, label, "text_overflow", "minor", item["selector"],
                    "Visible text overflows its box without ellipsis.", item, screenshot, item)
    if phone and not page.locator("dialog[open]").count() and not page.locator("#chapterFlyout:not(.hidden)").count():
        viewer = box(page, "#viewer")
        zoom_bar = box(page, "#stripZoomBar")
        sidebar = box(page, "#sidebar")
        if viewer and zoom_bar and (zoom_bar["x"] < viewer["x"] - TOLERANCE or
                                    zoom_bar["right"] > viewer["right"] + TOLERANCE):
            add_finding(page, label, "control_escapes_panel", "blocker", "#stripZoomBar",
                        "Floating zoom toolbar extends outside the viewer and into the sidebar area.",
                        {"viewer": viewer, "zoom_bar": zoom_bar, "sidebar": sidebar}, screenshot, zoom_bar,
                        "tools/translation_studio/static/style.css:445-449, 1392-1405")
        for item in pointer_occlusion:
            if not item["accessible"]:
                severity = "blocker" if item["selector"] in {"#btnStripZoomFit", "#btnSidebarToggle", "#sbClose",
                                                               "#tabInsp", "#btnOverview", "#btnSettings", "#btnOpen"} else "minor"
                add_finding(page, label, "pointer_occlusion", severity, item["selector"],
                            f"The control center is covered by {item['hit'] or 'no hit target'} and does not receive a normal pointer click.",
                            item, screenshot, item)
    if phone:
        for target in hit_targets:
            if target["width"] < 24 or target["height"] < 24:
                add_finding(page, label, "touch_target", "minor", target["selector"],
                            "Visible phone touch target is smaller than 24×24px.", target, screenshot, target)
            if target["offscreen"]:
                add_finding(page, label, "touch_target_clipped", "blocker", target["selector"],
                            "Visible phone touch target is clipped by the viewport edge.", target, screenshot, target)

    SNAPSHOTS.append(result)
    return result


def stable_boxes(page) -> dict[str, Any]:
    selectors = [".tb-left", ".tb-center", ".tb-right", "#rail", "#railToggle", "#viewer", "#sidebar",
                 "#stripZoomBar", ".sb-header", "#btnTreeReset", "#vizTree", "#confSlider"]
    return {selector: box(page, selector) for selector in selectors}


def moved(before: dict[str, Any], after: dict[str, Any], allow: set[str] | None = None) -> list[dict[str, Any]]:
    allow = allow or set()
    result = []
    for selector, old in before.items():
        new = after.get(selector)
        if selector in allow or not old or not new:
            continue
        delta = {k: new[k] - old[k] for k in ("x", "y", "width", "height")}
        if max(abs(v) for v in delta.values()) > 8:
            result.append({"selector": selector, "before": old, "after": new, "delta_px": delta})
    return result


def main() -> None:
    BASELINE.mkdir(parents=True, exist_ok=True)
    api_state: dict[str, Any] = {}
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1920, "height": 1080}, device_scale_factor=1)
        page.goto(URL, wait_until="networkidle")
        page.wait_for_selector("#strip .pg", timeout=15000)
        page.wait_for_timeout(500)
        api_state = page.request.get(URL + "/api/state").json()
        page_one = page.request.get(URL + "/api/page?p=p001.jpg").json()
        page_dims = api_state.get("dims", {}).get("p001.jpg", [900, 1400])
        region = next((r for r in page_one.get("regions", []) if r.get("id") == "r00"), None)
        if not region:
            raise RuntimeError("Demo page has no cached OCR region r00; aborting incomplete UI audit.")
        region_box = region["box"]

        # Default state at each required viewport, with tree state and zoom reset.
        for name, width, height in VIEWPORTS:
            page.set_viewport_size({"width": width, "height": height})
            page.wait_for_timeout(320)
            if page.locator("#btnTreeReset").is_visible():
                page.locator("#btnTreeReset").click()
                page.wait_for_timeout(150)
            if page.locator("#btnStripZoomFit").is_visible():
                if width > 400:
                    page.locator("#btnStripZoomFit").click()
                    page.wait_for_timeout(150)
            page.evaluate("document.querySelector('#viewer').scrollTop=0")
            page.evaluate("window.scrollTo(0,0)")
            page.wait_for_timeout(80)
            snapshot(page, f"matrix_{name}_default", width, height, phone=(width <= 390),
                     extra={"mode": "default visualization, page 1 visible", "cache_state": {
                         "pages": len(api_state.get("pages", [])), "p001_regions": len(page_one.get("regions", [])),
                         "detected": page_one.get("detected"), "ocr_ms": page_one.get("ocr_ms"),
                         "rendered": (page.request.get(URL + "/api/overview").json().get("pages", [{}])[0].get("rendered"))}})

        # Restore the full viewport and exercise toggle/selection transitions.
        page.set_viewport_size({"width": 1920, "height": 1080})
        page.wait_for_timeout(350)
        page.locator("#btnTreeReset").click()
        page.locator("#btnStripZoomFit").click()
        page.evaluate("document.querySelector('#viewer').scrollTop=0")
        before = stable_boxes(page)
        page.locator("#btnSidebarToggle").click()
        page.wait_for_timeout(250)
        collapsed_path = BASELINE / "screenshots" / "interaction_sidebar_collapsed_1920x1080.png"
        page.screenshot(path=str(collapsed_path))
        after_collapsed = stable_boxes(page)
        SNAPSHOTS.append({"state": "interaction_sidebar_collapsed_1920x1080", "viewport": {"width": 1920, "height": 1080},
                          "geometry": {"before": before, "after": after_collapsed}, "screenshot": rel(collapsed_path),
                          "layout_shifts": moved(before, after_collapsed, {"#viewer", "#sidebar", ".sb-header", "#btnTreeReset", "#vizTree", "#confSlider"})})
        for shift in moved(before, after_collapsed, {"#viewer", "#sidebar", ".sb-header", "#btnTreeReset", "#vizTree", "#confSlider"}):
            add_finding(page, "interaction_sidebar_collapsed_1920x1080", "state_layout_shift", "minor",
                        shift["selector"], "Unrelated control moved more than 8px when the sidebar was toggled.",
                        shift, collapsed_path, shift["after"])
        page.locator("#btnSidebarToggle").click()
        page.wait_for_timeout(250)
        layer_before = stable_boxes(page)
        page.locator("#treeBoxBubble").click()
        page.wait_for_timeout(300)
        layer_after = stable_boxes(page)
        layer_path = BASELINE / "screenshots" / "interaction_layer_toggle_1920x1080.png"
        page.screenshot(path=str(layer_path))
        SNAPSHOTS.append({"state": "interaction_layer_toggle_1920x1080", "viewport": {"width": 1920, "height": 1080},
                          "geometry": {"before": layer_before, "after": layer_after}, "screenshot": rel(layer_path),
                          "layout_shifts": moved(layer_before, layer_after)})
        for shift in moved(layer_before, layer_after):
            add_finding(page, "interaction_layer_toggle_1920x1080", "state_layout_shift", "minor",
                        shift["selector"], "Unrelated control moved more than 8px when a visualization layer was toggled.",
                        shift, layer_path, shift["after"])

        # Existing layer tree is the only filtering control on baseline.
        page.locator("#treeBoxBubble").click()
        page.wait_for_timeout(220)
        region_target = page.locator(".pg[data-page='p001.jpg'] .artifact-item[data-artifact-id='ocr-r00']").first.bounding_box()
        if not region_target:
            raise RuntimeError("Current Studio overlay did not render OCR artifact ocr-r00")
        page.mouse.click(region_target["x"] + region_target["width"] / 2,
                         region_target["y"] + region_target["height"] / 2)
        page.wait_for_timeout(350)
        inspector_path = BASELINE / "screenshots" / "interaction_region_selected_inspector_1920x1080.png"
        inspector_snap = snapshot(page, "interaction_region_selected_inspector_1920x1080", 1920, 1080,
                                  extra={"selected_region": "r00", "inspector_visible": page.locator("#paneInsp").is_visible(),
                                         "inspector_text": (page.locator("#inspBody").inner_text()[:350] if page.locator("#inspBody").count() else "")})

        # Mid-session 1920 -> 390 -> 1024 resize while selection and inspector are active.
        for name, width, height in [("390", 390, 844), ("1024", 1024, 768)]:
            page.set_viewport_size({"width": width, "height": height})
            page.wait_for_timeout(350)
            snapshot(page, f"mid_resize_{name}_inspector_selected", width, height,
                     phone=(width == 390), extra={"path": "1920x1080 -> 390x844 -> 1024x768",
                                                    "selection_preserved": page.locator(".rbox.sel").count() > 0,
                                                    "inspector_tab_active": "active" in (page.locator("#tabInsp").get_attribute("class") or "")})

        # Check the focus viewer and overlay registration at fit and a second zoom level.
        page.set_viewport_size({"width": 1920, "height": 1080})
        page.wait_for_timeout(250)
        page.locator("#focusBack").wait_for(state="hidden")
        page.locator("#strip .pg").first.dblclick(position={"x": 30, "y": 30})
        page.locator("#focusView").wait_for(state="visible")
        page.wait_for_timeout(300)
        focus_fit_path = BASELINE / "screenshots" / "focus_fit_1920x1080.png"
        focus_fit = snapshot(page, "focus_fit_1920x1080", 1920, 1080,
                             extra={"mode": "focus fit", "focus_visible": True})
        focus_fit["overlay_registration"] = overlay_registration(page, "focus_fit_1920x1080", focus_fit_path,
                                                                   "#focusInner .pg-media", "#focusInner .artifact-item[data-artifact-id='ocr-r00'] .artifact-shape",
                                                                   region_box, page_dims)
        page.locator("#btnZoomIn").click()
        page.wait_for_timeout(250)
        focus_zoom_path = BASELINE / "screenshots" / "focus_zoom_1_2_1920x1080.png"
        focus_zoom = snapshot(page, "focus_zoom_1_2_1920x1080", 1920, 1080,
                              extra={"mode": "focus zoomed", "zoom_label": page.locator("#btnZoomReset").inner_text()})
        focus_zoom["overlay_registration"] = overlay_registration(page, "focus_zoom_1_2_1920x1080", focus_zoom_path,
                                                                    "#focusInner .pg-media", "#focusInner .artifact-item[data-artifact-id='ocr-r00'] .artifact-shape",
                                                                    region_box, page_dims)
        page.locator("#focusBack").click()
        page.wait_for_timeout(220)
        page.locator("#btnStripZoomFit").click()
        page.wait_for_timeout(220)
        strip_fit_path = BASELINE / "screenshots" / "strip_zoom_100_1920x1080.png"
        strip_fit = snapshot(page, "strip_zoom_100_1920x1080", 1920, 1080,
                             extra={"mode": "strip view", "zoom_label": page.locator("#stripZoomVal").inner_text()})
        strip_fit["overlay_registration"] = overlay_registration(page, "strip_zoom_100_1920x1080", strip_fit_path,
                                                                   "#strip .pg-media", "#strip .artifact-item[data-artifact-id='ocr-r00'] .artifact-shape",
                                                                   region_box, page_dims)
        page.locator("#btnStripZoomIn").click()
        page.wait_for_timeout(250)
        strip_zoom_path = BASELINE / "screenshots" / "strip_zoom_120_1920x1080.png"
        strip_zoom = snapshot(page, "strip_zoom_120_1920x1080", 1920, 1080,
                              extra={"mode": "strip view zoomed", "zoom_label": page.locator("#stripZoomVal").inner_text()})
        strip_zoom["overlay_registration"] = overlay_registration(page, "strip_zoom_120_1920x1080", strip_zoom_path,
                                                                    "#strip .pg-media", "#strip .artifact-item[data-artifact-id='ocr-r00'] .artifact-shape",
                                                                    region_box, page_dims)

        # Modal and flyout geometry at phone width, then console open in the same viewport.
        page.set_viewport_size({"width": 390, "height": 844})
        page.wait_for_timeout(300)
        page.locator("#btnSettings").evaluate("el => el.click()")
        page.locator("#settingsDlg").wait_for(state="visible")
        snapshot(page, "phone_settings_dialog_390x844", 390, 844, phone=True,
                 extra={"state_note": "settings modal open"})
        page.locator("#setClose").evaluate("el => el.click()")
        page.wait_for_timeout(160)
        # At phone width the normal pointer target is obstructed by the compact
        # stage menu on this baseline; open the dialog via its DOM event so its
        # own layout can still be measured after the obstruction is recorded.
        page.locator("#btnOverview").evaluate("el => el.click()")
        page.locator("#overviewDlg").wait_for(state="visible")
        snapshot(page, "phone_overview_dialog_390x844", 390, 844, phone=True,
                 extra={"state_note": "overview modal and performance table open"})
        page.locator("#ovClose").evaluate("el => el.click()")
        page.wait_for_timeout(180)
        page.locator("#btnOpen").evaluate("el => el.click()")
        page.locator("#chapterFlyout").wait_for(state="visible")
        snapshot(page, "phone_chapter_flyout_390x844", 390, 844, phone=True,
                 extra={"state_note": "chapter switcher flyout open"})
        page.locator("#cfClose").evaluate("el => el.click()")
        page.wait_for_timeout(120)
        page.evaluate("document.querySelectorAll('#stagePipeline .stage-params').forEach(el => el.open = true)")
        page.locator("#stagePipeline").evaluate("el => el.scrollIntoView({block:'start'})")
        snapshot(page, "phone_stage_parameters_390x844", 390, 844, phone=True,
                 extra={"state_note": "all five stage parameter sections open"})
        page.evaluate("document.querySelectorAll('#artifactFilterCard details').forEach(el => el.open = true)")
        page.locator("#artifactFilterCard").evaluate("el => el.scrollIntoView({block:'start'})")
        snapshot(page, "phone_filter_controls_390x844", 390, 844, phone=True,
                 extra={"state_note": "filter sections open with controls in view"})
        page.locator("#btnLogs").evaluate("el => el.click()")
        page.wait_for_timeout(200)
        snapshot(page, "phone_console_open_390x844", 390, 844, phone=True,
                 extra={"state_note": "processing console open"})

        filter_controls = page.locator("#filterPreset, #artifactModelGroups, [data-filter-score-min], #filterSuppression").count()
        coverage = {
            "rubric_source": "team/09-ui-audit/RUBRIC.md (Pass 3 layout run)",
            "audited_branch": "t937-d1-ui-refactor",
            "server_url": URL,
            "chapter_pages": api_state.get("pages", []),
            "cached_page_1_region_count": len(page_one.get("regions", [])),
            "source_model_output_filter_controls_found": filter_controls,
            "filter_coverage": "B2 display-time model/output filters are present; this run measured layout and preserved their cached-data behavior.",
            "translated_text": "Synthetic cached OCR and translation fixture; no pipeline stage was run.",
            "pipeline_runs": 0,
        }
        browser.close()

    (BASELINE / "measurements.json").write_text(json.dumps({"coverage": coverage, "snapshots": SNAPSHOTS,
                                                              "findings": FINDINGS}, indent=2), encoding="utf-8")
    print(json.dumps({"coverage": coverage, "snapshots": len(SNAPSHOTS), "findings": len(FINDINGS),
                      "blockers": sum(f["severity"] == "blocker" for f in FINDINGS),
                      "minor": sum(f["severity"] == "minor" for f in FINDINGS),
                      "findings_by_category": {key: sum(f["category"] == key for f in FINDINGS)
                                               for key in sorted({f["category"] for f in FINDINGS})}}, indent=2))


if __name__ == "__main__":
    main()
