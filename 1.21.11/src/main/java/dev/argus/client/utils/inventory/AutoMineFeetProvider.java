package dev.argus.client.utils.inventory;

import dev.argus.client.ClientAccess;
import dev.argus.client.ClientContext;
import dev.argus.client.managers.ModuleRegistry;
import dev.argus.client.managers.RotationManager;
import dev.argus.client.modules.combat.AutoCrystalModule;
import dev.argus.client.modules.combat.AutoMineMiningTracker;
import dev.argus.client.modules.combat.AutoMineModule;
import dev.argus.client.modules.player.SpeedMineModule;
import dev.argus.client.settings.Setting;
import dev.argus.client.utils.entity.PlayerStateUtils;
import dev.argus.client.utils.world.BlockInteractionUtils;
import dev.argus.client.utils.world.WorldUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.Direction.Plane;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AutoMineFeetProvider extends AutoMineProviderBase {
   private static AutoCrystalModule autoCrystal = ClientContext.getModule(AutoCrystalModule.class);
   private static SpeedMineModule speedMine = ClientContext.getModule(SpeedMineModule.class);
   private static final List<Vec3i> crystalOffsets;
   private final Set<BlockPos> protectedPositions = new HashSet<>();

   private boolean canPlaceCrystal(BlockPos position) {
      boolean requireUpperAir = autoCrystal.requireUpperAirSetting.getValue();
      Setting<Boolean> lowerHitBoxSetting = autoCrystal.lowerHitBoxSetting;
      boolean lowerHitBox = lowerHitBoxSetting.getValue();
      boolean checkRotation = !RotationManager.isMovementSyncEnabled();
      return BlockInteractionUtils.canPlaceCrystal(position, requireUpperAir, true, false, false, lowerHitBox, checkRotation);
   }

   private boolean isProtectedSelfPosition(BlockPos position) {
      Setting<Boolean> safeSetting = this.autoMine.safeSetting;
      if (safeSetting.getValue()) {
         Iterator<BlockPos> protectedIterator = this.protectedPositions.iterator();

         while (protectedIterator.hasNext()) {
            if (protectedIterator.next().equals(position)) {
               return true;
            }
         }
      }

      AABB boundsSnapshot = ClientAccess.argusclientMinecraft.player.getBoundingBox();
      double playerY = ClientAccess.argusclientMinecraft.player.getY();
      AABB insetBounds = boundsSnapshot.setMaxY(playerY).deflate(0.0, -1.0, 0.0);
      List<BlockPos> occupiedPositions = BlockPos.betweenClosedStream(insetBounds).map(BlockPos::immutable).filter(occupiedPosition -> {
         ClientLevel level = ClientAccess.argusclientMinecraft.level;
         return level.getBlockState(occupiedPosition).getBlock().getExplosionResistance() >= 600.0F;
      }).toList();
      return occupiedPositions.size() <= 2 && occupiedPositions.contains(position);
   }

   private boolean hasAdjacentCrystalBase(BlockPos position) {
      for (Vec3i offset : crystalOffsets) {
         BlockPos supportPosition = position.offset(offset).below();
         if (this.canPlaceCrystal(supportPosition)) {
            return true;
         }
      }

      return false;
   }

   private boolean intersectsBedrock(Player target) {
      AABB targetBounds = target.getBoundingBox();
      double targetY = target.getY();
      AABB footBounds = targetBounds.setMaxY(targetY);
      return BlockPos.betweenClosedStream(footBounds).map(BlockPos::immutable).allMatch(position -> {
         ClientLevel level = ClientAccess.argusclientMinecraft.level;
         BlockState state = level.getBlockState(position);
         Block bedrock = Blocks.BEDROCK;
         return state.is(bedrock);
      });
   }

   public AutoMineFeetProvider(AutoMineModule module) {
      super(module);
   }

   @Override
   protected boolean canMinePosition(BlockPos position) {
      return this.isProtectedSelfPosition(position) ? false : WorldUtils.getBlock(position) != Blocks.COBWEB && super.canMinePosition(position);
   }

   private void refreshProtectedPositions() {
      this.protectedPositions.clear();
      boolean movementMode = RotationManager.isMovementSyncEnabled();
      LocalPlayer playerSnapshot = ClientAccess.argusclientMinecraft.player;

      for (BlockPos occupiedPosition : PlayerStateUtils.getSurroundPositions(playerSnapshot)) {
         if (!PlayerStateUtils.isLocalLowProfile() && !movementMode) {
            this.protectedPositions.add(occupiedPosition.above());
         }

         this.protectedPositions.add(occupiedPosition);
      }

      if (!movementMode) {
         double playerHeight = ClientAccess.argusclientMinecraft.player.getBoundingBox().getYsize();
         int upperOffset = (int)Math.ceil(playerHeight);
         LocalPlayer upperPlayer = ClientAccess.argusclientMinecraft.player;
         Stream<BlockPos> upperPositions = PlayerStateUtils.getOccupiedBlockPositions(upperPlayer).stream().map(position -> position.above(upperOffset));
         Set<BlockPos> protectedConsumer = this.protectedPositions;
         upperPositions.forEach(protectedConsumer::add);
      }
   }

   private Direction[] getPerpendicularDirections(Direction direction) {
      return switch (direction) {
         case NORTH, SOUTH -> {
            Direction[] perpendicularDirections = new Direction[]{Direction.EAST, Direction.WEST};
            yield perpendicularDirections;
         }
         case EAST, WEST -> {
            Direction[] perpendicularDirections = new Direction[]{Direction.NORTH, Direction.SOUTH};
            yield perpendicularDirections;
         }
         default -> new Direction[0];
      };
   }

   @Override
   public void collectCandidates(AutoMineCandidates candidates) {
      this.refreshProtectedPositions();
      Player target = this.autoMine.targetSelector.selectTarget();
      Setting<Boolean> fishSetting = this.autoMine.fishSetting;
      Boolean fishEnabled = fishSetting.getValue();
      boolean fishPriority = false;
      if (fishEnabled && candidates.getPriority() == 400) {
         fishPriority = candidates.getSecondaryPosition() != null;
      }

      if (target != null) {
         if (!this.hasNoCollisionBelow(target)) {
            if (!this.intersectsBedrock(target) || fishPriority) {
               Vec3 targetCoordinates = target.position();
               BlockPos targetPosition = BlockPos.containing(targetCoordinates);
               ArrayList<BlockPos> footPositions = new ArrayList<>(PlayerStateUtils.getSurroundPositions(target));
               ClientLevel level = ClientAccess.argusclientMinecraft.level;
               Block baseBlock = level.getBlockState(targetPosition).getBlock();
               boolean suppressPreviousSelection = baseBlock.getExplosionResistance() >= 600.0F && baseBlock != Blocks.AIR;
               if (this.autoMine.miningTracker.hasRecentDelayElapsed(500L)) {
                  AutoMineMiningTracker currentTrackerSnapshot = this.autoMine.miningTracker;
                  if (targetPosition.equals(currentTrackerSnapshot.getCurrentPosition())) {
                     suppressPreviousSelection = false;
                  }
               } else {
                  suppressPreviousSelection = false;
               }

               AutoMineFeetProvider.FeetMiningPair selectedPair = this.selectFeetPair(target, footPositions, suppressPreviousSelection, fishPriority);
               if (selectedPair.primaryPosition != null) {
                  Consumer<AutoMineCandidates> updateSelectedPair = output -> {
                     if (output.getSecondaryPosition() == null) {
                        BlockPos secondarySnapshot = selectedPair.secondaryPosition;
                        output.setSecondaryPosition(secondarySnapshot);
                     }

                     BlockPos primarySnapshot = selectedPair.primaryPosition;
                     output.setPrimaryPosition(primarySnapshot);
                  };
                  candidates.offer(600, updateSelectedPair);
               }
            }
         }
      }
   }

   private AutoMineFeetProvider.FeetMiningPair selectFeetPair(Player target, List<BlockPos> footPositions, boolean suppressPreviousBreak, boolean fishPriority) {
      boolean lowerBedrockCandidates = footPositions.stream().allMatch(position -> WorldUtils.getBlock(position) == Blocks.BEDROCK) && fishPriority;
      BlockPos selfPosition = PlayerStateUtils.getLocalRoundedBlockPosition();
      LocalPlayer selfPlayer = ClientAccess.argusclientMinecraft.player;
      int selfOccupiedCount = PlayerStateUtils.getOccupiedBlockPositions(selfPlayer).size();
      BlockPos primaryPosition = null;
      BlockPos secondaryPosition = null;
      boolean primaryIsCurrentMining = false;
      footPositions.sort(Comparator.comparing(position -> {
         AABB boundsSnapshot = ClientAccess.argusclientMinecraft.player.getBoundingBox();
         AABB blockBounds = new AABB(position);
         if (boundsSnapshot.intersects(blockBounds)) {
            return 9999.0;
         }

         LocalPlayer occupiedPlayerSnapshot = ClientAccess.argusclientMinecraft.player;
         if (PlayerStateUtils.getSurroundPositions(occupiedPlayerSnapshot).contains(position)) {
            return 999.0;
         }

         boolean hasCrystalBase = false;

         for (Vec3i offset : crystalOffsets) {
            if (offset.getY() == 0) {
               BlockPos adjacentPositionx = position.offset(offset);
               BlockPos supportSnapshot = adjacentPositionx.below();
               if (this.canPlaceCrystal(supportSnapshot)) {
                  hasCrystalBase = true;
                  break;
               }
            }
         }

         Vec3 distancePosition = ClientAccess.argusclientMinecraft.player.position();
         double distanceScore = position.distToCenterSqr(distancePosition);
         if (!hasCrystalBase) {
            double maximumRangeDouble = speedMine.rangeSetting.getMaximumValue().floatValue();
            distanceScore += Math.pow(maximumRangeDouble, 2.0);
         }

         return -distanceScore;
      }));

      for (BlockPos position : footPositions) {
         if (this.isAlreadyMining(position) || !selfPosition.below().equals(position) || selfOccupiedCount != 1) {
            if (lowerBedrockCandidates) {
               position = position.below();
            }

            long recentDelayMillis = RotationManager.isMovementSyncEnabled() ? 500L : 1000L;
            boolean retainPreviousBreak = false;
            if (WorldUtils.isAir(position)) {
               SpeedMineModule previousSnapshot = speedMine;
               retainPreviousBreak = position.equals(previousSnapshot.getMiningPos());
            }

            boolean instantRebreak = speedMine.isInstantRebreak();
            boolean fastRebreak = false;

            if (suppressPreviousBreak) {
               retainPreviousBreak = false;
            }

            if (speedMine.hasCrystalDelayElapsed(recentDelayMillis) && instantRebreak) {
               Setting<Boolean> asyncSetting = speedMine.asyncSetting;
               Boolean async = asyncSetting.getValue();
               if (!async) {
                  retainPreviousBreak = false;
               }
            }

            if ((this.hasAdjacentCrystalBase(position) || fishPriority || instantRebreak || fastRebreak)
               && (this.canMinePosition(position) || retainPreviousBreak)) {
               if (this.isAlreadyMining(position)) {
                  primaryPosition = position;
                  primaryIsCurrentMining = true;
               } else if (primaryIsCurrentMining) {
                  secondaryPosition = position;
               } else {
                  secondaryPosition = primaryPosition;
                  primaryPosition = position;
               }
            }
         }
      }

      if (primaryPosition != null) {
         Setting<Boolean> cornersSetting = this.autoMine.cornersSetting;
         if (cornersSetting.getValue() && this.autoMine.isExtraBreakEnabled()) {
            BlockPos adjacentPosition = this.findAdjacentCandidate(target, primaryPosition);
            if (adjacentPosition != null) {
               secondaryPosition = adjacentPosition;
            }
         }
      }

      return new AutoMineFeetProvider.FeetMiningPair(primaryPosition, secondaryPosition);
   }

   private BlockPos findAdjacentCandidate(Player target, BlockPos referencePosition) {
      Vec3 targetCoordinates = target.position();
      BlockPos targetPosition = BlockPos.containing(targetCoordinates);
      Direction matchedDirection = null;

      for (Direction direction : Plane.HORIZONTAL) {
         if (targetPosition.relative(direction).equals(referencePosition)) {
            matchedDirection = direction;
            break;
         }
      }

      if (matchedDirection == null) {
         return null;
      }

      for (Direction perpendicularDirection : this.getPerpendicularDirections(matchedDirection)) {
         BlockPos adjacentPosition = referencePosition.relative(perpendicularDirection);
         if (WorldUtils.isAir(adjacentPosition)) {
            BlockPos supportPosition = adjacentPosition.below();
            if (WorldUtils.isBedrockOrObsidian(supportPosition)) {
               return null;
            }
         }

         if (this.canMinePosition(adjacentPosition)) {
            BlockPos lowerPosition = adjacentPosition.below();
            if (WorldUtils.isBedrockOrObsidian(lowerPosition)) {
               return adjacentPosition;
            }
         }
      }

      return null;
   }

   @Override
   public boolean isEnabled() {
      return this.autoMine.feetSetting.getValue();
   }

   public static void bindModules(ModuleRegistry registry) {
      autoCrystal = (AutoCrystalModule)registry.getModule(AutoCrystalModule.class);
      speedMine = (SpeedMineModule)registry.getModule(SpeedMineModule.class);
   }

   static {
      Vec3i eastOffset = new Vec3i(1, 0, 0);
      Vec3i southOffset = new Vec3i(0, 0, 1);
      Vec3i upOffset = new Vec3i(0, 1, 0);
      Vec3i northOffset = new Vec3i(0, 0, -1);
      Vec3i westOffset = new Vec3i(-1, 0, 0);
      crystalOffsets = List.of(eastOffset, southOffset, upOffset, northOffset, westOffset);
   }

   private record FeetMiningPair(BlockPos primaryPosition, BlockPos secondaryPosition) {
   }
}
