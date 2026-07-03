package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {

    @Unique
    private static final boolean BOBBY_INSTALLED = false;

    @Shadow
    @Final
    private ClientLevel level;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void voxy$resetChunkTracker(CallbackInfo ci) {
        if (level.levelRenderer != null) {
            var system = ((IGetVoxyRenderSystem) level.levelRenderer).getVoxyRenderSystem();
            if (system != null) {
                system.chunkBoundRenderer.reset();
            }
        }
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"), remap = false)
    private void injectIngest(int x, int z, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.ingestEnabled && !BOBBY_INSTALLED) {
            var cccm = (ICheekyClientChunkCache) this.level.getChunkSource();
            if (cccm != null) {
                var chunk = cccm.voxy$cheekyGetChunk(x, z);
                if (chunk != null) {
                    VoxelIngestService.tryAutoIngestChunk(chunk);
                }
            }
        }
    }

    @Inject(method = "onChunkAdded", at = @At("HEAD"), remap = false)
    private void voxy$ingestOnAdd(int x, int z, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.ingestEnabled) {
            var chunk = this.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk != null) {
                VoxelIngestService.tryAutoIngestChunk(chunk);
            }
        }
    }

    @Unique
    private long cachedChunkPos = -1;
    @Unique
    private int cachedChunkStatus;

    @Redirect(method = "updateSectionInfo", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;setInfo(Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;)Z"), remap = false)
    private boolean voxy$updateOnUpload(net.caffeinemc.mods.sodium.client.render.chunk.RenderSection instance, net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo info) {
        // Упрощённая версия — нужно будет дорабатывать под актуальный API 0.8
        boolean wasBuilt = instance.getFlags() != 0;
        if (!instance.setInfo(info)) {
            return false;
        }

        VoxyRenderSystem system = ((IGetVoxyRenderSystem) this.level.levelRenderer).getVoxyRenderSystem();
        if (system == null) return true;

        int x = instance.getChunkX(), y = instance.getChunkY(), z = instance.getChunkZ();

        // Do some very cheeky stuff for MiB
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (x + 512) >> 10;
            x -= sector << 10;
            y += 16 + (256 - 32 - sector * 30);
        }

        long pos = SectionPos.asLong(x, y, z);

        if (wasBuilt) {
            system.chunkBoundRenderer.removeSection(pos);
        } else {
            system.chunkBoundRenderer.addSection(pos);
        }
        return true;
    }
}
