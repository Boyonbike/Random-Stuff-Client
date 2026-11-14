package dev.argus.client.modules.combat;

import dev.argus.client.ClientAccess;
import dev.argus.client.ClientContext;
import dev.argus.client.events.EventHandler;
import dev.argus.client.events.RotationRequestEvent;
import dev.argus.client.managers.ModuleRegistry;
import dev.argus.client.managers.RotationManager;
import dev.argus.client.modules.Module;
import dev.argus.client.modules.ModuleCategory;
import dev.argus.client.modules.client.AntiCheatModule;
import dev.argus.client.modules.exploit.MultiTaskModule;
import dev.argus.client.modules.player.SpeedMineModule;
import dev.argus.client.settings.BooleanSetting;
import dev.argus.client.settings.NumberSetting;
import dev.argus.client.settings.Setting;
import dev.argus.client.settings.options.BlockSampleMode;
import dev.argus.client.utils.ElapsedTimer;
import dev.argus.client.utils.inventory.AutoMineCandidates;
import dev.argus.client.utils.inventory.AutoMineFeetProvider;
import dev.argus.client.utils.inventory.AutoMineHeadProvider;
import dev.argus.client.utils.inventory.AutoMineProvider;
import dev.argus.client.utils.inventory.AutoMineProviderBase;
import dev.argus.client.utils.inventory.AutoMineTerrainProvider;
import dev.argus.client.utils.world.BlockInteractionUtils;
import dev.argus.client.utils.world.WorldUtils;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class AutoMineModule extends Module {
   private static SpeedMineModule speedMine = ClientContext.getModule(SpeedMineModule.class);
   private static AntiCheatModule antiCheat = ClientContext.getModule(AntiCheatModule.class);
   private static MultiTaskModule multiTask = ClientContext.getModule(MultiTaskModule.class);
   public final Setting<Boolean> strictDirectionSetting = this.add(new BooleanSetting("StrictDirection", false));
   public final Setting<Boolean> raytraceSetting = this.add(new BooleanSetting("Raytrace", false));
   public final Setting<Boolean> rotateSetting = this.add(new BooleanSetting("Rotate", false));
   public final Setting<Boolean> ignoreNakedSetting = this.add(new BooleanSetting("IgnoreNaked", false));
   public final Setting<Boolean> assistantSetting = this.add(new BooleanSetting("Assistant", false));
   private final Setting<Boolean> targetsSetting = this.add(new BooleanSetting("Targets", false).markAsGroup().markVisibilityDependency());
   public final Setting<Boolean> burrowSetting = this.add(new BooleanSetting("Burrow", true).dependsOn(this.targetsSetting));
   public final Setting<Boolean> fishSetting = this.add(new BooleanSetting("Fish", false).dependsOn(this.targetsSetting));
   public final Setting<Boolean> terrainSetting = this.add(new BooleanSetting("Terrain", false).dependsOn(this.targetsSetting));
   public final Setting<Boolean> enderChestsSetting = this.add(
      new BooleanSetting("EnderChests", true).markVisibilityDependency().dependsOn(this.targetsSetting)
   );
   public final Setting<Float> rangeSetting = this.add(new NumberSetting<>("Range", 2.5F, 0.1F, 6.0F).dependsOn(this.targetsSetting, this.enderChestsSetting));
   public final Setting<Boolean> headSetting = this.add(new BooleanSetting("Head", false).markVisibilityDependency().dependsOn(this.targetsSetting));
   public final Setting<Boolean> inCrawlSetting = this.add(new BooleanSetting("InCrawl", false).dependsOn(this.targetsSetting, this.headSetting));
   public final Setting<Boolean> trapOverrideSetting = this.add(new BooleanSetting("TrapOverride", false).dependsOn(this.targetsSetting, this.headSetting));
   public final Setting<Boolean> feetSetting = this.add(new BooleanSetting("Feet", true).markVisibilityDependency().dependsOn(this.targetsSetting));
   public final Setting<Boolean> cornersSetting = this.add(new BooleanSetting("Corners", true).dependsOn(this.targetsSetting, this.feetSetting));
   public final Setting<Boolean> safeSetting = this.add(new BooleanSetting("Safe", true).dependsOn(this.targetsSetting, this.feetSetting));
   public final Setting<Boolean> selfSetting = this.add(new BooleanSetting("Self", true).markVisibilityDependency().dependsOn(this.targetsSetting));
   public final Setting<Boolean> faceSetting = this.add(new BooleanSetting("Face", false).dependsOn(this.targetsSetting, this.selfSetting));
   public final Setting<Boolean> onlyCrawlSetting = this.add(new BooleanSetting("OnlyCrawl", false).dependsOn(this.targetsSetting, this.selfSetting));
   public final Setting<Boolean> tryCrawlSetting = this.add(new BooleanSetting("TryCrawl", false).dependsOn(this.targetsSetting, this.selfSetting));
   public final Setting<Boolean> instantSetting = this.add(new BooleanSetting("Instant", false).dependsOn(this.targetsSetting, this.selfSetting));
   public final Setting<Boolean> downSetting = this.add(new BooleanSetting("Down", true).dependsOn(this.targetsSetting, this.selfSetting));
   public final Setting<Boolean> downPrioritySetting = this.add(
      new BooleanSetting("DownPriority", false).dependsOn(this.targetsSetting, this.selfSetting, this.downSetting)
   );
   private final List<AutoMineModule.ScheduledMiningBreak> pendingBreaks = new ArrayList<>();
   private final List<AutoMineProvider> providerStages;
   public final AutoMineTargetSelector targetSelector;
   public final AutoMineMiningTracker miningTracker;
   private boolean miningActive;

   private float getBreakDelayValue() {
      // SpeedMine has no vanilla break delay to wait out.
      return 0.0F;
   }

   private boolean updateMiningState() {
      if (this.isMiningActive()) {
         if (speedMine.getMiningPos() == null) {
            this.setMiningActive(false);
         } else {
            BlockPos miningPosition = speedMine.getMiningPos();
            boolean blockPresent = !WorldUtils.isAir(miningPosition);
            this.setMiningActive(blockPresent);
         }
      }

      return this.isMiningActive();
   }

   public float getRange() {
      Setting<Float> rangeSettingSnapshot = speedMine.rangeSetting;
      return rangeSettingSnapshot.getValue();
   }

   public boolean isMiningActive() {
      return this.miningActive;
   }

   private Direction selectMiningDirection(BlockPos position) {
      if (this.strictDirectionSetting.getValue()) {
         List<Direction> directions = BlockInteractionUtils.getStrictDirections(position);
         return directions.isEmpty() ? null : directions.getFirst();
      } else {
         return Direction.UP;
      }
   }

   private void processPendingBreaks() {
      this.pendingBreaks.removeIf(pending -> {
         if (pending.isReady()) {
            BlockPos positionSnapshot = pending.position();
            Direction directionSnapshot = pending.direction();
            boolean miningFlag = pending.miningFlag();
            this.startMining(positionSnapshot, directionSnapshot, miningFlag);
            return true;
         } else {
            return false;
         }
      });
   }

   private void startMining(BlockPos position, Direction direction, boolean miningFlag) {
      if (this.rotateSetting.getValue()) {
         Vec3 centerSnapshot = position.getCenter();
         float[] angles = RotationManager.getRotationsToPosition(centerSnapshot);
         if (this.raytraceSetting.getValue()) {
            BlockSampleMode samplingMode = BlockSampleMode.X8;
            Vec3 hitPoint = WorldUtils.findVisiblePoint(position, samplingMode);
            if (hitPoint != null) {
               angles = RotationManager.getRotationsToPosition(hitPoint);
            }
         }

         ClientContext.rotationManager.requestRotation(angles, 100);
      }

      SpeedMineModule.automatedMiningStart = miningFlag;
      ClientAccess.argusclientMinecraft.gameMode.startDestroyBlock(position, direction);
      SpeedMineModule.automatedMiningStart = false;
   }

   private void scheduleMining(BlockPos position, Direction direction, boolean miningFlag, float delay) {
      if (delay == 0.0F) {
         this.startMining(position, direction, miningFlag);
      } else {
         this.pendingBreaks.add(new AutoMineModule.ScheduledMiningBreak(position, direction, miningFlag, this.getMiningDeadline(delay)));
      }
   }

   private long getMiningDeadline(float delay) {
      long now = System.currentTimeMillis();
      return now + (long)(50.0F * delay);
   }

   @EventHandler(priority = 300)
   private void onPlayerUpdate(RotationRequestEvent event) {
      if (speedMine.isToggled()) {
         if (!multiTask.shouldPauseModule(this)) {
            if (!this.pendingBreaks.isEmpty()) {
               this.processPendingBreaks();
            } else {
               AutoMineCandidates candidates = new AutoMineCandidates();

               for (AutoMineProvider provider : this.providerStages) {
                  if (provider.isEnabled()) {
                     provider.collectCandidates(candidates);
                  }
               }

               if (this.updateMiningState()) {
                  int priority = candidates.getPriority();
                  if (priority < 1500) {
                     return;
                  }
               }

               if (candidates.isMiningCancellationRequested()) {
                  SpeedMineModule.clearRebreakAndReset(speedMine);
               } else {
                  if (candidates.getPrimaryPosition() == null) {
                     if (candidates.getSecondaryPosition() == null) {
                        return;
                     }

                     BlockPos fallbackPosition = candidates.getSecondaryPosition();
                     if (fallbackPosition.equals(this.miningTracker.getCurrentPosition())) {
                        return;
                     }

                     candidates.swapPositions();
                  }

                  if (this.isExtraBreakEnabled() && this.miningTracker.hasMiningActivity()) {
                     int priority = candidates.getPriority();
                     if (priority < 1500) {
                        return;
                     }
                  }

                  BlockPos primary = candidates.getPrimaryPosition();
                  BlockPos secondary = candidates.getSecondaryPosition();
                  if (!primary.equals(speedMine.getMiningPos())) {
                     Direction direction = this.selectMiningDirection(primary);
                     if (direction != null) {
                        float primaryDelay = 0.0F;
                        if (this.isExtraBreakEnabled() && secondary != null && !secondary.equals(primary) && !this.miningTracker.hasMiningActivity()) {
                           double rawSecondaryDelay = this.getBreakDelayValue();
                           float secondaryDelay = (float)Math.ceil(rawSecondaryDelay);
                           if (secondaryDelay > 0.0F) {
                              SpeedMineModule.reset(speedMine);
                           }

                           this.scheduleMining(secondary, direction, false, secondaryDelay);
                           primaryDelay += secondaryDelay + this.getBreakMode();
                        }

                        this.scheduleMining(primary, direction, true, primaryDelay);
                     }
                  }
               }
            }
         }
      }
   }

   public void setMiningActive(boolean active) {
      this.miningActive = active;
   }

   public boolean hasAssistantBreakProgress(BlockPos position) {
      return this.assistantSetting.getValue() && ClientContext.blockBreakTracker.getBreakProgress(position) > 0.0F;
   }

   private int getBreakMode() {
      if (antiCheat.isMovementSyncEnabled()) {
         return 2;
      } else {
         return this.isExtraBreakEnabled() ? 1 : 0;
      }
   }

   public boolean isExtraBreakEnabled() {
      return speedMine.doubleBreakSetting.getValue();
   }

   public static void bindReferencedModules(ModuleRegistry modules) {
      speedMine = (SpeedMineModule)modules.getModule(SpeedMineModule.class);
      antiCheat = (AntiCheatModule)modules.getModule(AntiCheatModule.class);
      multiTask = (MultiTaskModule)modules.getModule(MultiTaskModule.class);
      AutoMineProviderBase.bindModules(modules);
      AutoMineFeetProvider.bindModules(modules);
      AutoMineHeadProvider.bindModules(modules);
      AutoMineTerrainProvider.bindModules(modules);
      AutoMineMiningTracker.bindModules(modules);
   }

   private static String[] createAliases() {
      return new String[]{"combatmine", "autocity"};
   }

   public AutoMineModule() {
      super(
         "AutoMine",
         "Mines out traps for your enemies and farms obsidian. \n" + ChatFormatting.RED + "Requires SpeedMine module enabled.",
         ModuleCategory.COMBAT,
         createAliases()
      );
      AutoMineEnderChestProvider enderChestProvider = new AutoMineEnderChestProvider(this);
      AutoMineBurrowProvider burrowProvider = new AutoMineBurrowProvider(this);
      AutoMineFishProvider fishProvider = new AutoMineFishProvider(this);
      AutoMineFeetProvider feetProvider = new AutoMineFeetProvider(this);
      AutoMineSelfProvider selfProvider = new AutoMineSelfProvider(this);
      AutoMineTerrainProvider terrainProvider = new AutoMineTerrainProvider(this);
      AutoMineHeadProvider headProvider = new AutoMineHeadProvider(this);
      this.providerStages = List.of(enderChestProvider, burrowProvider, fishProvider, feetProvider, selfProvider, headProvider, terrainProvider);
      this.targetSelector = new AutoMineTargetSelector(this);
      this.miningTracker = new AutoMineMiningTracker();
   }

   record ScheduledMiningBreak(BlockPos position, Direction direction, boolean miningFlag, long deadlineMillis) {
      public boolean isReady() {
         return System.currentTimeMillis() >= this.deadlineMillis;
      }
   }
}
