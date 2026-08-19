package llc.redstone.systemsapi.progress

import llc.redstone.systemsdata.Action
import llc.redstone.systemsdata.Condition
import llc.redstone.systemsdata.PropertyHolder
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * The single source of truth for which properties the importer walks, and in what order.
 *
 * This has to be shared between the containers and [ImportPlanner]. The list is not just the primary
 * constructor's parameters: `holder` is prepended for [Action.ChangeVariable], and conditions get
 * `inverted` prepended plus `holder` for variable requirements. Deriving it twice means the planner
 * and the importer disagree about what work exists.
 *
 * Cached per class; `memberProperties` is not cached by kotlin-reflect.
 */
internal object PropertyReflection {

    private val propertyCache = ConcurrentHashMap<KClass<*>, List<KProperty1<*, *>>>()
    private val defaultCache = ConcurrentHashMap<KClass<*>, Any>()

    private val NO_DEFAULT = Any()

    @Suppress("UNCHECKED_CAST")
    fun <T : PropertyHolder> propertiesOf(holder: T): List<KProperty1<T, *>> =
        propertyCache.getOrPut(holder.javaClass.kotlin) { compute(holder) } as List<KProperty1<T, *>>

    private fun compute(holder: PropertyHolder): List<KProperty1<*, *>> {
        val cls = holder.javaClass.kotlin
        val members = cls.memberProperties
        val constructor = cls.primaryConstructor ?: return emptyList()

        fun member(name: String) = members.find { it.name == name }

        val properties = mutableListOf<KProperty1<*, *>>()
        for (parameter in constructor.parameters) {
            properties.add(member(parameter.name ?: continue) ?: continue)
        }

        when (holder) {
            is Action.ChangeVariable -> member("holder")?.let { properties.add(0, it) }

            is Condition -> {
                members.find { it.name == "inverted" }?.let { properties.add(0, it) }
                if (holder is Condition.VariableRequirement) {
                    member("holder")?.let { properties.add(1, it) }
                }
            }

            else -> {}
        }

        return properties
    }

    /**
     * An instance built entirely from constructor defaults, or null if it cannot be built that way.
     *
     * Housing shows a new action's defaults, so a property already equal to its default usually
     * short-circuits rather than doing GUI work.
     */
    fun defaultInstance(cls: KClass<out PropertyHolder>): PropertyHolder? {
        val cached = defaultCache.getOrPut(cls) {
            val constructor = cls.primaryConstructor
            val built = if (constructor == null) null else runCatching { constructor.callBy(emptyMap()) }.getOrNull()
            built ?: NO_DEFAULT
        }
        return cached as? PropertyHolder
    }
}
