package dev.argus.client.modules.combat;

import dev.argus.client.ClientAccess;
import dev.argus.client.ClientContext;
import dev.argus.client.access.PlayerInfoJoinTime;
import dev.argus.client.events.EventHandler;
import dev.argus.client.events.EventStage;
import dev.argus.client.events.LocalPlayerSendPositionEvent;
import dev.argus.client.events.MinecraftDisconnectEvent;
import dev.argus.client.events.MinecraftRunTickEvent;
import dev.argus.client.events.PacketEvent;
import dev.argus.client.events.RotationRequestEvent;
import dev.argus.client.events.SelectedSlotChangeEvent;
import dev.argus.client.events.WorldRenderEvent;
import dev.argus.client.events.bus.EventBus;
import dev.argus.client.managers.CrystalInteractionManager;
import dev.argus.client.managers.ModuleRegistry;
import dev.argus.client.managers.MovementPredictionManager;
import dev.argus.client.managers.RotationManager;
import dev.argus.client.managers.ServerStateManager;
import dev.argus.client.managers.SocialRegistry;
import dev.argus.client.modules.Module;
import dev.argus.client.modules.ModuleCategory;
import dev.argus.client.modules.client.AntiCheatModule;
import dev.argus.client.modules.misc.KillEffectsModule;
import dev.argus.client.modules.player.SpeedMineModule;
import dev.argus.client.render.FeatureBoxRenderer;
import dev.argus.client.render.LineMeshRenderer;
import dev.argus.client.settings.BooleanSetting;
import dev.argus.client.settings.ColorSetting;
import dev.argus.client.settings.EnumSetting;
import dev.argus.client.settings.NumberSetting;
import dev.argus.client.settings.Setting;
import dev.argus.client.settings.SettingLabelCondition;
import dev.argus.client.settings.options.AutoCrystalInstantChoice;
import dev.argus.client.settings.options.AutoCrystalSequentialChoice;
import dev.argus.client.settings.options.AutoCrystalSilentChoice;
import dev.argus.client.settings.options.AutoCrystalWeaknessChoice;
import dev.argus.client.settings.options.BlockSampleMode;
import dev.argus.client.settings.options.ItemSwapMode;
import dev.argus.client.utils.ElapsedTimer;
import dev.argus.client.utils.LockUtils;
import dev.argus.client.utils.combat.BoundingBoxUtils;
import dev.argus.client.utils.combat.DamageUtils;
import dev.argus.client.utils.combat.EndCrystalState;
import dev.argus.client.utils.combat.ExtraBreakTarget;
import dev.argus.client.utils.entity.EntityUtils;
import dev.argus.client.utils.entity.PlayerStateUtils;
import dev.argus.client.utils.entity.SwingAction;
import dev.argus.client.utils.inventory.ArmorSlot;
import dev.argus.client.utils.inventory.EnchantmentUtils;
import dev.argus.client.utils.inventory.InventoryUtils;
import dev.argus.client.utils.inventory.ItemUtils;
import dev.argus.client.utils.math.MathUtils;
import dev.argus.client.utils.math.Vec3Utils;
import dev.argus.client.utils.network.PacketSender;
import dev.argus.client.utils.network.ServerSideDeathState;
import dev.argus.client.utils.world.BlockBreakEntry;
import dev.argus.client.utils.world.BlockInteractionUtils;
import dev.argus.client.utils.world.WorldUtils;
import dev.argus.mixin.ClientboundEntityEventPacketAccessor;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class AutoCrystalModule extends Module {
   /**
    * Only one calculation is current at a time; a second thread lets a new one start while a stale one exits.
    * Daemon, low-priority threads so slow CPUs keep the render thread responsive.
    */
   private static final ExecutorService calculationExecutor = Executors.newFixedThreadPool(
      Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 1)), task -> {
         Thread thread = new Thread(task, "Argus AutoCrystal calculation");
         thread.setDaemon(true);
         thread.setPriority(Thread.NORM_PRIORITY - 1);
         return thread;
      }
   );
   /** Minimum gap between calculations; they used to restart on every rendered frame. */
   private static final long CALCULATION_INTERVAL_MILLIS = 25L;
   private static SpeedMineModule speedMine = ClientContext.getModule(SpeedMineModule.class);
   private static AntiCheatModule antiCheat = ClientContext.getModule(AntiCheatModule.class);
   private static AnchorAuraModule anchorAura = ClientContext.getModule(AnchorAuraModule.class);
   private static AuraModule aura = ClientContext.getModule(AuraModule.class);
   private static AutoExpModule autoExp = ClientContext.getModule(AutoExpModule.class);
   private final Setting<Boolean> placeSetting = this.add(new BooleanSetting("Place", true).markVisibilityDependency());
   private final Setting<Integer> placeDelaySetting = this.add(new NumberSetting<>("Delay", 0, 0, 1000).withUnit("ms").dependsOn(this.placeSetting));
   private final Setting<Float> placeRangeSetting = this.add(new NumberSetting<>("Range", 6.0F, 0.0F, 6.0F).withUnit("m").dependsOn(this.placeSetting));
   private final Setting<Float> placeWallRangeSetting = this.add(new NumberSetting<>("WallRange", 6.0F, 0.0F, 6.0F).withUnit("m").dependsOn(this.placeSetting));
   final Setting<Float> minPlaceDamageSetting = this.add(
      new NumberSetting<>("MinDamage", 6.0F, 0.1F, 20.1F).withUnit("hp").setBoundaryLabel("Lethal", SettingLabelCondition.MAX).dependsOn(this.placeSetting)
   );
   final Setting<Float> maxPlaceSelfDamageSetting = this.add(
      new NumberSetting<>("MaxSelfDamage", 12.0F, 0.0F, 20.0F).withUnit("hp").dependsOn(this.placeSetting)
   );
   final Setting<Float> placeDamageRatioSetting = this.add(new NumberSetting<>("DamageRatio", 1.0F, 0.5F, 2.0F).withUnit("x").dependsOn(this.placeSetting));
   public final Setting<AutoCrystalSequentialChoice> sequentialSetting = this.add(
      new EnumSetting<>("Sequential", AutoCrystalSequentialChoice.STRICT).dependsOn(this.placeSetting)
   );
   public final Setting<Boolean> requireUpperAirSetting = this.add(new BooleanSetting("1.12", false).dependsOn(this.placeSetting));
   private final Setting<Boolean> strictDirectionSetting = this.add(new BooleanSetting("StrictDirection", false).dependsOn(this.placeSetting));
   public final Setting<Boolean> lowerHitBoxSetting = this.add(new BooleanSetting("LowerHitBox", false).dependsOn(this.placeSetting));
   private final Setting<Boolean> mineIgnoreSetting = this.add(new BooleanSetting("MineIgnore", true).dependsOn(this.placeSetting));
   protected final Setting<Boolean> alwaysIgnoreMiningSetting = this.add(
      new BooleanSetting("Always", false).dependsOn(this.placeSetting, this.mineIgnoreSetting)
   );
   private final Setting<Integer> miningIgnoreThresholdSetting = this.add(
      new NumberSetting<>("Threshold", 1, 1, 5).dependsOn(this.placeSetting, this.mineIgnoreSetting)
   );
   private final Setting<Boolean> breakSetting = this.add(new BooleanSetting("Break", true).markVisibilityDependency());
   public final Setting<Integer> breakDelaySetting = this.add(new NumberSetting<>("Delay", 0, 0, 1000).withUnit("ms").dependsOn(this.breakSetting));
   private final Setting<Integer> attackFactorSetting = this.add(new NumberSetting<>("Factor", 1, 1, 5).dependsOn(this.breakSetting));
   private final Setting<Float> breakRangeSetting = this.add(new NumberSetting<>("Range", 6.0F, 0.0F, 6.0F).withUnit("m").dependsOn(this.breakSetting));
   private final Setting<Float> breakWallRangeSetting = this.add(new NumberSetting<>("WallRange", 6.0F, 0.0F, 6.0F).withUnit("m").dependsOn(this.breakSetting));
   final Setting<Float> minBreakDamageSetting = this.add(new NumberSetting<>("MinDamage", 3.0F, 0.1F, 20.0F).withUnit("hp").dependsOn(this.breakSetting));
   final Setting<Float> maxBreakSelfDamageSetting = this.add(
      new NumberSetting<>("MaxSelfDamage", 8.0F, 0.0F, 20.0F).withUnit("hp").dependsOn(this.breakSetting)
   );
   final Setting<Boolean> antiSuicideSetting = this.add(new BooleanSetting("AntiSuicide", true).dependsOn(this.breakSetting));
   public final Setting<AutoCrystalInstantChoice> instantSetting = this.add(
      new EnumSetting<>("Instant", AutoCrystalInstantChoice.ALWAYS).dependsOn(this.breakSetting)
   );
   private final Setting<Boolean> awaitSetting = this.add(
      new BooleanSetting("Await", true, unused -> this.instantSetting.getValue() != AutoCrystalInstantChoice.NONE).dependsOn(this.breakSetting)
   );
   public final Setting<Integer> ticksExistedSetting = this.add(new NumberSetting<>("TicksExisted", 2, 0, 40).dependsOn(this.breakSetting));
   public final Setting<AutoCrystalWeaknessChoice> weaknessSetting = this.add(
      new EnumSetting<>("Weakness", AutoCrystalWeaknessChoice.NONE).dependsOn(this.breakSetting)
   );
   private final Setting<Boolean> basePlaceSetting = this.add(new BooleanSetting("BasePlace", false).markVisibilityDependency());
   private final Setting<Integer> basePlaceDelaySetting = this.add(new NumberSetting<>("Delay", 500, 0, 1000).withUnit("ms").dependsOn(this.basePlaceSetting));
   private final Setting<Boolean> basePlaceAirSetting = this.add(new BooleanSetting("Air", false).dependsOn(this.basePlaceSetting));
   public final Setting<Boolean> rotateSetting = this.add(new BooleanSetting("Rotate", false).markVisibilityDependency());
   private final Setting<Boolean> rotatePlaceSetting = this.add(new BooleanSetting("Place", true).dependsOn(this.rotateSetting));
   private final Setting<Boolean> rotateBreakSetting = this.add(new BooleanSetting("Break", true).dependsOn(this.rotateSetting));
   final Setting<Boolean> facePlaceSetting = this.add(new BooleanSetting("FacePlace", false).markVisibilityDependency());
   final Setting<Float> facePlaceHealthSetting = this.add(new NumberSetting<>("Health", 4.0F, 0.0F, 36.0F).withUnit("hp").dependsOn(this.facePlaceSetting));
   final Setting<Integer> facePlaceArmorThresholdSetting = this.add(
      new NumberSetting<>("ArmorThreshold", 10, 0, 100).withUnit("%").dependsOn(this.facePlaceSetting)
   );
   final Setting<Boolean> facePlaceShieldBreakerSetting = this.add(new BooleanSetting("ShieldBreaker", false).dependsOn(this.facePlaceSetting));
   private final Setting<Boolean> damageSyncSetting = this.add(new BooleanSetting("DamageSync", false).dependsOn(this.facePlaceSetting));
   private final Setting<Boolean> predictionSetting = this.add(new BooleanSetting("Extrapolation", true).markVisibilityDependency());
   private final Setting<Integer> predictionTicksSetting = this.add(new NumberSetting<>("Ticks", 4, 1, 8).dependsOn(this.predictionSetting));
   private final Setting<Boolean> drawPredictionSetting = this.add(new BooleanSetting("Draw", false).dependsOn(this.predictionSetting));
   private final Setting<Boolean> renderSetting = this.add(new BooleanSetting("Render", true).markVisibilityDependency());
   private final Setting<Color> fillSetting = this.add(new ColorSetting("Fill", new Color(1136499199, true)).dependsOn(this.renderSetting));
   private final Setting<Color> outlineSetting = this.add(new ColorSetting("Outline", new Color(-910321153, true)).dependsOn(this.renderSetting));
   private final Setting<Float> lineWidthSetting = this.add(new NumberSetting<>("LineWidth", 1.0F, 0.1F, 3.0F).dependsOn(this.renderSetting));
   final Setting<Boolean> renderDamageSetting = this.add(new BooleanSetting("Damage", false).dependsOn(this.renderSetting));
   final Setting<Boolean> fadeSetting = this.add(new BooleanSetting("Fade", true).markVisibilityDependency().dependsOn(this.renderSetting));
   private final Setting<Boolean> multiFadeSetting = this.add(new BooleanSetting("MultiFade", false).dependsOn(this.fadeSetting, this.renderSetting));
   final Setting<Float> fadeTimeSetting = this.add(
      new NumberSetting<>("FadeTime", 1.0F, 0.1F, 1.5F).withUnit("s").dependsOn(this.fadeSetting, this.renderSetting)
   );
   private final Setting<Boolean> pauseSetting = this.add(new BooleanSetting("Pause", false).markAsGroup());
   private final Setting<Boolean> pauseMiningSetting = this.add(new BooleanSetting("Mining", false).dependsOn(this.pauseSetting));
   private final Setting<Boolean> pauseEatingSetting = this.add(new BooleanSetting("Eating", false).dependsOn(this.pauseSetting));
   private final Setting<Boolean> pauseMendingSetting = this.add(new BooleanSetting("Mending", false).dependsOn(this.pauseSetting));
   private final Setting<Boolean> targetingSetting = this.add(new BooleanSetting("Targeting", false).markAsGroup());
   final Setting<Boolean> bossesSetting;
   private final Setting<Float> targetRangeSetting = this.add(
      new NumberSetting<>("TargetRange", 12.0F, 1.0F, 16.0F).withUnit("m").dependsOn(this.targetingSetting)
   );
   final Setting<Float> crystalRangeSetting = this.add(new NumberSetting<>("CrystalRange", 8.0F, 0.1F, 12.0F).withUnit("m").dependsOn(this.targetingSetting));
   final Setting<Boolean> antiFriendKillSetting = this.add(new BooleanSetting("AntiFriendKill", false).dependsOn(this.targetingSetting));
   private final Setting<Boolean> antiQuiverSetting = this.add(new BooleanSetting("AntiQuiver", false).dependsOn(this.targetingSetting));
   final Setting<Boolean> ignoreNakedsSetting = this.add(new BooleanSetting("IgnoreNakeds", false).dependsOn(this.targetingSetting));
   public final Setting<Boolean> assumeBestArmorSetting = this.add(new BooleanSetting("AssumeBestArmor", false).dependsOn(this.targetingSetting));
   public final Setting<Boolean> forceSuicideSetting;
   private final Setting<Boolean> forceSuicideNoArmorSetting;
   private final Setting<Boolean> assumeInvincibilitySetting;
   private final Setting<Boolean> assumeSelfInvincibilitySetting;
   private final Setting<Float> invincibilityTimeoutSetting;
   private final Setting<Boolean> miscSetting;
   final Setting<ItemSwapMode> autoSwapSetting;
   final Setting<AutoCrystalSilentChoice> silentSetting;
   private final Setting<Boolean> swapBackSetting;
   private final Setting<Boolean> strictTimingSetting;
   private final Setting<Float> swapDelaySetting;
   private final Setting<Float> swapPenaltySetting;
   private final Setting<Boolean> multipointSetting;
   private final AutoCrystalTargeting targeting;
   private final CrystalDamageRenderer damageRenderer;
   private final ElapsedTimer crystalHoldingTimer;
   public final AtomicReference placementData;
   public final AtomicReference rotationTarget;
   public final AtomicBoolean placementGate;
   private final ElapsedTimer rotationTimer;
   private final ElapsedTimer placeTimer;
   private final ElapsedTimer breakTimer;
   private final ElapsedTimer swapDelayTimer;
   private final ElapsedTimer swapPenaltyTimer;
   private final ElapsedTimer sequentialPlacementTimer;
   private final ElapsedTimer attackRetryTimer;
   private final ElapsedTimer basePlaceTimer;
   private final LinkedList<Long> placementTimes;
   private final List<Integer> predictedAttackIds;
   private final Set ignoredBlocks;
   private final AtomicInteger calculationGeneration = new AtomicInteger();
   private final ElapsedTimer calculationIntervalTimer = new ElapsedTimer();
   private CompletableFuture<CrystalPlacementResult> calculationFuture;
   protected volatile BlockPos lastPlacedBase;
   protected volatile BlockPos lastAttackedBase;
   protected volatile BlockPos lastForcedBase;
   protected volatile boolean spawnFollowupQueued;
   protected volatile boolean breakRotationRequested;
   protected volatile boolean obsidianDropNearTarget;
   protected volatile boolean quiverThreatActive;
   protected volatile float selectedDamage;
   private int lastAttackedCrystalId;
   private int attackRetries;
   private int ignoredSlotChanges;
   private int restoreSlot;
   private BlockPos previousPlacement;
   private boolean skip;
   private boolean auraHasAttackTarget;
   public LivingEntity target;
   public LivingEntity previousTarget;
   private Vec3 eyePosition;

   @Override
   public String getInfo() {
      if (this.target != null) {
         LivingEntity targetSnapshot = this.target;
         if (!this.isInvalidTarget(targetSnapshot)) {
            String targetName = this.target instanceof Player player ? ClientContext.aliasRegistry.getPlayerAlias(player) : this.target.getPlainTextName();
            Object[] arguments = new Object[]{targetName, null, null};
            float displayDamage = this.selectedDamage;
            arguments[1] = displayDamage;
            int placementsPerSecond = this.placementTimes.size();
            arguments[2] = placementsPerSecond;
            return "%s, %.1f, %d".formatted(arguments);
         }
      }

      return super.getInfo();
   }

   @Override
   public void onEnable() {
      this.updateEyePosition();
      this.lastAttackedCrystalId = -1;
      this.previousTarget = this.target;
      this.target = null;
      this.lastPlacedBase = null;
      this.placementData.set(null);
      this.calculationGeneration.incrementAndGet();
      if (this.calculationFuture != null) {
         try {
            this.calculationFuture.complete(null);
         } catch (CancellationException var2) {
         }
      }

      this.swapDelayTimer.reset();
      this.basePlaceTimer.clear();
      this.restoreSlot = -1;
   }

   @Override
   public void onDisable() {
      this.restoreSelectedSlot();
   }

   public void setRotationTarget(Vec3 point) {
      if (point != null) {
         this.rotationTimer.reset();
         this.rotationTarget.set(point);
      }
   }

   private CrystalPlacementResult calculatePlacement(List<LivingEntity> targets, int generation) {
      // Local copies: overlapping calculations used to share (and race on) these fields.
      MutableBlockPos scanPosition = new MutableBlockPos();
      Vec3 eyePosition = this.eyePosition;
      float selectedCrystalRange;
      if (this.shouldIgnoreMiningBlock()) {
         selectedCrystalRange = 999.0F;
      } else {
         selectedCrystalRange = this.breakRangeSetting.getValue();
      }

      boolean ignoreMiningBlock = this.shouldIgnoreMiningBlock();
      if (speedMine.isInstantRebreak()) {
         Setting<Boolean> asyncSetting = speedMine.asyncSetting;
         if (!asyncSetting.getValue()) {
            ignoreMiningBlock = false;
         }
      }

      CrystalPlacementResult currentPlacement = (CrystalPlacementResult)this.placementData.get();
      CrystalTargetDamage currentTargetDamage = null;
      CrystalPlacementResult bestPlacement = null;
      CrystalPlacementValidator configuredValidator = new CrystalPlacementValidator()
         .requireUpperAir(this.requireUpperAirSetting.getValue())
         .lowerHitbox(this.lowerHitBoxSetting.getValue())
         .expandMovingPlayers(!RotationManager.isMovementSyncEnabled() && !this.shouldIgnoreMiningBlock())
         .ignoreBaseBlock(this.basePlaceSetting.getValue() && this.canRetryBasePlacement())
         .ignoreYoungItems(ignoreMiningBlock);
      Boolean extrapolate = this.predictionSetting.getValue();
      int selectedPredictionTicks;
      if (extrapolate) {
         selectedPredictionTicks = this.predictionTicksSetting.getValue();
      } else {
         selectedPredictionTicks = 0;
      }

      CrystalPlacementValidator placementValidator = configuredValidator.predictionTicks(selectedPredictionTicks).ignoredCrystalRange(selectedCrystalRange);
      if (currentPlacement != null) {
         BlockPos storedBaseSnapshot = currentPlacement.base();
         if (placementValidator.canPlace(storedBaseSnapshot)) {
            BlockPos damageBaseSnapshot = currentPlacement.base();
            BlockPos checkBaseSnapshot = currentPlacement.base();
            boolean currentHasBase = WorldUtils.isBedrockOrObsidian(checkBaseSnapshot);
            currentTargetDamage = CrystalTargetDamage.calculateForBase(this, damageBaseSnapshot, currentHasBase, targets);
         }
      }

      for (double offsetX = -Math.floor(this.placeRangeSetting.getValue().floatValue());
         offsetX < Math.ceil(this.placeRangeSetting.getValue().floatValue());
         offsetX++
      ) {
         for (double offsetY = -Math.floor(this.placeRangeSetting.getValue().floatValue());
            offsetY < Math.ceil(this.placeRangeSetting.getValue().floatValue());
            offsetY++
         ) {
            if (generation != this.calculationGeneration.get()) {
               return null;
            }

            for (double offsetZ = -Math.floor(this.placeRangeSetting.getValue().floatValue());
               offsetZ < Math.ceil(this.placeRangeSetting.getValue().floatValue());
               offsetZ++
            ) {
               if (eyePosition != null) {
                  double scanXSnapshot = eyePosition.x + offsetX;
                  double scanYSnapshot = eyePosition.y + offsetY;
                  double scanZ = eyePosition.z + offsetZ;
                  scanPosition.set(scanXSnapshot, scanYSnapshot, scanZ);
                  boolean hasCrystalBase = WorldUtils.isBedrockOrObsidian(scanPosition);
                  if (bestPlacement == null || bestPlacement.kind() == CrystalPlacementKind.OBSIDIAN || hasCrystalBase) {
                     Vec3 distanceEyeSnapshot = eyePosition;
                     Vec3 center = Vec3.atCenterOf(scanPosition);
                     double distance = distanceEyeSnapshot.distanceTo(center);
                     Float distanceLimit = this.placeRangeSetting.getValue();
                     if (!(distance > distanceLimit.floatValue()) && !(distance > selectedCrystalRange) && placementValidator.canPlace(scanPosition)) {
                        boolean visible = true;
                        Object wallRangeValue = this.placeWallRangeSetting.getValue();
                        Setting<Float> visibleRangeProbeSetting = this.placeRangeSetting;
                        if (!Objects.equals(wallRangeValue, visibleRangeProbeSetting.getValue())) {
                           BlockSampleMode samplingMode = this.multipointSetting.getValue() ? BlockSampleMode.X8 : BlockSampleMode.FAST;
                           List visibilitySamples = samplingMode.sampleBlock(scanPosition);
                           Vec3 visibilityEye = eyePosition;
                           visible = WorldUtils.canSeeAny(visibilityEye, visibilitySamples);
                        }

                        if (!visible) {
                           Float wallRangeLimit = this.placeWallRangeSetting.getValue();
                           if (distance > wallRangeLimit.floatValue()) {
                              continue;
                           }
                        }

                        if (this.strictDirectionSetting.getValue()) {
                           MutableBlockPos wallRangePosition = scanPosition;
                           if (BlockInteractionUtils.getStrictDirections(wallRangePosition).isEmpty()) {
                              continue;
                           }
                        }

                        // Target damage first: most spots fail it, so the self-damage raycasts are skipped for them.
                        MutableBlockPos targetDamageBase = scanPosition;
                        CrystalTargetDamage candidateTargetDamage = CrystalTargetDamage.calculateForBase(this, targetDamageBase, hasCrystalBase, targets);
                        if (candidateTargetDamage != null) {
                           MutableBlockPos wallRangePosition = scanPosition;
                           Vec3 explosionPosition = Vec3.upFromBottomCenterOf(wallRangePosition, 1.0);
                           LocalPlayer selfDamagePlayer = ClientAccess.argusclientMinecraft.player;
                           AABB selfBounds = this.getTargetBounds(selfDamagePlayer);
                           List<Object> selfDamageIgnoredPositions = Collections.emptyList();
                           double selfDamage = DamageUtils.getExplosionDamage(
                              explosionPosition, selfDamagePlayer, selfBounds, 6.0, true, selfDamageIgnoredPositions, scanPosition
                           );
                           CrystalDamagePolicy placeDamagePolicy = CrystalDamagePolicy.PLACE;
                           LivingEntity policyTargetSnapshot = candidateTargetDamage.target();
                           double policyDamageSnapshot = candidateTargetDamage.damage();
                           LivingEntity lethalityTargetSnapshot = candidateTargetDamage.target();
                           double lethalityDamage = candidateTargetDamage.damage();
                           CrystalLethality lethality = CrystalLethality.classifyLethality(lethalityTargetSnapshot, lethalityDamage);
                           if (!placeDamagePolicy.shouldRejectDamage(policyTargetSnapshot, selfDamage, policyDamageSnapshot, lethality)) {
                              CrystalPlacementKind placementKind = CrystalPlacementKind.NONE;
                              if (currentTargetDamage != null && !currentPlacement.base().equals(scanPosition)) {
                                 double previousDamage = currentTargetDamage.damage();
                                 double roundedPreviousDamage = Math.round(previousDamage);
                                 if (roundedPreviousDamage >= candidateTargetDamage.damage()) {
                                    continue;
                                 }
                              }

                              if (!visible) {
                                 Float candidateWallRange = this.placeWallRangeSetting.getValue();
                                 if (distance > candidateWallRange.floatValue()) {
                                    placementKind = CrystalPlacementKind.RAYTRACE;
                                 }
                              }

                              if (this.basePlaceSetting.getValue() && !hasCrystalBase) {
                                 LivingEntity predictedTargetSnapshot = candidateTargetDamage.target();
                                 AABB predictedBounds = this.getTargetBounds(predictedTargetSnapshot);
                                 LivingEntity currentBoundsTarget = candidateTargetDamage.target();
                                 AABB targetBoundsUnion = predictedBounds.minmax(currentBoundsTarget.getBoundingBox());
                                 AABB baseBounds = new AABB(scanPosition);
                                 if (targetBoundsUnion.intersects(baseBounds)) {
                                    continue;
                                 }

                                 wallRangePosition = scanPosition;
                                 if (!BlockInteractionUtils.canPlaceAt(wallRangePosition, false)) {
                                    continue;
                                 }

                                 if (!this.basePlaceAirSetting.getValue()) {
                                    wallRangePosition = scanPosition;
                                    boolean supportDirectionMode = BlockInteractionUtils.isStrictDirectionEnabled();
                                    if (BlockInteractionUtils.findSupportDirection(wallRangePosition, supportDirectionMode) == null) {
                                       continue;
                                    }
                                 }

                                 placementKind = CrystalPlacementKind.OBSIDIAN;
                              }

                              if (!(this.target instanceof Player)) {
                                 placementKind = CrystalPlacementKind.NON_PLAYER;
                              }

                              CrystalPlacementResult newPlacement = new CrystalPlacementResult(
                                 scanPosition.immutable(), candidateTargetDamage.target(), candidateTargetDamage.damage(), placementKind
                              );
                              bestPlacement = newPlacement.selectBetterPlacement(bestPlacement);
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      return bestPlacement;
   }

   private boolean isPlacementFaceVisible(BlockPos base, Direction direction) {
      if (this.placeRangeSetting.getValue().equals(this.placeWallRangeSetting.getValue())) {
         return true;
      }

      Vec3 eyeSnapshot = this.eyePosition;
      Vec3 center = Vec3.atCenterOf(base);
      double distance = eyeSnapshot.distanceTo(center);
      Float distanceRange = this.placeRangeSetting.getValue();
      if (distance < distanceRange.floatValue()) {
         return true;
      }

      BlockSampleMode samplingMode = BlockSampleMode.X8;
      List<Vec3> placementVisibilitySamples = samplingMode.sampleFace(base, direction);
      return WorldUtils.canSeeAny(placementVisibilitySamples);
   }

   private void runPlacementCycle() {
      if (!this.shouldIgnoreMiningBlock()) {
         this.placementGate.set(false);
      }

      if (this.placeSetting.getValue()) {
         if (!this.isSwapPenaltyActive()) {
            if (!this.placementGate.get()) {
               boolean crystalInOffhand = ClientAccess.argusclientMinecraft.player.getOffhandItem().getItem() == Items.END_CRYSTAL;
               Item crystalItem = Items.END_CRYSTAL;
               boolean holdingCrystal = ClientAccess.argusclientMinecraft.player.isHolding(crystalItem);
               boolean missingTarget = this.target == null;
               if (this.damageSyncSetting.getValue() || this.obsidianDropNearTarget || this.quiverThreatActive || missingTarget) {
                  float damageSnapshot = this.selectedDamage;
                  Float minimumValue = this.minPlaceDamageSetting.getValue();
                  if (missingTarget || damageSnapshot < minimumValue) {
                     damageSnapshot = this.selectedDamage;
                     if ((missingTarget || damageSnapshot < 5.0F) && !this.placeTimer.hasElapsed(this.getTickDurationMillis())) {
                        return;
                     }
                  }
               }

               CrystalPlacementResult selectedPlacement = (CrystalPlacementResult)this.placementData.get();
               if (selectedPlacement == null) {
                  this.restoreSelectedSlot();
               } else {
                  BlockPos base = selectedPlacement.base();
                  if (base != null && this.target != null) {
                     LivingEntity minimumDamageTarget = this.target;
                     double minimumDamage = CrystalDamagePolicy.PLACE.getMinimumDamage(minimumDamageTarget);
                     Vec3 explosionPosition = Vec3.upFromBottomCenterOf(base, 1.0);
                     LivingEntity damageTargetSnapshot = this.target;
                     LivingEntity boundsTargetSnapshot = this.target;
                     AABB predictedBounds = this.getTargetBounds(boundsTargetSnapshot);
                     Set targetDamageIgnoredPositions = this.ignoredBlocks;
                     double targetDamage = DamageUtils.getExplosionDamage(
                        explosionPosition, damageTargetSnapshot, predictedBounds, 6.0, true, targetDamageIgnoredPositions, base
                     );
                     if (this.predictionSetting.getValue() && targetDamage > 2.0 && targetDamage < minimumDamage) {
                        AABB currentBounds = BoundingBoxUtils.getInterpolationBounds(this.target);
                        LivingEntity currentDamageTarget = this.target;
                        double currentDamage = this.calculateExplosionDamage(base, currentDamageTarget, currentBounds, true);
                        targetDamage = Math.max(targetDamage, currentDamage);
                     }

                     if (targetDamage < minimumDamage) {
                        this.restoreSelectedSlot();
                     } else if (!WorldUtils.isBedrockOrObsidian(base)) {
                        if (selectedPlacement.kind() == CrystalPlacementKind.OBSIDIAN && this.basePlaceSetting.getValue()) {
                           this.runForcedPlacement(base);
                        }
                     } else {
                        int originalSlot = -1;
                        Item slotSearchItemSnapshot = Items.END_CRYSTAL;
                        int crystalSlot = InventoryUtils.findHotbarSlot(slotSearchItemSnapshot);
                        if (ClientAccess.argusclientMinecraft.isSameThread()) {
                           ClientLevel fireLevelSnapshot = ClientAccess.argusclientMinecraft.level;
                           BlockPos firePosition = base.above();
                           BlockState fireState = fireLevelSnapshot.getBlockState(firePosition);
                           if (fireState.getBlock() == Blocks.FIRE) {
                              ServerboundPlayerActionPacket fireStartPacket = new ServerboundPlayerActionPacket(
                                 Action.START_DESTROY_BLOCK, base.above(), Direction.DOWN
                              );
                              PacketSender.send(fireStartPacket);
                              ServerboundPlayerActionPacket fireStopPacket = new ServerboundPlayerActionPacket(
                                 Action.STOP_DESTROY_BLOCK, base.above(), Direction.DOWN
                              );
                              PacketSender.send(fireStopPacket);
                              return;
                           }
                        }

                        boolean alternativeSlotSearch = false;
                        boolean alternativeSwap = false;
                        if (this.isSilentSwap()) {
                           alternativeSwap = this.silentSetting.getValue() == AutoCrystalSilentChoice.ALTERNATIVE;
                        }

                        boolean miningSwapReset = false;
                        if (!holdingCrystal && this.shouldIgnoreMiningBlock()) {
                           Setting<Boolean> swapResetSetting = speedMine.swapResetSetting;
                           if (swapResetSetting.getValue()) {
                              Setting<Boolean> alternativeMiningSetting = speedMine.alternativeSetting;
                              if (alternativeMiningSetting.getValue()) {
                                 alternativeSwap = true;
                                 miningSwapReset = true;
                              }
                           }
                        }

                        if (!alternativeSwap || this.skip && ClientAccess.argusclientMinecraft.player.containerMenu.getCarried().isEmpty()) {
                           if (alternativeSwap && crystalSlot == -1) {
                              Item fallbackCrystalItem = Items.END_CRYSTAL;
                              crystalSlot = InventoryUtils.findMenuSlot(fallbackCrystalItem);
                           }

                           if (this.autoSwapSetting.getValue() != ItemSwapMode.NONE && !holdingCrystal && !this.isSwapDelayActive() && crystalSlot != -1) {
                              originalSlot = ClientAccess.argusclientMinecraft.player.getInventory().getSelectedSlot();
                              if (alternativeSwap) {
                                 InventoryUtils.swapIntoSelectedSlot(crystalSlot);
                                 alternativeSlotSearch = true;
                                 this.placementGate.set(miningSwapReset);
                              } else {
                                 if (this.autoSwapSetting.getValue() == ItemSwapMode.SILENT) {
                                    speedMine.crystalAttackPending = true;
                                    this.ignoreNextSlotChange();
                                 }

                                 if (this.shouldRestoreSlot()) {
                                    this.restoreSlot = originalSlot;
                                 }

                                 InventoryUtils.selectHotbarSlotForPlacement(crystalSlot);
                              }
                           }

                           if (holdingCrystal || originalSlot != -1) {
                              this.placeCrystal(base, crystalInOffhand, targetDamage);
                              if (this.isSilentSwap() || alternativeSlotSearch) {
                                 if (originalSlot != -1) {
                                    if (alternativeSlotSearch) {
                                       InventoryUtils.swapIntoSelectedSlot(crystalSlot);
                                    } else if (this.autoSwapSetting.getValue() == ItemSwapMode.SILENT) {
                                       this.ignoreNextSlotChange();
                                       InventoryUtils.selectHotbarSlotForPlacement(originalSlot);
                                    }
                                 }

                                 if (this.strictTimingSetting.getValue() || miningSwapReset || this.skip) {
                                    this.skip = !this.skip;
                                 }
                              }
                           }
                        } else {
                           this.skip = true;
                        }
                     }
                  } else {
                     this.restoreSelectedSlot();
                  }
               }
            }
         }
      }
   }

   private void sendFactorAttacks(int crystalId) {
      if (!this.attackFactorSetting.isAtMinimum()) {
         for (Entity entity : ClientAccess.argusclientMinecraft.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity && entity.isRemoved()) {
               return;
            }

            if (!entity.isAlive()) {
               return;
            }
         }

         int attackIndex = 1;

         while (true) {
            Integer factor = this.attackFactorSetting.getValue();
            if (attackIndex >= factor) {
               return;
            }

            int nextCrystalId = crystalId + attackIndex;
            int predictedCrystalId = nextCrystalId - (RotationManager.isMovementSyncEnabled() ? 1 : 0);
            PacketSender.sendAttack(predictedCrystalId);
            InteractionHand hand = InteractionHand.MAIN_HAND;
            PacketSender.sendSwing(hand);
            this.predictedAttackIds.add(predictedCrystalId);
            attackIndex++;
         }
      }
   }

   protected boolean isSelfInvulnerable() {
      if (!this.assumeSelfInvincibilitySetting.getValue()) {
         return false;
      }

      float scaledPlayerAge = ClientAccess.argusclientMinecraft.player.tickCount;
      scaledPlayerAge /= 20.0F;
      return scaledPlayerAge < this.getInvulnerabilityTimeout();
   }

   private float getInvulnerabilityTimeout() {
      return !this.assumeInvincibilitySetting.getValue() ? -1.0F : this.invincibilityTimeoutSetting.getValue();
   }

   private boolean isPlayerInvulnerable(LivingEntity target) {
      if (this.isSelfInvulnerable()) {
         return false;
      }

      if (target instanceof Player player) {
         ClientPacketListener connectionSnapshot = ClientAccess.argusclientMinecraft.player.connection;
         UUID playerId = player.getGameProfile().id();
         PlayerInfo playerInfo = connectionSnapshot.getPlayerInfo(playerId);
         if (playerInfo == null) {
            return false;
         }

         float elapsedMillis = (float)(System.currentTimeMillis() - ((PlayerInfoJoinTime)playerInfo).argusclient$getJoinTime());
         float timeout = this.getInvulnerabilityTimeout();
         return elapsedMillis <= timeout * 1000.0F;
      } else {
         return false;
      }
   }

   private boolean shouldPause() {
      if (this.isWorldUnavailable()) {
         return true;
      }

      if (BlockInteractionUtils.isInteractionCooldownActive()) {
         return true;
      }

      if (OffhandModule.isRecentSwapWindowActive()) {
         return true;
      }

      if (SpeedMineModule.isSwapPending()) {
         return true;
      }

      LivingEntity targetSnapshot = this.target;
      if (this.isInvalidTarget(targetSnapshot)) {
         return true;
      }

      if (ClientAccess.argusclientMinecraft.player.isSleeping()) {
         return true;
      }

      if (ClientAccess.argusclientMinecraft.player.isUsingItem() && this.pauseEatingSetting.getValue()) {
         return true;
      }

      if (this.pauseMendingSetting.getValue() && autoExp.isActive()) {
         return true;
      }

      boolean miningFlag = ClientAccess.argusclientMinecraft.gameMode.isDestroying();
      if (!miningFlag) {
         BlockPos miningPositionSnapshot = speedMine.getMiningPos();
         if (WorldUtils.canMineBlock(miningPositionSnapshot)) {
            miningFlag = speedMine.isToggled();
         }
      }

      if (miningFlag && this.pauseMiningSetting.getValue()) {
         return true;
      }

      if (!this.forceSuicideSetting.getValue()) {
         return false;
      }

      if (anchorAura.isToggled() && anchorAura.placementData.get() != null && anchorAura.targetDamage != null && this.placementData.get() != null) {
         double anchorDamage = anchorAura.targetDamage.damage();
         CrystalPlacementResult crystalPlacementSnapshot = (CrystalPlacementResult)this.placementData.get();
         if (anchorDamage > crystalPlacementSnapshot.damage()) {
            return true;
         }
      }

      return RotationManager.isMovementSyncEnabled() && !RotationManager.isSilentRotationMode() && ClientAccess.argusclientMinecraft.player.isFallFlying()
         ? true
         : ClientAccess.argusclientMinecraft.player.isDeadOrDying();
   }

   public boolean shouldIgnoreMiningBlock() {
      boolean nearlyBrokenFlag = false;
      Integer thresholdTicks = this.miningIgnoreThresholdSetting.getValue();
      float predictionTicks = 2.0F + 0.5F * thresholdTicks.intValue();
      double miningProgress = speedMine.getMiningProgress();
      SpeedMineModule tickProgressSnapshot = speedMine;
      double predictedProgress = miningProgress + tickProgressSnapshot.getTickBreakProgress() * predictionTicks;
      Setting<Float> miningDamageSetting = speedMine.damageSetting;
      Float miningDamageThreshold = miningDamageSetting.getValue();
      if (predictedProgress >= miningDamageThreshold.floatValue() && speedMine.getMiningOrNearlyBrokenPos() != null) {
         BlockPos miningPositionSnapshot = speedMine.getMiningOrNearlyBrokenPos();
         if (WorldUtils.canMineBlock(miningPositionSnapshot)) {
            nearlyBrokenFlag = true;
         }
      }

      if (this.mineIgnoreSetting.getValue()) {
         if (nearlyBrokenFlag) {
            return true;
         }

         if (!speedMine.hasCrystalDelayElapsed(100L)) {
            return true;
         }
      }

      return false;
   }

   private void runBreakCycle() {
      if (this.breakSetting.getValue()) {
         ElapsedTimer timerSnapshot = this.breakTimer;
         long delayMillis = this.breakDelaySetting.getValue().intValue();
         if (timerSnapshot.hasElapsed(delayMillis)) {
            if (!this.isSwapPenaltyActive()) {
               LivingEntity invulnerabilityTargetSnapshot = this.target;
               if (!this.isPlayerInvulnerable(invulnerabilityTargetSnapshot)) {
                  timerSnapshot = this.sequentialPlacementTimer;
                  long awaitDelayMillis = ClientContext.serverStateManager.getLatencyMillis();
                  if (timerSnapshot.hasElapsed(awaitDelayMillis) || this.instantSetting.getValue() == AutoCrystalInstantChoice.NONE || !this.isAwaitEnabled()) {
                     Class<SpeedMineModule> speedMineType = SpeedMineModule.class;
                     SpeedMineModule registeredSpeedMine = (SpeedMineModule)ClientContext.moduleRegistry.getModule(speedMineType);
                     Entity bestCrystal = null;
                     double bestDamage = -1337.0;
                     boolean ignoreMiningBlock = this.shouldIgnoreMiningBlock();
                     BlockPos cachedBlockSnapshot = speedMine.getMiningOrNearlyBrokenPos();
                     if (!WorldUtils.canMineBlock(cachedBlockSnapshot)) {
                        ignoreMiningBlock = false;
                     }

                     BlockPos miningBlock = registeredSpeedMine.getMiningOrNearlyBrokenPos();
                     if (!WorldUtils.canMineBlock(miningBlock)) {
                        miningBlock = null;
                     }

                     for (Entity entity : ClientAccess.argusclientMinecraft.level.entitiesForRendering()) {
                        if (entity instanceof EndCrystal crystal && this.canAttackCrystal(crystal)) {
                           double targetDamage;
                           if (this.skip) {
                              Vec3 positionSnapshot = entity.position();
                              LivingEntity directTargetSnapshot = this.target;
                              LivingEntity directBoundsTargetSnapshot = this.target;
                              AABB directTargetBounds = this.getTargetBounds(directBoundsTargetSnapshot);
                              targetDamage = DamageUtils.getCrystalDamage(positionSnapshot, directTargetSnapshot, directTargetBounds);
                           } else {
                              Vec3 crystalPositionVector = entity.position();
                              BlockPos crystalPosition = BlockPos.containing(crystalPositionVector);
                              BlockPos crystalBase = crystalPosition.below();
                              LivingEntity baseTargetSnapshot = this.target;
                              LivingEntity baseBoundsTargetSnapshot = this.target;
                              AABB baseTargetBounds = this.getTargetBounds(baseBoundsTargetSnapshot);
                              targetDamage = this.calculateExplosionDamage(crystalBase, baseTargetSnapshot, baseTargetBounds, false);
                           }

                           boolean recentPlacementIntersectionFlag = false;
                           if (this.lastPlacedBase != null && !entity.blockPosition().equals(this.lastPlacedBase.above())) {
                              AABB entityBounds = entity.getBoundingBox();
                              AABB placementBoxSnapshot = new AABB(this.lastPlacedBase.above());
                              AABB placementIntersectionBox = placementBoxSnapshot.expandTowards(0.0, 1.0, 0.0);
                              if (entityBounds.intersects(placementIntersectionBox)) {
                                 timerSnapshot = this.placeTimer;
                                 if (!timerSnapshot.hasElapsed(200L)) {
                                    recentPlacementIntersectionFlag = true;
                                 }
                              }
                           }

                           Vec3 selfPositionSnapshot = entity.position();
                           LocalPlayer selfPlayerSnapshot = ClientAccess.argusclientMinecraft.player;
                           AABB selfBounds = ClientAccess.argusclientMinecraft.player.getBoundingBox();
                           double selfDamage = DamageUtils.getCrystalDamage(selfPositionSnapshot, selfPlayerSnapshot, selfBounds);
                           CrystalLethality lethality = CrystalLethality.classifyLethality(this.target, targetDamage);
                           if (recentPlacementIntersectionFlag) {
                              lethality = CrystalLethality.INTERSECTION;
                           }

                           if (!(targetDamage <= selfDamage)
                              || this.forceSuicideSetting.getValue()
                              || lethality.lethalDamage
                              || recentPlacementIntersectionFlag) {
                              LivingEntity policyTarget = this.target;
                              if (!CrystalDamagePolicy.HIT.shouldRejectDamage(policyTarget, selfDamage, targetDamage, lethality)) {
                                 if (recentPlacementIntersectionFlag && bestCrystal == null && (!ignoreMiningBlock || miningBlock == null)) {
                                    bestCrystal = entity;
                                 }

                                 LivingEntity minimumDamageTarget = this.target;
                                 CrystalDamagePolicy minimumDamagePolicy = CrystalDamagePolicy.HIT;
                                 if (!(targetDamage < minimumDamagePolicy.getMinimumDamage(minimumDamageTarget)) && targetDamage > bestDamage) {
                                    bestDamage = targetDamage;
                                    bestCrystal = entity;
                                 }
                              }
                           }
                        }
                     }

                     if (bestCrystal != null) {
                        EndCrystal selectedCrystal = (EndCrystal)bestCrystal;
                        this.attackCrystal(selectedCrystal);
                     }
                  }
               }
            }
         }
      }
   }

   private void updateEyePosition() {
      if (!this.isWorldUnavailable()) {
         LocalPlayer player = ClientAccess.argusclientMinecraft.player;
         this.eyePosition = player.getEyePosition();
      }
   }

   public boolean isFacePlaceEnabled() {
      if (this.facePlaceSetting.getValue()) {
         if (!this.auraHasAttackTarget) {
            return true;
         }

         if (!this.damageSyncSetting.getValue()) {
            return true;
         }
      }

      return false;
   }

   private boolean canRetryBasePlacement() {
      if (this.lastForcedBase != null && this.target != null) {
         BlockPos forcedBaseSnapshot = this.lastForcedBase;
         boolean requireUpperAir = this.requireUpperAirSetting.getValue();
         boolean lowerHitboxEnabled = this.lowerHitBoxSetting.getValue();
         if (!BlockInteractionUtils.canPlaceCrystal(forcedBaseSnapshot, requireUpperAir, true, false, true, lowerHitboxEnabled, true)) {
            return true;
         }

         BlockSampleMode samplingMode = this.multipointSetting.getValue() ? BlockSampleMode.X8 : BlockSampleMode.FAST;
         List samplePoints = samplingMode.sampleBlock(this.lastForcedBase);
         boolean visible = true;
         Object wallRangeValue = this.placeWallRangeSetting.getValue();
         Setting<Float> rangeCompareSetting = this.placeRangeSetting;
         if (!Objects.equals(wallRangeValue, rangeCompareSetting.getValue())) {
            Vec3 placementEye = this.eyePosition;
            visible = WorldUtils.canSeeAny(placementEye, samplePoints);
         }

         Vec3 eyePositionSnapshot = ClientAccess.argusclientMinecraft.player.getEyePosition();
         Vec3 center = Vec3.atCenterOf(this.lastForcedBase);
         double distance = eyePositionSnapshot.distanceTo(center);
         Float range = this.placeRangeSetting.getValue();
         if (distance > range.floatValue()) {
            return true;
         }

         if (!visible) {
            Float wallRange = this.placeWallRangeSetting.getValue();
            if (distance > wallRange.floatValue()) {
               return true;
            }
         }

         Vec3 explosionPositionSnapshot = Vec3.upFromBottomCenterOf(this.lastForcedBase, 1.0);
         LivingEntity damageTargetSnapshot = this.target;
         LivingEntity boundsTargetSnapshot = this.target;
         AABB targetBounds = this.getTargetBounds(boundsTargetSnapshot);
         double targetDamage = DamageUtils.getCrystalDamage(explosionPositionSnapshot, damageTargetSnapshot, targetBounds);
         LivingEntity minimumDamageTarget = this.target;
         CrystalDamagePolicy damagePolicy = CrystalDamagePolicy.PLACE;
         if (targetDamage >= damagePolicy.getMinimumDamage(minimumDamageTarget)) {
            return false;
         }
      }

      ElapsedTimer baseTimerSnapshot = this.basePlaceTimer;
      long tickDurationSnapshot = this.getTickDurationMillis();
      long baseDelayMillis = this.basePlaceDelaySetting.getValue().intValue();
      long retryDelayMillis = Math.max(tickDurationSnapshot, baseDelayMillis);
      return baseTimerSnapshot.hasElapsed(retryDelayMillis);
   }

   private void clearInvalidTarget() {
      LivingEntity targetSnapshot = this.target;
      if (this.isInvalidTarget(targetSnapshot)) {
         this.target = null;
      }
   }

   private Direction selectPlacementDirection(BlockPos base) {
      List<Direction> directions = BlockInteractionUtils.getStrictDirections(base);
      if (directions.isEmpty()) {
         Direction[] allDirections = Direction.values();
         directions = Arrays.asList(allDirections);
      }

      Stream<Direction> directionResultCandidates = directions.stream().filter(candidateDirection -> this.isPlacementFaceVisible(base, candidateDirection));
      Comparator<Direction> directionResultOrder = Comparator.comparing(candidateDirection -> {
         Vec3 eyeSnapshot = this.eyePosition;
         Vec3 centerSnapshot = Vec3.atCenterOf(base);
         double faceOffset = 0.5;
         Direction faceDirection = candidateDirection;
         Vec3 faceCenter = centerSnapshot.relative(faceDirection, faceOffset);
         double distance = eyeSnapshot.distanceTo(faceCenter);
         return distance;
      });
      Optional<Direction> directionResult = directionResultCandidates.min(directionResultOrder);
      return directionResult.orElse(null);
   }

   private void runForcedPlacement(BlockPos base) {
      if (ClientAccess.argusclientMinecraft.isSameThread()) {
         if (this.canRetryBasePlacement()) {
            Item itemSnapshot = Items.END_CRYSTAL;
            int crystalSlot = InventoryUtils.findHotbarSlot(itemSnapshot);
            if (this.autoSwapSetting.getValue() == ItemSwapMode.SILENT && this.silentSetting.getValue() == AutoCrystalSilentChoice.ALTERNATIVE) {
               Item alternativeCrystalItem = Items.END_CRYSTAL;
               crystalSlot = InventoryUtils.findMenuSlot(alternativeCrystalItem);
            }

            ItemStack offhandSnapshot = ClientAccess.argusclientMinecraft.player.getOffhandItem();
            Item crystalItem = Items.END_CRYSTAL;
            if (offhandSnapshot.is(crystalItem) || crystalSlot != -1) {
               boolean strictDirection = BlockInteractionUtils.isStrictDirectionEnabled();
               boolean minimumWallRange = this.placeWallRangeSetting.isAtMinimum();
               Direction direction = BlockInteractionUtils.findSupportDirection(base, strictDirection, minimumWallRange);
               Vec3 hitPoint = Vec3.atCenterOf(base);
               if (direction != null) {
                  BlockSampleMode samplingMode = this.multipointSetting.getValue() ? BlockSampleMode.X8 : BlockSampleMode.FAST;
                  Stream<Vec3> sampleResultCandidates = samplingMode.sampleFace(base, direction)
                     .stream()
                     .filter(candidatePoint -> this.isPlacementHitVisible(candidatePoint, base, direction));
                  Comparator<Vec3> sampleResultOrder = Comparator.comparing(candidatePoint -> {
                     Vec3 eyeSnapshot = ClientAccess.argusclientMinecraft.player.getEyePosition();
                     Vec3 point = candidatePoint;
                     double distance = eyeSnapshot.distanceTo(point);
                     return distance;
                  });
                  Optional<Vec3> sampleResult = sampleResultCandidates.min(sampleResultOrder);
                  Vec3 visibleHit = sampleResult.orElse(null);
                  if (visibleHit != null) {
                     hitPoint = visibleHit;
                  } else {
                     hitPoint = hitPoint.relative(direction, 0.5);
                  }
               }

               boolean alternativeSwap = false;
               int originalSlot = ClientAccess.argusclientMinecraft.player.getInventory().getSelectedSlot();
               itemSnapshot = Items.OBSIDIAN;
               int baseSlot = InventoryUtils.findHotbarSlot(itemSnapshot);
               if (this.isSilentSwap() && this.silentSetting.getValue() == AutoCrystalSilentChoice.ALTERNATIVE) {
                  alternativeSwap = true;
               }

               InteractionHand hand = InteractionHand.MAIN_HAND;
               if (alternativeSwap && baseSlot == -1) {
                  Item fallbackBaseItem = Items.OBSIDIAN;
                  baseSlot = InventoryUtils.findMenuSlot(fallbackBaseItem);
               }

               if (baseSlot != -1) {
                  if (direction == null && !this.basePlaceAirSetting.getValue()) {
                     return;
                  }

                  if (BlockInteractionUtils.canPlaceAt(base, false)) {
                     if (baseSlot != originalSlot) {
                        if (alternativeSwap) {
                           InventoryUtils.swapIntoSelectedSlot(baseSlot);
                        } else {
                           speedMine.crystalAttackPending = true;
                           InventoryUtils.selectHotbarSlotForPlacement(baseSlot);
                        }
                     }

                     boolean interactionAirEnabled = this.basePlaceAirSetting.getValue();
                     BlockInteractionUtils.placeBlockIgnoringCrystals(base, hitPoint, direction, interactionAirEnabled, hand);
                     this.basePlaceTimer.reset();
                     if (this.rotatePlaceSetting.getValue() && this.rotateSetting.getValue()) {
                        this.setRotationTarget(hitPoint);
                     }

                     this.lastForcedBase = base;
                     if (originalSlot != -1 && baseSlot != originalSlot) {
                        if (alternativeSwap) {
                           InventoryUtils.swapIntoSelectedSlot(baseSlot);
                        } else {
                           InventoryUtils.selectHotbarSlotForPlacement(originalSlot);
                        }
                     }

                     return;
                  }
               }
            }
         }
      }
   }

   private boolean isSwapDelayActive() {
      if (this.autoSwapSetting.getValue() != ItemSwapMode.NORMAL) {
         return false;
      }

      ElapsedTimer delayTimerSnapshot = this.swapDelayTimer;
      Float delay = this.swapDelaySetting.getValue();
      return !delayTimerSnapshot.hasElapsed((long)(50.0F * delay));
   }

   private void throwUnboundArmor() {
      for (ArmorSlot armorSlot : ArmorSlot.values()) {
         Inventory inventorySnapshot = ClientAccess.argusclientMinecraft.player.getInventory();
         int inventoryIndex = armorSlot.getInventoryIndex();
         ItemStack armorStack = inventorySnapshot.getItem(inventoryIndex);
         if (!armorStack.isEmpty()) {
            ResourceKey bindingCurse = Enchantments.BINDING_CURSE;
            if (!EnchantmentUtils.hasEnchantment(bindingCurse, armorStack)) {
               int slotIndex = armorSlot.getContainerSlot();
               ContainerInput clickType = ContainerInput.THROW;
               InventoryUtils.clickSlot(slotIndex, 1, clickType);
               break;
            }
         }
      }
   }

   private long getTickDurationMillis() {
      ServerStateManager serverStateSnapshot = ClientContext.serverStateManager;
      return (long)(500.0F / serverStateSnapshot.getTickRateScale());
   }

   public void ignoreNextSlotChange() {
      this.ignoredSlotChanges++;
   }

   private boolean isQuiverThreatPresent() {
      if (this.quiverThreatActive) {
         return false;
      }

      if (this.antiQuiverSetting.getValue() && this.target instanceof Player) {
         if (!this.target.isAlive()) {
            return false;
         }

         for (Entity entity : ClientAccess.argusclientMinecraft.level.entitiesForRendering()) {
            if (entity instanceof Arrow) {
               double distanceScratch = entity.getY();
               LivingEntity heightTarget = this.target;
               if (!(distanceScratch < heightTarget.getEyeY())) {
                  Vec3 arrowPositionSnapshot = entity.position();
                  Vec3 targetEyeSnapshot = this.target.getEyePosition();
                  distanceScratch = Vec3Utils.horizontalDistance(arrowPositionSnapshot, targetEyeSnapshot);
                  boolean withinHorizontalRange = distanceScratch < 0.5;
                  distanceScratch = this.target.getEyeY();
                  double heightDifference = distanceScratch - entity.getEyeY();
                  distanceScratch = Math.abs(heightDifference);
                  boolean withinVerticalRange = distanceScratch < 4.0;
                  if (withinHorizontalRange && withinVerticalRange) {
                     return true;
                  }
               }
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private boolean shouldRestoreSlot() {
      return this.autoSwapSetting.getValue() != ItemSwapMode.NORMAL ? false : this.swapBackSetting.getValue();
   }

   private boolean isSwapPenaltyActive() {
      ElapsedTimer penaltyTimerSnapshot = this.swapPenaltyTimer;
      Float penalty = this.swapPenaltySetting.getValue();
      return !penaltyTimerSnapshot.hasElapsed((long)(50.0F * penalty));
   }

   private void updateIgnoredBlocks() {
      synchronized (this.ignoredBlocks) {
         this.ignoredBlocks.clear();
         if (this.mineIgnoreSetting.getValue()) {
            if (this.shouldIgnoreMiningBlock()) {
               SpeedMineModule miningModuleSnapshot = speedMine;
               BlockPos miningPosition = miningModuleSnapshot.getMiningPos();
               if (miningPosition != null) {
                  this.ignoredBlocks.add(miningPosition);
               }

               miningModuleSnapshot = speedMine;
               if (miningModuleSnapshot.getExtraBreakTarget() != null) {
                  Set extraTargetIgnoredBlocks = this.ignoredBlocks;
                  ExtraBreakTarget extraTargetResult = speedMine.getExtraBreakTarget();
                  extraTargetIgnoredBlocks.add(extraTargetResult.getPosition());
               }
            }

            Setting<Boolean> assumeBreakingSetting = antiCheat.assumeBlockBreakingSetting;
            if (assumeBreakingSetting.getValue()) {
               for (BlockBreakEntry trackedBreak : ClientContext.blockBreakTracker.getEntries()) {
                  double breakProgress = trackedBreak.getProgress();
                  if (!(breakProgress < 0.8)) {
                     BlockPos positionSnapshot = trackedBreak.getPosition();
                     double distanceSquared = positionSnapshot.distSqr(ClientAccess.argusclientMinecraft.player.blockPosition());
                     if (!(distanceSquared > 144.0)) {
                        this.ignoredBlocks.add(trackedBreak.getPosition());
                     }
                  }
               }
            }
         }
      }
   }

   AABB getTargetBounds(LivingEntity target) {
      if (this.predictionSetting.getValue() && target instanceof Player player) {
         MovementPredictionManager predictionSnapshot = ClientContext.movementPredictionManager;
         int predictionTicks = BoundingBoxUtils.getPredictionTicks(this.predictionTicksSetting);
         return predictionSnapshot.getPredictedBoundingBox(player, predictionTicks);
      } else {
         return BoundingBoxUtils.getInterpolationBounds(target);
      }
   }

   private boolean prepareCalculation() {
      LocalPlayer heldItemPlayer = ClientAccess.argusclientMinecraft.player;
      Predicate<ItemStack> shulkerPredicate = stack -> {
         ItemStack heldStack = stack;
         Item heldItemSnapshot = heldStack.getItem();
         return ItemUtils.isShulkerBox(heldItemSnapshot);
      };
      boolean holdingShulker = heldItemPlayer.isHolding(shulkerPredicate);
      if (!holdingShulker && this.crystalHoldingTimer.hasElapsed(50L)) {
         return false;
      }

      if (holdingShulker) {
         this.crystalHoldingTimer.reset();
      }

      if (this.calculationFuture != null && !this.calculationFuture.isDone()) {
         this.completeCalculation();
      }

      return true;
   }

   private void restoreSelectedSlot() {
      if (!this.isWorldUnavailable() && this.shouldRestoreSlot() && this.restoreSlot != -1) {
         InventoryUtils.selectHotbarSlotForPlacement(this.restoreSlot);
         this.restoreSlot = -1;
      }
   }

   public boolean isAwaitEnabled() {
      return this.awaitSetting.getValue();
   }

   private boolean isSilentSwap() {
      return this.autoSwapSetting.getValue() == ItemSwapMode.SILENT;
   }

   private void onCrystalSpawn(ClientboundAddEntityPacket packet) {
      if (packet.getType() == EntityTypes.END_CRYSTAL && this.target != null) {
         Vec3 spawnPosition = new Vec3(packet.getX(), packet.getY(), packet.getZ());
         if (this.lastPlacedBase != null) {
            Vec3 placedCenter = Vec3.atCenterOf(this.lastPlacedBase);
            double placementDistanceSquared = spawnPosition.distanceToSqr(placedCenter);
            if (placementDistanceSquared <= 9.0) {
               long spawnTime = System.currentTimeMillis();
               Minecraft placementClient = ClientAccess.argusclientMinecraft;
               Runnable recordPlacement = () -> {
                  LinkedList<Long> placementTimesSnapshot = this.placementTimes;
                  Long boxedSpawnTime = spawnTime;
                  placementTimesSnapshot.add(boxedSpawnTime);
               };
               placementClient.execute(recordPlacement);
            }
         }

         if (this.instantSetting.getValue() != AutoCrystalInstantChoice.NONE) {
            if (this.breakSetting.getValue()) {
               LivingEntity targetSnapshot = this.target;
               if (!this.isPlayerInvulnerable(targetSnapshot)) {
                  if (!this.shouldIgnoreMiningBlock() || !this.isAwaitEnabled()) {
                     boolean visible = true;
                     Object visibleRangeValue = this.breakRangeSetting.getValue();
                     Setting<Float> wallRangeProbeSetting = this.breakWallRangeSetting;
                     if (!Objects.equals(visibleRangeValue, wallRangeProbeSetting.getValue())) {
                        visible = WorldUtils.canSee(this.eyePosition, spawnPosition);
                     }

                     double distance = ClientAccess.argusclientMinecraft.player.getEyePosition().distanceTo(spawnPosition);
                     Float selectedRange;
                     if (visible) {
                        selectedRange = this.breakRangeSetting.getValue();
                     } else {
                        selectedRange = this.breakWallRangeSetting.getValue();
                     }

                     if (!(distance > selectedRange.floatValue())) {
                        LivingEntity damageTarget = this.target;
                        double targetDamage = DamageUtils.getCrystalDamage(spawnPosition, damageTarget);
                        LocalPlayer selfPlayer = ClientAccess.argusclientMinecraft.player;
                        double selfDamage = DamageUtils.getCrystalDamage(spawnPosition, selfPlayer);
                        CrystalLethality lethality = CrystalLethality.classifyLethality(this.target, targetDamage);
                        if (!(targetDamage <= selfDamage) || this.forceSuicideSetting.getValue() || lethality.lethalDamage) {
                           LivingEntity minimumDamageTarget = this.target;
                           CrystalDamagePolicy minimumDamagePolicy = CrystalDamagePolicy.HIT;
                           if (!(targetDamage < minimumDamagePolicy.getMinimumDamage(minimumDamageTarget))) {
                              LivingEntity policyTarget = this.target;
                              if (!CrystalDamagePolicy.HIT.shouldRejectDamage(policyTarget, selfDamage, targetDamage, lethality)) {
                                 BlockPos crystalPosition = BlockPos.containing(spawnPosition);
                                 if (this.instantSetting.getValue() != AutoCrystalInstantChoice.STRICT || !this.previousPlacement.equals(crystalPosition)) {
                                    if (this.rotateSetting.getValue() && this.rotateBreakSetting.getValue()) {
                                       this.setRotationTarget(spawnPosition);
                                    }

                                    CrystalInteractionManager attackManagerSnapshot = ClientContext.crystalInteractionManager;
                                    int packetEntityId = packet.getId();
                                    if (attackManagerSnapshot.attackEntity(packetEntityId, true)) {
                                       this.lastAttackedBase = crystalPosition;
                                       this.previousPlacement = crystalPosition;
                                       this.lastAttackedCrystalId = packet.getId();
                                       this.attackRetries = 0;
                                       this.basePlaceTimer.reset();
                                    }

                                    Item crystalItem = Items.END_CRYSTAL;
                                    boolean followupAllowed = ClientAccess.argusclientMinecraft.player.isHolding(crystalItem);
                                    if (!followupAllowed) {
                                       float health = EntityUtils.getPlayerHealthWithAbsorption();
                                       followupAllowed = health >= 8.0F;
                                    }

                                    if (!antiCheat.is2b2tEnabled()) {
                                       followupAllowed = true;
                                    }

                                    if (this.sequentialSetting.getValue().isEnabled() && !this.spawnFollowupQueued && followupAllowed) {
                                       this.sequentialPlacementTimer.reset();
                                       Runnable placementCallback = this::runPlacementCycle;
                                       LockUtils.runWithSharedLock(placementCallback);
                                       if (this.sequentialSetting.getValue() == AutoCrystalSequentialChoice.STRICT) {
                                          this.spawnFollowupQueued = true;
                                       }
                                    }

                                    int factorIdSnapshot = packet.getId();
                                    this.sendFactorAttacks(factorIdSnapshot);
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void placeCrystal(BlockPos base, boolean offhand, double damage) {
      if (base != null) {
         Direction direction = this.selectPlacementDirection(base);
         if (direction != null) {
            if (!base.equals(this.lastAttackedBase) && this.lastAttackedBase != null && !this.sequentialSetting.getValue().isEnabled()) {
               return;
            }

            Vec3 centerSnapshot = Vec3.atCenterOf(base);
            double faceOffset = 0.5;
            Vec3 hitPoint = centerSnapshot.relative(direction, faceOffset);
            Stream<Vec3> cornerResultCandidates = MathUtils.faceCorners(base, direction)
               .stream()
               .filter(candidatePoint -> this.isPlacementHitVisible(candidatePoint, base, direction));
            Comparator<Vec3> cornerResultOrder = Comparator.comparing(candidatePoint -> {
               Vec3 point = candidatePoint;
               double distance = this.eyePosition.distanceTo(point);
               return distance;
            });
            Optional<Vec3> cornerResult = cornerResultCandidates.min(cornerResultOrder);
            Vec3 visibleHit = cornerResult.orElse(null);
            if (visibleHit != null) {
               hitPoint = visibleHit;
            }

            Object rangeValue = this.placeRangeSetting.getValue();
            Setting<Float> wallRangeSetting = this.placeWallRangeSetting;
            if (Objects.equals(rangeValue, wallRangeSetting.getValue())) {
               if (direction.getAxis() == Axis.Y) {
                  Vec3 verticalCenterSnapshot = Vec3.atCenterOf(base);
                  double verticalOffset = 0.5;
                  hitPoint = verticalCenterSnapshot.relative(direction, verticalOffset);
               } else {
                  Vec3 sideCenterSnapshot = Vec3.atCenterOf(base);
                  double sideOffset = 0.5;
                  Vec3 sidePosition = sideCenterSnapshot.relative(direction, sideOffset);
                  hitPoint = sidePosition.add(0.0, 0.5, 0.0);
               }
            }

            if (this.rotatePlaceSetting.getValue() && this.rotateSetting.getValue() && !this.breakRotationRequested) {
               this.setRotationTarget(hitPoint);
            }

            InteractionHand hand = offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            BlockHitResult hit = new BlockHitResult(hitPoint, direction, base, false);
            PacketSender.sendUseItemOn(hand, hit);
            SwingAction swingAction = SwingAction.INTERACT;
            ClientContext.swingManager.swing(hand, swingAction);
            this.lastPlacedBase = base;
            if (this.renderSetting.getValue()) {
               ClientContext.featureBoxRenderer.addBox(this, base);
               this.damageRenderer.showDamage(base, damage);
            }

            this.placeTimer.reset();
         }
      }
   }

   private void attackCrystal(EndCrystal crystal) {
      if (crystal.getId() == this.lastAttackedCrystalId && this.attackRetryTimer.hasElapsed(500L) && crystal.isAlive() && !crystal.isRemoved()) {
         this.attackRetries++;
      } else {
         this.attackRetries = 0;
      }

      this.lastAttackedCrystalId = crystal.getId();
      if (this.attackRetries >= 5) {
         this.attackRetryTimer.reset();
      }

      CrystalInteractionManager attackManagerSnapshot = ClientContext.crystalInteractionManager;
      int crystalId = crystal.getId();
      if (attackManagerSnapshot.attackEntity(crystalId)) {
         ((EndCrystalState)crystal).setClientAttacked(true);
         BlockPos crystalPosition = crystal.blockPosition();
         this.lastAttackedBase = crystalPosition.below();
         int attackedCrystalId = crystal.getId();
         this.sendFactorAttacks(attackedCrystalId);
         if (this.rotateBreakSetting.getValue() && this.rotateSetting.getValue()) {
            AABB bounds = crystal.getBoundingBox();
            Vec3 rotationPointSnapshot = BoundingBoxUtils.getLowerQuarterCenter(bounds);
            this.setRotationTarget(rotationPointSnapshot);
            this.breakRotationRequested = true;
         }

         this.breakTimer.reset();
         this.basePlaceTimer.reset();
      }
   }

   private boolean isPlacementHitVisible(Vec3 point, BlockPos base, Direction direction) {
      if (this.placeRangeSetting.getValue().equals(this.placeWallRangeSetting.getValue())) {
         return true;
      }

      double distance = this.eyePosition.distanceTo(point);
      Float wallRange = this.placeWallRangeSetting.getValue();
      return distance >= wallRange.floatValue() ? WorldUtils.canSee(this.eyePosition, point, base, direction) : true;
   }

   private static float compareMovementKeys(boolean positivePressed, boolean negativePressed) {
      return positivePressed == negativePressed ? 0.0F : (positivePressed ? 1 : -1);
   }

   double calculateExplosionDamage(BlockPos base, LivingEntity target, AABB bounds, boolean ignoreBaseBlock) {
      boolean miningMatchFlag = false;
      BlockPos trackedBlock = ClientContext.worldStateManager.getUnconfirmedBreak();
      if (trackedBlock != null && speedMine.getMiningOrNearlyBrokenPos() != null && trackedBlock.equals(speedMine.getMiningOrNearlyBrokenPos())) {
         miningMatchFlag = true;
      }

      boolean useIgnoredBlocks = ignoreBaseBlock || miningMatchFlag;
      if (trackedBlock != null) {
         SpeedMineModule ignoredBlockSnapshot = speedMine;
         useIgnoredBlocks |= trackedBlock.equals(ignoredBlockSnapshot.getMiningOrNearlyBrokenPos());
      }

      Vec3 explosionPosition = Vec3.upFromBottomCenterOf(base, 1.0);
      Collection damageIgnoredBlocks;
      if (useIgnoredBlocks) {
         damageIgnoredBlocks = this.ignoredBlocks;
      } else {
         damageIgnoredBlocks = Collections.emptyList();
      }

      BlockPos ignoredBase;
      if (ignoreBaseBlock) {
         ignoredBase = base.immutable();
      } else {
         ignoredBase = null;
      }

      return DamageUtils.getExplosionDamage(explosionPosition, target, bounds, 6.0, true, damageIgnoredBlocks, ignoredBase);
   }

   @EventHandler
   private void onHeldSlotChange(SelectedSlotChangeEvent event) {
      if (this.ignoredSlotChanges > 0) {
         this.ignoredSlotChanges--;
      } else {
         ElapsedTimer timerSnapshot = this.swapPenaltyTimer;
         timerSnapshot.reset();
         timerSnapshot = this.swapDelayTimer;
         timerSnapshot.reset();
      }
   }

   @EventHandler
   private void onMinecraftDisconnectEvent(MinecraftDisconnectEvent event) {
      Setting<Boolean> suicideSettingSnapshot = this.forceSuicideSetting;
      Boolean disabledValue = false;
      suicideSettingSnapshot.setValue(disabledValue);
   }

   boolean isInvalidTarget(LivingEntity candidate) {
      if (candidate == null) {
         return true;
      }

      if (this.forceSuicideSetting.getValue()) {
         return candidate != ClientAccess.argusclientMinecraft.player;
      }

      if (candidate instanceof Player friendCandidate) {
         SocialRegistry friends = ClientContext.socialRegistry;
         if (friends.isFriendPlayer(friendCandidate)) {
            return true;
         }
      }

      if (candidate instanceof Player player) {
         SocialRegistry friends = ClientContext.socialRegistry;
         if (friends.isFriendPlayer(player) || player.isCreative()) {
            return true;
         }
      }

      if (!candidate.isAlive()) {
         return true;
      }

      if (candidate.isDeadOrDying()) {
         return true;
      }

      float distance = candidate.distanceTo(ClientAccess.argusclientMinecraft.player);
      Float targetRange = this.targetRangeSetting.getValue();
      return distance <= targetRange && !((ServerSideDeathState)candidate).isServerSideDead() && ClientAccess.argusclientMinecraft.player != candidate
         ? candidate.isSpectator()
         : true;
   }

   public boolean canAttackCrystal(EndCrystal crystal) {
      int crystalAge = crystal.tickCount;
      Integer minimumAge = this.ticksExistedSetting.getValue();
      if (crystalAge < minimumAge && !this.shouldIgnoreMiningBlock()) {
         return false;
      }

      if (!crystal.isRemoved() && this.target != null) {
         boolean visible = ClientAccess.argusclientMinecraft.player.hasLineOfSight(crystal);
         Vec3 capturedEye = ClientAccess.argusclientMinecraft.player.getEyePosition();
         Vec3 position = crystal.position();
         double distance = capturedEye.distanceTo(position);
         Float selectedRange;
         if (visible) {
            selectedRange = this.breakRangeSetting.getValue();
         } else {
            selectedRange = this.breakWallRangeSetting.getValue();
         }

         return distance <= selectedRange.floatValue();
      } else {
         return false;
      }
   }

   @EventHandler
   private void onRenderTargetPath(WorldRenderEvent.Main event) {
      LineMeshRenderer.begin(event, 1.5F);
      if (this.drawPredictionSetting.getValue() && this.predictionSetting.getValue() && this.target instanceof Player pathTarget) {
         int predictionTick = 1;

         while (true) {
            Setting<Integer> ticksSetting = this.predictionTicksSetting;
            if (predictionTick >= BoundingBoxUtils.getPredictionTicks(ticksSetting)) {
               Player endStartTargetSnapshot = pathTarget;
               Vec3 endStart = BoundingBoxUtils.getBottomCenter(this.getTargetBounds(endStartTargetSnapshot));
               Player endPointTargetSnapshot = pathTarget;
               Vec3 endPoint = BoundingBoxUtils.getTopCenter(this.getTargetBounds(endPointTargetSnapshot));
               Setting<Color> endOutlineSetting = this.outlineSetting;
               LineMeshRenderer.addLine(endStart, endPoint, endOutlineSetting.getValue());
               break;
            }

            int previousTick = predictionTick - 1;
            Player previousTarget = pathTarget;
            AABB previousBounds = ClientContext.movementPredictionManager.getPredictedBoundingBox(previousTarget, previousTick);
            Player nextTarget = pathTarget;
            AABB nextBounds = ClientContext.movementPredictionManager.getPredictedBoundingBox(nextTarget, predictionTick);
            Vec3 previousBottomCenter = BoundingBoxUtils.getBottomCenter(previousBounds);
            Vec3 nextBottomCenter = BoundingBoxUtils.getBottomCenter(nextBounds);
            Setting<Color> pathOutlineSetting = this.outlineSetting;
            LineMeshRenderer.addLine(previousBottomCenter, nextBottomCenter, pathOutlineSetting.getValue());
            predictionTick++;
         }
      }

      LineMeshRenderer.draw();
   }

   @EventHandler
   private void onMinecraftRunTickEvent(MinecraftRunTickEvent event) {
      if (!this.isWorldUnavailable() && !this.prepareCalculation()) {
         if (this.calculationFuture != null && this.calculationFuture.isDone()) {
            try {
               CrystalPlacementResult calculatedPlacement = this.calculationFuture.get();
               if (calculatedPlacement == null) {
                  this.placementData.set(null);
                  this.selectedDamage = 0.0F;
               } else {
                  this.placementData.set(calculatedPlacement);
                  this.selectedDamage = (float)calculatedPlacement.damage();
                  this.target = calculatedPlacement.target();
               }
            } catch (InterruptedException | ExecutionException failure) {
               failure.printStackTrace();
            }
         }

         if ((this.calculationFuture == null || this.calculationFuture.isDone() || this.calculationFuture.isCancelled())
            && this.calculationIntervalTimer.hasElapsedAndReset(CALCULATION_INTERVAL_MILLIS)) {
            this.clearInvalidTarget();
            List<LivingEntity> targets = this.targeting.snapshot();
            // A new generation makes any timed-out calculation still running stop at its next row.
            int generation = this.calculationGeneration.incrementAndGet();
            CompletableFuture<CrystalPlacementResult> scheduledCalculation = CompletableFuture.supplyAsync(
               () -> this.calculatePlacement(targets, generation), calculationExecutor
            );
            TimeUnit timeoutUnit = TimeUnit.MILLISECONDS;
            CrystalPlacementResult timeoutResult = null;
            this.calculationFuture = scheduledCalculation.completeOnTimeout(timeoutResult, 750L, timeoutUnit).exceptionally(failure -> null);
         }

         synchronized (this.predictedAttackIds) {
            for (Integer predictedId : this.predictedAttackIds) {
               int predictedEntityId = predictedId;
               int entityId = predictedEntityId;
               if (ClientAccess.argusclientMinecraft.level.getEntity(entityId) instanceof EndCrystal crystal) {
                  ((EndCrystalState)crystal).setClientAttacked(true);
               }
            }

            this.predictedAttackIds.removeIf(predictedIdx -> {
               ClientLevel levelSnapshot = ClientAccess.argusclientMinecraft.level;
               int entityIdx = predictedIdx;
               return levelSnapshot.getEntity(entityIdx) != null;
            });
         }
      }
   }

   private boolean isAttackRetryWindowActive(long retryMillis) {
      ElapsedTimer setbackTimerSnapshot = ClientContext.serverStateManager.getSetbackTimer();
      if (!setbackTimerSnapshot.hasElapsed(200L)) {
         return true;
      }

      ElapsedTimer retryTimerSnapshot = this.attackRetryTimer;
      float unscaledRetryMillis = (float)retryMillis;
      ServerStateManager tickScaleSnapshot = ClientContext.serverStateManager;
      return !retryTimerSnapshot.hasElapsed((long)(unscaledRetryMillis / tickScaleSnapshot.getTickRateScale()));
   }

   @EventHandler
   private void onPacketReceived(PacketEvent.Receive event) {
      if (event.getPacket() instanceof ClientboundAddEntityPacket spawnPacket && !this.isSwapPenaltyActive()) {
         try {
            if (this.shouldPause()) {
               return;
            }

            this.onCrystalSpawn(spawnPacket);
         } catch (Throwable var5) {
         }
      }

      if (event.getPacket() instanceof ClientboundEntityEventPacket entityEventPacket) {
         int eventIdScratch = entityEventPacket.getEventId();
         if (eventIdScratch == 3 && this.target != null) {
            eventIdScratch = ((ClientboundEntityEventPacketAccessor)entityEventPacket).getId();
            LivingEntity eventTarget = this.target;
            if (eventIdScratch == eventTarget.getId()) {
               ((ServerSideDeathState)this.target).setServerSideDead(true);
            }
         }
      }

      if (event.getPacket() instanceof ClientboundPlayerCombatKillPacket) {
         Setting<Boolean> suicideSettingSnapshot = this.forceSuicideSetting;
         Boolean disabledValue = false;
         suicideSettingSnapshot.setValue(disabledValue);
      }
   }

   @EventHandler(priority = 200)
   private void onPlayerUpdate(RotationRequestEvent event) {
      this.breakRotationRequested = false;
      this.lastAttackedBase = null;
      int lastAttackedId = this.lastAttackedCrystalId;
      Entity lastAttackedEntity = ClientAccess.argusclientMinecraft.level.getEntity(lastAttackedId);
      if (lastAttackedEntity == null || !lastAttackedEntity.isAlive()) {
         this.attackRetries = 0;
      }

      int playerTicks = ClientAccess.argusclientMinecraft.player.tickCount;
      if (playerTicks % 4 == 0) {
         long now = System.currentTimeMillis();

         while (!this.placementTimes.isEmpty()) {
            LinkedList<?> historyHeadCheck = this.placementTimes;
            if (historyHeadCheck.getFirst() == null) {
               break;
            }

            Long oldestPlacement = this.placementTimes.getFirst();
            long placementAgeMillis = now - oldestPlacement;
            if (placementAgeMillis <= 1000L) {
               break;
            }

            this.placementTimes.remove();
         }
      }

      Class<KillEffectsModule> killEffectsType = KillEffectsModule.class;
      KillEffectsModule killEffectsSnapshot = (KillEffectsModule)ClientContext.moduleRegistry.getModule(killEffectsType);
      if (killEffectsSnapshot != null && killEffectsSnapshot.isToggled()) {
         LivingEntity effectTargetSnapshot = this.target;
         if (!this.isInvalidTarget(effectTargetSnapshot) && this.target instanceof Player player) {
            killEffectsSnapshot.trackTarget(player);
         }
      }

      if (!this.shouldPause()) {
         this.runBreakCycle();
         ElapsedTimer placeTimerSnapshot = this.placeTimer;
         long placeDelayMillis = this.placeDelaySetting.getValue().intValue();
         if (placeTimerSnapshot.hasElapsed(placeDelayMillis)) {
            Runnable placementCallback = this::runPlacementCycle;
            LockUtils.runWithSharedLock(placementCallback);
         }

         Vec3 rotationPoint = (Vec3)this.rotationTarget.get();
         if (this.rotateSetting.getValue() && rotationPoint != null) {
            Vec2 movementInput = new Vec2(
               readForwardMovementInput(),
               compareMovementKeys(ClientAccess.argusclientMinecraft.options.keyLeft.isDown(), ClientAccess.argusclientMinecraft.options.keyRight.isDown())
            );
            boolean retryWindowActive = this.isAttackRetryWindowActive(500L);
            boolean rotationFollowup = !RotationManager.isSilentRotationMode() || retryWindowActive || movementInput.lengthSquared() < 0.01;
            if (PlayerStateUtils.isFallFlying()) {
               rotationFollowup = false;
            }

            float[] rotations = RotationManager.getRotationsToPosition(rotationPoint);
            ClientContext.rotationManager.requestRotation(rotations, 100, rotationFollowup);
            if (RotationManager.isSilentRotationMode() && rotationFollowup && !retryWindowActive) {
               ClientContext.rotationManager.requestRotation(rotations, 101, false);
            }

            if (this.rotationTimer.hasElapsed(400L)) {
               this.placementData.lazySet(null);
               this.rotationTarget.lazySet(null);
            }
         }
      }
   }

   @EventHandler
   private void onLocalPlayerSendPositionEvent(LocalPlayerSendPositionEvent event) {
      if (event.getStage() != EventStage.Post) {
         this.targeting.refresh();
         this.updateEyePosition();
         this.updateIgnoredBlocks();
         AuraModule auraSnapshot = aura;
         this.auraHasAttackTarget = auraSnapshot.isTargetWithinAttackRange();
         this.spawnFollowupQueued = false;
         this.quiverThreatActive = this.isQuiverThreatPresent();
         if (this.forceSuicideSetting.getValue() && this.forceSuicideNoArmorSetting.getValue()) {
            this.throwUnboundArmor();
         }

         if (this.obsidianDropNearTarget) {
            this.obsidianDropNearTarget = false;
         } else {
            this.obsidianDropNearTarget = false;
            LivingEntity proximityTarget = this.targeting.findProximityTarget();
            if (proximityTarget == null) {
               return;
            }

            if (this.targeting.hasObsidianDropNear(proximityTarget)) {
               this.obsidianDropNearTarget = true;
            }
         }
      }
   }

   public void completeCalculation() {
      this.calculationGeneration.incrementAndGet();
      CompletableFuture<CrystalPlacementResult> pending = this.calculationFuture;
      if (pending != null && !pending.isDone() && !pending.isCancelled()) {
         pending.complete(null);
      }
   }

   public static void bindReferencedModules(ModuleRegistry modules) {
      speedMine = (SpeedMineModule)modules.getModule(SpeedMineModule.class);
      antiCheat = (AntiCheatModule)modules.getModule(AntiCheatModule.class);
      anchorAura = (AnchorAuraModule)modules.getModule(AnchorAuraModule.class);
      aura = (AuraModule)modules.getModule(AuraModule.class);
      autoExp = (AutoExpModule)modules.getModule(AutoExpModule.class);
   }

   public AutoCrystalModule() {
      super("AutoCrystal", "Kills people with end crystals (if you're good).", ModuleCategory.COMBAT, "crystalaura", "ca", "ac");
      this.bossesSetting = this.add(new BooleanSetting("Bosses", false).dependsOn(this.targetingSetting));
      this.forceSuicideSetting = this.add(new BooleanSetting("ForceSuicide", false).markVisibilityDependency().dependsOn(this.targetingSetting));
      this.forceSuicideNoArmorSetting = this.add(new BooleanSetting("NoArmor", false).dependsOn(this.targetingSetting, this.forceSuicideSetting));
      this.assumeInvincibilitySetting = this.add(new BooleanSetting("AssumeInvincibility", false).markVisibilityDependency().dependsOn(this.targetingSetting));
      this.assumeSelfInvincibilitySetting = this.add(new BooleanSetting("Self", true).dependsOn(this.assumeInvincibilitySetting, this.targetingSetting));
      this.invincibilityTimeoutSetting = this.add(
         new NumberSetting<>("Timeout", 0.0F, 0.0F, 4.0F).withUnit("s").dependsOn(this.assumeInvincibilitySetting, this.targetingSetting)
      );
      this.miscSetting = this.add(new BooleanSetting("Misc", false).markAsGroup());
      this.autoSwapSetting = this.add(new EnumSetting<>("AutoSwap", ItemSwapMode.NONE).dependsOn(this.miscSetting));
      this.silentSetting = this.add(
         new EnumSetting<>("Silent", AutoCrystalSilentChoice.NORMAL, unused -> this.autoSwapSetting.getValue() == ItemSwapMode.SILENT)
            .dependsOn(this.miscSetting)
      );
      this.swapBackSetting = this.add(
         new BooleanSetting("SwapBack", true, unused -> this.autoSwapSetting.getValue() == ItemSwapMode.NORMAL).dependsOn(this.miscSetting)
      );
      this.strictTimingSetting = this.add(
         new BooleanSetting(
               "StrictTiming",
               false,
               unused -> this.autoSwapSetting.getValue() == ItemSwapMode.SILENT && this.silentSetting.getValue() == AutoCrystalSilentChoice.ALTERNATIVE
            )
            .dependsOn(this.miscSetting)
      );
      this.swapDelaySetting = this.add(
         new NumberSetting<>("SwapDelay", 0.0F, 0.0F, 10.0F, unused -> this.autoSwapSetting.getValue() == ItemSwapMode.NORMAL).dependsOn(this.miscSetting)
      );
      this.swapPenaltySetting = this.add(new NumberSetting<>("SwapPenalty", 0.0F, 0.0F, 10.0F).dependsOn(this.miscSetting));
      this.multipointSetting = this.add(new BooleanSetting("Multipoint", false).dependsOn(this.miscSetting));
      this.targeting = new AutoCrystalTargeting(this);
      this.damageRenderer = new CrystalDamageRenderer(this);
      this.crystalHoldingTimer = new ElapsedTimer();
      this.placementData = new AtomicReference();
      this.rotationTarget = new AtomicReference();
      this.placementGate = new AtomicBoolean();
      this.rotationTimer = new ElapsedTimer();
      this.placeTimer = new ElapsedTimer();
      this.breakTimer = new ElapsedTimer();
      this.swapDelayTimer = new ElapsedTimer();
      this.swapPenaltyTimer = new ElapsedTimer();
      this.sequentialPlacementTimer = new ElapsedTimer();
      this.attackRetryTimer = new ElapsedTimer();
      this.basePlaceTimer = new ElapsedTimer();
      this.placementTimes = new LinkedList<>();
      this.predictedAttackIds = Collections.synchronizedList(new ArrayList<>());
      this.ignoredBlocks = new HashSet();
      this.lastAttackedCrystalId = -1;
      FeatureBoxRenderer boxManagerSnapshot = ClientContext.featureBoxRenderer;
      FeatureBoxRenderer.RenderConfiguration boxSettings = new FeatureBoxRenderer.RenderConfiguration(
         this, this.fillSetting, this.outlineSetting, this.lineWidthSetting, this.fadeTimeSetting, this.multiFadeSetting, this.fadeSetting, 1000
      );
      boxManagerSnapshot.register(boxSettings);
      CrystalDamageRenderer damageListener = this.damageRenderer;
      EventBus eventBus = ClientAccess.eventBus;
      eventBus.subscribe(damageListener);
      this.placeDelaySetting.setConfigName("PlaceDelay");
      this.placeRangeSetting.setConfigName("PlaceRange");
      this.placeWallRangeSetting.setConfigName("PlaceWallRange");
      this.minPlaceDamageSetting.setConfigName("MinPlaceDamage");
      this.maxPlaceSelfDamageSetting.setConfigName("MaxSelfPlace");
      this.breakDelaySetting.setConfigName("HitDelay");
      this.breakRangeSetting.setConfigName("BreakRange");
      this.breakWallRangeSetting.setConfigName("BreakWallRange");
      this.minBreakDamageSetting.setConfigName("MinBreakDamage");
      this.maxBreakSelfDamageSetting.setConfigName("MaxSelfHit");
      this.rotatePlaceSetting.setConfigName("PlaceRotate");
      this.rotateBreakSetting.setConfigName("BreakRotate");
      this.facePlaceHealthSetting.setConfigName("FacePlaceHealth");
      this.facePlaceArmorThresholdSetting.setConfigName("FacePlaceArmor");
      this.predictionSetting.setConfigName("ExtraTicks");
      this.invincibilityTimeoutSetting.setConfigName("InvincTimeout");
      this.basePlaceDelaySetting.setConfigName("BaseDelay");
      Setting<Integer> minimumAgeSetting = this.ticksExistedSetting;
      Predicate minimumAgePredicate = unused -> this.instantSetting.getValue() != AutoCrystalInstantChoice.ALWAYS;
      minimumAgeSetting.addVisibilityPredicate(minimumAgePredicate);
   }

   private static float readForwardMovementInput() {
      KeyMapping forwardKey = ClientAccess.argusclientMinecraft.options.keyUp;
      boolean forward = forwardKey.isDown();
      KeyMapping backKey = ClientAccess.argusclientMinecraft.options.keyDown;
      return compareMovementKeys(forward, backKey.isDown());
   }
}
