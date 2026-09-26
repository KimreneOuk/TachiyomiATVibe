package eu.kanade.translation.engines.rendering

import eu.kanade.translation.engines.vision.segmentation.MaskGeometry
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 *  slice 3: pure [MaskTextRegionPlanner] partitioning contract — disjoint
 * half-open cells, exact dead zones, parent-bias rules, equal-width fallback,
 * per-component independence, explicit empty cells, the 8-block optimization
 * cap, the page cell-span budget fallback, and [collisionGapPx] clamping.
 */
class MaskTextRegionPlannerTest {

    private val budget = MaskTextRegionPlanner.CellSpanBudget()

    private fun member(
        inputIndex: Int,
        centerX: Float,
        centerY: Float,
        parent: FloatRect? = null,
    ) = MaskTextRegionPlanner.Member(
        inputIndex = inputIndex,
        centerX = centerX,
        centerY = centerY,
        ocrRect = FloatRect(centerX - 5f, centerY - 2f, centerX + 5f, centerY + 2f),
        parentRect = parent,
        parentValid = parent != null,
    )

    private fun fullRowSpans(top: Int, bottom: Int, left: Int, right: Int): List<MaskGeometry.RowSpan> =
        (top until bottom).map { y -> MaskGeometry.RowSpan(y, left, right) }

    private fun region(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        spans: List<MaskGeometry.RowSpan>? = fullRowSpans(top, bottom, left, right),
    ) = MaskTextRegionPlanner.ComponentRegion(left, top, right, bottom, spans)

    /** All span x-intervals of [cell] on row [y], sorted by start. */
    private fun intervalsOnRow(cell: MaskTextRegionPlanner.Cell, y: Int): List<Pair<Int, Int>> =
        cell.spans.filter { it.y == y }.map { it.start to it.endExclusive }.sortedBy { it.first }

    @Test
    fun `one row span crossing two cuts yields three cell segments with exact intervals`() {
        // One component row [0,300); members at 50/150/250 → cuts at 100/200;
        // gap 2 splits the crossing span into [0,99), [101,199), [201,300).
        val component = region(0, 5, 300, 6, listOf(MaskGeometry.RowSpan(5, 0, 300)))
        val members = listOf(
            member(0, 50f, 5f),
            member(1, 150f, 5f),
            member(2, 250f, 5f),
        )

        val cells = MaskTextRegionPlanner.partition(component, members, gap = 2, budget = budget)

        cells shouldHaveSize 3
        cells.map { it.inputIndex } shouldBe listOf(0, 1, 2)
        cells.all { it.optimized } shouldBe true
        intervalsOnRow(cells[0], 5) shouldBe listOf(0 to 99)
        intervalsOnRow(cells[1], 5) shouldBe listOf(101 to 199)
        intervalsOnRow(cells[2], 5) shouldBe listOf(201 to 300)
    }

    @Test
    fun `three cell partition of a multi row component is pairwise pixel disjoint`() {
        val component = region(0, 0, 300, 10, fullRowSpans(0, 10, 0, 300))
        val members = listOf(
            member(0, 50f, 1f),
            member(1, 150f, 5f),
            member(2, 250f, 8f),
        )

        val cells = MaskTextRegionPlanner.partition(component, members, gap = 2, budget = budget)
        cells shouldHaveSize 3

        for (y in 0 until 10) {
            val perCell = cells.map { intervalsOnRow(it, y) }
            for (i in perCell.indices) {
                for (j in i + 1 until perCell.size) {
                    for ((aStart, aEnd) in perCell[i]) {
                        for ((bStart, bEnd) in perCell[j]) {
                            (aStart < bEnd && bStart < aEnd) shouldBe false
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `dead zone columns between cuts are owned by no cell at every row`() {
        // gap 5 → gapBefore 2, gapAfter 3: cuts 100/200 leave columns
        // [98,103) and [198,203) unowned; everything else is owned exactly once.
        val component = region(0, 0, 300, 5, fullRowSpans(0, 5, 0, 300))
        val members = listOf(
            member(0, 50f, 2f),
            member(1, 150f, 2f),
            member(2, 250f, 2f),
        )

        val cells = MaskTextRegionPlanner.partition(component, members, gap = 5, budget = budget)
        cells shouldHaveSize 3

        for (y in 0 until 5) {
            val owned = IntArray(300)
            cells.forEach { cell ->
                intervalsOnRow(cell, y).forEach { (start, end) ->
                    for (x in start until end) owned[x] += 1
                }
            }
            for (x in 0 until 300) {
                val expected = when (x) {
                    in 98..102 -> 0
                    in 198..202 -> 0
                    else -> 1
                }
                owned[x] shouldBe expected
            }
        }
    }

    @Test
    fun `overlapping parents never bias the cut away from the center midpoint`() {
        // Parent edges overlap (180 > 120): the cut stays at floor((100+200)/2).
        val component = region(0, 0, 300, 10)
        val members = listOf(
            member(0, 100f, 5f, parent = FloatRect(0f, 0f, 180f, 50f)),
            member(1, 200f, 5f, parent = FloatRect(120f, 0f, 300f, 50f)),
        )

        val cells = MaskTextRegionPlanner.partition(component, members, gap = 2, budget = budget)

        // cut 150 with gap 2 → [0,149) and [151,300).
        cells[0].slab.shouldNotBeNull().let { slab ->
            slab.left shouldBe 0f
            slab.right shouldBe 149f
        }
        cells[1].slab.shouldNotBeNull().let { slab ->
            slab.left shouldBe 151f
            slab.right shouldBe 300f
        }
    }

    @Test
    fun `facing parents bias the cut to the parent edge midpoint clamped between centers`() {
        val component = region(0, 0, 300, 10)
        // Facing edges 80 / 240 → biased cut floor(160) ∈ [100, 200].
        val biased = MaskTextRegionPlanner.partition(
            component,
            listOf(
                member(0, 100f, 5f, parent = FloatRect(0f, 0f, 80f, 50f)),
                member(1, 200f, 5f, parent = FloatRect(240f, 0f, 300f, 50f)),
            ),
            gap = 2,
            budget = budget,
        )
        biased[0].slab.shouldNotBeNull().right shouldBe 159f
        biased[1].slab.shouldNotBeNull().left shouldBe 161f

        // Edge midpoint 240 lies outside [100, 200] → clamped to the far center.
        val clamped = MaskTextRegionPlanner.partition(
            component,
            listOf(
                member(0, 100f, 5f, parent = FloatRect(0f, 0f, 80f, 50f)),
                member(1, 200f, 5f, parent = FloatRect(400f, 0f, 500f, 50f)),
            ),
            gap = 2,
            budget = budget,
        )
        clamped[0].slab.shouldNotBeNull().right shouldBe 199f
        clamped[1].slab.shouldNotBeNull().left shouldBe 201f
    }

    @Test
    fun `equal centers fall back to deterministic equal width slabs`() {
        val component = region(0, 0, 100, 10, fullRowSpans(0, 10, 0, 100))
        val members = listOf(
            member(0, 50f, 5f),
            member(1, 50f, 5f),
            member(2, 50f, 5f),
        )

        val cells = MaskTextRegionPlanner.partition(component, members, gap = 2, budget = budget)

        // W=100, n=3 → widths 33/33/34, no crash, no overlap.
        cells.map { it.slab.shouldNotBeNull().left } shouldBe listOf(0f, 33f, 66f)
        cells.map { it.slab.shouldNotBeNull().right } shouldBe listOf(33f, 66f, 100f)
        cells.forEach { cell ->
            cell.empty shouldBe false
            cell.spans.isNotEmpty() shouldBe true
        }
    }

    @Test
    fun `diagonal centers with equal spreads partition horizontally`() {
        // X spread == Y spread → horizontal (cuts on the x axis).
        val component = region(0, 0, 101, 101, fullRowSpans(0, 101, 0, 101))
        val members = listOf(
            member(0, 0f, 0f),
            member(1, 100f, 100f),
        )

        val cells = MaskTextRegionPlanner.partition(component, members, gap = 2, budget = budget)

        val first = cells[0].slab.shouldNotBeNull()
        val second = cells[1].slab.shouldNotBeNull()
        // Vertical slab boundaries identical; the split is on x at cut 50.
        first.top shouldBe 0f
        first.bottom shouldBe 101f
        second.top shouldBe 0f
        second.bottom shouldBe 101f
        first.right shouldBe 49f
        second.left shouldBe 51f
    }

    @Test
    fun `different components of one mask are partitioned independently and never share cuts`() {
        val componentA = region(0, 0, 100, 10, fullRowSpans(0, 10, 0, 100))
        val componentB = region(200, 0, 300, 10, fullRowSpans(0, 10, 200, 300))
        val membersA = listOf(member(0, 30f, 5f), member(1, 70f, 5f))
        val membersB = listOf(member(2, 230f, 5f), member(3, 270f, 5f))

        val cellsA = MaskTextRegionPlanner.partition(componentA, membersA, gap = 2, budget = budget)
        val cellsB = MaskTextRegionPlanner.partition(componentB, membersB, gap = 2, budget = budget)

        // Component A: cut 50. Component B: cut 250 — never shared.
        cellsA.map { it.slab.shouldNotBeNull().right } shouldBe listOf(49f, 100f)
        cellsB.map { it.slab.shouldNotBeNull().left } shouldBe listOf(200f, 251f)
        cellsA.all { it.slab.shouldNotBeNull().let { s -> s.left >= 0f && s.right <= 100f } } shouldBe true
        cellsB.all { it.slab.shouldNotBeNull().let { s -> s.left >= 200f && s.right <= 300f } } shouldBe true
    }

    @Test
    fun `slab without component pixels is explicitly flagged empty`() {
        // Two island spans supplied as ONE component span list; the middle slab
        // [41,59) falls entirely into the pixel gap (40, 60).
        val spans = fullRowSpans(0, 10, 0, 40) + fullRowSpans(0, 10, 60, 100)
        val component = region(0, 0, 100, 10, spans)
        val members = listOf(
            member(0, 30f, 5f),
            member(1, 50f, 5f),
            member(2, 70f, 5f),
        )

        val cells = MaskTextRegionPlanner.partition(component, members, gap = 2, budget = budget)

        cells shouldHaveSize 3
        cells[0].empty shouldBe false
        cells[0].spans.isNotEmpty() shouldBe true
        cells[1].empty shouldBe true
        cells[1].fitRegion.shouldBeNull()
        cells[1].spans shouldBe emptyList()
        cells[1].slab.shouldNotBeNull().let { slab ->
            slab.left shouldBe 41f
            slab.right shouldBe 59f
        }
        cells[2].empty shouldBe false
    }

    @Test
    fun `nine members optimize only the first eight in stable order`() {
        val component = region(0, 0, 100, 10, fullRowSpans(0, 10, 0, 100))
        // Descending centers: stable order is input 8, 7, ..., 0, so the
        // NOT-optimized member is input 0 while inputs 1..8 get cells.
        val members = (0 until 9).map { index -> member(index, centerX = (90 - 10 * index).toFloat(), centerY = 5f) }

        val cells = MaskTextRegionPlanner.partition(component, members, gap = 2, budget = budget)

        cells shouldHaveSize 9
        // One entry per member in INPUT order.
        cells.map { it.inputIndex } shouldBe (0 until 9).toList()
        cells[0].optimized shouldBe false
        cells[0].slab.shouldBeNull()
        cells[0].fitRegion.shouldBeNull()
        cells[0].empty shouldBe false
        cells.drop(1).forEach { cell ->
            cell.optimized shouldBe true
            cell.slab.shouldNotBeNull()
        }
        // And the eight slabs are pairwise disjoint.
        val slabs = cells.drop(1).map { it.slab.shouldNotBeNull() }
        for (i in slabs.indices) {
            for (j in i + 1 until slabs.size) {
                slabs[i].overlaps(slabs[j]) shouldBe false
            }
        }
    }

    @Test
    fun `cell span budget exceeded falls back to bounds mode cells with no spans`() {
        val component = region(0, 0, 300, 10, fullRowSpans(0, 10, 0, 300))
        val members = listOf(
            member(0, 50f, 5f),
            member(1, 150f, 5f),
            member(2, 250f, 5f),
        )
        val tightBudget = MaskTextRegionPlanner.CellSpanBudget(maxDerivedCellSpansPerPage = 2)

        val cells = MaskTextRegionPlanner.partition(component, members, gap = 2, budget = tightBudget)

        // Still one entry per member, still disjoint slabs, but no span lists.
        cells shouldHaveSize 3
        cells.forEach { cell ->
            cell.optimized shouldBe true
            cell.slab.shouldNotBeNull()
            cell.spans shouldBe emptyList()
            cell.empty shouldBe false
            cell.fitRegion.shouldNotBeNull()
        }
        tightBudget.used shouldBe 0
        val slabs = cells.map { it.slab.shouldNotBeNull() }
        for (i in slabs.indices) {
            for (j in i + 1 until slabs.size) {
                slabs[i].overlaps(slabs[j]) shouldBe false
            }
        }
    }

    @Test
    fun `vertical partition of two stacked members yields exact slabs and dead rows`() {
        // Slice-3 review NOTE 1 ride-along: the vertical branch (Y spread >
        // X spread, cuts on y, dead ROWS between slabs). Centers 30/70 → cut
        // 50; gap 2 → slabs [0,49) and [51,100) × full x, dead rows {49,50}.
        val component = region(0, 0, 100, 100, fullRowSpans(0, 100, 0, 100))
        val members = listOf(
            member(0, 50f, 30f),
            member(1, 50f, 70f),
        )

        val cells = MaskTextRegionPlanner.partition(component, members, gap = 2, budget = budget)

        cells shouldHaveSize 2
        cells[0].slab.shouldNotBeNull().let { slab ->
            slab.left shouldBe 0f
            slab.top shouldBe 0f
            slab.right shouldBe 100f
            slab.bottom shouldBe 49f
        }
        cells[1].slab.shouldNotBeNull().let { slab ->
            slab.left shouldBe 0f
            slab.top shouldBe 51f
            slab.right shouldBe 100f
            slab.bottom shouldBe 100f
        }
        // Dead ROWS (not columns) are owned by none; every other row is owned
        // exactly once across the full width.
        for (x in 0 until 100) {
            for (y in 0 until 100) {
                val owned = cells.count { cell ->
                    intervalsOnRow(cell, y).any { (start, end) -> x in start until end }
                }
                val expected = if (y == 49 || y == 50) 0 else 1
                owned shouldBe expected
            }
        }
    }

    @Test
    fun `collisionGapPx clamps at both bounds and scales with the page short side`() {
        // Below the 2*scale floor → 2.
        MaskTextRegionPlanner.collisionGapPx(100f, 1f) shouldBe 2
        MaskTextRegionPlanner.collisionGapPx(1000f, 1f) shouldBe 2 // 0.001*1000 = 1 → clamped to 2
        // Inside the band: raw value 3.5 → ceil 4.
        MaskTextRegionPlanner.collisionGapPx(3500f, 1f) shouldBe 4
        // Above the 4*scale ceiling → 4.
        MaskTextRegionPlanner.collisionGapPx(5000f, 1f) shouldBe 4
        // Scale narrows the whole band: clamp(10, 1, 2) = 2 and clamp(10, 0.5, 1) = 1.
        MaskTextRegionPlanner.collisionGapPx(10000f, 0.5f) shouldBe 2
        MaskTextRegionPlanner.collisionGapPx(10000f, 0.25f) shouldBe 1
    }
}
