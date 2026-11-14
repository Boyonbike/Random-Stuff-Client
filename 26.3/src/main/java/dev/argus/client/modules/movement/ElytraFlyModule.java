/*
 * ─────────────────────────────────────────────
 * Licensed under the MIT License.
 *
 * Part of Argus Client.
 * Provided "as is", without warranty of any kind.
 * ─────────────────────────────────────────────
 */

package dev.argus.client.modules.movement;


import dev.argus.client.ClientAccess;
import dev.argus.client.ClientContext;
import dev.argus.client.events.LivingEntityRotationEvent;
import dev.argus.client.events.PacketEvent;
import dev.argus.client.events.RotationRequestEvent;
import dev.argus.client.events.EventHandler;
import dev.argus.client.events.FireworkRocketEntityTickEvent;
import dev.argus.client.events.KeyboardInputTickEvent;
import dev.argus.client.events.LocalPlayerAiStepEvent;
import dev.argus.client.events.LocalPlayerMoveEvent;
import dev.argus.client.events.LocalPlayerSendPositionEvent;
import dev.argus.client.events.LocalPlayerTickEvent;
import dev.argus.client.events.LocalPlayerPreTickEvent;
import dev.argus.client.events.MinecraftDisconnectEvent;
import dev.argus.client.events.MinecraftRunTickEvent;
import dev.argus.client.events.MultiPlayerGameModeUseItemEvent;
import dev.argus.client.events.SoundEnginePlayEvent;
import dev.argus.client.integrations.ProtocolCompatibility;
import dev.argus.client.integrations.ProtocolVersionAccess;
import dev.argus.client.managers.RotationManager;
import dev.argus.client.utils.movement.ControlElytraFlyHandler;
import dev.argus.client.managers.InventoryStateManager;
import dev.argus.client.utils.movement.VanillaElytraFlyHandler;
import dev.argus.client.utils.movement.VolytraElytraFlyHandler;
import dev.argus.client.utils.ElapsedTimer;
import dev.argus.client.utils.network.PacketSender;
import dev.argus.client.render.FontRenderer;
import dev.argus.client.utils.entity.EntityUtils;
import dev.argus.client.utils.movement.BounceElytraFlyHandler;
import dev.argus.client.settings.SettingLabelCondition;
import dev.argus.client.utils.movement.NewGrimElytraFlyHandler;
import dev.argus.client.utils.movement.ElytraFlyModeHandler;
import dev.argus.client.utils.movement.BoostElytraFlyHandler;
import dev.argus.client.utils.entity.PlayerStateUtils;
import dev.argus.client.utils.inventory.InventoryUtils;
import dev.argus.client.managers.ModuleRegistry;
import dev.argus.client.modules.Module;
import dev.argus.client.modules.ModuleCategory;
import dev.argus.client.modules.client.AntiCheatModule;
import dev.argus.client.modules.misc.MiddleClickModule;
import dev.argus.client.settings.BooleanSetting;
import dev.argus.client.settings.EnumSetting;
import dev.argus.client.settings.NamedEntry;
import dev.argus.client.settings.NumberSetting;
import dev.argus.client.settings.Setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.argus.mixin.MinecraftAccessor;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

public class ElytraFlyModule extends Module {
   private static MiddleClickModule middleClickModule;
   private static ObstaclePasserModule obstaclePasserModule;
   private static NoSlowModule noSlowModule;
   private static AntiCheatModule antiCheatModule;
   public static boolean hook = false;
   public final Setting<ModeChoice> modeSetting;
   public final Setting<TakeoffChoice> takeoffSetting;
   public final Setting<Boolean> responsiveSetting;
   public final Setting<Boolean> factorizeSetting;
   public final Setting<Boolean> alwaysSetting;
   public final Setting<Boolean> nCPBoostSetting;
   public final Setting<Float> minBoostSetting;
   public final Setting<Float> limitSetting;
   public final Setting<Boolean> autoJumpSetting;
   public final Setting<Boolean> inLavaSetting;
   public final Setting<Float> speedSetting;
   public final Setting<Integer> minYSetting;
   public final Setting<Integer> heightSetting;
   public final Setting<Boolean> useFireworkSetting;
   public final Setting<Float> flightSpeedSetting;
   public final Setting<VerticalChoice> verticalSetting;
   public final Setting<Float> vSpeedSetting;
   public final Setting<Float> vPitchSetting;
   public final Setting<Float> glideSetting;
   public final Setting<Boolean> spoofPitchSetting;
   public final Setting<Float> spoofedPitchSetting;
   public final Setting<Boolean> antiAfkSetting;
   public final Setting<Boolean> packetVerticalSetting;
   public final Setting<Boolean> accelerateSetting;
   public final Setting<Float> verAccelMinSetting;
   public final Setting<Float> accelMinSetting;
   public final Setting<Float> accelTimeSetting;
   public final Setting<Float> startSetting;
   public final Setting<Float> strictSpeedSetting;
   public final Setting<Integer> deployTimeSetting;
   public final Setting<Boolean> pitchLockSetting;
   public final Setting<Boolean> autoPitchSetting;
   public final Setting<Boolean> silentSetting;
   public final Setting<Float> pitchSetting;
   public final Setting<Boolean> packetSetting;
   public final Setting<Boolean> diagonalSetting;
   public final Setting<Integer> maxBoostSetting;
   public final Setting<Boolean> compensateSetting;
   public final Setting<StopMotionChoice> stopMotionSetting;
   public final Setting<Boolean> fallChestplateSetting;
   public final Setting<Boolean> responsiveDescendSetting;
   public final Setting<Boolean> muteElytraSetting;
   public final Setting<InfDurabilityChoice> infDurabilitySetting;
   private final EnumSelectionMap<ElytraFlyModeHandler> modeHandlers;
   public final ElapsedTimer fireworkUseTimer;
   public final ElapsedTimer takeoffTimer;
   private final ElapsedTimer durabilitySwapTimer;
   public final ElapsedTimer enableTimer;
   private final ElapsedTimer elytraSearchTimer;
   public boolean fireworkUsePending;
   private boolean replayingFireworkUse;
   private boolean jumpPressed;
   private boolean sneakPressed;
   private boolean takeoffRequested;
   private TakeoffJumpPhase takeoffJumpPhase;

   @Override
   public String getInfo() {
      try {
         return FontRenderer.capitalize((this.modeSetting.getValue()).getName());
      } catch (Exception failure) {
         return null;
      }
   }

   @Override
   public void onEnable() {
      this.enableTimer.clear();
      if (!this.isWorldUnavailable()) {
         this.getActiveMode().onEnable();
      }
   }

   @Override
   public void onDisable() {
      try {
         if (!this.isWorldUnavailable()) {
            this.getActiveMode().onDisable();
         }
      } finally {
         this.takeoffRequested = false;
         this.cancelTakeoff();
         this.fireworkUseTimer.reset();
      }
   }

   public ElytraFlyModeHandler getActiveMode() {
      return this.modeHandlers.getSelected();
   }

   private void startFallFlyingWithPacket() {
      if (this.enableTimer.hasElapsed(500L)
         && !this.infDurabilitySetting.isVisible() && !this.isBouncePacketMode()) {
         return;
      }
      PacketSender.sendStartFallFlying();
      ClientAccess.argusclientMinecraft.player.startFallFlying();
   }

   private boolean isBouncePacketMode() {
      return this.modeSetting.getValue() == ModeChoice.BOUNCE && this.packetSetting.getValue();
   }

   public boolean tryStartNormalFlight() {
      if (!(this.infDurabilitySetting.getValue()).isDisabled()
         || this.isBouncePacketMode() || ClientContext.baritoneBridge.isPathingOrCalculating()
         || obstaclePasserModule != null && obstaclePasserModule.isPassingObstacles()) {
         return false;
      }
      if (!this.canStartFlightFromJump()) {
         return false;
      }
      ClientAccess.argusclientMinecraft.execute(() -> {
         ClientAccess.argusclientMinecraft.player.startFallFlying();
         PacketSender.sendStartFallFlying();
      });
      return true;
   }

   public void updateTakeoff() {
      this.takeoffRequested = true;
   }

   private void processTakeoff() {
      if (this.modeSetting.getValue() == ModeChoice.BOUNCE) {
         this.cancelTakeoff();
         return;
      }
      LocalPlayer player = ClientAccess.argusclientMinecraft.player;
      if (ClientContext.baritoneBridge.isPathingOrCalculating() || !this.isElytraEquipped()
         || player.isFallFlying() || EntityUtils.hasCollisionBelow(player)
         || this.takeoffSetting.getValue() == TakeoffChoice.NONE && !this.takeoffRequested) {
         this.cancelTakeoff();
         return;
      }
      if (player.isInWater()) {
         this.cancelTakeoff();
         return;
      }
      if (this.takeoffSetting.getValue() == TakeoffChoice.STRICT) {
         ClientContext.tickRateController.requestMultiplier(this, 0.1F);
      } else {
         ClientContext.tickRateController.releaseMultiplier(this);
      }
      long deployDelayMillis = this.modeSetting.getValue() == ModeChoice.STRICT
         ? this.deployTimeSetting.getValue() : 100L;
      if (!this.takeoffTimer.hasElapsedAndReset(deployDelayMillis)) {
         return;
      }
      ProtocolVersionAccess targetVersion = ProtocolCompatibility.getTargetVersion();
      boolean legacyProtocol = targetVersion != null && targetVersion.olderEq_1_21_5();
      if (!legacyProtocol && RotationManager.isMovementSyncEnabled()) {
         Input lastInput = ClientContext.playerStateTracker.getLastSentInput();
         this.takeoffJumpPhase = lastInput != null && lastInput.jump()
            ? TakeoffJumpPhase.RELEASE : TakeoffJumpPhase.PRESS;
      } else {
         player.startFallFlying();
         PacketSender.sendStartFallFlying();
      }
      this.takeoffRequested = false;
   }

   private void cancelTakeoff() {
      this.takeoffJumpPhase = null;
      ClientContext.tickRateController.releaseMultiplier(this);
   }

   @EventHandler
   private void onDisconnect(MinecraftDisconnectEvent event) {
      this.takeoffRequested = false;
      this.cancelTakeoff();
   }

   public boolean isElytraEquipped() {
      return PlayerStateUtils.isWearingElytra(ClientAccess.argusclientMinecraft.player);
   }

   private void updateDurabilitySwap() {
      if (FireworksModule.temporaryElytraSwapActive.get()) {
         return;
      }
      if (this.getActiveMode() instanceof BounceElytraFlyHandler bounce && bounce.isDiagonalPacketPending()) {
         return;
      }
      int elytraSlot = InventoryUtils.findHotbarSlot(Items.ELYTRA);
      boolean inventorySwapRequired = elytraSlot == -1;
      if (inventorySwapRequired) {
         elytraSlot = InventoryUtils.findMenuSlot(Items.ELYTRA);
      }
      InfDurabilityChoice durabilityMode = this.infDurabilitySetting.getValue();
      if (!this.shouldSwapForDurability(durabilityMode, inventorySwapRequired)
         || !this.durabilitySwapTimer.hasElapsed(200L) || !this.isDurabilityBypassActive()
         || obstaclePasserModule != null && obstaclePasserModule.isPassingObstacles()) {
         return;
      }
      int clicksRequired = inventorySwapRequired ? 4 : 2;
      if (InventoryStateManager.wouldReachClickLimit(ClientContext.inventoryStateManager, clicksRequired)) {
         return;
      }
      if (this.isElytraEquipped()) {
         ClientAccess.argusclientMinecraft.player.startFallFlying();
         PacketSender.sendStartFallFlying();
         return;
      }
      if (elytraSlot == -1) {
         return;
      }
      int originalMenuSlot = elytraSlot;
      if (inventorySwapRequired) {
         elytraSlot = 0;
         InventoryUtils.clickSlot(originalMenuSlot, 0, ContainerInput.SWAP);
      }

      InventoryUtils.clickSlot(6, elytraSlot, ContainerInput.SWAP);
      try {
         this.startFallFlyingWithPacket();
         if (this.fireworkUsePending && this.fireworkUseTimer.hasElapsedAndReset(150L)) {
            this.replayingFireworkUse = true;
            try {
               if (middleClickModule.isToggled()) {
                  middleClickModule.launchFirework();
               } else {
                  ((MinecraftAccessor)ClientAccess.argusclientMinecraft).interact();
               }
            } finally {
               this.replayingFireworkUse = false;
            }
            this.fireworkUsePending = false;
            this.durabilitySwapTimer.reset();
         }
      } finally {
         InventoryUtils.clickSlot(6, elytraSlot, ContainerInput.SWAP);
         if (inventorySwapRequired) {
            InventoryUtils.clickSlot(originalMenuSlot, 0, ContainerInput.SWAP);
         }
      }
   }

   public boolean isFallFlyingWithoutOverride() {
      boolean previousHook = hook;
      hook = true;
      try {
         return ClientAccess.argusclientMinecraft.player.isFallFlying();
      } finally {
         hook = previousHook;
      }
   }

   private boolean isInClimbableBlock() {
      LocalPlayer player = ClientAccess.argusclientMinecraft.player;
      return !player.isSpectator() && player.getInBlockState().is(BlockTags.CLIMBABLE);
   }

   public boolean isManualVerticalControlActive() {
      return !this.isWorldUnavailable() && (this.jumpPressed || this.sneakPressed)
         && this.verticalSetting.getValue() == VerticalChoice.MANUAL
         && ClientAccess.argusclientMinecraft.player.isFallFlying() && this.isElytraEquipped();
   }

   public boolean isItemUseSlowingFlight() {
      return !antiCheatModule.is2b2tEnabled() && ClientAccess.argusclientMinecraft.player.isUsingItem()
         && (!noSlowModule.isToggled() || !noSlowModule.itemsSetting.getValue());
   }

   public boolean canStartFlightFromJump() {
      if (ClientContext.baritoneBridge.isPathingOrCalculating()
         || obstaclePasserModule != null && obstaclePasserModule.isPassingObstacles()) {
         return false;
      }
      LocalPlayer player = ClientAccess.argusclientMinecraft.player;
      return !player.getAbilities().flying && !player.isPassenger() && !this.isInClimbableBlock()
         && this.isElytraEquipped() && !player.isInWater() && !player.hasEffect(MobEffects.LEVITATION)
         && player.input.keyPresses.jump();
   }

   @EventHandler(priority = -300)
   private void onLocalPlayerPreTickEvent(LocalPlayerPreTickEvent event) {
      this.updateDurabilitySwap();
      this.getActiveMode().handlePrePlayerTick(event);
   }

   @EventHandler
   private void onFireworkRocketEntityTickEvent(FireworkRocketEntityTickEvent event) {
      this.getActiveMode().handleFireworkTick(event);
   }

   @EventHandler
   private void onSoundEnginePlayEvent(SoundEnginePlayEvent event) {
      if (this.isDurabilityBypassActive() && event.getSoundInstance() != null) {
         Identifier identifier = event.getSoundInstance().getIdentifier();
         if (identifier != null && identifier.toString().contains("item.armor.equip")) {
            event.cancel();
         }
      }
   }

   @EventHandler
   private void onLocalPlayerAiStepEvent(LocalPlayerAiStepEvent event) {
      if (this.isFallFlyingWithoutOverride() && this.modeSetting.getValue() == ModeChoice.BOUNCE) {
         ClientAccess.argusclientMinecraft.player.setSprinting(true);
      }
   }

   @EventHandler
   private void onLocalPlayerTickEvent(LocalPlayerTickEvent event) {
      this.getActiveMode().handlePlayerTick(event);
   }

   private boolean shouldSwapForDurability(InfDurabilityChoice durabilityMode, boolean inventorySwapRequired) {
      if (!PlayerStateUtils.isLocalWearingElytra() && !InventoryUtils.getCarriedStack().isEmpty()) {
         return false;
      }
      if (this.isBouncePacketMode() && PlayerStateUtils.isLocalWearingElytra()) {
         return true;
      }
      return switch (durabilityMode) {
         case NONE -> false;
         case PLAIN -> true;
         case GRIM -> !PlayerStateUtils.isFallFlying();
         case _2B2T -> {
            if (PlayerStateUtils.isLocalWearingElytra()) {
               yield true;
            }
            if (inventorySwapRequired) {
               yield false;
            }
            if (this.modeSetting.getValue() == ModeChoice.BOUNCE && this.pitchLockSetting.getValue()
               && this.pitchSetting.isAtMaximum()) {
               int airborneTicks = ClientContext.playerStateTracker.getAirborneTicks();
               yield ClientAccess.argusclientMinecraft.player.onGround() || airborneTicks == 0
                  || airborneTicks == 1 || airborneTicks == 6;
            }
            int tick = ClientAccess.argusclientMinecraft.player.tickCount;
            yield tick % 2 != 0 && tick % 20 != 1;
         }
      };
   }

   @EventHandler
   private void onConnectionSendPacketEvent(PacketEvent.Send event) {
      if (event.getPacket() instanceof ServerboundPlayerActionPacket packet
         && packet.getAction() == Action.RELEASE_USE_ITEM && antiCheatModule.is2b2tEnabled()) {
         this.durabilitySwapTimer.reset();
      }
      this.getActiveMode().handleOutgoingPacket(event);
   }

   @EventHandler(priority = -200)
   private void onLocalPlayerMoveEvent(LocalPlayerMoveEvent event) {
      this.getActiveMode().handleMovement(event);
   }

   @EventHandler
   private void handleTravelRotation(LivingEntityRotationEvent event) {
      this.getActiveMode().handleTravelRotation(event);
   }

   @EventHandler
   private void onMinecraftRunTickEvent(MinecraftRunTickEvent event) {
      this.getActiveMode().handleClientTick(event);
   }

   public boolean isPacketFlightActive() {
      if (this.isWorldUnavailable() || ClientAccess.argusclientMinecraft.player.input == null) {
         return false;
      }
      LocalPlayer player = ClientAccess.argusclientMinecraft.player;
      return !(this.packetVerticalSetting.getValue() && player.input.keyPresses.jump())
         && this.isToggled() && !player.onGround() && this.modeSetting.getValue() == ModeChoice.PACKET
         && this.isElytraEquipped();
   }

   public boolean isDurabilityBypassActive() {
      if (this.isWorldUnavailable() || ClientContext.baritoneBridge.isPathingOrCalculating() || hook) {
         return false;
      }
      ModeChoice mode = this.modeSetting.getValue();
      if (EntityUtils.hasCollisionBelow(ClientAccess.argusclientMinecraft.player)
         && (mode == ModeChoice.NEWGRIM || mode == ModeChoice.CONTROL)) {
         return false;
      }
      int elytraSlot = InventoryUtils.findHotbarSlot(Items.ELYTRA);
      if (elytraSlot == -1) {
         elytraSlot = InventoryUtils.findMenuSlot(Items.ELYTRA);
      } else {
         this.elytraSearchTimer.reset();
      }
      if (elytraSlot == -1 && this.elytraSearchTimer.hasElapsed(150L)
         && !PlayerStateUtils.isLocalWearingElytra()) {
         return false;
      }
      boolean durabilityEnabled = !(this.infDurabilitySetting.getValue()).isDisabled()
         && this.infDurabilitySetting.isVisible();
      return this.isToggled() && (durabilityEnabled || this.isBouncePacketMode());
   }

   @EventHandler
   private void onKeyboardInputTickEvent(KeyboardInputTickEvent event) {
      this.jumpPressed = event.getInput().keyPresses.jump();
      this.sneakPressed = event.getInput().keyPresses.shift();
      if (!this.isElytraEquipped()) {
         this.cancelTakeoff();
         return;
      }
      if (this.modeSetting.getValue() == ModeChoice.VOLYTRA) {
         this.cancelTakeoff();
      } else if (this.modeSetting.getValue() != ModeChoice.PACKET
         || this.packetVerticalSetting.getValue() && this.jumpPressed) {
         this.processTakeoff();
      } else {
         this.cancelTakeoff();
      }
      if (this.takeoffJumpPhase != null) {
         switch (this.takeoffJumpPhase) {
            case RELEASE -> {
               ClientContext.inputOverrides.requestJump(false);
               this.takeoffJumpPhase = TakeoffJumpPhase.PRESS;
            }
            case PRESS -> {
               ClientContext.inputOverrides.requestJump(true);
               this.takeoffJumpPhase = TakeoffJumpPhase.FINISH;
            }
            case FINISH -> {
               ClientContext.inputOverrides.requestJump(false);
               this.takeoffJumpPhase = null;
            }
         }
      }
   }

   private enum TakeoffJumpPhase {
      RELEASE, PRESS, FINISH
   }

   @EventHandler
   private void onLocalPlayerSendPositionEvent(LocalPlayerSendPositionEvent event) {
      this.getActiveMode().handleSendPosition(event);
   }

   @EventHandler
   private void onUseItem(MultiPlayerGameModeUseItemEvent event) {
      if (!this.isDurabilityBypassActive() || this.replayingFireworkUse) {
         return;
      }
      ItemStack heldStack = ClientAccess.argusclientMinecraft.player.getItemInHand(event.getHand());
      if (heldStack.is(Items.FIREWORK_ROCKET) && !PlayerStateUtils.isLocalWearingElytra()) {
         this.fireworkUsePending = true;
         event.cancel();
      }
   }

   @EventHandler
   private void handleRotationManagerEvent(RotationRequestEvent event) {
      this.getActiveMode().handleRotationManagerEvent(event);
   }

   @EventHandler
   private void onIncomingPacket(PacketEvent.Receive event) {
      if (event.getPacket() instanceof ClientboundAddEntityPacket packet && packet.getType() == EntityTypes.FIREWORK_ROCKET) {
         Vec3 rocketPosition = new Vec3(packet.getX(), packet.getY(), packet.getZ());
         if (rocketPosition.distanceToSqr(ClientAccess.argusclientMinecraft.player.position()) <= 36.0D) {
            this.durabilitySwapTimer.reset();
         }
      }
      this.getActiveMode().handleIncomingPacket(event);
   }

   @Override
   public JsonElement toJson() {
      JsonElement json = super.toJson();
      if (json instanceof JsonObject object && this.volytra != null) object.add("autonavWaypoints", this.volytra.autonavWaypointsJson());
      return json;
   }

   @Override
   public void fromJson(JsonElement json) {
      // Partial module presets without saved destinations must not erase the ones already saved.
      if (json instanceof JsonObject object && object.has("autonavWaypoints") && this.volytra != null) {
         this.volytra.loadAutonavWaypoints(object.get("autonavWaypoints"));
      }
      // Configs saved before the Volytra tweak settings existed predate the new default.
      // Migrate those once to Volytra Fly; later configs keep the user's explicit mode.
      boolean legacyConfig = true;
      if (json instanceof JsonObject moduleJson && moduleJson.get("settings") instanceof JsonObject settings) {
         legacyConfig = !settings.has("volytraAdaptiveTurning") && !settings.has("argusAdaptiveTurning");
         // The Argus tweak settings were renamed; carry values saved under the old keys over to the new ones.
         JsonObject copy = moduleJson.deepCopy();
         copy.add("settings", VolytraElytraFlyHandler.migrateRenamedKeys(settings));
         json = copy;
      }

      super.fromJson(json);
      if (legacyConfig) {
         this.modeSetting.setValue(ModeChoice.VOLYTRA);
      }
   }

   public static void bindModuleReferences(ModuleRegistry registry) {
      middleClickModule = (MiddleClickModule)registry.getModule(
         MiddleClickModule.class
      );
      obstaclePasserModule = (ObstaclePasserModule)registry.getModule(
         ObstaclePasserModule.class
      );
      noSlowModule = (NoSlowModule)registry.getModule(
         NoSlowModule.class
      );
      antiCheatModule = (AntiCheatModule)registry.getModule(
         AntiCheatModule.class
      );
      BoostElytraFlyHandler.bindModuleReferences(registry);
      ControlElytraFlyHandler.bindModuleReferences(registry);
      BounceElytraFlyHandler.bindModuleReferences(registry);
      NewGrimElytraFlyHandler.bindModuleReferences(registry);
      VanillaElytraFlyHandler.bindModuleReferences(registry);
   }

   public ElytraFlyModule() {
      super("ElytraFly", "Turns you into a block game boeing. Volytra Fly mode ported from Volizray's VolytraFly (CC0).", ModuleCategory.MOVEMENT, new String[]{"elytraflight"});
      this.modeSetting = this.add(new EnumSetting<>("Mode", ElytraFlyModule.ModeChoice.VOLYTRA));
      Predicate<Object> elytraFlyTakeoffPredicate = ignored -> this.modeSetting.getValue() != ModeChoice.PACKET
         && this.modeSetting.getValue() != ModeChoice.VOLYTRA;
      this.takeoffSetting = this.add(new EnumSetting<>("Takeoff", ElytraFlyModule.TakeoffChoice.PLAIN, elytraFlyTakeoffPredicate));
      Predicate<Object> boostVisibility = ignored -> this.modeSetting.getValue() == ModeChoice.BOOST;
      this.responsiveSetting = this.add(new BooleanSetting("Responsive", true, boostVisibility));
      this.factorizeSetting = this.add(new BooleanSetting("Factorize", false, boostVisibility));
      this.alwaysSetting = this.add(new BooleanSetting("Always", false, boostVisibility));
      this.nCPBoostSetting = this.add(new BooleanSetting("NCPBoost", false, boostVisibility));
      this.minBoostSetting = this.add(new NumberSetting<>("MinBoost", 1.5F, 0.0F, 5.0F, boostVisibility));
      this.limitSetting = this.add(new NumberSetting<>("Limit", 500.0F, 20.0F, 500.0F, boostVisibility).withUnit("km/h"));
      this.autoJumpSetting = this.add(new BooleanSetting("AutoJump", false, boostVisibility));
      this.inLavaSetting = this.add(new BooleanSetting("InLava", true, boostVisibility).markVisibilityDependency());
      Predicate<Object> lavaSpeedVisibility = ignored -> this.modeSetting.getValue() == ModeChoice.BOOST && this.inLavaSetting.isExpanded();
      this.speedSetting = this.add(new NumberSetting<>("Speed", 1.0F, 0.0F, 2.0F, lavaSpeedVisibility));
      Predicate<Object> vanillaVisibility = ignored -> this.modeSetting.getValue() == ModeChoice.VANILLA;
      this.minYSetting = this.add(new NumberSetting<>("MinY", 100, 0, 500, vanillaVisibility));
      this.heightSetting = this.add(new NumberSetting<>("Height", 35, 20, 150, vanillaVisibility));
      this.useFireworkSetting = this.add(new BooleanSetting("UseFirework", false, vanillaVisibility));
      Predicate<Object> elytraFlySpeedPredicate = ignored -> this.modeSetting.getValue() == ModeChoice.CONTROL
         || this.modeSetting.getValue() == ModeChoice.PACKET;
      this.flightSpeedSetting = this.add(new NumberSetting<>("Speed", 1.6F, 0.1F, 10.0F, elytraFlySpeedPredicate));
      Predicate<Object> controlVisibility = ignored -> this.modeSetting.getValue() == ModeChoice.CONTROL;
      this.verticalSetting = this.add(new EnumSetting<>("Vertical", ElytraFlyModule.VerticalChoice.NONE, controlVisibility));
      Predicate<Object> elytraFlyVSpeedPredicate = ignored -> this.verticalSetting.getValue() == ElytraFlyModule.VerticalChoice.PLAIN
         && this.modeSetting.getValue() == ModeChoice.CONTROL;
      this.vSpeedSetting = this.add(new NumberSetting<>("VSpeed", 0.6F, 0.0F, 15.0F, elytraFlyVSpeedPredicate));
      Predicate<Object> elytraFlyVPitchPredicate = ignored -> this.verticalSetting.getValue() == ElytraFlyModule.VerticalChoice.STRICT
         && this.modeSetting.getValue() == ModeChoice.CONTROL;
      this.vPitchSetting = this.add(new NumberSetting<>("VPitch", 45.0F, 0.0F, 90.0F, elytraFlyVPitchPredicate));
      this.glideSetting = this.add(new NumberSetting<>("Glide", 0.0F, 0.0F, 5.0F, elytraFlySpeedPredicate));
      this.spoofPitchSetting = this.add(new BooleanSetting("SpoofPitch", false, controlVisibility).markVisibilityDependency());
      Predicate<Object> spoofedPitchVisibility = ignored -> this.modeSetting.getValue() == ModeChoice.CONTROL && this.spoofPitchSetting.isExpanded();
      this.spoofedPitchSetting = this.add(new NumberSetting<>("Pitch", 0.5F, -90.0F, 90.0F, spoofedPitchVisibility));
      Predicate<Object> packetVisibility = ignored -> this.modeSetting.getValue() == ModeChoice.PACKET;
      this.antiAfkSetting = this.add(new BooleanSetting("AntiAfk", true, packetVisibility));
      this.packetVerticalSetting = this.add(new BooleanSetting("Vertical", false, packetVisibility));
      Predicate<Object> elytraFlyAcceleratePredicate = ignored -> this.modeSetting.getValue() == ModeChoice.PACKET
         || this.modeSetting.getValue() == ModeChoice.CONTROL;
      this.accelerateSetting = this.add(new BooleanSetting("Accelerate", false, elytraFlyAcceleratePredicate).markVisibilityDependency());
      this.verAccelMinSetting = this.add(new NumberSetting<>("VerAccelMin", 1.0F, 0.0F, 5.0F, elytraFlyAcceleratePredicate).dependsOn(this.accelerateSetting));
      this.accelMinSetting = this.add(new NumberSetting<>("AccelMin", 1.0F, 0.0F, 5.0F, elytraFlyAcceleratePredicate).dependsOn(this.accelerateSetting));
      this.accelTimeSetting = this.add(new NumberSetting<>("AccelTime", 1.0F, 0.1F, 10.0F, elytraFlyAcceleratePredicate).withUnit("s").dependsOn(this.accelerateSetting));
      Predicate<Object> strictVisibility = ignored -> this.modeSetting.getValue() == ModeChoice.STRICT;
      this.startSetting = this.add(new NumberSetting<>("Start", 1.8F, 0.0F, 5.0F, strictVisibility));
      this.strictSpeedSetting = this.add(new NumberSetting<>("Speed", 1.8F, 0.1F, 5.0F, strictVisibility));
      this.deployTimeSetting = this.add(new NumberSetting<>("DeployTime", 400, 50, 1000, strictVisibility).withUnit("ms"));
      Predicate<Object> bounceVisibility = ignored -> this.modeSetting.getValue() == ModeChoice.BOUNCE;
      this.pitchLockSetting = this.add(new BooleanSetting("PitchLock", false, bounceVisibility).markVisibilityDependency());
      Predicate<Object> elytraFlySilentPredicate = ignored -> this.modeSetting.getValue() == ModeChoice.BOUNCE && this.pitchLockSetting.isExpanded();
      this.silentSetting = this.add(new BooleanSetting("Silent", true, elytraFlySilentPredicate));
      this.autoPitchSetting = this.add(new BooleanSetting("Auto", false, elytraFlySilentPredicate));
      Predicate<Object> manualPitchVisible = ignored -> this.modeSetting.getValue() == ModeChoice.BOUNCE
         && this.pitchLockSetting.isExpanded() && !this.autoPitchSetting.getValue();
      this.pitchSetting = this.add(new NumberSetting<>("Pitch", 90.0F, -90.0F, 90.0F, manualPitchVisible));
      this.packetSetting = this.add(new BooleanSetting("Packet", false, bounceVisibility).markVisibilityDependency());
      this.diagonalSetting = this.add(new BooleanSetting("Diagonal", false, bounceVisibility).dependsOn(this.packetSetting));
      this.maxBoostSetting = this.add(new NumberSetting<>("MaxBoost", 300, 40, 999, bounceVisibility).withUnit("km/h").dependsOn(this.packetSetting));
      this.compensateSetting = this.add(new BooleanSetting("Compensate", true, bounceVisibility));
      Predicate<Object> newGrimVisibility = ignored -> this.modeSetting.getValue() == ModeChoice.NEWGRIM;
      this.stopMotionSetting = this.add(new EnumSetting<>("StopMotion", ElytraFlyModule.StopMotionChoice.NONE, newGrimVisibility));
      this.fallChestplateSetting = this.add(new BooleanSetting("FallChestplate", false, newGrimVisibility));
      this.responsiveDescendSetting = this.add(new BooleanSetting("ResponsiveDescend", false, newGrimVisibility));
      this.muteElytraSetting = this.add(new BooleanSetting("MuteElytra", false));
      Predicate<Object> elytraFlyInfDurabilityPredicate = ignored -> this.modeSetting.getValue() == ModeChoice.BOOST
         || this.modeSetting.getValue() == ModeChoice.NEWGRIM
         || this.modeSetting.getValue() == ModeChoice.BOUNCE
         || this.modeSetting.getValue() == ModeChoice.CONTROL
         || this.modeSetting.getValue() == ModeChoice.VANILLA;
      this.infDurabilitySetting = this.add(new EnumSetting<>("InfDurability", ElytraFlyModule.InfDurabilityChoice.NONE, elytraFlyInfDurabilityPredicate));
      this.modeHandlers = new EnumSelectionMap<>(this.modeSetting);
      this.fireworkUseTimer = new ElapsedTimer();
      this.takeoffTimer = new ElapsedTimer();
      this.durabilitySwapTimer = new ElapsedTimer();
      this.enableTimer = new ElapsedTimer();
      this.elytraSearchTimer = new ElapsedTimer();
      this.speedSetting.setConfigName("LavaSpeed");
      this.packetVerticalSetting.setConfigName("VerticalPacket");
      this.strictSpeedSetting.setConfigName("StrictSpeed");
      this.pitchSetting.setConfigName("BouncePitch");
      this.minBoostSetting.setBoundaryLabel("GrimV3", SettingLabelCondition.MAX);
      this.limitSetting.setBoundaryLabel("Unlimited", SettingLabelCondition.MAX);
      this.packetSetting.setConfigName("BounceBoost");
      this.maxBoostSetting.setConfigName("BounceMaxBoost");
      this.modeHandlers.register(ElytraFlyModule.ModeChoice.BOOST, new BoostElytraFlyHandler(this));
      this.modeHandlers.register(ElytraFlyModule.ModeChoice.CONTROL, new ControlElytraFlyHandler(this));
      this.modeHandlers.register(ElytraFlyModule.ModeChoice.PACKET, new PacketElytraFlyHandler(this));
      this.modeHandlers.register(ElytraFlyModule.ModeChoice.STRICT, new StrictElytraFlyHandler(this));
      this.modeHandlers.register(ElytraFlyModule.ModeChoice.BOUNCE, new BounceElytraFlyHandler(this));
      this.modeHandlers.register(ElytraFlyModule.ModeChoice.NEWGRIM, new NewGrimElytraFlyHandler(this));
      this.modeHandlers.register(ElytraFlyModule.ModeChoice.VANILLA, new VanillaElytraFlyHandler(this));
      this.volytra = new VolytraElytraFlyHandler(this);
      this.modeHandlers.register(ElytraFlyModule.ModeChoice.VOLYTRA, this.volytra);
   }

   private VolytraElytraFlyHandler volytra;

   /** Volytra mode's handler, whichever mode is picked: the settings page's Autonav trip card works through it. */
   public VolytraElytraFlyHandler volytra() {
      return this.volytra;
   }

   public enum InfDurabilityChoice implements NamedEntry {
      NONE("None"),
      PLAIN("Plain"),
      GRIM("Grim"),
      _2B2T("2b2t");

      private final String displayName;

      public boolean isDisabled() {
         return this == NONE;
      }

      @Override
      public String getName() {
         return this.displayName;
      }

      InfDurabilityChoice(String displayName) {
         this.displayName = displayName;
      }
   }

   public enum ModeChoice implements NamedEntry {
      CONTROL("Control"),
      BOOST("Boost"),
      PACKET("Packet"),
      STRICT("Strict"),
      BOUNCE("Bounce"),
      NEWGRIM("2b2t"),
      VANILLA("Vanilla"),
      VOLYTRA("Volytra Fly");

      private final String displayName;

      @Override
      public String getName() {
         return this.displayName;
      }

      ModeChoice(String displayName) {
         this.displayName = displayName;
      }
   }

   public enum StopMotionChoice implements NamedEntry {
      NONE("None"),
      STATIC("Static"),
      MOTION("Jitter"),
      TIMER("Timer");

      private final String displayName;

      @Override
      public String getName() {
         return this.displayName;
      }

      StopMotionChoice(String displayName) {
         this.displayName = displayName;
      }
   }

   public enum TakeoffChoice implements NamedEntry {
      NONE("None"),
      PLAIN("Plain"),
      STRICT("Strict");

      private final String displayName;

      @Override
      public String getName() {
         return this.displayName;
      }

      TakeoffChoice(String displayName) {
         this.displayName = displayName;
      }
   }

   public enum VerticalChoice implements NamedEntry {
      NONE("None"),
      PLAIN("Plain"),
      MANUAL("Manual"),
      STRICT("Strict");

      private final String displayName;

      @Override
      public String getName() {
         return this.displayName;
      }

      VerticalChoice(String displayName) {
         this.displayName = displayName;
      }
   }
}
