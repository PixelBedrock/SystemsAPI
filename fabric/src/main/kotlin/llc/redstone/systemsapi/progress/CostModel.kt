package llc.redstone.systemsapi.progress

import llc.redstone.systemsapi.SystemsAPI.CONFIG
import llc.redstone.systemsapi.SystemsAPI.MINECRAFT
import java.util.EnumMap

/**
 * The learned timing model: how long each [OpKind] takes on this player's connection, how many page
 * turns a menu entry sits behind, and how often a property short-circuits.
 *
 * Mutated only from the Minecraft main thread. [snapshot] deep-copies so persistence can run
 * off-thread.
 */
internal object CostModel {

    /**
     * A running mean with a decaying step size and outlier winsorizing.
     *
     * `alpha = max(1/(n+1), MIN_ALPHA)` is an exact running mean while `n` is small -- the fastest
     * convergence from a cold prior, which matters because a whole import only produces a few
     * hundred samples -- then settles into an EMA of roughly `1/MIN_ALPHA` samples.
     */
    class RunningStat(var mean: Double, var n: Int) {
        fun record(sample: Double) {
            // Clamped rather than rejected, so the mean can still migrate to a genuinely shifted
            // regime (a higher-latency server) over several samples.
            val s = if (n >= WINSOR_AFTER && mean > 0.0) sample.coerceIn(0.25 * mean, 4.0 * mean) else sample
            val alpha = maxOf(1.0 / (n + 1.0), MIN_ALPHA)
            mean += alpha * (s - mean)
            n = minOf(n + 1, N_CAP)
        }
    }

    private const val MIN_ALPHA = 0.15
    private const val WINSOR_AFTER = 5
    private const val N_CAP = 500

    /** Loaded calibration is capped here so a stale file acts as a prior, not a straitjacket. */
    const val LOADED_N_CAP = 20

    private const val MAP_CAP = 2000
    private const val PAGE_TURN_PRIOR = 1.5

    /** Learned overhead above [OpKind.floorMs]. */
    private val ops = EnumMap<OpKind, RunningStat>(OpKind::class.java)

    /** Fraction of attempts that hit a `withTimeout` expiry, in `[0,1]`. */
    private val timeouts = EnumMap<OpKind, RunningStat>(OpKind::class.java)

    /** Keyed `"<menu>|<target>"`. */
    private val pages = HashMap<String, RunningStat>()

    /** Keyed `"<class>|<target>"`. */
    private val cycles = HashMap<String, RunningStat>()

    /** `[worked, skipped]` per `"<OwnerClass>#<property>"`. */
    private val skips = HashMap<String, IntArray>()

    /** What reading one action out of a container costs, learned from pages of leaf actions. */
    private var exportActionMs = RunningStat(0.0, 0)
    private var exportActionOps = RunningStat(0.0, 0)

    private fun opStat(kind: OpKind) = ops.getOrPut(kind) { RunningStat(kind.priorOverheadMs, 0) }
    private fun timeoutStat(kind: OpKind) = timeouts.getOrPut(kind) { RunningStat(0.0, 0) }

    /**
     * Expected cost of one [kind], including a surcharge for the chance it times out and gets
     * replayed by [llc.redstone.systemsapi.util.ErrorCorrection].
     */
    fun estimateMs(kind: OpKind): Double {
        if (kind == OpKind.FIXED_DELAY) return 0.0
        val overhead = opStat(kind).mean
        val floor = kind.floorMs(CONFIG.baseClickDelay).toDouble()
        return floor + overhead + timeoutStat(kind).mean * (CONFIG.menuTimeout + overhead)
    }

    /**
     * [actualMs] must be the raw measured duration. Feeding a drift-corrected value back in here
     * would couple this table to [ProgressTracker]'s drift factor and make the ETA oscillate.
     */
    fun record(kind: OpKind, actualMs: Long, timedOut: Boolean) {
        if (!CONFIG.learnTimings || kind == OpKind.FIXED_DELAY) return
        timeoutStat(kind).record(if (timedOut) 1.0 else 0.0)
        // Tracked as a rate instead of a duration, so one stall raises every future estimate
        // slightly rather than poisoning the mean.
        if (timedOut) return
        val overhead = (actualMs - kind.floorMs(CONFIG.baseClickDelay)).coerceAtLeast(0L).toDouble()
        opStat(kind).record(overhead)
    }

    private fun pageKey(menu: String, target: String) = "$menu|$target"

    /**
     * Expected page turns to reach [target] within [menu]. The page an entry sits on is stable, so
     * one observation makes this exact.
     */
    fun pageTurns(menu: String, target: String): Double =
        pages[pageKey(menu, target)]?.mean ?: PAGE_TURN_PRIOR

    fun recordPageTurns(menu: String, target: String, turns: Int) {
        if (!CONFIG.learnTimings) return
        pages.getOrPut(pageKey(menu, target)) { RunningStat(PAGE_TURN_PRIOR, 0) }.record(turns.toDouble())
        evict(pages)
    }

    private fun cycleKey(cls: String, target: String) = "$cls|$target"

    /** Expected clicks to cycle to [target], defaulting to the mean distance around the cycle. */
    fun cycleSteps(cls: String, target: String, entryCount: Int): Double =
        cycles[cycleKey(cls, target)]?.mean
            ?: ((entryCount - 1).coerceAtLeast(0) / 2.0).coerceAtLeast(1.0)

    fun recordCycleSteps(cls: String, target: String, steps: Int) {
        if (!CONFIG.learnTimings) return
        cycles.getOrPut(cycleKey(cls, target)) { RunningStat(steps.toDouble(), 0) }.record(steps.toDouble())
        evict(cycles)
    }

    /**
     * Probability that this property does GUI work rather than short-circuiting. Laplace-smoothed so
     * one observation never drives it to 0 or 1.
     */
    fun workProbability(shapeKey: String): Double {
        val counts = skips[shapeKey] ?: return 1.0
        return (counts[0] + 1.0) / (counts[0] + counts[1] + 2.0)
    }

    fun hasWorkData(shapeKey: String): Boolean = skips.containsKey(shapeKey)

    fun recordWork(shapeKey: String, didWork: Boolean) {
        if (!CONFIG.learnTimings) return
        val counts = skips.getOrPut(shapeKey) { IntArray(2) }
        val i = if (didWork) 0 else 1
        if (counts[i] < N_CAP) counts[i]++
        if (skips.size > MAP_CAP) {
            skips.entries
                .sortedBy { it.value[0] + it.value[1] }
                .take(skips.size - MAP_CAP)
                .map { it.key }
                .forEach { skips.remove(it) }
        }
    }

    /**
     * Expected cost of reading one action, used to price sub-actions that a parent's lore names but
     * whose own contents are not visible yet.
     *
     * A truncated value is the only thing a plain action can make export pay for, and most do not
     * have one, so the prior assumes roughly one in three. Erring low is deliberate: the total
     * growing slightly as containers are opened reads better than it shrinking below work already
     * done. One export replaces this with a measurement.
     */
    fun exportActionCost(): Pair<Double, Double> {
        val truncationOps = 3.0
        val truncationMs = estimateMs(OpKind.PREV_INPUT) + 2 * estimateMs(OpKind.MENU_ROUNDTRIP)
        return if (exportActionMs.n == 0) (truncationMs / truncationOps) to 1.0
        else exportActionMs.mean to exportActionOps.mean
    }

    /** Recorded only from pages with no nested descents, so it stays the cost of a *leaf* action. */
    fun recordExportActionCost(ms: Double, ops: Double) {
        if (!CONFIG.learnTimings) return
        exportActionMs.record(ms)
        exportActionOps.record(ops)
    }

    private fun evict(map: HashMap<String, RunningStat>) {
        if (map.size <= MAP_CAP) return
        map.entries.sortedBy { it.value.n }.take(map.size - MAP_CAP).map { it.key }.forEach { map.remove(it) }
    }

    fun snapshot(): CalibrationData = CalibrationData(
        mc = MINECRAFT,
        ops = ops.entries.associate { it.key.name to StatDto(it.value.mean, it.value.n) },
        timeouts = timeouts.entries.associate { it.key.name to StatDto(it.value.mean, it.value.n) },
        pages = pages.entries.associate { it.key to StatDto(it.value.mean, it.value.n) },
        cycles = cycles.entries.associate { it.key to StatDto(it.value.mean, it.value.n) },
        skips = skips.entries.associate { it.key to intArrayOf(it.value[0], it.value[1]) },
        exportActionMs = StatDto(exportActionMs.mean, exportActionMs.n),
        exportActionOps = StatDto(exportActionOps.mean, exportActionOps.n),
    )

    fun load(data: CalibrationData) {
        reset()
        data.ops?.forEach { (k, v) -> opKindOrNull(k)?.let { ops[it] = v.toStat() } }
        data.timeouts?.forEach { (k, v) -> opKindOrNull(k)?.let { timeouts[it] = v.toStat() } }
        data.pages?.forEach { (k, v) -> pages[k] = v.toStat() }
        data.cycles?.forEach { (k, v) -> cycles[k] = v.toStat() }
        data.skips?.forEach { (k, v) -> if (v.size >= 2) skips[k] = intArrayOf(v[0], v[1]) }
        data.exportActionMs?.let { exportActionMs = it.toStat() }
        data.exportActionOps?.let { exportActionOps = it.toStat() }
    }

    fun reset() {
        ops.clear()
        timeouts.clear()
        pages.clear()
        cycles.clear()
        skips.clear()
        exportActionMs = RunningStat(0.0, 0)
        exportActionOps = RunningStat(0.0, 0)
    }

    private fun opKindOrNull(name: String): OpKind? = OpKind.entries.firstOrNull { it.name == name }

    private fun StatDto.toStat() = RunningStat(mean, n.coerceIn(0, LOADED_N_CAP))
}
