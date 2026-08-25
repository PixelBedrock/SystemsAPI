package llc.redstone.systemsapi.mixins;

import llc.redstone.systemsapi.util.ChatUtils;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(
            method = "handleSystemChat",
            at = @At("HEAD"),
            cancellable = true
    )
    void onGameMessage(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        ChatUtils.dispatchIncomingChat(packet.content(), ci);
    }

}
