package llc.redstone.systemsapi.progress

import llc.redstone.systemsapi.SystemsAPI.LOGGER
import llc.redstone.systemsapi.util.ItemStackUtils.loreLines
import net.minecraft.screen.slot.Slot

/**
 * Predicts what an export will cost.
 *
 * Export is not handed its work up front, but an open container page is far from opaque. Its lore
 * says how many entries are present, which values are truncated with `...` and therefore need a trip
 * into the property to read, and which sub-actions each nested property holds, by name.
 *
 * A container is walked twice: forward through every page pricing them from lore alone, then back
 * again doing the real reading. By the time any expensive work starts the total is already known,
 * instead of lurching upward each time a nested container is opened. Conditions have no nested lists
 * of their own, so their pages are discovered and read together in one pass; only actions need the
 * two-pass treatment.
 *
 * ### Reconciling a descent
 *
 * A nested property is charged in two parts, and only one of them is provisional:
 *
 * - **Overhead** -- the round-trips the *caller* spends opening the property and backing out again.
 *   Fixed, and never revised.
 * - **Contents** -- a guess at what the container holds, from the entry names its parent's lore
 *   listed. Replaced by a real measurement when that container is opened.
 *
 * Keeping them separate matters: replacing the whole charge with the nested container's page cost
 * would subtract away the overhead and the entry costs too, so the total would collapse below the
 * work already completed.
 */
internal object ExportPlanner {

    /**
     * Round-trips to open a nested property and back out through two menus.
     *
     * Spent by the caller rather than the nested container, so this is never revised away when that
     * container turns out cheaper than guessed.
     */
    private const val DESCENT_ROUNDTRIPS = 3

    /**
     * Net round-trips a container's first page costs across both passes: discovery reaches it for
     * free (it is wherever the container was opened to), so only the reading pass's final arrival
     * here, at the end, is real.
     */
    const val FIRST_PAGE_ROUNDTRIPS = 1

    /**
     * Net round-trips a later page costs across both passes: one to arrive during discovery, one
     * to arrive again during reading. Slightly over-charges whichever page turns out to be the
     * last, since reading starts there for free -- a small, harmless overestimate.
     */
    const val LATER_PAGE_ROUNDTRIPS = 2

    /** A container with no nested lists is read in one forward pass, costing just its arrival. */
    const val PAGE_ROUNDTRIPS = 1

    private class Cost(val ms: Double, val ops: Int)

    /**
     * One container's descent bookkeeping.
     *
     * [discovered] collects each page's provisional contents charges in discovery order; [queue] is
     * the same set re-ordered for the reading pass, which visits pages back to front but reads each
     * page's own entries left to right as always.
     */
    private class Frame {
        val discovered = mutableListOf<List<Cost>>()
        val queue = ArrayDeque<Cost>()
    }

    /** One frame per open container, innermost last. */
    private val frames = ArrayDeque<Frame>()

    /** Contents charge for the descent being taken, replaced by that container's first page. */
    private var pendingPreCharge: Cost? = null

    fun reset() {
        frames.clear()
        pendingPreCharge = null
    }

    private fun currentFrame(): Frame {
        if (frames.isEmpty()) frames.addLast(Frame())
        return frames.last()
    }

    /**
     * Prices the page on screen. Called once per page during the forward pass.
     *
     * @param learnActionCost whether this page teaches what an action costs to read. False for
     * condition pages, whose entries are cheaper and would skew the figure applied to sub-actions.
     */
    fun discoverPage(slots: List<Slot>, pageRoundTrips: Int, learnActionCost: Boolean = true) {
        val frame = currentFrame()
        val page = planPage(slots, pageRoundTrips)

        // Replaces only the caller's guess at this container's contents; its overhead stays charged.
        val preCharged = pendingPreCharge
        pendingPreCharge = null
        ProgressTracker.reviseTotal(
            page.total.ms - (preCharged?.ms ?: 0.0),
            page.total.ops - (preCharged?.ops ?: 0),
        )

        frame.discovered.add(page.contents)

        // Only pages of leaf actions teach us what an action costs; a page holding nested containers
        // would fold their cost into the average.
        if (learnActionCost && page.entryCount > 0 && page.contents.isEmpty()) {
            CostModel.recordExportActionCost(
                page.perEntryMs / page.entryCount,
                page.perEntryOps.toDouble() / page.entryCount,
            )
        }

        LOGGER.debug(
            "Export page priced: {} entries, {} nested, {} ops (replacing {}), depth {}",
            page.entryCount, page.contents.size, page.total.ops, preCharged?.ops ?: 0, frames.size,
        )
    }

    /**
     * Ends discovery for the container currently open. Descents are queued last-page-first, since
     * reading (when it happens in a second pass) visits pages in that order; for a container read in
     * a single pass this just makes its one page's descents available immediately.
     */
    fun finishDiscovery() {
        val frame = currentFrame()
        frame.queue.clear()
        frame.discovered.asReversed().forEach { frame.queue.addAll(it) }
        frame.discovered.clear()

        // Only the outermost container decides whether the export as a whole is still unbounded.
        if (frames.size == 1) ProgressTracker.setIndeterminate(false)
    }

    /** Called around a descent into a nested action or condition container. */
    fun beginNestedDescent() {
        pendingPreCharge = currentFrame().queue.removeFirstOrNull()
        frames.addLast(Frame())
    }

    fun endNestedDescent() {
        if (frames.size > 1) frames.removeLast()
        pendingPreCharge = null
    }

    private class PageCost(
        val total: Cost,
        /** Provisional charges for the nested containers this page holds, in reading order. */
        val contents: List<Cost>,
        val entryCount: Int,
        val perEntryMs: Double,
        val perEntryOps: Int,
    )

    /**
     * Costs only what blocks. Reading a slot off an already-open page is free, so slots are
     * deliberately not charged -- doing so would leave the total unreachable and the bar would crawl
     * then jump to full at the end.
     */
    private fun planPage(slots: List<Slot>, pageRoundTrips: Int): PageCost {
        val roundTrip = CostModel.estimateMs(OpKind.MENU_ROUNDTRIP)

        var ms = roundTrip * pageRoundTrips
        var ops = pageRoundTrips

        var perEntryMs = 0.0
        var perEntryOps = 0
        var entryCount = 0
        val contents = mutableListOf<Cost>()

        for (slot in slots) {
            if (!slot.hasStack()) break
            entryCount++

            // Colour codes off: a styled line reads "&7 - Send Message", which no structural test
            // would recognise.
            val lines = slot.stack.loreLines(false)
            var index = 0
            while (index < lines.size) {
                val line = lines[index]

                if (isNestedEntry(line)) {
                    // A run of named entries is one nested property.
                    var entries = 0
                    while (index < lines.size && isNestedEntry(lines[index])) {
                        entries++
                        index++
                    }
                    ms += roundTrip * DESCENT_ROUNDTRIPS
                    ops += DESCENT_ROUNDTRIPS
                    contents.add(contentsCost(entries))
                    continue
                }
                index++

                if (!line.contains(":")) continue

                // A truncated value cannot be recovered from the lore, so it costs a trip into the
                // property to read the full text and back out again.
                if (line.substringAfter(": ").endsWith("...")) {
                    perEntryMs += CostModel.estimateMs(OpKind.PREV_INPUT) + roundTrip * 2
                    perEntryOps += 3
                }
            }
        }

        ms += perEntryMs + contents.sumOf { it.ms }
        ops += perEntryOps + contents.sumOf { it.ops }

        return PageCost(Cost(ms, ops), contents, entryCount, perEntryMs, perEntryOps)
    }

    /**
     * What a nested container is guessed to cost from the outside: its own first page, plus each
     * entry its parent's lore named priced at the learned cost of reading an action. Multi-page
     * nested containers are under-priced here until their own discovery pass corrects it -- lore
     * only shows what fits in the tooltip, not how many pages it spans.
     */
    private fun contentsCost(entries: Int): Cost {
        val (perActionMs, perActionOps) = CostModel.exportActionCost()
        return Cost(
            CostModel.estimateMs(OpKind.MENU_ROUNDTRIP) * PAGE_ROUNDTRIPS + entries * perActionMs,
            PAGE_ROUNDTRIPS + (entries * perActionOps).toInt(),
        )
    }

    /** Sub-actions and conditions are listed beneath their property as `" - <name>"`. */
    private fun isNestedEntry(line: String) = line.trimStart().startsWith("- ")
}
