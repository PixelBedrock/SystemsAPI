package llc.redstone.systemsapi.progress

/**
 * The atomic, *blocking* operations that import and export are built out of.
 *
 * Only operations that actually suspend are modelled. `packetClick` returns immediately, and
 * `awaitUntilMenuItemsLoaded` is always folded into an `onOpen` call.
 */
internal enum class OpKind {
    /** A full `onOpen` round-trip: click packet, screen open, items loaded, settle delay. */
    MENU_ROUNDTRIP,

    /** `onOpen`'s fast path when the screen was already correct: item-load wait and delay only. */
    MENU_ASSERT,

    TEXT_INPUT,

    /** One iteration of the paginated `findSlots` loop. */
    PAGE_TURN,

    TAB_COMPLETE,
    PREV_INPUT,
    ITEM_RECEIVE,

    /** A bare `scaledDelay`. Fully determined by config, so it is computed rather than learned. */
    FIXED_DELAY;

    /**
     * The config-derived part of this operation's duration.
     *
     * [CostModel] learns only the overhead above this, which makes the learned table immune to
     * `baseClickDelay` changes instead of having to re-learn after every adjustment.
     */
    fun floorMs(baseClickDelay: Long): Long = when (this) {
        MENU_ROUNDTRIP, MENU_ASSERT -> baseClickDelay
        TEXT_INPUT -> baseClickDelay * 5
        PAGE_TURN -> baseClickDelay * 4
        TAB_COMPLETE, PREV_INPUT, ITEM_RECEIVE, FIXED_DELAY -> 0L
    }

    /**
     * Cold-start overhead prior in milliseconds, above [floorMs]. Deliberately generous: a first run
     * that over-estimates reads better than a bar that stalls at full while work continues.
     */
    val priorOverheadMs: Double
        get() = when (this) {
            MENU_ROUNDTRIP -> 180.0
            MENU_ASSERT -> 25.0
            TEXT_INPUT -> 220.0
            PAGE_TURN -> 30.0
            TAB_COMPLETE -> 120.0
            PREV_INPUT -> 300.0
            ITEM_RECEIVE -> 300.0
            FIXED_DELAY -> 0.0
        }
}
