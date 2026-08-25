package llc.redstone.systemsapi.mixins;

import llc.redstone.systemsapi.importer.HouseImporter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class CancelPauseScreenMixin {
    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
    private void openGameMenu(boolean pauseOnly, CallbackInfo ci) {
        if (HouseImporter.INSTANCE.isImporting()) {
            ci.cancel();
        }
    }
}
