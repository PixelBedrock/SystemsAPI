package llc.redstone.systemsapi.progress

/**
 * Times blocking operations and reports them to [CostModel], which learns from them, and
 * [ProgressTracker], which advances progress by them.
 *
 * Nesting suppression is mandatory, not an optimisation: `InputUtils.textInput` calls
 * `MenuUtils.onOpen` internally, and `ErrorCorrection.onMenuTimeout` calls it from inside
 * `onOpen`'s own catch block. Only the outermost span records; an outer operation's duration
 * already contains its inner ones.
 */
internal object OpRecorder {

    /** Lets an instrumented body refine what it is reporting once it knows. */
    class Span internal constructor(@JvmField var kind: OpKind) {
        @JvmField
        var timedOut: Boolean = false

        @JvmField
        var suppressed: Boolean = false
    }

    private var depth = 0
    private var recorded = 0L

    fun isNested(): Boolean = depth > 0

    /**
     * Monotonic count of recorded operations. Callers diff it across a section to tell whether it
     * did GUI work or short-circuited.
     */
    fun opCount(): Long = recorded

    suspend fun <T> span(kind: OpKind, body: suspend (Span) -> T): T {
        if (depth > 0) return body(Span(kind).also { it.suppressed = true })

        val span = Span(kind)
        depth++
        val start = System.nanoTime()
        try {
            return body(span)
        } finally {
            depth--
            if (!span.suppressed) {
                complete(span.kind, (System.nanoTime() - start) / 1_000_000L, span.timedOut)
            }
        }
    }

    /** For operations whose duration is already known and which wrap no suspending body. */
    fun flat(kind: OpKind, ms: Long) {
        if (depth > 0) return
        complete(kind, ms, timedOut = false)
    }

    private fun complete(kind: OpKind, ms: Long, timedOut: Boolean) {
        recorded++
        CostModel.record(kind, ms, timedOut)
        ProgressTracker.opCompleted(kind, ms, timedOut)
    }
}
