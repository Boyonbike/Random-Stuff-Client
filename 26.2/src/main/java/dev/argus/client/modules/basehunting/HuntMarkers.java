package dev.argus.client.modules.basehunting;

import dev.argus.client.ClientAccess;
import dev.argus.client.ClientContext;
import dev.argus.client.audio.BuiltinSound;
import dev.argus.client.events.WorldRenderEvent;
import dev.argus.client.managers.CommandRegistry;
import dev.argus.client.managers.ConfigurationManager;
import dev.argus.client.modules.Module;
import dev.argus.client.render.LineMeshRenderer;
import dev.argus.client.render.WorldBoxRenderer;
import dev.argus.client.settings.BooleanSetting;
import dev.argus.client.settings.ColorSetting;
import dev.argus.client.settings.EnumSetting;
import dev.argus.client.settings.IdentifierSetting;
import dev.argus.client.settings.NamedEntry;
import dev.argus.client.settings.NumberSetting;
import dev.argus.client.settings.Setting;
import dev.argus.client.utils.ElapsedTimer;
import dev.argus.client.utils.chat.ChatUtils;
import dev.argus.client.utils.collections.NamedIdentifier;
import dev.argus.client.utils.world.Waypoint;
import dev.argus.client.utils.world.WorldUtils;
import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent.SuggestCommand;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The finds of one Base Hunting detector: highlight boxes, tracers, chat alerts, a sound, Argus or Xaero waypoints,
 * an optional CSV log, and optionally remembering finds per server and dimension between sessions. Each detector
 * creates one in its constructor, which adds these settings to the module.
 */
public final class HuntMarkers implements ClientAccess {
   private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
   private final Module module;
   /** The folder remembered finds go in: the module's name, plus the category for multi-category detectors. */
   private final String memoryName;
   private final Setting<Boolean> notifySetting;
   private final Setting<Boolean> censorSetting;
   private final Setting<Boolean> distanceSetting;
   private final Setting<Boolean> soundSetting;
   private final Setting<NamedIdentifier> soundTypeSetting;
   private final Setting<Float> volumeSetting;
   private final Setting<Boolean> waypointsSetting;
   private final Setting<Boolean> xaeroSetting;
   private final Setting<Boolean> logSetting;
   private final Setting<Boolean> rememberSetting;
   private final Setting<Boolean> forgetSetting;
   private final Setting<Integer> rangeSetting;
   private final Setting<Boolean> tracersSetting;
   private final Setting<Boolean> nearestOnlySetting;
   private final Setting<Shape> shapeSetting;
   private final Setting<Color> fillSetting;
   private final Setting<Color> outlineSetting;
   private final Setting<Float> lineWidthSetting;
   private final Map<BlockPos, Mark> marks = new ConcurrentHashMap<>();
   private final ElapsedTimer soundTimer = new ElapsedTimer();
   private final java.util.Set<Object> alerted = ConcurrentHashMap.newKeySet();
   /** The server and dimension the current finds belong to, or null before the first frame in a world. */
   private volatile String worldKey;

   public HuntMarkers(Module module, Color fill, Color outline, boolean tracers) {
      this(module, fill, outline, tracers, true);
   }

   /** As above; {@code notify} sets whether chat alerts start on, which suits detectors that find a few things. */
   public HuntMarkers(Module module, Color fill, Color outline, boolean tracers, boolean notify) {
      this.module = module;
      this.memoryName = module.getName();
      this.notifySetting = module.add(new BooleanSetting("Notify", notify).markVisibilityDependency());
      this.censorSetting = module.add(new BooleanSetting("CensorCoords", false).dependsOn(this.notifySetting));
      this.distanceSetting = module.add(new BooleanSetting("ShowDistance", true).dependsOn(this.notifySetting));
      this.soundSetting = module.add(new BooleanSetting("Sound", false).markVisibilityDependency());
      this.soundTypeSetting = module.add(new IdentifierSetting("Type", BuiltinSound.WARNING.getIdentifier()).dependsOn(this.soundSetting));
      this.volumeSetting = module.add(new NumberSetting<>("Volume", 1.0F, 0.0F, 1.0F).dependsOn(this.soundSetting));
      this.waypointsSetting = module.add(new BooleanSetting("Waypoints", false));
      this.xaeroSetting = module.add(new BooleanSetting("Xaero", false));
      this.logSetting = module.add(new BooleanSetting("Log", false));
      this.rememberSetting = module.add(new BooleanSetting("Remember", false));
      this.forgetSetting = module.add(new BooleanSetting("ForgetFinds", false));
      this.rangeSetting = module.add(new NumberSetting<>("Range", 512, 32, 4096).withUnit("m"));
      this.tracersSetting = module.add(new BooleanSetting("Tracers", tracers));
      this.nearestOnlySetting = module.add(new BooleanSetting("NearestOnly", false));
      this.shapeSetting = module.add(new EnumSetting<>("Shape", Shape.BOTH));
      this.fillSetting = module.add(new ColorSetting("Fill", fill));
      this.outlineSetting = module.add(new ColorSetting("Outline", outline));
      this.lineWidthSetting = module.add(new NumberSetting<>("LineWidth", 1.5F, 0.1F, 5.0F));
      this.nearestOnlySetting.addVisibilityPredicate(value -> this.tracersSetting.getValue());
      this.fillSetting.addVisibilityPredicate(value -> this.shapeSetting.getValue() != Shape.OUTLINE);
      this.outlineSetting.addVisibilityPredicate(value -> this.shapeSetting.getValue() != Shape.FILL || this.tracersSetting.getValue());
      this.forgetSetting.setChangeCallback(() -> {
         if (this.forgetSetting.getValue()) {
            this.forgetSetting.setValue(false);
            this.forget();
         }
      });
      if (ClientContext.waypointAdapter == null || !ClientContext.waypointAdapter.isAvailable()) {
         module.unregister(this.xaeroSetting);
      }
   }

   /**
    * One category of a detector that sorts its finds into several (see {@link HuntersESPModule}). The category gets
    * its own Colour, Shape, Tracers, Notify, Sound and Waypoints in its own card, shown while {@code enabled} is on and
    * saved as {@code <prefix><name>}; everything else comes from {@code shared}. Remembered finds are kept per category.
    */
   public HuntMarkers(Module module, Shared shared, String prefix, Setting<?> group, Setting<Boolean> enabled, Color colour, boolean tracers, boolean notify) {
      this.module = module;
      this.memoryName = module.getName() + "-" + prefix;
      this.outlineSetting = shared.own(prefix, group, enabled, new ColorSetting("Colour", colour));
      this.fillSetting = null;
      this.shapeSetting = shared.own(prefix, group, enabled, new EnumSetting<>("Shape", Shape.BOTH));
      this.tracersSetting = shared.own(prefix, group, enabled, new BooleanSetting("Tracers", tracers));
      this.notifySetting = shared.own(prefix, group, enabled, new BooleanSetting("Notify", notify));
      this.soundSetting = shared.own(prefix, group, enabled, new BooleanSetting("Sound", false));
      this.waypointsSetting = shared.own(prefix, group, enabled, new BooleanSetting("Waypoints", false));
      this.xaeroSetting = new BooleanSetting("Xaero", false);
      if (ClientContext.waypointAdapter != null && ClientContext.waypointAdapter.isAvailable()) {
         shared.own(prefix, group, enabled, this.xaeroSetting);
      }

      this.censorSetting = shared.censorSetting;
      this.distanceSetting = shared.distanceSetting;
      this.soundTypeSetting = shared.soundTypeSetting;
      this.volumeSetting = shared.volumeSetting;
      this.logSetting = shared.logSetting;
      this.rememberSetting = shared.rememberSetting;
      this.forgetSetting = shared.forgetSetting;
      this.rangeSetting = shared.rangeSetting;
      this.nearestOnlySetting = shared.nearestOnlySetting;
      this.lineWidthSetting = shared.lineWidthSetting;
      shared.members.add(this);
   }

   /**
    * The settings every category of one detector shares: coordinates in alerts, the sound itself, logging, memory,
    * range and line width. Build it first so categories can use it, then {@link #register()} it where its card goes.
    */
   public static final class Shared {
      private final Module module;
      private final List<HuntMarkers> members = new ArrayList<>();
      private final Setting<Boolean> censorSetting = new BooleanSetting("CensorCoords", false);
      private final Setting<Boolean> distanceSetting = new BooleanSetting("ShowDistance", true);
      private final Setting<NamedIdentifier> soundTypeSetting = new IdentifierSetting("SoundType", BuiltinSound.WARNING.getIdentifier());
      private final Setting<Float> volumeSetting = new NumberSetting<>("Volume", 1.0F, 0.0F, 1.0F);
      private final Setting<Boolean> logSetting = new BooleanSetting("Log", false);
      private final Setting<Boolean> rememberSetting = new BooleanSetting("Remember", false);
      private final Setting<Boolean> forgetSetting = new BooleanSetting("ForgetFinds", false);
      private final Setting<Integer> rangeSetting = new NumberSetting<>("Range", 512, 32, 4096).withUnit("m");
      private final Setting<Boolean> nearestOnlySetting = new BooleanSetting("NearestOnly", false);
      private final Setting<Float> lineWidthSetting = new NumberSetting<>("LineWidth", 1.5F, 0.1F, 5.0F);

      public Shared(Module module) {
         this.module = module;
         // Saved under the names the single-category settings used, so old configs keep these values.
         this.soundTypeSetting.setConfigName("Type");
         this.forgetSetting.setChangeCallback(() -> {
            if (this.forgetSetting.getValue()) {
               this.forgetSetting.setValue(false);
               for (HuntMarkers member : this.members) {
                  member.forget(false);
               }

               ChatUtils.printChat(Component.literal(module.getName() + ": forgot every find here."), ChatUtils.moduleSignature(module));
            }
         });
      }

      /** Adds the shared card, titled {@code title}, at this point in the module's settings. */
      public void register(String title) {
         Setting<Boolean> group = this.module.add(new BooleanSetting(title, true).markAsGroup());
         for (Setting<?> setting : List.of(
            this.rangeSetting, this.lineWidthSetting, this.nearestOnlySetting, this.censorSetting, this.distanceSetting,
            this.soundTypeSetting, this.volumeSetting, this.logSetting, this.rememberSetting, this.forgetSetting
         )) {
            setting.dependsOn(group);
            this.module.add(setting);
         }
      }

      private <S extends Setting<?>> S own(String prefix, Setting<?> group, Setting<Boolean> enabled, S setting) {
         setting.dependsOn(group, enabled);
         setting.setConfigName(prefix + setting.getName());
         return this.module.add(setting);
      }
   }

   /** See-through fill to go with an outline colour. */
   static Color fillFor(Color outline) {
      return new Color(outline.getRed(), outline.getGreen(), outline.getBlue(), Math.min(60, outline.getAlpha() / 4 + 10));
   }

   /** For modules that colour each find themselves, so the module-wide Fill and Outline would do nothing. */
   public HuntMarkers withoutColours() {
      this.module.unregister(this.fillSetting);
      this.module.unregister(this.outlineSetting);
      return this;
   }

   /** Saves this category's colour under {@code configName}, so a colour from before categories had cards carries over. */
   public HuntMarkers savingColourAs(String configName) {
      this.outlineSetting.setConfigName(configName);
      return this;
   }

   /** For detectors that only track moving things, where remembering finds between sessions means nothing. */
   public HuntMarkers withoutMemory() {
      this.module.unregister(this.rememberSetting);
      this.module.unregister(this.forgetSetting);
      this.rememberSetting.setValue(false);
      return this;
   }

   /**
    * Records a find. Only the first report for a key alerts, so detectors can report the same thing every scan.
    *
    * @return whether this was new
    */
   public boolean report(BlockPos key, AABB box, String what) {
      return this.report(key, box, what, null);
   }

   /** As {@link #report(BlockPos, AABB, String)}, drawn in its own colour (the outline; the fill is a see-through copy). */
   public boolean report(BlockPos key, AABB box, String what, Color color) {
      Mark mark = new Mark(box, what, color == null ? null : fillFor(color), color);
      if (this.marks.putIfAbsent(key.immutable(), mark) != null) {
         return false;
      }

      // Detectors often run on scanner threads; chat, sound and waypoints belong on the game thread.
      BlockPos position = key.immutable();
      ClientAccess.argusclientMinecraft.execute(() -> this.alert(position, what));
      return true;
   }

   /** Adds a box without any alert, for things that should only be highlighted. */
   public void mark(BlockPos key, AABB box, String what) {
      this.marks.put(key.immutable(), new Mark(box, what));
   }

   public boolean contains(BlockPos key) {
      return this.marks.containsKey(key);
   }

   public void remove(BlockPos key) {
      this.marks.remove(key);
   }

   /** Empties the finds held in memory; with Remember on, they are saved first and come back next time. */
   public void clear() {
      String key = this.worldKey;
      if (key != null && this.rememberSetting.getValue()) {
         this.save(key);
      }

      this.marks.clear();
      this.alerted.clear();
      this.worldKey = null;
   }

   /** Throws away every find here, including the remembered ones for this server and dimension. */
   public void forget() {
      this.forget(true);
   }

   private void forget(boolean announce) {
      String key = this.worldKey != null ? this.worldKey : currentWorldKey();
      this.marks.clear();
      this.alerted.clear();
      if (key != null) {
         Path file = this.memoryFile(key);
         ClientAccess.executorService.execute(() -> {
            try {
               Files.deleteIfExists(file);
            } catch (IOException failure) {
               ClientAccess.logger.warn("Could not forget {} finds", this.module.getName(), failure);
            }
         });
      }

      if (announce) {
         ChatUtils.printChat(Component.literal(this.module.getName() + ": forgot every find here."), ChatUtils.moduleSignature(this.module));
      }
   }

   public int size() {
      return this.marks.size();
   }

   public Collection<BlockPos> keys() {
      return this.marks.keySet();
   }

   public Collection<Mark> values() {
      return this.marks.values();
   }

   /** Forgets finds much further away than the render range, so long trips do not pile up boxes forever. */
   public void pruneFarFrom(Vec3 position) {
      if (this.rememberSetting.getValue()) {
         // Remembered finds are the whole point of a long trip; keep them all.
         return;
      }

      double limit = Math.max(this.rangeSetting.getValue() * 2.0, 1024.0);
      double limitSquared = limit * limit;
      this.marks.keySet().removeIf(key -> horizontalDistanceSquared(key, position) > limitSquared);
   }

   public void alert(BlockPos position, String what) {
      if (this.notifySetting.getValue()) {
         MutableComponent message = Component.literal(this.module.getName() + " found " + what + " at ");
         if (this.censorSetting.getValue()) {
            message.append(ChatUtils.watermarkText("### ### ###"));
         } else {
            String coordinates = position.getX() + " " + position.getY() + " " + position.getZ();
            SuggestCommand click = new SuggestCommand(CommandRegistry.getPrefix() + "highlight " + position.getX() + " ~ " + position.getZ());
            message.append(ChatUtils.watermarkText(coordinates).withStyle(style -> style.withClickEvent(click)));
         }

         if (this.distanceSetting.getValue() && !this.isWorldUnavailable()) {
            Vec3 player = ClientAccess.argusclientMinecraft.player.position();
            double dx = position.getX() + 0.5 - player.x;
            double dz = position.getZ() + 0.5 - player.z;
            message.append(Component.literal(" (" + Math.round(Math.sqrt(dx * dx + dz * dz)) + "m " + compass(dx, dz) + ")"));
         }

         ChatUtils.printChat(message, ChatUtils.createSignature(-Math.abs((this.module.getName() + position).hashCode())), ChatUtils.MessagePriority.LOW);
      }

      if (this.soundSetting.getValue() && this.soundTimer.hasElapsed(3.0, TimeUnit.SECONDS)) {
         ClientContext.soundManager.playSound(this.soundTypeSetting.getValue(), this.volumeSetting.getValue());
         this.soundTimer.reset();
      }

      if (this.waypointsSetting.getValue() && ClientContext.waypointRegistry != null && !this.isWorldUnavailable()) {
         String dimension = WorldUtils.getDimension().getDisplayName().toLowerCase();
         String name = this.module.getName() + " " + position.getX() + " " + position.getZ();
         ClientContext.waypointRegistry.registerWaypoint(new Waypoint(name, Vec3.atCenterOf(position), dimension, HuntCompat.serverKey()));
      }

      if (this.xaeroSetting.getValue() && ClientContext.waypointAdapter != null && ClientContext.waypointAdapter.isAvailable()) {
         ClientAccess.argusclientMinecraft.execute(() -> ClientContext.waypointAdapter.addWaypoint(this.module.getName() + " " + what, position));
      }

      if (this.logSetting.getValue()) {
         this.log(position, what);
      }
   }

   /** The eight-point compass direction of an offset, where north is negative Z. */
   static String compass(double dx, double dz) {
      double degrees = Math.toDegrees(Math.atan2(dx, -dz));
      String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
      return names[(int)Math.floorMod(Math.round(degrees / 45.0), 8L)];
   }

   private void log(BlockPos position, String what) {
      String server = HuntCompat.serverKey();
      String dimension = WorldUtils.getDimension().name();
      String line = String.join(
            ",",
            LocalDateTime.now().format(LOG_TIME),
            csv(server),
            dimension,
            String.valueOf(position.getX()),
            String.valueOf(position.getY()),
            String.valueOf(position.getZ()),
            csv(what)
         )
         + System.lineSeparator();
      ClientAccess.executorService.execute(() -> {
         try {
            Path folder = ConfigurationManager.ROOT_DIRECTORY.resolve("basehunting");
            Files.createDirectories(folder);
            Path file = folder.resolve(this.module.getName() + ".csv");
            if (!Files.exists(file)) {
               Files.writeString(file, "time,server,dimension,x,y,z,what" + System.lineSeparator(), StandardCharsets.UTF_8);
            }

            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
         } catch (IOException failure) {
            ClientAccess.logger.warn("Could not log a {} find", this.module.getName(), failure);
         }
      });
   }

   private static String csv(String value) {
      return value.contains(",") || value.contains("\"") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
   }

   private static String currentWorldKey() {
      if (ClientAccess.argusclientMinecraft.player == null || ClientAccess.argusclientMinecraft.level == null) {
         return null;
      }

      return HuntCompat.serverKey() + "/" + WorldUtils.getDimension().getName();
   }

   /**
    * Notices moving to another server or dimension: finds from the old one are saved (with Remember) and put away, and
    * remembered finds for the new one are loaded.
    */
   private void syncWorld() {
      String key = currentWorldKey();
      if (key == null || key.equals(this.worldKey)) {
         return;
      }

      String previous = this.worldKey;
      boolean remember = this.rememberSetting.getValue();
      if (previous != null && remember) {
         this.save(previous);
      }

      if (previous != null) {
         // Finds reported before the first frame in a world already belong to it; only a real move clears them.
         this.marks.clear();
         this.alerted.clear();
      }

      this.worldKey = key;
      if (remember) {
         this.load(key);
      }
   }

   private Path memoryFile(String key) {
      String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
      return ConfigurationManager.ROOT_DIRECTORY.resolve("basehunting").resolve("remembered").resolve(this.memoryName).resolve(safe + ".csv");
   }

   private void save(String key) {
      List<String> lines = new ArrayList<>(this.marks.size());
      for (Map.Entry<BlockPos, Mark> entry : this.marks.entrySet()) {
         lines.add(MarkCodec.encode(entry.getKey(), entry.getValue()));
      }

      Path file = this.memoryFile(key);
      ClientAccess.executorService.execute(() -> {
         try {
            if (lines.isEmpty()) {
               return;
            }

            // Merge with what is on disk, so finds pruned from memory on a long trip are not lost.
            Map<String, String> merged = new java.util.LinkedHashMap<>();
            if (Files.exists(file)) {
               for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                  merged.put(MarkCodec.keyOf(line), line);
               }
            }

            for (String line : lines) {
               merged.put(MarkCodec.keyOf(line), line);
            }

            Files.createDirectories(file.getParent());
            Files.write(file, merged.values(), StandardCharsets.UTF_8);
         } catch (IOException failure) {
            ClientAccess.logger.warn("Could not remember {} finds", this.module.getName(), failure);
         }
      });
   }

   private void load(String key) {
      Path file = this.memoryFile(key);
      ClientAccess.executorService.execute(() -> {
         if (!Files.exists(file)) {
            return;
         }

         try {
            int loaded = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
               MarkCodec.Entry entry = MarkCodec.decode(line);
               if (entry != null && key.equals(this.worldKey)) {
                  this.marks.putIfAbsent(entry.key(), entry.mark());
                  loaded++;
               }
            }

            if (loaded > 0) {
               int count = loaded;
               ClientAccess.argusclientMinecraft.execute(
                  () -> ChatUtils.printChat(
                     Component.literal(this.module.getName() + ": loaded " + count + " remembered finds."), ChatUtils.moduleSignature(this.module)
                  )
               );
            }
         } catch (IOException failure) {
            ClientAccess.logger.warn("Could not load remembered {} finds", this.module.getName(), failure);
         }
      });
   }

   private static double horizontalDistanceSquared(BlockPos position, Vec3 point) {
      double dx = position.getX() + 0.5 - point.x;
      double dz = position.getZ() + 0.5 - point.z;
      return dx * dx + dz * dz;
   }

   public void render(WorldRenderEvent event) {
      this.syncWorld();
      if (this.marks.isEmpty()) {
         return;
      }

      this.renderMarks(event, new ArrayList<>(this.marks.values()));
   }

   /** Draws boxes with this module's colours, range and tracers, for things such as moving entities. */
   public void renderBoxes(WorldRenderEvent event, Collection<AABB> boxes) {
      this.renderBoxes(event, boxes, null);
   }

   /** As above in one colour of your own, or the module's colours when {@code color} is null. */
   public void renderBoxes(WorldRenderEvent event, Collection<AABB> boxes, Color color) {
      List<Mark> marks = new ArrayList<>(boxes.size());
      Color fill = color == null ? null : fillFor(color);
      for (AABB box : boxes) {
         marks.add(new Mark(box, "", fill, color));
      }

      this.renderMarks(event, marks);
   }

   private void renderMarks(WorldRenderEvent event, List<Mark> marks) {
      this.syncWorld();
      if (this.isWorldUnavailable() || marks.isEmpty()) {
         return;
      }

      Vec3 eye = ClientAccess.argusclientMinecraft.player.getEyePosition();
      double range = this.rangeSetting.getValue();
      double rangeSquared = range * range;
      Color outline = this.outlineSetting.getValue();
      // Categories have one colour; their fill is a see-through copy of it.
      Color fill = this.fillSetting == null ? fillFor(outline) : this.fillSetting.getValue();
      Shape shape = this.shapeSetting.getValue();
      float lineWidth = this.lineWidthSetting.getValue();
      boolean tracers = this.tracersSetting.getValue();
      boolean nearestOnly = this.nearestOnlySetting.getValue();
      List<Vec3> tracerTargets = tracers && !nearestOnly ? new ArrayList<>() : null;
      List<Integer> tracerColors = new ArrayList<>();
      Vec3 nearest = null;
      int nearestColor = outline.hashCode();
      double nearestSquared = Double.MAX_VALUE;

      for (Mark mark : marks) {
         AABB box = mark.box();
         Color markFill = mark.fill() != null ? mark.fill() : fill;
         Color markOutline = mark.outline() != null ? mark.outline() : outline;
         Vec3 center = box.getCenter();
         double dx = center.x - eye.x;
         double dz = center.z - eye.z;
         double distanceSquared = dx * dx + dz * dz;
         if (distanceSquared > rangeSquared) {
            continue;
         }

         if (shape != Shape.OUTLINE) {
            WorldBoxRenderer.fillBox(event.getPoseStack(), box, markFill);
         }

         if (shape != Shape.FILL) {
            WorldBoxRenderer.outlineBox(event.getPoseStack(), box, markOutline, lineWidth);
         }

         if (tracerTargets != null) {
            tracerTargets.add(center);
            tracerColors.add(markOutline.hashCode());
         } else if (tracers && distanceSquared < nearestSquared) {
            nearestSquared = distanceSquared;
            nearest = center;
            nearestColor = markOutline.hashCode();
         }
      }

      if (nearest != null) {
         tracerTargets = List.of(nearest);
         tracerColors = List.of(nearestColor);
      }

      if (tracerTargets != null && !tracerTargets.isEmpty()) {
         LineMeshRenderer.begin(event, lineWidth);
         for (int index = 0; index < tracerTargets.size(); index++) {
            LineMeshRenderer.addTracer(tracerTargets.get(index), tracerColors.get(index));
         }

         LineMeshRenderer.draw();
      }
   }

   /** Alerts about something once per id (an entity's UUID, say), without adding a box. */
   public boolean alertOnce(Object id, BlockPos position, String what) {
      if (!this.alerted.add(id)) {
         return false;
      }

      BlockPos immutable = position.immutable();
      ClientAccess.argusclientMinecraft.execute(() -> this.alert(immutable, what));
      return true;
   }

   /** One find; {@code fill} and {@code outline} override the module's colours when set, or are null. */
   public record Mark(AABB box, String what, Color fill, Color outline) {
      public Mark(AABB box, String what) {
         this(box, what, null, null);
      }
   }

   public enum Shape implements NamedEntry {
      BOTH("Both"),
      FILL("Fill"),
      OUTLINE("Outline");

      private final String displayName;

      Shape(String displayName) {
         this.displayName = displayName;
      }

      @Override
      public String getName() {
         return this.displayName;
      }
   }

   /** Builds the markers once the module exists, for base classes whose subclasses pick the colours. */
   @FunctionalInterface
   public interface Factory {
      HuntMarkers create(Module module);
   }
}
