package llc.redstone.systemsapi.progress

import kotlinx.coroutines.CancellationException
import llc.redstone.systemsapi.SystemsAPI.CONFIG
import llc.redstone.systemsapi.SystemsAPI.DYNAMIC_FPS
import llc.redstone.systemsapi.SystemsAPI.LOGGER
import llc.redstone.systemsapi.api.HouseProgress
import llc.redstone.systemsapi.api.HouseProgressListener
import llc.redstone.systemsapi.api.ProgressOutcome
import llc.redstone.systemsapi.api.ProgressPhase
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Tracks how far along a house import or export is, and how much longer it will take.
 *
 * **Scopes.** Work is a stack of nested scopes -- run, action, property, nested container. Inside a
 * scope each completed operation advances progress by that operation's *predicted* cost; when a
 * scope closes, completed work snaps to the scope's full predicted cost. That snap is what makes
 * the estimate self-correcting: `PropertySettings.import` short-circuits constantly and page counts
 * are guesses, so rather than accumulating, every error is reconciled at the next boundary.
 *
 * **Drift.** Snapping fixes completed work but not the total. If everything so far cost twice its
 * prediction the remainder probably will too, so remaining work is scaled by
 * `actualElapsed / completedPredicted`. This absorbs lag, timeouts, and retries without modelling
 * them. Completed work accumulates *predicted* cost, never measured cost -- that is what makes the
 * ratio meaningful, and it keeps this corrector independent of [CostModel]'s.
 *
 * **Threading.** All mutation happens on the Minecraft main thread. Readers may be anywhere, so
 * state is published as an immutable [Snapshot] behind one `@Volatile` reference and [read] is a
 * pure function of that snapshot and a timestamp. Two readers polling at different framerates
 * therefore see identical values. Per-field atomics would allow torn reads across fields the
 * arithmetic assumes are consistent.
 */
internal object ProgressTracker {

    private const val FLOOR_MS = 250.0

    /** Fast when the estimate falls, slow when it rises, so upward revisions read as a slowdown. */
    private const val TAU_DOWN_MS = 250.0
    private const val TAU_UP_MS = 3000.0

    private const val DRIFT_WARMUP_OPS = 8.0
    private const val DRIFT_MIN = 0.5
    private const val DRIFT_MAX = 2.5

    /** How long a finished run stays readable, so a HUD can show the outcome and fade. */
    private const val TERMINAL_GRACE_NANOS = 3_000_000_000L

    private const val LISTENER_EPSILON = 0.001f

    private class Snapshot(
        val phase: ProgressPhase,
        val outcome: ProgressOutcome,
        val label: String?,
        val depth: Int,
        val indeterminate: Boolean,
        val fraction: Float,
        val completedOps: Int,
        val totalOps: Int,
        val runStartNanos: Long,
        val publishNanos: Long,
        val targetRemainingMs: Double?,
        val displayRemainingMs: Double?,
        val tauMs: Double,
        /** Non-zero once the run has ended; starts the grace window. */
        val terminalNanos: Long,
    )

    @Volatile
    private var snap: Snapshot? = null

    private val listeners = CopyOnWriteArrayList<HouseProgressListener>()

    private var lastNotifiedFraction = -1f
    private var lastNotifiedKey: String? = null

    private class Scope(
        val baseMs: Double,
        val baseOps: Int,
        var plannedMs: Double,
        var plannedOps: Int,
        val label: String?,
    )

    private val stack = ArrayDeque<Scope>()

    private var completedMs = 0.0
    private var totalMs = 0.0
    private var completedOps = 0
    private var totalOps = 0
    private var runStartNanos = 0L
    private var phase = ProgressPhase.PREPARING
    private var indeterminate = false

    /** Ratchet, since scope reconciliation can move completed work backwards. */
    private var fractionFloor = 0f

    @Volatile
    private var cancelRequested = false

    /** Mirrors `stack.isNotEmpty()` for readers on other threads. */
    @Volatile
    private var active = false

    fun isActive(): Boolean = active

    /**
     * [planned] may be null for work whose size is only discovered as it proceeds -- export before
     * its first page, or the entity importers, which have no enumerable structure.
     */
    fun beginRun(phase: ProgressPhase, planned: PlanCost?, label: String, indeterminate: Boolean) {
        stack.clear()
        this.phase = phase
        this.indeterminate = indeterminate || planned == null
        completedMs = 0.0
        completedOps = 0
        totalMs = planned?.totalMs ?: 0.0
        totalOps = planned?.totalOps ?: 0
        fractionFloor = 0f
        cancelRequested = false
        runStartNanos = System.nanoTime()
        ExportPlanner.reset()
        snap = null
        lastNotifiedFraction = -1f
        lastNotifiedKey = null

        // Held for the whole run. The old code toggled this per container, so it thrashed.
        LOGGER.debug("Disabling Dynamic FPS for {}...", label)
        DYNAMIC_FPS?.disable()

        stack.addLast(Scope(0.0, 0, totalMs, totalOps, label))
        active = true
        republish()
    }

    fun setPhase(phase: ProgressPhase) {
        if (this.phase == phase) return
        this.phase = phase
        republish()
    }

    fun beginScope(plannedMs: Double, plannedOps: Int, label: String?) {
        if (stack.isEmpty()) return
        stack.addLast(Scope(completedMs, completedOps, plannedMs, plannedOps, label))
        republish()
    }

    fun beginScope(plan: PlanCost) = beginScope(plan.totalMs, plan.totalOps, plan.label.ifEmpty { null })

    fun endScope() {
        val scope = stack.removeLastOrNull() ?: return
        if (stack.isEmpty()) {
            // The root is closed by completeRun/failRun/cancelRun, not here.
            stack.addLast(scope)
            return
        }
        completedMs = scope.baseMs + scope.plannedMs
        completedOps = scope.baseOps + scope.plannedOps
        republish()
    }

    /** Grows or shrinks the total as export discovers how much work there actually is. */
    fun reviseTotal(deltaMs: Double, deltaOps: Int) {
        if (stack.isEmpty()) return
        totalMs = (totalMs + deltaMs).coerceAtLeast(0.0)
        totalOps = (totalOps + deltaOps).coerceAtLeast(0)
        val root = stack.first()
        root.plannedMs = totalMs
        root.plannedOps = totalOps
        republish()
    }

    fun setIndeterminate(value: Boolean) {
        if (indeterminate == value) return
        indeterminate = value
        republish()
    }

    fun opCompleted(kind: OpKind, actualMs: Long, timedOut: Boolean) {
        if (stack.isEmpty()) return
        // A fixed delay is fully determined by config, so its measured value is its prediction.
        completedMs += if (kind == OpKind.FIXED_DELAY) actualMs.toDouble() else CostModel.estimateMs(kind)
        completedOps += 1
        republish()
    }

    fun completeRun() {
        if (stack.isEmpty()) return
        stack.clear()
        completedMs = totalMs
        completedOps = maxOf(completedOps, totalOps)
        indeterminate = false
        finish(ProgressOutcome.COMPLETED)
        if (CONFIG.persistCalibration) CalibrationStore.saveAsync(CostModel.snapshot())
    }

    fun failRun(cause: Throwable?) {
        if (stack.isEmpty()) return
        LOGGER.debug("House operation failed during '{}'", stack.lastOrNull()?.label, cause)
        // Not persisting: a failed run's samples are dominated by timeouts and retries.
        finish(ProgressOutcome.FAILED)
    }

    fun cancelRun() {
        if (stack.isEmpty()) return
        finish(ProgressOutcome.CANCELLED)
    }

    fun requestCancel() {
        cancelRequested = true
        cancelRun()
    }

    private fun finish(outcome: ProgressOutcome) {
        val label = stack.lastOrNull()?.label
        stack.clear()
        active = false
        LOGGER.debug("Re-enabling Dynamic FPS after house operation ({}).", outcome)
        DYNAMIC_FPS?.enable()

        val now = System.nanoTime()
        val fraction = if (outcome == ProgressOutcome.COMPLETED) 1f else fractionFloor
        fractionFloor = fraction
        snap = Snapshot(
            phase = if (outcome == ProgressOutcome.COMPLETED) ProgressPhase.FINISHING else phase,
            outcome = outcome,
            label = label,
            depth = 0,
            indeterminate = false,
            fraction = fraction,
            completedOps = completedOps,
            totalOps = maxOf(totalOps, completedOps),
            runStartNanos = runStartNanos,
            publishNanos = now,
            targetRemainingMs = if (outcome == ProgressOutcome.COMPLETED) 0.0 else null,
            displayRemainingMs = if (outcome == ProgressOutcome.COMPLETED) 0.0 else null,
            tauMs = TAU_DOWN_MS,
            terminalNanos = now,
        )
        notifyListeners()
    }

    /** Runs [body] as a complete operation, reaching a terminal state however it exits. */
    suspend fun <T> runRoot(
        phase: ProgressPhase,
        planned: PlanCost?,
        label: String,
        indeterminate: Boolean = false,
        body: suspend () -> T,
    ): T {
        beginRun(phase, planned, label, indeterminate)
        try {
            val result = body()
            completeRun()
            return result
        } catch (e: CancellationException) {
            cancelRun()
            throw e
        } catch (t: Throwable) {
            failRun(t)
            throw t
        } finally {
            if (stack.isNotEmpty()) failRun(null)
        }
    }

    /**
     * Joins the enclosing run if one is active. Export recurses for pagination and nested
     * containers, so only the outermost call should own the run.
     */
    suspend fun <T> runRootIfIdle(
        phase: ProgressPhase,
        planned: PlanCost?,
        label: String,
        indeterminate: Boolean = false,
        body: suspend () -> T,
    ): T = if (isActive()) body() else runRoot(phase, planned, label, indeterminate, body)

    private fun republish() {
        val now = System.nanoTime()
        val elapsedMs = (now - runStartNanos) / 1_000_000.0

        val fraction = if (totalMs > 0.0) {
            maxOf(fractionFloor, (completedMs / totalMs).toFloat()).coerceIn(0f, 0.999f)
        } else {
            fractionFloor
        }
        fractionFloor = fraction

        val target: Double? = if (totalMs > 0.0) {
            val drift = if (completedMs > 1.0) elapsedMs / completedMs else 1.0
            val warmup = min(1.0, completedOps / DRIFT_WARMUP_OPS)
            val effective = 1.0 + (drift.coerceIn(DRIFT_MIN, DRIFT_MAX) - 1.0) * warmup
            ((totalMs - completedMs) * effective).coerceAtLeast(FLOOR_MS)
        } else {
            null
        }

        val previous = snap
        val displayed = if (previous != null && previous.outcome == ProgressOutcome.RUNNING) {
            evaluateRemaining(previous, now)
        } else {
            target
        }

        val tau = if (target != null && displayed != null && target < displayed) TAU_DOWN_MS else TAU_UP_MS

        snap = Snapshot(
            phase = phase,
            outcome = ProgressOutcome.RUNNING,
            label = stack.lastOrNull()?.label,
            depth = (stack.size - 1).coerceAtLeast(0),
            indeterminate = indeterminate || totalMs <= 0.0,
            fraction = fraction,
            completedOps = completedOps,
            // Discovery can revise the total downward, but a step count that reads lower than the
            // steps already taken is nonsense to look at.
            totalOps = maxOf(totalOps, completedOps),
            runStartNanos = runStartNanos,
            publishNanos = now,
            targetRemainingMs = target,
            displayRemainingMs = displayed,
            tauMs = tau,
            terminalNanos = 0L,
        )
        notifyListeners()
    }

    /**
     * The target counts down in real time while the displayed value eases toward it. Both are
     * closed-form in elapsed time, so calling this more often does not speed the countdown up.
     */
    private fun evaluateRemaining(s: Snapshot, now: Long): Double? {
        val target = s.targetRemainingMs ?: return null
        val dt = (now - s.publishNanos) / 1_000_000.0
        val liveTarget = (target - dt).coerceAtLeast(FLOOR_MS)
        val display = s.displayRemainingMs ?: return liveTarget
        return liveTarget + (display - target) * exp(-dt / s.tauMs)
    }

    /** Pure. Safe from any thread. */
    fun read(): HouseProgress? {
        val s = snap ?: return null
        val now = System.nanoTime()

        if (s.terminalNanos != 0L && now - s.terminalNanos > TERMINAL_GRACE_NANOS) return null

        val remainingMs = if (s.outcome == ProgressOutcome.RUNNING) evaluateRemaining(s, now) else s.targetRemainingMs
        val elapsedNanos = if (s.terminalNanos != 0L) s.terminalNanos - s.runStartNanos else now - s.runStartNanos

        return HouseProgress(
            phase = s.phase,
            outcome = s.outcome,
            fraction = s.fraction,
            remainingSeconds = remainingMs?.let { (it / 1000.0).toFloat() },
            elapsedSeconds = (elapsedNanos / 1_000_000_000.0).toFloat(),
            indeterminate = s.indeterminate,
            currentLabel = s.label,
            completedSteps = s.completedOps,
            totalSteps = s.totalOps,
            depth = s.depth,
        )
    }

    fun addListener(listener: HouseProgressListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: HouseProgressListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        if (listeners.isEmpty()) return
        val s = snap ?: return

        val key = "${s.phase}|${s.outcome}|${s.label}"
        if (key == lastNotifiedKey && abs(s.fraction - lastNotifiedFraction) < LISTENER_EPSILON) return
        lastNotifiedKey = key
        lastNotifiedFraction = s.fraction

        val progress = read() ?: return
        for (listener in listeners) {
            try {
                listener.onProgress(progress)
            } catch (e: Throwable) {
                // A misbehaving consumer must not be able to abort an import.
                LOGGER.warn("Progress listener threw.", e)
            }
        }
    }
}
