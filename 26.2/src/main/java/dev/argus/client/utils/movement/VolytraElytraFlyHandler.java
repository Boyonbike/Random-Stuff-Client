package dev.argus.client.utils.movement;

import dev.argus.client.ClientAccess;
import dev.argus.client.ClientContext;
import dev.argus.client.events.LocalPlayerMoveEvent;
import dev.argus.client.events.LocalPlayerTickEvent;
import dev.argus.client.events.PacketEvent;
import dev.argus.client.events.EventHandler;
import dev.argus.client.modules.movement.ElytraFlyModule;
import dev.argus.client.settings.BooleanSetting;
import dev.argus.client.settings.KeyBindSetting;
import dev.argus.client.settings.SettingLabelCondition;
import dev.argus.client.settings.NumberSetting;
import dev.argus.client.settings.Setting;
import dev.argus.client.utils.KeyUtils;
import dev.argus.client.utils.inventory.InventoryUtils;
import dev.argus.client.utils.movement.argus.AdaptiveFlightController.Motion;
import dev.argus.client.utils.movement.argus.ArgusHorizontalFlight;
import dev.argus.client.utils.movement.argus.ElytraBoostDelay;
import dev.argus.client.modules.combat.AutoArmorModule;
import dev.argus.client.utils.movement.argus.SmartElytraSwap;
import dev.argus.client.events.LocalPlayerPreTickEvent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayDeque;
import java.util.Map;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.function.Predicate;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/*
 * Volytra Fly elytra mode.
 * Ported and adapted from VolytraFly by Volizray:
 * https://github.com/Volizray/VolytraFly-Addon
 * The original VolytraFly is released under CC0 1.0 Universal.
 * See THIRD_PARTY_NOTICES.md and licenses/VolytraFly-CC0.txt for attribution and the full licence text.
 */

/**
 * Faithful port of VolytraFly's elytra movement logic (General, Landing, Inventory and
 * Autopilot, with Autohighway and Autonav in {@link VolytraNavigator}), plus optional Argus
 * adaptive turning, base/boost speed caps and smart elytra handling. VolytraFly's Bypass,
 * Mapping, Building and Overdrive modes, its HUD and its visuals are not part of this handler.
 */
public class VolytraElytraFlyHandler extends ElytraFlyModeHandler {
   private static final double RUBBERBAND_CAP_TOLERANCE_PERCENT = 5.0;
   private static final double RUBBERBAND_CAP_DURATION_SECONDS = 10.0;
   private static final int SPEED_PRESERVATION_REWIND_TICKS = 15;
   private static final int RUBBERBAND_CAP_TRIGGER_COUNT = 3;
   private static final long RUBBERBAND_CAP_TRIGGER_WINDOW_NANOS = 3_000_000_000L;
   private static final int AVOIDANCE_STUCK_TICKS = 3;
   private static final int VERTICAL_STEP_TIMEOUT_TICKS = 40;
   /** Config keys the Argus tweak settings were saved under before they were renamed, mapped to the current keys. */
   public static final Map<String, String> RENAMED_CONFIG_KEYS = Map.ofEntries(
      Map.entry("volytraAdaptiveTurning", "argusAdaptiveTurning"),
      Map.entry("volytraTurnRate", "argusTurnRate"),
      Map.entry("volytraCustomSpeeds", "argusBaseBoostSpeeds"),
      Map.entry("volytraBaseHorizontalSpeed", "argusBaseHorizontalSpeed"),
      Map.entry("volytraBaseVerticalSpeed", "argusBaseVerticalSpeed"),
      Map.entry("volytraBoostHorizontalSpeed", "argusBoostHorizontalSpeed"),
      Map.entry("volytraBoostVerticalSpeed", "argusBoostVerticalSpeed"),
      Map.entry("volytraBoostKey", "argusBoostKey"),
      Map.entry("volytraBoostJump", "argusBoostJump"),
      Map.entry("volytra3dSpeedCap", "argus3dSpeedCap"),
      Map.entry("volytraMax3dSpeed", "argusMax3dSpeed"),
      Map.entry("volytraSmartElytra", "argusSmartElytra"),
      Map.entry("volytraLandingSwapHeight", "argusLandingSwapHeight"),
      Map.entry("volytraCometMotion", "argusMotion"),
      Map.entry("volytraCometAcceleration", "argusMotionAcceleration"),
      Map.entry("volytraCometBraking", "argusMotionBraking"),
      Map.entry("volytraCometTurnRate", "argusMotionTurnRate")
   );
   /** Boost H Speed's highest number, in b/s. */
   private static final double BOOST_H_NUMERIC_MAX = 300.0;
   /**
    * One notch past {@link #BOOST_H_NUMERIC_MAX}: the slider's top, shown as "MAX", which uses Volytra's own
    * Horizontal Speed instead of a number. (The slider snaps in steps of 2 b/s here, so 300 and this are neighbours.)
    */
   private static final double BOOST_H_MAX = 301.0;

   private final Setting<Double> startSpeed;
   private final Setting<Double> horizontalSpeed;
   private final Setting<Double> accelerationPlateau;
   private final Setting<Double> accelerationStep;
   private final Setting<Double> verticalStartSpeed;
   private final Setting<Double> verticalSpeed;
   private final Setting<Double> verticalAccelerationPlateau;
   private final Setting<Double> verticalAccelerationStep;
   private final Setting<Integer> accelerationDelay;
   private final Setting<Double> fallMultiplier;
   private final Setting<Boolean> accelerateUpward;
   private final Setting<Boolean> autoTakeOff;
   private final Setting<Boolean> stopInWater;
   private final Setting<Boolean> dontGoIntoUnloadedChunks;
   private final Setting<Boolean> noCrash;
   private final Setting<Integer> crashLookAhead;
   private final Setting<Boolean> instaDrop;
   private final Setting<Boolean> limitMaxHeight;
   private final Setting<Double> maxHeight;
   private final Setting<Boolean> rubberbandSpeedCap;
   private final Setting<Double> rubberbandSpeedCapThreshold;

   private final Setting<Boolean> adaptiveTurning;
   private final Setting<Double> adaptiveTurnRate;
   private final Setting<Boolean> customSpeeds;
   private final KeyBindSetting boostKey;
   private final Setting<Integer> boostJump;
   private final Setting<Double> baseHorizontalSpeed;
   private final Setting<Double> baseVerticalSpeed;
   private final Setting<Double> boostHorizontalSpeed;
   private final Setting<Double> boostVerticalSpeed;
   private final Setting<Boolean> speedCap3d;
   private final Setting<Double> maxSpeed3d;
   private final Setting<Boolean> tweaksGroup;

   private final Setting<Boolean> playerAvoidance;
   private final Setting<Double> avoidanceRadius;
   private final Setting<Boolean> avoidanceIgnoreFriends;
   private final Setting<Boolean> avoidWitherSkulls;
   private final Setting<Double> witherSkullRadius;
   private final Setting<Boolean> avoidArrows;
   private final Setting<Double> arrowRadius;
   private final Setting<Boolean> avoidBlocks;
   private final Setting<Double> blockAvoidanceRadius;
   private final Setting<Boolean> avoidanceLateral;

   private final Setting<Boolean> antiSlam;
   private final Setting<Double> antiSlamDistance;
   private final Setting<Double> antiSlamMinDistance;
   private final Setting<Double> antiSlamMinSpeed;

   private final Setting<Boolean> smartElytra;
   private final Setting<Double> landingSwapHeight;
   private final Setting<Boolean> argusMotion;
   private final Setting<Double> argusMotionAcceleration;
   private final Setting<Double> argusMotionBraking;
   private final Setting<Double> argusMotionTurnRate;
   private final Setting<Boolean> liquidTakeoff;
   private final Setting<Boolean> liquidHoldJump;
   private final Setting<Boolean> liquidWater;
   private final Setting<Double> liquidWaterRise;
   private final Setting<Double> liquidWaterLift;
   private final Setting<Boolean> liquidSwim;
   private final Setting<Double> liquidSwimRise;
   private final Setting<Double> liquidSwimLift;
   private final Setting<Boolean> liquidLava;
   private final Setting<Double> liquidLavaRise;
   private final Setting<Double> liquidLavaLift;
   private final Setting<Integer> liquidGlideDelay;
   private final Setting<Boolean> liquidFirework;
   private final LiquidTakeoff liquidTakeoffAssist = new LiquidTakeoff();
   private final Setting<Boolean> replace;
   private final Setting<Integer> replaceDurability;
   private final Setting<Boolean> autoReplenish;
   private final Setting<Integer> replenishSlot;

   private final Setting<Boolean> autoPilot;
   private final Setting<Boolean> useFireworks;
   private final Setting<Double> autoPilotFireworkDelay;
   private final Setting<Double> autoPilotMinimumHeight;
   private final VolytraNavigator navigator;

   /** Ticks left in which a freshly swapped-in elytra is reopened so the flight carries on. */
   private static final int REGLIDE_WINDOW_TICKS = 10;
   private int reglideTicksLeft;
   private boolean glidingLastTick;
   private final InstaDropListener instaDropListener = new InstaDropListener();

   private double acceleration;
   private double verticalAcceleration;
   double velX;
   double velY;
   double velZ;
   private Vec3 forward = Vec3.ZERO;
   private Vec3 right = Vec3.ZERO;

   private boolean atMaxSpeed;
   private boolean atMaxVerticalSpeed;
   private boolean jumpPressed;
   private boolean lastJumpPressed;
   private boolean incrementJumpTimer;
   private int jumpTimer;
   private int accelerationDelayTicks;
   private int verticalAccelerationDelayTicks;
   private int ticksLeft;
   private Vec3 lastGlidePos;
   private double lastGlideIntendedSpeed;
   private int rubberbandCapTicksLeft;
   private double rubberbandCapSpeed;
   private int positionCorrections;
   private final ArrayDeque<Long> rubberbandQualifyingTimestamps = new ArrayDeque<>();

   private boolean avoidanceSteering;
   private Vec3 avoidanceLateralDir;
   private int avoidanceStuckTicksCount;
   private boolean verticalStepActive;
   private boolean verticalStepUp;
   private double verticalStepStartY;
   private int verticalStepTicks;
   boolean lastForwardPressed;

   private Vec3 lastHorizontalDirection = Vec3.ZERO;
   private final ElytraBoostDelay boostDelay = new ElytraBoostDelay();
   private long clientTicks;
   private long smartTakeoffTick = Long.MIN_VALUE / 2;
   private final ArgusHorizontalFlight argusFlight = new ArgusHorizontalFlight();
   private boolean argusFlying;
   // Position corrections arrive on the network thread; the flight consumes them on the client thread.
   private final AtomicInteger argusCorrections = new AtomicInteger();

   public VolytraElytraFlyHandler(ElytraFlyModule module) {
      super(module);

      // General
      this.startSpeed = this.addDouble("Volytra Start Speed", "volytraStartSpeed", 2.999, 0.0, 50.0, this.volytraCurve());
      this.horizontalSpeed = this.addDouble("Volytra Horizontal Speed", "volytraHorizontalSpeed", 14.999, 0.0, 100.0, this.modeOnly());
      this.accelerationPlateau = this.addDouble("Volytra Acceleration Plateau", "volytraAccelerationPlateau", 14.999, 0.01, 100.0, this.volytraCurve());
      this.accelerationStep = this.addDouble("Volytra Acceleration Step", "volytraAccelerationStep", 0.3, 0.01, 5.0, this.volytraCurve());
      this.verticalStartSpeed = this.addDouble("Volytra Vertical Start Speed", "volytraVerticalStartSpeed", 5.999, 0.0, 50.0, this.modeOnly());
      this.verticalSpeed = this.addDouble("Volytra Vertical Speed", "volytraVerticalSpeed", 5.999, 0.0, 50.0, this.modeOnly());
      this.verticalAccelerationPlateau = this.addDouble("Volytra Vertical Acceleration Plateau", "volytraVerticalAccelerationPlateau", 29.999, 0.01, 100.0, this.nonArgusMotion());
      this.verticalAccelerationStep = this.addDouble("Volytra Vertical Acceleration Step", "volytraVerticalAccelerationStep", 1.0, 0.01, 5.0, this.nonArgusMotion());
      this.accelerationDelay = this.addInt("Volytra Acceleration Delay", "volytraAccelerationDelay", 1, 0, 100, this.modeOnly());
      this.fallMultiplier = this.addDouble("Volytra Fall Multiplier", "volytraFallMultiplier", 0.0, 0.0, 1.0, this.modeOnly());
      this.accelerateUpward = this.addBool("Volytra Accelerate Upward", "volytraAccelerateUpward", false, this.modeOnly());

      // Safety and limits: the rest of the Volytra Fly tab
      Setting<Boolean> safety = this.addGroup("Safety & Limits");
      safety.groupIcon = "shield";
      safety.groupSection = SECTION_FLY;
      this.stopInWater = this.addBool("Volytra Stop In Water", "volytraStopInWater", false, this.modeOnly());
      this.dontGoIntoUnloadedChunks = this.addBool("Volytra No Unloaded Chunks", "volytraNoUnloadedChunks", false, this.modeOnly());
      this.noCrash = this.addBool("Volytra No Crash", "volytraNoCrash", false, this.modeOnly());
      this.crashLookAhead = this.addInt("Volytra Crash Lookahead", "volytraCrashLookahead", 3, 1, 15, this.visibleWith(this.noCrash));
      this.limitMaxHeight = this.addBool("Volytra Limit Height", "volytraLimitHeight", true, this.modeOnly());
      this.maxHeight = this.addDouble("Volytra Max Height", "volytraMaxHeight", 500.0, -128.0, 4096.0, this.visibleWith(this.limitMaxHeight));
      this.rubberbandSpeedCap = this.addBool("Volytra Rubberband Cap", "volytraRubberbandCap", true, this.modeOnly());
      this.rubberbandSpeedCapThreshold = this.addDouble("Volytra Rubberband Threshold", "volytraRubberbandThreshold", 200.0, 0.0, 400.0, this.visibleWith(this.rubberbandSpeedCap));

      // Takeoff and landing
      this.addGroup("Takeoff & Landing").groupIcon = "drop";
      this.autoTakeOff = this.addBool("Volytra Auto Takeoff", "volytraAutoTakeoff", false, this.modeOnly());
      this.instaDrop = this.addBool("Volytra Insta Drop", "volytraInstaDrop", false, this.modeOnly());
      this.antiSlam = this.addBool("Volytra Anti Slam", "volytraAntiSlam", true, this.modeOnly());
      this.antiSlamDistance = this.addDouble("Volytra Anti Slam Distance", "volytraAntiSlamDistance", 10.0, 0.1, 30.0, this.visibleWith(this.antiSlam));
      this.antiSlamMinDistance = this.addDouble("Volytra Anti Slam Min Distance", "volytraAntiSlamMinDistance", 0.5, 0.0, 5.0, this.visibleWith(this.antiSlam));
      this.antiSlamMinSpeed = this.addDouble("Volytra Anti Slam Min Speed", "volytraAntiSlamMinSpeed", 0.2, 0.0, 1.0, this.visibleWith(this.antiSlam));

      // Player avoidance
      this.folded(this.addGroup("Avoidance")).groupIcon = "search";
      this.playerAvoidance = this.addBool("Volytra Player Avoidance", "volytraPlayerAvoidance", false, this.modeOnly());
      this.avoidanceRadius = this.addDouble("Volytra Avoidance Radius", "volytraAvoidanceRadius", 30.0, 0.0, 50.0, this.visibleWith(this.playerAvoidance));
      this.avoidanceIgnoreFriends = this.addBool("Volytra Ignore Friends", "volytraIgnoreFriends", true, this.visibleWith(this.playerAvoidance));
      this.avoidWitherSkulls = this.addBool("Volytra Avoid Wither Skulls", "volytraAvoidWitherSkulls", true, this.visibleWith(this.playerAvoidance));
      this.witherSkullRadius = this.addDouble("Volytra Wither Skull Radius", "volytraWitherSkullRadius", 10.0, 0.0, 50.0, value -> this.active() && this.playerAvoidance.getValue() && this.avoidWitherSkulls.getValue());
      this.avoidArrows = this.addBool("Volytra Avoid Arrows", "volytraAvoidArrows", false, this.visibleWith(this.playerAvoidance));
      this.arrowRadius = this.addDouble("Volytra Arrow Radius", "volytraArrowRadius", 50.0, 0.0, 50.0, value -> this.active() && this.playerAvoidance.getValue() && this.avoidArrows.getValue());
      this.avoidBlocks = this.addBool("Volytra Avoid Blocks", "volytraAvoidBlocks", true, this.visibleWith(this.playerAvoidance));
      this.blockAvoidanceRadius = this.addDouble("Volytra Block Radius", "volytraBlockRadius", 3.0, 0.0, 10.0, value -> this.active() && this.playerAvoidance.getValue() && this.avoidBlocks.getValue());
      this.avoidanceLateral = this.addBool("Volytra Sidestep", "volytraSidestep", true, this.visibleWith(this.playerAvoidance));

      // Inventory
      this.folded(this.addGroup("Inventory")).groupIcon = "player";
      this.replace = this.addBool("Volytra Replace Elytra", "volytraReplaceElytra", false, this.modeOnly());
      this.replaceDurability = this.addInt("Volytra Replace Durability", "volytraReplaceDurability", 2, 1, 500, this.visibleWith(this.replace));
      this.autoReplenish = this.addBool("Volytra Auto Replenish", "volytraAutoReplenish", false, this.modeOnly());
      this.replenishSlot = this.addInt("Volytra Replenish Slot", "volytraReplenishSlot", 9, 1, 9, this.visibleWith(this.autoReplenish));

      // Autopilot
      this.addGroup("Autopilot").groupIcon = "movement";
      this.autoPilot = this.addBool("Volytra Auto Pilot", "volytraAutoPilot", false, this.modeOnly());
      this.autoPilotMinimumHeight = this.addDouble("Volytra Min Height", "volytraMinHeight", 120.0, -128.0, 4096.0, this.visibleWith(this.autoPilot));
      this.useFireworks = this.addBool("Volytra Use Fireworks", "volytraUseFireworks", false, this.modeOnly());
      this.autoPilotFireworkDelay = this.addDouble("Volytra Firework Delay", "volytraFireworkDelay", 8.0, 1.0, 100.0, this.visibleWith(this.useFireworks));

      // Tweaks tab: Argus additions for flying by hand only (see tweak()), one card per part of the flight.
      this.tweaksGroup = this.tweakGroup("Handling", "movement");
      this.tweaksGroup.groupSection = SECTION_TWEAKS;
      this.tweaksGroup.groupNote = "Tweaks pause while Autonav or Autohighway flies";
      this.adaptiveTurning = this.addBool("Argus Adaptive Turning", "argusAdaptiveTurning", true, this.modeOnly());
      this.adaptiveTurnRate = this.addDouble("Argus Turn Rate", "argusTurnRate", 15.0, 0.0, 90.0, this.visibleWithoutArgusMotion(this.adaptiveTurning));
      this.argusMotion = this.addBool("Argus Motion", "argusMotion", true, this.modeOnly());
      this.argusMotionAcceleration = this.addDouble("Argus Motion Acceleration", "argusMotionAcceleration", 0.15, 0.01, 2.0, this.visibleWith(this.argusMotion));
      this.argusMotionBraking = this.addDouble("Argus Motion Braking", "argusMotionBraking", 0.3, 0.01, 2.0, this.visibleWith(this.argusMotion));
      this.argusMotionTurnRate = this.addDouble("Argus Motion Turn Rate", "argusMotionTurnRate", 15.0, 0.0, 45.0, this.visibleWith(this.argusMotion, this.adaptiveTurning));

      this.tweakGroup("Speeds & Boost", "sliders");
      this.customSpeeds = this.addBool("Argus Base/Boost Speeds", "argusBaseBoostSpeeds", false, this.modeOnly());
      // One toggle owns base and boost: off flies Volytra's own speeds with no boost; on shows all four sliders
      // and the boost key.
      this.baseHorizontalSpeed = this.addSpeed("Argus Base H Speed", "argusBaseHorizontalSpeed", 41.2, 0.0, 300.0, this.visibleWith(this.customSpeeds));
      this.baseVerticalSpeed = this.addSpeed("Argus Base V Speed", "argusBaseVerticalSpeed", 21.0, 0.0, 60.0, this.visibleWith(this.customSpeeds));
      this.boostHorizontalSpeed = this.addSpeed("Argus Boost H Speed", "argusBoostHorizontalSpeed", BOOST_H_MAX, 0.0, BOOST_H_MAX, this.visibleWith(this.customSpeeds));
      // The top of the slider is not a number: it is the fastest Volytra Fly goes (its Horizontal Speed).
      this.boostHorizontalSpeed.setBoundaryLabel("MAX", SettingLabelCondition.MAX);
      this.boostVerticalSpeed = this.addSpeed("Argus Boost V Speed", "argusBoostVerticalSpeed", 60.0, 0.0, 60.0, this.visibleWith(this.customSpeeds));
      this.boostKey = this.module.add(new KeyBindSetting("Argus Boost Key", "Sprint", this.visibleWith(this.customSpeeds)));
      this.boostKey.setConfigName("argusBoostKey");
      // Ticks of Volytra's acceleration curve skipped the moment boost starts: an instant kick that is large from
      // base speed and small near the top.
      this.boostJump = this.addInt("Argus Boost Jump", "argusBoostJump", 0, 0, 40, value -> this.active() && this.customSpeeds.getValue() && this.argusMotionOn());
      ((NumberSetting<Integer>) this.boostJump).withUnit(" ticks");
      this.speedCap3d = this.addBool("Argus 3D Speed Cap", "argus3dSpeedCap", false, this.modeOnly());
      this.maxSpeed3d = this.addSpeed("Argus Max 3D Speed", "argusMax3dSpeed", 200.0, 0.0, 480.0, this.visibleWith(this.speedCap3d));

      this.tweakGroup("Smart Elytra", "player");
      this.smartElytra = this.addBool("Argus Smart Elytra", "argusSmartElytra", false, this.modeOnly());
      // How close to the floor counts as landed for the chestplate swap; measured, not the onGround flag.
      this.landingSwapHeight = this.addDouble("Argus Landing Swap Height", "argusLandingSwapHeight", 0.5, 0.0, 3.0, this.visibleWith(this.smartElytra));
      ((NumberSetting<Double>) this.landingSwapHeight).withUnit(" blocks");

      this.tweakGroup("Liquid Takeoff", "drop");
      // Liquid takeoff (water, swimming, lava): hold jump to rise; at the top of the rise out of the liquid, lift
      // straight up by the Lift speed and start gliding (in water, the Lift is held until clear of it).
      this.liquidTakeoff = this.addBool("Argus Liquid Takeoff", "argusLiquidTakeoff", true, this.modeOnly());
      this.liquidHoldJump = this.addBool("Argus Liquid Hold Jump", "argusLiquidHoldJump", true, this.visibleWith(this.liquidTakeoff));
      this.liquidWater = this.addBool("Argus Liquid Water", "argusLiquidWater", true, this.visibleWith(this.liquidTakeoff));
      this.liquidWaterRise = this.addDouble("Argus Water Rise", "argusLiquidWaterRise", 0.1, 0.05, 1.0, this.visibleWith(this.liquidTakeoff, this.liquidWater));
      this.liquidWaterLift = this.addDouble("Argus Water Lift", "argusLiquidWaterLaunch", 0.8, 0.2, 2.0, this.visibleWith(this.liquidTakeoff, this.liquidWater));
      this.liquidSwim = this.addBool("Argus Liquid Swim", "argusLiquidSwim", true, this.visibleWith(this.liquidTakeoff));
      this.liquidSwimRise = this.addDouble("Argus Swim Rise", "argusLiquidSwimRise", 0.1, 0.05, 1.0, this.visibleWith(this.liquidTakeoff, this.liquidSwim));
      this.liquidSwimLift = this.addDouble("Argus Swim Lift", "argusLiquidSwimLaunch", 0.8, 0.2, 2.0, this.visibleWith(this.liquidTakeoff, this.liquidSwim));
      this.liquidLava = this.addBool("Argus Liquid Lava", "argusLiquidLava", true, this.visibleWith(this.liquidTakeoff));
      this.liquidLavaRise = this.addDouble("Argus Lava Rise", "argusLiquidLavaRise", 0.1, 0.05, 1.0, this.visibleWith(this.liquidTakeoff, this.liquidLava));
      this.liquidLavaLift = this.addDouble("Argus Lava Lift", "argusLiquidLavaLift", 0.2, 0.0, 1.0, this.visibleWith(this.liquidTakeoff, this.liquidLava));
      this.liquidGlideDelay = this.addInt("Argus Liquid Glide Delay", "argusLiquidGlideDelay", 2, 0, 10, this.visibleWith(this.liquidTakeoff));
      ((NumberSetting<Integer>) this.liquidGlideDelay).withUnit(" ticks");
      this.liquidFirework = this.addBool("Argus Liquid Firework", "argusLiquidFirework", false, this.visibleWith(this.liquidTakeoff));

      // Autonav and Autohighway (each its own card) make up the last tab.
      this.navigator = new VolytraNavigator(this);

      module.modeSetting.setChangeCallback(this::onModeChanged);
   }

   /** A copy of saved ElytraFly settings with values under old tweak keys moved to their current keys. */
   public static JsonObject migrateRenamedKeys(JsonObject settings) {
      JsonObject migrated = settings.deepCopy();
      RENAMED_CONFIG_KEYS.forEach((oldKey, newKey) -> {
         if (migrated.has(oldKey) && !migrated.has(newKey)) migrated.add(newKey, migrated.get(oldKey));
         migrated.remove(oldKey);
      });
      return migrated;
   }

   boolean active() {
      return this.module.modeSetting.getValue() == ElytraFlyModule.ModeChoice.VOLYTRA;
   }

   <T> Predicate<T> modeOnly() {
      return value -> this.active();
   }

   <T> Predicate<T> visibleWith(Setting<Boolean> parent) {
      return value -> this.active() && parent.getValue();
   }

   private <T> Predicate<T> visibleWith(Setting<Boolean> first, Setting<Boolean> second) {
      return value -> this.active() && first.getValue() && second.getValue();
   }

   /** Volytra's horizontal speed curve: drives flight without Argus Motion, and Argus Motion's boost or speed. */
   private <T> Predicate<T> volytraCurve() {
      return this.modeOnly();
   }

   /**
    * Whether a Tweaks toggle applies right now. Tweaks are for flying by hand: while Autonav or Autohighway has the
    * flight, it flies plain Volytra Fly, and the tweaks come back as soon as you have it again.
    */
   private boolean tweak(Setting<Boolean> setting) {
      return setting.getValue() && (this.navigator == null || !this.navigator.driving());
   }

   /** Argus Motion is declared after some settings that depend on it, so read it through a method. */
   private boolean argusMotionOn() {
      return this.argusMotion != null && this.argusMotion.getValue();
   }

   private <T> Predicate<T> nonArgusMotion() {
      return value -> this.active() && !this.argusMotion.getValue();
   }

   private <T> Predicate<T> visibleWithoutArgusMotion(Setting<Boolean> parent) {
      return value -> this.active() && !this.argusMotion.getValue() && parent.getValue();
   }

   /**
    * Starts a foldable card in the settings page: every setting added after it, up to the next group, sits in this
    * card. Shown only in Volytra mode. Group headers are not saved to the config.
    */
   Setting<Boolean> addGroup(String name) {
      Setting<Boolean> group = this.module.add(new BooleanSetting(name, false, this.modeOnly())).markAsGroup();
      group.expanded = true;
      return group;
   }

   /** The settings page's tabs in Volytra mode. */
   static final String SECTION_FLY = "Volytra Fly", SECTION_TWEAKS = "Tweaks", SECTION_NAV = "AutoNav & Highway";

   private Setting<Boolean> tweakGroup(String name, String icon) {
      Setting<Boolean> group = this.addGroup(name);
      group.groupIcon = icon;
      return group;
   }

   /** A card that starts folded each time the page opens: settings most players set once and leave. */
   private Setting<Boolean> folded(Setting<Boolean> group) {
      group.startsFolded = true;
      return group;
   }

   // ---------------------------------------------------------------- the settings page's Autonav trip card

   /** Whether the trip card belongs on the page: Volytra mode (it sits on the AutoNav & Highway tab). */
   public boolean autonavTripShown() {
      return this.active();
   }

   public int autonavTypedX() {
      return this.navigator.typedX();
   }

   public int autonavTypedZ() {
      return this.navigator.typedZ();
   }

   public void setAutonavTyped(int x, int z) {
      this.navigator.setTyped(x, z);
   }

   public boolean autonavTripActive() {
      return this.navigator.tripActive();
   }

   public boolean autonavTripPaused() {
      return this.navigator.tripPaused();
   }

   public String autonavTripStatus() {
      return this.navigator.tripStatus();
   }

   /** Resumes a paused trip, or starts one to the typed coordinates; throws with the reason when it can't. */
   public void startAutonavTrip() {
      this.navigator.startTrip();
   }

   /** Starts a trip to (x, z); throws with the reason when it can't. */
   public void flyAutonavTo(int x, int z) {
      this.navigator.flyTo(x, z);
   }

   public void pauseAutonavTrip() {
      this.navigator.pauseTrip();
   }

   public void stopAutonavTrip() {
      this.navigator.stopTrip();
   }

   public List<dev.argus.client.utils.movement.volytra.AutonavWaypoint> autonavWaypoints() {
      return this.navigator.waypoints();
   }

   public void saveAutonavWaypoint(String name, int x, int z) {
      this.navigator.saveWaypoint(name, x, z);
   }

   public void removeAutonavWaypoint(String name) {
      this.navigator.removeWaypoint(name);
   }

   public JsonElement autonavWaypointsJson() {
      return this.navigator.waypointsJson();
   }

   public void loadAutonavWaypoints(JsonElement json) {
      this.navigator.loadWaypoints(json);
   }

   BooleanSetting addBool(String name, String configName, boolean defaultValue, Predicate<Boolean> visible) {
      BooleanSetting setting = this.module.add(new BooleanSetting(name, defaultValue, visible));
      setting.setConfigName(configName);
      return setting;
   }

   Setting<Double> addDouble(String name, String configName, double defaultValue, double min, double max, Predicate<Double> visible) {
      Setting<Double> setting = this.module.add(new NumberSetting<>(name, defaultValue, min, max, visible));
      setting.setConfigName(configName);
      return setting;
   }

   Setting<Integer> addInt(String name, String configName, int defaultValue, int min, int max, Predicate<Integer> visible) {
      Setting<Integer> setting = this.module.add(new NumberSetting<>(name, defaultValue, min, max, visible));
      setting.setConfigName(configName);
      return setting;
   }

   private Setting<Double> addSpeed(String name, String configName, double defaultValue, double min, double max, Predicate<Double> visible) {
      NumberSetting<Double> setting = this.module.add(new NumberSetting<>(name, defaultValue, min, max, visible));
      setting.setConfigName(configName);
      setting.withUnit(" b/s");
      return setting;
   }

   private void onModeChanged() {
      if (!this.module.isToggled()) return;
      if (this.active()) {
         this.onEnable();
      } else {
         this.onDisable();
      }
   }

   @Override
   public void onEnable() {
      LocalPlayer player = this.minecraft().player;
      this.instaDropListener.stop();
      this.navigator.onEnable();
      this.reglideTicksLeft = 0;
      this.glidingLastTick = false;
      this.liquidTakeoffAssist.reset();
      this.atMaxSpeed = false;
      this.lastJumpPressed = false;
      this.jumpPressed = false;
      this.incrementJumpTimer = false;
      this.jumpTimer = 0;
      this.ticksLeft = 0;
      this.accelerationDelayTicks = 0;
      this.verticalAccelerationDelayTicks = 0;
      this.lastGlidePos = null;
      this.lastGlideIntendedSpeed = 0.0;
      this.lastForwardPressed = false;
      this.lastHorizontalDirection = Vec3.ZERO;
      this.boostDelay.reset();
      this.argusFlying = false;

      this.rubberbandCapTicksLeft = 0;
      double currentHorizontalSpeed = player != null
         ? Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z)
         : 0.0;
      double cap = this.effectiveHorizontalSpeedCap();
      this.acceleration = Math.max(this.effectiveHorizontalStartSpeed(), Math.min(currentHorizontalSpeed, cap));
      if (this.acceleration >= cap) this.atMaxSpeed = true;

      this.resetVerticalAcceleration();
      this.rubberbandQualifyingTimestamps.clear();
      this.avoidanceSteering = false;
      this.avoidanceLateralDir = null;
      this.avoidanceStuckTicksCount = 0;
      this.verticalStepActive = false;
      this.verticalStepTicks = 0;
      this.positionCorrections = 0;
   }

   @Override
   public void onDisable() {
      this.liquidTakeoffAssist.reset();
      this.navigator.onDisable();
      this.reglideTicksLeft = 0;
      // Insta Drop: switching the module off mid-glide stops the flight dead and drops straight down. A change of
      // ElytraFly mode keeps the module on and is not a switch-off.
      LocalPlayer player = this.minecraft().player;
      if (this.instaDrop.getValue() && !this.module.isToggled() && player != null && player.isFallFlying()) {
         this.instaDropListener.start();
      }
      this.releaseAvoidance();
      this.releaseVerticalStep();
      this.releaseAllForcedKeys();
      this.lastForwardPressed = false;
      this.lastHorizontalDirection = Vec3.ZERO;
      this.boostDelay.reset();
      this.argusFlying = false;
      this.rubberbandCapTicksLeft = 0;
   }

   @Override
   public void handleMovement(LocalPlayerMoveEvent event) {
      Minecraft minecraft = this.minecraft();
      LocalPlayer player = minecraft.player;
      ClientLevel world = minecraft.level;
      if (player == null || world == null) return;
      this.navigator.trackWorld(world);
      if (!player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)) {
         this.lastHorizontalDirection = Vec3.ZERO;
         return;
      }

      this.autoTakeoff(player);
      this.updatePlayerAvoidance(player);

      if (player.isFallFlying()) {
         Vec3 movement = event.getVelocity();
         this.velX = 0.0;
         this.velZ = 0.0;
         this.velY = movement.y;
         this.forward = Vec3.directionFromRotation(0.0F, player.getYRot()).scale(0.1);
         this.right = Vec3.directionFromRotation(0.0F, player.getYRot() + 90.0F).scale(0.1);

         if (player.isInWater() && this.stopInWater.getValue()) {
            player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            return;
         }

         this.handleFallMultiplier();
         this.handleAutopilot(player);

         if (this.tweak(this.argusMotion)) {
            this.handleArgusHorizontal(player);
         } else {
            this.argusFlying = false;
            this.resetRampOnCollision(player);
            this.handleAcceleration();
            this.handleHorizontalSpeed();
         }
         this.navigator.applySteering(player);

         this.handleVerticalAcceleration();
         this.handleVerticalSpeed();
         this.applyCustomVerticalSpeedCap();
         this.navigator.limitVerticalStep(player);
         this.navigator.applySlopeCoupling(player);
         this.handleAntiSlam(world, player);
         this.handleMaxHeight(player);
         this.navigator.applyClearanceGuard(player);

         int chunkX = SectionPos.blockToSectionCoord(player.getX() + this.velX);
         int chunkZ = SectionPos.blockToSectionCoord(player.getZ() + this.velZ);
         if (this.dontGoIntoUnloadedChunks.getValue() && !world.hasChunk(chunkX, chunkZ)) {
            this.velX = 0.0;
            this.velZ = 0.0;
         }

         if (this.noCrash.getValue()) {
            Vec3 velocity = player.getDeltaMovement();
            if (velocity.lengthSqr() > 1.0E-8) {
               Vec3 lookAhead = player.position().add(velocity.normalize().scale(this.crashLookAhead.getValue()));
               BlockHitResult hit = world.clip(new ClipContext(
                  player.position(),
                  new Vec3(lookAhead.x, player.getY(), lookAhead.z),
                  ClipContext.Block.COLLIDER,
                  ClipContext.Fluid.NONE,
                  player
               ));
               if (hit.getType() == HitResult.Type.BLOCK) {
                  this.velX = 0.0;
                  this.velZ = 0.0;
               }
            }
         }

         // Autohighway and Autonav aim the flight themselves; turn smoothing would swing it off their route.
         if (!this.tweak(this.argusMotion) && Float.isNaN(this.navigator.steerYaw(player))) this.applyAdaptiveTurning();
         if (this.tweak(this.argusMotion)) this.applyTotalCeiling(this.argusFlight.totalCeiling());
         this.applySpeedCap3d();
         if (this.tweak(this.argusMotion)) {
            // Momentum continues from what was actually sent, including chunk/crash stops and the caps.
            this.argusFlight.sent(this.velX, this.velZ);
         }

         event.setVelocity(new Vec3(this.velX, this.velY, this.velZ));
         this.lastGlidePos = player.position();
         this.lastGlideIntendedSpeed = Math.hypot(this.velX, this.velZ);
      } else {
         if (this.lastForwardPressed) {
            this.setKey(minecraft.options.keyUp, false);
            this.lastForwardPressed = false;
         }
         this.lastGlidePos = null;
         this.lastGlideIntendedSpeed = 0.0;
         this.lastHorizontalDirection = Vec3.ZERO;
         this.argusFlying = false;
      }
   }

   /**
    * Argus Motion's horizontal flight (cruise, boost and the transition between them); see
    * {@link ArgusHorizontalFlight}. Vertical motion stays on Volytra's model.
    */
   private void handleArgusHorizontal(LocalPlayer player) {
      if (!this.argusFlying) {
         // Argus Motion starts each controlled glide from rest with the boost ramp released.
         this.argusFlight.reset();
         this.argusFlying = true;
         this.argusCorrections.set(0);
         this.lastGlidePos = null;
      }

      if (this.argusCorrections.getAndSet(0) > 0) {
         // A correction farther than 64 blocks (a command or portal) is a teleport, not a setback.
         boolean teleport = this.lastGlidePos == null || player.position().distanceToSqr(this.lastGlidePos) > 64.0 * 64.0;
         this.argusFlight.setback(teleport);
         this.lastGlidePos = null;
      }

      // Only a real wall collision costs speed: keep what got through.
      if (this.lastGlidePos != null && player.horizontalCollision) {
         this.argusFlight.collided(player.getX() - this.lastGlidePos.x, player.getZ() - this.lastGlidePos.z);
      }

      Minecraft minecraft = this.minecraft();
      int forward = (PlayerMovementKeys.isDown(minecraft.options.keyUp) ? 1 : 0) - (PlayerMovementKeys.isDown(minecraft.options.keyDown) ? 1 : 0);
      int right = (PlayerMovementKeys.isDown(minecraft.options.keyRight) ? 1 : 0) - (PlayerMovementKeys.isDown(minecraft.options.keyLeft) ? 1 : 0);
      // Lets the server establish a glide from a fresh elytra swap before speed builds past cruise.
      this.observeBoostDelay(player);
      double turnRate = this.tweak(this.adaptiveTurning) ? this.argusMotionTurnRate.getValue() : 0.0;
      ArgusHorizontalFlight.Settings settings;
      boolean curveKey;
      if (this.tweak(this.customSpeeds)) {
         // Base: Argus Motion's smooth cruise at Base H Speed. Boost: hands over to Volytra Fly's own acceleration
         // curve (Start Speed, Acceleration Step and Plateau) up to Boost H Speed. Pressing boost jumps at once
         // to Start Speed, or carries on from the current speed when already faster.
         settings = new ArgusHorizontalFlight.Settings(this.argusMotionAcceleration.getValue(), this.argusMotionBraking.getValue(),
            turnRate, this.withRubberbandCap(this.baseHorizontalSpeed.getValue() / 20.0), true,
            this.withRubberbandCap(this.boostHorizontalCap()), this.accelerationStep.getValue(),
            Math.max(this.accelerationPlateau.getValue(), 1.0E-3), this.curveStartSpeed(),
            this.boostJump.getValue());
         curveKey = this.boostKeyHeld();
      } else {
         // Volytra's own speeds: its acceleration curve (Start Speed, Acceleration Step and Plateau, up to
         // Horizontal Speed) runs whenever you move, with Argus Motion's smoothing on top. Using the curve keeps
         // Volytra's setback rewind, the server's per-move limit and speed-scaled braking; a plain cruise
         // target of Horizontal Speed has none of them and rubber-bands once it gets fast.
         settings = new ArgusHorizontalFlight.Settings(this.argusMotionAcceleration.getValue(), this.argusMotionBraking.getValue(),
            turnRate, this.withRubberbandCap(this.curveStartSpeed()), true,
            this.withRubberbandCap(this.horizontalSpeed.getValue()), this.accelerationStep.getValue(),
            Math.max(this.accelerationPlateau.getValue(), 1.0E-3), this.curveStartSpeed());
         curveKey = true;
      }

      // While Autohighway or Autonav drives, Argus Motion turns towards their waypoint instead of the camera.
      float steerYaw = this.navigator.steerYaw(player);
      Motion output = this.argusFlight.step(settings, forward, right, Float.isNaN(steerYaw) ? player.getYRot() : steerYaw,
         this.boostDelay.ready() && curveKey);
      this.velX = output.x();
      this.velZ = output.z();
   }

   private double withRubberbandCap(double speed) {
      if (this.rubberbandCapTicksLeft > 0) speed = Math.min(speed, this.rubberbandCapSpeed);
      return Math.max(0.0, speed);
   }

   /** Holds the whole 3D move to {@code ceiling} blocks per tick, while boosting. */
   private void applyTotalCeiling(double ceiling) {
      double speed = Math.sqrt(this.velX * this.velX + this.velY * this.velY + this.velZ * this.velZ);
      if (!(speed > ceiling)) return;
      double scale = ceiling / speed;
      this.velX *= scale;
      this.velY *= scale;
      this.velZ *= scale;
   }

   private void applyAdaptiveTurning() {
      if (!this.tweak(this.adaptiveTurning)) {
         this.lastHorizontalDirection = Vec3.ZERO;
         return;
      }

      double speed = Math.hypot(this.velX, this.velZ);
      if (speed < 1.0E-4) {
         this.lastHorizontalDirection = Vec3.ZERO;
         return;
      }

      Vec3 desired = new Vec3(this.velX / speed, 0.0, this.velZ / speed);
      if (this.lastHorizontalDirection.lengthSqr() < 1.0E-6) {
         this.lastHorizontalDirection = desired;
         return;
      }

      double dot = Math.max(-1.0, Math.min(1.0, this.lastHorizontalDirection.dot(desired)));
      double angle = Math.toDegrees(Math.acos(dot));
      if (angle > 135.0) {
         this.lastHorizontalDirection = desired;
         return;
      }

      double configuredTurnRate = this.adaptiveTurnRate.getValue();
      double speedRatio = Math.min(1.0, speed / 5.9);
      double maxTurn = Math.min(90.0, configuredTurnRate * (1.0 + speedRatio));
      if (angle <= maxTurn) {
         this.lastHorizontalDirection = desired;
         return;
      }

      double cross = this.lastHorizontalDirection.x * desired.z - this.lastHorizontalDirection.z * desired.x;
      if (Math.abs(cross) < 1.0E-6) {
         this.lastHorizontalDirection = desired;
         return;
      }

      Vec3 turned = rotateHorizontal(this.lastHorizontalDirection, Math.signum(cross) * maxTurn);
      this.lastHorizontalDirection = turned;
      this.velX = turned.x * speed;
      this.velZ = turned.z * speed;
   }

   private void applyCustomVerticalSpeedCap() {
      if (!this.tweak(this.customSpeeds)) return;

      double cap = this.effectiveVerticalSpeedCap();
      if (Math.abs(this.velY) > cap) {
         double brakingCap = this.tweak(this.argusMotion)
            ? Math.max(cap, Math.abs(this.velY) - this.argusMotionBraking.getValue())
            : cap;
         this.velY = Math.copySign(brakingCap, this.velY);
      }
   }

   private void applySpeedCap3d() {
      if (!this.tweak(this.speedCap3d)) return;

      double cap = this.maxSpeed3d.getValue() / 20.0;
      if (cap <= 0.0) {
         this.velX = 0.0;
         this.velY = 0.0;
         this.velZ = 0.0;
         return;
      }

      double speed = Math.sqrt(this.velX * this.velX + this.velY * this.velY + this.velZ * this.velZ);
      if (speed <= cap || speed < 1.0E-6) return;

      double scale = cap / speed;
      this.velX *= scale;
      this.velY *= scale;
      this.velZ *= scale;
   }

   @Override
   public void handlePrePlayerTick(LocalPlayerPreTickEvent event) {
      LocalPlayer player = this.minecraft().player;
      if (player == null) return;
      this.clientTicks++;
      this.smartLandingSwap(player);
      this.observeBoostDelay(player);
      this.liquidTakeoff(player);
   }

   private void liquidTakeoff(LocalPlayer player) {
      if (!this.tweak(this.liquidTakeoff) || ClientContext.baritoneBridge.isPathingOrCalculating()) {
         this.liquidTakeoffAssist.reset();
         return;
      }
      // Without wings on, only help when Smart Elytra can put them on for the glide.
      if (!isGlider(player.getItemBySlot(EquipmentSlot.CHEST)) && !this.tweak(this.smartElytra)) {
         this.liquidTakeoffAssist.reset();
         return;
      }
      this.liquidTakeoffAssist.tick(player, new LiquidTakeoff.Options(
         new LiquidTakeoff.Phase(this.liquidWater.getValue(), this.liquidWaterRise.getValue(), this.liquidWaterLift.getValue()),
         new LiquidTakeoff.Phase(this.liquidSwim.getValue(), this.liquidSwimRise.getValue(), this.liquidSwimLift.getValue()),
         new LiquidTakeoff.Phase(this.liquidLava.getValue(), this.liquidLavaRise.getValue(), this.liquidLavaLift.getValue()),
         this.liquidHoldJump.getValue(), this.liquidGlideDelay.getValue(), this.liquidFirework.getValue()),
         () -> this.equipGliderForTakeoff(player));
   }

   /** Wings on for a liquid takeoff: already worn, or swapped on by Smart Elytra. */
   private boolean equipGliderForTakeoff(LocalPlayer player) {
      if (isGlider(player.getItemBySlot(EquipmentSlot.CHEST))) return true;
      if (!this.tweak(this.smartElytra) || !this.canSmartSwap(player)) return false;
      this.swapChest(player, true);
      this.smartTakeoffTick = this.clientTicks;
      return isGlider(player.getItemBySlot(EquipmentSlot.CHEST));
   }

   @Override
   public void handlePlayerTick(LocalPlayerTickEvent event) {
      if (this.rubberbandCapTicksLeft > 0) this.rubberbandCapTicksLeft--;

      Minecraft minecraft = this.minecraft();
      LocalPlayer player = minecraft.player;
      if (player == null) return;

      this.smartLandingSwap(player);

      if (this.autoReplenish.getValue() && this.canManageInventory(player)) {
         int targetSlot = this.replenishSlot.getValue() - 1;
         if (!player.getInventory().getItem(targetSlot).is(Items.FIREWORK_ROCKET)) {
            int slot = InventoryUtils.findInventorySlot(stack -> stack.is(Items.FIREWORK_ROCKET), true);
            if (slot >= 0) {
               InventoryUtils.swapWithHotbarSlot(InventoryUtils.toMenuSlot(slot), targetSlot);
            }
         }
      }

      // Gliding now, or a tick ago and just lost it (an elytra breaking ends the glide)
      boolean wasFlying = player.isFallFlying() || this.glidingLastTick;
      if (this.replace.getValue() && this.canManageInventory(player)) {
         ItemStack chestStack = player.getItemBySlot(EquipmentSlot.CHEST);
         // Either the worn elytra is about to give out, or it already broke mid-flight and the chest slot is empty
         boolean needsReplacing = chestStack.is(Items.ELYTRA)
            ? chestStack.getMaxDamage() - chestStack.getDamageValue() <= this.replaceDurability.getValue()
            : wasFlying && chestStack.isEmpty();
         if (needsReplacing) {
            int slot = InventoryUtils.findInventorySlot(stack ->
               stack.is(Items.ELYTRA) && stack.getMaxDamage() - stack.getDamageValue() > this.replaceDurability.getValue(), false);
            if (slot >= 0) {
               // InventoryMenu slot 6 is the chest armour slot.
               InventoryUtils.swapSlotsWithPickup(InventoryUtils.toMenuSlot(slot), 6);
               // The swap (or the break before it) drops out of the glide; open the new elytra again so the
               // flight, Autopilot, Autohighway and Autonav carry on.
               if (wasFlying) this.reglideTicksLeft = REGLIDE_WINDOW_TICKS;
            }
         }
      }

      this.continueGlideAfterSwap(player);
      this.navigator.tick(player);
      this.glidingLastTick = player.isFallFlying();
   }

   /** After an elytra was swapped in mid-flight, opens it again so the player doesn't drop to the ground. */
   private void continueGlideAfterSwap(LocalPlayer player) {
      if (this.reglideTicksLeft <= 0) return;
      this.reglideTicksLeft--;
      if (player.onGround() || player.isInWater()) {
         this.reglideTicksLeft = 0;
         return;
      }
      if (player.isFallFlying() || !isGlider(player.getItemBySlot(EquipmentSlot.CHEST))) return;
      if (player.tryToStartFallFlying()) {
         player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
      }
   }

   /**
    * Insta Drop: after ElytraFly is switched off mid-glide, stops all motion each tick (and tells the server the
    * player is on the ground) until the glide ends, so the player drops straight down instead of coasting on.
    * Subscribed only while that drop lasts, since the module's own events stop once it is off.
    */
   private final class InstaDropListener {
      private boolean active;
      private ClientLevel world;

      void start() {
         if (this.active) return;
         this.active = true;
         this.world = VolytraElytraFlyHandler.this.minecraft().level;
         ClientAccess.eventBus.subscribe(this);
      }

      void stop() {
         if (!this.active) return;
         this.active = false;
         this.world = null;
         ClientAccess.eventBus.unsubscribe(this);
      }

      @EventHandler
      private void onLocalPlayerTick(LocalPlayerTickEvent event) {
         Minecraft minecraft = VolytraElytraFlyHandler.this.minecraft();
         LocalPlayer player = minecraft.player;
         if (minecraft.level != this.world || player == null || !player.isFallFlying()) {
            this.stop();
            return;
         }
         player.setDeltaMovement(Vec3.ZERO);
         player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, player.horizontalCollision));
      }
   }

   /**
    * Argus smart elytra swap. Called from {@code LocalPlayer.aiStep} just before vanilla checks a Jump press for
    * gliding: equipping here lets vanilla open the wings in the same attempt and send its own normal flight packet,
    * and holding Jump or ordinary jumps never equip wings.
    */
   public void prepareSmartTakeoff() {
      Minecraft minecraft = this.minecraft();
      LocalPlayer player = minecraft.player;
      if (!this.active() || !this.module.isToggled() || !this.tweak(this.smartElytra) || !this.canSmartSwap(player)
         || minecraft.gui.screen() != null || !minecraft.isWindowActive()
         || player.onGround() || player.isFallFlying() || player.isInWater() || player.isInLava()
         || player.isPassenger() || player.getAbilities().flying || player.hasEffect(MobEffects.LEVITATION)) return;
      if (gliderRemaining(player.getItemBySlot(EquipmentSlot.CHEST)) > 1) return;
      this.swapChest(player, true);
      this.smartTakeoffTick = this.clientTicks;
   }

   /** Whether Smart Elytra is managing the chest slot (Volytra mode with the tweak on). */
   public boolean isSmartElytraOn() {
      return this.active() && this.smartElytra.getValue();
   }

   /** Argus landing swap: back on the ground, wear the best chestplate instead of wings (or nothing). */
   private void smartLandingSwap(LocalPlayer player) {
      if (!this.tweak(this.smartElytra) || !this.canSmartSwap(player)) return;
      ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
      if (!chest.isEmpty() && !isGlider(chest)) return;
      // Measure the real gap under the hitbox rather than trusting the onGround flag, which hovering and other
      // modules can report wrongly in both directions.
      double height = this.landingSwapHeight.getValue();
      ClientLevel level = this.minecraft().level;
      double gap = level == null ? Double.POSITIVE_INFINITY : this.groundGap(level, player, height);
      if (!shouldLandingSwap(gap, height, player.getDeltaMovement().y, player.isFallFlying(),
         this.clientTicks - this.smartTakeoffTick)) return;
      this.swapChest(player, false);
   }

   /** Ticks after a smart takeoff during which a landing swap would strip the wings just equipped. */
   static final int TAKEOFF_GRACE_TICKS = 10;
   /** Rising faster than this (blocks/tick) is a jump or climb, never a landing. */
   static final double LANDING_MAX_RISE = 0.05;
   /** While still gliding, only a floor this close (touching) counts, so skimming low never drops the wings. */
   static final double GLIDING_TOUCH_GAP = 0.05;

   /**
    * Whether to swap back to a chestplate: the floor is within {@code height} blocks (touching, while still gliding),
    * the player is not rising, and a smart takeoff has not just equipped the wings.
    */
   static boolean shouldLandingSwap(double groundGap, double height, double verticalSpeed, boolean gliding,
                                    long ticksSinceTakeoff) {
      if (ticksSinceTakeoff < TAKEOFF_GRACE_TICKS) return false;
      if (verticalSpeed > LANDING_MAX_RISE) return false;
      double allowed = gliding ? Math.min(height, GLIDING_TOUCH_GAP) : height;
      return groundGap <= allowed + 1.0E-6;
   }

   /** Gap between the feet and the nearest floor under the hitbox, or infinity beyond {@code height}. */
   private double groundGap(ClientLevel world, LocalPlayer player, double height) {
      // Rays start a little above the feet so a floor the player stands exactly on still counts as zero.
      double lift = 0.1;
      double reach = height + lift + 0.01;
      AABB box = player.getBoundingBox();
      double top = player.getY() + lift;
      double nearest = Double.POSITIVE_INFINITY;
      double[] xs = {box.minX + 1.0E-3, (box.minX + box.maxX) / 2.0, box.maxX - 1.0E-3};
      double[] zs = {box.minZ + 1.0E-3, (box.minZ + box.maxZ) / 2.0, box.maxZ - 1.0E-3};
      for (double x : xs) {
         for (double z : zs) {
            Vec3 start = new Vec3(x, top, z);
            BlockHitResult hit = world.clip(new ClipContext(start, start.add(0.0, -reach, 0.0),
               ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK) continue;
            nearest = Math.min(nearest, Math.max(0.0, player.getY() - hit.getLocation().y));
         }
      }
      return nearest;
   }

   private boolean canSmartSwap(LocalPlayer player) {
      Minecraft minecraft = this.minecraft();
      return player != null && minecraft.level != null && minecraft.gameMode != null && player.isAlive()
         && !player.isSpectator() && player.containerMenu == player.inventoryMenu
         && player.inventoryMenu.getCarried().isEmpty();
   }

   private void swapChest(LocalPlayer player, boolean glider) {
      // Slot permissions also cover a worn Curse of Binding item; never pick it up and strand it on the cursor.
      Slot chestSlot = player.inventoryMenu.getSlot(InventoryMenu.ARMOR_SLOT_START + 1);
      if (!chestSlot.mayPickup(player)) return;
      Inventory inventory = player.getInventory();
      List<SmartElytraSwap.Candidate> candidates = new ArrayList<>();
      // With AutoArmor on, its scoring (protection type, Safe durability, curses) picks the landing chestplate, so
      // the two modules agree and AutoArmor has nothing to swap afterwards.
      AutoArmorModule autoArmor = glider ? null : ClientContext.getModule(AutoArmorModule.class);
      if (autoArmor != null && !autoArmor.isToggled()) autoArmor = null;
      for (int i = 0; i <= Inventory.INVENTORY_SIZE; i++) {
         int slot = i == Inventory.INVENTORY_SIZE ? Inventory.SLOT_OFFHAND : i;
         ItemStack stack = inventory.getItem(slot);
         if (stack.isEmpty() || isGlider(stack) != glider || !chestSlot.mayPlace(stack)
            || EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) continue;
         double[] armor = new double[2];
         if (!glider) stack.forEachModifier(EquipmentSlot.CHEST, (attribute, modifier) -> {
            if (attribute.equals(Attributes.ARMOR)) armor[0] += modifier.amount();
            else if (attribute.equals(Attributes.ARMOR_TOUGHNESS)) armor[1] += modifier.amount();
         });
         if (autoArmor != null) {
            double score = autoArmor.scoreChestplate(stack);
            if (score < 0.0) continue;
            armor[0] = score;
            armor[1] = 0.0;
         }
         int remaining = stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
         candidates.add(new SmartElytraSwap.Candidate(slot, glider, remaining, armor[0], armor[1]));
      }
      int slot = SmartElytraSwap.best(candidates, glider);
      if (slot < 0) return;
      MultiPlayerGameMode gameMode = this.minecraft().gameMode;
      int containerId = player.inventoryMenu.containerId;
      int chestMenuSlot = chestSlot.index;
      if (Inventory.isHotbarSlot(slot) || slot == Inventory.SLOT_OFFHAND) {
         // A single click exchanges both items, even with a completely full inventory.
         gameMode.handleContainerInput(containerId, chestMenuSlot, slot, ContainerInput.SWAP, player);
      } else {
         // Main inventory slots 9-35 share their index with the inventory menu.
         gameMode.handleContainerInput(containerId, slot, 0, ContainerInput.PICKUP, player);
         gameMode.handleContainerInput(containerId, chestMenuSlot, 0, ContainerInput.PICKUP, player);
         if (!player.inventoryMenu.getCarried().isEmpty()) {
            gameMode.handleContainerInput(containerId, slot, 0, ContainerInput.PICKUP, player);
         }
      }
      if (glider) this.boostDelay.swapped();
   }

   private void observeBoostDelay(LocalPlayer player) {
      this.boostDelay.observe(this.clientTicks, isGlider(player.getItemBySlot(EquipmentSlot.CHEST)), player.isFallFlying());
   }

   private static boolean isGlider(ItemStack stack) {
      return !stack.isEmpty() && stack.has(DataComponents.GLIDER);
   }

   /** Remaining durability of a glider (unbreakable ones never wear out), otherwise -1. */
   private static int gliderRemaining(ItemStack stack) {
      if (!isGlider(stack)) return -1;
      return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
   }

   @Override
   public void handleIncomingPacket(PacketEvent.Receive event) {
      if (!(event.getPacket() instanceof ClientboundPlayerPositionPacket)) return;

      this.positionCorrections++;
      this.argusCorrections.incrementAndGet();
      Minecraft minecraft = this.minecraft();
      LocalPlayer player = minecraft.player;
      ClientLevel world = minecraft.level;
      boolean inFlight = player != null && world != null && player.isFallFlying();

      if (inFlight && this.rubberbandSpeedCap.getValue()) {
         double speedBps = Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z) * 20.0;
         double thresholdBps = this.rubberbandSpeedCapThreshold.getValue();
         double toleranceBps = thresholdBps * (RUBBERBAND_CAP_TOLERANCE_PERCENT / 100.0);

         if (speedBps >= thresholdBps && speedBps <= thresholdBps + toleranceBps) {
            long now = System.nanoTime();
            this.rubberbandQualifyingTimestamps.addLast(now);
            while (!this.rubberbandQualifyingTimestamps.isEmpty()
               && now - this.rubberbandQualifyingTimestamps.peekFirst() > RUBBERBAND_CAP_TRIGGER_WINDOW_NANOS) {
               this.rubberbandQualifyingTimestamps.pollFirst();
            }

            if (this.rubberbandQualifyingTimestamps.size() >= RUBBERBAND_CAP_TRIGGER_COUNT) {
               this.rubberbandCapTicksLeft = (int)Math.round(RUBBERBAND_CAP_DURATION_SECONDS * 20.0);
               this.rubberbandCapSpeed = thresholdBps / 20.0;
               this.rubberbandQualifyingTimestamps.clear();
            }
         }
      }

      if (inFlight) {
         double keptSpeed = rewindSpeedFor(this.acceleration, this.accelerationStep.getValue(), this.accelerationPlateau.getValue(), this.effectiveHorizontalStartSpeed(), SPEED_PRESERVATION_REWIND_TICKS);
         if (keptSpeed < this.acceleration) {
            this.acceleration = keptSpeed;
            this.atMaxSpeed = false;
            this.accelerationDelayTicks = 0;

            this.verticalAcceleration = rewindSpeedFor(this.verticalAcceleration, this.verticalAccelerationStep.getValue(), this.verticalAccelerationPlateau.getValue(), this.effectiveVerticalStartSpeed(), SPEED_PRESERVATION_REWIND_TICKS);
            this.atMaxVerticalSpeed = false;
            this.verticalAccelerationDelayTicks = 0;
         }
         return;
      }

      this.zeroAcceleration();
   }

   private void autoTakeoff(LocalPlayer player) {
      if (this.incrementJumpTimer) this.jumpTimer++;

      this.jumpPressed = PlayerMovementKeys.isDown(this.minecraft().options.keyJump);

      // Smart Elytra owns the jump-to-flight path; don't let the older auto-takeoff
      // setting fire a second takeoff from the same jump.
      if (this.autoTakeOff.getValue() && !this.tweak(this.smartElytra) && this.jumpPressed) {
         if (!this.lastJumpPressed && !player.isFallFlying()) {
            this.jumpTimer = 0;
            this.incrementJumpTimer = true;
         }

         if (this.jumpTimer >= 8) {
            this.jumpTimer = 0;
            this.incrementJumpTimer = false;
            player.setJumping(false);
            player.setSprinting(true);
            player.jumpFromGround();
            player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
         }
      }

      this.lastJumpPressed = this.jumpPressed;
   }

   private void handleAutopilot(LocalPlayer player) {
      if (!player.isFallFlying()) return;

      if (!this.avoidanceSteering) {
         // Autonav, then Autohighway, then plain Autopilot
         if (!this.navigator.drive(player)) {
            this.releaseMovementKeys();
            this.releaseVerticalKeys();
            if (this.autoPilot.getValue() && player.getY() > this.autoPilotMinimumHeight.getValue()) {
               this.setKey(this.minecraft().options.keyUp, true);
               this.lastForwardPressed = true;
            }
         }
      } else {
         // Avoidance has the keys this tick; don't repoint its dodge at a waypoint
         this.navigator.clearSteering();
      }

      if (this.useFireworks.getValue()) {
         if (this.ticksLeft <= 0) {
            this.ticksLeft = (int)Math.round(this.autoPilotFireworkDelay.getValue() * 20.0);
            if (!this.useFirework(player)) return;
         }
         this.ticksLeft--;
      }
   }

   private boolean useFirework(LocalPlayer player) {
      Minecraft minecraft = this.minecraft();
      if (minecraft.gameMode == null) return false;

      if (player.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
         minecraft.gameMode.useItem(player, InteractionHand.OFF_HAND);
         player.swing(InteractionHand.OFF_HAND);
         return true;
      }

      int slot = InventoryUtils.findHotbarSlot(Items.FIREWORK_ROCKET);
      if (slot < 0) return false;

      int previousSlot = InventoryUtils.getSelectedSlot();
      InventoryUtils.selectHotbarSlot(slot);
      minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
      player.swing(InteractionHand.MAIN_HAND);
      InventoryUtils.selectHotbarSlot(previousSlot);
      return true;
   }

   private void handleHorizontalSpeed() {
      boolean forwardPressed = false;
      boolean sidewaysPressed = false;

      if (PlayerMovementKeys.isDown(this.minecraft().options.keyUp)) {
         this.velX += this.forward.x * this.acceleration * 10.0;
         this.velZ += this.forward.z * this.acceleration * 10.0;
         forwardPressed = true;
      } else if (PlayerMovementKeys.isDown(this.minecraft().options.keyDown)) {
         this.velX -= this.forward.x * this.acceleration * 10.0;
         this.velZ -= this.forward.z * this.acceleration * 10.0;
         forwardPressed = true;
      }

      if (PlayerMovementKeys.isDown(this.minecraft().options.keyRight)) {
         this.velX += this.right.x * this.acceleration * 10.0;
         this.velZ += this.right.z * this.acceleration * 10.0;
         sidewaysPressed = true;
      } else if (PlayerMovementKeys.isDown(this.minecraft().options.keyLeft)) {
         this.velX -= this.right.x * this.acceleration * 10.0;
         this.velZ -= this.right.z * this.acceleration * 10.0;
         sidewaysPressed = true;
      }

      if (forwardPressed && sidewaysPressed) {
         double diagonal = 1.0 / Math.sqrt(2.0);
         this.velX *= diagonal;
         this.velZ *= diagonal;
      }

   }

   private void handleVerticalSpeed() {
      // Volytra's own model flies at half the vertical value; the Tweaks base/boost sliders are real speeds.
      double scale = this.tweak(this.customSpeeds) ? 1.0 : 0.5;
      if (PlayerMovementKeys.isDown(this.minecraft().options.keyJump)) {
         this.velY += scale * this.verticalAcceleration;
      } else if (PlayerMovementKeys.isDown(this.minecraft().options.keyShift)) {
         this.velY -= scale * this.verticalAcceleration;
      }
   }

   private void handleFallMultiplier() {
      if (this.velY < 0.0) {
         this.velY *= this.fallMultiplier.getValue();
      } else if (this.velY > 0.0) {
         this.velY = 0.0;
      }
   }

   private void handleAntiSlam(ClientLevel world, LocalPlayer player) {
      if (!this.antiSlam.getValue() || this.velY >= 0.0) return;

      double currentSpeed = -this.velY;
      double minSpeed = this.antiSlamMinSpeed.getValue();
      if (currentSpeed <= minSpeed) return;

      double startDist = this.antiSlamDistance.getValue();
      double minDist = Math.min(this.antiSlamMinDistance.getValue(), startDist);

      double distanceToGround = this.distanceToGroundBelow(world, player, startDist);
      if (distanceToGround >= startDist) return;

      double allowedSpeed;
      if (distanceToGround <= minDist) {
         allowedSpeed = minSpeed;
      } else {
         double t = (distanceToGround - minDist) / Math.max(startDist - minDist, 0.0001);
         allowedSpeed = minSpeed + t * (currentSpeed - minSpeed);
      }

      if (allowedSpeed < currentSpeed) {
         this.velY = -allowedSpeed;
      }
   }

   private double distanceToGroundBelow(ClientLevel world, LocalPlayer player, double maxDistance) {
      AABB box = player.getBoundingBox();
      double y = player.getY();
      double[] xs = {box.minX, (box.minX + box.maxX) / 2.0, box.maxX};
      double[] zs = {box.minZ, (box.minZ + box.maxZ) / 2.0, box.maxZ};
      double nearest = maxDistance;

      for (double x : xs) {
         for (double z : zs) {
            Vec3 start = new Vec3(x, y, z);
            Vec3 end = start.add(0.0, -maxDistance, 0.0);
            BlockHitResult hit = world.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK) continue;

            double distance = y - hit.getLocation().y;
            if (distance < nearest) nearest = distance;
         }
      }
      return nearest;
   }

   private void handleMaxHeight(LocalPlayer player) {
      if (!this.limitMaxHeight.getValue() || this.velY <= 0.0) return;

      double limit = this.maxHeight.getValue();
      double currentY = player.getY();
      if (currentY >= limit) {
         this.velY = 0.0;
      } else if (currentY + this.velY > limit) {
         this.velY = limit - currentY;
      }
   }

   private boolean isMovementKeyPressed() {
      return PlayerMovementKeys.isDown(this.minecraft().options.keyUp)
         || PlayerMovementKeys.isDown(this.minecraft().options.keyDown)
         || PlayerMovementKeys.isDown(this.minecraft().options.keyLeft)
         || PlayerMovementKeys.isDown(this.minecraft().options.keyRight);
   }

   private void resetRampOnCollision(LocalPlayer player) {
      if (this.lastGlidePos == null) return;
      if (!player.horizontalCollision) return;
      if (this.lastGlideIntendedSpeed < 1.0E-3) return;

      Vec3 now = player.position();
      double moved = Math.hypot(now.x - this.lastGlidePos.x, now.z - this.lastGlidePos.z);
      if (moved >= this.lastGlideIntendedSpeed - 1.0E-3) return;

      double kept = Math.max(this.effectiveHorizontalStartSpeed(), moved);
      if (kept < this.acceleration) this.acceleration = kept;
      double cap = this.effectiveHorizontalSpeedCap();
      this.atMaxSpeed = this.acceleration >= cap;
      this.accelerationDelayTicks = 0;
   }

   private void handleAcceleration() {
      if (!this.isMovementKeyPressed()) {
         this.resetHorizontalAcceleration();
         return;
      }

      double cap = this.effectiveHorizontalSpeedCap();
      if (this.acceleration > cap) {
         this.acceleration = cap;
         this.atMaxSpeed = true;
         this.accelerationDelayTicks = 0;
         return;
      }
      if (this.atMaxSpeed && this.acceleration >= cap) return;
      if (this.acceleration < cap) this.atMaxSpeed = false;

      if (this.accelerationDelayTicks < this.accelerationDelay.getValue()) {
         this.accelerationDelayTicks++;
         return;
      }

      this.acceleration = nextSpeedFor(this.acceleration, this.accelerationStep.getValue(), this.accelerationPlateau.getValue(), cap);
      if (this.acceleration >= cap) {
         this.atMaxSpeed = true;
      }
   }

   private static double curveGain(double currentSpeed, double step, double plateau) {
      if (plateau <= 0.0) return 0.0;
      double remainingToPlateau = Math.max(0.0, plateau - currentSpeed);
      return step * (remainingToPlateau / plateau);
   }

   private static double nextSpeedFor(double currentSpeed, double step, double plateau, double cap) {
      return Math.min(currentSpeed + curveGain(currentSpeed, step, plateau), cap);
   }

   private static double rewindSpeedFor(double currentSpeed, double step, double plateau, double floor, int ticks) {
      double speed = currentSpeed;
      for (int i = 0; i < ticks && speed > floor; i++) {
         speed = Math.max(floor, speed - curveGain(speed, step, plateau));
      }
      return speed;
   }

   private void zeroAcceleration() {
      this.resetHorizontalAcceleration();
      this.resetVerticalAcceleration();
   }

   private void resetVerticalAcceleration() {
      this.atMaxVerticalSpeed = false;
      this.verticalAccelerationDelayTicks = 0;
      this.verticalAcceleration = this.effectiveVerticalStartSpeed();
   }

   private void resetHorizontalAcceleration() {
      this.atMaxSpeed = false;
      this.accelerationDelayTicks = 0;
      this.acceleration = this.effectiveHorizontalStartSpeed();
   }

   /** The boost key (Sprint unless rebound) held physically while playing, with no screen open. */
   private boolean boostKeyHeld() {
      Minecraft minecraft = this.minecraft();
      return minecraft.gui.screen() == null && !PlayerMovementKeys.isFreecamActive() && this.boostKey.isPhysicallyDown(minecraft.options.keySprint);
   }

   /** Boost H Speed in blocks/tick; at the slider's top ("MAX") it is Volytra's own Horizontal Speed. */
   private double boostHorizontalCap() {
      return this.boostHorizontalSpeed.isAtMaximum()
         ? this.horizontalSpeed.getValue()
         : Math.min(this.boostHorizontalSpeed.getValue(), BOOST_H_NUMERIC_MAX) / 20.0;
   }

   private boolean boostActive() {
      return this.tweak(this.customSpeeds) && this.boostKeyHeld();
   }

   private double effectiveHorizontalSpeedCap() {
      double cap = this.tweak(this.customSpeeds)
         ? (this.boostActive() ? this.boostHorizontalCap() : this.baseHorizontalSpeed.getValue() / 20.0)
         : this.horizontalSpeed.getValue();
      if (this.rubberbandCapTicksLeft > 0) cap = Math.min(cap, this.rubberbandCapSpeed);
      return Math.max(0.0, cap);
   }

   private double effectiveVerticalSpeedCap() {
      double cap = this.tweak(this.customSpeeds)
         ? (this.boostActive() ? this.boostVerticalSpeed.getValue() : this.baseVerticalSpeed.getValue()) / 20.0
         : this.verticalSpeed.getValue();
      return Math.max(0.0, cap);
   }

   /** Volytra's Start Speed, or 1 while Autohighway or Autonav slows down to break out of a loop. */
   private double curveStartSpeed() {
      double start = this.startSpeed.getValue();
      return this.navigator.unsticking() ? Math.min(VolytraNavigator.UNSTICK_MIN_SPEED, start) : start;
   }

   private double effectiveHorizontalStartSpeed() {
      return this.tweak(this.customSpeeds)
         ? Math.min(this.curveStartSpeed(), this.effectiveHorizontalSpeedCap())
         : this.curveStartSpeed();
   }

   private double effectiveVerticalStartSpeed() {
      double start = this.verticalStartSpeed.getValue();
      if (this.navigator.unsticking()) start = Math.min(VolytraNavigator.UNSTICK_MIN_SPEED, start);
      return this.tweak(this.customSpeeds) ? Math.min(start, this.effectiveVerticalSpeedCap()) : start;
   }

   // ---------------------------------------------------------------- hooks for VolytraNavigator

   /** Restarts the speed ramps from their start, as Autonav does on taking off, arriving and unsticking. */
   void resetRamps() {
      this.zeroAcceleration();
      this.argusFlying = false;
   }

   /** The clearance guard cut this tick's move short: the ramp drops to what was kept. */
   void onGuardSpeedLoss(double keptHorizontal) {
      double kept = Math.max(this.effectiveHorizontalStartSpeed(), keptHorizontal);
      if (kept < this.acceleration) this.acceleration = kept;
      this.atMaxSpeed = this.acceleration >= this.effectiveHorizontalSpeedCap();
      this.accelerationDelayTicks = 0;
   }

   /** The highest Y Autonav and Autohighway may aim for: Max Height, or none when Limit Height is off. */
   double flightCeiling() {
      return this.limitMaxHeight.getValue() ? this.maxHeight.getValue() : Double.POSITIVE_INFINITY;
   }

   int flightCeilingBlock() {
      return this.limitMaxHeight.getValue() ? (int) Math.floor(this.maxHeight.getValue()) : 100_000;
   }

   boolean limitsHeight() {
      return this.limitMaxHeight.getValue();
   }

   /** The fastest the flight goes, in blocks per tick. */
   double topHorizontalSpeed() {
      return this.effectiveHorizontalSpeedCap();
   }

   /** Sets a key's state unless the player is physically holding it. */
   void forceKey(KeyMapping mapping, boolean down) {
      if (!this.isKeyPhysicallyPressed(mapping)) this.setKey(mapping, down);
   }

   private void handleVerticalAcceleration() {
      boolean movingUp = PlayerMovementKeys.isDown(this.minecraft().options.keyJump);
      boolean movingDown = PlayerMovementKeys.isDown(this.minecraft().options.keyShift);

      if (!movingUp && !movingDown) {
         this.resetVerticalAcceleration();
         return;
      }

      if (movingUp && !this.accelerateUpward.getValue()) {
         this.resetVerticalAcceleration();
         return;
      }

      double cap = this.effectiveVerticalSpeedCap();
      if (this.tweak(this.argusMotion)) {
         if (this.verticalAcceleration > cap) {
            this.verticalAcceleration = Math.max(cap, this.verticalAcceleration - this.argusMotionBraking.getValue());
            this.atMaxVerticalSpeed = this.verticalAcceleration >= cap;
            return;
         }
         if (this.verticalAccelerationDelayTicks < this.accelerationDelay.getValue()) {
            this.verticalAccelerationDelayTicks++;
            return;
         }
         if (this.verticalAcceleration < cap) {
            this.verticalAcceleration = Math.min(cap, this.verticalAcceleration + this.argusMotionAcceleration.getValue());
         }
         this.atMaxVerticalSpeed = this.verticalAcceleration >= cap;
         return;
      }

      if (this.verticalAcceleration > cap) {
         this.verticalAcceleration = cap;
         this.atMaxVerticalSpeed = true;
         this.verticalAccelerationDelayTicks = 0;
         return;
      }
      if (this.atMaxVerticalSpeed && this.verticalAcceleration >= cap) return;
      if (this.verticalAcceleration < cap) this.atMaxVerticalSpeed = false;

      if (this.verticalAccelerationDelayTicks < this.accelerationDelay.getValue()) {
         this.verticalAccelerationDelayTicks++;
         return;
      }

      this.verticalAcceleration = nextSpeedFor(this.verticalAcceleration, this.verticalAccelerationStep.getValue(), this.verticalAccelerationPlateau.getValue(), cap);
      if (this.verticalAcceleration >= cap) {
         this.atMaxVerticalSpeed = true;
      }
   }

   private void updatePlayerAvoidance(LocalPlayer player) {
      if (!this.playerAvoidance.getValue()) {
         this.releaseAvoidance();
         this.releaseVerticalStep();
         return;
      }

      if (this.isMovementKeyPhysicallyPressed() || this.isKeyPhysicallyPressed(this.minecraft().options.keyShift)) {
         this.releaseAvoidance();
         this.releaseVerticalStep();
         return;
      }

      Vec3 away = this.findAvoidanceAwayVector(player);
      if (away == null) {
         this.releaseAvoidance();
         this.releaseVerticalStep();
         return;
      }

      if (!player.isFallFlying()) {
         this.releaseAvoidance();
         this.releaseVerticalStep();
         return;
      }

      if (this.verticalStepActive) {
         this.avoidanceSteering = true;
         this.stepVerticalStep(player);
         return;
      }

      this.steerTowardsDirection(player, away);
      this.avoidanceSteering = true;
      this.updateAvoidanceStuckDetection(player);
   }

   private void steerTowardsDirection(LocalPlayer player, Vec3 dir) {
      double targetYaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
      double relative = wrapDegrees(targetYaw - player.getYRot());
      int octant = ((int)Math.round(relative / 45.0) % 8 + 8) % 8;

      this.setKey(this.minecraft().options.keyUp, octant == 0 || octant == 1 || octant == 7);
      this.setKey(this.minecraft().options.keyDown, octant == 3 || octant == 4 || octant == 5);
      this.setKey(this.minecraft().options.keyRight, octant == 1 || octant == 2 || octant == 3);
      this.setKey(this.minecraft().options.keyLeft, octant == 5 || octant == 6 || octant == 7);
   }

   private Vec3 findAvoidanceAwayVector(LocalPlayer player) {
      ClientLevel world = this.minecraft().level;
      if (world == null) return null;

      double[] acc = {0.0, 0.0};
      boolean foundThreat = false;

      for (AbstractClientPlayer other : world.players()) {
         if (other == player) continue;
         if (this.avoidanceIgnoreFriends.getValue() && ClientContext.socialRegistry.isFriendPlayer(other)) continue;
         if (this.accumulateThreat(player, other.position(), this.avoidanceRadius.getValue(), acc)) foundThreat = true;
      }

      if (this.avoidWitherSkulls.getValue() || this.avoidArrows.getValue()) {
         for (Entity entity : world.entitiesForRendering()) {
            if (this.avoidWitherSkulls.getValue() && entity instanceof WitherSkull) {
               if (this.accumulateThreat(player, entity.position(), this.witherSkullRadius.getValue(), acc)) foundThreat = true;
            } else if (this.avoidArrows.getValue() && entity instanceof Arrow) {
               if (this.accumulateThreat(player, entity.position(), this.arrowRadius.getValue(), acc)) foundThreat = true;
            }
         }
      }

      if (this.avoidBlocks.getValue() && this.isBlockAvoidanceTriggered(player)) {
         if (this.accumulateNearbyBlockThreats(player, this.blockAvoidanceRadius.getValue(), acc)) foundThreat = true;
      }

      if (!foundThreat) return null;

      Vec3 direction = new Vec3(acc[0], 0.0, acc[1]);
      if (direction.lengthSqr() == 0.0) return null;
      direction = direction.normalize();

      if (this.avoidanceLateral.getValue()) {
         direction = this.lateralize(player, direction);
      } else {
         this.avoidanceLateralDir = null;
      }

      return direction;
   }

   private boolean isBlockAvoidanceTriggered(LocalPlayer player) {
      ClientLevel world = this.minecraft().level;
      if (world == null) return false;

      Vec3 selfPos = player.position();
      double radius = this.avoidanceRadius.getValue();
      if (radius > 0.0) {
         double radiusSq = radius * radius;
         for (AbstractClientPlayer other : world.players()) {
            if (other == player) continue;
            if (this.avoidanceIgnoreFriends.getValue() && ClientContext.socialRegistry.isFriendPlayer(other)) continue;
            if (selfPos.distanceToSqr(other.position()) < radiusSq) return true;
         }
      }

      double skullRadius = this.witherSkullRadius.getValue();
      if (this.avoidWitherSkulls.getValue() && skullRadius > 0.0) {
         double skullRadiusSq = skullRadius * skullRadius;
         for (Entity entity : world.entitiesForRendering()) {
            if (entity instanceof WitherSkull && selfPos.distanceToSqr(entity.position()) < skullRadiusSq) return true;
         }
      }

      return false;
   }

   private Vec3 lateralize(LocalPlayer player, Vec3 away) {
      Vec3 perpA = new Vec3(-away.z, 0.0, away.x);
      Vec3 perpB = new Vec3(away.z, 0.0, -away.x);

      boolean sideA;
      if (this.avoidanceLateralDir != null) {
         sideA = this.avoidanceLateralDir.dot(perpA) >= this.avoidanceLateralDir.dot(perpB);
      } else {
         Vec3 velocity = player.getDeltaMovement();
         Vec3 horizontalVelocity = new Vec3(velocity.x, 0.0, velocity.z);
         sideA = !(horizontalVelocity.lengthSqr() > 1.0E-4 && horizontalVelocity.dot(perpB) > horizontalVelocity.dot(perpA));
      }

      double baseAngle = sideA ? 90.0 : -90.0;
      Vec3 optionA = rotateHorizontal(away, baseAngle - 10.0);
      Vec3 optionB = rotateHorizontal(away, baseAngle + 10.0);
      Vec3 chosen = optionA.dot(away) >= optionB.dot(away) ? optionA : optionB;

      this.avoidanceLateralDir = chosen;
      return chosen;
   }

   private static Vec3 rotateHorizontal(Vec3 vector, double degrees) {
      double radians = Math.toRadians(degrees);
      double cos = Math.cos(radians);
      double sin = Math.sin(radians);
      return new Vec3(vector.x * cos - vector.z * sin, 0.0, vector.x * sin + vector.z * cos);
   }

   private boolean accumulateThreat(LocalPlayer player, Vec3 threatPos, double radius, double[] acc) {
      if (radius <= 0.0) return false;

      double distanceSq = player.position().distanceToSqr(threatPos);
      if (distanceSq >= radius * radius || distanceSq == 0.0) return false;

      Vec3 awayFromThreat = player.position().subtract(threatPos);
      awayFromThreat = new Vec3(awayFromThreat.x, 0.0, awayFromThreat.z);
      if (awayFromThreat.lengthSqr() == 0.0) return false;

      double weight = 1.0 - (Math.sqrt(distanceSq) / radius);
      awayFromThreat = awayFromThreat.normalize().scale(weight);
      acc[0] += awayFromThreat.x;
      acc[1] += awayFromThreat.z;
      return true;
   }

   private boolean accumulateNearbyBlockThreats(LocalPlayer player, double radius, double[] acc) {
      ClientLevel world = this.minecraft().level;
      if (world == null || radius <= 0.0) return false;

      boolean foundThreat = false;
      BlockPos center = player.blockPosition();
      int r = (int)Math.ceil(radius);
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

      for (int dx = -r; dx <= r; dx++) {
         for (int dy = 0; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
               pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
               var state = world.getBlockState(pos);
               boolean isCobweb = state.is(Blocks.COBWEB);
               if (!isCobweb && state.getCollisionShape(world, pos).isEmpty()) continue;

               Vec3 blockCenter = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
               if (this.accumulateThreat(player, blockCenter, radius, acc)) foundThreat = true;
            }
         }
      }
      return foundThreat;
   }

   private void updateAvoidanceStuckDetection(LocalPlayer player) {
      this.avoidanceStuckTicksCount = player.horizontalCollision ? this.avoidanceStuckTicksCount + 1 : 0;
      if (this.avoidanceStuckTicksCount < AVOIDANCE_STUCK_TICKS) return;
      this.beginVerticalStep(player);
   }

   private void beginVerticalStep(LocalPlayer player) {
      boolean upClear = this.hasVerticalClearance(player, 1.0);
      boolean downClear = this.hasVerticalClearance(player, -1.0);

      if (upClear) {
         this.verticalStepUp = true;
      } else if (downClear) {
         this.verticalStepUp = false;
      } else {
         this.avoidanceStuckTicksCount = 0;
         return;
      }

      this.verticalStepActive = true;
      this.verticalStepStartY = player.getY();
      this.verticalStepTicks = 0;
      this.avoidanceStuckTicksCount = 0;
      this.releaseHorizontalKeys();
   }

   private boolean hasVerticalClearance(LocalPlayer player, double dy) {
      ClientLevel world = this.minecraft().level;
      if (world == null) return false;
      AABB box = player.getBoundingBox().move(0.0, dy, 0.0);
      return !world.getBlockCollisions(player, box).iterator().hasNext();
   }

   private void stepVerticalStep(LocalPlayer player) {
      this.verticalStepTicks++;
      double traveled = Math.abs(player.getY() - this.verticalStepStartY);
      if (traveled >= 1.0 || this.verticalStepTicks > VERTICAL_STEP_TIMEOUT_TICKS) {
         this.releaseVerticalStep();
         return;
      }

      if (!this.isKeyPhysicallyPressed(this.minecraft().options.keyJump)) {
         this.setKey(this.minecraft().options.keyJump, this.verticalStepUp);
      }
      if (!this.isKeyPhysicallyPressed(this.minecraft().options.keyShift)) {
         this.setKey(this.minecraft().options.keyShift, !this.verticalStepUp);
      }
   }

   private void releaseVerticalStep() {
      this.avoidanceStuckTicksCount = 0;

      if (this.verticalStepActive) {
         if (!this.isKeyPhysicallyPressed(this.minecraft().options.keyJump)) this.setKey(this.minecraft().options.keyJump, false);
         if (!this.isKeyPhysicallyPressed(this.minecraft().options.keyShift)) this.setKey(this.minecraft().options.keyShift, false);
      }

      this.verticalStepActive = false;
      this.verticalStepTicks = 0;
   }

   private void releaseHorizontalKeys() {
      if (!this.isKeyPhysicallyPressed(this.minecraft().options.keyUp)) this.setKey(this.minecraft().options.keyUp, false);
      if (!this.isKeyPhysicallyPressed(this.minecraft().options.keyDown)) this.setKey(this.minecraft().options.keyDown, false);
      if (!this.isKeyPhysicallyPressed(this.minecraft().options.keyLeft)) this.setKey(this.minecraft().options.keyLeft, false);
      if (!this.isKeyPhysicallyPressed(this.minecraft().options.keyRight)) this.setKey(this.minecraft().options.keyRight, false);
   }

   private void releaseMovementKeys() {
      this.releaseHorizontalKeys();
   }

   private void releaseVerticalKeys() {
      if (!this.isKeyPhysicallyPressed(this.minecraft().options.keyJump)) this.setKey(this.minecraft().options.keyJump, false);
      if (!this.isKeyPhysicallyPressed(this.minecraft().options.keyShift)) this.setKey(this.minecraft().options.keyShift, false);
   }

   private void releaseAllForcedKeys() {
      this.releaseHorizontalKeys();
      this.releaseVerticalKeys();
   }

   private void releaseAvoidance() {
      this.avoidanceLateralDir = null;
      if (!this.avoidanceSteering) return;
      this.releaseHorizontalKeys();
      this.avoidanceSteering = false;
   }

   boolean isMovementKeyPhysicallyPressed() {
      return this.isKeyPhysicallyPressed(this.minecraft().options.keyUp)
         || this.isKeyPhysicallyPressed(this.minecraft().options.keyDown)
         || this.isKeyPhysicallyPressed(this.minecraft().options.keyLeft)
         || this.isKeyPhysicallyPressed(this.minecraft().options.keyRight);
   }

   private boolean isKeyPhysicallyPressed(KeyMapping mapping) {
      return KeyUtils.isPhysicallyDown(mapping);
   }

   private void setKey(KeyMapping mapping, boolean down) {
      mapping.setDown(down);
   }

   private boolean canManageInventory(LocalPlayer player) {
      return this.minecraft().gui.screen() == null && player.containerMenu instanceof InventoryMenu;
   }

   private Minecraft minecraft() {
      return ClientAccess.argusclientMinecraft;
   }

   private static double wrapDegrees(double degrees) {
      double wrapped = degrees % 360.0;
      if (wrapped >= 180.0) wrapped -= 360.0;
      if (wrapped < -180.0) wrapped += 360.0;
      return wrapped;
   }
}
