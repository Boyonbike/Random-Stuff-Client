package dev.argus.client.modules.player;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.argus.client.ClientAccess;
import dev.argus.client.ClientContext;
import dev.argus.client.events.EventHandler;
import dev.argus.client.events.MultiPlayerGameModeStartDestroyBlockEvent;
import dev.argus.client.events.RotationRequestEvent;
import dev.argus.client.events.WorldRenderEvent;
import dev.argus.client.managers.ModuleRegistry;
import dev.argus.client.managers.RotationManager;
import dev.argus.client.managers.SwingInteractionManager;
import dev.argus.client.modules.Module;
import dev.argus.client.modules.ModuleCategory;
import dev.argus.client.modules.client.AntiCheatModule;
import dev.argus.client.render.ColorUtils;
import dev.argus.client.render.FontRenderer;
import dev.argus.client.render.WorldBoxRenderer;
import dev.argus.client.settings.BooleanSetting;
import dev.argus.client.settings.ColorSetting;
import dev.argus.client.settings.EnumSetting;
import dev.argus.client.settings.NumberSetting;
import dev.argus.client.settings.RegistrySetSetting;
import dev.argus.client.settings.Setting;
import dev.argus.client.settings.SettingLabelCondition;
import dev.argus.client.settings.options.NukerShapeChoice;
import dev.argus.client.settings.options.NukerSortChoice;
import dev.argus.client.utils.ElapsedTimer;
import dev.argus.client.utils.combat.ExtraBreakTarget;
import dev.argus.client.utils.entity.SwingAction;
import dev.argus.client.utils.inventory.InventoryUtils;
import dev.argus.client.utils.network.PacketSender;
import dev.argus.client.utils.world.BlockInteractionUtils;
import dev.argus.client.utils.world.NukerBlockQueue;
import dev.argus.client.utils.world.WorldUtils;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class NukerModule extends Module {
   private static AntiCheatModule antiCheatModule = ClientContext.getModule(AntiCheatModule.class);
   private static ItemSaverModule itemSaverModule = ClientContext.getModule(ItemSaverModule.class);
   private static SpeedMineModule speedMineModule = ClientContext.getModule(SpeedMineModule.class);
   final Setting<Set<Block>> blocksSetting;
   final Setting<NukerModeChoice> modeSetting;
   final Setting<NukerShapeChoice> shapeSetting;
   private final Setting<NukerSortChoice> sortSetting;
   private final Setting<Integer> bPTSetting;
   private final Setting<Integer> delaySetting;
   public final Setting<Float> rangeSetting;
   private final Setting<Float> wallRangeSetting;
   public final Setting<Integer> heightSetting;
   private final Setting<Boolean> strictDirectionSetting;
   private final Setting<Boolean> rotateSetting;
   private final Setting<Boolean> creativeSetting;
   private final Setting<Boolean> flattenSetting;
   private final Setting<Boolean> baritoneAreaSetting;
   final Setting<Boolean> playerProximitySetting;
   private final Setting<Color> colorSetting;
   private final NukerBlockQueue blockQueue;
   private final List<NukerModule.RecentBreak> recentBreaks;
   private final ElapsedTimer delayTimer;
   private BlockPos lastMinedPos;
   private int blocksMinedThisTick;
   private Block clickedBlock;
   private boolean internalMining;
   private boolean instantBreak;

   public NukerBlockQueue getBlockQueue() {
      return this.blockQueue;
   }

   private boolean shouldUseInstantBlockFilter() {
      SpeedMineModule speedMineForToggle = speedMineModule;
      if (!speedMineForToggle.isToggled()) {
         return false;
      }

      SpeedMineModule speedMineSnapshot = speedMineModule;
      BlockPos primaryTarget = speedMineSnapshot.getMiningPos();
      if (primaryTarget == null) {
         speedMineSnapshot = speedMineModule;
         primaryTarget = speedMineSnapshot.getRebreakPos();
      }

      BlockPos extraTarget = null;
      speedMineSnapshot = speedMineModule;
      if (speedMineSnapshot.getExtraBreakTarget() != null) {
         speedMineSnapshot = speedMineModule;
         extraTarget = speedMineSnapshot.getExtraBreakTarget().getPosition();
      }

      return !WorldUtils.canMineBlock(primaryTarget) && !WorldUtils.canMineBlock(extraTarget)
         ? false
         : primaryTarget == null || !primaryTarget.equals(extraTarget);
   }

   private int getMiningBatchLimit() {
      if (speedMineModule.isToggled()) {
         Boolean extraBreakEnabled = speedMineModule.doubleBreakSetting.getValue();
         int batchLimit;
         if (extraBreakEnabled) {
            batchLimit = 2;
         } else {
            batchLimit = 1;
         }

         return batchLimit;
      } else {
         return this.bPTSetting.getValue();
      }
   }

   private boolean isZeroHardnessBlock(BlockPos blockPos) {
      BlockState blockState = ClientAccess.argusclientMinecraft.level.getBlockState(blockPos);
      ClientLevel blockLevel = ClientAccess.argusclientMinecraft.level;
      return blockState.getDestroySpeed(blockLevel, blockPos) == 0.0F;
   }

   public Block getClickedBlock() {
      return this.clickedBlock;
   }

   @Override
   public String getInfo() {
      Setting<NukerModeChoice> modeSettingRef = this.modeSetting;
      return FontRenderer.capitalize(modeSettingRef.getValue());
   }

   @Override
   public void onDisable() {
      ClientAccess.argusclientMinecraft.options.keyAttack.setDown(false);
      this.clickedBlock = null;
   }

   @EventHandler
   private void onTick(RotationRequestEvent event) {
      if (!this.isWorldUnavailable()) {
         this.recentBreaks.removeIf(recentBreak -> System.currentTimeMillis() > recentBreak.expiresAtMillis());
         this.instantBreak = false;
         this.lastMinedPos = null;
         this.blocksMinedThisTick = 0;
         ElapsedTimer delayTimerWork = this.delayTimer;
         long delayMillis = this.delaySetting.getValue().intValue();
         if (delayTimerWork.hasElapsed(delayMillis)) {
            if (speedMineModule.isToggled()) {
               SpeedMineModule speedMineForDelaySnapshot = speedMineModule;
               long speedMineDelayCapture = this.delaySetting.getValue().intValue();
               if (!speedMineForDelaySnapshot.hasCrystalDelayElapsed(speedMineDelayCapture)) {
                  return;
               }
            }

            List<BlockPos> candidates = this.shapeSetting.getValue().collectPositions(this);
            this.blockQueue.pruneInvalidPositions();
            if (this.shapeSetting.getValue() != NukerShapeChoice.QUEUE) {
               candidates.sort(Comparator.comparingDouble(sortPos -> this.sortSetting.getValue().getSortKey(sortPos)));
            }

            boolean restrictToInstantBlocks = this.shouldUseInstantBlockFilter();

            for (BlockPos candidatePos : candidates) {
               if (this.flattenSetting.getValue()) {
                  double candidateY = candidatePos.getY();
                  LocalPlayer playerForHeight = ClientAccess.argusclientMinecraft.player;
                  if (candidateY < playerForHeight.getY()) {
                     continue;
                  }
               }

               if ((!restrictToInstantBlocks || this.isZeroHardnessBlock(candidatePos)) && this.canMineBlock(candidatePos)) {
                  if (antiCheatModule.isPacketLimitReached()) {
                     break;
                  }

                  this.internalMining = true;
                  this.mineBlock(candidatePos);
                  this.internalMining = false;
                  delayTimerWork = this.delayTimer;
                  delayTimerWork.reset();
                  if (this.blocksMinedThisTick >= this.getMiningBatchLimit()) {
                     return;
                  }

                  if (this.blocksMinedThisTick > 0 && !this.instantBreak) {
                     Setting<Boolean> creativeSetting = this.creativeSetting;
                     Boolean creativeEnabled = creativeSetting.getValue();
                     if (!creativeEnabled) {
                        SpeedMineModule speedMine = speedMineModule;
                        if (!speedMine.isToggled()) {
                           return;
                        }
                     }
                  }
               }
            }
         }
      }
   }

   @EventHandler
   private void onRenderQueue(WorldRenderEvent.Main event) {
      if (this.shapeSetting.getValue() == NukerShapeChoice.QUEUE) {
         ExtraBreakTarget extraTask = speedMineModule.getExtraBreakTarget();
         NukerBlockQueue queueSnapshot = this.blockQueue;

         for (BlockPos queuedPos : queueSnapshot.getPositions()) {
            if (speedMineModule.isToggled()) {
               SpeedMineModule speedMineForPrimarySnapshot = speedMineModule;
               if (queuedPos.equals(speedMineForPrimarySnapshot.getMiningPos()) || extraTask != null && queuedPos.equals(extraTask.getPosition())) {
                  continue;
               }
            }

            Color queueColor = this.colorSetting.getValue();
            if (queuedPos.equals(this.lastMinedPos)) {
               Color selectedColor = ColorUtils.withAlpha(queueColor, 1.0F);
               queueColor = ColorUtils.pulse(selectedColor, queueColor, 1000.0, 0.0);
            }

            PoseStack poseStack = event.getPoseStack();
            WorldBoxRenderer.outlineBox(poseStack, queuedPos, queueColor, 1.0F);
         }
      }
   }

   @EventHandler(priority = 200)
   private void onStartDestroyBlock(MultiPlayerGameModeStartDestroyBlockEvent event) {
      if (!event.isCancelled() && !this.internalMining) {
         if (this.shapeSetting.getValue() == NukerShapeChoice.QUEUE) {
            NukerBlockQueue queueSnapshot = this.blockQueue;
            BlockPos clickedPos = event.getPos();
            queueSnapshot.enqueue(clickedPos);
            event.cancel();
         } else if (this.modeSetting.getValue() == NukerModeChoice.CLICK) {
            BlockPos clickedPosSnapshot = event.getPos();
            if (WorldUtils.canMineBlock(clickedPosSnapshot)) {
               BlockPos clickedPosForBlock = event.getPos();
               this.clickedBlock = WorldUtils.getBlock(clickedPosForBlock);
            }
         }
      }
   }

   private void mineBlock(BlockPos blockPos) {
      if (WorldUtils.canMineBlock(blockPos)) {
         Direction miningDirection = Direction.UP;
         if (this.strictDirectionSetting.getValue()) {
            List<Direction> availableFaces = BlockInteractionUtils.getStrictDirections(blockPos);
            if (availableFaces.isEmpty()) {
               return;
            }

            miningDirection = availableFaces.getFirst();
         }

         if (this.rotateSetting.getValue()) {
            RotationManager rotationManagerSnapshot = ClientContext.rotationManager;
            Vec3 centerSnapshot = Vec3.atCenterOf(blockPos);
            float[] rotations = RotationManager.getRotationsToFace(centerSnapshot, miningDirection);
            rotationManagerSnapshot.requestRotation(rotations, 5);
         }

         this.blocksMinedThisTick++;
         if (speedMineModule.isToggled()) {
            SpeedMineModule.automatedMiningStart = true;
            ClientAccess.argusclientMinecraft.gameMode.startDestroyBlock(blockPos, miningDirection);
            SpeedMineModule.automatedMiningStart = false;
            this.lastMinedPos = blockPos;
         } else {
            int selectedSlot = ClientAccess.argusclientMinecraft.player.getInventory().getSelectedSlot();
            int bestSlot = -1;
            double bestBreakProgress = -1.0;
            BlockState blockState = ClientAccess.argusclientMinecraft.level.getBlockState(blockPos);

            for (int hotbarSlot = 0; hotbarSlot < 9; hotbarSlot++) {
               ItemStack stack = ClientAccess.argusclientMinecraft.player.getInventory().getItem(hotbarSlot);
               boolean onGround = ClientAccess.argusclientMinecraft.player.onGround();
               double breakProgress = WorldUtils.getBreakProgress(stack, blockState, onGround);
               if ((!itemSaverModule.isToggled() || ItemSaverModule.isSafeToUse(stack))
                  && (breakProgress > bestBreakProgress || breakProgress == bestBreakProgress && selectedSlot == hotbarSlot)) {
                  bestBreakProgress = breakProgress;
                  bestSlot = hotbarSlot;
               }
            }

            this.lastMinedPos = blockPos;
            LocalPlayer playerForCreative = ClientAccess.argusclientMinecraft.player;
            boolean survivalPlayer = !playerForCreative.isCreative();
            if (this.creativeSetting.getValue()) {
               if (antiCheatModule.is2b2tEnabled() && survivalPlayer) {
                  InventoryUtils.selectHotbarSlotForPlacement(bestSlot);
               }

               BlockPos startPos = this.lastMinedPos;
               Action startAction = Action.START_DESTROY_BLOCK;
               PacketSender.sendPlayerAction(startAction, startPos, miningDirection);
               if (bestBreakProgress < 1.0) {
                  BlockPos stopPos = this.lastMinedPos;
                  Action stopAction = Action.STOP_DESTROY_BLOCK;
                  PacketSender.sendPlayerAction(stopAction, stopPos, miningDirection);
               }

               SwingInteractionManager swingManager = ClientContext.swingManager;
               InteractionHand swingHand = InteractionHand.MAIN_HAND;
               SwingAction swingType = SwingAction.INTERACT;
               swingManager.swingPacket(swingHand, swingType);
               List<NukerModule.RecentBreak> recentBreaksSnapshot = this.recentBreaks;
               recentBreaksSnapshot.add(new NukerModule.RecentBreak(this.lastMinedPos, System.currentTimeMillis() + 1000L));
            } else {
               if (survivalPlayer) {
                  InventoryUtils.selectHotbarSlotForPlacement(bestSlot);
               }

               ItemStack selectedTool = ClientAccess.argusclientMinecraft.player.getInventory().getItem(bestSlot);
               BlockState selectedState = ClientAccess.argusclientMinecraft.level.getBlockState(blockPos);
               boolean selectedOnGround = ClientAccess.argusclientMinecraft.player.onGround();
               this.instantBreak = WorldUtils.getBreakProgress(selectedTool, selectedState, selectedOnGround) >= 1.0;
               if (this.instantBreak && RotationManager.isMovementSyncEnabled() && RotationManager.is2b2tMode()) {
                  Action startAction = Action.START_DESTROY_BLOCK;
                  PacketSender.sendPlayerAction(startAction, blockPos, miningDirection);
                  List<NukerModule.RecentBreak> recentBreaksSnapshot = this.recentBreaks;
                  recentBreaksSnapshot.add(new NukerModule.RecentBreak(this.lastMinedPos, System.currentTimeMillis() + 300L));
               } else {
                  ClientAccess.argusclientMinecraft.gameMode.continueDestroyBlock(blockPos, miningDirection);
               }

               LocalPlayer playerForSwing = ClientAccess.argusclientMinecraft.player;
               InteractionHand mainHand = InteractionHand.MAIN_HAND;
               playerForSwing.swing(mainHand, playerForSwing.getItemInHand(mainHand).getAttackAnimation(), false);
            }
         }
      }
   }

   public boolean canMineBlock(BlockPos blockPos) {
      if (speedMineModule.isToggled() && speedMineModule.getExtraBreakTracker().isTracked(blockPos)) {
         return false;
      }

      if (this.recentBreaks.stream().anyMatch(recentBreak -> recentBreak.blockPos().equals(blockPos))) {
         return false;
      }

      Vec3 eyePosition = ClientAccess.argusclientMinecraft.player.getEyePosition();
      double squaredDistance = blockPos.distToCenterSqr(eyePosition);
      double distanceToEye = Math.sqrt(squaredDistance);
      Float selectedRangeValue;
      if (WorldUtils.canSeeBlock(blockPos)) {
         selectedRangeValue = this.rangeSetting.getValue();
      } else {
         selectedRangeValue = this.wallRangeSetting.getValue();
      }

      double allowedRange = selectedRangeValue.floatValue();
      if (speedMineModule.isToggled()) {
         double speedMineRangeLimit = speedMineModule.rangeSetting.getValue().floatValue();
         allowedRange = Math.min(allowedRange, speedMineRangeLimit);
      }

      if (distanceToEye > allowedRange) {
         return false;
      }

      if (WorldUtils.getBlock(blockPos) instanceof ShulkerBoxBlock && ClientContext.worldStateManager.hasKnownPlacement(blockPos)) {
         return false;
      }

      if (this.baritoneAreaSetting.getValue() && ClientContext.baritoneBridge.isAvailable() && !ClientContext.baritoneBridge.isInSelection(blockPos)) {
         return false;
      }

      NukerModeChoice modeSnapshot = this.modeSetting.getValue();
      Block blockSnapshot = BlockInteractionUtils.getBlock(blockPos);
      return modeSnapshot.matchesBlock(blockPos, blockSnapshot, this);
   }

   public static void bindReferencedModules(ModuleRegistry moduleRegistry) {
      antiCheatModule = (AntiCheatModule)moduleRegistry.getModule(AntiCheatModule.class);
      itemSaverModule = (ItemSaverModule)moduleRegistry.getModule(ItemSaverModule.class);
      speedMineModule = (SpeedMineModule)moduleRegistry.getModule(SpeedMineModule.class);
      NukerShapeChoice.bindModules(moduleRegistry);
      NukerBlockQueue.bindModules(moduleRegistry);
   }

   public NukerModule() {
      super("Nuker", "Breaks blocks nearby.", ModuleCategory.PLAYER);
      DefaultedRegistry blockRegistry = BuiltInRegistries.BLOCK;
      Block[] defaultBlocks = new Block[0];
      this.blocksSetting = this.add(new RegistrySetSetting<>("Blocks", blockRegistry, defaultBlocks));
      NukerModeChoice defaultMode = NukerModeChoice.SHULKERS;
      this.modeSetting = this.add(new EnumSetting<>("Mode", defaultMode));
      NukerShapeChoice defaultShape = NukerShapeChoice.SPHERE;
      this.shapeSetting = this.add(new EnumSetting<>("Shape", defaultShape));
      NukerSortChoice defaultSort = NukerSortChoice.CLOSEST;
      this.sortSetting = this.add(new EnumSetting<>("Sort", defaultSort));
      this.bPTSetting = this.add(new NumberSetting<>("BPT", 1, 1, 10));
      this.delaySetting = this.add(new NumberSetting<>("Delay", 50, 0, 500).withUnit("ms"));
      this.rangeSetting = this.add(new NumberSetting<>("Range", 4.5F, 0.0F, 6.0F).withUnit("m"));
      this.wallRangeSetting = this.add(new NumberSetting<>("WallRange", 4.5F, 0.0F, 6.0F).withUnit("m"));
      Predicate<Object> heightVisibility = ignoredValue -> this.shapeSetting.getValue() == NukerShapeChoice.TUNNEL;
      NumberSetting<Integer> heightEditor = new NumberSetting<>("Height", 0, 0, 5, heightVisibility);
      SettingLabelCondition heightAutoMode = SettingLabelCondition.MIN;
      this.heightSetting = this.add(heightEditor.setBoundaryLabel("Auto", heightAutoMode));
      this.strictDirectionSetting = this.add(new BooleanSetting("StrictDirection", false));
      this.rotateSetting = this.add(new BooleanSetting("Rotate", false));
      this.creativeSetting = this.add(new BooleanSetting("Creative", false));
      this.flattenSetting = this.add(new BooleanSetting("Flatten", false));
      this.baritoneAreaSetting = this.add(new BooleanSetting("BaritoneArea", false));
      Predicate<Object> proximityVisibility = ignoredValue -> this.modeSetting.getValue() == NukerModeChoice.SHULKERS;
      this.playerProximitySetting = this.add(new BooleanSetting("PlayerProximity", false, proximityVisibility));
      Predicate<Object> colorVisibility = ignoredValue -> this.shapeSetting.getValue() == NukerShapeChoice.QUEUE;
      Color defaultColor = new Color(1019058687, true);
      this.colorSetting = this.add(new ColorSetting("Color", defaultColor, colorVisibility));
      this.blockQueue = new NukerBlockQueue(this);
      this.recentBreaks = new ArrayList<>();
      this.delayTimer = new ElapsedTimer();
      this.lastMinedPos = null;
      Setting<?> visibilitySetting = this.sortSetting;
      Predicate<Object> visibilityPredicate = ignored -> {
         Setting<NukerShapeChoice> visibilitySnapshot = this.shapeSetting;
         return visibilitySnapshot.getValue() != NukerShapeChoice.QUEUE;
      };
      visibilitySetting.addVisibilityPredicate(visibilityPredicate);
      visibilitySetting = this.bPTSetting;
      Predicate<Integer> batchLimitVisibility = ignored -> {
         SpeedMineModule visibilitySnapshot = speedMineModule;
         return !visibilitySnapshot.isToggled();
      };
      this.bPTSetting.addVisibilityPredicate(batchLimitVisibility);
      this.baritoneAreaSetting.hideWithoutBaritone();
   }

   private record RecentBreak(BlockPos blockPos, long expiresAtMillis) {
   }
}
