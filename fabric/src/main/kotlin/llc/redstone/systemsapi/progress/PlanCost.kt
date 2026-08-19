package llc.redstone.systemsapi.progress

/**
 * A node in a predicted cost tree, mirroring the structure the importer walks: container, action,
 * property, nested container.
 *
 * [ProgressTracker] opens a scope per node and reconciles against that node's cost when it closes.
 */
internal class PlanCost(
    val label: String,
    /** Predicted milliseconds for this node's own operations, excluding children. */
    val selfMs: Double,
    val selfOps: Int,
    val children: List<PlanCost> = emptyList(),
) {
    val totalMs: Double = selfMs + children.sumOf { it.totalMs }
    val totalOps: Int = selfOps + children.sumOf { it.totalOps }

    companion object {
        val EMPTY = PlanCost("", 0.0, 0)

        fun group(label: String, children: List<PlanCost>): PlanCost = PlanCost(label, 0.0, 0, children)
    }
}
