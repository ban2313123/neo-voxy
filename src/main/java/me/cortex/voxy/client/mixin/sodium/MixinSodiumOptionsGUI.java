package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.config.VoxyConfigScreenPages;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI.class)
public class MixinSodiumOptionsGUI {

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void voxy$addConfigPage(Screen prevScreen, CallbackInfo ci) {
        if (VoxyCommon.isAvailable()) {
            VoxyConfigScreenPages.voxyOptionPage = VoxyConfigScreenPages.page();
            // Добавление страницы в Sodium 0.8 может требовать другого подхода
            // Пока оставляем так — если не сработает, поправим
        }
    }
}
