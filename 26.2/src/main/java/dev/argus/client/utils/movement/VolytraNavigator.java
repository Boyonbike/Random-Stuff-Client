package dev.argus.client.utils.movement;

/*
 * Autohighway, Autonav, relaunch-after-landing and loop unsticking for Volytra Fly.
 * Ported and adapted from VolytraFly by Volizray (https://github.com/Volizray/VolytraFly-Addon), CC0 1.0 Universal.
 * See THIRD_PARTY_NOTICES.md and licenses/VolytraFly-CC0.txt.
 */

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.argus.client.ClientAccess;
import dev.argus.client.modules.Keybind;
import dev.argus.client.settings.BooleanSetting;
import dev.argus.client.settings.EnumSetting;
import dev.argus.client.settings.KeyBindSetting;
import dev.argus.client.settings.NamedEntry;
import dev.argus.client.settings.Setting;
import dev.argus.client.utils.KeyUtils;
import dev.argus.client.utils.chat.ChatUtils;
import dev.argus.client.utils.movement.volytra.AutonavPlanner;
import dev.argus.client.utils.movement.volytra.AutonavWaypoint;
import dev.argus.client.utils.movement.volytra.XaeroMapClicks;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Volytra Fly's automatic navigation. Autohighway locks onto the nearest world highway line (an axis or diagonal
 * through 0, 0) and flies it with a throwaway parrot's mob pathfinder; Autonav flies to typed coordinates or a spot
 * clicked on Xaero's World Map, planning round obstacles through loaded air with {@link AutonavPlanner}. Both steer by
 * holding forward and repointing the flight's horizontal velocity at the next waypoint, take off again after an
 * accidental landing, and drop to a slow speed when they keep circling one spot (a rubberband loop).
 */
final class VolytraNavigator {
   public enum HighwayDirection implements NamedEntry {
      AWAY_FROM_CENTRE("Away From Centre"),
      TOWARDS_CENTRE("Towards Centre");

      private final String name;

      HighwayDirection(String name) {
         this.name = name;
      }

      @Override
      public String getName() {
         return this.name;
      }
   }

   public enum Intelligence implements NamedEntry {
      LOW("Low", 8, 20, 5.0, 25_000),
      MEDIUM("Medium", 64, 8, 3.0, 80_000),
      HIGH("High", 256, 6, 2.0, 200_000);

      private final String name;
      final int pathRange;
      final int repathInterval;
      final double stuckTimeout;
      final int mapSearchNodes;

      Intelligence(String name, int pathRange, int repathInterval, double stuckTimeout, int mapSearchNodes) {
         this.name = name;
         this.pathRange = pathRange;
         this.repathInterval = repathInterval;
         this.stuckTimeout = stuckTimeout;
         this.mapSearchNodes = mapSearchNodes;
      }

      @Override
      public String getName() {
         return this.name;
      }
   }

   public enum MapClickButton implements NamedEntry {
      MIDDLE("Middle", InputConstants.MOUSE_BUTTON_MIDDLE),
      RIGHT("Right", InputConstants.MOUSE_BUTTON_RIGHT),
      LEFT("Left", InputConstants.MOUSE_BUTTON_LEFT);

      private final String name;
      final int button;

      MapClickButton(String name, int button) {
         this.name = name;
         this.button = button;
      }

      @Override
      public String getName() {
         return this.name;
      }
   }

   // Unstick: not getting further than UNSTICK_RADIUS from one spot for UNSTICK_WINDOW_TICKS is a loop; the
   // minimum speeds then drop to UNSTICK_MIN_SPEED for UNSTICK_SLOW_TICKS and the ramp restarts from there.
   static final double UNSTICK_MIN_SPEED = 1.0;
   private static final double UNSTICK_RADIUS = 16.0;
   private static final int UNSTICK_WINDOW_TICKS = 60;
   private static final int UNSTICK_SLOW_TICKS = 80;

   private static final int RELAUNCH_RETRY_TICKS = 2;
   private static final int RELAUNCH_MAX_ATTEMPTS = 15;
   private static final int RELAUNCH_PAUSE_TICKS = 60;
   private static final int RELAUNCH_STABLE_TICKS = 20;
   private static final int MAP_ABOVE_LIMIT_ROOM = 32;

   private static final double HIGHWAY_BLOCK_CLEARANCE = 0.1;
   // The hitbox as the server sees it (a hair smaller, so resting flush against a block isn't a hit)
   private static final double HIGHWAY_TRUE_MARGIN = -0.001;
   private static final double HIGHWAY_CENTRE_RADIUS = 1.0;
   private static final double HIGHWAY_STUCK_MOVE_EPSILON = 0.05;
   private static final double HIGHWAY_NODE_HEIGHT = 0.2;
   private static final double HIGHWAY_HARD_MARGIN = 0.02;
   private static final double HIGHWAY_HEIGHT_BAND = 0.3;
   private static final int HIGHWAY_MAX_LOOKAHEAD_NODES = 48;
   private static final int HIGHWAY_MAX_ROUTE_NODES = 96;
   private static final int HIGHWAY_HEALTHY_ROUTE_NODES = 60;
   private static final int HIGHWAY_HEALTHY_REPATH_FACTOR = 3;
   private static final int HIGHWAY_DETOUR_RADIUS = 3;
   private static final int HIGHWAY_DETOUR_MAX_CELLS = 4000;

   private static final int MAP_HIGHWAY_NONE = 0;
   private static final int MAP_HIGHWAY_JOINING = 1;
   private static final int MAP_HIGHWAY_FOLLOWING = 2;
   private static final double MAP_HIGHWAY_DIAGONAL = Math.sqrt(0.5);
   private static final double[][] MAP_HIGHWAY_DIRECTIONS = {
      {1, 0}, {-1, 0}, {0, 1}, {0, -1},
      {MAP_HIGHWAY_DIAGONAL, MAP_HIGHWAY_DIAGONAL}, {-MAP_HIGHWAY_DIAGONAL, -MAP_HIGHWAY_DIAGONAL},
      {MAP_HIGHWAY_DIAGONAL, -MAP_HIGHWAY_DIAGONAL}, {-MAP_HIGHWAY_DIAGONAL, MAP_HIGHWAY_DIAGONAL}
   };
   private static final int[] MAP_HIGHWAY_LINES = {0, 2, 4, 6};
   private static final double MAP_HIGHWAY_MIN_FOLLOW = 96.0;
   private static final double MAP_HIGHWAY_JOIN_DISTANCE = 4.0;
   private static final double MAP_HIGHWAY_LOOKAHEAD = 128.0;
   private static final double MAP_HIGHWAY_GOAL_RADIUS = 3.0;
   private static final int MAP_HIGHWAY_COOLDOWN_TICKS = 100;
   private static final int MAP_HIGHWAY_CHECK_TICKS = 10;
   private static final int MAP_STRAIGHT_CONFIRM_TICKS = 3;
   private static final int MAP_STRAIGHT_CONFIRM_TICKS_AFTER_STUCK = 20;
   private static final int MAP_AWAITING_PLAN_GRACE_TICKS = 60;
   private static final double MAP_OBSTACLE_STOP_DISTANCE = 8.0;
   private static final double MAP_FAT_MARGIN = 0.4;
   private static final double MAP_LAVA_MARGIN = 1.5;
   private static final double MAP_WATER_MARGIN = 0.75;
   private static final double MAP_FLUID_HARD_MARGIN = 0.15;
   private static final int MAP_ESCAPE_RADIUS = 16;
   private static final int MAP_ESCAPE_MAX_NODES = 30_000;
   private static final int MAP_ESCAPE_SOLID_COST = 12;
   private static final int MAP_ESCAPE_FLUID_COST = 3;

   private final VolytraElytraFlyHandler flight;

   final Setting<Boolean> autohighway;
   private final Setting<HighwayDirection> highwayDirection;
   private final Setting<Intelligence> highwayIntelligence;
   private final KeyBindSetting autohighwayKey;
   final Setting<Boolean> autonav;
   private final Setting<Integer> autonavGotoX;
   private final Setting<Integer> autonavGotoZ;
   private final Setting<MapClickButton> autonavButton;
   private final Setting<Boolean> autonavCloseMap;
   private final Setting<Intelligence> autonavIntelligence;
   private final Setting<Boolean> autonavAvoidLava;
   private final Setting<Boolean> autonavAvoidWater;
   private final Setting<Double> autonavArrivalRadius;
   private final Setting<Boolean> autonavPrioritiseHighways;
   private final KeyBindSetting autonavKey;
   private final KeyBindSetting autonavCancelKey;
   private boolean autohighwayKeyWasDown;
   private boolean autonavKeyWasDown;
   private boolean autonavCancelKeyWasDown;

   private ClientLevel trackedWorld;

   // Unstick and relaunch
   private int unstickTicksLeft;
   private Vec3 unstickAnchor;
   private int unstickWindowTicks;
   private boolean highwayRelaunchArmed;
   private int relaunchCooldown;
   private int relaunchPause;
   private int relaunchAttempts;
   private int relaunchGlidingTicks;

   // Autohighway
   private Vec3 highwayAxis;
   private double highwayCruiseY = Double.NaN;
   private Parrot highwayPathMob;
   private ClientLevel highwayPathMobWorld;
   private final ArrayList<Vec3> highwayPathNodes = new ArrayList<>();
   private int highwayPathIndex;
   private Vec3 highwayGoal;
   private int highwayTicksSinceRepath;
   private ExecutorService highwayPathExecutor;
   private HighwayPathRequest highwayPathRequest;
   private final AtomicInteger highwayPathGeneration = new AtomicInteger();
   /** The point this tick's movement is aimed at, set while Autohighway or Autonav drives. */
   private Vec3 steerTarget;
   private double targetY = Double.NaN;
   private Vec3 highwayLastPos;
   private int highwayStuckTicks;
   private int highwayMovingTicks;
   private int highwayEscalation;
   private boolean highwayCentreAnnounced;

   // Autonav
   private volatile boolean autonavActive;
   /** A trip paused from the settings page: where it was going, kept until it is resumed or stopped. */
   private boolean autonavPaused;
   private int pausedX;
   private int pausedZ;
   private final List<AutonavWaypoint> waypoints = new ArrayList<>();
   private double mapDestX;
   private double mapDestZ;
   private double mapCruiseY;
   private double mapGoalX;
   private double mapGoalZ;
   private double mapGoalRadius;
   private int mapHighwayPhase;
   private double mapHighwayUx;
   private double mapHighwayUz;
   private double mapHighwayEntryX;
   private double mapHighwayEntryZ;
   private int mapHighwayCooldown;
   private boolean mapDetouring;
   private int mapClearStreak;
   private final ArrayList<Vec3> mapRouteNodes = new ArrayList<>();
   private int mapRouteIndex;
   private int mapTicksSinceRepath;
   private boolean mapNoRoute;
   private boolean mapRouteEndsAtFrontier;
   private int mapAwaitingPlanTicks;
   private ExecutorService mapRouteExecutor;
   private MapRouteRequest mapRouteRequest;
   private final AtomicInteger mapRouteGeneration = new AtomicInteger();
   private Vec3 mapLastPos;
   private int mapStuckTicks;
   private int mapMovingTicks;
   private int mapEscalation;
   private boolean mapArrivedAnnounced;
   private final XaeroMapClicks mapClicks = new XaeroMapClicks();
   private boolean mapInLava;
   private boolean mapInWater;

   VolytraNavigator(VolytraElytraFlyHandler flight) {
      this.flight = flight;

      // Each mode has its own card. Display names are short because the card already says which mode they belong to;
      // config names are unchanged, so saved settings carry over.

      // Autonav: switch on, then pick a destination (typed or clicked on the map), then how to get there.
      Setting<Boolean> autonavCard = flight.addGroup("Autonav");
      autonavCard.groupIcon = "compass";
      autonavCard.groupSection = VolytraElytraFlyHandler.SECTION_NAV;
      this.autonav = flight.addBool("Enable Autonav", "volytraAutonav", false, flight.modeOnly());
      this.autonavKey = flight.module.add(new KeyBindSetting("Autonav Toggle Key", "None", flight.modeOnly()));
      this.autonavKey.setConfigName("volytraAutonavKey");
      // The destination is typed on the "Autonav trip" card under this one, which keeps it in these two settings so it
      // is saved with the config; they are not listed on the card themselves.
      this.autonavGotoX = flight.addInt("Destination X", "volytraAutonavX", 0, -30_000_000, 30_000_000, value -> false);
      this.autonavGotoZ = flight.addInt("Destination Z", "volytraAutonavZ", 0, -30_000_000, 30_000_000, value -> false);
      // Coordinates are typed, not slid: a slider over the whole world can't land on a block.
      this.autonavGotoX.disableNumericBounds();
      this.autonavGotoZ.disableNumericBounds();
      // Start, Pause and Stop, the trip's status and the saved destinations are on the "Autonav trip" card.
      this.autonavButton = flight.module.add(new EnumSetting<>("Map Click Button", MapClickButton.MIDDLE, flight.visibleWith(this.autonav)));
      this.autonavButton.setConfigName("volytraMapClickButton");
      this.autonavCloseMap = flight.addBool("Close Map On Click", "volytraCloseMapOnClick", true, flight.visibleWith(this.autonav));
      this.autonavCancelKey = flight.module.add(new KeyBindSetting("Cancel Trip Key", "None", flight.visibleWith(this.autonav)));
      this.autonavCancelKey.setConfigName("volytraAutonavCancelKey");
      this.autonavArrivalRadius = flight.addDouble("Arrival Radius", "volytraArrivalRadius", 30.0, 1.0, 50.0, flight.visibleWith(this.autonav));
      this.autonavIntelligence = flight.module.add(new EnumSetting<>("Route Intelligence", Intelligence.HIGH, flight.visibleWith(this.autonav)));
      this.autonavIntelligence.setConfigName("volytraAutonavIntelligence");
      this.autonavAvoidLava = flight.addBool("Avoid Lava", "volytraAutonavAvoidLava", true, flight.visibleWith(this.autonav));
      this.autonavAvoidWater = flight.addBool("Avoid Water", "volytraAutonavAvoidWater", false, flight.visibleWith(this.autonav));
      this.autonavPrioritiseHighways = flight.addBool("Prioritise Highways", "volytraPrioritiseHighways", false, flight.visibleWith(this.autonav));

      // Autohighway
      Setting<Boolean> highwayCard = flight.addGroup("Autohighway");
      highwayCard.groupIcon = "link";
      highwayCard.groupNote = "An Autonav trip takes over while one is running";
      this.autohighway = flight.addBool("Enable Autohighway", "volytraAutohighway", false, flight.modeOnly());
      this.autohighwayKey = flight.module.add(new KeyBindSetting("Autohighway Toggle Key", "None", flight.modeOnly()));
      this.autohighwayKey.setConfigName("volytraAutohighwayKey");
      this.highwayDirection = flight.module.add(new EnumSetting<>("Highway Direction", HighwayDirection.AWAY_FROM_CENTRE, flight.visibleWith(this.autohighway)));
      this.highwayDirection.setConfigName("volytraHighwayDirection");
      this.highwayIntelligence = flight.module.add(new EnumSetting<>("Highway Intelligence", Intelligence.HIGH, flight.visibleWith(this.autohighway)));
      this.highwayIntelligence.setConfigName("volytraHighwayIntelligence");
   }

   private static Minecraft mc() {
      return ClientAccess.argusclientMinecraft;
   }

   private void say(String message) {
      ChatUtils.printChat(Component.literal(message), ChatUtils.moduleSignature(this.flight.module));
   }

   // ---------------------------------------------------------------- lifecycle

   void onEnable() {
      this.trackedWorld = mc().level;
      this.autonavPaused = false;
      this.resetHighwayState();
      this.resetAutonavState();
      this.resetRelaunch();
      this.unstickTicksLeft = 0;
      this.unstickAnchor = null;
      this.mapClicks.reset();
      this.autohighwayKeyWasDown = true;
      this.autonavKeyWasDown = true;
      this.autonavCancelKeyWasDown = true;
   }

   void onDisable() {
      this.autonavPaused = false;
      this.releaseDrivingKeys();
      this.resetHighwayState();
      this.highwayPathMob = null;
      this.highwayPathMobWorld = null;
      if (this.highwayPathExecutor != null) {
         this.highwayPathExecutor.shutdownNow();
         this.highwayPathExecutor = null;
      }
      this.resetAutonavState();
      if (this.mapRouteExecutor != null) {
         this.mapRouteExecutor.shutdownNow();
         this.mapRouteExecutor = null;
      }
      this.resetRelaunch();
      this.unstickTicksLeft = 0;
      this.unstickAnchor = null;
   }

   /** Coordinates and highway lines belong to the world (and dimension) they were set in. */
   void trackWorld(ClientLevel world) {
      if (world == this.trackedWorld) return;
      if (this.trackedWorld != null) {
         if (this.autonavActive) this.cancelAutonav(false);
         this.autonavPaused = false;
         this.resetHighwayState();
         this.resetRelaunch();
         this.unstickTicksLeft = 0;
         this.unstickAnchor = null;
      }
      this.trackedWorld = world;
   }

   private void resetRelaunch() {
      this.relaunchCooldown = 0;
      this.relaunchPause = 0;
      this.relaunchAttempts = 0;
      this.relaunchGlidingTicks = 0;
      this.highwayRelaunchArmed = false;
   }

   /** Whether Autohighway or Autonav is driving (or will drive, once gliding). */
   boolean driving() {
      return this.autohighway.getValue() || this.autonavDriving();
   }

   private boolean autonavDriving() {
      return this.autonavActive && this.autonav.getValue();
   }

   boolean unsticking() {
      return this.unstickTicksLeft > 0;
   }

   // ---------------------------------------------------------------- per tick (after the player tick)

   void tick(LocalPlayer player) {
      this.pollKeys();
      this.pollMapClick();
      this.relaunchAfterLanding(player);
      this.updateUnstick(player);
   }

   private void pollKeys() {
      Minecraft minecraft = mc();
      boolean playing = minecraft.gui.screen() == null;
      boolean highwayDown = playing && pressed(this.autohighwayKey);
      if (highwayDown && !this.autohighwayKeyWasDown) {
         this.autohighway.setValue(!this.autohighway.getValue());
         this.say("Autohighway " + (this.autohighway.getValue() ? "on" : "off"));
      }
      this.autohighwayKeyWasDown = highwayDown;

      boolean autonavDown = playing && pressed(this.autonavKey);
      if (autonavDown && !this.autonavKeyWasDown) {
         this.autonav.setValue(!this.autonav.getValue());
         this.say("Autonav " + (this.autonav.getValue() ? "on" : "off"));
      }
      this.autonavKeyWasDown = autonavDown;

      boolean cancelDown = playing && pressed(this.autonavCancelKey);
      if (cancelDown && !this.autonavCancelKeyWasDown) {
         if (this.autonavActive) this.cancelAutonav(true);
         else if (this.autonavPaused) this.stopTrip();
      }
      this.autonavCancelKeyWasDown = cancelDown;
   }

   /** A picked key held physically; an unpicked key ("None") never fires. */
   private static boolean pressed(KeyBindSetting key) {
      Keybind value = key.getValue();
      return value != null && !value.isUnbound() && KeyUtils.isPhysicallyDown(value);
   }

   // ---------------------------------------------------------------- flight hooks (inside the glide movement)

   /**
    * Drives Autonav or Autohighway for this tick, if either is on. Returns false when neither is, so the caller
    * falls back to Autopilot.
    */
   boolean drive(LocalPlayer player) {
      if (!this.autohighway.getValue()) this.resetHighwayState();
      this.steerTarget = null;
      this.targetY = Double.NaN;
      if (this.autonavDriving()) {
         this.driveAutonav(player);
         return true;
      }
      if (this.autohighway.getValue()) {
         this.driveAutohighway(player);
         return true;
      }
      return false;
   }

   /** Clears this tick's steering when avoidance has the keys, so nothing is repointed behind its back. */
   void clearSteering() {
      this.steerTarget = null;
      this.targetY = Double.NaN;
   }

   /** The yaw towards this tick's steer target, or NaN when there is none (or it is straight up or down). */
   float steerYaw(LocalPlayer player) {
      if (this.steerTarget == null) return Float.NaN;
      double dx = this.steerTarget.x - player.getX();
      double dz = this.steerTarget.z - player.getZ();
      if (Math.hypot(dx, dz) < 1.0E-3) return Float.NaN;
      return (float) Math.toDegrees(Math.atan2(-dx, dz));
   }

   /** Repoints this tick's horizontal speed at the steer target, never overshooting it. */
   void applySteering(LocalPlayer player) {
      if (this.steerTarget == null) return;
      double speed = Math.hypot(this.flight.velX, this.flight.velZ);
      if (speed < 1.0E-6) return;

      double dx = this.steerTarget.x - player.getX();
      double dz = this.steerTarget.z - player.getZ();
      double horizontalDistance = Math.hypot(dx, dz);
      if (horizontalDistance < 1.0E-3) {
         this.flight.velX = 0.0;
         this.flight.velZ = 0.0;
         return;
      }
      double step = Math.min(speed, horizontalDistance);
      this.flight.velX = dx / horizontalDistance * step;
      this.flight.velZ = dz / horizontalDistance * step;
   }

   /** Keeps the vertical step from overshooting the height being held. */
   void limitVerticalStep(LocalPlayer player) {
      if (Double.isNaN(this.targetY) || !this.driving()) return;
      double error = this.targetY - player.getY();
      if (this.flight.velY > 0.0) this.flight.velY = Math.min(this.flight.velY, Math.max(0.0, error));
      else if (this.flight.velY < 0.0) this.flight.velY = Math.max(this.flight.velY, Math.min(0.0, error));
   }

   /** Keeps the horizontal step in proportion to the vertical one, so climbs and dives follow the route's slope. */
   void applySlopeCoupling(LocalPlayer player) {
      if (this.steerTarget == null) return;
      double dy = this.steerTarget.y - player.getY();
      if (Math.abs(dy) <= HIGHWAY_HEIGHT_BAND || Math.abs(this.flight.velY) < 1.0E-6) return;
      double horizontalSpeed = Math.hypot(this.flight.velX, this.flight.velZ);
      if (horizontalSpeed < 1.0E-6) return;
      double horizontalDistance = Math.hypot(this.steerTarget.x - player.getX(), this.steerTarget.z - player.getZ());
      double allowed = horizontalDistance * Math.min(1.0, Math.abs(this.flight.velY) / Math.abs(dy));
      if (allowed < horizontalSpeed) {
         double scale = allowed / horizontalSpeed;
         this.flight.velX *= scale;
         this.flight.velZ *= scale;
      }
   }

   /** Last line of defence: never lets this tick's move bring the hitbox into a block (or an avoided fluid). */
   void applyClearanceGuard(LocalPlayer player) {
      if (!this.autonavDriving() && (!this.autohighway.getValue() || this.highwayAxis == null)) return;
      ClientLevel world = mc().level;
      if (world == null) return;
      if (Math.abs(this.flight.velX) + Math.abs(this.flight.velY) + Math.abs(this.flight.velZ) < 1.0E-6) return;

      double x = player.getX();
      double y = player.getY();
      double z = player.getZ();
      AABB start = this.hitbox(player, x, y, z, HIGHWAY_BLOCK_CLEARANCE);
      AABB hardStart = this.hitbox(player, x, y, z, HIGHWAY_HARD_MARGIN);
      boolean autonavOn = this.autonavDriving();
      this.mapInLava = autonavOn && this.touchesAvoidedFluid(world, player, x, y, z, true);
      this.mapInWater = autonavOn && this.touchesAvoidedFluid(world, player, x, y, z, false);
      double horizontalBefore = Math.hypot(this.flight.velX, this.flight.velZ);

      double allowedY = this.clampAxis(world, player, x, y, z, 1, this.flight.velY, start, hardStart);
      y += allowedY;
      // Y, then the longer horizontal axis, then the other: the order the server resolves a move in.
      double allowedX;
      double allowedZ;
      if (Math.abs(this.flight.velX) < Math.abs(this.flight.velZ)) {
         allowedZ = this.clampAxis(world, player, x, y, z, 2, this.flight.velZ, start, hardStart);
         z += allowedZ;
         allowedX = this.clampAxis(world, player, x, y, z, 0, this.flight.velX, start, hardStart);
      } else {
         allowedX = this.clampAxis(world, player, x, y, z, 0, this.flight.velX, start, hardStart);
         x += allowedX;
         allowedZ = this.clampAxis(world, player, x, y, z, 2, this.flight.velZ, start, hardStart);
      }
      this.flight.velX = allowedX;
      this.flight.velY = allowedY;
      this.flight.velZ = allowedZ;

      double horizontalAfter = Math.hypot(allowedX, allowedZ);
      if (horizontalAfter < horizontalBefore - 1.0E-3) this.flight.onGuardSpeedLoss(horizontalAfter);
   }

   // ---------------------------------------------------------------- relaunch and unstick

   /**
    * If Autonav is on its way somewhere, or Autohighway was flying, and the player ends up on the ground (or out of
    * the glide) before arriving, takes off again so the trip carries on. Autohighway only does this after it has
    * actually been flying, so having it on while walking around never launches you.
    */
   private void relaunchAfterLanding(LocalPlayer player) {
      if (this.relaunchCooldown > 0) this.relaunchCooldown--;
      if (this.relaunchPause > 0) this.relaunchPause--;
      if (!this.autohighway.getValue()) this.highwayRelaunchArmed = false;

      if (player.isFallFlying()) {
         if (++this.relaunchGlidingTicks >= RELAUNCH_STABLE_TICKS) this.relaunchAttempts = 0;
         if (this.autohighway.getValue()) this.highwayRelaunchArmed = true;
         return;
      }
      this.relaunchGlidingTicks = 0;

      Minecraft minecraft = mc();
      boolean autonavMode = this.autonavDriving();
      boolean highwayMode = this.autohighway.getValue() && this.highwayRelaunchArmed;
      if ((!autonavMode && !highwayMode) || minecraft.level == null || minecraft.gui.screen() != null || minecraft.isPaused()) {
         this.relaunchAttempts = 0;
         return;
      }
      if (!player.isAlive() || player.isPassenger() || player.getAbilities().flying || player.isInWater() || player.isInLava()
         || !player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)) return;
      if (this.flight.isMovementKeyPhysicallyPressed() || KeyUtils.isPhysicallyDown(minecraft.options.keyShift)) {
         // Walking or sneaking on the ground by choice: Autohighway stands down until the next flight
         if (!autonavMode && player.onGround()) this.highwayRelaunchArmed = false;
         return;
      }
      if (autonavMode) {
         if (Math.hypot(this.mapDestX - player.getX(), this.mapDestZ - player.getZ()) <= this.autonavArrivalRadius.getValue()) return;
      } else if (this.highwayDirection.getValue() == HighwayDirection.TOWARDS_CENTRE
         && Math.hypot(player.getX(), player.getZ()) < HIGHWAY_CENTRE_RADIUS) {
         return;
      }
      if (this.relaunchPause > 0) return;

      if (this.relaunchAttempts >= RELAUNCH_MAX_ATTEMPTS) {
         this.relaunchAttempts = 0;
         this.relaunchPause = RELAUNCH_PAUSE_TICKS;
         this.say("Can't take off here; trying again in a moment.");
         return;
      }

      if (player.onGround()) {
         if (this.relaunchCooldown > 0) return;
         this.releaseDrivingKeys();
         this.flight.resetRamps();
         player.setJumping(false);
         player.setSprinting(true);
         player.jumpFromGround();
         player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
         this.relaunchAttempts++;
         this.relaunchCooldown = RELAUNCH_RETRY_TICKS;
      } else if (player.tryToStartFallFlying()) {
         player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
         this.relaunchAttempts++;
      }
   }

   /**
    * Watches for circling while Autonav or Autohighway flies: not getting further than UNSTICK_RADIUS from where the
    * player was UNSTICK_WINDOW_TICKS ago. Then both minimum speeds drop to 1 for a while and the ramp restarts, so the
    * way round whatever is in the way can be flown slowly enough for the server to accept it.
    */
   private void updateUnstick(LocalPlayer player) {
      if (this.unstickTicksLeft > 0) {
         this.unstickTicksLeft--;
         this.unstickAnchor = null;
         return;
      }
      boolean autonavFlying = this.autonavDriving()
         && Math.hypot(this.mapDestX - player.getX(), this.mapDestZ - player.getZ()) > this.autonavArrivalRadius.getValue();
      if (!player.isFallFlying() || !(autonavFlying || this.autohighway.getValue())) {
         this.unstickAnchor = null;
         return;
      }
      Vec3 pos = player.position();
      if (this.unstickAnchor == null || pos.distanceTo(this.unstickAnchor) > UNSTICK_RADIUS) {
         this.unstickAnchor = pos;
         this.unstickWindowTicks = 0;
         return;
      }
      if (++this.unstickWindowTicks < UNSTICK_WINDOW_TICKS) return;

      this.unstickAnchor = pos;
      this.unstickWindowTicks = 0;
      this.unstickTicksLeft = UNSTICK_SLOW_TICKS;
      this.flight.resetRamps();
   }

   // ---------------------------------------------------------------- Autohighway

   private void driveAutohighway(LocalPlayer player) {
      ClientLevel world = mc().level;
      if (world == null) {
         this.releaseDrivingKeys();
         return;
      }
      if (this.highwayAxis == null) this.armHighwayAxis(player);
      Vec3 pos = player.position();

      if (this.highwayDirection.getValue() == HighwayDirection.TOWARDS_CENTRE && Math.hypot(pos.x, pos.z) < HIGHWAY_CENTRE_RADIUS) {
         this.highwayStuckTicks = 0;
         this.highwayMovingTicks = 0;
         this.highwayEscalation = 0;
         this.highwayLastPos = pos;
         if (!this.highwayCentreAnnounced) {
            this.highwayCentreAnnounced = true;
            this.say("Autohighway reached the centre.");
         }
         Vec3 centre = new Vec3(0.0, Double.isNaN(this.highwayCruiseY) ? pos.y : this.highwayCruiseY, 0.0);
         this.steerTarget = centre;
         this.holdDrivingKeys();
         this.applyVerticalHold(player, centre.y);
         return;
      }
      this.highwayCentreAnnounced = false;

      // Barely moved at all for the stuck timeout while trying to: plan the next route to a goal off to the side or
      // above first (further out each time); the count only relaxes after a stretch of real progress.
      if (this.highwayLastPos != null) {
         if (pos.distanceTo(this.highwayLastPos) < HIGHWAY_STUCK_MOVE_EPSILON) {
            this.highwayStuckTicks++;
            this.highwayMovingTicks = 0;
         } else {
            this.highwayStuckTicks = 0;
            if (++this.highwayMovingTicks >= 60) this.highwayEscalation = 0;
         }
      }
      this.highwayLastPos = pos;
      if (this.highwayStuckTicks >= Math.round(this.highwayIntelligence.getValue().stuckTimeout * 20.0)) {
         this.highwayStuckTicks = 0;
         this.highwayEscalation = Math.min(this.highwayEscalation + 1, 6);
         this.highwayPathNodes.clear();
         this.highwayPathGeneration.incrementAndGet();
         this.highwayPathRequest = null;
      }

      this.applyHighwayPathResult(world, pos);
      this.highwayTicksSinceRepath++;
      boolean routeLeft = this.highwayPathNodes.size() >= 2 && this.highwayPathIndex < this.highwayPathNodes.size() - 1;
      int interval = this.highwayIntelligence.getValue().repathInterval;
      boolean routeHealthy = routeLeft && this.highwayPathNodes.size() - 1 - this.highwayPathIndex >= HIGHWAY_HEALTHY_ROUTE_NODES
         && this.highwayEscalation == 0;
      boolean due = this.highwayTicksSinceRepath >= (routeHealthy ? interval * HIGHWAY_HEALTHY_REPATH_FACTOR : interval);
      if (!routeLeft || due) this.computeHighwayPath(world, pos);

      Vec3 target = null;
      if (this.highwayPathNodes.size() >= 2) {
         this.highwayPathIndex = advanceIndex(this.highwayPathNodes, this.highwayPathIndex, pos);
         target = this.pickWaypoint(player, world, pos, this.highwayPathNodes, this.highwayPathIndex, false);
      }
      if (target == null) target = this.highwayGoal != null ? this.highwayGoal : this.goalOnHighway(pos, 0.0, 0.0);

      this.steerTarget = target;
      this.holdDrivingKeys();
      this.applyVerticalHold(player, target.y);
   }

   private static Vec3 nearestHighwayDirection(double x, double z) {
      double distToXAxis = Math.abs(z);
      double distToZAxis = Math.abs(x);
      double distToDiag1 = Math.abs(z - x) / Math.sqrt(2.0);
      double distToDiag2 = Math.abs(z + x) / Math.sqrt(2.0);
      double nearest = Math.min(Math.min(distToXAxis, distToZAxis), Math.min(distToDiag1, distToDiag2));
      if (nearest == distToXAxis) return new Vec3(1.0, 0.0, 0.0);
      if (nearest == distToZAxis) return new Vec3(0.0, 0.0, 1.0);
      if (nearest == distToDiag1) return new Vec3(1.0, 0.0, 1.0);
      return new Vec3(1.0, 0.0, -1.0);
   }

   /** Locks onto whichever highway line through the origin the player is perpendicularly closest to. */
   private void armHighwayAxis(LocalPlayer player) {
      double x = player.getX();
      double z = player.getZ();
      Vec3 dir = nearestHighwayDirection(x, z);
      double dot = x * dir.x + z * dir.z;
      boolean away = this.highwayDirection.getValue() == HighwayDirection.AWAY_FROM_CENTRE;
      double sign = (dot >= 0.0) == away ? 1.0 : -1.0;
      this.highwayAxis = new Vec3(dir.x * sign, 0.0, dir.z * sign).normalize();
      this.highwayCruiseY = player.getY();
   }

   private void resetHighwayState() {
      this.highwayAxis = null;
      this.highwayCruiseY = Double.NaN;
      this.highwayGoal = null;
      this.highwayPathGeneration.incrementAndGet();
      this.highwayPathRequest = null;
      this.highwayPathNodes.clear();
      this.highwayPathIndex = 0;
      this.highwayTicksSinceRepath = 0;
      this.highwayStuckTicks = 0;
      this.highwayMovingTicks = 0;
      this.highwayEscalation = 0;
      this.highwayLastPos = null;
      this.highwayRelaunchArmed = false;
      this.highwayCentreAnnounced = false;
   }

   @SuppressWarnings("unchecked")
   private static EntityType<Parrot> parrotEntityType() {
      for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
         if (BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath().equals("parrot")) return (EntityType<Parrot>) type;
      }
      return null;
   }

   /** The throwaway parrot whose flying navigation does the pathfinding. It is never added to the world. */
   private Parrot highwayPathMob(ClientLevel world) {
      if (this.highwayPathMob == null || this.highwayPathMobWorld != world) {
         try {
            EntityType<Parrot> parrotType = parrotEntityType();
            this.highwayPathMob = parrotType == null ? null : new Parrot(parrotType, world);
            this.highwayPathMobWorld = this.highwayPathMob == null ? null : world;
         } catch (RuntimeException e) {
            this.highwayPathMob = null;
            this.highwayPathMobWorld = null;
         }
      }
      return this.highwayPathMob;
   }

   /** A goal point for the navigator: inside its follow range, towards the highway line. */
   private Vec3 goalOnHighway(Vec3 pos, double lateralOffset, double heightOffset) {
      Vec3 axis = this.highwayAxis;
      Vec3 lateral = new Vec3(-axis.z, 0.0, axis.x);
      double lat = pos.x * lateral.x + pos.z * lateral.z;
      double along = pos.x * axis.x + pos.z * axis.z;
      double range = this.highwayIntelligence.getValue().pathRange - 2;
      double ahead = Math.abs(lat) < 2.0 ? range : Math.min(range, Math.abs(lat) + 8.0);

      double reach = along + ahead;
      if (this.highwayDirection.getValue() == HighwayDirection.TOWARDS_CENTRE) reach = Math.min(reach, 0.0);
      double gx = axis.x * reach + lateral.x * lateralOffset;
      double gz = axis.z * reach + lateral.z * lateralOffset;
      double dx = gx - pos.x;
      double dz = gz - pos.z;
      double dist = Math.hypot(dx, dz);
      if (dist > range) {
         gx = pos.x + dx / dist * range;
         gz = pos.z + dz / dist * range;
      }

      double cruiseY = Double.isNaN(this.highwayCruiseY) ? pos.y : this.highwayCruiseY;
      double gy = cruiseY + heightOffset;
      ClientLevel world = mc().level;
      if (world != null) gy = Math.max(world.getMinY() + 2, Math.min(this.flight.flightCeiling(), gy));
      return new Vec3(gx, gy, gz);
   }

   private static final class HighwayPathRequest {
      final int generation;
      final Parrot mob;
      final Vec3 start;
      final int range;
      final List<Vec3> goals;
      final int firstGoal;
      volatile boolean done;
      int goalIndex = -1;
      ArrayList<Vec3> nodes;

      HighwayPathRequest(int generation, Parrot mob, Vec3 start, int range, List<Vec3> goals, int firstGoal) {
         this.generation = generation;
         this.mob = mob;
         this.start = start;
         this.range = range;
         this.goals = goals;
         this.firstGoal = firstGoal;
      }
   }

   private void computeHighwayPath(ClientLevel world, Vec3 pos) {
      this.highwayTicksSinceRepath = 0;
      HighwayPathRequest running = this.highwayPathRequest;
      if (running != null && !running.done && running.generation == this.highwayPathGeneration.get()) return;

      Parrot mob = this.highwayPathMob(world);
      if (mob == null) return;

      ArrayList<Vec3> goals = new ArrayList<>(6);
      double step = 6.0 * Math.max(1, this.highwayEscalation);
      double firstSide = this.highwayEscalation % 2 == 1 ? 1.0 : -1.0;
      if (this.highwayEscalation == 0) goals.add(this.goalOnHighway(pos, 0.0, 0.0));
      goals.add(this.goalOnHighway(pos, firstSide * step, 0.0));
      goals.add(this.goalOnHighway(pos, -firstSide * step, 0.0));
      goals.add(this.goalOnHighway(pos, 0.0, step));
      if (this.highwayEscalation > 0) goals.add(this.goalOnHighway(pos, 0.0, 0.0));

      this.submitHighwayPath(new HighwayPathRequest(this.highwayPathGeneration.get(), mob, pos,
         this.highwayIntelligence.getValue().pathRange, goals, 0));
   }

   private void submitHighwayPath(HighwayPathRequest request) {
      if (this.highwayPathExecutor == null) this.highwayPathExecutor = newWorker("Volytra-Pathfinder");
      this.highwayPathRequest = request;
      try {
         this.highwayPathExecutor.execute(() -> this.runHighwayPathSearch(request));
      } catch (RejectedExecutionException e) {
         this.highwayPathRequest = null;
      }
   }

   private static ExecutorService newWorker(String name) {
      return Executors.newSingleThreadExecutor(r -> {
         Thread thread = new Thread(r, name);
         thread.setDaemon(true);
         thread.setPriority(Thread.NORM_PRIORITY - 1);
         return thread;
      });
   }

   /** Background thread: the mob navigator's A*, for each goal in turn until one gives a real route. */
   private void runHighwayPathSearch(HighwayPathRequest request) {
      try {
         Parrot mob = request.mob;
         AttributeInstance followRange = mob.getAttribute(Attributes.FOLLOW_RANGE);
         if (followRange != null && followRange.getBaseValue() != request.range) followRange.setBaseValue(request.range);
         mob.setPos(request.start.x, request.start.y, request.start.z);
         PathNavigation navigation = mob.getNavigation();

         for (int i = request.firstGoal; i < request.goals.size(); i++) {
            if (request.generation != this.highwayPathGeneration.get()) return;
            Vec3 goal = request.goals.get(i);
            Path path;
            try {
               path = navigation.createPath(BlockPos.containing(goal.x, goal.y, goal.z), 1);
            } catch (Throwable t) {
               continue; // the world can change under a background read
            }
            if (path == null || path.getNodeCount() < 2) continue;
            BlockPos end = path.getNodePos(path.getNodeCount() - 1);
            if (new Vec3(end.getX() + 0.5, end.getY() + HIGHWAY_NODE_HEIGHT, end.getZ() + 0.5).distanceTo(request.start) < 2.0) continue;

            int nodeCount = Math.min(path.getNodeCount(), HIGHWAY_MAX_ROUTE_NODES);
            ArrayList<Vec3> nodes = new ArrayList<>(nodeCount);
            for (int n = 0; n < nodeCount; n++) {
               BlockPos node = path.getNodePos(n);
               nodes.add(new Vec3(node.getX() + 0.5, node.getY() + HIGHWAY_NODE_HEIGHT, node.getZ() + 0.5));
            }
            request.goalIndex = i;
            request.nodes = nodes;
            return;
         }
      } catch (Throwable ignored) {
         // No route this time; the caller heads straight at the goal.
      } finally {
         request.done = true;
      }
   }

   /** Game thread: takes a finished search, checks it against the real hitbox and swaps it in. */
   private void applyHighwayPathResult(ClientLevel world, Vec3 pos) {
      HighwayPathRequest request = this.highwayPathRequest;
      if (request == null || !request.done) return;
      this.highwayPathRequest = null;
      if (request.generation != this.highwayPathGeneration.get() || request.nodes == null) return;

      ArrayList<Vec3> nodes = request.nodes;
      // The navigator thinks any block that isn't a full cube can be flown through: bend the route round those.
      LocalPlayer player = mc().player;
      if (player != null && (!this.repairHighwayPath(world, player, nodes) || nodes.getLast().distanceTo(pos) < 2.0)) {
         if (request.goalIndex + 1 < request.goals.size()) {
            this.submitHighwayPath(new HighwayPathRequest(request.generation, request.mob, pos, request.range, request.goals, request.goalIndex + 1));
         }
         return;
      }
      this.highwayPathNodes.clear();
      this.highwayPathNodes.addAll(nodes);
      this.highwayPathIndex = 0;
      this.highwayGoal = request.goals.get(request.goalIndex);
   }

   private boolean repairHighwayPath(ClientLevel world, LocalPlayer player, ArrayList<Vec3> nodes) {
      double loose = HIGHWAY_HARD_MARGIN;
      double strict = HIGHWAY_BLOCK_CLEARANCE - 1.0E-4;
      int n = nodes.size();
      boolean[] free = new boolean[n];
      boolean[] bad = new boolean[n];
      boolean anyBad = false;
      free[0] = true;
      for (int i = 1; i < n; i++) free[i] = this.pointFree(world, player, nodes.get(i), loose);
      for (int i = 1; i < n; i++) {
         bad[i] = !free[i] || (free[i - 1] && this.lineBlocked(world, player, nodes.get(i - 1), nodes.get(i), loose));
         anyBad |= bad[i];
      }
      if (!anyBad) return true;

      ArrayList<Vec3> out = new ArrayList<>(n + 8);
      int i = 0;
      while (i < n) {
         if (!bad[i]) {
            out.add(nodes.get(i));
            i++;
            continue;
         }
         int j = i;
         while (j < n && bad[j]) j++;
         if (j == n) break;
         List<BlockPos> detour = this.detour(world, player, nodeCell(out.getLast()), nodeCell(nodes.get(j)), strict);
         if (detour == null) detour = this.detour(world, player, nodeCell(out.getLast()), nodeCell(nodes.get(j)), loose);
         if (detour == null) return false;
         for (BlockPos cell : detour) out.add(new Vec3(cell.getX() + 0.5, cell.getY() + HIGHWAY_NODE_HEIGHT, cell.getZ() + 0.5));
         i = j;
      }
      if (out.size() < 2) return false;
      nodes.clear();
      nodes.addAll(out);
      return true;
   }

   private static BlockPos nodeCell(Vec3 node) {
      return new BlockPos((int) Math.floor(node.x), (int) Math.round(node.y - HIGHWAY_NODE_HEIGHT), (int) Math.floor(node.z));
   }

   private boolean pointFree(ClientLevel world, LocalPlayer player, Vec3 p, double margin) {
      return highestBlockingY(world, this.hitbox(player, p.x, p.y, p.z, margin), null) == Integer.MIN_VALUE;
   }

   private boolean lineBlocked(ClientLevel world, LocalPlayer player, Vec3 from, Vec3 to, double margin) {
      Vec3 delta = to.subtract(from);
      int steps = Math.max(1, (int) Math.ceil(delta.length() / 0.5));
      for (int k = 1; k <= steps; k++) {
         if (!this.pointFree(world, player, from.add(delta.scale((double) k / steps)), margin)) return true;
      }
      return false;
   }

   /** Breadth-first search through free cells round a blocked stretch of route. */
   private List<BlockPos> detour(ClientLevel world, LocalPlayer player, BlockPos from, BlockPos to, double margin) {
      int minX = Math.min(from.getX(), to.getX()) - HIGHWAY_DETOUR_RADIUS;
      int maxX = Math.max(from.getX(), to.getX()) + HIGHWAY_DETOUR_RADIUS;
      int minY = Math.max(world.getMinY(), Math.min(from.getY(), to.getY()) - HIGHWAY_DETOUR_RADIUS);
      int maxY = Math.min(this.flight.flightCeilingBlock(), Math.max(from.getY(), to.getY()) + HIGHWAY_DETOUR_RADIUS);
      int minZ = Math.min(from.getZ(), to.getZ()) - HIGHWAY_DETOUR_RADIUS;
      int maxZ = Math.max(from.getZ(), to.getZ()) + HIGHWAY_DETOUR_RADIUS;

      HashMap<BlockPos, BlockPos> cameFrom = new HashMap<>();
      ArrayDeque<BlockPos> queue = new ArrayDeque<>();
      cameFrom.put(from, from);
      queue.add(from);
      while (!queue.isEmpty() && cameFrom.size() < HIGHWAY_DETOUR_MAX_CELLS) {
         BlockPos cur = queue.poll();
         Vec3 curPoint = new Vec3(cur.getX() + 0.5, cur.getY() + HIGHWAY_NODE_HEIGHT, cur.getZ() + 0.5);
         for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
               for (int dz = -1; dz <= 1; dz++) {
                  if (dx == 0 && dy == 0 && dz == 0) continue;
                  BlockPos next = cur.offset(dx, dy, dz);
                  if (next.getX() < minX || next.getX() > maxX || next.getY() < minY || next.getY() > maxY
                     || next.getZ() < minZ || next.getZ() > maxZ || cameFrom.containsKey(next)) continue;
                  Vec3 nextPoint = new Vec3(next.getX() + 0.5, next.getY() + HIGHWAY_NODE_HEIGHT, next.getZ() + 0.5);
                  if (!this.pointFree(world, player, nextPoint, margin)) continue;
                  if (this.lineBlocked(world, player, curPoint, nextPoint, margin)) continue;
                  cameFrom.put(next, cur);
                  if (next.equals(to)) {
                     LinkedList<BlockPos> cells = new LinkedList<>();
                     for (BlockPos c = cameFrom.get(next); !c.equals(from); c = cameFrom.get(c)) cells.addFirst(c);
                     return cells;
                  }
                  queue.add(next);
               }
            }
         }
      }
      return null;
   }

   /** Moves the route index past every segment the player has already flown. */
   private static int advanceIndex(List<Vec3> nodes, int index, Vec3 pos) {
      int last = nodes.size() - 1;
      while (index < last) {
         Vec3 cur = nodes.get(index);
         Vec3 seg = nodes.get(index + 1).subtract(cur);
         double len2 = seg.lengthSqr();
         if (len2 < 1.0E-6 || pos.subtract(cur).dot(seg) / len2 >= 1.0) index++;
         else break;
      }
      return index;
   }

   /** The furthest route point, a short way ahead, that can be flown to directly. */
   private Vec3 pickWaypoint(LocalPlayer player, ClientLevel world, Vec3 pos, List<Vec3> nodes, int index, boolean mapHazards) {
      int first = index + 1;
      if (first >= nodes.size()) return null;
      int last = Math.min(nodes.size() - 1, index + HIGHWAY_MAX_LOOKAHEAD_NODES);
      AABB startBox = this.hitbox(player, pos.x, pos.y, pos.z, HIGHWAY_BLOCK_CLEARANCE);
      for (int k = last; k > first; k--) {
         Vec3 candidate = nodes.get(k);
         if (this.routeClear(world, player, pos, candidate, startBox, mapHazards)) return candidate;
      }
      return nodes.get(first);
   }

   /** Whether flying to the point is clear the way it will actually be flown (climb or dive first, then across). */
   private boolean routeClear(ClientLevel world, LocalPlayer player, Vec3 from, Vec3 to, AABB startBox, boolean mapHazards) {
      if (Math.abs(to.y - from.y) > HIGHWAY_HEIGHT_BAND) return this.sweepClear(world, player, from, to, startBox, mapHazards);
      Vec3 above = new Vec3(from.x, to.y, from.z);
      return this.sweepClear(world, player, from, above, startBox, mapHazards) && this.sweepClear(world, player, above, to, startBox, mapHazards);
   }

   private boolean sweepClear(ClientLevel world, LocalPlayer player, Vec3 from, Vec3 to, AABB startBox, boolean mapHazards) {
      AABB trueStart = this.hitbox(player, from.x, from.y, from.z, HIGHWAY_TRUE_MARGIN);
      Vec3 delta = to.subtract(from);
      int steps = Math.max(1, (int) Math.ceil(delta.length() / 0.5));
      for (int i = 1; i <= steps; i++) {
         Vec3 point = from.add(delta.scale((double) i / steps));
         if (highestBlockingY(world, this.hitbox(player, point.x, point.y, point.z, HIGHWAY_BLOCK_CLEARANCE), startBox) != Integer.MIN_VALUE) return false;
         if (highestBlockingY(world, this.hitbox(player, point.x, point.y, point.z, HIGHWAY_TRUE_MARGIN), trueStart) != Integer.MIN_VALUE) return false;
         if (mapHazards && this.fluidNear(world, player, point, from)) return false;
      }
      return true;
   }

   /** Whether the hitbox at p is within the safety margin of a fluid Autonav is set to avoid. */
   private boolean fluidNear(ClientLevel world, LocalPlayer player, Vec3 p, Vec3 start) {
      boolean lava = this.autonavAvoidLava.getValue();
      boolean water = this.autonavAvoidWater.getValue();
      if (!lava && !water) return false;
      if (lava && touchesFluid(world, this.hitbox(player, p.x, p.y, p.z, MAP_LAVA_MARGIN),
         start == null ? null : this.hitbox(player, start.x, start.y, start.z, MAP_LAVA_MARGIN), true)) return true;
      return water && touchesFluid(world, this.hitbox(player, p.x, p.y, p.z, MAP_WATER_MARGIN),
         start == null ? null : this.hitbox(player, start.x, start.y, start.z, MAP_WATER_MARGIN), false);
   }

   private boolean touchesAvoidedFluid(ClientLevel world, LocalPlayer player, double x, double y, double z, boolean lava) {
      if (!(lava ? this.autonavAvoidLava.getValue() : this.autonavAvoidWater.getValue())) return false;
      return touchesFluid(world, this.hitbox(player, x, y, z, MAP_FLUID_HARD_MARGIN), null, lava);
   }

   private static boolean touchesFluid(ClientLevel world, AABB box, AABB ignoreBox, boolean lava) {
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      int maxX = (int) Math.floor(box.maxX);
      int maxY = (int) Math.floor(box.maxY);
      int maxZ = (int) Math.floor(box.maxZ);
      for (int bx = (int) Math.floor(box.minX); bx <= maxX; bx++) {
         for (int by = (int) Math.floor(box.minY); by <= maxY; by++) {
            for (int bz = (int) Math.floor(box.minZ); bz <= maxZ; bz++) {
               pos.set(bx, by, bz);
               var fluid = world.getFluidState(pos);
               if (fluid.isEmpty() || !fluid.is(lava ? FluidTags.LAVA : FluidTags.WATER)) continue;
               if (ignoreBox != null && ignoreBox.intersects(bx, by, bz, bx + 1, by + 1, bz + 1)) continue;
               return true;
            }
         }
      }
      return false;
   }

   /** Holds forward (and nothing else), so the flight moves; its direction is set by {@link #applySteering}. */
   private void holdDrivingKeys() {
      Minecraft minecraft = mc();
      this.flight.forceKey(minecraft.options.keyUp, true);
      this.flight.forceKey(minecraft.options.keyDown, false);
      this.flight.forceKey(minecraft.options.keyLeft, false);
      this.flight.forceKey(minecraft.options.keyRight, false);
      this.flight.lastForwardPressed = minecraft.options.keyUp.isDown();
   }

   private void applyVerticalHold(LocalPlayer player, double y) {
      this.targetY = y;
      double verticalError = y - player.getY();
      Minecraft minecraft = mc();
      minecraft.options.keyJump.setDown(verticalError > HIGHWAY_HEIGHT_BAND);
      minecraft.options.keyShift.setDown(verticalError < -HIGHWAY_HEIGHT_BAND);
      if (Math.abs(verticalError) <= HIGHWAY_HEIGHT_BAND) this.flight.velY = verticalError;
   }

   /** Releases the keys these modes drive (WASD, jump and sneak), skipping any held physically. */
   void releaseDrivingKeys() {
      Minecraft minecraft = mc();
      this.flight.forceKey(minecraft.options.keyUp, false);
      this.flight.forceKey(minecraft.options.keyDown, false);
      this.flight.forceKey(minecraft.options.keyLeft, false);
      this.flight.forceKey(minecraft.options.keyRight, false);
      this.flight.forceKey(minecraft.options.keyJump, false);
      this.flight.forceKey(minecraft.options.keyShift, false);
   }

   /** The Y of the highest block whose collision shape overlaps box (and not ignoreBox), or MIN_VALUE. */
   private static int highestBlockingY(ClientLevel world, AABB box, AABB ignoreBox) {
      int top = Integer.MIN_VALUE;
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      int maxX = (int) Math.floor(box.maxX);
      int maxY = (int) Math.floor(box.maxY);
      int maxZ = (int) Math.floor(box.maxZ);
      for (int bx = (int) Math.floor(box.minX); bx <= maxX; bx++) {
         // One lower: fences and walls stand taller than their cell
         for (int by = (int) Math.floor(box.minY) - 1; by <= maxY; by++) {
            for (int bz = (int) Math.floor(box.minZ); bz <= maxZ; bz++) {
               pos.set(bx, by, bz);
               var shape = world.getBlockState(pos).getCollisionShape(world, pos);
               if (shape.isEmpty()) continue;
               AABB blockBox = shape.bounds().move(pos);
               if (!box.intersects(blockBox)) continue;
               if (ignoreBox != null && ignoreBox.intersects(blockBox)) continue;
               top = Math.max(top, by);
            }
         }
      }
      return top;
   }

   private double clampAxis(ClientLevel world, LocalPlayer player, double x, double y, double z, int axis, double delta, AABB start, AABB hardStart) {
      if (Math.abs(delta) < 1.0E-9) return delta;
      int steps = Math.max(1, (int) Math.ceil(Math.abs(delta) / 0.25));
      for (int i = 1; i <= steps; i++) {
         if (!this.axisMoveSafe(world, player, x, y, z, axis, delta * i / steps, start, hardStart)) {
            double lo = (double) (i - 1) / steps;
            double hi = (double) i / steps;
            for (int k = 0; k < 6; k++) {
               double mid = (lo + hi) / 2.0;
               if (this.axisMoveSafe(world, player, x, y, z, axis, delta * mid, start, hardStart)) lo = mid;
               else hi = mid;
            }
            return delta * lo;
         }
      }
      return delta;
   }

   private boolean axisMoveSafe(ClientLevel world, LocalPlayer player, double x, double y, double z, int axis, double move, AABB start, AABB hardStart) {
      double nx = x + (axis == 0 ? move : 0.0);
      double ny = y + (axis == 1 ? move : 0.0);
      double nz = z + (axis == 2 ? move : 0.0);
      if (highestBlockingY(world, this.hitbox(player, nx, ny, nz, HIGHWAY_BLOCK_CLEARANCE), start) != Integer.MIN_VALUE) return false;

      if (this.autonavDriving() && (this.autonavAvoidLava.getValue() || this.autonavAvoidWater.getValue())) {
         AABB fluidBox = this.hitbox(player, nx, ny, nz, MAP_FLUID_HARD_MARGIN);
         if (this.autonavAvoidLava.getValue() && !this.mapInLava && touchesFluid(world, fluidBox, start, true)) return false;
         if (this.autonavAvoidWater.getValue() && !this.mapInWater && touchesFluid(world, fluidBox, start, false)) return false;
      }
      if (highestBlockingY(world, this.hitbox(player, nx, ny, nz, HIGHWAY_HARD_MARGIN), hardStart) != Integer.MIN_VALUE) return false;
      AABB trueStart = this.hitbox(player, x, y, z, HIGHWAY_TRUE_MARGIN);
      return highestBlockingY(world, this.hitbox(player, nx, ny, nz, HIGHWAY_TRUE_MARGIN), trueStart) == Integer.MIN_VALUE;
   }

   /** The player's hitbox with its feet at (x, y, z), grown on every side by margin. */
   private AABB hitbox(LocalPlayer player, double x, double y, double z, double margin) {
      AABB base = player.getBoundingBox();
      double halfWidth = (base.maxX - base.minX) / 2.0;
      double halfDepth = (base.maxZ - base.minZ) / 2.0;
      double height = base.maxY - base.minY;
      return new AABB(x - halfWidth, y, z - halfDepth, x + halfWidth, y + height, z + halfDepth).inflate(margin);
   }

   // ---------------------------------------------------------------- Autonav

   private void pollMapClick() {
      if (!this.autonav.getValue()) {
         this.autonavPaused = false;
         this.mapClicks.reset();
         if (this.autonavActive) this.cancelAutonav(true);
         return;
      }
      XaeroMapClicks.Click click = this.mapClicks.poll(mc(), this.autonavButton.getValue().button);
      if (click != null) this.startAutonav(click.x(), click.z(), true);
      String problem = this.mapClicks.takeError();
      if (problem != null) this.say(problem);
   }

   // ---------------------------------------------------------------- the settings page's trip card

   boolean autonavOn() {
      return this.autonav.getValue();
   }

   int typedX() {
      return this.autonavGotoX.getValue();
   }

   int typedZ() {
      return this.autonavGotoZ.getValue();
   }

   void setTyped(int x, int z) {
      this.autonavGotoX.setValue(x);
      this.autonavGotoZ.setValue(z);
   }

   boolean tripActive() {
      return this.autonavActive;
   }

   boolean tripPaused() {
      return this.autonavPaused && !this.autonavActive;
   }

   /** Resumes a paused trip, or starts one to the typed coordinates; throws with the reason when it can't. */
   void startTrip() {
      if (this.tripPaused()) this.flyTo(this.pausedX, this.pausedZ);
      else this.flyTo(this.autonavGotoX.getValue(), this.autonavGotoZ.getValue());
   }

   /** Starts a trip to (x, z), switching Autonav on if it is off; throws with the reason when it can't. */
   void flyTo(int x, int z) {
      if (mc().player == null || mc().level == null) throw new IllegalStateException("Join a world first.");
      if (!this.flight.module.isToggled() || !this.flight.active()) throw new IllegalStateException("Turn on ElytraFly in Volytra mode first.");
      if (!this.autonav.getValue()) this.autonav.setValue(true);
      this.autonavPaused = false;
      this.startAutonav(x, z, false);
   }

   /** Stops steering but keeps the destination, so Start carries on to it. */
   void pauseTrip() {
      if (!this.autonavActive) throw new IllegalStateException("There is no trip to pause.");
      this.pausedX = (int) Math.floor(this.mapDestX);
      this.pausedZ = (int) Math.floor(this.mapDestZ);
      this.cancelAutonav(false);
      this.autonavPaused = true;
      this.say("Autonav paused. Press Start to carry on to " + this.pausedX + ", " + this.pausedZ + ".");
   }

   /** Ends the trip, paused or not. */
   void stopTrip() {
      if (!this.autonavActive && !this.autonavPaused) throw new IllegalStateException("There is no trip to stop.");
      boolean wasPaused = this.tripPaused();
      this.autonavPaused = false;
      if (wasPaused) this.say("Autonav stopped.");
      else this.cancelAutonav(true);
   }

   /** One line on the trip, for the settings page. */
   String tripStatus() {
      LocalPlayer player = mc().player;
      if (this.tripPaused()) {
         String to = "to " + this.pausedX + ", " + this.pausedZ;
         if (player == null) return "Paused " + to;
         long left = Math.round(Math.hypot(this.pausedX + 0.5 - player.getX(), this.pausedZ + 0.5 - player.getZ()));
         return "Paused " + to + " · " + left + " blocks left";
      }
      if (!this.autonavActive) return this.autohighway.getValue() ? "No trip · Autohighway is flying" : "No trip";
      String to = "to " + (int) Math.floor(this.mapDestX) + ", " + (int) Math.floor(this.mapDestZ);
      if (player == null) return "Flying " + to;
      if (this.mapArrivedAnnounced) return "Arrived · hovering until you move";
      long left = Math.round(Math.hypot(this.mapDestX - player.getX(), this.mapDestZ - player.getZ()));
      if (!player.isFallFlying()) return "Start gliding to set off " + to + " · " + left + " blocks";
      return "Flying " + to + " · " + left + " blocks left" + (this.mapNoRoute ? " · looking for a way through" : "");
   }

   List<AutonavWaypoint> waypoints() {
      return List.copyOf(this.waypoints);
   }

   /** Saves (x, z) under name, replacing a saved destination of the same name. */
   void saveWaypoint(String name, int x, int z) {
      String trimmed = name == null ? "" : name.trim();
      if (trimmed.isEmpty()) trimmed = x + ", " + z;
      String key = trimmed;
      this.waypoints.removeIf(waypoint -> waypoint.name().equalsIgnoreCase(key));
      this.waypoints.add(new AutonavWaypoint(trimmed, x, z));
   }

   void removeWaypoint(String name) {
      if (!this.waypoints.removeIf(waypoint -> waypoint.name().equals(name))) throw new IllegalStateException("No saved destination called " + name + ".");
   }

   JsonArray waypointsJson() {
      JsonArray array = new JsonArray();
      for (AutonavWaypoint waypoint : this.waypoints) array.add(waypoint.toJson());
      return array;
   }

   void loadWaypoints(JsonElement json) {
      this.waypoints.clear();
      if (!(json instanceof JsonArray array)) return;
      for (JsonElement element : array) {
         if (!(element instanceof JsonObject object) || !object.has("x") || !object.has("z")) continue;
         try {
            int x = object.get("x").getAsInt(), z = object.get("z").getAsInt();
            String name = object.has("name") ? object.get("name").getAsString() : x + ", " + z;
            this.waypoints.add(new AutonavWaypoint(name, x, z));
         } catch (RuntimeException ignored) {
            // A hand-edited entry that isn't a destination is skipped.
         }
      }
   }

   private void startAutonav(int blockX, int blockZ, boolean fromMap) {
      LocalPlayer player = mc().player;
      ClientLevel world = mc().level;
      if (player == null || world == null) return;

      this.autonavPaused = false;
      this.resetMapRouteState();
      this.flight.resetRamps();
      this.mapDestX = blockX + 0.5;
      this.mapDestZ = blockZ + 0.5;
      this.mapGoalX = this.mapDestX;
      this.mapGoalZ = this.mapDestZ;
      this.mapCruiseY = Math.max(world.getMinY() + 2, Math.min(this.flight.flightCeiling(), player.getY()));
      this.mapDetouring = false;
      this.mapArrivedAnnounced = false;
      this.autonavActive = true;
      this.say("Autonav: flying to " + blockX + ", " + blockZ + ".");
      if (fromMap && this.autonavCloseMap.getValue() && mc().gui.screen() != null) mc().gui.setScreen(null);
   }

   private void cancelAutonav(boolean announce) {
      boolean wasActive = this.autonavActive;
      this.resetAutonavState();
      this.releaseDrivingKeys();
      if (wasActive && announce) this.say("Autonav cancelled.");
   }

   private void resetAutonavState() {
      this.autonavActive = false;
      this.mapDestX = 0.0;
      this.mapDestZ = 0.0;
      this.mapCruiseY = 0.0;
      this.resetMapRouteState();
   }

   private void resetMapRouteState() {
      this.mapRouteGeneration.incrementAndGet();
      this.mapRouteRequest = null;
      this.mapRouteNodes.clear();
      this.mapRouteIndex = 0;
      this.mapTicksSinceRepath = 0;
      this.mapNoRoute = false;
      this.mapRouteEndsAtFrontier = false;
      this.mapAwaitingPlanTicks = 0;
      this.mapHighwayPhase = MAP_HIGHWAY_NONE;
      this.mapHighwayCooldown = 0;
      this.mapDetouring = false;
      this.mapClearStreak = 0;
      this.mapLastPos = null;
      this.mapStuckTicks = 0;
      this.mapMovingTicks = 0;
      this.mapEscalation = 0;
   }

   /**
    * Flies the straight line to the destination while it is clear. When something is in the way, asks the planner
    * for a route through air the player fits in (ending at the destination, or at the edge of the loaded world on
    * the quickest way on towards it) and follows that until the straight line is clear again.
    */
   private void driveAutonav(LocalPlayer player) {
      ClientLevel world = mc().level;
      if (world == null) {
         this.releaseDrivingKeys();
         return;
      }
      Vec3 pos = player.position();
      double distToDest = Math.hypot(this.mapDestX - pos.x, this.mapDestZ - pos.z);
      Vec3 destPoint = new Vec3(this.mapDestX, this.mapCruiseY, this.mapDestZ);

      // Arrived: hover at the destination until the user takes over
      if (distToDest <= this.autonavArrivalRadius.getValue()) {
         if (this.flight.isMovementKeyPhysicallyPressed()) {
            this.cancelAutonav(true);
            return;
         }
         if (!this.mapArrivedAnnounced) {
            this.mapArrivedAnnounced = true;
            this.say("Autonav: arrived. Hovering until you take over.");
         }
         this.flight.resetRamps();
         this.steerTarget = destPoint;
         this.holdDrivingKeys();
         this.applyVerticalHold(player, this.mapCruiseY);
         return;
      }

      this.updateHighwayGoal(world, pos);
      double gdx = this.mapGoalX - pos.x;
      double gdz = this.mapGoalZ - pos.z;
      double distToGoal = Math.max(Math.hypot(gdx, gdz), 1.0E-6);
      Vec3 goalPoint = new Vec3(this.mapGoalX, this.mapCruiseY, this.mapGoalZ);

      // Not stuck while the next chunk along simply hasn't loaded, or while a route is still being worked out
      boolean waitingOnChunks = !world.hasChunk(
         ((int) Math.floor(pos.x + gdx / distToGoal * 6.0)) >> 4,
         ((int) Math.floor(pos.z + gdz / distToGoal * 6.0)) >> 4);
      boolean awaitingPlan = this.mapAwaitingPlanTicks > 0 && this.mapAwaitingPlanTicks <= MAP_AWAITING_PLAN_GRACE_TICKS;
      if (this.mapLastPos != null && !waitingOnChunks && !awaitingPlan) {
         if (pos.distanceTo(this.mapLastPos) < HIGHWAY_STUCK_MOVE_EPSILON) {
            this.mapStuckTicks++;
            this.mapMovingTicks = 0;
         } else {
            this.mapStuckTicks = 0;
            if (++this.mapMovingTicks >= 60) this.mapEscalation = 0;
         }
      } else if (waitingOnChunks || awaitingPlan) {
         this.mapStuckTicks = 0;
      }
      this.mapLastPos = pos;
      if (this.mapStuckTicks >= Math.round(this.autonavIntelligence.getValue().stuckTimeout * 20.0)) {
         this.mapStuckTicks = 0;
         this.mapEscalation = Math.min(this.mapEscalation + 1, 4);
         // Not getting anywhere means the straight line isn't really clear: plan round it with more room
         this.mapDetouring = true;
         this.mapClearStreak = 0;
         this.clearMapRoute();
      }

      double lineRange = Math.min(distToGoal, Math.max(96.0, Math.min(400.0, this.flight.topHorizontalSpeed() * 32.0)));
      double dirX = gdx / distToGoal;
      double dirZ = gdz / distToGoal;
      double blockedAt = this.straightBlockedAt(world, player, pos, dirX, dirZ, lineRange);
      // Inside blocks, or in a fluid it avoids: the way out has to be planned
      boolean inHazard = highestBlockingY(world, this.hitbox(player, pos.x, pos.y, pos.z, HIGHWAY_TRUE_MARGIN), null) != Integer.MIN_VALUE
         || this.touchesAvoidedFluid(world, player, pos.x, pos.y, pos.z, true)
         || this.touchesAvoidedFluid(world, player, pos.x, pos.y, pos.z, false);
      boolean straightClear = blockedAt < 0.0 && !inHazard;
      this.mapClearStreak = straightClear ? this.mapClearStreak + 1 : 0;

      if (!this.mapDetouring && !straightClear) {
         this.mapDetouring = true;
         this.clearMapRoute();
         this.mapTicksSinceRepath = Integer.MAX_VALUE / 2;
      } else if (this.mapDetouring && this.mapStuckTicks == 0
         && this.mapClearStreak >= (this.mapEscalation > 0 ? MAP_STRAIGHT_CONFIRM_TICKS_AFTER_STUCK : MAP_STRAIGHT_CONFIRM_TICKS)) {
         // Back on the open line: carry on level from wherever the detour got to
         this.mapDetouring = false;
         this.mapCruiseY = Math.max(world.getMinY() + 2, Math.min(this.flight.flightCeiling(), pos.y));
         this.clearMapRoute();
         goalPoint = new Vec3(this.mapGoalX, this.mapCruiseY, this.mapGoalZ);
      }

      Vec3 target = null;
      boolean awaitingRoute = false;
      if (this.mapDetouring) {
         this.applyMapRouteResult();
         this.mapTicksSinceRepath++;
         boolean routeLeft = this.mapRouteNodes.size() >= 2 && this.mapRouteIndex < this.mapRouteNodes.size() - 1;
         int interval = this.autonavIntelligence.getValue().repathInterval;
         boolean routeHealthy = routeLeft && this.mapRouteNodes.size() - 1 - this.mapRouteIndex >= 8 && this.mapEscalation == 0;
         boolean endLoaded = routeLeft && this.mapRouteEndsAtFrontier && this.routeEndHasLoaded(world, player, pos);
         boolean due = endLoaded || this.mapTicksSinceRepath >= (routeHealthy ? interval * 3 : interval);
         if ((!routeLeft && !this.mapNoRoute) || due) this.submitMapPlan(world, player, pos);

         if (this.mapRouteNodes.size() >= 2) {
            this.mapRouteIndex = advanceIndex(this.mapRouteNodes, this.mapRouteIndex, pos);
            target = this.pickWaypoint(player, world, pos, this.mapRouteNodes, this.mapRouteIndex, true);
         }
         if (target == null && this.mapNoRoute) target = pos; // sealed in: hold still rather than push at a wall
         if (target == null) {
            // No route yet: never fly into whatever blocked the line, stop short of it until the route arrives
            awaitingRoute = true;
            target = this.approachTarget(pos, dirX, dirZ, blockedAt);
         }
      }
      this.mapAwaitingPlanTicks = awaitingRoute ? this.mapAwaitingPlanTicks + 1 : 0;
      if (target == null) target = goalPoint;

      this.steerTarget = target;
      this.holdDrivingKeys();
      this.applyVerticalHold(player, target.y);
   }

   /**
    * Sets the point Autonav flies to this tick. Normally the destination; with Prioritise Highways it first joins a
    * highway, then follows it while it points within 45 degrees of the destination.
    */
   private void updateHighwayGoal(ClientLevel world, Vec3 pos) {
      this.mapGoalX = this.mapDestX;
      this.mapGoalZ = this.mapDestZ;
      this.mapGoalRadius = this.autonavArrivalRadius.getValue();

      if (!this.autonavPrioritiseHighways.getValue()) {
         if (this.mapHighwayPhase != MAP_HIGHWAY_NONE) this.endHighway(0);
         return;
      }
      if (this.mapHighwayCooldown > 0) this.mapHighwayCooldown--;
      if (this.mapHighwayPhase == MAP_HIGHWAY_NONE) {
         if (this.mapHighwayCooldown > 0) return;
         if (!this.chooseHighway(world, pos)) {
            this.mapHighwayCooldown = MAP_HIGHWAY_CHECK_TICKS;
            return;
         }
      }

      double ux = this.mapHighwayUx;
      double uz = this.mapHighwayUz;
      double along = pos.x * ux + pos.z * uz;
      double footX = ux * along;
      double footZ = uz * along;
      double lateral = Math.hypot(pos.x - footX, pos.z - footZ);
      if (this.highwayUsable(ux, uz, footX, footZ) < 0.0) {
         this.endHighway(MAP_HIGHWAY_COOLDOWN_TICKS);
         return;
      }
      if (this.mapHighwayPhase == MAP_HIGHWAY_JOINING && lateral <= MAP_HIGHWAY_JOIN_DISTANCE) {
         this.mapHighwayPhase = MAP_HIGHWAY_FOLLOWING;
         this.resetHighwayRoute();
      }
      this.mapGoalRadius = MAP_HIGHWAY_GOAL_RADIUS;
      if (this.mapHighwayPhase == MAP_HIGHWAY_FOLLOWING) {
         this.mapGoalX = footX + ux * MAP_HIGHWAY_LOOKAHEAD;
         this.mapGoalZ = footZ + uz * MAP_HIGHWAY_LOOKAHEAD;
      } else {
         this.mapGoalX = this.mapHighwayEntryX;
         this.mapGoalZ = this.mapHighwayEntryZ;
      }
   }

   /** How much further a highway stays within 45 degrees of the destination: along less across. */
   private double highwayUsable(double ux, double uz, double fx, double fz) {
      double rx = this.mapDestX - fx;
      double rz = this.mapDestZ - fz;
      return rx * ux + rz * uz - Math.abs(rx * uz - rz * ux);
   }

   private boolean chooseHighway(ClientLevel world, Vec3 pos) {
      double px = pos.x;
      double pz = pos.z;
      double distToDest = Math.hypot(this.mapDestX - px, this.mapDestZ - pz);

      int best = -1;
      double bestLateral = Double.MAX_VALUE;
      double bestFootX = 0.0;
      double bestFootZ = 0.0;
      for (int i = 0; i < MAP_HIGHWAY_DIRECTIONS.length; i++) {
         double ux = MAP_HIGHWAY_DIRECTIONS[i][0];
         double uz = MAP_HIGHWAY_DIRECTIONS[i][1];
         double along = px * ux + pz * uz;
         double footX = ux * along;
         double footZ = uz * along;
         double lateral = Math.hypot(px - footX, pz - footZ);
         if (lateral >= distToDest || lateral >= bestLateral) continue;
         if (!world.hasChunk(((int) Math.floor(footX)) >> 4, ((int) Math.floor(footZ)) >> 4)) continue;
         if (this.highwayUsable(ux, uz, footX, footZ) < MAP_HIGHWAY_MIN_FOLLOW) continue;
         best = i;
         bestLateral = lateral;
         bestFootX = footX;
         bestFootZ = footZ;
      }

      double ux;
      double uz;
      double footX;
      double footZ;
      if (best >= 0) {
         ux = MAP_HIGHWAY_DIRECTIONS[best][0];
         uz = MAP_HIGHWAY_DIRECTIONS[best][1];
         footX = bestFootX;
         footZ = bestFootZ;
      } else {
         // Of the four lines, the one the destination is nearest to
         int line = MAP_HIGHWAY_LINES[0];
         double nearest = Double.MAX_VALUE;
         for (int i : MAP_HIGHWAY_LINES) {
            double distance = Math.abs(this.mapDestX * MAP_HIGHWAY_DIRECTIONS[i][1] - this.mapDestZ * MAP_HIGHWAY_DIRECTIONS[i][0]);
            if (distance < nearest) {
               nearest = distance;
               line = i;
            }
         }
         ux = MAP_HIGHWAY_DIRECTIONS[line][0];
         uz = MAP_HIGHWAY_DIRECTIONS[line][1];
         double along = px * ux + pz * uz;
         footX = ux * along;
         footZ = uz * along;
         if ((this.mapDestX - footX) * ux + (this.mapDestZ - footZ) * uz < 0.0) {
            ux = -ux;
            uz = -uz;
         }
         double lateral = Math.hypot(px - footX, pz - footZ);
         if (lateral >= distToDest || this.highwayUsable(ux, uz, footX, footZ) < MAP_HIGHWAY_MIN_FOLLOW) return false;
      }

      this.mapHighwayUx = ux;
      this.mapHighwayUz = uz;
      this.mapHighwayEntryX = footX;
      this.mapHighwayEntryZ = footZ;
      this.mapHighwayPhase = MAP_HIGHWAY_JOINING;
      this.resetHighwayRoute();
      return true;
   }

   private void endHighway(int cooldownTicks) {
      this.mapHighwayPhase = MAP_HIGHWAY_NONE;
      this.mapHighwayCooldown = cooldownTicks;
      this.resetHighwayRoute();
   }

   private void resetHighwayRoute() {
      this.clearMapRoute();
      this.mapDetouring = false;
      this.mapClearStreak = 0;
   }

   private void clearMapRoute() {
      this.mapRouteGeneration.incrementAndGet();
      this.mapRouteRequest = null;
      this.mapRouteNodes.clear();
      this.mapRouteIndex = 0;
      this.mapNoRoute = false;
      this.mapRouteEndsAtFrontier = false;
   }

   /** How far along the straight line the first obstacle is (as far as chunks are loaded), or -1 when clear. */
   private double straightBlockedAt(ClientLevel world, LocalPlayer player, Vec3 from, double dirX, double dirZ, double range) {
      double reach = 0.0;
      for (double d = 4.0; ; d += 4.0) {
         double along = Math.min(d, range);
         if (!world.hasChunk(((int) Math.floor(from.x + dirX * along)) >> 4, ((int) Math.floor(from.z + dirZ * along)) >> 4)) break;
         reach = along;
         if (along >= range) break;
      }
      if (reach < 1.0) return -1.0;

      AABB startBox = this.hitbox(player, from.x, from.y, from.z, HIGHWAY_BLOCK_CLEARANCE);
      AABB trueStart = this.hitbox(player, from.x, from.y, from.z, HIGHWAY_TRUE_MARGIN);
      int steps = Math.max(1, (int) Math.ceil(reach / 0.5));
      for (int i = 1; i <= steps; i++) {
         double t = (double) i / steps;
         Vec3 point = new Vec3(from.x + dirX * reach * t, from.y, from.z + dirZ * reach * t);
         if (highestBlockingY(world, this.hitbox(player, point.x, point.y, point.z, HIGHWAY_BLOCK_CLEARANCE), startBox) != Integer.MIN_VALUE
            || highestBlockingY(world, this.hitbox(player, point.x, point.y, point.z, HIGHWAY_TRUE_MARGIN), trueStart) != Integer.MIN_VALUE
            || this.fluidNear(world, player, point, from)) {
            return reach * t;
         }
      }
      return -1.0;
   }

   /** While there's no route yet: along the straight line, stopping short of the first thing in its way. */
   private Vec3 approachTarget(Vec3 pos, double dirX, double dirZ, double blockedAt) {
      if (blockedAt < 0.0) return pos;
      double go = blockedAt - MAP_OBSTACLE_STOP_DISTANCE;
      if (go < 1.0) return pos;
      return new Vec3(pos.x + dirX * go, this.mapCruiseY, pos.z + dirZ * go);
   }

   private boolean routeEndHasLoaded(ClientLevel world, LocalPlayer player, Vec3 pos) {
      if (this.mapRouteNodes.isEmpty()) return false;
      Vec3 end = this.mapRouteNodes.getLast();
      return !AutonavPlanner.isFrontier(new MapGrid(world, player, pos), (int) Math.floor(end.x), (int) Math.floor(end.z));
   }

   private static final class MapRouteRequest {
      final int generation;
      final ClientLevel world;
      final LocalPlayer player;
      final Vec3 start;
      final double destX;
      final double destZ;
      final double arrivalRadius;
      final int escalation;
      final int maxNodes;
      volatile boolean done;
      AutonavPlanner.Status status;
      ArrayList<Vec3> nodes;

      MapRouteRequest(int generation, ClientLevel world, LocalPlayer player, Vec3 start, double destX, double destZ,
                      double arrivalRadius, int escalation, int maxNodes) {
         this.generation = generation;
         this.world = world;
         this.player = player;
         this.start = start;
         this.destX = destX;
         this.destZ = destZ;
         this.arrivalRadius = arrivalRadius;
         this.escalation = escalation;
         this.maxNodes = maxNodes;
      }
   }

   private void submitMapPlan(ClientLevel world, LocalPlayer player, Vec3 pos) {
      this.mapTicksSinceRepath = 0;
      MapRouteRequest running = this.mapRouteRequest;
      if (running != null && !running.done && running.generation == this.mapRouteGeneration.get()) return;
      if (this.mapRouteExecutor == null) this.mapRouteExecutor = newWorker("Volytra-MapPlanner");

      // Each stuck escalation doubles how much the search may look at
      int budget = this.autonavIntelligence.getValue().mapSearchNodes * (1 << Math.min(this.mapEscalation, 2));
      MapRouteRequest request = new MapRouteRequest(this.mapRouteGeneration.get(), world, player, pos, this.mapGoalX, this.mapGoalZ,
         this.mapGoalRadius, this.mapEscalation, budget);
      this.mapRouteRequest = request;
      try {
         this.mapRouteExecutor.execute(() -> this.runMapPlan(request));
      } catch (RejectedExecutionException e) {
         this.mapRouteRequest = null;
      }
   }

   /** Background thread: plans the route, then straightens it. */
   private void runMapPlan(MapRouteRequest request) {
      try {
         AutonavPlanner.Grid grid = new MapGrid(request.world, request.player, request.start);
         int[] start = {(int) Math.floor(request.start.x), (int) Math.floor(request.start.y), (int) Math.floor(request.start.z)};

         // After getting stuck, ask for extra room round every cell first
         AutonavPlanner.Result best = null;
         for (int clearance = request.escalation > 0 ? 1 : 0; clearance >= 0; clearance--) {
            if (request.generation != this.mapRouteGeneration.get()) return;
            AutonavPlanner.Result result = AutonavPlanner.planTwoPass(grid, start, request.destX, request.destZ,
               request.arrivalRadius, start[1], clearance, request.maxNodes);
            boolean found = result.status == AutonavPlanner.Status.REACHED_DESTINATION
               || result.status == AutonavPlanner.Status.REACHED_FRONTIER;
            if (best == null || found || best.status == AutonavPlanner.Status.SEALED) best = result;
            if (found) break;
         }
         if (best.status == AutonavPlanner.Status.SEALED && grid.escapeCost(start[0], start[1], start[2]) != 0) {
            AutonavPlanner.Result out = AutonavPlanner.escape(grid, start, MAP_ESCAPE_RADIUS, MAP_ESCAPE_MAX_NODES);
            if (out.status != AutonavPlanner.Status.SEALED) best = out;
         }
         request.status = best.status;
         if (best.status != AutonavPlanner.Status.SEALED) {
            List<int[]> cells = best.status == AutonavPlanner.Status.ESCAPING ? best.cells : AutonavPlanner.smooth(grid, best.cells, 64);
            ArrayList<Vec3> nodes = new ArrayList<>(cells.size());
            for (int[] cell : cells) nodes.add(new Vec3(cell[0] + 0.5, cell[1] + HIGHWAY_NODE_HEIGHT, cell[2] + 0.5));
            if (!nodes.isEmpty()) nodes.set(0, request.start);
            request.nodes = nodes;
         }
      } catch (Throwable ignored) {
         // The world can change under a background read; the next cadence tries again.
      } finally {
         request.done = true;
      }
   }

   private void applyMapRouteResult() {
      MapRouteRequest request = this.mapRouteRequest;
      if (request == null || !request.done) return;
      this.mapRouteRequest = null;
      if (request.generation != this.mapRouteGeneration.get() || request.status == null) return;
      if (request.status == AutonavPlanner.Status.SEALED) {
         this.mapRouteNodes.clear();
         this.mapRouteIndex = 0;
         this.mapNoRoute = true;
         return;
      }
      if (request.nodes == null || request.nodes.size() < 2) return;
      this.mapNoRoute = false;
      this.mapRouteNodes.clear();
      this.mapRouteNodes.addAll(request.nodes);
      this.mapRouteIndex = 0;
      this.mapRouteEndsAtFrontier = request.status == AutonavPlanner.Status.REACHED_FRONTIER;
   }

   /** What the planner sees of the world: the player's hitbox against the blocks, and which chunks are loaded. */
   private final class MapGrid implements AutonavPlanner.Grid {
      private final ClientLevel world;
      private final LocalPlayer player;
      private final Vec3 start;

      MapGrid(ClientLevel world, LocalPlayer player, Vec3 start) {
         this.world = world;
         this.player = player;
         this.start = start;
      }

      @Override
      public boolean isChunkUnloaded(int chunkX, int chunkZ) {
         return !this.world.hasChunk(chunkX, chunkZ);
      }

      @Override
      public boolean isPassable(int x, int y, int z, int clearance) {
         if (this.isChunkUnloaded(x >> 4, z >> 4)) return false;
         double margin = clearance > 0 ? MAP_FAT_MARGIN : HIGHWAY_BLOCK_CLEARANCE - 1.0E-4;
         Vec3 point = new Vec3(x + 0.5, y + HIGHWAY_NODE_HEIGHT, z + 0.5);
         return VolytraNavigator.this.pointFree(this.world, this.player, point, margin)
            && !VolytraNavigator.this.fluidNear(this.world, this.player, point, this.start);
      }

      @Override
      public boolean lineClear(int x0, int y0, int z0, int x1, int y1, int z1) {
         Vec3 from = new Vec3(x0 + 0.5, y0 + HIGHWAY_NODE_HEIGHT, z0 + 0.5);
         Vec3 to = new Vec3(x1 + 0.5, y1 + HIGHWAY_NODE_HEIGHT, z1 + 0.5);
         AABB startBox = VolytraNavigator.this.hitbox(this.player, from.x, from.y, from.z, HIGHWAY_BLOCK_CLEARANCE);
         return VolytraNavigator.this.routeClear(this.world, this.player, from, to, startBox, true);
      }

      @Override
      public int escapeCost(int x, int y, int z) {
         if (this.isChunkUnloaded(x >> 4, z >> 4)) return -1;
         Vec3 point = new Vec3(x + 0.5, y + HIGHWAY_NODE_HEIGHT, z + 0.5);
         if (!VolytraNavigator.this.pointFree(this.world, this.player, point, HIGHWAY_BLOCK_CLEARANCE - 1.0E-4)) return MAP_ESCAPE_SOLID_COST;
         if (VolytraNavigator.this.fluidNear(this.world, this.player, point, null)) return MAP_ESCAPE_FLUID_COST;
         return 0;
      }

      @Override
      public int minY() {
         return this.world.getMinY() + 1;
      }

      @Override
      public int maxY() {
         int startY = (int) Math.floor(this.start.y);
         if (VolytraNavigator.this.flight.limitsHeight()) return Math.max(VolytraNavigator.this.flight.flightCeilingBlock(), startY);
         int top = this.world.getMinY() + this.world.getHeight() - 3;
         return startY >= top ? startY + MAP_ABOVE_LIMIT_ROOM : top;
      }
   }
}
