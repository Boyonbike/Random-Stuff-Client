package dev.argus.client.modules.basehunting;

import dev.argus.client.ClientAccess;
import dev.argus.client.events.EventHandler;
import dev.argus.client.events.MinecraftDisconnectEvent;
import dev.argus.client.events.PacketEvent;
import dev.argus.client.events.WorldRenderEvent;
import dev.argus.client.managers.RotationManager;
import dev.argus.client.modules.Module;
import dev.argus.client.modules.ModuleCategory;
import dev.argus.client.render.WorldBoxRenderer;
import dev.argus.client.settings.BooleanSetting;
import dev.argus.client.settings.ColorSetting;
import dev.argus.client.settings.NumberSetting;
import dev.argus.client.settings.Setting;
import dev.argus.client.utils.world.ChunkScanner;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.awt.Color;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Highlights flowing (non-source) water and lava. Flowing liquid in the wild usually means a spring or a broken
 * aquifer, so long runs of it underground or in odd places are often a sign that a player has been digging there.
 */
public class FlowESPModule extends Module {
   private static final byte LAVA = 1;
   private static final byte FALLING = 2;
   private static final int AMOUNT_SHIFT = 2;

   private final Setting<Integer> rangeSetting = this.add(new NumberSetting<>("Range", 64, 8, 256).withUnit("m"));
   private final Setting<Boolean> waterSetting = this.add(new BooleanSetting("Water", true));
   private final Setting<Boolean> lavaSetting = this.add(new BooleanSetting("Lava", true));
   private final Setting<Boolean> fallingSetting = this.add(new BooleanSetting("Falling", true));
   private final Setting<Boolean> fillSetting = this.add(new BooleanSetting("Fill", true));
   private final Setting<Boolean> outlineSetting = this.add(new BooleanSetting("Outline", true));
   private final Setting<Float> lineWidthSetting = this.add(new NumberSetting<>("LineWidth", 1.0F, 0.1F, 3.0F));
   private final Setting<Boolean> colorsSetting = this.add(new BooleanSetting("Colors", true).markAsGroup());
   private final Setting<Color> waterColorSetting = this.add(new ColorSetting("WaterColor", new Color(60, 140, 255, 200)).dependsOn(this.colorsSetting));
   private final Setting<Color> lavaColorSetting = this.add(new ColorSetting("LavaColor", new Color(255, 110, 30, 200)).dependsOn(this.colorsSetting));

   /** Flowing liquid per chunk, keyed by packed block position. Each map is replaced, never changed, so rendering can read it from another thread. */
   private final Map<Long, Long2ByteMap> chunks = new ConcurrentHashMap<>();
   private final ChunkScanner chunkScanner = new ChunkScanner(1, this::scanChunk, this::unloadChunk, this::updateBlock);
   private int count;

   public FlowESPModule() {
      super("FlowESP", "Highlights flowing water and lava.", ModuleCategory.BASE_HUNTING);
      this.lineWidthSetting.addVisibilityPredicate(value -> this.outlineSetting.getValue());
   }

   @Override
   public void onEnable() {
      this.chunks.clear();
      this.chunkScanner.start();
      if (!this.isWorldUnavailable()) {
         this.chunkScanner.scanLoadedChunks();
      }
   }

   @Override
   public void onDisable() {
      this.chunkScanner.stop();
      this.chunks.clear();
      this.count = 0;
   }

   @Override
   public String getInfo() {
      return this.count == 0 ? null : String.valueOf(this.count);
   }

   @EventHandler
   private void onDisconnect(MinecraftDisconnectEvent event) {
      this.chunks.clear();
   }

   @EventHandler
   private void onPacketReceive(PacketEvent.Receive event) {
      if (event.getPacket() instanceof ClientboundRespawnPacket || event.getPacket() instanceof ClientboundLoginPacket) {
         this.chunks.clear();
      }
   }

   /** Packs a fluid into a byte, or returns 0 when it is not flowing liquid. */
   private static byte encode(FluidState fluid) {
      if (fluid.isEmpty() || fluid.isSource()) {
         return 0;
      }

      boolean lava = fluid.is(FluidTags.LAVA);
      if (!lava && !fluid.is(FluidTags.WATER)) {
         return 0;
      }

      boolean falling = fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING);
      int amount = Math.clamp(fluid.getAmount(), 1, 8);
      return (byte)((lava ? LAVA : 0) | (falling ? FALLING : 0) | amount << AMOUNT_SHIFT);
   }

   private void scanChunk(ChunkPos chunkPosition, LevelChunk chunk) {
      Long2ByteOpenHashMap found = new Long2ByteOpenHashMap();
      LevelChunkSection[] sections = chunk.getSections();
      int minY = chunk.getMinY();
      int baseX = chunkPosition.getMinBlockX();
      int baseZ = chunkPosition.getMinBlockZ();

      for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
         LevelChunkSection section = sections[sectionIndex];
         if (section == null || section.hasOnlyAir() || !section.maybeHas(state -> encode(state.getFluidState()) != 0)) {
            continue;
         }

         int sectionY = minY + sectionIndex * 16;
         for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
               for (int z = 0; z < 16; z++) {
                  byte code = encode(section.getFluidState(x, y, z));
                  if (code != 0) {
                     found.put(BlockPos.asLong(baseX + x, sectionY + y, baseZ + z), code);
                  }
               }
            }
         }
      }

      long key = chunkPosition.toLong();
      if (found.isEmpty()) {
         this.chunks.remove(key);
      } else {
         this.chunks.put(key, found);
      }
   }

   private void unloadChunk(ChunkPos chunkPosition, LevelChunk chunk) {
      this.chunks.remove(chunkPosition.toLong());
   }

   private void updateBlock(BlockPos position, BlockState state) {
      long key = ChunkPos.asLong(position.getX() >> 4, position.getZ() >> 4);
      byte code = encode(state.getFluidState());
      Long2ByteMap current = this.chunks.get(key);
      long packed = position.asLong();
      if (current == null ? code == 0 : current.get(packed) == code) {
         return;
      }

      Long2ByteOpenHashMap next = current == null ? new Long2ByteOpenHashMap() : new Long2ByteOpenHashMap(current);
      if (code == 0) {
         next.remove(packed);
      } else {
         next.put(packed, code);
      }

      if (next.isEmpty()) {
         this.chunks.remove(key);
      } else {
         this.chunks.put(key, next);
      }
   }

   @EventHandler
   private void onWorldRender(WorldRenderEvent.Main event) {
      if (this.isWorldUnavailable()) {
         return;
      }

      boolean water = this.waterSetting.getValue();
      boolean lava = this.lavaSetting.getValue();
      boolean falling = this.fallingSetting.getValue();
      boolean fill = this.fillSetting.getValue();
      boolean outline = this.outlineSetting.getValue();
      float lineWidth = this.lineWidthSetting.getValue();
      Color waterColor = this.waterColorSetting.getValue();
      Color lavaColor = this.lavaColorSetting.getValue();
      Color waterFill = withAlpha(waterColor, waterColor.getAlpha() / 4);
      Color lavaFill = withAlpha(lavaColor, lavaColor.getAlpha() / 4);

      Vec3 camera = ClientAccess.argusclientMinecraft.gameRenderer.getMainCamera().position();
      int range = this.rangeSetting.getValue();
      double rangeSquared = (double)range * range;
      int cameraChunkX = (int)Math.floor(camera.x) >> 4;
      int cameraChunkZ = (int)Math.floor(camera.z) >> 4;
      int chunkRange = (range >> 4) + 1;
      int shown = 0;

      for (Map.Entry<Long, Long2ByteMap> entry : this.chunks.entrySet()) {
         long chunkKey = entry.getKey();
         if (Math.abs(ChunkPos.getX(chunkKey) - cameraChunkX) > chunkRange || Math.abs(ChunkPos.getZ(chunkKey) - cameraChunkZ) > chunkRange) {
            continue;
         }

         for (Long2ByteMap.Entry block : entry.getValue().long2ByteEntrySet()) {
            byte code = block.getByteValue();
            boolean isLava = (code & LAVA) != 0;
            boolean isFalling = (code & FALLING) != 0;
            if (isLava ? !lava : !water) {
               continue;
            }

            if (isFalling && !falling) {
               continue;
            }

            long packed = block.getLongKey();
            int x = BlockPos.getX(packed);
            int y = BlockPos.getY(packed);
            int z = BlockPos.getZ(packed);
            double dx = x + 0.5 - camera.x;
            double dy = y + 0.5 - camera.y;
            double dz = z + 0.5 - camera.z;
            if (dx * dx + dy * dy + dz * dz > rangeSquared) {
               continue;
            }

            // Match the liquid's surface: falling columns fill the block, flowing layers shrink as they spread.
            double height = isFalling ? 1.0 : ((code >> AMOUNT_SHIFT) & 0xF) / 9.0;
            AABB box = new AABB(x, y, z, x + 1, y + height, z + 1);
            if (!RotationManager.isVisible(box)) {
               continue;
            }

            shown++;
            if (fill) {
               WorldBoxRenderer.fillBox(event.getPoseStack(), box, isLava ? lavaFill : waterFill);
            }

            if (outline) {
               WorldBoxRenderer.outlineBox(event.getPoseStack(), box, isLava ? lavaColor : waterColor, lineWidth);
            }
         }
      }

      this.count = shown;
   }

   private static Color withAlpha(Color color, int alpha) {
      return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.clamp(alpha, 0, 255));
   }
}
