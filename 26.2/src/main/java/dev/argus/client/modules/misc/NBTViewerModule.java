package dev.argus.client.modules.misc;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.argus.client.ClientAccess;
import dev.argus.client.events.AbstractContainerScreenRenderTooltipEvent;
import dev.argus.client.events.EventHandler;
import dev.argus.client.events.LocalPlayerTickEvent;
import dev.argus.mixin.AbstractContainerScreenAccessor;
import dev.argus.client.modules.Module;
import dev.argus.client.modules.ModuleCategory;
import dev.argus.client.settings.BooleanSetting;
import dev.argus.client.settings.NumberSetting;
import dev.argus.client.settings.Setting;
import dev.argus.client.utils.KeyUtils;
import dev.argus.client.utils.chat.ChatUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Shows the NBT of items: on hovered tooltips, and in chat for the held item.
 * Inspired by Meteorist's ItemDataInspector (Zgoly/Meteorist).
 */
public class NBTViewerModule extends Module {
   /** Encodes every component, defaults included, unlike ItemStack.CODEC which only writes the patch. */
   private static final Codec<ItemStack> FULL_CODEC = RecordCodecBuilder.<ItemStack>mapCodec(
         instance -> instance.group(
               BuiltInRegistries.ITEM.holderByNameCodec().fieldOf("id").forGetter(ItemStack::typeHolder),
               ExtraCodecs.POSITIVE_INT.fieldOf("count").forGetter(ItemStack::getCount),
               DataComponentMap.CODEC.fieldOf("components").forGetter(ItemStack::immutableComponents)
            )
            .apply(instance, (holder, count, components) -> {
               ItemStack stack = new ItemStack(holder, count);
               stack.applyComponents(components);
               return stack;
            })
      )
      .codec();
   private static final String INDENT = "  ";

   private final Setting<Boolean> tooltipSetting = this.add(new BooleanSetting("Tooltip", true).markVisibilityDependency());
   private final Setting<Boolean> shiftSetting = this.add(new BooleanSetting("ShiftOnly", true).dependsOn(this.tooltipSetting));
   private final Setting<Integer> maxLinesSetting = this.add(new NumberSetting<>("MaxLines", 24, 4, 100).dependsOn(this.tooltipSetting));
   private final Setting<Boolean> chatSetting = this.add(new BooleanSetting("HeldInChat", true).markVisibilityDependency());
   private final Setting<Boolean> onChangeSetting = this.add(new BooleanSetting("OnChange", false).dependsOn(this.chatSetting));
   private final Setting<Boolean> copySetting = this.add(new BooleanSetting("Copy", false));
   private final Setting<Boolean> fullSetting = this.add(new BooleanSetting("Full", false));

   private ItemStack lastHeld = ItemStack.EMPTY;

   public NBTViewerModule() {
      super("NBTViewer", "Shows item NBT in tooltips and chat.", ModuleCategory.MISC);
      this.setDrawn(false);
   }

   @Override
   public void onEnable() {
      super.onEnable();
      this.lastHeld = ItemStack.EMPTY;
      LocalPlayer player = ClientAccess.argusclientMinecraft.player;
      if (player != null && this.chatSetting.getValue()) {
         this.lastHeld = player.getMainHandItem().copy();
         this.inspectHeld(player.getMainHandItem());
      }
   }

   @EventHandler
   private void onLocalPlayerTickEvent(LocalPlayerTickEvent event) {
      if (!this.chatSetting.getValue() || !this.onChangeSetting.getValue()) {
         return;
      }
      LocalPlayer player = ClientAccess.argusclientMinecraft.player;
      if (player == null) {
         return;
      }
      ItemStack held = player.getMainHandItem();
      if (!ItemStack.matches(held, this.lastHeld)) {
         this.lastHeld = held.copy();
         if (!held.isEmpty()) {
            this.inspectHeld(held);
         }
      }
   }

   @EventHandler
   private void onAbstractContainerScreenRenderTooltipEvent(AbstractContainerScreenRenderTooltipEvent event) {
      if (!this.tooltipSetting.getValue() || !(event.getScreen() instanceof AbstractContainerScreen)) {
         return;
      }
      Slot slot = ((AbstractContainerScreenAccessor)event.getScreen()).argusclient$getFocusedSlot();
      if (slot == null || slot.getItem().isEmpty()) {
         return;
      }
      @SuppressWarnings("unchecked")
      List<ClientTooltipComponent> components = event.getTooltipComponents();
      if (this.shiftSetting.getValue() && !KeyUtils.isShiftDown()) {
         components.add(ClientTooltipComponent.create(Component.literal("Hold Shift for NBT").withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText()));
         return;
      }
      Optional<Tag> nbt = this.encode(slot.getItem());
      if (nbt.isEmpty()) {
         return;
      }
      List<MutableComponent> lines = formatLines(nbt.get());
      int max = this.maxLinesSetting.getValue();
      for (int index = 0; index < lines.size() && index < max; index++) {
         components.add(ClientTooltipComponent.create(lines.get(index).getVisualOrderText()));
      }
      if (lines.size() > max) {
         Component more = Component.literal("... " + (lines.size() - max) + " more lines").withStyle(ChatFormatting.DARK_GRAY);
         components.add(ClientTooltipComponent.create(more.getVisualOrderText()));
      }
   }

   private void inspectHeld(ItemStack stack) {
      if (stack.isEmpty()) {
         ChatUtils.printChat(Component.literal("Main hand is empty."), ChatUtils.moduleSignature(this));
         return;
      }
      Optional<Tag> nbt = this.encode(stack);
      if (nbt.isEmpty()) {
         ChatUtils.printChat(Component.literal("Could not encode the held item."), ChatUtils.moduleSignature(this));
         return;
      }
      MutableComponent message = Component.literal("NBT: ").append(NbtUtils.toPrettyComponent(nbt.get()));
      // Each print replaces the previous one via the shared module signature, so one message holds it all.
      ChatUtils.printChat(message, ChatUtils.moduleSignature(this));
      if (this.copySetting.getValue()) {
         ClientAccess.argusclientMinecraft.keyboardHandler.setClipboard(nbt.get().toString());
         ChatUtils.printChat(Component.literal("Copied NBT to clipboard."), ChatUtils.createSignature(-Math.abs(("NBTViewerCopy").hashCode())));
      }
   }

   private Optional<Tag> encode(ItemStack stack) {
      LocalPlayer player = ClientAccess.argusclientMinecraft.player;
      if (player == null) {
         return Optional.empty();
      }
      try {
         Codec<ItemStack> codec = this.fullSetting.getValue() ? FULL_CODEC : ItemStack.CODEC;
         return codec.encodeStart(player.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack).result();
      } catch (RuntimeException failure) {
         return Optional.empty();
      }
   }

   /** Splits a tag into indented, vanilla-coloured lines; tooltips cannot wrap on newlines. */
   static List<MutableComponent> formatLines(Tag tag) {
      List<MutableComponent> lines = new ArrayList<>();
      appendTag(lines, Component.empty(), tag, 0, "");
      return lines;
   }

   private static void appendTag(List<MutableComponent> lines, MutableComponent prefix, Tag tag, int depth, String suffix) {
      String pad = INDENT.repeat(depth);
      if (tag instanceof CompoundTag compound && !compound.isEmpty()) {
         lines.add(Component.literal(pad).append(prefix).append("{"));
         List<String> keys = new ArrayList<>(compound.keySet());
         keys.sort(null);
         for (int index = 0; index < keys.size(); index++) {
            String key = keys.get(index);
            MutableComponent keyPrefix = Component.literal(key).withStyle(ChatFormatting.AQUA).append(Component.literal(": "));
            appendTag(lines, keyPrefix, compound.get(key), depth + 1, index < keys.size() - 1 ? "," : "");
         }
         lines.add(Component.literal(pad + "}" + suffix));
      } else if (tag instanceof ListTag list && !list.isEmpty()) {
         lines.add(Component.literal(pad).append(prefix).append("["));
         for (int index = 0; index < list.size(); index++) {
            appendTag(lines, Component.empty(), list.get(index), depth + 1, index < list.size() - 1 ? "," : "");
         }
         lines.add(Component.literal(pad + "]" + suffix));
      } else {
         lines.add(Component.literal(pad).append(prefix).append(NbtUtils.toPrettyComponent(tag)).append(suffix));
      }
   }
}
