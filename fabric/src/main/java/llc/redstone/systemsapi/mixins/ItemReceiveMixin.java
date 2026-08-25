package llc.redstone.systemsapi.mixins;

import llc.redstone.systemsapi.util.InputUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ItemReceiveMixin {
    @Inject(method = "handleContainerSetSlot", at = @At("HEAD"))
    private void onItemReceived(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        if (InputUtils.INSTANCE.getPendingStack$systemsapi() == null) return;
        ItemStack oldStack = Minecraft.getInstance().player.containerMenu.getSlot(packet.getSlot()).getItem();
        ItemStack newStack = packet.getItem();
        if (oldStack.isEmpty()) {
            if (newStack.isEmpty()) return;
            InputUtils.INSTANCE.onItemReceived$systemsapi(newStack);
        } else {
            ItemStack stack = packet.getItem().copyWithCount(newStack.getCount() - oldStack.getCount());
            if (stack.isEmpty()) return;
            InputUtils.INSTANCE.onItemReceived$systemsapi(stack);
        }
    }
}
