package dev.argus.client.modules.combat;

import dev.argus.client.ClientAccess;
import dev.argus.client.ClientContext;
import dev.argus.client.managers.ModuleRegistry;
import dev.argus.client.modules.ModuleCategory;
import dev.argus.client.modules.PlacementModule;
import dev.argus.client.modules.player.SpeedMineModule;
import dev.argus.client.settings.BooleanSetting;
import dev.argus.client.settings.NumberSetting;
import dev.argus.client.settings.Setting;
import dev.argus.client.utils.entity.PlayerStateUtils;
import dev.argus.client.utils.inventory.InventoryUtils;
import dev.argus.client.utils.world.BlockInteractionUtils;
import dev.argus.client.utils.world.WorldUtils;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ConcretePowderBlock;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.SandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class AutoDropModule extends PlacementModule {
   private static SpeedMineModule speedMineModule = ClientContext.getModule(SpeedMineModule.class);
   private static final MutableBlockPos supportProbePosition = new MutableBlockPos();
   protected final Setting<Float> targetRangeSetting = this.add(new NumberSetting<>("TargetRange", 4.0F, 1.0F, 6.0F).withUnit("m"));
   protected final Setting<Boolean> ignoreNakedsSetting = this.add(new BooleanSetting("IgnoreNakeds", false));
   protected final Setting<Boolean> blocksSetting = this.add(new BooleanSetting("Blocks", false).markAsGroup());
   protected final Setting<Boolean> anvilsSetting = this.add(new BooleanSetting("Anvils", true).dependsOn(this.blocksSetting));
   protected final Setting<Boolean> sandSetting = this.add(new BooleanSetting("Sand", false).dependsOn(this.blocksSetting));
   protected final Setting<Boolean> gravelSetting = this.add(new BooleanSetting("Gravel", false).dependsOn(this.blocksSetting));
   protected final Setting<Boolean> concreteSetting = this.add(new BooleanSetting("Concrete", false).dependsOn(this.blocksSetting));
   protected final Setting<Boolean> dragonEggSetting = this.add(new BooleanSetting("DragonEgg", false).dependsOn(this.blocksSetting));
   private final Setting<Boolean> completeSetting = this.add(
      new BooleanSetting("Complete", false).dependsOn(this.placementAutoDisableSetting), this.placementAutoDisableSetting
   );
   private boolean placingGravityBlock;

   @Override
   protected InventoryUtils.SwapSlot findPlacementSwapSlot() {
      if (this.placingGravityBlock) {
         Predicate<ItemStack> gravityStackPredicate = this::isAllowedGravityStack;
         return InventoryUtils.findSwapSlot(gravityStackPredicate, false);
      } else {
         return super.findPlacementSwapSlot();
      }
   }

   private DropTargetPlan selectDropTarget() {
      double nearestDistance = Double.MAX_VALUE;
      DropTargetPlan selectedTarget = null;

      for (AbstractClientPlayer candidate : ClientAccess.argusclientMinecraft.level.players()) {
         if (candidate.isAlive()
            && ClientAccess.argusclientMinecraft.player != candidate
            && !ClientContext.socialRegistry.isFriendPlayer(candidate)
            && (!this.ignoreNakedsSetting.getValue() || PlayerStateUtils.hasArmor(candidate))
            && !this.isPlayerInCobweb(candidate)) {
            BlockPos dropPosition = this.getDropPosition(candidate);
            Vec3 playerEyes = ClientAccess.argusclientMinecraft.player.getEyePosition();
            Vec3 candidatePosition = candidate.position();
            double distance = playerEyes.distanceTo(candidatePosition);
            Float targetRange = this.targetRangeSetting.getValue();
            if (!(distance > targetRange.floatValue())) {
               ClientLevel levelForBelowSnapshot = ClientAccess.argusclientMinecraft.level;
               BlockPos belowPosition = dropPosition.below();
               BlockState belowState = levelForBelowSnapshot.getBlockState(belowPosition);
               if (belowState.canBeReplaced() && BlockInteractionUtils.canPlaceAt(dropPosition, false) && !this.isInstantRebreakPosition(dropPosition)) {
                  DropTargetPlan candidatePlan;
                  label45: {
                     boolean directionCheck = BlockInteractionUtils.isStrictDirectionEnabled();
                     Direction placementDirection = BlockInteractionUtils.findSupportDirection(dropPosition, directionCheck);
                     if (placementDirection == null) {
                        Setting<Boolean> airPlaceSettingRef = this.placementAirPlaceSetting;
                        if (!airPlaceSettingRef.getValue()) {
                           Direction cageDirection = this.findCageDirection(dropPosition);
                           if (cageDirection == null) {
                              continue;
                           }

                           candidatePlan = new DropTargetPlan(candidate, DropPlacementMode.CAGE, cageDirection);
                           break label45;
                        }
                     }

                     candidatePlan = new DropTargetPlan(candidate, DropPlacementMode.HEAD, null);
                  }

                  if (distance < nearestDistance) {
                     selectedTarget = candidatePlan;
                     nearestDistance = distance;
                  }
               }
            }
         }
      }

      return selectedTarget;
   }

   public AutoDropModule() {
      super("AutoDrop", "Drops gravity blocks on your enemies.", ModuleCategory.COMBAT, "dropaura");
      this.unregister(this.placementChorusSetting);
   }

   private boolean isAllowedGravityStack(ItemStack stack) {
      if (!(stack.getItem() instanceof BlockItem blockItem)) {
         return false;
      } else {
         Block var4 = blockItem.getBlock();
         if (this.anvilsSetting.getValue() && var4 instanceof AnvilBlock) {
            return true;
         } else if (this.sandSetting.getValue() && var4 instanceof SandBlock) {
            return true;
         } else if (this.gravelSetting.getValue() && var4 == Blocks.GRAVEL) {
            return true;
         } else {
            return this.concreteSetting.getValue() && var4 instanceof ConcretePowderBlock
               ? true
               : this.dragonEggSetting.getValue() && var4 instanceof DragonEggBlock;
         }
      }
   }

   private boolean isPlayerInCobweb(Player player) {
      Iterator intersectingPositions = PlayerStateUtils.getOccupiedBlockPositions(player).iterator();

      while (intersectingPositions.hasNext()) {
         if (WorldUtils.getBlock((BlockPos)intersectingPositions.next()) == Blocks.COBWEB) {
            return true;
         }
      }

      return false;
   }

   private BlockPos getDropPosition(LivingEntity entity) {
      BlockPos entityPositionSnapshot = PlayerStateUtils.getRoundedBlockPosition(entity);
      double height = entity.getBbHeight();
      return entityPositionSnapshot.above((int)Math.ceil(height));
   }

   @Override
   public List getPlacementPositions() {
      Predicate<ItemStack> gravityStackPredicate = this::isAllowedGravityStack;
      int gravitySlot = InventoryUtils.findHotbarSlot(gravityStackPredicate);
      ArrayList positions = new ArrayList();
      this.placingGravityBlock = false;
      DropTargetPlan targetPlan = this.selectDropTarget();
      if (targetPlan == null) {
         return positions;
      }

      if (targetPlan.mode() == DropPlacementMode.CAGE && gravitySlot != -1) {
         for (int heightOffset = 0; heightOffset < 3; heightOffset++) {
            Player cagePlayerSnapshot = targetPlan.player();
            BlockPos cageOriginSnapshot = PlayerStateUtils.getRoundedBlockPosition(cagePlayerSnapshot);
            Direction cageDirection = targetPlan.cageDirection();
            BlockPos cageBaseSnapshot = cageOriginSnapshot.relative(cageDirection);
            positions.add(cageBaseSnapshot.above(heightOffset));
         }
      } else {
         Player headPlayerSnapshot = targetPlan.player();
         positions.add(this.getDropPosition(headPlayerSnapshot));
         this.placingGravityBlock = true;
      }

      if (this.completeSetting.getValue() && positions.isEmpty()) {
         this.disable();
      }

      return positions;
   }

   private boolean isInstantRebreakPosition(BlockPos position) {
      float miningDamage = speedMineModule.getMiningProgress();
      Setting<Float> damageSettingRef = speedMineModule.damageSetting;
      Float damageSettingValue = damageSettingRef.getValue();
      float damageFraction = miningDamage / damageSettingValue;
      if (speedMineModule.isToggled() && damageFraction >= 1.0F) {
         if (speedMineModule.isInstantRebreak() && position.equals(speedMineModule.getMiningPos())) {
            return true;
         }
      }

      return false;
   }

   private Direction findCageDirection(BlockPos dropPosition) {
      List placeableDirections = BlockInteractionUtils.getStrictDirections(dropPosition);
      int nearestSupportDepth = 1337;
      Direction selectedDirection = null;

      for (Direction direction : Direction.values()) {
         if (placeableDirections.contains(direction.getOpposite()) || !BlockInteractionUtils.isStrictDirectionEnabled()) {
            int supportDepth = 0;

            for (int depth = 0; depth < 3; depth++) {
               MutableBlockPos mutableProbeSnapshot = supportProbePosition;
               int probeX = dropPosition.getX() + direction.getStepX();
               int probeY = dropPosition.getY() - depth;
               int probeZ = dropPosition.getZ() + direction.getStepZ();
               mutableProbeSnapshot.set(probeX, probeY, probeZ);
               MutableBlockPos probePosition = supportProbePosition;
               BlockState probeState = ClientAccess.argusclientMinecraft.level.getBlockState(probePosition);
               if (!probeState.canBeReplaced()) {
                  supportDepth = depth;
                  break;
               }

               supportDepth = -1;
            }

            if (supportDepth != -1 && supportDepth < nearestSupportDepth) {
               nearestSupportDepth = supportDepth;
               selectedDirection = direction;
            }
         }
      }

      return selectedDirection;
   }

   public static void bindModules(ModuleRegistry modules) {
      speedMineModule = (SpeedMineModule)modules.getModule(SpeedMineModule.class);
   }
}
