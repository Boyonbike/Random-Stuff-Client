package dev.argus.client.modules.combat;

import dev.argus.client.ClientAccess;
import dev.argus.client.ClientContext;
import dev.argus.client.managers.ModuleRegistry;
import dev.argus.client.modules.player.SpeedMineModule;
import dev.argus.client.settings.Setting;
import dev.argus.client.utils.world.HoleInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class PlainHoleFillPolicy implements ClientAccess {
   private static SpeedMineModule speedMineModule = ClientContext.getModule(SpeedMineModule.class);
   protected final HoleFillModule holeFillModule;

   public PlainHoleFillPolicy(HoleFillModule holeFillModule) {
      this.holeFillModule = holeFillModule;
   }

   public boolean shouldFill(HoleInfo hole) {
      if (speedMineModule.isToggled()) {
         if (speedMineModule.isInstantRebreak() && speedMineModule.getMiningPos() != null) {
            BlockPos miningPos = speedMineModule.getMiningPos();
            Vec3 miningCenter = miningPos.getCenter();
            AABB miningBoundsSnapshot = hole.bounds();
            if (miningBoundsSnapshot.contains(miningCenter)) {
               return false;
            }
         }
      }

      AABB widthBounds = hole.bounds();
      if ((widthBounds.getXsize() != 1.0 || hole.bounds().getZsize() != 1.0) && !this.holeFillModule.doublesSetting.getValue()) {
         return false;
      } else {
         Vec3 eyeSnapshot = ClientAccess.argusclientMinecraft.player.getEyePosition();
         BlockPos rangePosition = hole.pos();
         Vec3 holeCenter = rangePosition.getCenter();
         double distance = eyeSnapshot.distanceTo(holeCenter);
         Float configuredRange = this.holeFillModule.rangeSetting.getValue();
         if (distance <= configuredRange.floatValue()) {
            double eyeY = ClientAccess.argusclientMinecraft.player.getEyePosition().y;
            BlockPos verticalPosition = hole.pos();
            double verticalDifference = eyeY - verticalPosition.getCenter().y;
            double absoluteVerticalDifference = Math.abs(verticalDifference);
            Float configuredVerticalRange = this.holeFillModule.verticalRangeSetting.getValue();
            return absoluteVerticalDifference <= configuredVerticalRange.floatValue();
         } else {
            return false;
         }
      }
   }

   public static void connectSpeedMineModule(ModuleRegistry registry) {
      speedMineModule = (SpeedMineModule)registry.getModule(SpeedMineModule.class);
   }
}
