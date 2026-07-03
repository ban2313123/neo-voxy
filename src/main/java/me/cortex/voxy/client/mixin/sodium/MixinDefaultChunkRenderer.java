package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void cancelThingie(CallbackInfo ci) {
        if (VoxyClient.disableSodiumChunkRender()) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V", shift = At.Shift.BEFORE), remap = false)
    private void injectRender(CallbackInfo ci) {
        this.doRender();
    }

    @Unique
    private void doRender() {
        var renderer = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).getVoxyRenderSystem();
        if (renderer != null) {
            // TODO: Передать актуальные матрицы и камеру
            var viewport = renderer.getViewport(); // упрощённо
            if (viewport != null) {
                renderer.renderOpaque(viewport);
            }
        }
    }
}
