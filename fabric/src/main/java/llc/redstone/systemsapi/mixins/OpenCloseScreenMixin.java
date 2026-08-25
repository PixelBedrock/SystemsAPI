package llc.redstone.systemsapi.mixins;

import llc.redstone.systemsapi.util.MenuUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class OpenCloseScreenMixin {
    @Inject(method = "handleOpenScreen", at = @At(value = "RETURN"))
    private void onScreenOpen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        //? if >=26.2 {
        /*Screen screen = Minecraft.getInstance().gui.screen();
        *///?} else {
        Screen screen = Minecraft.getInstance().screen;
        //?}
        if (screen == null) return;
        MenuUtils.INSTANCE.completeOnOpenScreen$systemsapi(screen);
    }

    @Inject(method = "handleContainerClose", at = @At(value = "RETURN"))
    private void onScreenClose(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        MenuUtils.INSTANCE.completeOnClose$systemsapi();
    }
}
