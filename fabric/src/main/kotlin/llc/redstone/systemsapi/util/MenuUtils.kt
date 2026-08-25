@file:Suppress("UnstableApiUsage")

package llc.redstone.systemsapi.util

//? if >=26.2 {
/*import llc.redstone.systemsapi.screen
*///?}

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import llc.redstone.systemsapi.SystemsAPI.CONFIG
import llc.redstone.systemsapi.SystemsAPI.LOGGER
import llc.redstone.systemsapi.SystemsAPI.MC
import llc.redstone.systemsapi.SystemsAPI.scaledDelay
import llc.redstone.systemsapi.progress.CostModel
import llc.redstone.systemsapi.progress.OpKind
import llc.redstone.systemsapi.progress.OpRecorder
import llc.redstone.systemsapi.util.ErrorCorrection.BasicClick
import llc.redstone.systemsapi.util.PredicateUtils.ItemMatch.ItemExact
import llc.redstone.systemsapi.util.PredicateUtils.ItemSelector
import llc.redstone.systemsapi.util.PredicateUtils.NameMatch
import llc.redstone.systemsapi.util.PredicateUtils.NameMatch.NameContains
import llc.redstone.systemsapi.util.PredicateUtils.NameMatch.NameWithin
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.network.HashedStack
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
//? if >=26.1 {
/*import net.minecraft.world.inventory.ContainerInput
*///?} else {
import net.minecraft.world.inventory.ClickType
//?}
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.reflect.KClass

object MenuUtils {
    var pendingScreen: CompletableDeferred<Screen?>? = null
    var pendingClazz: Array<out KClass<out Screen>?> = arrayOf()
    var pendingNameMatch: NameMatch? = null

    suspend fun onCurrentScreenUpdate() {
        val screen = MC.screen ?: return
        onOpen(screen.title?.string ?: return, checkIfOpen = false)
    }

    suspend fun onOpen(
        name: String,
        vararg clazz: KClass<out Screen>? = arrayOf(ContainerScreen::class),
        checkIfOpen: Boolean = true
    ): Screen? {
        return onOpen(NameContains(name), *clazz, checkIfOpen = checkIfOpen)
    }

    suspend fun onOpen(
        nameMatch: NameMatch?,
        vararg clazz: KClass<out Screen>? = arrayOf(ContainerScreen::class),
        checkIfOpen: Boolean = false,
        errorCorrection: Boolean = true
    ): Screen? = OpRecorder.span(OpKind.MENU_ROUNDTRIP) { span ->
        suspend fun reset() {
            pendingScreen = null
            pendingNameMatch = null
            pendingClazz = arrayOf()
            if (MC.screen is AbstractContainerScreen<*>) awaitUntilMenuItemsLoaded()
        }

        val deferred = CompletableDeferred<Screen?>()
        pendingScreen?.cancel()
        pendingScreen = deferred
        pendingClazz = clazz
        pendingNameMatch = nameMatch

        val alreadyOpen = if (checkIfOpen) {
            MC.screen?.takeIf { screen ->
                pendingClazz.any { it?.isInstance(screen) == true } &&
                    pendingNameMatch?.matches(screen.title?.string ?: "null") != false
            }
        } else null

        if (alreadyOpen != null) {
            // No packet round-trip paid for, only the item-load wait and settle delay.
            span.kind = OpKind.MENU_ASSERT
            reset()
            alreadyOpen
        } else {
            try {
                withTimeout(CONFIG.menuTimeout) {
                    deferred.await()
                }
            } catch (_: Exception) {
                span.timedOut = true
                if (checkScreen(MC.screen)) {
                    LOGGER.debug("Menu opened during timeout: {}", nameMatch)
                    MC.screen
                } else {
                    if (errorCorrection && ErrorCorrection.onMenuTimeout()) {
                        LOGGER.debug("Menu corrected on timeout: {}", nameMatch)
                        MC.screen
                    } else {
                        error("Timed out waiting for menu: $nameMatch")
                    }
                }
            } finally {
                reset()
            }
        }
    }

    internal fun completeOnClose() {
        val pending = pendingScreen ?: return
        val screen = MC.screen
        if (!checkScreen(screen)) return
        pendingScreen = null
        pending.complete(null)
    }

    private fun checkScreen(screen: Screen?): Boolean {
        if (screen == null && pendingNameMatch == null && pendingClazz.contains(null)) return true
        if (pendingClazz.isNotEmpty()) {
            val matchesClass = pendingClazz.any { it?.isInstance(screen) == true }
            if (!matchesClass) return false
        }
        if (pendingNameMatch != null) {
            val title = screen?.title?.string ?: "null"
            if (!pendingNameMatch!!.matches(title)) return false
        }
        return true
    }

    internal fun completeOnOpenScreen(screen: Screen) {
        val pending = pendingScreen ?: return
        if (!checkScreen(screen)) return
        pendingScreen = null
        pending.complete(screen)
    }

    var isLoading = false
    var lastItemAddedTimestamp = 0L
    var itemsLoaded = mutableMapOf<String, ItemStack>()

    var pendingLoaded: CompletableDeferred<Screen>? = null
    private suspend fun awaitUntilMenuItemsLoaded(): Screen {
        val deferred = CompletableDeferred<Screen>()
        pendingLoaded?.cancel()
        pendingLoaded = deferred

        return try {
            isLoading = true
            itemsLoaded.clear()
            lastItemAddedTimestamp = System.currentTimeMillis()
            withTimeout(CONFIG.menuItemLoadedTimeout) {
                deferred.await()
            }
        } finally {
            if (pendingLoaded === deferred) pendingLoaded = null
            scaledDelay()
        }
    }

    internal fun render() {
        if (!isLoading) return
        val screen = MC.screen as? AbstractContainerScreen<*> ?: return
        val delay = 0L // your gui delay
        if (System.currentTimeMillis() - lastItemAddedTimestamp < delay) return

        val slots = screen.menu.slots
        var startIndex = slots.size - 44
        if (startIndex < 0) {
            startIndex = 0
        }
        val hotbarSlots = slots.subList(startIndex, startIndex + 9)
        if (hotbarSlots.all { it.item.isEmpty }) return
        isLoading = false
        val pending = pendingLoaded ?: return
        pendingLoaded = null
        pending.complete(screen)
    }

    internal fun renderStack(stack: ItemStack) {
        if (!isLoading) return
        val displayName = stack.hoverName.string
        if (itemsLoaded.containsKey(displayName)) return
        lastItemAddedTimestamp = System.currentTimeMillis()
        itemsLoaded[displayName] = stack
    }

    fun clickPlayerSlot(slot: Int, button: Int = 0) {
        ErrorCorrection.lastPlayerSlotClick = BasicClick(slot, button)
        val gui = currentMenu()
        val playerSlot = when (slot) {
            in 0..8 -> slot + gui.menu.slots.size - 9
            in 9..35 -> {
                slot + gui.menu.slots.size - 45
            }
            else -> throw IllegalArgumentException("Invalid player slot index: $slot")
        }
        packetClick(playerSlot, button)
    }

    // CORE UTILS

    fun packetClick(slot: Int, button: Int = 0) {
        ErrorCorrection.lastMenuSlotClick = BasicClick(slot, button)
        val gui = MC.screen as? AbstractContainerScreen<*> ?: return

        val pkt = ServerboundContainerClickPacket(
            gui.menu.containerId,
            gui.menu.stateId,
            slot.toShort(),
            button.toByte(),
            //? if >=26.1 {
            /*ContainerInput.PICKUP,
            *///?} else {
            ClickType.PICKUP,
            //?}
            Int2ObjectOpenHashMap(),
            HashedStack.EMPTY
        )

        MC.connection?.send(pkt) ?: error("Failed to send click packet")
    }

    fun interactionClick(slot: Int, button: Int = 0) {
        ErrorCorrection.lastMenuSlotClick = BasicClick(slot, button)
        val gui = MC.screen as? AbstractContainerScreen<*> ?: return

        val player = MC.player ?: return
        //? if >=26.1 {
        /*MC.gameMode?.handleContainerInput(
            gui.menu.containerId,
            slot,
            button,
            ContainerInput.PICKUP,
            player
        )
        *///?} else {
        MC.gameMode?.handleInventoryMouseClick(
            gui.menu.containerId,
            slot,
            button,
            ClickType.PICKUP,
            player
        )
        //?}
    }

    fun currentMenu(): ContainerScreen =
        MC.screen as? ContainerScreen
        ?: throw ClassCastException("Expected ContainerScreen but found ${MC.screen?.javaClass?.name}")

    // FINDING ITEMS IN MENUS

    /**
     * @param cacheKey stable name of what is being looked for. Combined with [paginated], the number
     * of page turns needed to reach it is learned, so a later import knows that (say) "Send Message"
     * sits three pages into the "Add Action" menu instead of assuming an average.
     */
    suspend fun findSlots(
        predicate: (ItemStack) -> Boolean,
        paginated: Boolean = false,
        cacheKey: String? = null
    ): List<Slot> {
        fun currentSlots() = currentMenu().menu.slots.filter { predicate(it.item) }

        val learnKey = if (paginated) cacheKey else null
        val menuTitle = if (learnKey != null) currentMenu().title.string else null

        var slots = currentSlots()
        var turns = 0
        while (slots.isEmpty() && paginated) {
            val nextPageSlot = findSlots(GlobalMenuItems.NEXT_PAGE).firstOrNull() ?: return emptyList()
            // Spanned rather than timed inline so the scaledDelay inside is not counted twice.
            OpRecorder.span(OpKind.PAGE_TURN) {
                packetClick(nextPageSlot.index)
                scaledDelay(4.0)
            }
            turns++
            slots = currentSlots()
        }
        if (learnKey != null && menuTitle != null) CostModel.recordPageTurns(menuTitle, learnKey, turns)
        return slots
    }

    suspend fun findSlots(name: String, paginated: Boolean = false, partial: Boolean = false): List<Slot> {
        return findSlots({
            if (!partial) it.hoverName.string == name else it.hoverName.string.contains(name)
        }, paginated, cacheKey = name)
    }

    suspend fun findSlots(name: String, item: Item, paginated: Boolean = false): List<Slot> {
        return findSlots({
            it.hoverName.string == name &&
            it.item == item
        }, paginated, cacheKey = name)
    }

    suspend fun findSlots(selector: ItemSelector, paginated: Boolean = false): List<Slot> {
        return findSlots(selector.toPredicate(), paginated, cacheKey = selector.cacheKey)
    }

    fun getSlot(slotIndex: Int): Slot {
        return currentMenu().menu.getSlot(slotIndex)
    }

    // CLICKING ITEMS IN MENUS
    suspend fun clickItems(
        predicate: (ItemStack) -> Boolean,
        packet: Boolean = true,
        button: Int = 0,
        paginated: Boolean = false,
        cacheKey: String? = null
    ) {
        findSlots(predicate, paginated, cacheKey).forEach { slot ->
            when (packet) {
                true -> packetClick(slot.index, button)
                false -> interactionClick(slot.index, button)
            }
        }
    }

    suspend fun clickItems(name: String, packet: Boolean = false, button: Int = 0, paginated: Boolean = false) {
        clickItems(
            {
                it.hoverName.string == name
            },
            packet,
            button,
            paginated,
            cacheKey = name
        )
    }

    suspend fun clickItems(name: String, item: Item, packet: Boolean = true, button: Int = 0, paginated: Boolean = false) {
        clickItems(
            {
                it.hoverName.string == name &&
                it.item == item
            },
            packet,
            button,
            paginated,
            cacheKey = name
        )
    }

    suspend fun clickItems(selector: ItemSelector, packet: Boolean = true, button: Int = 0, paginated: Boolean = false) {
        clickItems(
            selector.toPredicate(),
            packet,
            button,
            paginated,
            cacheKey = selector.cacheKey
        )
    }

    object GlobalMenuItems {
        val NEXT_PAGE = ItemSelector(
            name = NameWithin(listOf("Next Page", "Left-click for next page!")),
            item = ItemExact(Items.ARROW)
        )
        val PREVIOUS_PAGE = ItemSelector(
            name = NameWithin(listOf("Last Page", "Left-click for previous page!")),
            item = ItemExact(Items.ARROW)
        )
    }
}
