package dev.argus.client.modules.render.targets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/** The target lists ESP and Tracers start with on a new config (or after a reset), and presets added from search. */
public final class DefaultTargets {
   /** A set of groups added in one go from the button above the Targets search, e.g. Meteor's Storage ESP. */
   public record Preset(String name, String description, String json) {
      /** How many groups it adds. */
      public int size() {
         return JsonParser.parseString(this.json).getAsJsonArray().size();
      }
   }

   /** What adding a preset did: groups added, and things left out because they were already in a group. */
   public record Applied(int groups, int things, int skipped) {
   }

   private static final String[] DYES = {
      "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
      "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
   };

   private DefaultTargets() {
   }

   /**
    * ESP: Meteor's Storage ESP defaults, one group per Meteor colour. Chests, barrels and shulker boxes orange, trapped
    * chests red, ender chests purple, and furnaces, hoppers, dispensers and the rest grey.
    */
   public static String esp() {
      return storage("default-");
   }

   /** Meteor's Storage ESP as a preset: the same groups and colours ESP starts with. */
   public static final Preset STORAGE = new Preset("Storage ESP", "Meteor's storage groups, each with its own colour", storage("preset-"));

   /** The presets offered by the Targets search. */
   public static List<Preset> presets() {
      return List.of(STORAGE);
   }

   /**
    * Adds a preset's groups (with their settings) to a list. Things already in a group are left out of the new ones,
    * and a group with nothing left isn't added.
    */
   public static Applied apply(SpecificRules rules, Preset preset) {
      int groups = 0;
      int things = 0;
      int skipped = 0;
      for (JsonElement element : JsonParser.parseString(preset.json()).getAsJsonArray()) {
         SpecificRules.Rule template = SpecificRules.Rule.fromJson(element.getAsJsonObject());
         SpecificRules.Rule added = rules.add(template.members());
         int count = added == null ? 0 : added.members().size();
         skipped += template.members().size() - count;
         if (added != null) {
            added.body = new SpecificRules.TextBody(template.body.read());
            groups++;
            things += count;
         }
      }

      return new Applied(groups, things, skipped);
   }

   /** How many of a preset's things aren't in a group yet (0: adding it would do nothing). */
   public static int missing(SpecificRules rules, Preset preset) {
      int missing = 0;
      for (JsonElement element : JsonParser.parseString(preset.json()).getAsJsonArray()) {
         for (SpecificRules.Member member : SpecificRules.Rule.fromJson(element.getAsJsonObject()).members()) {
            if (rules.owner(member.type(), member.id()) == null) {
               missing++;
            }
         }
      }

      return missing;
   }

   private static String storage(String prefix) {
      List<String> chests = new ArrayList<>(List.of("chest"));
      for (String age : new String[]{"", "exposed_", "weathered_", "oxidized_"}) {
         chests.add(age + "copper_chest");
         chests.add("waxed_" + age + "copper_chest");
      }

      List<String> shulkers = new ArrayList<>(List.of("shulker_box"));
      for (String dye : DYES) {
         shulkers.add(dye + "_shulker_box");
      }

      JsonArray rules = new JsonArray();
      rules.add(blocks(prefix + "chests", chests, new Color(255, 160, 0)));
      rules.add(blocks(prefix + "trapped-chests", List.of("trapped_chest"), new Color(255, 0, 0)));
      rules.add(blocks(prefix + "barrels", List.of("barrel"), new Color(255, 160, 0)));
      rules.add(blocks(prefix + "shulker-boxes", shulkers, new Color(255, 160, 0)));
      rules.add(blocks(prefix + "ender-chests", List.of("ender_chest"), new Color(120, 0, 255)));
      rules.add(blocks(prefix + "other-storage", List.of(
         "furnace", "blast_furnace", "smoker", "brewing_stand", "chiseled_bookshelf", "crafter",
         "dispenser", "dropper", "decorated_pot", "hopper"
      ), new Color(140, 140, 140)));
      return rules.toString();
   }

   /** Tracers: friends in green and every other player in red. */
   public static String tracers() {
      JsonArray rules = new JsonArray();
      rules.add(players("default-tracers-friends", SpecificRules.FRIENDS, new Color(60, 255, 110)));
      rules.add(players("default-tracers-players", SpecificRules.ALL_PLAYERS, new Color(255, 60, 60)));
      return rules.toString();
   }

   private static JsonObject blocks(String id, List<String> blocks, Color color) {
      JsonArray members = new JsonArray();
      for (String block : blocks) {
         members.add(member("BLOCK", "minecraft:" + block, "BLOCKS"));
      }

      JsonObject settings = new JsonObject();
      settings.addProperty("TargetColorMode", "Custom");
      settings.add("TargetColor", color(color));
      settings.addProperty("TargetRange", 512.0F);
      return rule(id, members, settings);
   }

   private static JsonObject players(String id, String group, Color color) {
      JsonArray members = new JsonArray();
      members.add(member("GROUP", group, "PLAYERS"));
      JsonObject settings = new JsonObject();
      settings.add("TargetColor", color(color));
      settings.addProperty("TargetIgnoreNaked", false);
      settings.addProperty("TargetRange", 512.0F);
      return rule(id, members, settings);
   }

   /** A colour as {@code ColorSetting} saves it (built by hand: ColorSetting needs the game's Colors module). */
   private static JsonObject color(Color color) {
      JsonArray rgba = new JsonArray();
      rgba.add(color.getRed());
      rgba.add(color.getGreen());
      rgba.add(color.getBlue());
      rgba.add(color.getAlpha());
      JsonObject object = new JsonObject();
      object.add("rgba", rgba);
      object.addProperty("rainbow", false);
      object.addProperty("sync", false);
      return object;
   }

   private static JsonObject member(String type, String id, String category) {
      JsonObject member = new JsonObject();
      member.addProperty("type", type);
      member.addProperty("id", id);
      member.addProperty("category", category);
      return member;
   }

   private static JsonObject rule(String id, JsonArray members, JsonObject settings) {
      JsonObject rule = new JsonObject();
      rule.addProperty("id", id);
      rule.add("members", members);
      rule.addProperty("enabled", true);
      rule.addProperty("mirror", false);
      rule.add("settings", settings);
      return rule;
   }
}
