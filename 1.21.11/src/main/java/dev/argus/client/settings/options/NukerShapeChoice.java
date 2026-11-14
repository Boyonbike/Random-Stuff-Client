package dev.argus.client.settings.options;

import dev.argus.client.ClientAccess;
import dev.argus.client.ClientContext;
import dev.argus.client.managers.ModuleRegistry;
import dev.argus.client.modules.player.NukerModule;
import dev.argus.client.modules.player.SpeedMineModule;
import dev.argus.client.settings.NamedEntry;
import dev.argus.client.settings.Setting;
import dev.argus.client.utils.world.NukerBlockQueue;
import dev.argus.client.utils.world.WorldUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public enum NukerShapeChoice implements NamedEntry {
   SPHERE("Sphere") {
      @Override
      public List collectPositions(NukerModule nukerModule) {
         LocalPlayer playerForPosition = ClientAccess.argusclientMinecraft.player;
         Vec3 positionSnapshot = playerForPosition.position();
         Setting<Float> rangeSettingRef = nukerModule.rangeSetting;
         Float configuredRange = rangeSettingRef.getValue();
         float rangeSnapshot = configuredRange;
         return new ArrayList<>(WorldUtils.collectSphereBlocks(positionSnapshot, rangeSnapshot, true));
      }
   },
   TUNNEL("Tunnel") {
      @Override
      public List collectPositions(NukerModule nukerModule) {
         ArrayList positions = new ArrayList();
         Setting<Integer> heightSettingRef = nukerModule.heightSetting;
         double height;
         if (heightSettingRef.isAtMinimum()) {
            LocalPlayer playerForHeight = ClientAccess.argusclientMinecraft.player;
            double playerHeight = playerForHeight.getBbHeight();
            height = Math.ceil(playerHeight);
         } else {
            Setting<Integer> configuredHeightSetting = nukerModule.heightSetting;
            Integer configuredHeight = configuredHeightSetting.getValue();
            height = configuredHeight.intValue();
         }

         int distance = 0;

         while (true) {
            Setting<Float> rangeSettingRef = nukerModule.rangeSetting;
            Float configuredRange = rangeSettingRef.getValue();
            double range = configuredRange.floatValue();
            if (!(distance <= Math.ceil(range))) {
               return positions;
            }

            for (int heightOffset = 0; heightOffset < height; heightOffset++) {
               LocalPlayer playerForPosition = ClientAccess.argusclientMinecraft.player;
               BlockPos position = playerForPosition.blockPosition();
               position = position.above(heightOffset);
               LocalPlayer playerForDirection = ClientAccess.argusclientMinecraft.player;
               Direction directionSnapshot = playerForDirection.getDirection();
               position = position.relative(directionSnapshot, distance);
               positions.add(position);
            }

            distance++;
         }
      }
   },
   QUEUE("Queue") {
      @Override
      public List collectPositions(NukerModule nukerModule) {
         NukerBlockQueue queueSnapshot = nukerModule.getBlockQueue();
         Setting<Boolean> extraBreakSettingRef = NukerShapeChoice.speedMineModule.doubleBreakSetting;
         Boolean extraBreakEnabled = extraBreakSettingRef.getValue();
         boolean extraBreakFlag = extraBreakEnabled;
         return queueSnapshot.getNextPositions(extraBreakFlag);
      }
   },
   CROSSHAIR("Crosshair") {
      @Override
      public List collectPositions(NukerModule nukerModule) {
         return ClientAccess.argusclientMinecraft.hitResult instanceof BlockHitResult hitResult
            ? new ArrayList<>(Collections.singletonList(hitResult.getBlockPos()))
            : Collections.emptyList();
      }
   };

   private static SpeedMineModule speedMineModule = ClientContext.getModule(SpeedMineModule.class);
   private final String displayName;

   @Override
   public String getName() {
      return this.displayName;
   }

   NukerShapeChoice(String displayName) {
      this.displayName = displayName;
   }

   public abstract List collectPositions(NukerModule var1);

   public static void bindModules(ModuleRegistry modules) {
      speedMineModule = (SpeedMineModule)modules.getModule(SpeedMineModule.class);
   }
}
