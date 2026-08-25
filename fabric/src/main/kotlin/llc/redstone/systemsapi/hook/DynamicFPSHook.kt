package llc.redstone.systemsapi.hook

//? if >=26.1 {
/*class DynamicFPSHook {
    // dynamic_fps has not published a compatible API for this Minecraft version yet.
    fun disable() {}
    fun enable() {}
}
*///?} else {
import dynamic_fps.impl.DynamicFPSMod

class DynamicFPSHook {
    fun disable() {
        if (!DynamicFPSMod.isDisabled()) {
            DynamicFPSMod.toggleDisabled()
        }
    }

    fun enable() {
        if (DynamicFPSMod.isDisabled()) {
            DynamicFPSMod.toggleDisabled()
        }
    }
}
//?}
