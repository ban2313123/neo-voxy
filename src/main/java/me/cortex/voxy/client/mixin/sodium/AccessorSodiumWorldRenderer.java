package me.cortex.voxy.client.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface AccessorSodiumWorldRenderer {

    @Accessor(remap = false)
    RenderSectionManager getRenderSectionManager();

    // Если в 0.8 есть другие нужные поля — добавим позже
}
