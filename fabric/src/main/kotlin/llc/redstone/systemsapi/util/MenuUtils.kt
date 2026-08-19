@file:Suppress("UnstableApiUsage")

package llc.redstone.systemsapi.util

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
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket
import net.minecraft.screen.slot.Slot
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.screen.sync.ItemStackHash
import kotlin.reflect.KClass

object MenuUtils {
    var pendingScreen: CompletableDeferred<Screen?>? = null
    var pendingClazz: Array<out KClass<out Screen>?> = arrayOf()
    var pendingNameMatch: NameMatch? = null

    suspend fun onCurrentScreenUpdate() {
        val screen = MC.currentScreen ?: return
        onOpen(screen.title?.string ?: return, checkIfOpen = false)
    }

    suspend fun onOpen(
        name: String,
        vararg clazz: KClass<out Screen>? = arrayOf(GenericContainerScreen::class),
        checkIfOpen: Boolean = true
    ): Screen? {
        return onOpen(NameContains(name), *clazz, checkIfOpen = checkIfOpen)
    }

    suspend fun onOpen(
        nameMatch: NameMatch?,
        vararg clazz: KClass<out Screen>? = arrayOf(GenericContainerScreen::class),
        checkIfOpen: Boolean = false,
        errorCorrection: Boolean = true
    ): Screen? = OpRecorder.span(OpKind.MENU_ROUNDTRIP) { span ->
        suspend fun reset() {
            pendingScreen = null
            pendingNameMatch = null
            pendingClazz = arrayOf()
            if (MC.currentScreen is HandledScreen<*>) awaitUntilMenuItemsLoaded()
        }

        val deferred = CompletableDeferred<Screen?>()
        pendingScreen?.cancel()
        pendingScreen = deferred
        pendingClazz = clazz
        pendingNameMatch = nameMatch

        val alreadyOpen = if (checkIfOpen) {
            MC.currentScreen?.takeIf { screen ->
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
                if (checkScreen(MC.currentScreen)) {
                    LOGGER.debug("Menu opened during timeout: {}", nameMatch)
                    MC.currentScreen
                } else {
                    if (errorCorrection && ErrorCorrection.onMenuTimeout()) {
                        LOGGER.debug("Menu corrected on timeout: {}", nameMatch)
                        MC.currentScreen
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
        val screen = MC.currentScreen
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
        val screen = MC.currentScreen as? HandledScreen<*> ?: return
        val delay = 0L // your gui delay
        if (System.currentTimeMillis() - lastItemAddedTimestamp < delay) return

        val slots = screen.screenHandler.slots
        var startIndex = slots.size - 44
        if (startIndex < 0) {
            startIndex = 0
        }
        val hotbarSlots = slots.subList(startIndex, startIndex + 9)
        if (hotbarSlots.all { it.stack.isEmpty }) return
        isLoading = false
        val pending = pendingLoaded ?: return
        pendingLoaded = null
        pending.complete(screen)
    }

    internal fun renderStack(stack: ItemStack) {
        if (!isLoading) return
        val displayName = stack.name.string
        if (itemsLoaded.containsKey(displayName)) return
        lastItemAddedTimestamp = System.currentTimeMillis()
        itemsLoaded[displayName] = stack
    }

    fun clickPlayerSlot(slot: Int, button: Int = 0) {
        ErrorCorrection.lastPlayerSlotClick = BasicClick(slot, button)
        val gui = currentMenu()
        val playerSlot = when (slot) {
            in 0..8 -> slot + gui.screenHandler.slots.size - 9
            in 9..35 -> {
                slot + gui.screenHandler.slots.size - 45
            }
            else -> throw IllegalArgumentException("Invalid player slot index: $slot")
        }
        packetClick(playerSlot, button)
    }

    // CORE UTILS

    fun packetClick(slot: Int, button: Int = 0) {
        ErrorCorrection.lastMenuSlotClick = BasicClick(slot, button)
        val gui = MC.currentScreen as? HandledScreen<*> ?: return

        val pkt = ClickSlotC2SPacket(
            gui.screenHandler.syncId,
            gui.screenHandler.revision,
            slot.toShort(),
            button.toByte(),
            SlotActionType.PICKUP,
            Int2ObjectOpenHashMap(),
            ItemStackHash.EMPTY
        )

        MC.networkHandler?.sendPacket(pkt) ?: error("Failed to send click packet")
    }

    fun interactionClick(slot: Int, button: Int = 0) {
        ErrorCorrection.lastMenuSlotClick = BasicClick(slot, button)
        val gui = MC.currentScreen as? HandledScreen<*> ?: return

        MC.interactionManager?.clickSlot(
            gui.screenHandler.syncId,
            slot,
            button,
            SlotActionType.PICKUP,
            MC.player
        )
    }

    fun currentMenu(): GenericContainerScreen =
        MC.currentScreen as? GenericContainerScreen
        ?: throw ClassCastException("Expected GenericContainerScreen but found ${MC.currentScreen?.javaClass?.name}")

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
        fun currentSlots() = currentMenu().screenHandler.slots.filter { predicate(it.stack) }

        val learnKey = if (paginated) cacheKey else null
        val menuTitle = if (learnKey != null) currentMenu().title.string else null

        var slots = currentSlots()
        var turns = 0
        while (slots.isEmpty() && paginated) {
            val nextPageSlot = findSlots(GlobalMenuItems.NEXT_PAGE).firstOrNull() ?: return emptyList()
            // Spanned rather than timed inline so the scaledDelay inside is not counted twice.
            OpRecorder.span(OpKind.PAGE_TURN) {
                packetClick(nextPageSlot.id)
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
            if (!partial) it.name.string == name else it.name.string.contains(name)
        }, paginated, cacheKey = name)
    }

    suspend fun findSlots(name: String, item: Item, paginated: Boolean = false): List<Slot> {
        return findSlots({
            it.name.string == name &&
            it.item == item
        }, paginated, cacheKey = name)
    }

    suspend fun findSlots(selector: ItemSelector, paginated: Boolean = false): List<Slot> {
        return findSlots(selector.toPredicate(), paginated, cacheKey = selector.cacheKey)
    }

    fun getSlot(slotIndex: Int): Slot {
        return currentMenu().screenHandler.getSlot(slotIndex)
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
                true -> packetClick(slot.id, button)
                false -> interactionClick(slot.id, button)
            }
        }
    }

    suspend fun clickItems(name: String, packet: Boolean = false, button: Int = 0, paginated: Boolean = false) {
        clickItems(
            {
                it.name.string == name
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
                it.name.string == name &&
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
