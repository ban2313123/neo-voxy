package me.cortex.voxy.client.core;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.IUsesMeshlets;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;

public class VoxyRenderSystem {

    private final WorldEngine worldIn;
    private final ModelBakerySubsystem modelService;
    private final RenderGenerationService renderGen;
    private final IGeometryData geometryData;
    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;
    private final RenderDistanceTracker renderDistanceTracker;
    public final ChunkBoundRenderer chunkBoundRenderer;
    private final ViewportSelector<?> viewportSelector;
    private final AbstractRenderPipeline pipeline;

    private static AbstractSectionRenderer.Factory<?,? extends IGeometryData> getRenderBackendFactory() {
        return MDICSectionRenderer.FACTORY;
    }

    public VoxyRenderSystem(WorldEngine world, ServiceManager sm) {
        world.acquireRef();
        Logger.info("Creating Voxy render system");
        System.gc();

        if (Minecraft.getInstance().options.renderDistance().get() < 3) {
            String msg = "Voxy: Having a vanilla render distance of 2 can cause rare culling issues, please use 3 or more";
            Logger.warn(msg);
            Minecraft.getInstance().getChatListener().handleSystemMessage(Component.literal(msg), false);
        }

        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        try {
            glFinish();
            glFinish();

            this.worldIn = world;

            var backendFactory = getRenderBackendFactory();

            this.modelService = new ModelBakerySubsystem(world.getMapper());
            this.renderGen = new RenderGenerationService(world, this.modelService, sm, IUsesMeshlets.class.isAssignableFrom(backendFactory.clz()));
            this.geometryData = new BasicSectionGeometryData(1<<20, RenderResourceReuse.getOrCreateGeometryBuffer());
            this.nodeManager = new AsyncNodeManager(1 << 21, this.geometryData, this.renderGen);
            this.nodeCleaner = new NodeCleaner(this.nodeManager);
            this.traversal = new HierarchicalOcclusionTraverser(this.nodeManager, this.nodeCleaner, this.renderGen);

            world.setDirtyCallback(this.nodeManager::worldEvent);
            Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
            world.getMapper().setBiomeCallback(this.modelService::addBiome);
            this.nodeManager.start();

            this.pipeline = RenderPipelineFactory.createPipeline(this.nodeManager, this.nodeCleaner, this.traversal, this::frexStillHasWork);
            this.pipeline.setupExtraModelBakeryData(this.modelService);

            this.traversal.lateStageCompile(this.pipeline);

            var sectionRenderer = backendFactory.create(this.pipeline, this.modelService.getStore(), this.geometryData);
            this.pipeline.setSectionRenderer(sectionRenderer);

            this.viewportSelector = new ViewportSelector<>(sectionRenderer::createViewport);

            {
                int minSec = Minecraft.getInstance().level.getMinSectionY() >> 5;
                int maxSec = (Minecraft.getInstance().level.getMaxSectionY() - 1) >> 5;
                if (VoxyCommon.IS_MINE_IN_ABYSS) {
                    minSec = -8;
                    maxSec = 7;
                }
                this.renderDistanceTracker = new RenderDistanceTracker(40, minSec, maxSec,
                        this.nodeManager::addTopLevel, this.nodeManager::removeTopLevel);
                this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
            }

            this.chunkBoundRenderer = new ChunkBoundRenderer(this.pipeline);

            Logger.info("Voxy render system created with " + this.geometryData.getMaxCapacity() +
                       " geometry capacity, using pipeline '" + this.pipeline.getClass().getSimpleName() +
                       "' with renderer '" + sectionRenderer.getClass().getSimpleName() + "'");
        } catch (RuntimeException e) {
            world.releaseRef();
            throw e;
        }

        // Restore OpenGL state
        for (int i = 0; i < oldBufferBindings.length; i++) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
        }
        for (int i = 0; i < 12; i++) {
            RenderSystem.activeTexture(GL11.GL_TEXTURE0 + i);
            RenderSystem.bindTexture(0);
            glBindSampler(i, 0);
        }
    }

    public Viewport<?> setupViewport(Matrix4fc vanillaProjection, Matrix4fc modelView, Object fogParameters, double cameraX, double cameraY, double cameraZ) {
        var viewport = this.getViewport();
        if (viewport == null) return null;

        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (((int)Math.floor(cameraX)>>4)+512)>>10;
            cameraX -= sector<<14;
            cameraY += (16+(256-32-sector*30))*16;
        }

        var voxyProjection = computeProjectionMat(vanillaProjection);
        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);
        int width = dims[2];
        int height = dims[3];

        if (width == 0 || height == 0) {
            Logger.error("Viewport width or height was zero");
            return null;
        }

        viewport
            .setVanillaProjection(vanillaProjection)
            .setProjection(voxyProjection)
            .setModelView(new Matrix4f(modelView))
            .setCamera(cameraX, cameraY, cameraZ)
            .setScreenSize(width, height)
            // .setFogParameters(fogParameters)  // временно убрано
            .update();

        if (VoxyClient.getOcclusionDebugState() == 0) {
            viewport.frameId++;
        }
        return viewport;
    }

    private static Matrix4f computeProjectionMat(Matrix4fc base) {
        var rawMCProj = Minecraft.getInstance().gameRenderer.getProjectionMatrix(); // упрощённый вариант
        float near = VoxyClient.disableSodiumChunkRender() ? 0.1f : (getRenderDistance() <= 32.0f ? 8f : 16f);
        float far = 16 * 3000;

        return new Matrix4f(base)
                .mulLocal(rawMCProj.invert(new Matrix4f()))
                .mulLocal(new Matrix4f().setPerspective(
                        Minecraft.getInstance().gameRenderer.getFov(),
                        (float) Minecraft.getInstance().getWindow().getWidth() / Minecraft.getInstance().getWindow().getHeight(),
                        near, far));
    }

    public void renderOpaque(Viewport<?> viewport) {
        if (viewport == null || viewport.width <= 0 || viewport.height <= 0) return;

        TimingStatistics.resetSamplers();
        TimingStatistics.all.start();
        GPUTiming.INSTANCE.marker();

        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }
        int oldFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);

        glViewport(0, 0, viewport.width, viewport.height);

        this.pipeline.preSetup(viewport);

        if ((!VoxyClient.disableSodiumChunkRender()) && !IrisUtil.irisShadowActive()) {
            this.chunkBoundRenderer.render(viewport, VoxyClient.isFrexActive());
        } else {
            // viewport.depthBoundingBuffer.clear(...);
        }

        this.pipeline.runPipeline(viewport, oldFB, viewport.width, viewport.height);

        GPUTiming.INSTANCE.marker();
        TimingStatistics.main.stop();
        TimingStatistics.postDynamic.start();

        UploadStream.INSTANCE.tick();
        while (this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ) && VoxyClient.isFrexActive());
        this.modelService.tick(900_000);

        GPUTiming.INSTANCE.marker();
        TimingStatistics.postDynamic.stop();
        GPUTiming.INSTANCE.tick();

        // Restore state
        glBindFramebuffer(GL_FRAMEBUFFER, oldFB);
        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);
        glViewport(dims[0], dims[1], dims[2], dims[3]);

        for (int i = 0; i < 12; i++) {
            RenderSystem.activeTexture(GL11.GL_TEXTURE0 + i);
            RenderSystem.bindTexture(0);
            glBindSampler(i, 0);
        }

        for (int i = 0; i < oldBufferBindings.length; i++) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
        }
    }

    private boolean frexStillHasWork() {
        if (!VoxyClient.isFrexActive()) return false;
        UploadStream.INSTANCE.tick();
        this.modelService.tick(100_000_000);
        GL11.glFinish();
        return this.nodeManager.hasWork() || this.renderGen.getTaskCount() != 0 || !this.modelService.areQueuesEmpty();
    }

    public void setRenderDistance(float renderDistance) {
        this.renderDistanceTracker.setRenderDistance((int) Math.ceil(renderDistance + 1));
    }

    public Viewport<?> getViewport() {
        if (IrisUtil.irisShadowActive()) return null;
        return this.viewportSelector.getViewport();
    }

    public void addDebugInfo(List<String> debug) {
        debug.add("Buf/Tex [#/Mb]: [" + GlBuffer.getCount() + "/" + (GlBuffer.getTotalSize()/1_000_000) + "],[" 
                 + GlTexture.getCount() + "/" + (GlTexture.getEstimatedTotalSize()/1_000_000)+"]");
        this.modelService.addDebugData(debug);
        this.renderGen.addDebugData(debug);
        this.nodeManager.addDebug(debug);
        this.pipeline.addDebug(debug);

        TimingStatistics.update();
        debug.add("Voxy frame runtime: " + TimingStatistics.all.pVal());
        debug.add(GPUTiming.INSTANCE.getDebug());
    }

    public void shutdown() {
        Logger.info("Shutting down Voxy render system...");
        try {
            this.worldIn.setDirtyCallback(null);
            this.worldIn.getMapper().setBiomeCallback(null);
            this.nodeManager.stop();
            this.modelService.shutdown();
            this.renderGen.shutdown();
            this.traversal.free();
            this.nodeCleaner.free();
            this.geometryData.free();
            this.chunkBoundRenderer.free();
            this.viewportSelector.free();
            this.pipeline.free();
        } catch (Exception e) {
            Logger.error("Error during shutdown", e);
        }
        this.worldIn.releaseRef();
        Logger.info("Render system shutdown completed");
    }

    public WorldEngine getEngine() {
        return this.worldIn;
    }

    public static float getRenderDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
    }
}
