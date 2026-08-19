package llc.redstone.systemsapi.importer

import llc.redstone.systemsapi.api.ProgressPhase
import llc.redstone.systemsapi.progress.CostModel
import llc.redstone.systemsapi.progress.ExportPlanner
import llc.redstone.systemsapi.progress.ImportPlanner
import llc.redstone.systemsapi.progress.OpRecorder
import llc.redstone.systemsapi.progress.PlanCost
import llc.redstone.systemsapi.progress.ProgressTracker
import llc.redstone.systemsapi.progress.PropertyReflection
import llc.redstone.systemsapi.util.ItemStackUtils.getLoreLineMatchesOrNull
import llc.redstone.systemsapi.util.ItemStackUtils.loreLines
import llc.redstone.systemsapi.util.MenuUtils
import llc.redstone.systemsapi.util.PredicateUtils.ItemMatch.ItemExact
import llc.redstone.systemsapi.util.PredicateUtils.ItemSelector
import llc.redstone.systemsapi.util.PredicateUtils.NameMatch.NameExact
import llc.redstone.systemsapi.util.TextUtils
import llc.redstone.systemsdata.Condition
import llc.redstone.systemsdata.DisplayName
import llc.redstone.systemsdata.VariableHolder
import net.minecraft.item.Items
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotations
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

object ConditionContainer {
    private val slots = mapOf(
        0 to 10,
        1 to 11,
        2 to 12,
        3 to 13,
        4 to 14,
        5 to 15,
        6 to 16,
        7 to 19,
        8 to 20,
        9 to 21,
        10 to 22,
        11 to 23,
        12 to 24,
        13 to 25,
        14 to 28,
        15 to 29,
        16 to 30,
        17 to 31,
        18 to 32,
        19 to 33,
        20 to 34,
    )

    //List of conditions to add to the container
    suspend fun addConditions(actions: List<Condition>) = addConditions(actions, plan = null)

    internal suspend fun addConditions(actions: List<Condition>, plan: PlanCost?) {
        if (actions.isEmpty()) return

        val conditionsPlan = plan ?: ImportPlanner.planConditions(actions)
        ProgressTracker.runRootIfIdle(ProgressPhase.IMPORTING, conditionsPlan, "Import conditions") {
            addPlannedConditions(actions, conditionsPlan)
        }
    }

    private suspend fun addPlannedConditions(actions: List<Condition>, conditionsPlan: PlanCost) {
        ProgressTracker.beginScope(conditionsPlan)
        try {
            for ((index, condition) in actions.withIndex()) {
                val conditionPlan = conditionsPlan.children.getOrNull(index) ?: PlanCost.EMPTY
                ProgressTracker.beginScope(conditionPlan)
                try {
                    //Wait for the "Edit Conditions" to open
                    //We do this every iteration to make sure we are right back at the Conditions page
                    MenuUtils.onOpen("Edit Conditions")

                    //Add a condition
                    MenuUtils.clickItems(MenuItems.ADD_CONDITION)
                    MenuUtils.onOpen("Add Condition")

                    //Get the condition parameters/properties, including the injected `inverted` and
                    //`holder` that are not constructor parameters
                    val properties = PropertyReflection.propertiesOf(condition)

                    //Get the Display Name of the condition and add it
                    val displayName = (condition::class.annotations.find { it is DisplayName } as DisplayName).value
                    MenuUtils.clickItems(displayName, paginated = true)

                    //Iterate through parameters
                    for ((propertyIndex, property) in properties.withIndex()) {
                        //Get the property and its values
                        val value = property.get(condition)

                        val propertyPlan = conditionPlan.children.getOrNull(propertyIndex) ?: PlanCost.EMPTY
                        ProgressTracker.beginScope(propertyPlan)
                        val opsBefore = OpRecorder.opCount()
                        try {
                            //Make sure we are in the right gui before continuing
                            MenuUtils.onOpen("Settings")

                            //Place in the gui to click
                            val slotIndex = slots[propertyIndex]!!
                            val slot = MenuUtils.getSlot(slotIndex)

                            PropertySettings.import(property, slot, value)
                        } finally {
                            CostModel.recordWork(
                                "${condition::class.simpleName}#${property.name}",
                                OpRecorder.opCount() - opsBefore > 1,
                            )
                            ProgressTracker.endScope()
                        }
                    }
                    //Make sure we are in the condition settings menu before we go back to actions to add another one
                    if (properties.isNotEmpty()) {
                        MenuUtils.onOpen("Settings")
                        MenuUtils.clickItems(MenuItems.BACK)
                    }
                    MenuUtils.onOpen("Edit Conditions")
                } finally {
                    ProgressTracker.endScope()
                }
            }
        } finally {
            ProgressTracker.endScope()
        }
    }

    suspend fun exportConditions(): List<Condition> {
        val conditions = mutableListOf<Condition>()

        MenuUtils.onOpen("Edit Conditions")

        if (MenuUtils.findSlots(MenuItems.NO_CONDITIONS).firstOrNull() != null) return conditions

        ExportPlanner.discoverPage(
            slots.values.map { MenuUtils.getSlot(it) },
            ExportPlanner.PAGE_ROUNDTRIPS,
            learnActionCost = false,
        )

        for (slotIndex in slots.values) {
            val slot = MenuUtils.getSlot(slotIndex)
            if (!slot.hasStack()) break //No more actions

            val item = slot.stack
            val loreLines = item.loreLines(true).filter {
                it.contains(": ") //Only care about lines with properties
            }


            val name = TextUtils.convertTextToString(item.name, false)
            var conditionClass = Condition::class.sealedSubclasses.firstOrNull { it.findAnnotations(DisplayName::class).any { ann -> ann.value == name } }
                ?: continue

            var constructor = conditionClass.primaryConstructor!!
            var parameters = constructor.parameters.toMutableList()
            var conditionProperties = conditionClass.memberProperties
            var properties = mutableListOf<Pair<KProperty1<Condition, *>, KParameter?>>()

            for (parm in parameters) {
                properties.add(conditionProperties.find { it.name == parm.name } as KProperty1<Condition, *> to parm)
            }

            suspend fun args(indexAddition: Int = 1): MutableMap<KParameter, Any?> {
                val args = mutableMapOf<KParameter, Any?>()
                properties.forEachIndexed { index, (prop, param) ->
                    if (param == null) return@forEachIndexed
                    val colorValue =
                        (loreLines.getOrNull(index + indexAddition - 1)?.split(": ")?.drop(1)?.joinToString(": ")
                            ?: return@forEachIndexed).replaceFirst("&f", "")
                    val value = colorValue.replace(Regex("&[0-9a-fk-or]"), "")

                    val returnValue = PropertySettings.export(
                        "Edit Conditions",
                        prop,
                        slot,
                        slots[index + indexAddition]!!,
                        value,
                        colorValue
                    )

                    if (returnValue is VariableHolder) {
                        conditionClass = when (returnValue) {
                            VariableHolder.Player -> Condition.PlayerVariableRequirement::class
                            VariableHolder.Global -> Condition.GlobalVariableRequirement::class
                            VariableHolder.Team -> Condition.TeamVariableRequirement::class
                        }
                        constructor = conditionClass.primaryConstructor!!
                        parameters = constructor.parameters.toMutableList()
                        conditionProperties = conditionClass.memberProperties
                        properties = mutableListOf()
                        for (parm in parameters) {
                            properties.add(conditionProperties.find { it.name == parm.name } as KProperty1<Condition, *> to parm)
                        }
                        // I hate recursion, but I think this is the cleanest way to handle it
                        return args(2)
                    }

                    args[param] = returnValue
                }
                return args
            }
            val args = args()

            var conditionInstance: Condition? = null
            if (args.size != constructor.parameters.size) {
                conditionClass.constructors.forEach { newCon ->
                    if (constructor.parameters.size == newCon.parameters.size) {
                        conditionInstance = newCon.callBy(args)
                    }
                }
            } else {
                conditionInstance = constructor.callBy(args)
            }

            if (conditionInstance == null) continue

            if (slot.stack.getLoreLineMatchesOrNull(false) {it == "Inverted"} != null) {
                conditionInstance.inverted = true
            }

            conditions.add(conditionInstance)
        }

        if (MenuUtils.findSlots(MenuUtils.GlobalMenuItems.NEXT_PAGE).firstOrNull() != null) {
            MenuUtils.clickItems(MenuUtils.GlobalMenuItems.NEXT_PAGE)
            MenuUtils.onOpen("Edit Conditions", checkIfOpen = false)
            conditions.addAll(exportConditions())
        } else {
            ExportPlanner.finishDiscovery()
        }

        return conditions
    }

    object MenuItems {
        val ADD_CONDITION = ItemSelector(
            name = NameExact("Add Condition"),
            item = ItemExact(Items.PAPER)
        )
        val BACK = ItemSelector(
            name = NameExact("Go Back"),
            item = ItemExact(Items.ARROW)
        )
        val NO_CONDITIONS = ItemSelector(
            name = NameExact("No Conditions!"),
            item = ItemExact(Items.BEDROCK)
        )
    }
}