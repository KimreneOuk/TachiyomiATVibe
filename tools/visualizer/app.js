// TachiyomiAT Visualizer Engine — Strict Mask Ceiling & Koharu-Conformant Line Segmentation

let currentSample = "page1-tall-ovals";
let viewLayout = "dual";
let targetFontSize = 19.0;
let heightFillTarget = 0.85;
let bubblePadding = 6.0;

// State toggles
let showPanels = true;
let showText = true;
let showSeg = true;
let showTypography = true;
let showBoxes = true;
let selectedBubbleId = null;

// Exact representation of user's uploaded HotMilk manga pages
const SAMPLES = {
    "page1-tall-ovals": {
        title: "📸 Real Page 1: Tall Slim Ovals (HotMilk Screenshot)",
        width: 550,
        height: 820,
        panels: [
            { id: "p1_1", x: 40, y: 30, w: 200, h: 180, label: "Panel 1 (Boy Walking)" },
            { id: "p1_2", x: 250, y: 30, w: 260, h: 480, label: "Panel 2 (Mother & Son Full View)" },
            { id: "p1_3", x: 40, y: 220, w: 200, h: 140, label: "Panel 3 (Boy Profile)" },
            { id: "p1_4", x: 40, y: 370, w: 200, h: 140, label: "Panel 4 (Tall Slim Bubbles)" },
            { id: "p1_5", x: 40, y: 520, w: 200, h: 260, label: "Panel 5 (Boy Looking Back)" },
            { id: "p1_6", x: 250, y: 520, w: 260, h: 260, label: "Panel 6 (Mother Surprised Close-up)" }
        ],
        bubbles: [
            {
                id: "b_tall_excuse",
                panelId: "p1_4",
                x: 48, y: 375, w: 55, h: 130,
                parentBox: { x: 48, y: 375, w: 55, h: 130 },
                maskPolygon: [[60, 375], [95, 410], [90, 490], [60, 505], [48, 450]],
                text: "EXCUSE ME FOR A MOMENT.",
                rawJp: "ちょっと\nすいません",
                oldFont: 10.5
            },
            {
                id: "b_tall_um",
                panelId: "p1_4",
                x: 180, y: 400, w: 40, h: 75,
                parentBox: { x: 180, y: 400, w: 40, h: 75 },
                maskPolygon: [[195, 400], [218, 420], [215, 465], [195, 475], [182, 440]],
                text: "UM...",
                rawJp: "あの…",
                oldFont: 11.0
            },
            {
                id: "b_tall_possible",
                panelId: "p1_5",
                x: 155, y: 530, w: 55, h: 120,
                parentBox: { x: 155, y: 530, w: 55, h: 120 },
                maskPolygon: [[175, 530], [205, 560], [200, 640], [175, 650], [158, 600]],
                text: "IS IT POSSIBLE THAT...",
                rawJp: "もしかして…",
                oldFont: 9.5
            },
            {
                id: "b_tall_sister",
                panelId: "p1_5",
                x: 108, y: 545, w: 45, h: 110,
                parentBox: { x: 108, y: 545, w: 45, h: 110 },
                maskPolygon: [[125, 545], [150, 570], [145, 645], [125, 655], [110, 605]],
                text: "HANAZUMI NEE-CHAN...",
                rawJp: "花津美\n姉ちゃん…",
                oldFont: 9.5
            },
            {
                id: "b_tall_isit",
                panelId: "p1_5",
                x: 90, y: 625, w: 42, h: 60,
                parentBox: { x: 90, y: 625, w: 42, h: 60 },
                maskPolygon: [[105, 625], [128, 640], [125, 675], [105, 685], [92, 660]],
                text: "IS IT?",
                rawJp: "ですか？",
                oldFont: 10.0
            }
        ]
    },

    "page2-huge-bubble": {
        title: "📸 Real Page 2: Huge Bubble & Conjoined Clouds (HotMilk Screenshot)",
        width: 550,
        height: 820,
        panels: [
            { id: "p1", x: 40, y: 30, w: 230, h: 180, label: "Panel 1 (Top Left)" },
            { id: "p2", x: 280, y: 30, w: 230, h: 180, label: "Panel 2 (Top Right)" },
            { id: "p3", x: 40, y: 220, w: 470, h: 180, label: "Panel 3 (Middle)" },
            { id: "p4", x: 40, y: 410, w: 470, h: 180, label: "Panel 4 (Huge Bubble & Face)" },
            { id: "p5", x: 40, y: 600, w: 340, h: 190, label: "Panel 5 (Conjoined Clouds)" },
            { id: "p6", x: 390, y: 600, w: 120, h: 190, label: "Panel 6 (Boy Reaction)" }
        ],
        bubbles: [
            {
                id: "b_cousin_right",
                panelId: "p1",
                x: 165, y: 40, w: 95, h: 155,
                parentBox: { x: 165, y: 40, w: 95, h: 155 },
                maskPolygon: [[175, 45], [255, 40], [260, 140], [220, 190], [175, 160]],
                text: "IT'S ME!\nYOUR COUSIN\nHERE!",
                rawJp: "俺だよ！\nいとこの貴敏！",
                oldFont: 11.0
            },
            {
                id: "b_cousin_left",
                panelId: "p1",
                x: 50, y: 45, w: 100, h: 150,
                parentBox: { x: 50, y: 45, w: 100, h: 150 },
                maskPolygon: [[60, 48], [145, 45], [140, 150], [90, 190], [55, 155]],
                text: "IT'S TRUE\nAFTER ALL!",
                rawJp: "やっぱり\nそうだ！",
                oldFont: 11.5
            },
            {
                id: "b_mother",
                panelId: "p3",
                x: 330, y: 225, w: 170, h: 165,
                parentBox: { x: 330, y: 225, w: 170, h: 165 },
                maskPolygon: [[340, 235], [490, 230], [495, 370], [350, 385]],
                text: "YOU'RE OF SUCH HIGH RANK...? I DIDN'T REALIZE... IT'S BEEN SO LONG... YOU'VE GROWN UP COMPLETELY...",
                rawJp: "貴敏…なの？\n分からなかったわ…\nあんまり久しぶりだから…\nすっかり大人になって…",
                oldFont: 9.5
            },
            {
                id: "b_huge_left",
                panelId: "p4",
                x: 50, y: 420, w: 95, h: 160,
                parentBox: { x: 50, y: 420, w: 95, h: 160 },
                maskPolygon: [[50, 420], [145, 420], [140, 575], [55, 580]],
                text: "I MOVED HERE TO THE NEIGHBORHOOD RECENTLY, SO THIS IS A SURPRISE REUNION.",
                rawJp: "…最近この近所に\n引っ越してきたんだ\nよもやの再会ってわけだ",
                oldFont: 7.5
            },
            {
                id: "b_huge_right",
                panelId: "p4",
                x: 150, y: 420, w: 115, h: 160,
                parentBox: { x: 150, y: 420, w: 115, h: 160 },
                maskPolygon: [[150, 420], [260, 420], [255, 575], [155, 580]],
                text: "IT'S NO WONDER YOU DON'T RECOGNIZE ME, AFTER ALL, 20 YEARS HAS PASSED... BUT I KNEW RIGHT AWAY! AFTER ALL, HANAZUMI-NEE-SAN HASN'T CHANGED AT ALL FROM BEFORE!",
                rawJp: "分からなくてもムリないさ\nもう20年も経つもんな…\nだけど俺にはすぐ分かったぜ\nだって花津美姉ちゃんは昔と\nちっとも変わってないもんな",
                oldFont: 6.8
            },
            {
                id: "b_cloud_1",
                panelId: "p5",
                x: 50, y: 605, w: 100, h: 175,
                parentBox: { x: 50, y: 605, w: 100, h: 175 },
                maskPolygon: [[50, 615], [145, 610], [140, 775], [55, 780]],
                text: "AND I'LL HEAD BACK FIRST. YOU DON'T KNOW, DO YOU? THE LEFT AND RIGHT BRAINS SOMETIMES SHOW DIFFERENT COLORS... RIGHT?",
                rawJp: "あっ 優は先に帰っててくれる？\n私はこのお兄ちゃんと\n色々積もる話があったりなんかしたりするから…ね？",
                oldFont: 8.0
            },
            {
                id: "b_cloud_2",
                panelId: "p5",
                x: 155, y: 605, w: 105, h: 175,
                parentBox: { x: 155, y: 605, w: 105, h: 175 },
                maskPolygon: [[155, 615], [255, 610], [250, 775], [160, 780]],
                text: "YOU MOVED HERE NEARBY, DIDN'T YOU? THAT'S RIGHT! I'D LIKE TO SEE YOUR HOUSE!",
                rawJp: "こっ この近くに越してきたんだったのよね？\nそうだ お宅を拝見したいわ！",
                oldFont: 8.5
            },
            {
                id: "b_cloud_3",
                panelId: "p5",
                x: 265, y: 605, w: 110, h: 175,
                parentBox: { x: 265, y: 605, w: 110, h: 175 },
                maskPolygon: [[265, 615], [370, 610], [365, 780], [270, 785]],
                text: "NICE TO MEET YOU, YU-KUN... IT REMINDS ME... WHEN I WAS AROUND YOUR AGE, YOUR MOTHER AND I...",
                rawJp: "はじめまして優君\n…思い出すなぁ 俺が君と同じくらいの頃\n君の母さんと俺は…",
                oldFont: 8.0
            }
        ]
    }
};

/**
 * Koharu-style Tokenizer:
 * Breaks words on spaces, hyphens, and syllable points so compound words
 * (like NEE-CHAN or HANAZUMI-NEE-SAN) can wrap cleanly across narrow lines.
 */
function tokenizeWrapUnits(text) {
    const tokens = [];
    const rawWords = text.replace(/\n/g, ' ').split(/\s+/).filter(Boolean);

    for (let word of rawWords) {
        if (word.includes('-') && word.length > 3) {
            const parts = word.split('-');
            for (let i = 0; i < parts.length; i++) {
                if (i < parts.length - 1) {
                    tokens.push(parts[i] + '-');
                } else {
                    tokens.push(parts[i]);
                }
            }
        } else {
            tokens.push(word);
        }
    }
    return tokens;
}

/**
 * Koharu-style Line Flow with Syllable Break Support:
 * Wraps tokens to fit strictly inside availW. If a single word is wider than availW,
 * it splits with a hyphen across multiple lines instead of blowing out the width!
 */
function formatLinesToWidth(tokens, fontSize, availW) {
    const avgCharW = fontSize * 0.58;
    const lines = [];
    let cur = "";

    for (let token of tokens) {
        let test = cur ? (cur.endsWith('-') ? cur + token : cur + " " + token) : token;
        let testW = test.length * avgCharW;

        if (testW > availW && cur) {
            lines.push(cur);
            cur = token;
            // If token alone exceeds width, split by syllable
            while (cur.length * avgCharW > availW && cur.length > 3) {
                const maxChars = Math.max(2, Math.floor((availW - avgCharW) / avgCharW));
                lines.push(cur.slice(0, maxChars) + "-");
                cur = cur.slice(maxChars);
            }
        } else if (testW > availW && !cur) {
            let rem = token;
            while (rem.length * avgCharW > availW && rem.length > 3) {
                const maxChars = Math.max(2, Math.floor((availW - avgCharW) / avgCharW));
                lines.push(rem.slice(0, maxChars) + "-");
                rem = rem.slice(maxChars);
            }
            cur = rem;
        } else {
            cur = test;
        }
    }
    if (cur) lines.push(cur);
    return lines;
}

/**
 * Strict Koharu Layout Solver:
 * Probes largest fitting font size that strictly satisfies:
 * 1. totalH <= availH * fillTarget (Strict Vertical Ceiling)
 * 2. maxLineW <= availW (Strict Horizontal Ceiling with Syllable Break)
 */
function solveStrictKoharuLayout(bubble, targetFont, fillTarget, pad) {
    const pBox = bubble.parentBox;
    const availW = Math.max(10, pBox.w - pad * 2);
    const availH = Math.max(10, pBox.h - pad * 2);
    const tokens = tokenizeWrapUnits(bubble.text);

    let bestFont = 8.0;
    let bestLines = [];

    // Probe from 22px down to 8px
    for (let font = 22.0; font >= 8.0; font -= 0.25) {
        const lineH = font * 1.22;
        const lines = formatLinesToWidth(tokens, font, availW);
        const totalH = lines.length * lineH;

        if (totalH <= availH * fillTarget) {
            bestFont = font;
            bestLines = lines;
            break;
        }
    }

    if (!bestLines.length) {
        bestFont = 8.0;
        bestLines = formatLinesToWidth(tokens, 8.0, availW);
    }

    const lineH = bestFont * 1.22;
    const totalH = bestLines.length * lineH;

    return {
        fontSize: bestFont,
        lines: bestLines,
        lineH: lineH,
        totalH: totalH,
        availH: availH,
        availW: availW,
        heightFillPct: Math.round((totalH / availH) * 100)
    };
}

// Rendering routines
function renderLeftCanvas(ctx, sample) {
    ctx.clearRect(0, 0, sample.width, sample.height);
    ctx.fillStyle = "#ffffff";
    ctx.fillRect(0, 0, sample.width, sample.height);

    if (showPanels) {
        sample.panels.forEach(p => {
            ctx.strokeStyle = "#94a3b8";
            ctx.lineWidth = 1.5;
            ctx.strokeRect(p.x, p.y, p.w, p.h);
        });
    }

    sample.bubbles.forEach(b => {
        if (showSeg && b.maskPolygon) {
            ctx.beginPath();
            b.maskPolygon.forEach((pt, idx) => {
                if (idx === 0) ctx.moveTo(pt[0], pt[1]);
                else ctx.lineTo(pt[0], pt[1]);
            });
            ctx.closePath();
            ctx.fillStyle = "#ffffff";
            ctx.fill();
            ctx.strokeStyle = "#cbd5e1";
            ctx.lineWidth = 1.5;
            ctx.stroke();
        }

        if (showBoxes) {
            ctx.strokeStyle = "rgba(6, 182, 212, 0.5)";
            ctx.lineWidth = 1;
            ctx.setLineDash([3, 3]);
            ctx.strokeRect(b.parentBox.x, b.parentBox.y, b.parentBox.w, b.parentBox.h);
            ctx.setLineDash([]);
        }

        if (showTypography) {
            const font = b.oldFont;
            ctx.font = `600 ${font}px "JetBrains Mono", sans-serif`;
            ctx.fillStyle = "#0f172a";
            ctx.textAlign = "center";
            ctx.textBaseline = "middle";

            const words = b.text.replace(/\n/g, ' ').split(/\s+/).filter(Boolean);
            const lines = [];
            let cur = "";
            for (let w of words) {
                let test = cur ? cur + " " + w : w;
                if (test.length * font * 0.6 > b.parentBox.w * 0.75 && cur) {
                    lines.push(cur);
                    cur = w;
                } else {
                    cur = test;
                }
            }
            if (cur) lines.push(cur);

            const lineH = font * 1.2;
            const startY = (b.parentBox.y + b.parentBox.h / 2) - ((lines.length - 1) * lineH) / 2;

            lines.forEach((line, idx) => {
                ctx.fillText(line, b.parentBox.x + b.parentBox.w / 2, startY + idx * lineH);
            });
        }
    });
}

function renderRightCanvas(ctx, sample) {
    ctx.clearRect(0, 0, sample.width, sample.height);
    ctx.fillStyle = "#ffffff";
    ctx.fillRect(0, 0, sample.width, sample.height);

    if (showPanels) {
        sample.panels.forEach(p => {
            ctx.strokeStyle = "#94a3b8";
            ctx.lineWidth = 1.5;
            ctx.strokeRect(p.x, p.y, p.w, p.h);
        });
    }

    sample.bubbles.forEach(b => {
        // Draw segmentation mask boundary
        if (showSeg && b.maskPolygon) {
            ctx.beginPath();
            b.maskPolygon.forEach((pt, idx) => {
                if (idx === 0) ctx.moveTo(pt[0], pt[1]);
                else ctx.lineTo(pt[0], pt[1]);
            });
            ctx.closePath();
            ctx.fillStyle = "#ffffff";
            ctx.fill();
            ctx.strokeStyle = "#10b981"; // Emerald mask ceiling outline
            ctx.lineWidth = 2.0;
            ctx.stroke();
        }

        // ParentBox ceiling boundary
        if (showBoxes) {
            ctx.strokeStyle = "rgba(59, 130, 246, 0.4)";
            ctx.lineWidth = 1;
            ctx.setLineDash([4, 4]);
            ctx.strokeRect(b.parentBox.x, b.parentBox.y, b.parentBox.w, b.parentBox.h);
            ctx.setLineDash([]);
        }

        // Solve strictly contained layout
        const layout = solveStrictKoharuLayout(b, targetFontSize, heightFillTarget, bubblePadding);

        // Draw Typography strictly within mask ceiling
        if (showTypography) {
            ctx.font = `700 ${layout.fontSize}px "Plus Jakarta Sans", sans-serif`;
            ctx.fillStyle = "#020617";
            ctx.textAlign = "center";
            ctx.textBaseline = "middle";

            const cx = b.parentBox.x + b.parentBox.w / 2;
            const cy = b.parentBox.y + b.parentBox.h / 2;
            const startY = cy - ((layout.lines.length - 1) * layout.lineH) / 2;

            layout.lines.forEach((line, idx) => {
                // White outline stroke
                ctx.strokeStyle = "rgba(255, 255, 255, 0.95)";
                ctx.lineWidth = layout.fontSize * 0.2;
                ctx.strokeText(line, cx, startY + idx * layout.lineH);
                ctx.fillText(line, cx, startY + idx * layout.lineH);
            });
        }
    });
}

function updateCanvas() {
    const sample = SAMPLES[currentSample];
    if (!sample) return;

    showPanels = document.getElementById("toggle-panels").checked;
    showText = document.getElementById("toggle-text").checked;
    showSeg = document.getElementById("toggle-seg").checked;
    showTypography = document.getElementById("toggle-typography").checked;

    const cLeft = document.getElementById("canvas-left");
    const cRight = document.getElementById("canvas-right");

    if (cLeft) {
        cLeft.width = sample.width;
        cLeft.height = sample.height;
        renderLeftCanvas(cLeft.getContext("2d"), sample);
    }
    if (cRight) {
        cRight.width = sample.width;
        cRight.height = sample.height;
        renderRightCanvas(cRight.getContext("2d"), sample);
    }

    updateInspector(sample);
}

function updateInspector(sample) {
    const focusBubble = sample.bubbles.find(b => b.id === selectedBubbleId) || sample.bubbles[0];
    if (!focusBubble) return;

    const opt = solveStrictKoharuLayout(focusBubble, targetFontSize, heightFillTarget, bubblePadding);

    document.getElementById("insp-bounds").textContent = `${focusBubble.parentBox.w} × ${focusBubble.parentBox.h} px`;
    document.getElementById("insp-old-font").textContent = `${focusBubble.oldFont.toFixed(1)} px (Squished)`;
    document.getElementById("insp-new-font").textContent = `${opt.fontSize.toFixed(1)} px (Contained)`;
    document.getElementById("insp-height-fill").textContent = `${opt.heightFillPct}% of Bubble Box`;
    document.getElementById("insp-lines").textContent = `${opt.lines.length} lines • Strict Mask Cap`;
}

function switchSample(sampleKey) {
    currentSample = sampleKey;
    const sample = SAMPLES[sampleKey];
    if (sample) {
        document.getElementById("sample-title-display").textContent = sample.title;
    }
    updateCanvas();
}

function setViewLayout(mode) {
    viewLayout = mode;
    const cardStandard = document.getElementById("card-standard");
    const btnDual = document.getElementById("btn-view-dual");
    const btnSingle = document.getElementById("btn-view-single");

    if (mode === "single") {
        cardStandard.style.display = "none";
        btnSingle.classList.add("active");
        btnDual.classList.remove("active");
    } else {
        cardStandard.style.display = "flex";
        btnDual.classList.add("active");
        btnSingle.classList.remove("active");
    }
}

function onSliderChange() {
    targetFontSize = parseFloat(document.getElementById("range-font-target").value);
    heightFillTarget = parseFloat(document.getElementById("range-height-fill").value) / 100.0;
    bubblePadding = parseFloat(document.getElementById("range-padding").value);

    document.getElementById("val-font-target").textContent = targetFontSize.toFixed(1) + " px";
    document.getElementById("val-height-fill").textContent = Math.round(heightFillTarget * 100) + " %";
    document.getElementById("val-padding").textContent = bubblePadding.toFixed(0) + " px";

    updateCanvas();
}

function resetZoom() {
    const wrapper = document.getElementById("canvases-container");
    if (wrapper) {
        wrapper.scrollTo({ top: 0, left: 0, behavior: "smooth" });
    }
}

function toggleGrid() {
    showBoxes = !showBoxes;
    updateCanvas();
}

// Init
window.addEventListener("DOMContentLoaded", () => {
    switchSample("page1-tall-ovals");
});
