package dev.argus.client.modules.player;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.argus.client.ClientAccess;
import dev.argus.client.ClientContext;
import dev.argus.client.events.WorldRenderEvent;
import dev.argus.client.render.ColorUtils;
import dev.argus.client.render.RenderUtils;
import dev.argus.client.render.WorldBoxRenderer;
import dev.argus.client.settings.options.SpeedMineColorModeChoice;
import dev.argus.client.settings.options.SpeedMineRenderModeChoice;
import dev.argus.client.utils.FloatAnimation;
import dev.argus.client.utils.world.WorldUtils;
import java.awt.Color;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

public class SpeedMineBlockRenderState implements ClientAccess {
   private static final SpeedMineModule speedMineModule = ClientContext.getModule(SpeedMineModule.class);
   private final FloatAnimation fadeAnimation = new FloatAnimation();
   private final BlockPos blockPos;
   private AABB renderBounds;
   private long lastUpdateMillis = -1L;
   private float progress;
   private float previousProgress;

   public SpeedMineBlockRenderState(BlockPos initialBlockPos, float initialProgress) {
      this.blockPos = initialBlockPos;
      this.progress = initialProgress;
      this.previousProgress = initialProgress;
      this.fadeAnimation.setValue(1.0F);
   }

   public void render(WorldRenderEvent event) {
      this.updateFade();
      boolean instantRebreak = speedMineModule.isInstantRebreak();
      if (!this.isEmpty() || instantRebreak && speedMineModule.airSetting.getValue() || speedMineModule.fadeSetting.getValue()) {
         BlockState state = ClientAccess.argusclientMinecraft.level.getBlockState(this.blockPos);
         VoxelShape shape = state.getShape(ClientAccess.argusclientMinecraft.level, this.blockPos);
         if (!shape.isEmpty()) {
            this.renderBounds = shape.bounds();
         }

         float partialTick = RenderUtils.getPartialTick();
         float currentProgress = this.progress;
         float previousProgress = this.previousProgress;
         float interpolated = Mth.lerp(partialTick, previousProgress, currentProgress);
         float renderProgress = Mth.clamp(interpolated, 0.0F, 1.0F);
         float opacity = this.fadeAnimation.getValue();
         Color[] colors = this.getColors(renderProgress);
         AABB bounds = this.getRenderBounds(shape, renderProgress);
         WorldBoxRenderer.fillBox(event.getPoseStack(), bounds, this.withOpacity(colors[0], opacity));
         PoseStack outlinePoses = event.getPoseStack();
         Color outline = this.withOpacity(colors[1], opacity);
         float lineWidth = speedMineModule.lineWidthSetting.getValue();
         WorldBoxRenderer.outlineBox(outlinePoses, bounds, outline, lineWidth);
      }
   }

   public void snapshotProgress() {
      this.previousProgress = this.progress;
   }

   public void setProgress(float newProgress) {
      newProgress = Mth.clamp(newProgress, 0.0F, 1.0F);
      this.lastUpdateMillis = System.currentTimeMillis();
      this.previousProgress = Math.min(this.progress, newProgress);
      this.progress = newProgress;
   }

   public boolean isFadedOut() {
      return this.fadeAnimation.getValue() == 0.0F;
   }

   public BlockPos getBlockPos() {
      return this.blockPos;
   }

   private void updateFade() {
      if (System.currentTimeMillis() - this.lastUpdateMillis <= 100L) {
         this.fadeAnimation.setValue(1.0F);
      } else if (!speedMineModule.fadeSetting.getValue()) {
         this.fadeAnimation.setValue(0.0F);
      } else {
         this.fadeAnimation.setTarget(0.0F, 500L);
      }
   }

   private Color withOpacity(Color color, float opacity) {
      return ColorUtils.withAlpha(color, (int)(color.getAlpha() * opacity));
   }

   private Color[] getColors(float renderProgress) {
      SpeedMineColorModeChoice colorMode = speedMineModule.colorModeSetting.getValue();
      SpeedMineModule moduleForColors = speedMineModule;
      return colorMode.getColors(moduleForColors, renderProgress);
   }

   private AABB getRenderBounds(VoxelShape shape, float renderProgress) {
      AABB bounds = new AABB(BlockPos.ZERO);
      if (WorldUtils.isAir(this.blockPos)) {
         if (this.renderBounds != null) {
            bounds = this.renderBounds;
         }

         return bounds.move(this.blockPos);
      } else {
         if (!shape.isEmpty()) {
            bounds = shape.bounds();
         }

         bounds = bounds.move(this.blockPos);
         SpeedMineRenderModeChoice mode = speedMineModule.renderModeSetting.getValue();
         return mode.getRenderBounds(bounds, renderProgress);
      }
   }

   private boolean isEmpty() {
      BlockState state = ClientAccess.argusclientMinecraft.level.getBlockState(this.blockPos);
      VoxelShape shape = state.getShape(ClientAccess.argusclientMinecraft.level, this.blockPos);
      return shape.isEmpty() || !WorldUtils.canMineBlock(this.blockPos);
   }
}
