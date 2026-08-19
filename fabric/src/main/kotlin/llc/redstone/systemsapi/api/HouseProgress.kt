package llc.redstone.systemsapi.api

import llc.redstone.systemsapi.progress.ProgressTracker

/** Which part of a house operation is currently running. */
enum class ProgressPhase { PREPARING, CLEARING, IMPORTING, EXPORTING, FINISHING }

/** How a run ended, or that it has not ended yet. */
enum class ProgressOutcome { RUNNING, COMPLETED, CANCELLED, FAILED }

/**
 * An immutable view of the progress of the current (or just-finished) house operation.
 *
 * Obtain one from [ImportProgress.current], which recomputes it on every call.
 */
data class HouseProgress(
    val phase: ProgressPhase,
    val outcome: ProgressOutcome,

    /**
     * Completion in `0..1`. Monotonically non-decreasing within a run, and capped just below 1
     * until the run actually ends.
     */
    val fraction: Float,

    /**
     * Estimated seconds remaining, or null when no estimate is possible yet -- a run whose total is
     * still undiscovered, or one that was cancelled or failed.
     *
     * May occasionally rise when the operation turns out more expensive than predicted; pinning it
     * to a monotonic decrease would make it stall at zero instead. Rises are eased in over seconds.
     */
    val remainingSeconds: Float?,

    val elapsedSeconds: Float,

    /**
     * True while the total is still being discovered, in which case [fraction] and
     * [remainingSeconds] are best-effort and [totalSteps] may still grow.
     */
    val indeterminate: Boolean,

    /** Description of the innermost unit of work, e.g. `"SendMessage.message"`. */
    val currentLabel: String?,

    val completedSteps: Int,

    /** Expected blocking operations. Grows during discovery; zero if entirely unknown. */
    val totalSteps: Int,

    /** Scope nesting depth, for rendering a breadcrumb. */
    val depth: Int,
)

/** Receives progress updates as they happen. Invoked on the Minecraft main thread. */
fun interface HouseProgressListener {
    fun onProgress(progress: HouseProgress)
}

/**
 * Access to the progress of house import and export operations.
 *
 * [current] is recomputed from a timestamp on every call, so polling it once per frame is the
 * intended usage for a progress bar, and is safe from any thread. Alternatively register a
 * [HouseProgressListener] to be pushed throttled updates as work completes.
 *
 * After a run ends, [current] keeps returning the terminal snapshot for a few seconds so a HUD can
 * show the outcome and fade out, then returns null.
 */
object ImportProgress {

    fun current(): HouseProgress? = ProgressTracker.read()

    fun addListener(listener: HouseProgressListener) = ProgressTracker.addListener(listener)

    fun removeListener(listener: HouseProgressListener) = ProgressTracker.removeListener(listener)
}
