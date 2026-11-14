package dev.argus.client.utils.inventory;

import dev.argus.client.ClientAccess;
import dev.argus.client.ClientContext;
import dev.argus.client.managers.ModuleRegistry;
import dev.argus.client.modules.combat.AutoMineMiningTracker;
import dev.argus.client.modules.combat.AutoMineModule;
import dev.argus.client.modules.player.SpeedMineModule;
import dev.argus.client.settings.Setting;
import dev.argus.client.settings.options.BlockSampleMode;
import dev.argus.client.utils.world.BlockInteractionUtils;
import dev.argus.client.utils.world.WorldUtils;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public abstract class AutoMineProviderBase implements ClientAccess, AutoMineProvider {
   private static SpeedMineModule speedMine = ClientContext.getModule(SpeedMineModule.class);
   protected final AutoMineModule autoMine;

   protected boolean hasNoCollisionBelow(Entity entity) {
      ClientLevel level = ClientAccess.argusclientMinecraft.level;
      AABB collisionBounds = entity.getBoundingBox().expandTowards(0.0, -0.1, 0.0);
      return level.noBlockCollision(entity, collisionBounds);
   }

   public AutoMineProviderBase(AutoMineModule module) {
      this.autoMine = module;
   }

   protected boolean canMinePosition(BlockPos position) {
      AutoMineMiningTracker miningTracker = this.autoMine.miningTracker;
      if (position.equals(miningTracker.getRecentPosition())) {
         return false;
      }

      SpeedMineModule rangeCheck = speedMine;
      if (!rangeCheck.isInMiningRange(position)) {
         return false;
      }

      if (!speedMine.isBlockAllowed(position)) {
         return false;
      }

      Setting<Boolean> strictDirectionSetting = this.autoMine.strictDirectionSetting;
      if (strictDirectionSetting.getValue() && BlockInteractionUtils.getStrictDirections(position).isEmpty()) {
         return false;
      }

      Setting<Boolean> raytraceSetting = this.autoMine.raytraceSetting;
      if (raytraceSetting.getValue()) {
         List<Vec3> raytracePoints = BlockSampleMode.X8.sampleBlock(position);
         if (!WorldUtils.canSeeAny(raytracePoints)) {
            return false;
         }
      }

      return this.autoMine.hasAssistantBreakProgress(position) ? false : WorldUtils.canMineBlock(position);
   }

   protected boolean isAlreadyMining(BlockPos position) {
      return position != null && (position.equals(speedMine.getMiningPos()) || position.equals(speedMine.getRebreakPos()));
   }

   @Override
   public boolean isEnabled() {
      return false;
   }

   public static void bindModules(ModuleRegistry registry) {
      speedMine = (SpeedMineModule)registry.getModule(SpeedMineModule.class);
   }
}
