package llc.redstone.systemsapi.util

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
//? if >=26.2 {
/*import net.minecraft.world.item.DyeColor
*///?}
import kotlin.jvm.optionals.getOrNull

// The individual XXX_DYE constants on Items were replaced in 26.2 by a single
// ColorCollection<Item> (Items.DYE), so these compat constants restore per-color access.
object Dyes {
    //? if >=26.2 {
    /*val LIME: Item = Items.DYE.pick(DyeColor.LIME)
    val LIGHT_GRAY: Item = Items.DYE.pick(DyeColor.LIGHT_GRAY)
    val GRAY: Item = Items.DYE.pick(DyeColor.GRAY)
    val RED: Item = Items.DYE.pick(DyeColor.RED)
    *///?} else {
    val LIME: Item = Items.LIME_DYE
    val LIGHT_GRAY: Item = Items.LIGHT_GRAY_DYE
    val GRAY: Item = Items.GRAY_DYE
    val RED: Item = Items.RED_DYE
    //?}
}

object ItemUtils {
    fun toNBT(itemStack: ItemStack): CompoundTag {
        return ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, itemStack).result()
            .getOrNull() as? CompoundTag
            ?: throw IllegalStateException("Could not convert item to nbt")
    }

    fun createFromNBT(nbt: CompoundTag): ItemStack {
        return ItemStack.CODEC.decode(NbtOps.INSTANCE, nbt).result().getOrNull()?.first
            ?: throw IllegalArgumentException("Failed to decode ItemStack from NBT")
    }
}
