package llc.redstone.systemsapi.mixins;

import com.mojang.brigadier.suggestion.Suggestions;
import llc.redstone.systemsapi.util.CommandUtils;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class CommandSuggestionMixin {

    @Inject(
            method = "handleCommandSuggestions",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onCommandSuggestions(ClientboundCommandSuggestionsPacket packet, CallbackInfo ci) {
        if (CommandUtils.INSTANCE.getPending$systemsapi() == null) return;

        Suggestions suggestions = packet.toSuggestions();
        CommandUtils.INSTANCE.handleSuggestions$systemsapi(suggestions);
        ci.cancel();
    }

}
