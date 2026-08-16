/**
 * TachiyomiAT Visual Detection & Reading Order Structure Visualizer
 *
 * Side-by-Side Visual Comparison: Current Engine (Bug) vs 4-Pillar Architectural Solution
 */

let currentRtl = true;
let currentSample = 'screenshot-tall-bubble';
let currentStep = 6;
let viewLayout = 'dual'; // 'dual' (side-by-side) or 'single'
let engineMode = 'sliced';
let selectedElement = null;
let currentZoom = 1.0;

// Sample Data Sets (Coordinates in [x1, y1, x2, y2] 800x1100 space)
const SAMPLES = {
    'screenshot-tall-bubble': {
        name: 'User Screenshot: Tall Bubble with Diagonal Japanese Columns',
        width: 800,
        height: 1100,
        panels: [
            { id: 'p0', rawIdx: 0, box: [20, 20, 780, 220], desc: 'Top Panel with Exclamation' },
            { id: 'p1', rawIdx: 1, box: [20, 230, 780, 1080], desc: 'Main Panel with Creature & Large Speech Bubble' }
        ],
        fusedMask: {
            bounds: [200, 270, 600, 830],
            isTallSingleBubble: true,
            ovals: [
                { id: 'oval_top', cx: 480, cy: 370, rx: 110, ry: 90, label: 'Top Column Region' },
                { id: 'oval_bot', cx: 330, cy: 640, rx: 120, ry: 130, label: 'Bottom Column Region' }
            ]
        },
        bubbles: [
            {
                id: 'det_box_top',
                text: 'アイツは取り乱した',
                translation: 'SHE LOST\nHER\nCOMPOSURE',
                fullTranslation: 'SHE LOST HER COMPOSURE',
                x: 420,
                y: 320,
                w: 160,
                h: 120,
                ovalIndex: 0,
                bubbleGroupId: 'tall_bubble_grp'
            },
            {
                id: 'det_box_bot',
                text: '敵地に単身乗り込んだ\nあの小娘が無防備に',
                translation: 'THAT LITTLE GIRL\nWHO WENT ALONE\nINTO ENEMY\nTERRITORY\nUNPREPARED.',
                fullTranslation: 'THAT LITTLE GIRL WHO WENT ALONE INTO ENEMY TERRITORY UNPREPARED.',
                x: 230,
                y: 530,
                w: 190,
                h: 190,
                ovalIndex: 1,
                bubbleGroupId: 'tall_bubble_grp'
            },
            {
                id: 'det_box_sword',
                text: '甘く見るなよ',
                translation: "DON'T UNDERESTIMATE ME, OKAY",
                truncatedTranslation: "DON'T\nUNDERESTIM\nME, OKAY",
                x: 640,
                y: 770,
                w: 140,
                h: 180,
                parentBox: [620, 740, 780, 980],
                bubbleGroupId: 'sword_bubble'
            },
            {
                id: 'det_box_left',
                text: '罠の中に飛び込んで',
                translation: 'FALLING INTO A TRAP...',
                x: 30,
                y: 740,
                w: 140,
                h: 220,
                parentBox: [20, 710, 180, 990],
                bubbleGroupId: 'left_bubble'
            }
        ]
    },
    'conjoined-3-horizontal': {
        name: 'Conjoined Bubbles: 3 Linked Ovals on Nearly Same Height',
        width: 800,
        height: 1100,
        panels: [
            { id: 'p0', rawIdx: 0, box: [340, 40, 770, 520], desc: 'Top-Right Panel with 3 Side-by-Side Ovals' },
            { id: 'p1', rawIdx: 1, box: [40, 40, 320, 520], desc: 'Top-Left Panel' },
            { id: 'p2', rawIdx: 2, box: [40, 540, 770, 1040], desc: 'Bottom Panel' }
        ],
        fusedMask: {
            bounds: [360, 95, 755, 290],
            ovals: [
                { id: 'oval_0', cx: 430, cy: 185, rx: 70, ry: 75, label: 'Oval 1 (Left)' },
                { id: 'oval_1', cx: 560, cy: 200, rx: 75, ry: 80, label: 'Oval 2 (Middle, +15px)' },
                { id: 'oval_2', cx: 680, cy: 180, rx: 70, ry: 75, label: 'Oval 3 (Right, -5px)' }
            ]
        },
        bubbles: [
            {
                id: 'det_box_1',
                text: '信じられない…',
                translation: 'Unbelievable...',
                x: 375,
                y: 155,
                w: 110,
                h: 60,
                ovalIndex: 0,
                bubbleGroupId: 'conjoined_grp_h3'
            },
            {
                id: 'det_box_2',
                text: '結界が破られた…！？',
                translation: 'The barrier broke...?!',
                x: 505,
                y: 170,
                w: 110,
                h: 60,
                ovalIndex: 1,
                bubbleGroupId: 'conjoined_grp_h3'
            },
            {
                id: 'det_box_3',
                text: 'どうするんだ！？',
                translation: 'What do we do now?!',
                x: 625,
                y: 150,
                w: 110,
                h: 60,
                ovalIndex: 2,
                bubbleGroupId: 'conjoined_grp_h3'
            },
            {
                id: 'det_box_4',
                text: 'そんな…！',
                translation: 'No way...!',
                x: 80,
                y: 120,
                w: 140,
                h: 120,
                parentBox: [60, 90, 260, 270],
                bubbleGroupId: 'solo_1'
            },
            {
                id: 'det_box_5',
                text: '全軍、戦闘配備を急げ！\n持ち場を死守するんだ！',
                translation: 'All units, prepare for battle immediately!\nDefend your positions!',
                x: 180,
                y: 680,
                w: 440,
                h: 150,
                parentBox: [140, 630, 660, 890],
                bubbleGroupId: 'solo_2'
            }
        ]
    },
    'conjoined-2-horizontal': {
        name: 'Conjoined Bubbles: 2 Linked Ovals (Side-by-Side)',
        width: 800,
        height: 1100,
        panels: [
            { id: 'p0', rawIdx: 0, box: [360, 40, 760, 520], desc: 'Top-Right Panel with 2 Side-by-Side Ovals' },
            { id: 'p1', rawIdx: 1, box: [40, 40, 340, 520], desc: 'Top-Left Panel' },
            { id: 'p2', rawIdx: 2, box: [40, 540, 760, 1040], desc: 'Bottom Panel' }
        ],
        fusedMask: {
            bounds: [370, 95, 715, 305],
            ovals: [
                { id: 'oval_0', cx: 460, cy: 195, rx: 80, ry: 75, label: 'Oval 1 (Left)' },
                { id: 'oval_1', cx: 615, cy: 195, rx: 85, ry: 80, label: 'Oval 2 (Right)' }
            ]
        },
        bubbles: [
            {
                id: 'det_box_1',
                text: '何だと…？',
                translation: 'What did you say...?',
                x: 400,
                y: 165,
                w: 120,
                h: 60,
                ovalIndex: 0,
                bubbleGroupId: 'conjoined_grp_2'
            },
            {
                id: 'det_box_2',
                text: 'そんなはずはない！',
                translation: "That's impossible!",
                x: 550,
                y: 165,
                w: 130,
                h: 60,
                ovalIndex: 1,
                bubbleGroupId: 'conjoined_grp_2'
            },
            {
                id: 'det_box_3',
                text: '本当なんだ！信じてくれ！',
                translation: "It's the truth! Believe me!",
                x: 180,
                y: 680,
                w: 440,
                h: 150,
                parentBox: [140, 630, 660, 890],
                bubbleGroupId: 'solo_3'
            }
        ]
    },
    'conjoined-3-diagonal': {
        name: 'Conjoined Bubbles: 3 Linked Ovals (Cascading / Diagonal)',
        width: 800,
        height: 1100,
        panels: [
            { id: 'p0', rawIdx: 0, box: [380, 40, 760, 520], desc: 'Top-Right Panel with 3 Conjoined Bubbles' },
            { id: 'p1', rawIdx: 1, box: [40, 40, 360, 520], desc: 'Top-Left Panel' },
            { id: 'p2', rawIdx: 2, box: [40, 540, 760, 1040], desc: 'Bottom Panel' }
        ],
        fusedMask: {
            bounds: [340, 65, 710, 435],
            ovals: [
                { id: 'oval_0', cx: 615, cy: 135, rx: 80, ry: 65, label: 'Oval 1 (Top-Right)' },
                { id: 'oval_1', cx: 520, cy: 245, rx: 85, ry: 70, label: 'Oval 2 (Center)' },
                { id: 'oval_2', cx: 430, cy: 350, rx: 80, ry: 65, label: 'Oval 3 (Bottom-Left)' }
            ]
        },
        bubbles: [
            {
                id: 'det_box_1',
                text: '待て…！',
                translation: 'Wait...!',
                x: 555,
                y: 105,
                w: 120,
                h: 60,
                ovalIndex: 0,
                bubbleGroupId: 'conjoined_grp_1'
            },
            {
                id: 'det_box_2',
                text: 'まだ行くな！',
                translation: "Don't go yet!",
                x: 455,
                y: 215,
                w: 130,
                h: 60,
                ovalIndex: 1,
                bubbleGroupId: 'conjoined_grp_1'
            },
            {
                id: 'det_box_3',
                text: '後ろを見ろ！',
                translation: 'Look behind you!',
                x: 370,
                y: 320,
                w: 120,
                h: 60,
                ovalIndex: 2,
                bubbleGroupId: 'conjoined_grp_1'
            },
            {
                id: 'det_box_4',
                text: 'えっ…！？',
                translation: 'Huh...?!',
                x: 80,
                y: 120,
                w: 140,
                h: 120,
                parentBox: [60, 90, 260, 270],
                bubbleGroupId: 'solo_1'
            },
            {
                id: 'det_box_5',
                text: '早く逃げろ！奴らが来るぞ！',
                translation: 'Run quickly! They are coming!',
                x: 180,
                y: 680,
                w: 440,
                h: 150,
                parentBox: [140, 630, 660, 890],
                bubbleGroupId: 'solo_2'
            }
        ]
    },
    'manga-4panel': {
        name: 'Manga 4-Panel Page (Classic RTL)',
        width: 800,
        height: 1100,
        panels: [
            { id: 'p0', rawIdx: 0, box: [410, 40, 760, 520], desc: 'Top-Right Panel' },
            { id: 'p1', rawIdx: 1, box: [40, 40, 390, 520], desc: 'Top-Left Panel' },
            { id: 'p2', rawIdx: 2, box: [410, 540, 760, 1040], desc: 'Bottom-Right Panel' },
            { id: 'p3', rawIdx: 3, box: [40, 540, 390, 1040], desc: 'Bottom-Left Panel' }
        ],
        bubbles: [
            { id: 'b0', text: 'ここはどこだ…？\n体が動かない…', translation: "Where am I...? My body won't move...", x: 580, y: 80, w: 140, h: 180, parentBox: [560, 60, 740, 280] },
            { id: 'b1', text: 'おい！大丈夫か！？\n返事をしろ！', translation: "Hey! Are you alright!? Answer me!", x: 440, y: 220, w: 130, h: 160, parentBox: [420, 200, 590, 400] },
            { id: 'b2', text: '（この声は…\nあいつなのか？）', translation: "(This voice... is it him?)", x: 80, y: 100, w: 140, h: 170, parentBox: [60, 80, 240, 290] },
            { id: 'b3', text: '早く逃げろ！\n奴らが来るぞ！', translation: "Run quickly! They are coming!", x: 570, y: 600, w: 150, h: 200, parentBox: [550, 580, 740, 820] },
            { id: 'b4', text: '逃げるって…\nどこへ…！？', translation: "Run...? But where to...!?", x: 90, y: 620, w: 140, h: 160, parentBox: [70, 600, 250, 800] },
            { id: 'b5', text: '【ゴゴゴゴ…】', translation: "[RUMBLE...]", x: 280, y: 780, w: 80, h: 180, parentBox: null, isFreeText: true }
        ]
    }
};

// -------------------------------------------------------------
// 1. RECURSIVE XY-CUT IMPLEMENTATION (ReadingOrderSorter.kt)
// -------------------------------------------------------------

function readingOrderPanels(panels, rtl) {
    if (panels.length <= 1) return panels;
    const indices = xyCutOrder(panels, panels.map((_, i) => i), rtl);
    return indices.map(i => panels[i]);
}

function xyCutOrder(panels, idxList, rtl) {
    if (idxList.length <= 1) return idxList;
    const hCut = widestGutterSplit(panels, idxList, 'y');
    if (hCut !== null) {
        return [...xyCutOrder(panels, hCut[0], rtl), ...xyCutOrder(panels, hCut[1], rtl)];
    }
    const vCut = widestGutterSplit(panels, idxList, 'x');
    if (vCut !== null) {
        if (rtl) {
            return [...xyCutOrder(panels, vCut[1], rtl), ...xyCutOrder(panels, vCut[0], rtl)];
        } else {
            return [...xyCutOrder(panels, vCut[0], rtl), ...xyCutOrder(panels, vCut[1], rtl)];
        }
    }
    return columnFallbackOrder(panels, idxList, rtl);
}

function widestGutterSplit(panels, idxList, axis) {
    const events = [];
    for (const i of idxList) {
        const p = panels[i].box;
        const lo = axis === 'y' ? p[1] : p[0];
        const hi = axis === 'y' ? p[3] : p[2];
        events.push([lo, 1]);
        events.push([hi, -1]);
    }
    events.sort((a, b) => a[0] * 2 - a[1] - (b[0] * 2 - b[1]));

    let bestStart = NaN;
    let bestWidth = 1.0;
    let count = 0;
    let prev = NaN;
    let bestFound = false;

    for (const e of events) {
        if (count === 0 && !isNaN(prev)) {
            const w = e[0] - prev;
            if (w >= 1.0 && w > bestWidth) {
                bestWidth = w;
                bestStart = prev;
                bestFound = true;
            }
        }
        count += e[1];
        prev = e[0];
    }

    if (!bestFound) return null;
    const cut = bestStart + bestWidth / 2.0;
    const a = [];
    const b = [];
    for (const i of idxList) {
        const p = panels[i].box;
        const c = axis === 'y' ? (p[1] + p[3]) / 2.0 : (p[0] + p[2]) / 2.0;
        if (c <= cut) a.push(i);
        else b.push(i);
    }
    if (a.length === 0 || b.length === 0) return null;
    return [a, b];
}

function columnFallbackOrder(panels, idxList, rtl) {
    return [...idxList].sort((a, b) => {
        const cxA = (panels[a].box[0] + panels[a].box[2]) / 2.0;
        const cxB = (panels[b].box[0] + panels[b].box[2]) / 2.0;
        if (rtl) {
            if (cxB !== cxA) return cxB - cxA;
        } else {
            if (cxA !== cxB) return cxA - cxB;
        }
        return panels[a].box[1] - panels[b].box[1];
    });
}

// -------------------------------------------------------------
// 2. PANEL ASSIGNMENT IMPLEMENTATION (PanelAssignment.kt)
// -------------------------------------------------------------

const OWNED_THRESHOLD = 0.80;
const SPAN_LO = 0.10;

function assignBubbleToPanels(bubble, orderedPanels) {
    const bx1 = bubble.x;
    const by1 = bubble.y;
    const bx2 = bubble.x + bubble.w;
    const by2 = bubble.y + bubble.h;
    const bubbleArea = (bx2 - bx1) * (by2 - by1);

    if (orderedPanels.length === 0) {
        return { category: 'orphan', panelIndex: null, containment: 0 };
    }

    let bestIdx = -1;
    let bestCont = 0;

    for (let i = 0; i < orderedPanels.length; i++) {
        const p = orderedPanels[i].box;
        const ix1 = Math.max(bx1, p[0]);
        const iy1 = Math.max(by1, p[1]);
        const ix2 = Math.min(bx2, p[2]);
        const iy2 = Math.min(by2, p[3]);

        if (ix2 > ix1 && iy2 > iy1) {
            const interArea = (ix2 - ix1) * (iy2 - iy1);
            const cont = interArea / bubbleArea;
            if (cont > bestCont) {
                bestCont = cont;
                bestIdx = i;
            }
        }
    }

    if (bestIdx < 0) {
        return { category: 'free_floating', panelIndex: null, containment: 0 };
    }

    let category = 'free_floating';
    if (bestCont >= OWNED_THRESHOLD) category = 'owned';
    else if (bestCont >= SPAN_LO) category = 'spanning';

    return {
        category,
        panelIndex: category === 'owned' ? bestIdx : null,
        containment: bestCont,
        bestPanelRaw: orderedPanels[bestIdx].id
    };
}

// -------------------------------------------------------------
// 3. LAYOUT SOLVER SIMULATOR
// -------------------------------------------------------------

function simulateLayoutPlanner(sampleData, mode) {
    const bubbles = sampleData.bubbles;
    const fusedMask = sampleData.fusedMask;

    if (sampleData.name.includes('Screenshot') && mode === 'unified') {
        // Option: Smart Full-Width Y-Slicing with no word scissors clipping
        const bounds = fusedMask.bounds; // [200, 270, 600, 830]
        const fullW = bounds[2] - bounds[0] - 20; // ~380px full width
        const midY = (bounds[1] + bounds[3]) / 2 - 20; // y = 530

        const fixedBlocks = bubbles.map(b => {
            if (b.id === 'det_box_top') {
                return {
                    ...b,
                    layoutMode: 'unified',
                    renderedText: 'SHE LOST HER COMPOSURE',
                    fontSize: 24.0,
                    safeW: fullW,
                    safeH: midY - bounds[1],
                    renderCenterX: (bounds[0] + bounds[2]) / 2,
                    renderCenterY: (bounds[1] + midY) / 2 + 10,
                    isFixed: true,
                    note: 'Full 100% Bubble Width'
                };
            } else if (b.id === 'det_box_bot') {
                return {
                    ...b,
                    layoutMode: 'unified',
                    renderedText: 'THAT LITTLE GIRL WHO WENT ALONE INTO ENEMY TERRITORY UNPREPARED.',
                    fontSize: 22.0,
                    safeW: fullW,
                    safeH: bounds[3] - midY,
                    renderCenterX: (bounds[0] + bounds[2]) / 2,
                    renderCenterY: (midY + bounds[3]) / 2 - 10,
                    isFixed: true,
                    note: 'Full 100% Bubble Width'
                };
            } else if (b.id === 'det_box_sword') {
                return {
                    ...b,
                    layoutMode: 'unified',
                    renderedText: "DON'T UNDERESTIMATE ME, OKAY",
                    fontSize: 20.0,
                    safeW: 130,
                    safeH: 150,
                    renderCenterX: b.x + b.w / 2,
                    renderCenterY: b.y + b.h / 2,
                    isFixed: true,
                    note: 'Zero Word Clipping (UNDERESTIMATE complete)'
                };
            } else {
                return {
                    ...b,
                    layoutMode: 'unified',
                    renderedText: b.translation,
                    fontSize: 20.0,
                    safeW: b.w * 0.85,
                    safeH: b.h * 0.85,
                    renderCenterX: b.x + b.w / 2,
                    renderCenterY: b.y + b.h / 2,
                    isFixed: true
                };
            }
        });
        return { blocks: fixedBlocks, cutLines: [{ x1: bounds[0], y1: midY, x2: bounds[2], y2: midY, label: 'Smart Y-Axis Split (Full Width Available)', isVertical: false }] };
    }

    if (!fusedMask) {
        const standardBlocks = bubbles.map(b => ({
            ...b,
            layoutMode: 'standard',
            renderedText: b.translation,
            fontSize: 20,
            safeW: b.w * 0.85,
            safeH: b.h * 0.85,
            renderCenterX: b.x + b.w / 2,
            renderCenterY: b.y + b.h / 2,
            isConjoined: false
        }));
        return { blocks: standardBlocks, cutLines: [] };
    }

    if (mode === 'unified') {
        const fixedBlocks = bubbles.map(b => {
            if (b.ovalIndex !== undefined && fusedMask.ovals[b.ovalIndex]) {
                const oval = fusedMask.ovals[b.ovalIndex];
                const safeW = oval.rx * 1.6;
                const safeH = oval.ry * 1.5;
                return {
                    ...b,
                    layoutMode: 'unified',
                    renderedText: b.translation,
                    fontSize: 22.0,
                    safeW,
                    safeH,
                    renderCenterX: oval.cx,
                    renderCenterY: oval.cy,
                    isConjoined: true,
                    isFixed: true,
                    ovalData: oval
                };
            } else {
                return {
                    ...b,
                    layoutMode: 'unified',
                    renderedText: b.translation,
                    fontSize: 20,
                    safeW: b.w * 0.85,
                    safeH: b.h * 0.85,
                    renderCenterX: b.x + b.w / 2,
                    renderCenterY: b.y + b.h / 2,
                    isConjoined: false
                };
            }
        });
        return { blocks: fixedBlocks, cutLines: [] };
    } else {
        // CURRENT ENGINE BUG (Diagonal 4-quadrant slice + word clipping)
        const cutLines = [];
        const bounds = fusedMask.bounds;
        const ovals = fusedMask.ovals;

        if (sampleData.name.includes('Screenshot')) {
            // Cut in both X and Y
            const cutX = 400;
            const cutY = 480;
            cutLines.push({ x1: cutX, y1: bounds[1], x2: cutX, y2: bounds[3], label: 'Vertical Cut (x=400)', isVertical: true });
            cutLines.push({ x1: bounds[0], y1: cutY, x2: bounds[2], y2: cutY, label: 'Horizontal Cut (y=480)', isVertical: false });
        } else if (sampleData.name.includes('Diagonal')) {
            const cutY1 = (ovals[0].cy + ovals[1].cy) / 2;
            const cutY2 = (ovals[1].cy + ovals[2].cy) / 2;
            cutLines.push({ x1: bounds[0], y1: cutY1, x2: bounds[2], y2: cutY1, label: `Cut 1 (y=${Math.round(cutY1)})`, isVertical: false });
            cutLines.push({ x1: bounds[0], y1: cutY2, x2: bounds[2], y2: cutY2, label: `Cut 2 (y=${Math.round(cutY2)})`, isVertical: false });
        } else if (sampleData.name.includes('Same Height') || ovals.length === 3) {
            const cutX1 = (ovals[0].cx + ovals[1].cx) / 2;
            const cutX2 = (ovals[1].cx + ovals[2].cx) / 2;
            cutLines.push({ x1: cutX1, y1: bounds[1], x2: cutX1, y2: bounds[3], label: `Cut 1 (x=${Math.round(cutX1)})`, isVertical: true });
            cutLines.push({ x1: cutX2, y1: bounds[1], x2: cutX2, y2: bounds[3], label: `Cut 2 (x=${Math.round(cutX2)})`, isVertical: true });
        } else {
            const cutX = (ovals[0].cx + ovals[1].cx) / 2;
            cutLines.push({ x1: cutX, y1: bounds[1], x2: cutX, y2: bounds[3], label: `Cut (x=${Math.round(cutX)})`, isVertical: true });
        }

        const slicedBlocks = bubbles.map(b => {
            if (sampleData.name.includes('Screenshot')) {
                if (b.id === 'det_box_top') {
                    return {
                        ...b,
                        layoutMode: 'sliced',
                        renderedText: b.translation,
                        fontSize: 14.0,
                        safeW: 90,
                        safeH: 80,
                        renderCenterX: 500,
                        renderCenterY: 360,
                        isSlicedBug: true,
                        note: 'Squeezed into Top-Right 50% Quadrant'
                    };
                } else if (b.id === 'det_box_bot') {
                    return {
                        ...b,
                        layoutMode: 'sliced',
                        renderedText: b.translation,
                        fontSize: 12.0,
                        safeW: 100,
                        safeH: 140,
                        renderCenterX: 300,
                        renderCenterY: 620,
                        isSlicedBug: true,
                        note: 'Squeezed into Bottom-Left 50% Quadrant'
                    };
                } else if (b.id === 'det_box_sword') {
                    return {
                        ...b,
                        layoutMode: 'sliced',
                        renderedText: b.truncatedTranslation,
                        fontSize: 14.0,
                        safeW: 70,
                        safeH: 110,
                        renderCenterX: b.x + b.w / 2,
                        renderCenterY: b.y + b.h / 2,
                        isSlicedBug: true,
                        isWordTruncated: true,
                        note: 'Scissor Cut chopped UNDERESTIM'
                    };
                } else {
                    return {
                        ...b,
                        layoutMode: 'sliced',
                        renderedText: b.translation,
                        fontSize: 18.0,
                        safeW: b.w * 0.85,
                        safeH: b.h * 0.85,
                        renderCenterX: b.x + b.w / 2,
                        renderCenterY: b.y + b.h / 2
                    };
                }
            } else if (b.ovalIndex !== undefined) {
                return {
                    ...b,
                    layoutMode: 'sliced',
                    renderedText: b.translation,
                    fontSize: 8.5,
                    safeW: 54,
                    safeH: 44,
                    renderCenterX: b.x + b.w / 2,
                    renderCenterY: b.y + b.h / 2,
                    isConjoined: true,
                    isSlicedBug: true
                };
            } else {
                return {
                    ...b,
                    layoutMode: 'sliced',
                    renderedText: b.translation,
                    fontSize: 20,
                    safeW: b.w * 0.85,
                    safeH: b.h * 0.85,
                    renderCenterX: b.x + b.w / 2,
                    renderCenterY: b.y + b.h / 2,
                    isConjoined: false
                };
            }
        });
        return { blocks: slicedBlocks, cutLines };
    }
}

// -------------------------------------------------------------
// 4. CANVAS RENDERING
// -------------------------------------------------------------

function renderSingleSvg(svgId, mode) {
    const svg = document.getElementById(svgId);
    if (!svg) return;
    svg.innerHTML = '';

    const data = SAMPLES[currentSample];
    const showPanels = document.getElementById('toggle-panels').checked;
    const showText = document.getElementById('toggle-text').checked;
    const showSeg = document.getElementById('toggle-seg').checked;
    const showFlow = document.getElementById('toggle-flow').checked;
    const showRender = document.getElementById('toggle-render') ? document.getElementById('toggle-render').checked : true;
    const showCuts = document.getElementById('toggle-cuts') ? document.getElementById('toggle-cuts').checked : true;

    // Panels
    const rawPanels = data.panels.map(p => ({ ...p }));
    const orderedPanels = readingOrderPanels(rawPanels, currentRtl);
    orderedPanels.forEach((p, idx) => { p.readingOrderIdx = idx; });

    // Solve Layout
    const { blocks: layoutBlocks, cutLines } = simulateLayoutPlanner(data, mode);
    const processedBubbles = layoutBlocks.map((b, bIdx) => ({
        ...b,
        assignment: assignBubbleToPanels(b, orderedPanels),
        utteranceId: `b${bIdx}`
    }));

    // Background
    const bg = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
    bg.setAttribute('width', '100%');
    bg.setAttribute('height', '100%');
    bg.setAttribute('fill', '#0d1117');
    svg.appendChild(bg);

    // Panels (YOLO26-nano)
    if (showPanels && currentStep >= 2) {
        orderedPanels.forEach((p) => {
            const [x1, y1, x2, y2] = p.box;
            const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
            rect.setAttribute('x', x1);
            rect.setAttribute('y', y1);
            rect.setAttribute('width', x2 - x1);
            rect.setAttribute('height', y2 - y1);
            rect.setAttribute('fill', 'rgba(59, 130, 246, 0.08)');
            rect.setAttribute('stroke', '#3b82f6');
            rect.setAttribute('stroke-width', '2.5');
            rect.setAttribute('rx', '4');
            rect.setAttribute('class', 'svg-panel-box');
            rect.onclick = () => selectPanel(p);
            svg.appendChild(rect);

            // Label
            const tagG = document.createElementNS('http://www.w3.org/2000/svg', 'g');
            const tagBg = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
            tagBg.setAttribute('x', x1 + 8);
            tagBg.setAttribute('y', y1 + 8);
            tagBg.setAttribute('width', '74');
            tagBg.setAttribute('height', '22');
            tagBg.setAttribute('rx', '4');
            tagBg.setAttribute('fill', '#1e40af');

            const tagText = document.createElementNS('http://www.w3.org/2000/svg', 'text');
            tagText.setAttribute('x', x1 + 45);
            tagText.setAttribute('y', y1 + 23);
            tagText.setAttribute('fill', '#ffffff');
            tagText.setAttribute('font-size', '11');
            tagText.setAttribute('font-weight', '700');
            tagText.setAttribute('text-anchor', 'middle');
            tagText.textContent = `Panel ${p.readingOrderIdx}`;

            tagG.appendChild(tagBg);
            tagG.appendChild(tagText);
            svg.appendChild(tagG);
        });
    }

    // Speech Bubble Polygons / Mask
    if (data.fusedMask && showSeg && currentStep >= 4) {
        const fm = data.fusedMask;
        if (fm.isTallSingleBubble) {
            // Draw tall speech bubble with bottom pointy tail matching user screenshot
            const pathEl = document.createElementNS('http://www.w3.org/2000/svg', 'path');
            pathEl.setAttribute('d', 'M 350 280 L 520 310 L 590 450 L 560 620 L 410 740 L 390 830 L 360 750 L 220 630 L 210 380 Z');
            pathEl.setAttribute('fill', 'rgba(255, 255, 255, 0.95)');
            pathEl.setAttribute('stroke', '#000000');
            pathEl.setAttribute('stroke-width', '4');
            svg.appendChild(pathEl);
        } else {
            fm.ovals.forEach((ov) => {
                const ovalEl = document.createElementNS('http://www.w3.org/2000/svg', 'ellipse');
                ovalEl.setAttribute('cx', ov.cx);
                ovalEl.setAttribute('cy', ov.cy);
                ovalEl.setAttribute('rx', ov.rx);
                ovalEl.setAttribute('ry', ov.ry);
                ovalEl.setAttribute('fill', 'rgba(16, 185, 129, 0.16)');
                ovalEl.setAttribute('stroke', '#10b981');
                ovalEl.setAttribute('stroke-width', '2.5');
                svg.appendChild(ovalEl);
            });
        }
    }

    // Other parent speech bubbles (sword & left monster)
    if (showSeg && currentStep >= 4) {
        data.bubbles.forEach(b => {
            if (b.parentBox) {
                const [px1, py1, px2, py2] = b.parentBox;
                const ellipse = document.createElementNS('http://www.w3.org/2000/svg', 'ellipse');
                ellipse.setAttribute('cx', px1 + (px2 - px1) / 2);
                ellipse.setAttribute('cy', py1 + (py2 - py1) / 2);
                ellipse.setAttribute('rx', (px2 - px1) / 2);
                ellipse.setAttribute('ry', (py2 - py1) / 2);
                ellipse.setAttribute('fill', 'rgba(255, 255, 255, 0.92)');
                ellipse.setAttribute('stroke', '#000000');
                ellipse.setAttribute('stroke-width', '3.5');
                svg.appendChild(ellipse);
            }
        });
    }

    // Cut Lines
    if (showCuts && cutLines.length > 0) {
        cutLines.forEach(c => {
            const cutLine = document.createElementNS('http://www.w3.org/2000/svg', 'line');
            cutLine.setAttribute('x1', c.x1);
            cutLine.setAttribute('y1', c.y1);
            cutLine.setAttribute('x2', c.x2);
            cutLine.setAttribute('y2', c.y2);
            cutLine.setAttribute('stroke', mode === 'sliced' ? '#f43f5e' : '#10b981');
            cutLine.setAttribute('stroke-width', '2.5');
            cutLine.setAttribute('stroke-dasharray', '6 4');
            svg.appendChild(cutLine);

            const cutLabel = document.createElementNS('http://www.w3.org/2000/svg', 'text');
            cutLabel.setAttribute('x', c.x1 + 10);
            cutLabel.setAttribute('y', c.y1 - 6);
            cutLabel.setAttribute('fill', mode === 'sliced' ? '#f43f5e' : '#10b981');
            cutLabel.setAttribute('font-size', '10');
            cutLabel.setAttribute('font-weight', '700');
            cutLabel.textContent = c.label;
            svg.appendChild(cutLabel);
        });
    }

    // Rendered Text Overlay
    if (showRender && currentStep >= 6) {
        processedBubbles.forEach((b) => {
            const overlayG = document.createElementNS('http://www.w3.org/2000/svg', 'g');
            const lines = b.renderedText.split('\n');
            const lineH = b.fontSize * 1.25;
            const totalH = lines.length * lineH;
            const startY = b.renderCenterY - totalH / 2 + b.fontSize * 0.9;

            lines.forEach((tLine, lIdx) => {
                const textNode = document.createElementNS('http://www.w3.org/2000/svg', 'text');
                textNode.setAttribute('x', b.renderCenterX);
                textNode.setAttribute('y', startY + lIdx * lineH);
                textNode.setAttribute('fill', '#000000');
                textNode.setAttribute('font-size', b.fontSize);
                textNode.setAttribute('font-weight', '900');
                textNode.setAttribute('font-family', 'CC Wild Words, Bangers, Impact, sans-serif');
                textNode.setAttribute('text-anchor', 'middle');
                textNode.textContent = tLine;
                overlayG.appendChild(textNode);
            });

            // Status Badge
            const badge = document.createElementNS('http://www.w3.org/2000/svg', 'text');
            badge.setAttribute('x', b.renderCenterX);
            badge.setAttribute('y', startY + totalH + 12);
            badge.setAttribute('fill', b.isWordTruncated || b.isSlicedBug ? '#f43f5e' : '#10b981');
            badge.setAttribute('font-size', '10');
            badge.setAttribute('font-weight', '800');
            badge.setAttribute('text-anchor', 'middle');
            badge.textContent = b.note || `Font: ${b.fontSize}px ✅`;
            overlayG.appendChild(badge);

            svg.appendChild(overlayG);
        });
    }
}

function updateCanvas() {
    renderSingleSvg('manga-svg-bug', 'sliced');
    renderSingleSvg('manga-svg-fix', 'unified');

    const data = SAMPLES[currentSample];
    const rawPanels = data.panels.map(p => ({ ...p }));
    const orderedPanels = readingOrderPanels(rawPanels, currentRtl);
    const { blocks: layoutBlocks } = simulateLayoutPlanner(data, engineMode);
    const processedBubbles = layoutBlocks.map((b, bIdx) => ({
        ...b,
        assignment: assignBubbleToPanels(b, orderedPanels),
        utteranceId: `b${bIdx}`
    }));

    updatePromptPayload(orderedPanels, processedBubbles);
    updateXyCutTree(orderedPanels);
}

// -------------------------------------------------------------
// 5. INSPECTOR & SELECTION HANDLERS
// -------------------------------------------------------------

function selectPanel(panel) {
    selectedElement = { type: 'panel', data: panel };
    switchInspectorTab('inspect');

    document.getElementById('inspector-placeholder').classList.add('hidden');
    const details = document.getElementById('inspector-details');
    details.classList.remove('hidden');

    const [x1, y1, x2, y2] = panel.box;
    details.innerHTML = `
        <div class="data-card">
            <div class="data-card-title">
                <span>YOLO26-nano Panel Detection</span>
                <span class="badge-mini">Panel ${panel.readingOrderIdx}</span>
            </div>
            <div class="data-row">
                <span class="data-label">Coordinates [x1,y1,x2,y2]</span>
                <span class="data-value">[${x1}, ${y1}, ${x2}, ${y2}]</span>
            </div>
            <div class="data-row">
                <span class="data-label">Dimensions</span>
                <span class="data-value">${x2 - x1}w &times; ${y2 - y1}h px</span>
            </div>
        </div>
    `;
}

function selectBubble(bubble) {
    selectedElement = { type: 'bubble', data: bubble };
    switchInspectorTab('inspect');

    document.getElementById('inspector-placeholder').classList.add('hidden');
    const details = document.getElementById('inspector-details');
    details.classList.remove('hidden');

    const cat = bubble.assignment.category;
    const catClass = `cat-${cat.replace('_', '')}`;

    details.innerHTML = `
        <div class="data-card">
            <div class="data-card-title">
                <span>TranslationBlock &bull; ${bubble.id}</span>
                <span class="cat-badge ${catClass}">${cat.toUpperCase()}</span>
            </div>
            <div class="data-row">
                <span class="data-label">Japanese Source</span>
                <span class="data-value">"${bubble.text.replace(/\n/g, ' ')}"</span>
            </div>
            <div class="data-row">
                <span class="data-label">English Translation</span>
                <span class="data-value">"${bubble.translation.replace(/\n/g, ' ')}"</span>
            </div>
        </div>
    `;
}

// -------------------------------------------------------------
// 6. PROMPT & TREE VIEWERS
// -------------------------------------------------------------

function updatePromptPayload(orderedPanels, processedBubbles) {
    const codeEl = document.getElementById('prompt-code-block');
    let output = '';

    output += `// PASS 1 STRUCTURED REQUEST (TranslationPrompts.kt)\n`;
    output += `You are an expert manga translator. Source: Japanese -> Target: English.\n\n`;

    processedBubbles.forEach((b) => {
        const panelTag = b.assignment.panelIndex !== null ? `[panel:${b.assignment.panelIndex}]` : `[panel:orphan]`;
        const textClean = b.text.replace(/\n/g, ' ');
        output += `${b.utteranceId}|${panelTag} ${textClean}\n`;
    });

    codeEl.textContent = output;
}

function updateXyCutTree(orderedPanels) {
    const treeEl = document.getElementById('xycut-tree-content');
    const badge = document.getElementById('xycut-direction-badge');
    badge.textContent = currentRtl ? 'RTL Mode (Manga)' : 'LTR Mode (Webtoon)';

    let html = `
        <div class="tree-node">
            <div class="tree-node-title">Page Root (800x1100)</div>
            <div class="tree-children">
                <div class="tree-node">
                    <div class="tree-node-title">&darr; Horizontal Panel Split</div>
                </div>
            </div>
        </div>
    `;
    treeEl.innerHTML = html;
}

// -------------------------------------------------------------
// 7. UI CONTROLLERS
// -------------------------------------------------------------

function setViewLayout(layout) {
    viewLayout = layout;
    const wrapper = document.getElementById('canvas-wrapper');
    const cardBug = document.getElementById('card-bug');
    const cardFix = document.getElementById('card-fix');

    document.getElementById('btn-view-single').classList.toggle('active', layout === 'single');
    document.getElementById('btn-view-dual').classList.toggle('active', layout === 'dual');

    if (layout === 'dual') {
        wrapper.classList.add('dual-view');
        cardBug.style.display = 'flex';
        cardFix.style.display = 'flex';
    } else {
        wrapper.classList.remove('dual-view');
        cardBug.style.display = engineMode === 'sliced' ? 'flex' : 'none';
        cardFix.style.display = engineMode === 'unified' ? 'flex' : 'none';
    }
    updateCanvas();
}

function setEngineMode(mode) {
    engineMode = mode;
    updateCanvas();
}

function setReadingDirection(dir) {
    currentRtl = dir === 'RTL';
    document.getElementById('btn-rtl').classList.toggle('active', currentRtl);
    document.getElementById('btn-ltr').classList.toggle('active', !currentRtl);
    updateCanvas();
}

function switchSample(sampleKey) {
    currentSample = sampleKey;
    if (sampleKey === 'webtoon-strip') {
        setReadingDirection('LTR');
    } else {
        setReadingDirection('RTL');
    }
    updateCanvas();
}

function switchInspectorTab(tabKey) {
    ['compare', 'inspect', 'prompt', 'xycut'].forEach(key => {
        document.getElementById(`tab-${key}`).classList.toggle('hidden', key !== tabKey);
        document.getElementById(`tab-btn-${key}`).classList.toggle('active', key === tabKey);
    });
}

function applyZoom(zoom) {
    currentZoom = Math.max(0.4, Math.min(2.5, zoom));
    document.getElementById('btn-zoom-val').textContent = `${Math.round(currentZoom * 100)}%`;
}

function zoomIn() { applyZoom(currentZoom + 0.15); }
function zoomOut() { applyZoom(currentZoom - 0.15); }
function resetZoom() { applyZoom(1.0); }
function fitHeight() { applyZoom(1.0); }
function fitWidth() { applyZoom(1.0); }

// Initial Boot
document.addEventListener('DOMContentLoaded', () => {
    setViewLayout('dual');
    updateCanvas();
});
