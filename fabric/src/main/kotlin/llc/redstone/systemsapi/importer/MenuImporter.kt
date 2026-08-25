package llc.redstone.systemsapi.importer

//? if >=26.2 {
/*import llc.redstone.systemsapi.screen
*///?}

import llc.redstone.systemsapi.SystemsAPI.MC
import llc.redstone.systemsapi.api.Menu
import llc.redstone.systemsapi.util.ChatUtils
import llc.redstone.systemsapi.util.CommandUtils
import llc.redstone.systemsapi.util.InputUtils
import llc.redstone.systemsapi.util.ItemStackUtils.giveItem
import llc.redstone.systemsapi.util.MenuUtils
import llc.redstone.systemsapi.util.PredicateUtils.ItemMatch.ItemExact
import llc.redstone.systemsapi.util.PredicateUtils.ItemSelector
import llc.redstone.systemsapi.util.PredicateUtils.NameMatch.NameExact
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

internal class MenuImporter(override var title: String) : Menu {
    private fun isMenuEditMenuOpen(): Boolean {
        val container = MC.screen as? ContainerScreen ?: return false
        return container.title.string.contains("Edit Menu: $title")
    }

    private suspend fun openMenuEditMenu() {
        if (isMenuEditMenuOpen()) return
        CommandUtils.runCommand("menu edit $title")
        MenuUtils.onOpen("Edit Menu: $title")
    }

    override suspend fun setTitle(newTitle: String): Menu {
        if (newTitle.length !in 1..32) throw IllegalArgumentException("Title length must be in range 1..32")
        openMenuEditMenu()
        MenuUtils.clickItems(MenuItems.title)
        InputUtils.textInput(newTitle)
        title = newTitle
        return this
    }

    override suspend fun getMenuSize(): Int {
        openMenuEditMenu()
        MenuUtils.clickItems(MenuItems.size)
        MenuUtils.onOpen("Change Menu Size")
        val stack = (MC.screen as ContainerScreen).menu.container.first { stack -> stack.hasFoil() || stack.isEnchanted }
        return Regex("""\d+""").find(stack.hoverName.string)?.value?.toIntOrNull() ?: throw IllegalStateException("[Menu $title] Couldn't find menu size.")
    }

    override suspend fun setMenuSize(newSize: Int): Menu {
        if (newSize !in 1..6) throw IllegalArgumentException("New size must be in 1..6")
        openMenuEditMenu()
        MenuUtils.clickItems(MenuItems.size)
        MenuUtils.onOpen("Change Menu Size")
        if (newSize == 1) MenuUtils.clickItems("1 Row", Items.BEACON)
        else MenuUtils.clickItems("$newSize Rows", Items.BEACON)
        return this
    }

    override suspend fun getAllMenuElements(): Array<Menu.MenuElement> {
        openMenuEditMenu()
        MenuUtils.clickItems(MenuItems.elements)
        MenuUtils.onOpen("Edit Elements: $title")
        val gui = MenuUtils.currentMenu()
        val numSlots = 9 * gui.menu.rowCount
        return Array(numSlots) { index -> MenuElementImporter(index, title) }
    }

    override suspend fun getMenuElement(index: Int): Menu.MenuElement {
        openMenuEditMenu()
        MenuUtils.clickItems(MenuItems.elements)
        MenuUtils.onOpen("Edit Elements: $title")
        val gui = MenuUtils.currentMenu()
        val numSlots = 9 * gui.menu.rowCount
        if (index !in 0..<numSlots) throw IllegalArgumentException("Index must be in 0..${numSlots-1}")
        return MenuElementImporter(index, title)
    }

    suspend fun exists(): Boolean = CommandUtils.getTabCompletions("menus edit").contains(title)
    suspend fun create() {
        CommandUtils.runCommand("menus create $title")
        MenuUtils.onOpen("Edit Menu: $title")
    }
    override suspend fun delete() {
        CommandUtils.runCommand("menus delete $title")
        ChatUtils.onRecieve("Deleted the custom menu")
    }


    private object MenuItems {
        val title = ItemSelector(
            name = NameExact("Current Item"),
            item = ItemExact(Items.ANVIL)
        )
        val size = ItemSelector(
            name = NameExact("Current Size"),
            item = ItemExact(Items.BEACON)
        )
        val elements = ItemSelector(
            name = NameExact("Edit Menu Elements"),
            item = ItemExact(Items.ENDER_CHEST)
        )
    }

    internal class MenuElementImporter(val slot: Int, val title: String) : Menu.MenuElement {
        override suspend fun getItem(): ItemStack {
            MenuUtils.packetClick(slot, 1)
            MenuUtils.onOpen("Select an Item")

            val stack = MenuUtils.findSlots(MenuItems.currentItem).first().item
            return InputUtils.getItemFromMenu(null, stack) {
                MenuUtils.clickItems(MenuItems.currentItem)
            }
        }

        override suspend fun setItem(item: ItemStack): Menu.MenuElement {
            val player = MC.player ?: throw IllegalStateException("Could not access the player")
            MenuUtils.onOpen("Edit Elements: $title")

            MenuUtils.packetClick(slot, 1)
            MenuUtils.onOpen("Select an Item")

            val oldStack = player.inventory.getItem(26)
            item.giveItem(26)
            MenuUtils.clickPlayerSlot(26)
            oldStack.giveItem(26)
            return this
        }

        override suspend fun getActionContainer(): ActionContainer? {
            val gui = MenuUtils.currentMenu()
            val item = gui.menu.container.getItem(slot)
            if (item.hoverName.string == "Empty Slot" && item.get(DataComponents.LORE)?.lines()?.get(0)?.string == "Click to set item!") return null // Slot must first have an item before it can have an ActionContainer

            MenuUtils.onOpen("Edit Elements: $title")

            MenuUtils.packetClick(slot)
            MenuUtils.onOpen("Edit Actions")

            return ActionContainer("Edit Actions")
        }

        private object MenuItems {
            val currentItem = ItemSelector(
                name = NameExact("Current Item")
            )
        }
    }
}
