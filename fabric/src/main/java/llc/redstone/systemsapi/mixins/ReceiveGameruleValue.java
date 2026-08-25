package llc.redstone.systemsapi.mixins;

import llc.redstone.systemsapi.importer.GameruleImporter;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ReceiveGameruleValue {

    @Inject(
            method = "handleSystemChat",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onGameMessage(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (GameruleImporter.INSTANCE.getPendingChat$systemsapi() == null) return;

        String content = packet.content().getSiblings().get(1).getString();
        Boolean value = switch (content) {
            case "enabled" -> true;
            case "disabled" -> false;
            default -> null;
        };
        if (value == null) return;

        ci.cancel();
        GameruleImporter.INSTANCE.receiveChat$systemsapi(value);
    }

}
