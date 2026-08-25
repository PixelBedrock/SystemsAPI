package llc.redstone.systemsapi.util

import com.mojang.brigadier.suggestion.Suggestions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import llc.redstone.systemsapi.SystemsAPI.MC
import llc.redstone.systemsapi.progress.OpKind
import llc.redstone.systemsapi.progress.OpRecorder
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket

object CommandUtils {
    fun runCommand(command: String) {
        Minecraft.getInstance().player
            ?.connection
            ?.sendCommand(command) ?: throw IllegalStateException("Unable to send command $command")

    }

    internal var pending: CompletableDeferred<List<String>>? = null
    suspend fun getTabCompletions(baseCommand: String): List<String> =
        OpRecorder.span(OpKind.TAB_COMPLETE) {
            val partialCommand = buildString {
                append(if (baseCommand.startsWith('/')) baseCommand else "/$baseCommand")
                if (!endsWith(' ')) append(' ')
            }

            val deferred = CompletableDeferred<List<String>>()
            pending?.cancel()
            pending = deferred

            try {
                MC.connection?.send(ServerboundCommandSuggestionPacket(1, partialCommand))
                withTimeout(1_000) { deferred.await() }
            } finally {
                if (pending === deferred) pending = null
            }
        }

    internal fun handleSuggestions(suggestions: Suggestions) {
        pending?.let { current ->
            pending = null
            current.complete(suggestions.list.map { it.text })
        }
    }
}