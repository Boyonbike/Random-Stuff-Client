package dev.argus.client.utils.world;

import dev.argus.client.ClientAccess;
import dev.argus.client.ClientContext;
import dev.argus.client.events.ConnectScreenConnectEvent;
import dev.argus.client.events.EventHandler;
import dev.argus.client.events.LocalPlayerTickEvent;
import dev.argus.client.events.PacketEvent;
import dev.argus.client.events.bus.EventBus;
import dev.argus.client.managers.ModuleRegistry;
import dev.argus.client.managers.ServerStateManager;
import dev.argus.client.managers.WorldStateManager;
import dev.argus.client.modules.player.SpeedMineModule;
import dev.argus.client.settings.Setting;
import dev.argus.client.utils.ElapsedTimer;
import dev.argus.client.utils.inventory.EnchantmentUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockBreakTracker implements ClientAccess {
   private static SpeedMineModule speedMine = ClientContext.getModule(SpeedMineModule.class);
   private static ItemStack miningPickaxe;
   private final List<BlockBreakEntry> entries = Collections.synchronizedList(new ArrayList<>());

   private static ItemStack getMiningPickaxe() {
      if (miningPickaxe == null) {
         miningPickaxe = new ItemStack(Items.NETHERITE_PICKAXE);
         ItemStack pickaxe = miningPickaxe;
         ResourceKey efficiency = Enchantments.EFFICIENCY;
         Holder enchantment = EnchantmentUtils.getHolder(efficiency);
         pickaxe.enchant(enchantment, 5);
      }

      return miningPickaxe;
   }

   public List<BlockBreakEntry> getEntries() {
      return this.entries;
   }

   private void onBlockRemoved(BlockPos position) {
      ClientAccess.argusclientMinecraft.execute(() -> {
         for (BlockBreakEntry entry : this.entries) {
            if (position.equals(entry.getPosition())) {
               if (!speedMine.isInstantRebreak()) {
                  entry.setProgress(0.0F);
                  entry.setAirTicks(0);
               }

               entry.markBroken();
               ElapsedTimer progressTimer = entry.getProgressTimer();
               progressTimer.reset();
            }
         }
      });
   }

   public BlockBreakTracker() {
      EventBus eventBus = ClientAccess.eventBus;
      eventBus.subscribe(this);
   }

   @EventHandler
   private void onLocalPlayerTickEvent(LocalPlayerTickEvent event) {
      this.entries.removeIf(BlockBreakEntry::isExpired);

      for (BlockBreakEntry entry : this.entries) {
         ClientLevel levelSnapshot = ClientAccess.argusclientMinecraft.level;
         BlockPos statePosition = entry.getPosition();
         BlockState blockState = levelSnapshot.getBlockState(statePosition);
         ItemStack pickaxeSnapshot = getMiningPickaxe();
         float baseProgress = (float)WorldUtils.getBreakProgress(pickaxeSnapshot, blockState, true);
         ServerStateManager serverStateSnapshot = ClientContext.serverStateManager;
         float progressDelta = baseProgress * serverStateSnapshot.getTickRateScale();
         SpeedMineModule speedMineSnapshot = speedMine;
         if (speedMineSnapshot.isToggled() && !entry.isExtraBreak()) {
            Setting<Float> damageSetting = speedMine.damageSetting;
            Float damageScale = damageSetting.getValue();
            progressDelta /= damageScale;
         }

         BlockPos miningPositionSnapshot = entry.getPosition();
         if (WorldUtils.canMineBlock(miningPositionSnapshot)) {
            int airTicksSnapshot = entry.getAirTicks();
            progressDelta *= Math.max(airTicksSnapshot, 1);
            entry.setAirTicks(0);
         } else {
            progressDelta = 0.0F;
            int nextAirTicks = entry.getAirTicks() + 1;
            entry.setAirTicks(nextAirTicks);
            entry.resetInactivityTimer();
         }

         float previousProgress = entry.getProgress();
         float nextProgress = previousProgress + progressDelta;
         entry.setProgress(nextProgress);
         if (Math.min(nextProgress, 1.0) > previousProgress) {
            ElapsedTimer progressTimerSnapshot = entry.getProgressTimer();
            progressTimerSnapshot.reset();
         }

         if (nextProgress >= 0.8 && !entry.isBroken()) {
            ElapsedTimer rollbackTimerSnapshot = entry.getProgressTimer();
            if (!rollbackTimerSnapshot.hasElapsed(150L)) {
               WorldStateManager worldStateSnapshot = ClientContext.worldStateManager;
               BlockPos rollbackPositionSnapshot = entry.getPosition();
               worldStateSnapshot.saveBlockForRollback(rollbackPositionSnapshot);
            }
         }
      }
   }

   private void trackBlockBreaking(BlockPos position, int entityId) {
      boolean alreadyTracked = false;
      boolean hasExtraBreak = false;

      for (BlockBreakEntry entry : this.entries) {
         if (entry.getEntityId() == entityId) {
            if (entry.getPosition().equals(position)) {
               alreadyTracked = true;
            }

            if (entry.isExtraBreak()) {
               hasExtraBreak = true;
            }
         }
      }

      if (!alreadyTracked) {
         boolean extraBreak = false;
         if (!hasExtraBreak) {
            Setting<Boolean> extraBreakSetting = speedMine.doubleBreakSetting;
            Boolean extraBreakEnabled = extraBreakSetting.getValue();
            extraBreak = extraBreakEnabled;
         }

         this.entries
            .removeIf(
               existingEntry -> existingEntry.isExtraBreak() ? false : existingEntry.getPosition().equals(position) || existingEntry.getEntityId() == entityId
            );
         if (WorldUtils.canMineBlock(position)) {
            this.entries.add(new BlockBreakEntry(entityId, position, extraBreak));
         }
      }
   }

   @EventHandler
   private void onConnectScreenConnectEvent(ConnectScreenConnectEvent event) {
      this.entries.clear();
   }

   @EventHandler
   private void onPacketReceive(PacketEvent.Receive event) {
      if (event.getPacket() instanceof ClientboundBlockDestructionPacket destructionPacket) {
         ClientAccess.argusclientMinecraft.execute(() -> {
            if (destructionPacket.getProgress() >= 0) {
               int breakStage = destructionPacket.getProgress();
               if (breakStage <= 10) {
                  BlockPos positionSnapshot = destructionPacket.getPos();
                  int breakerId = destructionPacket.getId();
                  this.trackBlockBreaking(positionSnapshot, breakerId);
               }
            }
         });
      }

      if (event.getPacket() instanceof ClientboundBlockUpdatePacket updatePacket) {
         BlockState updatedState = updatePacket.getBlockState();
         if (updatedState.isAir()) {
            BlockPos removedPosition = updatePacket.getPos();
            this.onBlockRemoved(removedPosition);
         }
      }

      if (event.getPacket() instanceof ClientboundSectionBlocksUpdatePacket sectionPacket) {
         sectionPacket.runUpdates((position, updatedState) -> {
            if (updatedState.isAir()) {
               this.onBlockRemoved(position);
            }
         });
      }
   }

   public float getBreakProgress(BlockPos position) {
      return !WorldUtils.canMineBlock(position)
         ? 0.0F
         : this.entries.stream().filter(entry -> entry.getPosition().equals(position)).map(BlockBreakEntry::getProgress).findAny().orElse(0.0F);
   }

   public static void bindModules(ModuleRegistry modules) {
      speedMine = (SpeedMineModule)modules.getModule(SpeedMineModule.class);
   }
}
