/**
 * TachiyomiAT Visual Detection & Reading Order Structure Visualizer
 *
 * Side-by-Side Visual Comparison:
 * Left: Standard Original Raw Detection & Placement (Ground Truth)
 * Right: New Implementation (text_bubble as Starting Truth + YOLO11 Mask as Upper Ceiling + Height Maximization)
 */

let currentRtl = true;
let currentSample = 'conjoined-thin-tall-ovals';
let currentStep = 6;
let viewLayout = 'dual'; // 'dual' (side-by-side) or 'single'
let selectedElement = null;
let currentZoom = 1.0;

// Sample Data Sets (Coordinates in [x1, y1, x2, y2] 800x1100 space)
const SAMPLES = {
    'conjoined-thin-tall-ovals': {
        name: '⚡ Conjoined Thin Tall Ovals: Multi-Lobe Full Height Utilization',
        width: 800,
        height: 1100,
        panels: [
            { id: 'p0', rawIdx: 0, box: [30, 30, 770, 700], desc: 'Dramatic Monologue Panel with Conjoined Thin Tall Ovals' },
            { id: 'p1', rawIdx: 1, box: [30, 720, 770, 1070], desc: 'Bottom Wide Panel' }
        ],
        // Single Fused YOLO11-seg mask encompassing both connected tall lobes
        fusedMask: {
            bounds: [175, 95, 425, 570],
            isConjoinedThinOvals: true,
            pathD: `
                M 245 100
                C 285 100, 310 135, 310 200
                C 310 215, 305 230, 300 245
                C 340 180, 385 140, 410 160
                C 435 180, 425 280, 420 380
                C 415 480, 380 560, 340 560
                C 310 560, 290 520, 285 480
                L 270 590 L 260 480
                C 230 520, 180 520, 180 440
                C 180 340, 185 200, 205 130
                C 215 105, 230 100, 245 100
                Z
            `
        },
        // 2 distinct tall, thin text_bubble bounding boxes from Detector v4 (Starting Truths!)
        bubbles: [
            {
                id: 'det_box_left_lobe',
                japaneseText: 'あいつの計画は最初から\n破綻していたんだ…',
                translation: 'His entire\nplan had been\nfatally flawed\nfrom the very\nbeginning\nof all this!',
                x: 195,
                y: 150,
                w: 80,
                h: 300,
                // Left Tall Lobe (w=125, h=420, aspect ratio 0.30)
                parentBox: [180, 100, 305, 520],
                direction: 'TTB',
                bubbleGroupId: 'left_tall_lobe'
            },
            {
                id: 'det_box_right_lobe',
                japaneseText: 'それでも最後まで\n戦うしかなかったんだ！',
                translation: 'Even so,\nthere was no\nchoice left but\nto fight until\nthe bitter end\nno matter what!',
                x: 310,
                y: 190,
                w: 85,
                h: 320,
                // Right Tall Lobe (w=135, h=420, aspect ratio 0.32 - 25px waist overlap with left lobe!)
                parentBox: [280, 140, 415, 560],
                tail: [340, 555, 360, 610, 320, 550],
                direction: 'TTB',
                bubbleGroupId: 'right_tall_lobe'
            },
            {
                id: 'det_box_reaction',
                japaneseText: 'すべては終わったんだ…',
                translation: 'Everything is over now...',
                x: 180,
                y: 840,
                w: 440,
                h: 120,
                parentBox: [140, 780, 660, 990],
                direction: 'LTR',
                bubbleGroupId: 'solo_reaction'
            }
        ]
    },
    'ultra-thin-tall-oval': {
        name: '📏 Ultra-Thin Tall Oval: Full Vertical Height Utilization',
        width: 800,
        height: 1100,
        panels: [
            { id: 'p0', rawIdx: 0, box: [30, 30, 770, 680], desc: 'Dramatic Vertical Dialogue Panel with Ultra-Thin Ovals' },
            { id: 'p1', rawIdx: 1, box: [30, 700, 770, 1070], desc: 'Bottom Panel' }
        ],
        fusedMask: {
            bounds: [170, 90, 450, 570],
            isSlimOvalPair: true
        },
        bubbles: [
            {
                id: 'det_box_left_speaker',
                japaneseText: 'そんな事…\n絶対に許されるはずがない！',
                translation: 'Something\nlike that\ncould never\nbe forgiven\nby anyone\nat all!',
                x: 200,
                y: 160,
                w: 70,
                h: 300,
                parentBox: [180, 100, 290, 520],
                tail: [210, 510, 185, 570, 240, 505],
                direction: 'TTB',
                bubbleGroupId: 'speaker_left'
            },
            {
                id: 'det_box_right_speaker',
                japaneseText: '黙れ！\n勝者が全てを決めるのだ！',
                translation: 'Silence!\nThe victor\nis the one\nwho decides\neverything\nin this world!',
                x: 350,
                y: 190,
                w: 70,
                h: 310,
                parentBox: [330, 130, 440, 560],
                tail: [405, 550, 435, 610, 375, 545],
                direction: 'TTB',
                bubbleGroupId: 'speaker_right'
            }
        ]
    },
    'slim-oval-dialogue-pair': {
        name: '🗣️ Slim Tall Oval Dialogue Bubbles (Two Speakers Overlapping)',
        width: 800,
        height: 1100,
        panels: [
            { id: 'p0', rawIdx: 0, box: [30, 30, 770, 680], desc: 'Dialogue Panel with Slim Oval Bubbles' },
            { id: 'p1', rawIdx: 1, box: [30, 700, 770, 1070], desc: 'Bottom Panel' }
        ],
        fusedMask: {
            bounds: [180, 95, 455, 530],
            isSlimOvalPair: true
        },
        bubbles: [
            {
                id: 'det_box_left_speaker',
                japaneseText: 'まさか…\nあの男が犯人なのか？',
                translation: 'Could that man\nreally be the\nculprit...?',
                x: 215,
                y: 190,
                w: 90,
                h: 180,
                parentBox: [185, 100, 335, 460],
                tail: [215, 450, 185, 510, 245, 445],
                direction: 'TTB',
                bubbleGroupId: 'speaker_left'
            },
            {
                id: 'det_box_right_speaker',
                japaneseText: '証拠は全て揃っている！\n言い逃れはできないぞ！',
                translation: 'All the evidence\npoints to him!\nHe cannot escape!',
                x: 325,
                y: 230,
                w: 95,
                h: 200,
                parentBox: [290, 140, 450, 520],
                tail: [420, 510, 450, 570, 390, 505],
                direction: 'TTB',
                bubbleGroupId: 'speaker_right'
            }
        ]
    },
    'peanut-double-lobe': {
        name: '🥜 Double-Lobe Peanut Bubble (User Reference Image 1)',
        width: 800,
        height: 1100,
        panels: [
            { id: 'p0', rawIdx: 0, box: [30, 30, 770, 880], desc: 'Main Panel with Double-Lobe Peanut Bubble' },
            { id: 'p1', rawIdx: 1, box: [30, 900, 770, 1070], desc: 'Bottom Reaction Panel' }
        ],
        fusedMask: {
            bounds: [90, 80, 710, 850],
            isPeanut: true,
            pathD: `
                M 450 90
                C 570 90, 690 140, 690 250
                C 690 330, 610 380, 520 400
                C 590 440, 650 510, 640 620
                C 630 730, 510 790, 380 780
                L 440 855 L 340 780
                C 220 770, 100 710, 100 580
                C 100 460, 200 400, 290 380
                C 220 350, 190 290, 190 230
                C 190 130, 310 90, 450 90
                Z
            `
        },
        bubbles: [
            {
                id: 'det_box_top_lobe',
                japaneseText: 'まさか…\nこんな所で会うとは',
                translation: 'No way...\nTo meet in a place like this...',
                x: 320,
                y: 190,
                w: 240,
                h: 120,
                parentBox: [210, 95, 680, 385],
                direction: 'TTB',
                bubbleGroupId: 'top_lobe'
            },
            {
                id: 'det_box_bot_lobe',
                japaneseText: 'ずっと探していたんだ！\n無事でよかった…！',
                translation: "I've been searching for you all along!\nThank goodness you're safe...!",
                x: 230,
                y: 510,
                w: 320,
                h: 140,
                parentBox: [110, 395, 635, 775],
                direction: 'TTB',
                bubbleGroupId: 'bot_lobe'
            }
        ]
    },
    'dialogue-overlapping-bubbles': {
        name: '👥 Dialogue Overlapping Bubbles (User Reference Image 2: Two Characters)',
        width: 800,
        height: 1100,
        panels: [
            { id: 'p0', rawIdx: 0, box: [40, 40, 760, 680], desc: 'Dialogue Panel with Overlapping Bubbles' },
            { id: 'p1', rawIdx: 1, box: [40, 700, 760, 1060], desc: 'Bottom Wide Panel' }
        ],
        fusedMask: {
            bounds: [140, 70, 670, 540],
            isDialoguePair: true
        },
        bubbles: [
            {
                id: 'det_box_left_speaker',
                japaneseText: 'プロジェクトの進捗は\nどうなっている？',
                translation: 'How is the progress on the new project going?',
                x: 200,
                y: 160,
                w: 220,
                h: 120,
                parentBox: [150, 80, 470, 390],
                tail: [195, 385, 175, 430, 225, 380],
                direction: 'TTB',
                bubbleGroupId: 'speaker_left'
            },
            {
                id: 'det_box_right_speaker',
                japaneseText: '順調です！予定通り\n今週中に納品できます！',
                translation: 'Everything is on track! We can deliver within this week!',
                x: 390,
                y: 280,
                w: 230,
                h: 130,
                parentBox: [340, 190, 660, 500],
                tail: [590, 495, 620, 540, 560, 490],
                direction: 'TTB',
                bubbleGroupId: 'speaker_right'
            }
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
// 2. CANVAS RENDERING
// -------------------------------------------------------------

function renderRawPlacementSvg(svgId) {
    const svg = document.getElementById(svgId);
    if (!svg) return;
    svg.innerHTML = '';

    const data = SAMPLES[currentSample];
    const rawPanels = data.panels.map(p => ({ ...p }));
    const orderedPanels = readingOrderPanels(rawPanels, currentRtl);

    // Background
    const bg = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
    bg.setAttribute('width', '100%');
    bg.setAttribute('height', '100%');
    bg.setAttribute('fill', '#0d1117');
    svg.appendChild(bg);

    // Panels
    orderedPanels.forEach(p => {
        const [x1, y1, x2, y2] = p.box;
        const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
        rect.setAttribute('x', x1);
        rect.setAttribute('y', y1);
        rect.setAttribute('width', x2 - x1);
        rect.setAttribute('height', y2 - y1);
        rect.setAttribute('fill', 'rgba(59, 130, 246, 0.05)');
        rect.setAttribute('stroke', '#3b82f6');
        rect.setAttribute('stroke-width', '2');
        svg.appendChild(rect);
    });

    // Bubble contours (Peanut, Overlapping Dialogue, Slim Ovals, Conjoined Thin Ovals)
    if (data.fusedMask?.pathD) {
        const pathEl = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        pathEl.setAttribute('d', data.fusedMask.pathD.trim());
        pathEl.setAttribute('fill', '#ffffff');
        pathEl.setAttribute('stroke', '#000000');
        pathEl.setAttribute('stroke-width', '5');
        svg.appendChild(pathEl);
    } else if (data.fusedMask?.isDialoguePair || data.fusedMask?.isSlimOvalPair) {
        data.bubbles.forEach(b => {
            if (b.parentBox) {
                const [px1, py1, px2, py2] = b.parentBox;
                const ellipse = document.createElementNS('http://www.w3.org/2000/svg', 'ellipse');
                ellipse.setAttribute('cx', (px1 + px2) / 2);
                ellipse.setAttribute('cy', (py1 + py2) / 2);
                ellipse.setAttribute('rx', (px2 - px1) / 2);
                ellipse.setAttribute('ry', (py2 - py1) / 2);
                ellipse.setAttribute('fill', '#ffffff');
                ellipse.setAttribute('stroke', '#000000');
                ellipse.setAttribute('stroke-width', '4');
                svg.appendChild(ellipse);

                if (b.tail) {
                    const tailPoly = document.createElementNS('http://www.w3.org/2000/svg', 'polygon');
                    tailPoly.setAttribute('points', `${b.tail[0]},${b.tail[1]} ${b.tail[2]},${b.tail[3]} ${b.tail[4]},${b.tail[5]}`);
                    tailPoly.setAttribute('fill', '#ffffff');
                    tailPoly.setAttribute('stroke', '#000000');
                    tailPoly.setAttribute('stroke-width', '4');
                    svg.appendChild(tailPoly);
                }
            }
        });
    }

    // 1. Draw distinct text_bubble bounding boxes (CYAN DASHED)
    data.bubbles.forEach(b => {
        if (b.parentBox) {
            const [px1, py1, px2, py2] = b.parentBox;
            const pw = px2 - px1;
            const ph = py2 - py1;

            const pRect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
            pRect.setAttribute('x', px1);
            pRect.setAttribute('y', py1);
            pRect.setAttribute('width', pw);
            pRect.setAttribute('height', ph);
            pRect.setAttribute('fill', 'rgba(6, 182, 212, 0.14)');
            pRect.setAttribute('stroke', '#06b6d4');
            pRect.setAttribute('stroke-width', '2.5');
            pRect.setAttribute('stroke-dasharray', '5 4');
            pRect.setAttribute('rx', '8');
            svg.appendChild(pRect);

            // Label
            const pLabel = document.createElementNS('http://www.w3.org/2000/svg', 'text');
            pLabel.setAttribute('x', px1 + 6);
            pLabel.setAttribute('y', py1 + 16);
            pLabel.setAttribute('fill', '#0891b2');
            pLabel.setAttribute('font-size', '11');
            pLabel.setAttribute('font-weight', '800');
            pLabel.textContent = `text_bubble [${pw}x${ph}]`;
            svg.appendChild(pLabel);
        }
    });

    // 2. Draw raw Japanese OCR text detection boxes (AMBER)
    data.bubbles.forEach(b => {
        const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
        rect.setAttribute('x', b.x);
        rect.setAttribute('y', b.y);
        rect.setAttribute('width', b.w);
        rect.setAttribute('height', b.h);
        rect.setAttribute('fill', 'rgba(245, 158, 11, 0.18)');
        rect.setAttribute('stroke', '#f59e0b');
        rect.setAttribute('stroke-width', '2');
        rect.setAttribute('rx', '4');
        svg.appendChild(rect);

        // Original Text
        const textG = document.createElementNS('http://www.w3.org/2000/svg', 'g');
        const lines = b.japaneseText.split('\n');
        const fontSize = 16;
        const lineH = fontSize * 1.3;
        const startY = b.y + fontSize + 4;

        lines.forEach((line, lIdx) => {
            const textNode = document.createElementNS('http://www.w3.org/2000/svg', 'text');
            textNode.setAttribute('x', b.x + b.w / 2);
            textNode.setAttribute('y', startY + lIdx * lineH);
            textNode.setAttribute('fill', '#000000');
            textNode.setAttribute('font-size', fontSize);
            textNode.setAttribute('font-weight', '700');
            textNode.setAttribute('text-anchor', 'middle');
            textNode.textContent = line;
            textG.appendChild(textNode);
        });

        svg.appendChild(textG);
    });
}

function renderNewImplementationSvg(svgId) {
    const svg = document.getElementById(svgId);
    if (!svg) return;
    svg.innerHTML = '';

    const data = SAMPLES[currentSample];
    const rawPanels = data.panels.map(p => ({ ...p }));
    const orderedPanels = readingOrderPanels(rawPanels, currentRtl);

    // Background
    const bg = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
    bg.setAttribute('width', '100%');
    bg.setAttribute('height', '100%');
    bg.setAttribute('fill', '#0d1117');
    svg.appendChild(bg);

    // Panels
    orderedPanels.forEach(p => {
        const [x1, y1, x2, y2] = p.box;
        const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
        rect.setAttribute('x', x1);
        rect.setAttribute('y', y1);
        rect.setAttribute('width', x2 - x1);
        rect.setAttribute('height', y2 - y1);
        rect.setAttribute('fill', 'rgba(59, 130, 246, 0.05)');
        rect.setAttribute('stroke', '#3b82f6');
        rect.setAttribute('stroke-width', '2');
        svg.appendChild(rect);
    });

    // 1. Fused Segmentation Mask (UPPER CEILING - GREEN OUTLINE)
    if (data.fusedMask?.pathD) {
        const pathEl = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        pathEl.setAttribute('d', data.fusedMask.pathD.trim());
        pathEl.setAttribute('fill', '#ffffff');
        pathEl.setAttribute('stroke', '#10b981');
        pathEl.setAttribute('stroke-width', '5');
        svg.appendChild(pathEl);
    } else if (data.fusedMask?.isDialoguePair || data.fusedMask?.isSlimOvalPair) {
        data.bubbles.forEach(b => {
            if (b.parentBox) {
                const [px1, py1, px2, py2] = b.parentBox;
                const ellipse = document.createElementNS('http://www.w3.org/2000/svg', 'ellipse');
                ellipse.setAttribute('cx', (px1 + px2) / 2);
                ellipse.setAttribute('cy', (py1 + py2) / 2);
                ellipse.setAttribute('rx', (px2 - px1) / 2);
                ellipse.setAttribute('ry', (py2 - py1) / 2);
                ellipse.setAttribute('fill', '#ffffff');
                ellipse.setAttribute('stroke', '#10b981');
                ellipse.setAttribute('stroke-width', '4');
                svg.appendChild(ellipse);

                if (b.tail) {
                    const tailPoly = document.createElementNS('http://www.w3.org/2000/svg', 'polygon');
                    tailPoly.setAttribute('points', `${b.tail[0]},${b.tail[1]} ${b.tail[2]},${b.tail[3]} ${b.tail[4]},${b.tail[5]}`);
                    tailPoly.setAttribute('fill', '#ffffff');
                    tailPoly.setAttribute('stroke', '#10b981');
                    tailPoly.setAttribute('stroke-width', '4');
                    svg.appendChild(tailPoly);
                }
            }
        });
    }

    // Ceiling Badge
    if (data.fusedMask?.bounds) {
        const [fx1, fy1] = data.fusedMask.bounds;
        const mLabel = document.createElementNS('http://www.w3.org/2000/svg', 'text');
        mLabel.setAttribute('x', fx1 + 10);
        mLabel.setAttribute('y', fy1 - 10);
        mLabel.setAttribute('fill', '#10b981');
        mLabel.setAttribute('font-size', '12');
        mLabel.setAttribute('font-weight', '800');
        mLabel.textContent = '▲ YOLO11 Mask (Upper Growth & Clip Ceiling)';
        svg.appendChild(mLabel);
    }

    // 2. Render each block rooted inside its OWN distinct text_bubble box (Starting Ground Truth)
    // AND utilizing full vertical height!
    data.bubbles.forEach(b => {
        const pBox = b.parentBox || [b.x, b.y, b.x + b.w, b.y + b.h];
        const pw = pBox[2] - pBox[0];
        const ph = pBox[3] - pBox[1];
        const pcx = (pBox[0] + pBox[2]) / 2;
        const pcy = (pBox[1] + pBox[3]) / 2;

        // Container boundary rooted in its text_bubble
        const cRect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
        cRect.setAttribute('x', pBox[0]);
        cRect.setAttribute('y', pBox[1]);
        cRect.setAttribute('width', pw);
        cRect.setAttribute('height', ph);
        cRect.setAttribute('fill', 'rgba(6, 182, 212, 0.06)');
        cRect.setAttribute('stroke', '#06b6d4');
        cRect.setAttribute('stroke-width', '2');
        cRect.setAttribute('stroke-dasharray', '4 3');
        cRect.setAttribute('rx', '10');
        svg.appendChild(cRect);

        // Render English Translated Text maximizing vertical height
        const safeW = pw * 0.82;
        const isTall = ph > pw * 1.8;
        const fontSize = isTall ? 19.0 : (data.fusedMask?.isSlimOvalPair ? 18.0 : 21.0);
        const textLines = wrapText(b.translation, safeW, fontSize);
        const lineH = fontSize * 1.35;
        const totalH = textLines.length * lineH;
        const startY = pcy - totalH / 2 + fontSize * 0.9;

        const textG = document.createElementNS('http://www.w3.org/2000/svg', 'g');
        textLines.forEach((tLine, lIdx) => {
            const textNode = document.createElementNS('http://www.w3.org/2000/svg', 'text');
            textNode.setAttribute('x', pcx);
            textNode.setAttribute('y', startY + lIdx * lineH);
            textNode.setAttribute('fill', '#000000');
            textNode.setAttribute('font-size', fontSize);
            textNode.setAttribute('font-weight', '900');
            textNode.setAttribute('text-anchor', 'middle');
            textNode.textContent = tLine;
            textG.appendChild(textNode);
        });

        // Status Badge
        const badge = document.createElementNS('http://www.w3.org/2000/svg', 'text');
        badge.setAttribute('x', pcx);
        badge.setAttribute('y', pBox[3] - 8);
        badge.setAttribute('fill', '#059669');
        badge.setAttribute('font-size', '10');
        badge.setAttribute('font-weight', '800');
        badge.setAttribute('text-anchor', 'middle');
        badge.textContent = `Full Height (${Math.round(totalH/ph*100)}%) • ${fontSize}px ✅`;
        textG.appendChild(badge);

        svg.appendChild(textG);
    });
}

function wrapText(text, safeW, fontSize) {
    const rawLines = text.split('\n');
    const outLines = [];
    const approxCharW = fontSize * 0.52;
    const maxChars = Math.max(6, Math.floor(safeW / approxCharW));

    rawLines.forEach(rawLine => {
        const words = rawLine.split(' ');
        let cur = '';
        words.forEach(w => {
            if ((cur + ' ' + w).trim().length <= maxChars) {
                cur = (cur + ' ' + w).trim();
            } else {
                if (cur) outLines.push(cur);
                cur = w;
            }
        });
        if (cur) outLines.push(cur);
    });

    return outLines.length > 0 ? outLines : [text];
}

function updateCanvas() {
    renderRawPlacementSvg('manga-svg-bug');
    renderNewImplementationSvg('manga-svg-fix');
}

// -------------------------------------------------------------
// UI CONTROLLERS
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
        cardBug.style.display = 'flex';
        cardFix.style.display = 'none';
    }
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

document.addEventListener('DOMContentLoaded', () => {
    setViewLayout('dual');
    updateCanvas();
});
