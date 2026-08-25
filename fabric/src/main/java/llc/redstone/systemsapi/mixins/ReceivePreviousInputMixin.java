package llc.redstone.systemsapi.mixins;

import llc.redstone.systemsapi.util.InputUtils;
import llc.redstone.systemsapi.util.MenuUtils;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.Objects;

@Mixin(ClientPacketListener.class)
public class ReceivePreviousInputMixin {

    @Inject(
            method = "handleSystemChat",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onGameMessage(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (Arrays.stream(MenuUtils.INSTANCE.getPendingClazz()).anyMatch(Objects::isNull)) {
            MenuUtils.INSTANCE.completeOnClose$systemsapi();
            return;
        }

        if (InputUtils.INSTANCE.getPendingString$systemsapi() == null) return;

        Component message = packet.content();
        if (message.getSiblings().isEmpty()) return;
        if (!message.getSiblings().get(0).getString().trim().equals("Please use the chat to provide the value you wish to set.")) return;

        Component previousComponent = message.getSiblings().get(1);
        Style previousStyle = previousComponent.getStyle();
        ClickEvent clickEvent = previousStyle.getClickEvent();
        if (clickEvent != null && clickEvent.action() != ClickEvent.Action.SUGGEST_COMMAND) return;
        ClickEvent.SuggestCommand suggestCommand = (ClickEvent.SuggestCommand) clickEvent;
        if (suggestCommand == null) return;

        ci.cancel();
        InputUtils.INSTANCE.receivePreviousInput$systemsapi(suggestCommand.command());
    }

}
