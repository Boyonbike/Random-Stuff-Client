package dev.argus.client.modules.basehunting;

import dev.argus.client.ClientAccess;
import dev.argus.client.events.EventHandler;
import dev.argus.client.events.LocalPlayerTickEvent;
import dev.argus.client.events.MinecraftDisconnectEvent;
import dev.argus.client.events.WorldRenderEvent;
import dev.argus.client.modules.Module;
import dev.argus.client.modules.ModuleCategory;
import dev.argus.client.settings.BooleanSetting;
import dev.argus.client.settings.ColorSetting;
import dev.argus.client.settings.NumberSetting;
import dev.argus.client.settings.RegistrySetSetting;
import dev.argus.client.settings.Setting;
import dev.argus.client.settings.StringSetting;
import dev.argus.client.utils.world.WorldUtils;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Every sign of players that you can see from a distance, in one module with a colour per kind. Collectibles: framed
 * maps, banners, valuable gear in frames, on the ground and on armour stands. Pots: decorated pots holding things that
 * never generate in them. Mob gear: mobs wearing or holding player gear. Vanity: framed shulker boxes, filled glow
 * frames, endermen carrying blocks, XP orbs, dropped items and mobs crammed into one block. Merges Trouser Streak's
 * CollectibleESP, PotESP and MobGearESP with bephax's and the stash hunting add-on's VanityESP.
 *
 * <p>Each category has its own card with an on switch, its own options and its own colour, shape, tracers and alerts;
 * the gear filter and the settings every category shares have cards of their own.
 */
public class HuntersESPModule extends Module {
   private static final Set<Item> NATURAL_POT_ITEMS = Set.of(HuntBlocks.items(
      "air", "string", "emerald", "emerald_block", "raw_iron_block", "iron_ingot", "trial_key", "diamond", "diamond_block", "music_disc_creator_music_box"
   ));
   // Built first so each category can use it; its card is added last, in the constructor.
   private final HuntMarkers.Shared shared = new HuntMarkers.Shared(this);
   // Collectibles: things players put up or leave lying about.
   private final Setting<Boolean> collectiblesGroup = this.group("Collectibles");
   private final Setting<Boolean> collectiblesSetting = this.enabled(this.collectiblesGroup, "Collectibles");
   private final Setting<Boolean> mapsSetting = this.option(this.collectiblesGroup, this.collectiblesSetting, new BooleanSetting("FramedMaps", true));
   private final Setting<Boolean> bannersSetting = this.option(this.collectiblesGroup, this.collectiblesSetting, new BooleanSetting("Banners", true));
   private final Setting<Boolean> framedGearSetting = this.option(this.collectiblesGroup, this.collectiblesSetting, new BooleanSetting("FramedGear", true));
   private final Setting<Boolean> groundGearSetting = this.option(this.collectiblesGroup, this.collectiblesSetting, new BooleanSetting("GroundGear", true));
   private final Setting<Boolean> standsSetting = this.option(this.collectiblesGroup, this.collectiblesSetting, new BooleanSetting("ArmorStands", true));
   private final HuntMarkers collectibles = this.category(this.collectiblesGroup, this.collectiblesSetting, "Collectibles", new Color(80, 255, 140, 230), "CollectibleColor");
   // Mob gear: mobs that picked up a player's things.
   private final Setting<Boolean> mobGearGroup = this.group("MobGear");
   private final Setting<Boolean> mobGearSetting = this.enabled(this.mobGearGroup, "MobGear");
   private final Setting<Boolean> listAllSetting = this.option(this.mobGearGroup, this.mobGearSetting, new BooleanSetting("ListEveryItem", true), "ListAllGear");
   private final Setting<Integer> hideWithinSetting = this.option(
      this.mobGearGroup, this.mobGearSetting, new NumberSetting<>("HideCloserThan", 0, 0, 64).withUnit("m"), "HideWithin"
   );
   private final HuntMarkers mobGear = this.category(this.mobGearGroup, this.mobGearSetting, "MobGear", new Color(255, 80, 200, 230), "MobGearColor");
   // Gear filter: what counts as a player's item, for framed, dropped and armour stand gear and for mob gear.
   private final Setting<Boolean> gearGroup = this.group("GearFilter");
   private final Setting<Set<Item>> gearSetting = this.gearOption(new RegistrySetSetting<>("Items", BuiltInRegistries.ITEM, HuntBlocks.items(HuntItems.PLAYER_GEAR)), "GearItems");
   private final Setting<Boolean> needEnchantsSetting = this.gearOption(new BooleanSetting("EnchantedOnly", true), "NeedEnchants");
   private final Setting<String> enchantListSetting = this.gearOption(new StringSetting("RequiredEnchants", ""), "FindEnchants");
   // Pots: decorated pots holding things that never generate in them.
   private final Setting<Boolean> potsGroup = this.group("Pots");
   private final Setting<Boolean> potsSetting = this.enabled(this.potsGroup, "Pots");
   private final Setting<Set<Item>> potIgnoreSetting = this.option(this.potsGroup, this.potsSetting, new RegistrySetSetting<>("IgnoreItems", BuiltInRegistries.ITEM), "PotIgnore");
   private final HuntMarkers pots = this.category(this.potsGroup, this.potsSetting, "Pots", new Color(255, 160, 60, 230), "PotColor");
   // Vanity: decoration and leftovers that only players make.
   private final Setting<Boolean> vanityGroup = this.group("Vanity");
   private final Setting<Boolean> vanitySetting = this.enabled(this.vanityGroup, "Vanity");
   private final Setting<Boolean> shulkerFramesSetting = this.option(this.vanityGroup, this.vanitySetting, new BooleanSetting("ShulkerFrames", true));
   private final Setting<Boolean> glowFramesSetting = this.option(this.vanityGroup, this.vanitySetting, new BooleanSetting("FilledGlowFrames", true), "GlowFrames");
   private final Setting<Boolean> endermenSetting = this.option(this.vanityGroup, this.vanitySetting, new BooleanSetting("CarryingEndermen", true), "Endermen");
   private final Setting<Boolean> orbsSetting = this.option(this.vanityGroup, this.vanitySetting, new BooleanSetting("XPOrbs", true));
   private final Setting<Boolean> dropsSetting = this.option(this.vanityGroup, this.vanitySetting, new BooleanSetting("DroppedItems", false));
   private final Setting<Set<Item>> dropFilterSetting = this.option(this.vanityGroup, this.vanitySetting, new RegistrySetSetting<>("DropItems", BuiltInRegistries.ITEM), "DropFilter");
   private final Setting<Integer> minDropSetting = this.option(this.vanityGroup, this.vanitySetting, new NumberSetting<>("DropMinCount", 1, 1, 64), "MinDropCount");
   private final Setting<Boolean> stackedSetting = this.option(this.vanityGroup, this.vanitySetting, new BooleanSetting("CrammedMobs", true), "Stacked");
   private final Setting<Integer> stackSizeSetting = this.option(this.vanityGroup, this.vanitySetting, new NumberSetting<>("CrammedMin", 6, 3, 50), "StackSize");
   private final HuntMarkers vanity = this.category(this.vanityGroup, this.vanitySetting, "Vanity", new Color(120, 160, 255, 220), "VanityColor");
   private final List<HuntMarkers> categories = List.of(this.collectibles, this.mobGear, this.pots, this.vanity);
   private final List<Entity> mobGearEntities = new ArrayList<>();
   private final List<Entity> vanityEntities = new ArrayList<>();
   private int ticks;

   public HuntersESPModule() {
      super("HuntersESP", "Highlights every visible sign of players: collectibles, mob gear, pots and vanity, each with its own colour and alerts.", ModuleCategory.BASE_HUNTING);
      this.shared.register("AllCategories");
      this.dropFilterSetting.addVisibilityPredicate(value -> this.dropsSetting.getValue());
      this.minDropSetting.addVisibilityPredicate(value -> this.dropsSetting.getValue());
      this.stackSizeSetting.addVisibilityPredicate(value -> this.stackedSetting.getValue());
      // The gear filter only matters to the two categories that look for gear.
      this.gearGroup.addVisibilityPredicate(value -> this.collectiblesSetting.getValue() || this.mobGearSetting.getValue());
   }

   /** A card header. Only the GUI uses it; it is never saved. */
   private Setting<Boolean> group(String name) {
      return this.add(new BooleanSetting(name, true).markAsGroup());
   }

   /** A category's on switch, first in its card and saved as e.g. {@code CollectiblesEnabled}. */
   private Setting<Boolean> enabled(Setting<Boolean> group, String prefix) {
      Setting<Boolean> enabled = new BooleanSetting("Enabled", true);
      enabled.dependsOn(group);
      enabled.setConfigName(prefix + "Enabled");
      return this.add(enabled);
   }

   /** A category's own option, shown while the category is on. */
   private <T> Setting<T> option(Setting<Boolean> group, Setting<Boolean> enabled, Setting<T> setting) {
      setting.dependsOn(group, enabled);
      return this.add(setting);
   }

   /** As above, saved under the name it had before the settings were reorganised so old configs keep their values. */
   private <T> Setting<T> option(Setting<Boolean> group, Setting<Boolean> enabled, Setting<T> setting, String savedAs) {
      setting.setConfigName(savedAs);
      return this.option(group, enabled, setting);
   }

   private <T> Setting<T> gearOption(Setting<T> setting, String savedAs) {
      setting.dependsOn(this.gearGroup);
      setting.setConfigName(savedAs);
      return this.add(setting);
   }

   private HuntMarkers category(Setting<Boolean> group, Setting<Boolean> enabled, String prefix, Color colour, String colourSavedAs) {
      return new HuntMarkers(this, this.shared, prefix, group, enabled, colour, false, true).savingColourAs(colourSavedAs);
   }

   @Override
   public void onDisable() {
      for (HuntMarkers category : this.categories) {
         category.clear();
      }

      this.mobGearEntities.clear();
      this.vanityEntities.clear();
   }

   @Override
   public String getInfo() {
      int total = this.mobGearEntities.size() + this.vanityEntities.size();
      if (this.collectiblesSetting.getValue()) {
         total += this.collectibles.size();
      }

      if (this.potsSetting.getValue()) {
         total += this.pots.size();
      }

      if (this.vanitySetting.getValue()) {
         total += this.vanity.size();
      }

      return total == 0 ? null : String.valueOf(total);
   }

   @EventHandler
   private void onDisconnect(MinecraftDisconnectEvent event) {
      this.onDisable();
   }

   private boolean isGear(ItemStack stack) {
      return HuntItems.isPlayerItem(stack, this.gearSetting.getValue(), this.needEnchantsSetting.getValue())
         && HuntItems.hasAnyEnchant(stack, this.enchantListSetting.getValue());
   }

   private List<ItemStack> gearOn(LivingEntity entity) {
      return HuntItems.allPlayerGear(entity, this.gearSetting.getValue(), this.needEnchantsSetting.getValue(), this.enchantListSetting.getValue());
   }

   @EventHandler
   private void onTick(LocalPlayerTickEvent event) {
      if (this.isWorldUnavailable() || ++this.ticks < 10) {
         return;
      }

      this.ticks = 0;
      this.mobGearEntities.clear();
      this.vanityEntities.clear();
      for (HuntMarkers category : this.categories) {
         category.pruneFarFrom(ClientAccess.argusclientMinecraft.player.position());
      }

      Map<String, List<Entity>> crowds = new HashMap<>();
      for (Entity entity : ClientAccess.argusclientMinecraft.level.entitiesForRendering()) {
         this.checkEntity(entity, crowds);
      }

      this.checkCrowds(crowds);
      this.checkBlockEntities();
   }

   private void checkEntity(Entity entity, Map<String, List<Entity>> crowds) {
      boolean collectiblesOn = this.collectiblesSetting.getValue();
      boolean vanityOn = this.vanitySetting.getValue();
      if (entity instanceof ItemFrame frame) {
         this.checkFrame(frame, collectiblesOn, vanityOn);
         return;
      }

      if (entity instanceof ItemEntity item) {
         ItemStack stack = item.getItem();
         if (collectiblesOn && this.groundGearSetting.getValue() && this.isGear(stack)) {
            this.collectibles.report(item.blockPosition(), new AABB(item.blockPosition()), stack.getCount() + " dropped " + stack.getHoverName().getString());
         } else if (vanityOn && this.dropsSetting.getValue() && this.wantedDrop(stack)) {
            this.vanityEntities.add(entity);
         }

         return;
      }

      if (entity instanceof ExperienceOrb) {
         if (vanityOn && this.orbsSetting.getValue()) {
            this.vanityEntities.add(entity);
         }

         return;
      }

      if (entity instanceof ArmorStand stand) {
         if (collectiblesOn && this.standsSetting.getValue()) {
            List<ItemStack> gear = this.gearOn(stand);
            if (!gear.isEmpty()) {
               this.collectibles.report(stand.blockPosition(), stand.getBoundingBox(), "an armour stand with " + gear.get(0).getHoverName().getString());
            }
         }

         return;
      }

      if (entity instanceof Player) {
         return;
      }

      if (vanityOn && this.endermenSetting.getValue()) {
         BlockState carried = HuntCompat.carriedBlock(entity);
         if (carried != null) {
            this.vanityEntities.add(entity);
            this.vanity.alertOnce(entity.getUUID(), entity.blockPosition(), "an enderman carrying " + carried.getBlock().getName().getString());
         }
      }

      if (this.mobGearSetting.getValue() && entity instanceof LivingEntity living) {
         this.checkMobGear(living);
      }

      if (vanityOn && this.stackedSetting.getValue()) {
         crowds.computeIfAbsent(EntityType.getKey(entity.getType()) + "@" + entity.blockPosition().asLong(), unused -> new ArrayList<>()).add(entity);
      }
   }

   /** A frame belongs to at most one category; whichever it is not in, it is taken out of, in case its item changed. */
   private void checkFrame(ItemFrame frame, boolean collectiblesOn, boolean vanityOn) {
      ItemStack stack = frame.getItem();
      BlockPos position = frame.blockPosition();
      String collectible = null;
      String decoration = null;
      if (collectiblesOn && this.mapsSetting.getValue() && stack.is(Items.FILLED_MAP)) {
         collectible = "a framed map";
      } else if (collectiblesOn && this.framedGearSetting.getValue() && this.isGear(stack)) {
         collectible = "a framed " + stack.getHoverName().getString();
      } else if (vanityOn && this.shulkerFramesSetting.getValue() && stack.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock) {
         decoration = "a framed shulker box";
      } else if (vanityOn && this.glowFramesSetting.getValue() && frame instanceof GlowItemFrame && !stack.isEmpty()) {
         // Glow item frames never generate, so a filled one is decoration someone put up.
         decoration = "a glow frame holding " + stack.getHoverName().getString();
      }

      if (collectible != null) {
         this.collectibles.report(position, frame.getBoundingBox(), collectible);
      } else {
         this.collectibles.remove(position);
      }

      if (decoration != null) {
         this.vanity.report(position, frame.getBoundingBox(), decoration);
      } else {
         this.vanity.remove(position);
      }
   }

   private void checkMobGear(LivingEntity living) {
      List<ItemStack> gear = this.gearOn(living);
      if (gear.isEmpty()) {
         return;
      }

      int hide = this.hideWithinSetting.getValue();
      if (hide <= 0 || living.distanceTo(ClientAccess.argusclientMinecraft.player) > hide) {
         this.mobGearEntities.add(living);
      }

      List<String> names = new ArrayList<>();
      for (ItemStack stack : this.listAllSetting.getValue() ? gear : gear.subList(0, 1)) {
         names.add(stack.getHoverName().getString() + (stack.isEnchanted() ? " (enchanted)" : ""));
      }

      this.mobGear.alertOnce(living.getUUID(), living.blockPosition(), "a " + living.getType().getDescription().getString() + " with " + String.join(", ", names));
   }

   private void checkCrowds(Map<String, List<Entity>> crowds) {
      int stackSize = this.stackSizeSetting.getValue();
      for (List<Entity> crowd : crowds.values()) {
         if (crowd.size() >= stackSize) {
            Entity first = crowd.get(0);
            this.vanityEntities.add(first);
            this.vanity.alertOnce(
               "stack" + first.blockPosition().asLong(), first.blockPosition(), crowd.size() + " " + first.getType().getDescription().getString() + " in one block"
            );
         }
      }
   }

   private void checkBlockEntities() {
      boolean banners = this.collectiblesSetting.getValue() && this.bannersSetting.getValue();
      boolean potsOn = this.potsSetting.getValue();
      if (!banners && !potsOn) {
         return;
      }

      for (BlockEntity blockEntity : WorldUtils.getLoadedBlockEntities()) {
         BlockPos position = blockEntity.getBlockPos();
         if (banners && blockEntity instanceof BannerBlockEntity) {
            this.collectibles.report(position, new AABB(position), "a banner");
         } else if (potsOn && blockEntity instanceof DecoratedPotBlockEntity pot) {
            ItemStack stack = pot.getTheItem();
            if (!stack.isEmpty() && !NATURAL_POT_ITEMS.contains(stack.getItem()) && !this.potIgnoreSetting.getValue().contains(stack.getItem())) {
               this.pots.report(position, new AABB(position), "a pot holding " + stack.getCount() + " " + stack.getHoverName().getString());
            }
         }
      }
   }

   private boolean wantedDrop(ItemStack stack) {
      Set<Item> filter = this.dropFilterSetting.getValue();
      return stack.getCount() >= this.minDropSetting.getValue() && (filter.isEmpty() || filter.contains(stack.getItem()));
   }

   @EventHandler
   private void onWorldRender(WorldRenderEvent.Main event) {
      float partialTick = event.getPartialTick();
      if (this.collectiblesSetting.getValue()) {
         this.collectibles.render(event);
      }

      if (this.potsSetting.getValue()) {
         this.pots.render(event);
      }

      this.mobGear.renderBoxes(event, boxes(this.mobGearEntities, partialTick));
      if (this.vanitySetting.getValue()) {
         this.vanity.render(event);
      }

      this.vanity.renderBoxes(event, boxes(this.vanityEntities, partialTick));
   }

   /** Where each entity is this frame, so boxes glide with them instead of jumping every half second. */
   private static List<AABB> boxes(List<Entity> entities, float partialTick) {
      List<AABB> boxes = new ArrayList<>(entities.size());
      for (Entity entity : entities) {
         if (!entity.isRemoved()) {
            boxes.add(HuntEntities.interpolatedBox(entity, partialTick));
         }
      }

      return boxes;
   }
}
