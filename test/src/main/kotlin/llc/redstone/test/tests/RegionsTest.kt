package llc.redstone.test.tests

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import llc.redstone.systemsapi.SystemsAPI
import llc.redstone.systemsapi.api.Region
import llc.redstone.systemsdata.Action
import llc.redstone.systemsdata.StatOp
import llc.redstone.systemsdata.StatValue
import llc.redstone.test.TestMod.sendFeedback
//? if <26.1 {
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
//?} else {
/*import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
*///?}
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.MutableComponent
import java.awt.Color
import net.minecraft.network.chat.contents.PlainTextContents.create as of

object RegionsTest {
    fun LiteralArgumentBuilder<FabricClientCommandSource>.withRegionsSubCommand(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return this.then(
            literal("regions").executes { context ->
                execute(context)
            }
        )
    }

    fun execute(context: CommandContext<FabricClientCommandSource>): Int {
        SystemsAPI.launch {
            try {
                val region = SystemsAPI.getHousingImporter().getRegion("test")!!
                region.setPvpSettings(
                    mutableMapOf(
                        Pair(Region.PvpSettings.PVP, true),
                        Pair(Region.PvpSettings.KEEP_INVENTORY, true),
                        Pair(Region.PvpSettings.FIRE_DAMAGE, null),
                        Pair(Region.PvpSettings.FALL_DAMAGE, false)
                    )
                )
                region.getEntryActionContainer().addActions(
                    listOf(
                        Action.FullHeal(),
                        Action.ChangeHealth(StatValue.Dbl(10.0), StatOp.Set)
                    )
                )
                region.getExitActionContainer().addActions(
                    listOf(
                        Action.FullHeal(),
                        Action.ChangeHealth(StatValue.Dbl(10.0), StatOp.Set)
                    )
                )

                context.sendFeedback("PvP Settings", region.getPvpSettings())
                context.sendFeedback("Entry Actions", region.getEntryActionContainer().getActions())
                context.sendFeedback("Exit Actions", region.getExitActionContainer().getActions())
            } catch (e: Exception) {
                e.printStackTrace()
                context.source.sendFeedback(
                    MutableComponent.create(
                        of("[Test Mod] An error occurred: ${e.message}")
                    ).withColor(Color.RED.rgb)
                )
            }
        }
        return 1
    }
}

