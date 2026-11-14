package dev.argus.client.modules.render.targets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DefaultTargetsTest {
   @Test
   void espStartsWithMeteorsStorageGroups() {
      SpecificRules rules = new SpecificRules();
      rules.load(DefaultTargets.esp());
      assertEquals(6, rules.all().size());
      assertEquals(9, rules.find("default-chests").members().size());
      assertEquals(17, rules.find("default-shulker-boxes").members().size());
      assertEquals(10, rules.find("default-other-storage").members().size());
      assertNotNull(rules.owner(SpecificRules.Type.BLOCK, "minecraft:ender_chest"));
      // Loading and saving again changes nothing, so the list still counts as the defaults.
      assertEquals(DefaultTargets.esp(), rules.toJson());
   }

   @Test
   void tracersStartsWithFriendsAndPlayers() {
      SpecificRules rules = new SpecificRules();
      rules.load(DefaultTargets.tracers());
      assertEquals(2, rules.all().size());
      assertNotNull(rules.owner(SpecificRules.Type.GROUP, SpecificRules.FRIENDS));
      assertNotNull(rules.owner(SpecificRules.Type.GROUP, SpecificRules.ALL_PLAYERS));
      assertEquals(DefaultTargets.tracers(), rules.toJson());
   }

   @Test
   void storagePresetAddsMeteorsGroupsWithTheirColours() {
      SpecificRules rules = new SpecificRules();
      DefaultTargets.Applied applied = DefaultTargets.apply(rules, DefaultTargets.STORAGE);
      assertEquals(6, applied.groups());
      assertEquals(6, rules.all().size());
      SpecificRules.Rule ender = rules.owner(SpecificRules.Type.BLOCK, "minecraft:ender_chest");
      assertTrue(ender.body.read().contains("\"rgba\":[120,0,255,255]"), ender.body.read());
      assertTrue(ender.body.read().contains("\"TargetColorMode\":\"Custom\""));
   }

   @Test
   void storagePresetSkipsThingsAlreadyAdded() {
      SpecificRules rules = new SpecificRules();
      rules.add(java.util.List.of(new SpecificRules.Member(SpecificRules.Type.BLOCK, "minecraft:barrel", SpecificRules.Category.BLOCKS),
         new SpecificRules.Member(SpecificRules.Type.BLOCK, "minecraft:chest", SpecificRules.Category.BLOCKS)));
      assertEquals(37, DefaultTargets.missing(rules, DefaultTargets.STORAGE));
      DefaultTargets.Applied applied = DefaultTargets.apply(rules, DefaultTargets.STORAGE);
      // The barrel group is left out (its only block is taken); the chest group comes without the plain chest.
      assertEquals(5, applied.groups());
      assertEquals(2, applied.skipped());
      assertEquals(6, rules.all().size());
      assertEquals(8, rules.owner(SpecificRules.Type.BLOCK, "minecraft:copper_chest").members().size());

      assertEquals(0, DefaultTargets.missing(rules, DefaultTargets.STORAGE));
      // Applying again adds nothing.
      DefaultTargets.Applied again = DefaultTargets.apply(rules, DefaultTargets.STORAGE);
      assertEquals(0, again.groups());
      assertEquals(6, rules.all().size());
   }
}
