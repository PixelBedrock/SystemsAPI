package llc.redstone.systemsapi.progress

import llc.redstone.systemsdata.Action
import llc.redstone.systemsdata.ActionDefinition
import llc.redstone.systemsdata.Condition
import llc.redstone.systemsdata.CustomKey
import llc.redstone.systemsdata.DisplayName
import llc.redstone.systemsdata.InventorySlot
import llc.redstone.systemsdata.ItemStack
import llc.redstone.systemsdata.Keyed
import llc.redstone.systemsdata.KeyedCycle
import llc.redstone.systemsdata.KeyedLabeled
import llc.redstone.systemsdata.Location
import llc.redstone.systemsdata.Pagination
import llc.redstone.systemsdata.PropertyHolder
import llc.redstone.systemsdata.StatOp
import llc.redstone.systemsdata.StatValue
import llc.redstone.systemsdata.enums.Sound
import kotlin.math.roundToInt
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.hasAnnotation

/**
 * Predicts what an import will cost by walking the same branches `PropertySettings.import` will,
 * counting the menu round-trips, text inputs, and page turns each value actually requires.
 *
 * **Mirrors `PropertySettings.import` and must be kept in step with it.** A branch added there
 * without one here makes that property invisible to the estimate.
 */
internal object ImportPlanner {

    private const val FRESH_DEFAULT_WORK_PRIOR = 0.2

    private const val ADD_ACTION_MENU = "Add Action"
    private const val ADD_CONDITION_MENU = "Add Condition"
    private const val SELECT_OPTION_MENU = "Select Option"
    private const val SELECT_SLOT_MENU = "Select Inventory Slot"
    private const val EDIT_ACTIONS_MENU = "Edit Actions"
    private const val CUSTOM_COORDINATES = "Custom Coordinates"

    private class OpBag {
        var ms = 0.0
        var ops = 0

        fun add(kind: OpKind, count: Double = 1.0) {
            if (count <= 0.0) return
            ms += CostModel.estimateMs(kind) * count
            ops += count.roundToInt().coerceAtLeast(1)
        }

        fun weight(probability: Double) {
            ms *= probability
        }
    }

    /** A property's cost, plus how likely it is to leave the settings screen behind it. */
    private class PropertyPlan(val plan: PlanCost, val leaveProbability: Double)

    /**
     * @param fresh true when the container was just cleared, so every action will show its
     * constructor defaults and default-valued properties are likely to short-circuit.
     */
    fun planActions(actions: List<Action>, containerTitle: String, fresh: Boolean): PlanCost =
        PlanCost.group("Actions", actions.map { planAction(it, containerTitle, fresh) })

    fun planAction(action: Action, containerTitle: String, fresh: Boolean): PlanCost {
        val displayName = (action::class.annotations.find { it is ActionDefinition } as? ActionDefinition)
            ?.displayName ?: action::class.simpleName.orEmpty()

        // A custom action just invokes a lambda; no menus at all.
        if (action is Action.CustomAction) return PlanCost(displayName, 0.0, 0)

        val bag = OpBag()
        // Already on the container screen from the previous action's closing wait.
        bag.add(OpKind.MENU_ASSERT)
        bag.add(OpKind.MENU_ROUNDTRIP)
        bag.add(OpKind.PAGE_TURN, CostModel.pageTurns(ADD_ACTION_MENU, displayName))

        val properties = PropertyReflection.propertiesOf(action)
        val children = planProperties(action, properties, fresh)

        if (properties.isNotEmpty()) {
            bag.add(OpKind.MENU_ASSERT)
            bag.add(OpKind.MENU_ROUNDTRIP)
        }

        return PlanCost(displayName, bag.ms, bag.ops, children)
    }

    fun planConditions(conditions: List<Condition>): PlanCost =
        PlanCost.group("Conditions", conditions.map { planCondition(it) })

    fun planCondition(condition: Condition): PlanCost {
        val displayName = (condition::class.annotations.find { it is DisplayName } as? DisplayName)
            ?.value ?: condition::class.simpleName.orEmpty()

        val bag = OpBag()
        bag.add(OpKind.MENU_ASSERT)
        bag.add(OpKind.MENU_ROUNDTRIP)
        bag.add(OpKind.PAGE_TURN, CostModel.pageTurns(ADD_CONDITION_MENU, displayName))

        val properties = PropertyReflection.propertiesOf(condition)
        val children = planProperties(condition, properties, fresh = true)

        if (properties.isNotEmpty()) {
            bag.add(OpKind.MENU_ASSERT)
            bag.add(OpKind.MENU_ROUNDTRIP)
        }

        return PlanCost(displayName, bag.ms, bag.ops, children)
    }

    private fun <T : PropertyHolder> planProperties(
        owner: T,
        properties: List<KProperty1<T, *>>,
        fresh: Boolean,
    ): List<PlanCost> {
        val children = mutableListOf<PlanCost>()
        var leaveProbability = 0.0
        for (property in properties) {
            val value = runCatching { property.get(owner) }.getOrNull()
            val planned = planProperty(owner, property, value, leaveProbability, fresh)
            children.add(planned.plan)
            leaveProbability = planned.leaveProbability
        }
        return children
    }

    private fun planProperty(
        owner: PropertyHolder,
        property: KProperty1<*, *>,
        value: Any?,
        leaveProbability: Double,
        fresh: Boolean,
    ): PropertyPlan {
        val label = "${owner::class.simpleName}.${property.name}"

        // Whether this property's leading settings-screen wait is a real round-trip or a cheap
        // assertion depends on whether the previous property navigated away. Blended, since that is
        // itself only a probability.
        val leading = OpBag()
        leading.ms = leaveProbability * CostModel.estimateMs(OpKind.MENU_ROUNDTRIP) +
            (1.0 - leaveProbability) * CostModel.estimateMs(OpKind.MENU_ASSERT)
        leading.ops = 1

        if (value == null) {
            return PropertyPlan(PlanCost(label, leading.ms, leading.ops), 0.0)
        }

        val body = OpBag()
        val children = mutableListOf<PlanCost>()
        var leaves = false

        when (property.returnType.classifier) {
            Int::class, Double::class, StatValue::class -> {
                body.add(OpKind.TEXT_INPUT)
                leaves = true
            }

            String::class -> {
                if (property.hasAnnotation<Pagination>()) {
                    body.add(OpKind.MENU_ROUNDTRIP)
                    body.add(OpKind.PAGE_TURN, CostModel.pageTurns(SELECT_OPTION_MENU, value.toString()))
                } else {
                    body.add(OpKind.TEXT_INPUT)
                }
                leaves = true
            }

            ItemStack::class -> {
                // The NBT give/click/restore around it is local, so only the menu costs.
                body.add(OpKind.MENU_ROUNDTRIP)
                leaves = true
            }

            Boolean::class -> {
                // Toggled in place and the refresh is awaited, so the screen stays correct.
                body.add(OpKind.MENU_ROUNDTRIP)
            }

            List::class -> {
                val entries = value as? List<*> ?: emptyList<Any?>()
                val first = entries.firstOrNull()
                if (first is Action) {
                    children += planActions(entries.filterIsInstance<Action>(), EDIT_ACTIONS_MENU, fresh = true)
                } else if (first is Condition) {
                    children += planConditions(entries.filterIsInstance<Condition>())
                }
                if (first != null) {
                    body.add(OpKind.MENU_ASSERT)
                    body.add(OpKind.MENU_ROUNDTRIP)
                }
            }

            InventorySlot::class -> {
                body.add(OpKind.MENU_ROUNDTRIP)
                body.add(OpKind.PAGE_TURN, CostModel.pageTurns(SELECT_SLOT_MENU, (value as Keyed).key))
                if (hasCustomKey(value)) body.add(OpKind.TEXT_INPUT)
                leaves = true
            }

            Sound::class -> {
                body.add(OpKind.MENU_ROUNDTRIP)
                body.add(OpKind.TEXT_INPUT)
                leaves = true
            }

            Location::class -> {
                body.add(OpKind.MENU_ROUNDTRIP)
                if (value is Location.Custom) {
                    body.add(OpKind.PAGE_TURN, CostModel.pageTurns(SELECT_OPTION_MENU, CUSTOM_COORDINATES))
                    body.add(OpKind.TEXT_INPUT)
                } else {
                    body.add(OpKind.PAGE_TURN, CostModel.pageTurns(SELECT_OPTION_MENU, (value as Keyed).key))
                }
                leaves = true
            }

            StatOp::class -> {
                val op = value as StatOp
                body.add(OpKind.MENU_ROUNDTRIP)
                // Only clicked when advanced operations are currently off, so it gets its own rate.
                if (op.advanced) {
                    body.add(OpKind.MENU_ROUNDTRIP, CostModel.workProbability("${op.key}#advancedToggle"))
                }
                body.add(OpKind.PAGE_TURN, CostModel.pageTurns(SELECT_OPTION_MENU, op.key))
                leaves = true
            }

            else -> {
                planKeyed(value, body)?.let { leaves = it } ?: return PropertyPlan(
                    PlanCost(label, leading.ms, leading.ops), leaveProbability = 0.0
                )
            }
        }

        val workProbability = workProbability(owner, property, value, fresh)
        body.weight(workProbability)

        val plan = PlanCost(label, leading.ms + body.ms, leading.ops + body.ops, children)
        return PropertyPlan(plan, if (leaves) workProbability else 0.0)
    }

    /** Returns whether the branch navigates away, or null if the value is not keyed at all. */
    private fun planKeyed(value: Any, body: OpBag): Boolean? {
        if (value !is Keyed) return null

        if (value is KeyedCycle) {
            // Clicked repeatedly in place, each click awaiting a refresh.
            body.add(
                OpKind.MENU_ROUNDTRIP,
                CostModel.cycleSteps(cycleKey(value), value.key, cycleEntryCount(value::class)),
            )
            return false
        }

        body.add(OpKind.MENU_ROUNDTRIP)
        val target = if (value is KeyedLabeled) value.label else value.key
        body.add(OpKind.PAGE_TURN, CostModel.pageTurns(SELECT_OPTION_MENU, target))
        if (hasCustomKey(value)) body.add(OpKind.TEXT_INPUT)
        return true
    }

    /**
     * How likely this property is to do GUI work rather than short-circuit. The default-value
     * heuristic only applies until real observations exist; Housing's default is not guaranteed to
     * match the Kotlin one.
     */
    private fun workProbability(owner: PropertyHolder, property: KProperty1<*, *>, value: Any?, fresh: Boolean): Double {
        val key = shapeKey(owner, property)
        if (CostModel.hasWorkData(key)) return CostModel.workProbability(key)
        if (fresh && matchesDefault(owner, property, value)) return FRESH_DEFAULT_WORK_PRIOR
        return 1.0
    }

    private fun shapeKey(owner: PropertyHolder, property: KProperty1<*, *>) =
        "${owner::class.simpleName}#${property.name}"

    @Suppress("UNCHECKED_CAST")
    private fun matchesDefault(owner: PropertyHolder, property: KProperty1<*, *>, value: Any?): Boolean {
        val defaults = PropertyReflection.defaultInstance(owner.javaClass.kotlin) ?: return false
        return runCatching { (property as KProperty1<Any, Any?>).get(defaults) }.getOrNull() == value
    }

    private fun hasCustomKey(value: Any) = value::class.annotations.any { it is CustomKey }

    private fun cycleKey(value: Keyed) = value::class.simpleName ?: "cycle"

    private fun cycleEntryCount(cls: KClass<*>): Int {
        cls.java.enclosingClass?.enumConstants?.size?.let { if (it > 0) return it }
        cls.java.enumConstants?.size?.let { if (it > 0) return it }
        return cls.sealedSubclasses.size.coerceAtLeast(2)
    }
}
