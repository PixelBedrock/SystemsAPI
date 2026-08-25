package llc.redstone.systemsapi.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import llc.redstone.systemsapi.SystemsAPI.CONFIG
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

object ChatUtils {

    private data class Listener(
        val predicate: (Component) -> Boolean,
        val deferred: CompletableDeferred<Component>,
        val cancel: Boolean
    )
    private val listeners = mutableListOf<Listener>()

    suspend fun onRecieve(
        predicate: (Component) -> Boolean,
        cancel: Boolean = false,
    ): Component {
        return try {
            withTimeout(CONFIG.menuTimeout) {
                val deferred = CompletableDeferred<Component>()
                val listener = Listener(predicate, deferred, cancel)
                synchronized(listeners) {
                    listeners += listener
                }
                try {
                    deferred.await()
                } finally {
                    synchronized(listeners) {
                        listeners.remove(listener)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw e
        }
    }

    suspend fun onRecieve(
        message: String,
        cancel: Boolean = false,
    ): Component {
        return onRecieve(
            { it.string == message },
            cancel
        )
    }

    @JvmStatic
    fun dispatchIncomingChat(text: Component, ci: CallbackInfo) {
        val toComplete = synchronized(listeners) {
            listeners.filter { it.predicate(text) }
        }
        if (toComplete.isNotEmpty()) {
            synchronized(listeners) {
                toComplete.forEach {
                    if (it.cancel) {
                        ci.cancel()
                    }
                    it.deferred.complete(text)
                    listeners.remove(it)
                }
            }
        }
    }

}