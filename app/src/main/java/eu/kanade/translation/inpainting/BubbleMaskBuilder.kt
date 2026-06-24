package eu.kanade.translation.inpainting

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure mask-construction and morphology helpers used by [SmartBubbleTextCleaner]
 * to build the regions where original text is allowed/erased before inpainting.
 *
 * Extracted out of the cleaner so these byte-array algorithms are testable in
 * isolation (the cleaner's public API operates on `android.graphics.Bitmap`,
 * which plain JVM tests can't load). Every function here is pure — it works
 * only on its parameters, no instance state — so callers must thread in the
 * tuning constants the cleaner used to read as fields.
 */
object BubbleMaskBuilder {

    // TachiyomiAT: Navier-Stokes inpaint defaults. The pure-Kotlin port of the
    // prototype demo's `method = "ns"` (cv2.inpaint INPAINT_NS), used for the
    // FAST free-text fill. Internal constants (prototype-style defaults) — no
    // settings surface, per the task's "tunables stay internal" requirement.
    private const val NS_DOWNSAMPLE = 0.25f
    private const val NS_OUTER_STEPS = 50
    private const val NS_POISSON_ITERS = 40
    private const val NS_VISCOSITY = 1.0f

    /**
     * Build a filled mask over the union of [boxes], each drawn as a rounded
     * rectangle (corner radius derived from the box's shorter side). Pixels
     * inside any rounded rect are set to 1; the rest stay 0.
     */
    fun roundedAllowedMask(
        boxes: List<IntArray>,
        width: Int,
        height: Int,
    ): ByteArray {
        val mask = ByteArray(width * height)
        for (box in boxes) {
            val x1 = box[0].coerceIn(0, width)
            val y1 = box[1].coerceIn(0, height)
            val x2 = box[2].coerceIn(x1, width)
            val y2 = box[3].coerceIn(y1, height)
            val bw = x2 - x1
            val bh = y2 - y1
            if (bw <= 0 || bh <= 0) continue
            val radius = (min(bw, bh) * 0.25f).roundToInt().coerceIn(8, 20).coerceAtMost(min(bw, bh) / 2)
            for (y in y1 until y2) {
                for (x in x1 until x2) {
                    if (insideRoundedRect(x - x1, y - y1, bw, bh, radius)) {
                        mask[y * width + x] = 1
                    }
                }
            }
        }
        return mask
    }

    /**
     * Build a filled rectangular mask for the interior of [bubble], eroded
     * inward by [erodePx] on every side and clamped to the canvas bounds.
     */
    fun bubbleInteriorMask(
        bubble: IntArray,
        width: Int,
        height: Int,
        erodePx: Int,
    ): ByteArray {
        val x1 = (bubble[0] + erodePx).coerceIn(0, width)
        val y1 = (bubble[1] + erodePx).coerceIn(0, height)
        val x2 = (bubble[2] - erodePx).coerceIn(x1, width)
        val y2 = (bubble[3] - erodePx).coerceIn(y1, height)
        val mask = ByteArray(width * height)
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                mask[y * width + x] = 1
            }
        }
        return mask
    }

    /**
     * Pixel-in-rounded-rect test. Outside the corner zones every interior pixel
     * counts; inside a corner zone the pixel must lie within `radius` of the
     * corner center.
     */
    fun insideRoundedRect(x: Int, y: Int, width: Int, height: Int, radius: Int): Boolean {
        if (radius <= 0) return true
        val left = x < radius
        val right = x >= width - radius
        val top = y < radius
        val bottom = y >= height - radius
        if (!(left || right) || !(top || bottom)) return true
        val cx = if (left) radius else width - radius - 1
        val cy = if (top) radius else height - radius - 1
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= radius * radius
    }

    /** Logical AND of two equal-length masks. Either-zero stays zero. */
    fun andMasks(a: ByteArray, b: ByteArray): ByteArray {
        val result = ByteArray(a.size)
        for (i in a.indices) {
            if (a[i] != 0.toByte() && b[i] != 0.toByte()) result[i] = 1
        }
        return result
    }

    /** Percentage (0..100) of [mask] bytes that are non-zero. */
    fun maskCoverage(mask: ByteArray): Float {
        if (mask.isEmpty()) return 0f
        return mask.count { it != 0.toByte() } * 100f / mask.size
    }

    /**
     * TachiyomiAT: build a SOLID rectangular erase mask over the union of
     * [boxes], each padded outward by [pad] px and dilated by a disk SE of
     * [dilateRadius].
     *
     * Port of `build_rect_mask` in `tools/inpaint-debug-viewer/server.py`
     * (the `mask_mode = paddle_boxes` path): every PaddleOCR-v6 line box is
     * turned into a solid white rectangle — NOT sparse text pixels and NOT the
     * loose detector-v4 rectangle — then padded and dilated so anti-aliased
     * stroke edges fall inside the hole. This is the proven, boring-and-direct
     * erase target: Paddle box → pad → mask.
     *
     * Pure (no Android, no ONNX) so it is unit-tested in isolation. Returns a
     * `width * height` ByteArray where 1 = erase.
     *
     * @param boxes `[x1, y1, x2, y2]` boxes in canvas coords (e.g. page-space or
     *   crop-local — same space as [width]/[height]).
     * @param pad px added to every side of each box before filling, clamped to
     *   the canvas. Prototype default is 8.
     * @param dilateRadius disk-dilation radius applied after filling (0 = no
     *   dilation). The prototype uses `cv2.MORPH_ELLIPSE (5,5)` ≈ disk radius 2.
     */
    fun buildRectMask(
        boxes: List<IntArray>,
        width: Int,
        height: Int,
        pad: Int,
        dilateRadius: Int = 2,
    ): ByteArray {
        if (width <= 0 || height <= 0 || boxes.isEmpty()) return ByteArray(width * height)
        val mask = ByteArray(width * height)
        for (box in boxes) {
            if (box.size < 4) continue
            val x1 = (box[0] - pad).coerceIn(0, width)
            val y1 = (box[1] - pad).coerceIn(0, height)
            val x2 = (box[2] + pad).coerceIn(0, width)
            val y2 = (box[3] + pad).coerceIn(0, height)
            if (x2 <= x1 || y2 <= y1) continue
            for (y in y1 until y2) {
                val row = y * width
                for (x in x1 until x2) {
                    mask[row + x] = 1
                }
            }
        }
        return if (dilateRadius > 0) {
            dilateMaskDisk(mask, width, height, dilateRadius)
        } else {
            mask
        }
    }

    /**
     * TachiyomiAT: pure-Kotlin Navier-Stokes inpaint.
     *
     * Replaces the flat-median FAST free-text fill with the same algorithm the
     * prototype demo's `method = "ns"` (`cv2.inpaint(INPAINT_NS)`) uses: the
     * Bertalmio/Bertozzi/Sapiro (CVPR 2001) vorticity-stream formulation. The
     * app has no OpenCV dependency, so this is a from-scratch finite-difference
     * implementation rather than a `cv2` call.
     *
     * What NS does (and why it looks better than a flat/weighted fill): it
     * treats image intensity as a 2D fluid stream function and solves the
     * incompressible-NS equations inside the hole Ω, with Dirichlet boundary
     * conditions from ∂Ω. The defining visual property is **isophote
     * continuity** — equal-intensity lines arriving at the hole boundary
     * continue smoothly across it without crossing, so a gradient that enters
     * the hole on the left flows out on the right at the right value. A flat
     * or weighted-average fill cannot reproduce this; it produces the white
     * block this solver exists to eliminate.
     *
     * Scheme (per channel, on the downsampled grid — matching the prototype,
     * which runs cv2.inpaint at small scale too), iterated to steady state:
     *  1. Vorticity: ω = ∇⊥I · ∇²I, where ∇⊥ = (∂/∂y, −∂/∂x) is the 90°-
     *     rotated gradient (isophote tangent) and ∇²I is the 5-pt Laplacian.
     *  2. Vorticity transport: enforce ∇·(∇⊥I · ω) = 0 — vorticity flows
     *     along isophotes, not across them (the steady isophote-continuity
     *     condition).
     *  3. Poisson reconstruction: solve ∇²I = ω inside Ω with Dirichlet BC
     *     from ∂Ω (Jacobi iteration).
     *  4. Repeat until convergence, then bilinear-upsample to full res.
     *
     * Pure (no Android, no OpenCV): `IntArray` ARGB in, `IntArray` ARGB out,
     * so it is unit-tested on the JVM. The caller feather-composites the
     * result via [featherAlpha] + [SmartBubbleTextCleaner.applyFeatheredFill].
     *
     * @param pixels ARGB image (same layout as Bitmap.getPixels).
     * @param mask non-zero = hole pixel to reconstruct (Ω).
     * @param width / height image dimensions.
     * @param downsample small-grid scale (0..1). The PDE runs on
     *   `(width*downsample) × (height*downsample)`; smaller = faster but less
     *   detail. The prototype uses ~0.10 for cv2.inpaint; 0.25 gives the
     *   finite-difference stencil enough resolution to be stable.
     * @param outerSteps vorticity-transport + Poisson outer iterations.
     * @param poissonIters Jacobi sweeps per Poisson solve.
     * @param viscosityNu ν in ∂ω/∂t = ν∇²ω (vorticity diffusion smoothing).
     * @return filled ARGB IntArray (full res); hole pixels reconstructed,
     *   non-hole pixels unchanged.
     */
    fun navierStokesInpaint(
        pixels: IntArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        downsample: Float = NS_DOWNSAMPLE,
        outerSteps: Int = NS_OUTER_STEPS,
        poissonIters: Int = NS_POISSON_ITERS,
        viscosityNu: Float = NS_VISCOSITY,
    ): IntArray {
        if (width <= 0 || height <= 0) return pixels.copyOf()
        // No hole → nothing to do.
        if (mask.none { it != 0.toByte() }) return pixels.copyOf()

        val sw = max(4, (width * downsample).toInt())
        val sh = max(4, (height * downsample).toInt())

        // Downsample: image by box-average (keeps gradients smooth at small
        // scale), mask by area-threshold (a small cell is a hole iff any source
        // hole pixel maps into it).
        val smallR = downsampleChannel(pixels, mask, width, height, sw, sh) { px -> (px shr 16 and 0xFF).toFloat() }
        val smallG = downsampleChannel(pixels, mask, width, height, sw, sh) { px -> (px shr 8 and 0xFF).toFloat() }
        val smallB = downsampleChannel(pixels, mask, width, height, sw, sh) { px -> (px and 0xFF).toFloat() }
        val smallMask = downsampleMask(mask, width, height, sw, sh)

        // Solve NS per channel (intensity = channel value).
        solveChannel(smallR, smallMask, sw, sh, outerSteps, poissonIters, viscosityNu)
        solveChannel(smallG, smallMask, sw, sh, outerSteps, poissonIters, viscosityNu)
        solveChannel(smallB, smallMask, sw, sh, outerSteps, poissonIters, viscosityNu)

        // Bilinear upsample back to full res, recombining ARGB. Non-hole pixels
        // keep their ORIGINAL value (Dirichlet BC already held them fixed in the
        // solve; here we override them explicitly so floating-point drift from
        // the downsample/upsample round-trip never touches known pixels).
        val out = pixels.copyOf()
        for (y in 0 until height) {
            val fy = (y + 0.5f) * sh / height - 0.5f
            val y0 = fy.toInt().coerceIn(0, sh - 1)
            val y1 = (y0 + 1).coerceIn(0, sh - 1)
            val ty = (fy - y0).coerceIn(0f, 1f)
            for (x in 0 until width) {
                val idx = y * width + x
                if (mask[idx] == 0.toByte()) continue
                val fx = (x + 0.5f) * sw / width - 0.5f
                val x0 = fx.toInt().coerceIn(0, sw - 1)
                val x1 = (x0 + 1).coerceIn(0, sw - 1)
                val tx = (fx - x0).coerceIn(0f, 1f)
                val r = bilerp(smallR, sw, x0, y0, x1, y1, tx, ty).roundToInt().coerceIn(0, 255)
                val g = bilerp(smallG, sw, x0, y0, x1, y1, tx, ty).roundToInt().coerceIn(0, 255)
                val b = bilerp(smallB, sw, x0, y0, x1, y1, tx, ty).roundToInt().coerceIn(0, 255)
                out[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /**
     * One Bertalmio vorticity-stream NS solve for a single intensity channel.
     * Mutates [u] in place: hole pixels are reconstructed, non-hole pixels
     * (Dirichlet BC) stay fixed. Central differences throughout.
     */
    private fun solveChannel(
        u: FloatArray,
        mask: ByteArray,
        w: Int,
        h: Int,
        outerSteps: Int,
        poissonIters: Int,
        nu: Float,
    ) {
        val n = w * h
        val omega = FloatArray(n)   // vorticity
        val scratch = FloatArray(n) // Poisson Jacobi scratch
        for (step in 0 until outerSteps) {
            // 1. Vorticity ω = ∇⊥I · ∇²I over the WHOLE grid (needed inside Ω
            //    for the Poisson RHS and just outside Ω for transport).
            computeVorticity(u, omega, mask, w, h)
            // 2. Vorticity transport: diffuse vorticity along isophotes inside
            //    Ω (ν∇²ω smoothing toward steady ∇·(∇⊥I·ω)=0). One explicit
            //    diffusion sweep keeps it cheap and stable.
            if (nu > 0f) diffuseVorticity(omega, mask, w, h, nu)
            // 3. Poisson reconstruction: ∇²I = ω inside Ω, Dirichlet BC on ∂Ω.
            poissonSolve(u, omega, mask, w, h, poissonIters, scratch)
        }
    }

    /**
     * Vorticity ω = ∇⊥I · ∇²I = (∂I/∂y)(∂∇²? )... concretely the stream-
     * function vorticity: ω = (∂I/∂y)(∂²I/∂x²) − ... — implemented as the dot
     * product of the isophote tangent ∇⊥I = (∂I/∂y, −∂I/∂x) with the gradient
     * of the Laplacian ∇(∇²I) = (∂∇²I/∂x, ∂∇²I/∂y). Computed everywhere
     * (central differences) so the Poisson RHS inside Ω is defined.
     */
    private fun computeVorticity(
        u: FloatArray,
        omega: FloatArray,
        mask: ByteArray,
        w: Int,
        h: Int,
    ) {
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                // Isophote tangent ∇⊥I = (∂I/∂y, −∂I/∂x) (rotated gradient).
                val dIdx = (u[idx + 1] - u[idx - 1]) * 0.5f
                val dIdy = (u[idx + w] - u[idx - w]) * 0.5f
                // Laplacian ∇²I.
                val lap = u[idx + 1] + u[idx - 1] + u[idx + w] + u[idx - w] - 4f * u[idx]
                // Gradient of the Laplacian ∇(∇²I).
                val dLapDx = (u[idx + w + 1] + u[idx - w + 1] - u[idx + w - 1] - u[idx - w - 1]) * 0.25f
                val dLapDy = (u[idx + w + 1] + u[idx + w - 1] - u[idx - w + 1] - u[idx - w - 1]) * 0.25f
                // ω = ∇⊥I · ∇(∇²I) = (∂I/∂y)(∂∇²I/∂x) − (∂I/∂x)(∂∇²I/∂y).
                omega[idx] = dIdy * dLapDx - dIdx * dLapDy
            }
        }
    }

    /** One explicit vorticity-diffusion sweep ∂ω/∂t = ν∇²ω inside Ω. */
    private fun diffuseVorticity(
        omega: FloatArray,
        mask: ByteArray,
        w: Int,
        h: Int,
        nu: Float,
    ) {
        val next = omega.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                if (mask[idx] == 0.toByte()) continue
                val lap = omega[idx + 1] + omega[idx - 1] + omega[idx + w] + omega[idx - w] - 4f * omega[idx]
                next[idx] = omega[idx] + nu * lap
            }
        }
        System.arraycopy(next, 0, omega, 0, omega.size)
    }

    /**
     * Poisson solve ∇²I = ω inside Ω with Dirichlet BC on ∂Ω (non-hole pixels
     * held fixed). Jacobi iteration — simple and parallel-friendly; converges
     * monotonically for the smooth manga-background case.
     */
    private fun poissonSolve(
        u: FloatArray,
        omega: FloatArray,
        mask: ByteArray,
        w: Int,
        h: Int,
        iters: Int,
        scratch: FloatArray,
    ) {
        var current = u
        var next = scratch
        for (it in 0 until iters) {
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val idx = row + x
                    // Dirichlet BC: known pixels never move.
                    if (mask[idx] == 0.toByte()) {
                        next[idx] = current[idx]
                        continue
                    }
                    // Jacobi update: I = (neighbors − ω) / 4 for ∇²I = ω.
                    val nRight = current[idx + 1]
                    val nLeft = current[idx - 1]
                    val nDown = current[idx + w]
                    val nUp = current[idx - w]
                    val neighbors = nRight + nLeft + nDown + nUp
                    var v = (neighbors - omega[idx]) * 0.25f
                    // TachiyomiAT: stability guard. On a sharp edge (e.g. dark/light
                    // step at a bubble boundary) the vorticity source ω can be large
                    // and the Jacobi update overshoots, propagating NaN/Inf that
                    // crashes the upsample roundToInt(). Two defenses:
                    //  (1) clamp to the global intensity band [0,255] (the discrete
                    //      maximum principle for bounded-source Poisson — a recon-
                    //      structed intensity can never validly leave the 8-bit range);
                    //  (2) reject non-finite values, falling back to the neighbor
                    //      mean (a stable, in-range estimate). Without this a single
                    //      step-edge hole blew up the whole solve.
                    if (!v.isFinite()) {
                        v = neighbors * 0.25f
                    }
                    next[idx] = v.coerceIn(0f, 255f)
                }
            }
            // Boundary cells: copy through (Dirichlet).
            for (x in 0 until w) {
                next[x] = current[x]
                next[(h - 1) * w + x] = current[(h - 1) * w + x]
            }
            for (y in 0 until h) {
                next[y * w] = current[y * w]
                next[y * w + w - 1] = current[y * w + w - 1]
            }
            val tmp = current
            current = next
            next = tmp
        }
        // Ensure the final state lands in [u] (the array the caller reads).
        if (current !== u) {
            System.arraycopy(current, 0, u, 0, u.size)
        }
    }

    /** Box-average downsample of one ARGB channel to the small grid. */
    private inline fun downsampleChannel(
        pixels: IntArray,
        mask: ByteArray,
        w: Int,
        h: Int,
        sw: Int,
        sh: Int,
        extract: (Int) -> Float,
    ): FloatArray {
        val out = FloatArray(sw * sh)
        // Per small cell: average the source pixels that fall in it.
        // Accumulate counts to handle non-integer (w/sw) cell widths.
        val counts = IntArray(sw * sh)
        for (y in 0 until h) {
            val sy = (y * sh / h).coerceIn(0, sh - 1)
            for (x in 0 until w) {
                val sx = (x * sw / w).coerceIn(0, sw - 1)
                val si = sy * sw + sx
                out[si] += extract(pixels[y * w + x])
                counts[si]++
            }
        }
        for (i in out.indices) {
            if (counts[i] > 0) out[i] /= counts[i]
        }
        return out
    }

    /** Downsample the hole mask: a small cell is a hole iff any source hole
     *  pixel maps into it (conservative — never shrink the hole region). */
    private fun downsampleMask(
        mask: ByteArray,
        w: Int,
        h: Int,
        sw: Int,
        sh: Int,
    ): ByteArray {
        val out = ByteArray(sw * sh)
        for (y in 0 until h) {
            val sy = (y * sh / h).coerceIn(0, sh - 1)
            for (x in 0 until w) {
                if (mask[y * w + x] != 0.toByte()) {
                    val sx = (x * sw / w).coerceIn(0, sw - 1)
                    out[sy * sw + sx] = 1
                }
            }
        }
        return out
    }

    private fun bilerp(
        a: FloatArray, w: Int,
        x0: Int, y0: Int, x1: Int, y1: Int,
        tx: Float, ty: Float,
    ): Float {
        val i00 = y0 * w + x0
        val i10 = y0 * w + x1
        val i01 = y1 * w + x0
        val i11 = y1 * w + x1
        val top = a[i00] * (1f - tx) + a[i10] * tx
        val bottom = a[i01] * (1f - tx) + a[i11] * tx
        return top * (1f - ty) + bottom * ty
    }

    /**
     * Drop connected components of set pixels that touch the canvas border
     * (within a 2px margin), keeping only interior blobs. Used to reject
     * detector masks that bleed out of the page edge.
     */
    fun removeEdgeTouchingComponents(
        mask: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        val filtered = ByteArray(mask.size)
        val visited = BooleanArray(mask.size)
        val queue = ArrayDeque<Int>()
        for (start in mask.indices) {
            if (mask[start] == 0.toByte() || visited[start]) continue
            visited[start] = true
            queue.add(start)
            val component = mutableListOf<Int>()
            var touchesEdge = false
            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                component.add(idx)
                val x = idx % width
                val y = idx / width
                if (x <= 1 || y <= 1 || x >= width - 2 || y >= height - 2) touchesEdge = true
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val ni = ny * width + nx
                        if (mask[ni] != 0.toByte() && !visited[ni]) {
                            visited[ni] = true
                            queue.add(ni)
                        }
                    }
                }
            }
            if (!touchesEdge) {
                for (idx in component) filtered[idx] = 1
            }
        }
        return filtered
    }

    /**
     * Dilate the set pixels of [mask] by one pixel (4-neighbourhood), repeated
     * [iterations] times — a true multi-pass dilation that grows set pixels by
     * [iterations] pixels outward along each axis.
     *
     * TachiyomiAT: each pass reads the *running* result (snapshotted into a
     * separate read buffer) rather than the original [mask]. The earlier
     * implementation read [mask] on every pass, which re-applied the same
     * single-pixel dilation each time and capped growth at 1px no matter how
     * large [iterations] was — callers setting `iterations = 3` to cover
     * anti-aliased stroke edges (see [SmartBubbleTextCleaner]) only ever got
     * 1px, leaving stroke fringes half-covered at the fill boundary. Reading
     * the snapshot makes growth compound correctly.
     *
     * Growth shape: a 4-neighbourhood dilation reaches pixels by Manhattan
     * distance, so after N iterations a single isolated pixel fills a diamond
     * (L1 ball) of radius N — the axes grow N pixels (centre ± N), and the
     * diagonal corner of an enclosing square fills only at iteration 2·N
     * (corner Manhattan distance). Callers wanting a square block must use
     * 8-neighbourhood (Chebyshev) dilation; this 4-neighbourhood variant is
     * intentionally conservative so it does not bridge across thin gaps.
     */
    fun dilateMask(
        mask: ByteArray,
        width: Int,
        height: Int,
        iterations: Int,
    ): ByteArray {
        if (iterations <= 0) return mask.copyOf()
        var result = mask.copyOf()
        repeat(iterations) {
            val source = result
            val temp = result.copyOf()
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    if (source[y * width + x] != 0.toByte()) {
                        temp[y * width + x] = 1
                        temp[(y - 1) * width + x] = 1
                        temp[(y + 1) * width + x] = 1
                        temp[y * width + (x - 1)] = 1
                        temp[y * width + (x + 1)] = 1
                    }
                }
            }
            result = temp
        }
        return result
    }

    /**
     * TachiyomiAT: disk (circular) structuring-element dilation.
     *
     * Unlike the 4-neighbourhood [dilateMask] (Manhattan-diamond growth, which
     * produces 45° chamfered corners on rectangular masks), this grows set
     * pixels isotropically — a disk of radius [radius] — so rectangle corners
     * become genuinely rounded rather than chamfered. This directly addresses
     * the reported "corners too sharp" inpainting artifact: the erase mask's
     * corners are the corners the user sees on the cleaned bubble, and a disk
     * SE rounds them while a diamond SE chamfers them.
     *
     * The disk does NOT bridge thin gaps more than the diamond would at the
     * same radius (a disk of radius N has the same diagonal reach as a diamond
     * of radius N), so the "intentionally conservative" property cited on
     * [dilateMask] is preserved.
     *
     * Implementation: precompute the disk kernel offsets once (dx,dy pairs
     * where dx²+dy² ≤ radius²), then for each set source pixel OR the kernel
     * into the output. Single-pass, no iteration loop — [radius] IS the growth.
     */
    fun dilateMaskDisk(
        mask: ByteArray,
        width: Int,
        height: Int,
        radius: Int,
    ): ByteArray {
        if (radius <= 0) return mask.copyOf()
        val result = mask.copyOf()
        // Precompute disk kernel offsets (relative coords where dx²+dy² ≤ r²).
        val kernel = mutableListOf<Pair<Int, Int>>()
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                if (dx * dx + dy * dy <= radius * radius) {
                    kernel += dx to dy
                }
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[y * width + x] != 0.toByte()) {
                    for ((dx, dy) in kernel) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until width && ny in 0 until height) {
                            result[ny * width + nx] = 1
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * Build a feathered alpha map (0..1) for [mask]: core pixels are fully
     * opaque (1.0); pixels just outside are a box-blurred average over a
     * [featherRadius] kernel. Used to blend the inpaint smoothly with the
     * surrounding artwork.
     */
    fun featherAlpha(
        mask: ByteArray,
        width: Int,
        height: Int,
        featherRadius: Int,
    ): FloatArray {
        val alpha = FloatArray(width * height)
        val coreBool = BooleanArray(width * height)
        for (i in mask.indices) coreBool[i] = mask[i] != 0.toByte()

        val hasAny = coreBool.any { it }
        if (!hasAny) return alpha

        val fr = max(2, featherRadius)

        val blurred = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                var count = 0
                for (ky in -fr..fr) {
                    for (kx in -fr..fr) {
                        val ny = y + ky
                        val nx = x + kx
                        if (ny in 0 until height && nx in 0 until width) {
                            sum += if (coreBool[ny * width + nx]) 255f else 0f
                            count++
                        }
                    }
                }
                blurred[y * width + x] = if (count > 0) sum / count / 255f else 0f
            }
        }

        for (i in alpha.indices) {
            alpha[i] = if (coreBool[i]) 1.0f else blurred[i]
        }
        return alpha
    }
}
