/*
 * This file is part of THM Addons — https://github.com/Leonn170709/THM-Addons
 * Copyright (c) THM Addons contributors. Credit the devs, keep the link.
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted for Comet (Minecraft 26.2) by Boyonabike in October 2026; Comet's Auto Mine
 * target selection is ported from BlackOut's AutoMine.
 * Ported to Argus Client by Boyonabike on 2026-11-14: Argus settings, events, rotation
 * manager, friends, MultiTask and box renderer replace their Meteor counterparts. On top of
 * Comet's mining core it brings back Argus's own SpeedMine features: the totem guard,
 * crystal/anchor/shulker/NoGlitchBlocks checks, AutoCrystal and AutoExp coordination, the
 * block filter, InstantDelay/HoldingBest/Async, AutoSwap None/Normal/Silent with SwapBack,
 * Alternative and the 1.21 timing, and the old render modes.
 * Distributed under GNU GPL v3; see licenses/GPL-3.0.txt and THIRD_PARTY_NOTICES.md.
 * This program is distributed WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package dev.argus.client.modules.player;

import dev.argus.client.ClientAccess;
import dev.argus.client.ClientContext;
import dev.argus.client.events.ClientLevelAddEntityEvent;
import dev.argus.client.events.EventHandler;
import dev.argus.client.events.LocalPlayerTickEvent;
import dev.argus.client.events.MinecraftDisconnectEvent;
import dev.argus.client.events.MinecraftTickEvent;
import dev.argus.client.events.MultiPlayerGameModeStartDestroyBlockEvent;
import dev.argus.client.events.PacketEvent;
import dev.argus.client.events.SelectedSlotChangeEvent;
import dev.argus.client.events.WorldRenderEvent;
import dev.argus.client.managers.ModuleRegistry;
import dev.argus.client.managers.RotationManager;
import dev.argus.client.modules.Module;
import dev.argus.client.modules.ModuleCategory;
import dev.argus.client.modules.combat.AnchorAuraModule;
import dev.argus.client.modules.combat.AutoCrystalModule;
import dev.argus.client.modules.combat.AutoExpModule;
import dev.argus.client.modules.combat.AutoMineModule;
import dev.argus.client.modules.exploit.MultiTaskModule;
import dev.argus.client.modules.render.LogoutSpotsModule;
import dev.argus.client.render.WorldBoxRenderer;
import dev.argus.client.settings.BooleanSetting;
import dev.argus.client.settings.ColorSetting;
import dev.argus.client.settings.EnumSetting;
import dev.argus.client.settings.KeyBindSetting;
import dev.argus.client.settings.NamedEntry;
import dev.argus.client.settings.NumberSetting;
import dev.argus.client.settings.RegistrySetSetting;
import dev.argus.client.settings.Setting;
import dev.argus.client.settings.SettingLabelCondition;
import dev.argus.client.settings.options.BlockSampleMode;
import dev.argus.client.settings.options.ListFilterMode;
import dev.argus.client.settings.options.SpeedMineAutoSwapChoice;
import dev.argus.client.settings.options.SpeedMineColorModeChoice;
import dev.argus.client.settings.options.SpeedMineRenderModeChoice;
import dev.argus.client.settings.options.SpeedMineValue121Choice;
import dev.argus.client.utils.ElapsedTimer;
import dev.argus.client.utils.KeyUtils;
import dev.argus.client.utils.chat.ChatUtils;
import dev.argus.client.utils.combat.ExtraBreakTarget;
import dev.argus.client.utils.entity.EntityUtils;
import dev.argus.client.utils.entity.SwingAction;
import dev.argus.client.utils.inventory.ExtraBreakTracker;
import dev.argus.client.utils.inventory.InventoryUtils;
import dev.argus.client.utils.network.PacketSender;
import dev.argus.client.utils.world.BlockBreakingState;
import dev.argus.client.utils.world.WorldUtils;
import dev.argus.mixin.ClientLevelAccessor;
import dev.argus.mixin.ServerboundMovePlayerPacketAccessor;
import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Comet's packet miner (server-timed progress, double break, queue, sequenced prediction) with Argus's SpeedMine extras. */
public class SpeedMineModule extends Module {
   private static final Minecraft mc = ClientAccess.argusclientMinecraft;
   /** Ticks with nothing left to mine before the slot is handed back to the client's real one. */
   private static final int IDLE_RELEASE_TICKS = 3;
   private static final int ROTATION_PRIORITY = 150;
   /** With 1.21 Tick, a silent break whose unenchanted progress is below this keeps the tool an extra tick. */
   private static final float TICK_121_PROGRESS = 0.425F;
   /** How long a block removed early waits for the server's update before it is put back. */
   private static final long AWAIT_SERVER_MILLIS = 1500L;

   private static MultiTaskModule multiTaskModule = ClientContext.getModule(MultiTaskModule.class);
   private static AutoCrystalModule autoCrystalModule = ClientContext.getModule(AutoCrystalModule.class);
   private static AutoExpModule autoExpModule = ClientContext.getModule(AutoExpModule.class);
   private static AutoMineModule autoMineModule = ClientContext.getModule(AutoMineModule.class);
   private static AnchorAuraModule anchorAuraModule = ClientContext.getModule(AnchorAuraModule.class);
   private static NoGlitchBlocksModule noGlitchBlocksModule = ClientContext.getModule(NoGlitchBlocksModule.class);
   private static ItemSaverModule itemSaverModule = ClientContext.getModule(ItemSaverModule.class);
   private static LogoutSpotsModule logoutSpotsModule = ClientContext.getModule(LogoutSpotsModule.class);

   /** True while AutoMine/Nuker start a block through {@code startDestroyBlock}. */
   public static boolean automatedMiningStart;
   /** Above zero while Minecraft handles the attack key; set by MinecraftMixin. */
   private static int playerAttackDepth;
   private static volatile boolean swapPending;

   // General
   protected final Setting<Set<Block>> blocksSetting = this.add(new RegistrySetSetting<>("Blocks", BuiltInRegistries.BLOCK, Blocks.OBSIDIAN, Blocks.ENDER_CHEST));
   public final Setting<ListFilterMode> modeSetting = this.add(new EnumSetting<>("Mode", ListFilterMode.ANY));
   public final Setting<Float> rangeSetting = this.add(new NumberSetting<>("Range", 5.2F, 1.0F, 6.0F).withUnit("m"));
   private final Setting<Boolean> rotateSetting = this.add(new BooleanSetting("Rotate", false).markVisibilityDependency());
   /** Turn toward the block only once progress reaches this fraction; at the maximum it rotates the whole time. */
   private final Setting<Float> limitSetting = this.add(
      new NumberSetting<>("Limit", 0.9F, 0.0F, 1.0F, ignored -> this.rotateSetting.isExpanded()).setBoundaryLabel("Off", SettingLabelCondition.MAX)
   );
   private final Setting<Boolean> swingSetting = this.add(new BooleanSetting("Swing", true));
   private final Setting<Boolean> grimBypassSetting = this.add(new BooleanSetting("GrimBypass", true));
   public final Setting<Boolean> doubleBreakSetting = this.add(new BooleanSetting("DoubleBreak", true).markVisibilityDependency());
   /** While sneaking, the block you click is pushed one block further along your view for double break. */
   public final Setting<Boolean> sneakExtendSetting = this.add(new BooleanSetting("SneakExtend", false).dependsOn(this.doubleBreakSetting));
   public final Setting<Boolean> queueSetting = this.add(new BooleanSetting("Queue", true));
   /** Mining progress the primary block needs before STOP is sent; the secondary always waits for 1.0. */
   public final Setting<Float> damageSetting = this.add(new NumberSetting<>("BreakThreshold", 0.7F, 0.1F, 1.0F));
   private final Setting<Boolean> clientPredictionSetting = this.add(new BooleanSetting("ClientPrediction", true));
   private final Setting<Boolean> tpsSyncSetting = this.add(new BooleanSetting("TPSSync", false));
   // Rebreak
   private final Setting<Boolean> autoRebreakSetting = this.add(new BooleanSetting("AutoRebreak", true).markAsGroup().markVisibilityDependency());
   private final Setting<RebreakMode> rebreakModeSetting = this.add(new EnumSetting<>("RebreakMode", RebreakMode.STRONG).dependsOn(this.autoRebreakSetting));
   private final Setting<RebreakTrigger> rebreakTriggerSetting = this.add(
      new EnumSetting<>("RebreakTrigger", RebreakTrigger.ON_PACKET).dependsOn(this.autoRebreakSetting)
   );
   protected final Setting<Float> instantDelaySetting = this.add(new NumberSetting<>("InstantDelay", 0.0F, 0.0F, 2.0F).withUnit("s").dependsOn(this.autoRebreakSetting));
   private final Setting<Boolean> holdingBestSetting = this.add(new BooleanSetting("HoldingBest", false).dependsOn(this.autoRebreakSetting));
   /** Keeps sending rebreaks while the block is still air, so a replacement goes the moment it lands. */
   public final Setting<Boolean> asyncSetting = this.add(new BooleanSetting("Async", false).dependsOn(this.autoRebreakSetting));
   private final Setting<Boolean> debugRebreakSetting = this.add(new BooleanSetting("DebugRebreak", false).dependsOn(this.autoRebreakSetting));
   // Swap
   private final Setting<Boolean> swapPageSetting = this.add(new BooleanSetting("AutoSwap", true).markAsGroup().markVisibilityDependency());
   final Setting<SpeedMineAutoSwapChoice> autoSwapModeSetting = this.add(new EnumSetting<>("AutoSwap", SpeedMineAutoSwapChoice.SILENT, this::isSwapPageExpanded));
   private final Setting<SwapMode> swapModeSetting = this.add(new EnumSetting<>("SwapMode", SwapMode.SAME_TICK, ignored -> this.isSwapPageExpanded(null) && this.isSilent()));
   private final Setting<Boolean> swapBackSetting = this.add(
      new BooleanSetting("SwapBack", false, ignored -> this.isSwapPageExpanded(null) && this.autoSwapModeSetting.getValue() == SpeedMineAutoSwapChoice.NORMAL)
   );
   private final Setting<SpeedMineValue121Choice> value121Setting = this.add(
      new EnumSetting<>("1.21", SpeedMineValue121Choice.OFF, ignored -> this.isSwapPageExpanded(null) && this.isSilent())
   );
   public final Setting<Boolean> alternativeSetting = this.add(new BooleanSetting("Alternative", false, ignored -> this.isSwapPageExpanded(null) && this.isSilent()));
   public final Setting<Boolean> swapResetSetting = this.add(
      new BooleanSetting("SwapReset", true, ignored -> this.isSwapPageExpanded(null) && this.isSilent() && this.alternativeSetting.getValue())
   );
   // Auto Mine (Comet's, from BlackOut)
   private final Setting<Boolean> autoMineSetting = this.add(new BooleanSetting("AutoMine", false).markAsGroup().markVisibilityDependency());
   private final KeyBindSetting autoMineBindSetting = this.add(new KeyBindSetting("AutoMineBind", "Always", ignored -> this.autoMineSetting.isExpanded()));
   private final Setting<Boolean> debugAutoMineSetting = this.add(new BooleanSetting("DebugAutoMine", false).dependsOn(this.autoMineSetting));
   private final Setting<Boolean> autoMineOnlySetting = this.add(new BooleanSetting("AutoMineOnly", false).dependsOn(this.autoMineSetting));
   private final Setting<Boolean> feetFirstSetting = this.add(new BooleanSetting("FeetFirst", false).dependsOn(this.autoMineSetting));
   private final Setting<Float> enemyRangeSetting = this.add(new NumberSetting<>("EnemyRange", 10.0F, 1.0F, 20.0F).withUnit("m").dependsOn(this.autoMineSetting));
   private final Setting<Boolean> antiPhaseSetting = this.add(new BooleanSetting("AntiPhase", true).dependsOn(this.autoMineSetting));
   private final Setting<Boolean> antiSurroundSetting = this.add(new BooleanSetting("AntiSurround", true).dependsOn(this.autoMineSetting));
   private final Setting<Boolean> neverMineOwnSetting = this.add(new BooleanSetting("NeverMineOwn", true).dependsOn(this.autoMineSetting));
   private final Setting<Boolean> autoDoubleMineSetting = this.add(new BooleanSetting("AutoDoubleMine", true).dependsOn(this.autoMineSetting));
   private final Setting<Boolean> mineBedrockSetting = this.add(new BooleanSetting("MineBedrock", false).dependsOn(this.autoMineSetting));
   private final Setting<Boolean> bedrockOnlySetting = this.add(
      new BooleanSetting("BedrockOnly", false, ignored -> this.autoMineSetting.isExpanded() && this.mineBedrockSetting.getValue())
   );
   private final Setting<Boolean> bedrockWhileEatingSetting = this.add(
      new BooleanSetting("BedrockWhileEating", false, ignored -> this.autoMineSetting.isExpanded() && this.mineBedrockSetting.getValue())
   );
   private final Setting<Boolean> bedrockRotateSetting = this.add(
      new BooleanSetting("BedrockRotate", false, ignored -> this.autoMineSetting.isExpanded() && this.mineBedrockSetting.getValue())
   );
   // Air miner
   private final Setting<Boolean> airMineSetting = this.add(new BooleanSetting("AirMine", false).markAsGroup().markVisibilityDependency());
   private final Setting<Boolean> airWhileGlidingSetting = this.add(new BooleanSetting("AirWhileGliding", false).dependsOn(this.airMineSetting));
   private final Setting<Float> airMaxSpeedSetting = this.add(new NumberSetting<>("AirMaxSpeed", 1.0F, 0.0F, 3.0F).dependsOn(this.airMineSetting));
   private final Setting<Integer> airClaimWindowSetting = this.add(new NumberSetting<>("AirClaimWindow", 2, 1, 10).dependsOn(this.airMineSetting));
   // Render
   final Setting<Boolean> renderSetting = this.add(new BooleanSetting("Render", true).markAsGroup().markVisibilityDependency());
   public final Setting<Float> lineWidthSetting = this.add(new NumberSetting<>("LineWidth", 1.0F, 0.0F, 5.0F).dependsOn(this.renderSetting));
   public final Setting<SpeedMineRenderModeChoice> renderModeSetting = this.add(new EnumSetting<>("RenderMode", SpeedMineRenderModeChoice.OUT).dependsOn(this.renderSetting));
   /** Keeps drawing the rebreak position while it is air. */
   public final Setting<Boolean> airSetting = this.add(new BooleanSetting("Air", false, ignored -> this.renderSetting.isExpanded() && this.isInstantRebreak()));
   public final Setting<Boolean> fadeSetting = this.add(new BooleanSetting("Fade", false).dependsOn(this.renderSetting));
   public final Setting<SpeedMineColorModeChoice> colorModeSetting = this.add(new EnumSetting<>("ColorMode", SpeedMineColorModeChoice.PROGRESS).dependsOn(this.renderSetting));
   public final Setting<Color> fillSetting = this.add(new ColorSetting("Fill", new Color(280861183, true), this::isCustomColorVisible));
   public final Setting<Color> outlineSetting = this.add(new ColorSetting("Outline", new Color(-1044538881, true), this::isCustomColorVisible));
   private final Setting<Color> bedrockColorSetting = this.add(
      new ColorSetting("BedrockColor", new Color(255, 70, 70, 255), ignored -> this.renderSetting.isExpanded() && this.mineBedrockSetting.getValue())
   );

   // Air miner state
   private int airClaimLeft;
   private long airClaimTick = -1L;
   private boolean airInjecting;
   private Connection airClaimConnection;

   // Mining state
   private MineContext primary;
   private MineContext secondary;
   private BlockPos lastBrokenPos;
   private boolean lastBreakConfirmed;
   private boolean rebreakPending;
   private boolean bypassConfirmed;
   private long rebreakDeadlineMs;
   private BlockState packetReplacement;
   private long rebreakMonitorStartNs;
   private int rebreakCount;
   private String rebreakRate = "0.0";
   private BlockPos lastStartedPos;
   private final Deque<BlockPos> queue = new ArrayDeque<>();
   private final ElapsedTimer instantDelayTimer = new ElapsedTimer();
   private final ElapsedTimer itemSpawnTimer = new ElapsedTimer();
   private final ElapsedTimer breakEffectTimer = new ElapsedTimer();
   /** Blocks removed early on the client, waiting for the server's block update. */
   private final Map<BlockPos, AwaitedBreak> awaitingServer = new HashMap<>();
   private long asyncTick = -1L;
   /** A silent break held back one tick by 1.21 Tick. */
   private MineContext delayedFinish;
   /** Re-send START for the primary next tick after another module's slot change (SwapReset). */
   private boolean restartPrimary;
   public volatile boolean crystalAttackPending;

   /** Tool slot owned by this miner; {@link #serverSlot()} tracks the actual server slot. */
   private int heldSlot = -1;
   /** With Alternative, the inventory slot whose tool is currently swapped into the selected hotbar slot. */
   private int alternativeSlot = -1;
   /** With Normal swap and SwapBack, the hotbar slot to return to once mining is done. */
   private int swapBackSlot = -1;
   private boolean sendingOwnSlot;
   private int idleTicks;
   private final List<PendingBreak> pendingBreaks = new ArrayList<>(3);
   private long lastStartCreditMs;
   private double startCredit = 1.0;
   private long clientTick;
   private long lastMiningActionTick;

   // Server slot tracking
   private volatile Connection slotConnection;
   private volatile int trackedServerSlot = -1;

   // Auto Mine state
   private BlockPos bedrockPos;
   private String phaseRejection;
   private String autoMineStatus;
   private String lastAutoMineStatus;
   private long nextAutoMineReportTick;
   private int autoAirRejected;
   private int autoSelfRejected;
   private int autoRangeRejected;
   private int autoDisabledRejected;
   private int autoUnbreakableRejected;

   // Read by other Argus modules
   private final ExtraBreakTracker extraBreakTracker = new ExtraBreakTracker();
   private final ElapsedTimer crystalDelayTimer = new ElapsedTimer();
   private final SpeedMineRenderQueue renderQueue = new SpeedMineRenderQueue();
   private ExtraBreakTarget extraBreakTarget;

   public SpeedMineModule() {
      super("SpeedMine", "Grim-safe packet miner with queue and double break (from Comet).", ModuleCategory.PLAYER);
      this.blocksSetting.addVisibilityPredicate(ignored -> this.modeSetting.getValue().isFiltering());
      this.autoRebreakSetting.setConfigName("RebreakPage");
      this.swapPageSetting.setConfigName("SwapPage");
      this.autoMineSetting.setConfigName("AutoMinePage");
      this.airMineSetting.setConfigName("AirPage");
      this.renderSetting.setConfigName("RenderPage");
      this.debugRebreakSetting.setChangeCallback(this::resetRebreakMonitor);
      this.debugAutoMineSetting.setChangeCallback(() -> {
         this.lastAutoMineStatus = null;
         this.nextAutoMineReportTick = 0L;
      });
      Runnable releaseOnChange = () -> {
         if (!this.isWorldUnavailable()) {
            this.releaseHeldSlot();
         }
      };
      this.swapModeSetting.setChangeCallback(releaseOnChange);
      this.autoSwapModeSetting.setChangeCallback(releaseOnChange);
      this.alternativeSetting.setChangeCallback(releaseOnChange);
      this.airMineSetting.setChangeCallback(() -> {
         if (!this.airMineSetting.getValue() && !this.isWorldUnavailable()) {
            this.restoreAirGround();
         }
      });
   }

   public static void bindModules(ModuleRegistry moduleRegistry) {
      multiTaskModule = (MultiTaskModule)moduleRegistry.getModule(MultiTaskModule.class);
      autoCrystalModule = (AutoCrystalModule)moduleRegistry.getModule(AutoCrystalModule.class);
      autoExpModule = (AutoExpModule)moduleRegistry.getModule(AutoExpModule.class);
      autoMineModule = (AutoMineModule)moduleRegistry.getModule(AutoMineModule.class);
      anchorAuraModule = (AnchorAuraModule)moduleRegistry.getModule(AnchorAuraModule.class);
      noGlitchBlocksModule = (NoGlitchBlocksModule)moduleRegistry.getModule(NoGlitchBlocksModule.class);
      itemSaverModule = (ItemSaverModule)moduleRegistry.getModule(ItemSaverModule.class);
      logoutSpotsModule = (LogoutSpotsModule)moduleRegistry.getModule(LogoutSpotsModule.class);
   }

   public static void beginPlayerAttack() {
      playerAttackDepth++;
   }

   public static void endPlayerAttack() {
      playerAttackDepth = Math.max(0, playerAttackDepth - 1);
   }

   // ── Lifecycle ─────────────────────────────────────────────────────────────

   @Override
   public void onEnable() {
      this.airClaimLeft = 0;
      this.airClaimTick = -1L;
      this.airClaimConnection = null;
      this.lastStartCreditMs = System.currentTimeMillis();
      this.startCredit = 1.0;
      this.clientTick = 0L;
      this.lastMiningActionTick = 0L;
      this.phaseRejection = null;
      this.autoMineStatus = this.lastAutoMineStatus = null;
      this.nextAutoMineReportTick = 0L;
      swapPending = false;
      this.renderQueue.clear();
      this.resetRebreakMonitor();
   }

   @Override
   public void onDisable() {
      if (!this.isWorldUnavailable()) {
         this.restoreAirGround();
         this.releaseHeldSlot();
         this.restoreSwapBack();
         this.stopBedrock();
      }

      this.clearState();
   }

   private void clearState() {
      this.primary = null;
      this.secondary = null;
      this.lastBrokenPos = null;
      this.lastBreakConfirmed = false;
      this.rebreakPending = false;
      this.bypassConfirmed = false;
      this.packetReplacement = null;
      this.lastStartedPos = null;
      this.extraBreakTarget = null;
      this.delayedFinish = null;
      this.restartPrimary = false;
      this.bedrockPos = null;
      this.pendingBreaks.clear();
      this.queue.clear();
      this.renderQueue.clear();
      this.awaitingServer.clear();
      swapPending = false;
      this.resetRebreakMonitor();
   }

   @EventHandler
   private void onDisconnect(MinecraftDisconnectEvent event) {
      this.heldSlot = -1;
      this.alternativeSlot = -1;
      this.swapBackSlot = -1;
      this.airClaimConnection = null;
      this.airClaimLeft = 0;
      this.slotConnection = null;
      this.trackedServerSlot = -1;
      this.clearState();
   }

   // ── Air miner ─────────────────────────────────────────────────────────────

   /** True while airborne mining should run at ground speed (also read by PlayerMixin). */
   public boolean liftsAirMiningPenalty() {
      return this.isToggled()
         && this.airMineSetting.getValue()
         && mc.player != null
         && mc.getConnection() != null
         && SpeedMinePolicy.canClaimAir(
            mc.player.onGround(),
            mc.player.isFallFlying(),
            this.airWhileGlidingSetting.getValue(),
            mc.player.isCreative(),
            mc.player.isSpectator(),
            mc.player.getDeltaMovement().horizontalDistance(),
            this.airMaxSpeedSetting.getValue()
         );
   }

   @EventHandler(priority = -1000)
   private void onPacketSend(PacketEvent.Send event) {
      if (mc.player == null || mc.getConnection() == null) {
         return;
      }

      if (event.getPacket() instanceof ServerboundUseItemOnPacket place) {
         this.onOwnPlacement(place);
      }

      if (event.isCancelled() || this.airInjecting) {
         return;
      }

      if (event.getPacket() instanceof ServerboundPlayerActionPacket action
         && (action.getAction() == Action.START_DESTROY_BLOCK || action.getAction() == Action.STOP_DESTROY_BLOCK)
         && this.liftsAirMiningPenalty()) {
         if (this.airClaimTick != this.clientTick) {
            this.sendAirGround(true);
            this.airClaimTick = this.clientTick;
            this.airClaimConnection = mc.getConnection().getConnection();
         }

         this.airClaimLeft = this.airClaimWindowSetting.getValue();
      } else if (event.getPacket() instanceof ServerboundMovePlayerPacket move
         && this.airClaimLeft > 0
         && event.getConnection() == this.airClaimConnection
         && this.liftsAirMiningPenalty()) {
         ((ServerboundMovePlayerPacketAccessor)move).setOnGround(true);
      }
   }

   /**
    * A block you place (by hand or through any module) into the rebreak spot ends rebreaking there. This is
    * checked as the packet goes out, so it doesn't depend on WorldStateManager's 250 ms pending-placement window,
    * which runs out before the server answers on high ping. Packets the rotation manager defers come through
    * twice (cancelled, then replayed); handling both is harmless.
    */
   private void onOwnPlacement(ServerboundUseItemOnPacket place) {
      BlockPos rebreakPos = this.lastBrokenPos;
      if (rebreakPos == null || mc.level == null) {
         return;
      }

      BlockHitResult hit = place.getHitResult();
      BlockPos target = WorldUtils.isReplaceable(hit.getBlockPos()) ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
      if (!target.equals(rebreakPos)) {
         return;
      }

      // Only block items count: a crystal placed on the block below must not end the rebreak.
      ItemStack stack = place.getHand() == InteractionHand.OFF_HAND ? mc.player.getOffhandItem() : this.serverHandStack();
      if (stack.getItem() instanceof BlockItem) {
         this.forgetRebreakPos();
      }
   }

   /** The main-hand stack as the server sees it, following silent slot swaps. */
   private ItemStack serverHandStack() {
      int slot = this.serverSlot();
      return slot >= 0 && slot < 9 ? mc.player.getInventory().getItem(slot) : mc.player.getMainHandItem();
   }

   private void sendAirGround(boolean onGround) {
      this.airInjecting = true;
      try {
         PacketSender.send(
            new ServerboundMovePlayerPacket.PosRot(
               mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot(), onGround, mc.player.horizontalCollision
            )
         );
      } finally {
         this.airInjecting = false;
      }
   }

   private void restoreAirGround() {
      Connection claimed = this.airClaimConnection;
      this.airClaimConnection = null;
      this.airClaimLeft = 0;
      this.airClaimTick = -1L;
      if (claimed != null && mc.player != null && mc.getConnection() != null && mc.getConnection().getConnection() == claimed) {
         this.sendAirGround(mc.player.onGround());
      }
   }

   // ── Server slot tracking ──────────────────────────────────────────────────

   @EventHandler
   private void onPacketPostSend(PacketEvent.PostSend event) {
      if (event.getPacket() instanceof ServerboundSetCarriedItemPacket packet) {
         this.slotConnection = event.getConnection() != null ? event.getConnection() : this.currentConnection();
         this.trackedServerSlot = packet.getSlot();
      }
   }

   @EventHandler
   private void onPacketReceive(PacketEvent.Receive event) {
      if (event.getPacket() instanceof ClientboundRespawnPacket) {
         mc.execute(() -> {
            this.heldSlot = -1;
            this.alternativeSlot = -1;
            this.clearState();
         });
      }

      if (!event.isCancelled() && event.getPacket() instanceof ClientboundSetHeldSlotPacket packet) {
         Connection receivedFrom = event.getConnection();
         mc.execute(() -> {
            if (receivedFrom == null || this.currentConnection() == receivedFrom) {
               this.slotConnection = this.currentConnection();
               this.trackedServerSlot = packet.slot();
            }
         });
      }
   }

   private Connection currentConnection() {
      return mc.getConnection() == null ? null : mc.getConnection().getConnection();
   }

   private int serverSlot() {
      if (mc.player == null || mc.getConnection() == null) {
         return -1;
      }

      return this.slotConnection == this.currentConnection() && this.trackedServerSlot >= 0
         ? this.trackedServerSlot
         : mc.player.getInventory().getSelectedSlot();
   }

   private void setSlotForced(int slot) {
      if (slot >= 0 && slot < 9 && mc.getConnection() != null) {
         this.sendingOwnSlot = true;
         try {
            PacketSender.sendSelectedSlot(slot);
         } finally {
            this.sendingOwnSlot = false;
         }
      }
   }

   // ── AutoCrystal / AutoExp coordination ────────────────────────────────────

   /** SwapReset: another module's silent slot change restarts the primary block with Alternative. */
   @EventHandler(priority = 100)
   private void onSelectedSlotChange(SelectedSlotChangeEvent event) {
      if (this.sendingOwnSlot || !this.isAlternativeActive() || !this.swapResetSetting.getValue() || this.primary == null) {
         return;
      }

      this.primary.elapsedTicks = 1.0;
      this.primary.lastProgressMs = System.currentTimeMillis();
      this.renderQueue.setProgressTwice(this.primary.pos, 0.0F);
      this.restartPrimary = true;
   }

   @EventHandler
   private void onAddEntity(ClientLevelAddEntityEvent event) {
      BlockPos target = this.lastBrokenPos;
      if (target != null && this.isInstantRebreak() && event.getEntity() instanceof ItemEntity && new AABB(target).contains(event.getEntity().position())) {
         if (this.asyncSetting.getValue()) {
            this.crystalDelayTimer.reset();
         }

         if (this.alternativeSetting.getValue()) {
            this.itemSpawnTimer.clear();
         }
      }
   }

   // ── Server block updates ──────────────────────────────────────────────────

   /** Called on the client thread before a server block update is applied (ClientLevelMixin). */
   public void onServerBlockUpdate(BlockPos pos, BlockState state) {
      if (!this.isToggled() || mc.player == null || mc.level == null) {
         return;
      }

      this.awaitingServer.remove(pos);
      this.confirmBreak(pos, state);
      if (pos.equals(this.lastBrokenPos)) {
         this.packetReplacement = state.isAir() ? null : state;
         if (this.rebreakTriggerSetting.getValue() == RebreakTrigger.ON_PACKET && this.packetReplacement != null) {
            this.tryRebreak(pos, this.packetReplacement, true);
         }
      }
   }

   private void confirmBreak(BlockPos pos, BlockState state) {
      if (!state.isAir()) {
         return;
      }

      if (pos.equals(this.lastStartedPos) && (pos.equals(this.lastBrokenPos) || this.primary != null && pos.equals(this.primary.pos))) {
         this.lastBrokenPos = pos.immutable();
         this.lastBreakConfirmed = true;
         if (this.rebreakPending) {
            this.bypassConfirmed = true;
         }

         this.rebreakPending = false;
      }

      this.pendingBreaks.removeIf(pending -> pending.pos().equals(pos));
      if (this.primary != null && pos.equals(this.primary.pos)) {
         this.primary = null;
      }

      if (this.secondary != null && pos.equals(this.secondary.pos)) {
         this.secondary = null;
      }
   }

   // ── Input ─────────────────────────────────────────────────────────────────

   @EventHandler(priority = -100)
   private void onStartDestroyBlock(MultiPlayerGameModeStartDestroyBlockEvent event) {
      if (event.isCancelled() || mc.level == null || mc.player == null || mc.player.isCreative() || mc.player.isSpectator()) {
         return;
      }

      // Auto Mine Only hands manual clicks straight back to vanilla.
      if (!automatedMiningStart && this.autoMineSetting.getValue() && this.autoMineOnlySetting.getValue()) {
         return;
      }

      BlockPos pos = event.getPos();
      BlockState state = mc.level.getBlockState(pos);
      if (!canBreak(pos, state) || !this.isStateAllowed(state) || this.outOfRange(pos)) {
         return;
      }

      event.cancel();
      if (!automatedMiningStart) {
         BlockPos extended = this.sneakExtend(pos);
         if (extended != null) {
            pos = extended;
            state = mc.level.getBlockState(pos);
         }
      }

      if (!this.isMining(pos)) {
         if (automatedMiningStart) {
            this.requestAutomatedBreak(pos, state);
         } else {
            autoMineModule.setMiningActive(true);
            this.handleBlockClick(pos, state, true);
         }
      }
   }

   /** SneakExtend: the block one step beyond {@code pos} along the view toward it, while no second block is running. */
   private BlockPos sneakExtend(BlockPos pos) {
      if (!this.doubleBreakSetting.getValue()
         || !this.sneakExtendSetting.getValue()
         || this.secondary != null
         || !KeyUtils.isPhysicallyDown(mc.options.keyShift)) {
         return null;
      }

      float[] rotations = RotationManager.getRotationsToBlock(pos);
      BlockPos extended = BlockPos.containing(pos.getCenter().add(Vec3.directionFromRotation(rotations[1], rotations[0])));
      return !extended.equals(pos) && this.isBlockAllowed(extended) && !this.outOfRange(extended) ? extended : null;
   }

   /**
    * AutoMine and Nuker retarget instead of queueing: with both break slots taken, the primary is
    * aborted for the new block, the way Argus's old SpeedMine switched targets.
    */
   private void requestAutomatedBreak(BlockPos pos, BlockState state) {
      if (this.primary != null && (this.secondary != null || !this.doubleBreakSetting.getValue()) && !this.hasPendingNormalBreak() && this.canStart()) {
         this.abort(this.primary);
         this.primary = null;
      }

      this.handleBlockClick(pos, state, false);
   }

   /** Asks the miner to break {@code pos}, as Comet's Auto Mine does. */
   public void requestBreak(BlockPos pos) {
      if (mc.level != null && mc.player != null) {
         BlockState state = mc.level.getBlockState(pos);
         if (!state.isAir() && !this.isMining(pos)) {
            this.handleBlockClick(pos, state, true);
         }
      }
   }

   // ── Tick ──────────────────────────────────────────────────────────────────

   @EventHandler
   private void onTickPre(MinecraftTickEvent event) {
      if (mc.level == null || mc.player == null) {
         return;
      }

      this.clientTick++;
      this.renderQueue.snapshotProgress();
      if (this.airClaimLeft > 0 && (--this.airClaimLeft == 0 || !this.liftsAirMiningPenalty())) {
         this.restoreAirGround();
      }

      long now = System.currentTimeMillis();
      this.startCredit = SpeedMineTiming.replenishStartCredit(this.startCredit, now - this.lastStartCreditMs, this.effectiveTps());
      this.lastStartCreditMs = now;
      this.pendingBreaks.removeIf(pending -> now >= pending.deadlineMs());
      if (this.rebreakPending && now >= this.rebreakDeadlineMs) {
         this.rebreakPending = false;
      }

      this.updateRebreakMonitor();
      this.expireAwaitedBreaks(now);
      this.extraBreakTracker.pruneExpired();
      this.forgetOwnPlacement();
      if (this.debugAutoMineSetting.getValue()) {
         this.autoMineStatus = !this.autoMineSetting.getValue()
            ? "Disabled."
            : !this.autoMineHeld() ? "Waiting for AutoMineBind." : "Paused while using an item or eating.";
      }

      if (this.shouldPauseMining()) {
         this.releaseHeldSlot();
         if (this.primary != null) {
            this.primary.lastProgressMs = now;
         }

         if (this.secondary != null) {
            this.secondary.lastProgressMs = now;
         }

         if (this.bedrockWhileEatingSetting.getValue() && this.isEating()) {
            this.tickAutoMine();
         } else {
            this.stopBedrock();
         }

         this.updateExtraBreakTarget();
         this.updateRender();
         return;
      }

      if (this.delayedFinish != null) {
         MineContext delayed = this.delayedFinish;
         this.delayedFinish = null;
         this.finishBreak(delayed, this.isSilent());
         swapPending = this.alternativeSlot != -1;
      }

      if (this.restartPrimary) {
         this.restartPrimary = false;
         if (this.primary != null && canBreak(this.primary.pos, mc.level.getBlockState(this.primary.pos))) {
            this.sendStart(this.primary);
         }
      }

      if (this.isToolSwapGuarded()) {
         // Low health with a totem in hand: give the server slot back and touch nothing.
         this.releaseHeldSlot();
      } else {
         int validationSlot = this.validationToolSlot();
         if (!this.isSilent()) {
            this.releaseHeldSlot();
            if (validationSlot != -1 && this.autoSwapModeSetting.getValue() == SpeedMineAutoSwapChoice.NORMAL) {
               this.selectNormal(validationSlot);
            }
         } else if (validationSlot != -1) {
            this.holdTool(validationSlot, false);
         } else if (this.swapModeSetting.getValue() == SwapMode.SAME_TICK) {
            this.releaseHeldSlot();
         } else if (this.swapModeSetting.getValue() != SwapMode.END_OF_TICK) {
            if (this.primary != null) {
               this.holdTool(this.primary.startSlot, false);
            } else if (this.secondary != null) {
               this.holdTool(this.secondary.startSlot, false);
            } else if (this.heldSlot != -1 && !this.pendingBreaks.isEmpty()) {
               this.holdTool(this.heldSlot, false);
            }
         }
      }

      this.tickAutoMine();
      this.rotateWhileMining();
      this.rebreakReplacement();
      this.pruneCompletedOrInvalid();
      if (this.secondary != null && this.secondary.progress() >= 1.0) {
         this.finishBreak(this.secondary, this.isSilent());
      }

      if (this.primary != null && this.primary.progress() >= 1.0 && this.mayFinish(this.primary.pos)) {
         this.finishBreak(this.primary, this.isSilent());
      }

      this.drainQueue();
      this.updateExtraBreakTarget();
      this.updateRender();
   }

   @EventHandler(priority = -1000)
   private void onTickPost(LocalPlayerTickEvent event) {
      if (mc.player == null || mc.level == null) {
         return;
      }

      if (this.debugAutoMineSetting.getValue()) {
         String report = this.autoMineStatus == null ? null : this.autoMineStatus + (this.phaseRejection == null ? "" : " Phase target skipped: " + this.phaseRejection);
         if (report != null && !report.equals(this.lastAutoMineStatus) && this.clientTick >= this.nextAutoMineReportTick) {
            this.info("Auto Mine: " + report);
            this.lastAutoMineStatus = report;
            this.nextAutoMineReportTick = this.clientTick + 40L;
         }
      }

      // Placers can refill predicted air after the pre-tick handler.
      this.rebreakReplacement();
      boolean mining = this.primary != null || this.secondary != null || !this.queue.isEmpty();
      boolean pending = !this.pendingBreaks.isEmpty();
      if (this.swapBackSlot != -1 && !mining && !pending && this.delayedFinish == null) {
         this.restoreSwapBack();
      }

      if (this.heldSlot == -1 || this.delayedFinish != null) {
         return;
      }

      if (!this.isSilent() || this.shouldPauseMining()) {
         this.releaseHeldSlot();
         return;
      }

      boolean replacement = this.autoRebreakSetting.getValue()
         && this.rebreakModeSetting.getValue() != RebreakMode.OFF
         && this.rebreakModeSetting.getValue().isPrimed(this.lastBreakConfirmed)
         && this.lastBrokenPos != null
         && this.lastBrokenPos.equals(this.lastStartedPos)
         && !this.outOfRange(this.lastBrokenPos)
         && canBreak(this.lastBrokenPos, mc.level.getBlockState(this.lastBrokenPos));
      this.idleTicks = !mining && !pending ? Math.min(IDLE_RELEASE_TICKS, this.idleTicks + 1) : 0;
      // Checked after this tick's placements before releasing the keep-mode hold.
      SwapMode mode = this.validationToolSlot() != -1 ? SwapMode.TOOL_HOLD : this.swapModeSetting.getValue();
      if (mode.shouldRelease(this.clientTick - this.lastMiningActionTick, mining, replacement, pending, this.idleTicks)) {
         this.releaseHeldSlot();
      }
   }

   /** Rotate: keep turning toward the primary block, held back by Limit until it is nearly broken. */
   private void rotateWhileMining() {
      if (!this.rotateSetting.getValue() || this.primary == null || RotationManager.isSilentRotationMode() || !WorldUtils.canMineBlock(this.primary.pos)) {
         return;
      }

      int selected = mc.player.getInventory().getSelectedSlot();
      boolean rotate = SpeedMinePolicy.shouldRotate(
         this.limitSetting.getValue(),
         (float)this.primary.progress(),
         1.0F,
         this.autoSwapModeSetting.getValue() == SpeedMineAutoSwapChoice.NONE,
         this.holdingBestSetting.getValue(),
         this.bestToolSlot(this.primary.state) == selected
      );
      if (rotate) {
         Vec3 target = WorldUtils.findVisiblePoint(this.primary.pos, BlockSampleMode.X8);
         if (target == null) {
            target = this.primary.pos.getCenter();
         }

         ClientContext.rotationManager.requestRotation(RotationManager.getRotationsToPosition(target), ROTATION_PRIORITY, true);
      }
   }

   // ── Rendering ─────────────────────────────────────────────────────────────

   /** Feeds this tick's progress into the render queue (in old SpeedMine's render style). */
   private void updateRender() {
      if (this.secondary != null) {
         this.renderQueue.setProgress(this.secondary.pos, (float)this.secondary.progress());
      }

      if (this.primary != null) {
         this.renderQueue.setProgress(this.primary.pos, (float)this.primary.progress());
      }

      if (this.lastBrokenPos != null && this.isInstantRebreak() && this.primary == null && this.secondary == null) {
         boolean replaced = !mc.level.getBlockState(this.lastBrokenPos).isAir();
         if (replaced || this.airSetting.getValue()) {
            this.renderQueue.setProgress(this.lastBrokenPos, 1.0F);
         }
      }
   }

   @EventHandler
   private void onRenderWorld(WorldRenderEvent.Main event) {
      if (!this.renderSetting.getValue() || mc.level == null || mc.player == null) {
         return;
      }

      this.renderQueue.render(event);
      Color queuedOutline = this.outlineSetting.getValue();
      float lineWidth = this.lineWidthSetting.getValue();
      for (BlockPos pos : this.queue) {
         WorldBoxRenderer.outlineBox(event.getPoseStack(), pos, queuedOutline, lineWidth);
      }

      if (this.bedrockPos != null) {
         Color bedrock = this.bedrockColorSetting.getValue();
         WorldBoxRenderer.fillBox(event.getPoseStack(), this.bedrockPos, new Color(bedrock.getRed(), bedrock.getGreen(), bedrock.getBlue(), bedrock.getAlpha() / 3));
         WorldBoxRenderer.outlineBox(event.getPoseStack(), this.bedrockPos, bedrock, lineWidth);
      }
   }

   // ── Core break logic ──────────────────────────────────────────────────────

   private void rebreakReplacement() {
      if (this.lastBrokenPos != null) {
         boolean packet = this.rebreakTriggerSetting.getValue() == RebreakTrigger.ON_PACKET && this.packetReplacement != null;
         this.tryRebreak(this.lastBrokenPos, packet ? this.packetReplacement : mc.level.getBlockState(this.lastBrokenPos), packet);
      }
   }

   private void tryRebreak(BlockPos pos, BlockState state, boolean packet) {
      RebreakMode mode = this.rebreakModeSetting.getValue();
      boolean strong = mode.isStrong(this.bypassConfirmed);
      boolean asyncAir = state.isAir() && this.canAsyncRebreak(pos);
      if (mc.gameMode == null
         || !this.autoRebreakSetting.getValue()
         || !pos.equals(this.lastBrokenPos)
         || !pos.equals(this.lastStartedPos)
         || !mode.canRebreak(this.lastBreakConfirmed, this.rebreakPending, this.bypassConfirmed)
         || this.primary != null
         || this.secondary != null
         || hasPendingNormalBreak(this.pendingBreaks, strong ? pos : null)
         || this.outOfRange(pos)
         || state.isAir() && !asyncAir
         || !state.isAir() && (!canBreak(pos, state) || !this.isStateAllowed(state))
         || this.shouldPauseMining()) {
         return;
      }

      // Never rebreak a block you placed there yourself (by hand or through another module).
      if (ClientContext.worldStateManager.hasKnownPlacement(pos)) {
         this.forgetRebreakPos();
         return;
      }

      if (!strong && !asyncAir && !this.canStart()) {
         return;
      }

      boolean alternativeAllowed = this.isAlternativeAllowed();
      float instantDelay = SpeedMineTiming.instantDelaySeconds(this.instantDelaySetting.getValue(), alternativeAllowed, RotationManager.is2b2tMode());
      if (!this.instantDelayTimer.hasElapsed(instantDelay, TimeUnit.SECONDS) || !this.mayFinish(pos)) {
         return;
      }

      MineContext ctx = new MineContext(pos, state, false);
      int selected = mc.player.getInventory().getSelectedSlot();
      if (this.holdingBestSetting.getValue() && this.autoSwapModeSetting.getValue() == SpeedMineAutoSwapChoice.NONE && this.bestToolSlot(state) != selected) {
         return;
      }

      if (this.isAlternativeActive() && ctx.startSlot != selected && !this.itemSpawnTimer.hasElapsed(150L)) {
         return;
      }

      if (!this.canUseTool(ctx.startSlot)) {
         return;
      }

      if (asyncAir) {
         // One pre-break per client tick while the block is still air.
         if (this.asyncTick == this.clientTick) {
            return;
         }

         this.asyncTick = this.clientTick;
      }

      ctx.rebreak = true;
      ctx.packetRebreak = packet;
      if (!strong && !asyncAir && this.tpsSyncSetting.getValue()) {
         this.startCredit = Math.max(0.0, this.startCredit - 1.0);
      }

      if (this.rotateSetting.getValue()) {
         this.lookAt(pos);
      }

      this.finishBreak(ctx, this.isSilent());
   }

   /** Async keeps pre-breaking an air rebreak position unless something makes that unsafe. */
   private boolean canAsyncRebreak(BlockPos pos) {
      if (!this.asyncSetting.getValue() || !this.isInstantRebreak()) {
         return false;
      }

      boolean lastWasShulker = this.extraBreakTracker.getLastBlock() instanceof ShulkerBoxBlock;
      boolean containerOpen = RotationManager.is2b2tMode() && mc.screen instanceof AbstractContainerScreen;
      return !lastWasShulker && !containerOpen && !this.isPlayerAboveBlock(pos);
   }

   private void handleBlockClick(BlockPos pos, BlockState state, boolean allowQueue) {
      if (this.isMining(pos)) {
         return;
      }

      RebreakMode mode = this.rebreakModeSetting.getValue();
      if (this.autoRebreakSetting.getValue()
         && mode != RebreakMode.OFF
         && mode.isPrimed(this.lastBreakConfirmed)
         && pos.equals(this.lastBrokenPos)
         && pos.equals(this.lastStartedPos)) {
         boolean packet = this.rebreakTriggerSetting.getValue() == RebreakTrigger.ON_PACKET;
         if (packet && this.packetReplacement != null) {
            this.tryRebreak(pos, this.packetReplacement, true);
         } else {
            this.tryRebreak(pos, state, false);
         }

         return;
      }

      if (this.shouldPauseMining()) {
         return;
      }

      // AutoCrystal's and AutoExp's silent swaps take priority over a new block (SwapReset).
      if (this.isAlternativeActive() && this.swapResetSetting.getValue()) {
         if (this.crystalAttackPending) {
            this.crystalAttackPending = false;
            reset(this);
            return;
         }

         if (autoExpModule.isToggled() && autoExpModule.modeSetting.getValue() == AutoExpModule.ModeChoice.SILENT && !autoExpModule.stopped) {
            reset(this);
            return;
         }
      }

      if (this.hasPendingNormalBreak() || !this.canStart() || !this.canUseTool(this.bestToolSlot(state))) {
         if (allowQueue && this.queueSetting.getValue() && !this.queue.contains(pos)) {
            this.queue.addLast(pos.immutable());
         }

         return;
      }

      boolean canAddSecondary = this.secondary == null && this.doubleBreakSetting.getValue();
      if (this.primary == null) {
         this.primary = new MineContext(pos, state, true);
         this.sendStart(this.primary);
      } else if (canAddSecondary && this.canUseTool(this.primary.startSlot)) {
         this.stopWithTool(this.primary, this.isSilent());
         this.secondary = this.primary;
         this.secondary.isPrimary = false;
         this.primary = new MineContext(pos, state, true);
         this.sendStart(this.primary);
      } else if (allowQueue && this.queueSetting.getValue() && !this.queue.contains(pos)) {
         this.queue.addLast(pos.immutable());
      }
   }

   private void pruneCompletedOrInvalid() {
      if (this.primary != null && this.shouldRemove(this.primary.pos)) {
         this.primary = null;
      }

      if (this.secondary != null && this.shouldRemove(this.secondary.pos)) {
         this.secondary = null;
      }

      this.queue.removeIf(pos -> this.shouldRemove(pos) || !this.isBlockAllowed(pos));
   }

   private boolean shouldRemove(BlockPos pos) {
      return mc.level.getBlockState(pos).isAir() || this.outOfRange(pos);
   }

   private void drainQueue() {
      if (!this.queueSetting.getValue()) {
         this.queue.clear();
         return;
      }

      if (this.hasPendingNormalBreak() || this.queue.isEmpty() || !this.canStart()) {
         return;
      }

      if (this.primary == null || this.doubleBreakSetting.getValue() && this.secondary == null) {
         BlockPos pos = this.queue.pollFirst();
         this.handleBlockClick(pos, mc.level.getBlockState(pos), true);
      }
   }

   // ── Safety checks ─────────────────────────────────────────────────────────

   /** Whether STOP may be sent for {@code pos}: never AnchorAura's anchors, and crystal-aware obsidian. */
   private boolean mayFinish(BlockPos pos) {
      return !anchorAuraModule.isManagedAnchor(pos) && this.canBreakNearCrystal(pos);
   }

   /**
    * Obsidian (or air) with an enemy standing on it and no crystal there yet waits a second between
    * breaks, so AutoCrystal gets the chance to place first. Other blocks, and a block with a crystal on
    * it, break straight away.
    */
   private boolean canBreakNearCrystal(BlockPos pos) {
      BlockState state = mc.level.getBlockState(pos);
      if (!state.is(Blocks.OBSIDIAN) && !state.isAir()) {
         return true;
      }

      AABB bounds = new AABB(pos);
      for (Entity entity : mc.level.entitiesForRendering()) {
         if (entity instanceof EndCrystal && entity.getBoundingBox().inflate(0.0, 0.25, 0.0).intersects(bounds)) {
            return true;
         }
      }

      if (this.isPlayerAboveBlock(pos)) {
         long delayMillis = (long)(1000.0F / ClientContext.serverStateManager.getTickRateScale());
         return this.crystalDelayTimer.hasElapsed(delayMillis);
      }

      return true;
   }

   private boolean isPlayerAboveBlock(BlockPos pos) {
      AABB blockBounds = new AABB(pos);
      List<LivingEntity> players = new ArrayList<>(mc.level.players());
      if (logoutSpotsModule.isToggled()) {
         players.addAll(logoutSpotsModule.getSpotPlayers());
      }

      for (LivingEntity player : players) {
         if (player != mc.player
            && player.getBoundingBox().expandTowards(0.0, 1.0, 0.0).inflate(1.0, 0.0, 1.0).intersects(blockBounds)
            && pos.getY() - player.getBlockY() > 0) {
            return true;
         }
      }

      return false;
   }

   /** A rebreak position you have placed a block at yourself is forgotten. */
   private void forgetOwnPlacement() {
      if (this.lastBrokenPos != null && ClientContext.worldStateManager.hasKnownPlacement(this.lastBrokenPos)) {
         this.forgetRebreakPos();
      }
   }

   private void forgetRebreakPos() {
      if (this.lastBrokenPos != null) {
         this.renderQueue.remove(this.lastBrokenPos);
      }

      this.lastBrokenPos = null;
      this.packetReplacement = null;
      this.lastBreakConfirmed = false;
      this.rebreakPending = false;
   }

   /** At low health with a totem in the main hand, the totem is never swapped away for a tool. */
   private boolean isToolSwapGuarded() {
      return !SpeedMinePolicy.canSwapTools(EntityUtils.getPlayerHealthWithAbsorption(), mc.player.getMainHandItem().is(Items.TOTEM_OF_UNDYING));
   }

   /** Whether mining with {@code slot} is allowed right now (it needs no swap, or the totem guard allows one). */
   private boolean canUseTool(int slot) {
      return slot < 0
         || this.autoSwapModeSetting.getValue() == SpeedMineAutoSwapChoice.NONE
         || slot == mc.player.getInventory().getSelectedSlot()
         || !this.isToolSwapGuarded();
   }

   private boolean noGlitchBreaks() {
      return noGlitchBlocksModule.isToggled() && noGlitchBlocksModule.breakSetting.getValue();
   }

   // ── Packets ───────────────────────────────────────────────────────────────

   private void sendStart(MineContext ctx) {
      this.rebreakPending = false;
      this.bypassConfirmed = false;
      this.packetReplacement = null;
      this.lastBreakConfirmed = false;
      this.lastStartedPos = ctx.pos;
      if (this.tpsSyncSetting.getValue()) {
         this.startCredit = Math.max(0.0, this.startCredit - 1.0);
      }

      if (this.rotateSetting.getValue()) {
         this.lookAt(ctx.pos);
      }

      this.withMiningTool(ctx, this.isSilent(), () -> {
         if (this.grimBypassSetting.getValue()) {
            this.sendSequencedAction(Action.STOP_DESTROY_BLOCK, ctx.pos);
         }

         this.sendSequencedAction(Action.START_DESTROY_BLOCK, ctx.pos);
         if (this.swingSetting.getValue()) {
            ClientContext.swingManager.swing(InteractionHand.MAIN_HAND, SwingAction.MINING);
         }

         if (ctx.instaBreak) {
            this.finishBreak(ctx, this.isSilent());
         }
      });
   }

   /** STOP must use the same tool as START. */
   private void stopWithTool(MineContext ctx, boolean silent) {
      if (mc.level != null && mc.player != null) {
         this.withMiningTool(ctx, silent, () -> this.sendSequencedAction(Action.STOP_DESTROY_BLOCK, ctx.pos));
      }
   }

   private void abort(MineContext ctx) {
      if (ctx != null && mc.getConnection() != null) {
         PacketSender.send(new ServerboundPlayerActionPacket(Action.ABORT_DESTROY_BLOCK, ctx.pos, Direction.DOWN, 0));
      }
   }

   private void finishBreak(MineContext ctx, boolean silent) {
      if (mc.level == null || mc.player == null || mc.gameMode == null || !this.canUseTool(ctx.startSlot)) {
         return;
      }

      // 1.21 Tick: hold the tool for a tick before a break that leans on its enchantments.
      if (silent && !ctx.tickDelayed && this.needsExtraTick(ctx)) {
         ctx.tickDelayed = true;
         this.holdTool(ctx.startSlot, true);
         this.delayedFinish = ctx;
         this.lastMiningActionTick = this.clientTick;
         swapPending = true;
         return;
      }

      boolean air = ctx.state.isAir();
      boolean sneakPredict = this.sneakExtendSetting.getValue() && mc.player.isShiftKeyDown();
      boolean clientRemove = (this.clientPredictionSetting.getValue() || sneakPredict) && !this.noGlitchBreaks() && !air;
      if (!clientRemove && !air && this.breakEffectTimer.hasElapsedAndReset(200L)) {
         this.playBreakEffects(ctx.pos);
      }

      this.withMiningTool(ctx, silent, () -> {
         if (this.swingSetting.getValue()) {
            ClientContext.swingManager.swingPacket(InteractionHand.MAIN_HAND, SwingAction.MINING);
         }

         // Without movement sync, a position packet goes ahead of a normal break's STOP.
         if (!ctx.rebreak && !RotationManager.isMovementSyncEnabled()) {
            PacketSender.sendPosition(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.onGround());
         }

         this.sendPredicted(sequence -> {
         // The prediction shares the STOP sequence, so a rejected break restores the server state.
         if (clientRemove && ctx.packetRebreak) {
            BlockStatePredictionHandler prediction = predictionHandler();
            // The replacement may still be air locally; keep the packet state for rollback.
            prediction.retainKnownServerState(ctx.pos, ctx.state, mc.player);
            prediction.updateKnownServerState(ctx.pos, ctx.state);
         }

         if (clientRemove) {
            mc.level.setBlock(ctx.pos, Blocks.AIR.defaultBlockState(), 3);
            // The server acks STOP a tick before its air update arrives; without this the ack would put the old
            // block back for a tick, and AutoRebreak would see it as a replacement and break it again.
            predictionHandler().updateKnownServerState(ctx.pos, Blocks.AIR.defaultBlockState());
            this.awaitingServer.put(ctx.pos, new AwaitedBreak(ctx.state, System.currentTimeMillis() + this.awaitServerMillis(ctx)));
         }

         return new ServerboundPlayerActionPacket(Action.STOP_DESTROY_BLOCK, ctx.pos, Direction.DOWN, sequence);
         });
      });
      if (!air) {
         // CrystalPlacementValidator, AutoCrystal and FeetPlace treat this block as gone until the server answers.
         ClientContext.worldStateManager.markUnconfirmedBreak(ctx.pos);
      }

      if (ctx == this.secondary) {
         ClientContext.worldStateManager.markExtraBreak(ctx.pos);
         autoMineModule.miningTracker.rememberPosition(ctx.pos);
      }
      if (ctx.requiresValidation() || silent && this.swapModeSetting.getValue() == SwapMode.TOOL_HOLD) {
         double ticksPerMs = SpeedMineTiming.serverTicks(1L, this.serverTps()) * ctx.calcDelta();
         long deadline = System.currentTimeMillis() + (long)Math.clamp(ticksPerMs > 0.0 ? 1.0 / ticksPerMs + 1000.0 : 30000.0, 2000.0, 30000.0);
         // At most two normal validations and the latest instant/rebreak hold.
         this.pendingBreaks.removeIf(pending -> !pending.normal() || pending.pos().equals(ctx.pos));
         this.pendingBreaks.add(new PendingBreak(ctx.pos, ctx.startSlot, ctx.requiresValidation(), !ctx.isPrimary, deadline));
      }

      if (clientRemove) {
         mc.level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, ctx.pos, Block.getId(ctx.state));
      }

      if (!air) {
         this.crystalDelayTimer.reset();
      }

      this.instantDelayTimer.reset();
      this.itemSpawnTimer.reset();
      this.extraBreakTracker.trackBreak(ctx.pos);
      if (!ctx.pos.equals(this.lastBrokenPos)) {
         this.lastBreakConfirmed = false;
      }

      this.lastBrokenPos = ctx.pos;
      if (ctx.rebreak && !air) {
         this.rebreakPending = true;
         this.rebreakDeadlineMs = System.currentTimeMillis() + 2000L;
         this.packetReplacement = null;
         if (this.debugRebreakSetting.getValue()) {
            this.rebreakCount++;
         }
      }

      ctx.active = false;
      if (ctx == this.primary) {
         this.primary = null;
      } else if (ctx == this.secondary) {
         this.secondary = null;
      }

      if (silent && this.hasPendingNormalBreak()) {
         this.holdTool(this.validationToolSlot(), false);
      }
   }

   /**
    * How long to keep a block removed early before giving up on the server. A STOP the server judges early isn't
    * refused: it finishes the block itself once it reaches full progress, so the wait covers the mining it still
    * has left (half a minute at most) on top of {@link #AWAIT_SERVER_MILLIS}.
    */
   private long awaitServerMillis(MineContext ctx) {
      float delta = ctx.calcDelta();
      double remainingTicks = delta > 0.0F && delta < Float.MAX_VALUE ? Math.max(0.0, (1.0 - delta * ctx.elapsedTicks) / delta) : 0.0;
      return AWAIT_SERVER_MILLIS + (long)Math.min(30000.0, remainingTicks * 1000.0 / this.serverTps());
   }

   /** A predicted break the server never confirmed puts the block back, so a rejected break leaves no ghost hole. */
   private void expireAwaitedBreaks(long now) {
      Iterator<Map.Entry<BlockPos, AwaitedBreak>> entries = this.awaitingServer.entrySet().iterator();
      while (entries.hasNext()) {
         Map.Entry<BlockPos, AwaitedBreak> entry = entries.next();
         if (now >= entry.getValue().deadlineMs()) {
            entries.remove();
            BlockPos pos = entry.getKey();
            if (mc.level.getBlockState(pos).isAir()) {
               predictionHandler().updateKnownServerState(pos, entry.getValue().state());
               mc.level.setBlock(pos, entry.getValue().state(), 3);
            }
         }
      }
   }

   /** The break sound and particles, for when the block isn't removed early on the client. */
   private void playBreakEffects(BlockPos pos) {
      if (!mc.level.getFluidState(pos).isEmpty()) {
         return;
      }

      BlockState state = mc.level.getBlockState(pos);
      if (!state.isAir()) {
         SoundType sound = state.getSoundType();
         mc.level.playLocalSound(pos, sound.getBreakSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F, false);
      }

      mc.level.addDestroyBlockEffect(pos, state);
   }

   /** 1.21 Tick applies to silent breaks with a tool that isn't held and whose unenchanted progress is low. */
   private boolean needsExtraTick(MineContext ctx) {
      return this.value121Setting.getValue() == SpeedMineValue121Choice.TICK
         && !ctx.state.isAir()
         && ctx.startSlot >= 0
         && ctx.startSlot != mc.player.getInventory().getSelectedSlot()
         && ctx.unenchantedDelta() * ctx.elapsedTicks < TICK_121_PROGRESS;
   }

   private void sendSequencedAction(Action action, BlockPos pos) {
      if (mc.gameMode != null && mc.level != null) {
         this.sendPredicted(sequence -> new ServerboundPlayerActionPacket(action, pos, Direction.DOWN, sequence));
      }
   }

   /** Vanilla's {@code MultiPlayerGameMode.startPrediction}: the packet carries the prediction's sequence. */
   private void sendPredicted(IntFunction<Packet<?>> action) {
      try (BlockStatePredictionHandler prediction = predictionHandler().startPredicting()) {
         PacketSender.send(action.apply(prediction.currentSequence()));
      }
   }

   private static BlockStatePredictionHandler predictionHandler() {
      return ((ClientLevelAccessor)mc.level).getPendingUpdateManager();
   }

   // ── Tool swapping ─────────────────────────────────────────────────────────

   private boolean isSilent() {
      return this.autoSwapModeSetting.getValue() == SpeedMineAutoSwapChoice.SILENT;
   }

   /** Alternative is in use: tools come from the whole inventory and are swapped into the hand by inventory clicks. */
   private boolean isAlternativeActive() {
      return this.isSilent() && this.isAlternativeAllowed();
   }

   private boolean isAlternativeAllowed() {
      boolean is2b2t = RotationManager.is2b2tMode();
      return SpeedMinePolicy.isAlternativeAllowed(
         is2b2t, is2b2t && multiTaskModule.isItemUseRestricted(), is2b2t && multiTaskModule.isItemUseActiveOrRecent(150L), this.alternativeSetting.getValue()
      );
   }

   private void withMiningTool(MineContext ctx, boolean silent, Runnable action) {
      SpeedMineAutoSwapChoice swap = this.autoSwapModeSetting.getValue();
      if (swap == SpeedMineAutoSwapChoice.NONE) {
         action.run();
         this.lastMiningActionTick = this.clientTick;
         return;
      }

      SwapMode mode = this.swapModeSetting.getValue().forBreak(ctx.instaBreak, ctx.rebreak);
      if (this.validationToolSlot() != -1) {
         mode = SwapMode.TOOL_HOLD;
      }

      if (silent && this.heldSlot == ctx.startSlot && this.isHolding(ctx.startSlot)) {
         action.run();
      } else if (silent && mode == SwapMode.SAME_TICK) {
         this.releaseHeldSlot();
         this.withSilentTool(ctx.startSlot, action);
      } else {
         if (silent) {
            this.holdTool(ctx.startSlot, true);
         } else {
            this.selectNormal(ctx.startSlot);
         }

         action.run();
      }

      int validationSlot = this.validationToolSlot();
      if (validationSlot != -1) {
         if (silent) {
            this.holdTool(validationSlot, false);
         } else {
            this.selectNormal(validationSlot);
         }
      }

      this.lastMiningActionTick = this.clientTick;
   }

   /** Runs {@code action} with {@code slot}'s tool in hand, then puts things back. */
   private void withSilentTool(int slot, Runnable action) {
      if (mc.player == null || slot < 0) {
         action.run();
         return;
      }

      int selected = mc.player.getInventory().getSelectedSlot();
      if (this.isAlternativeActive()) {
         boolean swap = slot != selected;
         if (swap) {
            InventoryUtils.swapIntoSelectedSlot(slot);
         }

         try {
            action.run();
         } finally {
            if (swap) {
               InventoryUtils.swapIntoSelectedSlot(slot);
            }

            this.clearPlacementGate();
         }

         return;
      }

      int previous = this.serverSlot();
      boolean swap = slot < 9 && slot != previous;
      if (swap) {
         this.setSlotForced(slot);
      }

      try {
         action.run();
      } finally {
         if (swap) {
            this.setSlotForced(previous);
         }

         this.clearPlacementGate();
      }
   }

   /** Whether the server currently has {@code slot}'s tool in the main hand. */
   private boolean isHolding(int slot) {
      if (this.isAlternativeActive()) {
         return slot == mc.player.getInventory().getSelectedSlot() ? this.alternativeSlot == -1 : this.alternativeSlot == slot;
      }

      return this.serverSlot() == slot;
   }

   /** Normal swap: select the slot for real, remembering where to go back to with SwapBack. */
   private void selectNormal(int slot) {
      if (slot < 0 || slot >= 9 || mc.player == null) {
         return;
      }

      int selected = mc.player.getInventory().getSelectedSlot();
      if (selected != slot) {
         if (this.swapBackSetting.getValue() && this.swapBackSlot == -1) {
            this.swapBackSlot = selected;
         }

         mc.player.getInventory().setSelectedSlot(slot);
      }

      if (this.serverSlot() != slot) {
         this.setSlotForced(slot);
      }
   }

   private void restoreSwapBack() {
      int slot = this.swapBackSlot;
      this.swapBackSlot = -1;
      if (slot >= 0 && mc.player != null && this.autoSwapModeSetting.getValue() == SpeedMineAutoSwapChoice.NORMAL) {
         InventoryUtils.selectHotbarSlotForPlacement(slot);
      }
   }

   /** A delayed secondary completes in a server tick, using the tool held then. */
   private int validationToolSlot() {
      return validationToolSlot(
         this.secondary != null && this.secondary.requiresValidation() ? this.secondary.startSlot : -1,
         this.pendingBreaks,
         this.primary != null && this.primary.requiresValidation() ? this.primary.startSlot : -1
      );
   }

   static int validationToolSlot(int secondarySlot, List<PendingBreak> pendingBreaks, int primarySlot) {
      for (PendingBreak pending : pendingBreaks) {
         if (pending.normal() && pending.secondary()) {
            return pending.toolSlot();
         }
      }

      if (secondarySlot != -1) {
         return secondarySlot;
      }

      for (PendingBreak pending : pendingBreaks) {
         if (pending.normal()) {
            return pending.toolSlot();
         }
      }

      return primarySlot;
   }

   private boolean hasPendingNormalBreak() {
      return hasPendingNormalBreak(this.pendingBreaks, null);
   }

   static boolean hasPendingNormalBreak(List<PendingBreak> pendingBreaks, BlockPos completedTarget) {
      return pendingBreaks.stream().anyMatch(pending -> pending.normal() && !pending.pos().equals(completedTarget));
   }

   /** Mining packets force selection; tick refreshes reuse the tracked slot. */
   private void holdTool(int slot, boolean force) {
      if (slot < 0 || mc.player == null || this.isToolSwapGuarded() && slot != mc.player.getInventory().getSelectedSlot()) {
         return;
      }

      if (this.isAlternativeActive()) {
         if (this.alternativeSlot != slot) {
            this.undoAlternative();
            if (slot != mc.player.getInventory().getSelectedSlot()) {
               InventoryUtils.swapIntoSelectedSlot(slot);
               this.alternativeSlot = slot;
               swapPending = true;
            }
         }

         this.heldSlot = slot;
         return;
      }

      if (slot >= 9) {
         return;
      }

      SwapMode mode = this.swapModeSetting.getValue();
      boolean retained = (mode == SwapMode.KEEP || mode == SwapMode.END_OF_TICK) && this.heldSlot == slot;
      if (force && !retained || this.serverSlot() != slot) {
         this.setSlotForced(slot);
      }

      this.heldSlot = slot;
   }

   private void releaseHeldSlot() {
      this.undoAlternative();
      if (mc.player != null && SpeedMinePolicy.shouldRestoreSlot(this.heldSlot, this.serverSlot())) {
         this.setSlotForced(mc.player.getInventory().getSelectedSlot());
      }

      if (this.heldSlot != -1) {
         this.clearPlacementGate();
      }

      this.heldSlot = -1;
      this.idleTicks = 0;
      if (this.delayedFinish == null) {
         swapPending = false;
      }
   }

   private void undoAlternative() {
      if (this.alternativeSlot != -1 && mc.player != null) {
         InventoryUtils.swapIntoSelectedSlot(this.alternativeSlot);
      }

      this.alternativeSlot = -1;
   }

   /** Lets AutoCrystal place again after SpeedMine hands the slot back. */
   private void clearPlacementGate() {
      if (autoCrystalModule != null) {
         autoCrystalModule.placementGate.set(false);
      }
   }

   private int bestToolSlot(BlockState state) {
      if (mc.player == null) {
         return -1;
      }

      int selected = mc.player.getInventory().getSelectedSlot();
      int best = -1;
      float bestSpeed = -1.0F;
      int slots = this.isAlternativeActive() ? 36 : 9;
      for (int slot = 0; slot < slots; slot++) {
         ItemStack stack = mc.player.getInventory().getItem(slot);
         if (!itemSaverModule.isToggled() || ItemSaverModule.isSafeToUse(stack)) {
            float speed = stack.getDestroySpeed(state);
            if (speed > 1.0F) {
               speed += efficiencyBonus(stack);
            }

            speed /= state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state) ? 100.0F : 30.0F;
            if (speed > bestSpeed || speed == bestSpeed && slot == selected) {
               bestSpeed = speed;
               best = slot;
            }
         }
      }

      return best;
   }

   /** The slot a new block is mined with: the held one with AutoSwap None, otherwise the best tool. */
   private int miningToolSlot(BlockState state) {
      if (mc.player == null) {
         return -1;
      }

      return this.autoSwapModeSetting.getValue() == SpeedMineAutoSwapChoice.NONE ? mc.player.getInventory().getSelectedSlot() : this.bestToolSlot(state);
   }

   /** The stack a tool slot holds right now, following an Alternative swap into the hand. */
   private ItemStack toolStack(int slot) {
      int selected = mc.player.getInventory().getSelectedSlot();
      int physical = slot < 0 ? selected : slot;
      if (this.alternativeSlot != -1) {
         if (physical == this.alternativeSlot) {
            physical = selected;
         } else if (physical == selected) {
            physical = this.alternativeSlot;
         }
      }

      return mc.player.getInventory().getItem(physical);
   }

   private static float efficiencyBonus(ItemStack stack) {
      for (var entry : stack.getEnchantments().entrySet()) {
         if (entry.getKey().is(Enchantments.EFFICIENCY)) {
            int level = entry.getIntValue();
            return level > 0 ? level * level + 1 : 0.0F;
         }
      }

      return 0.0F;
   }

   // ── Auto Mine (Comet's, from BlackOut) ────────────────────────────────────

   /**
    * Picks blocks to break around nearby enemies, in BlackOut AutoMine's priority order, and hands them to
    * the packet miner. Bedrock can't be packet-mined, so it goes down vanilla's progress + swing path.
    */
   private void tickAutoMine() {
      if (!this.autoMineSetting.getValue() || !this.autoMineHeld()) {
         this.stopBedrock();
         this.phaseRejection = null;
         if (this.debugAutoMineSetting.getValue()) {
            this.autoMineStatus = !this.autoMineSetting.getValue() ? "Disabled." : "Waiting for AutoMineBind.";
         }

         return;
      }

      BlockPos previousBedrock = this.bedrockPos;
      this.bedrockPos = null;
      int budget = this.autoDoubleMineSetting.getValue() && this.doubleBreakSetting.getValue() ? 2 : 1;
      List<BlockPos> targets = this.findAutoTargets();
      BlockPos targetBedrock = selectBedrockTarget(previousBedrock, targets, pos -> mc.level.getBlockState(pos).is(Blocks.BEDROCK));
      if (targetBedrock != null) {
         this.mineBedrock(targetBedrock);
      } else if (previousBedrock != null) {
         this.abortBedrock(previousBedrock);
      }

      // Auto Mine outranks the queue: drop the backlog instead of mining it first.
      if (!targets.isEmpty() && !this.shouldPauseMining()) {
         this.queue.clear();
      }

      for (BlockPos target : targets) {
         if (mc.level.getBlockState(target).is(Blocks.BEDROCK)) {
            continue;
         }

         if (budget-- <= 0) {
            break;
         }

         // Both break slots busy: skip rather than queue.
         if (this.primary != null && (this.secondary != null || !this.doubleBreakSetting.getValue())) {
            continue;
         }

         this.requestBreak(target);
      }

      if (this.debugAutoMineSetting.getValue() && !targets.isEmpty()) {
         if (this.bedrockPos != null) {
            this.autoMineStatus = "Targeting bedrock at " + this.bedrockPos.toShortString();
         } else if (this.primary != null) {
            this.autoMineStatus = "Targeting block at " + this.primary.pos.toShortString();
         } else if (this.secondary != null) {
            this.autoMineStatus = "Targeting block at " + this.secondary.pos.toShortString();
         } else if (this.hasPendingNormalBreak()) {
            this.autoMineStatus = "Waiting for server break confirmation.";
         } else if (!this.canStart()) {
            this.autoMineStatus = "Waiting for TPS mining credit.";
         } else {
            this.autoMineStatus = "Targets selected; waiting for mining slots.";
         }
      }
   }

   /** A bound key is hold-to-mine; an unbound one means always on. */
   private boolean autoMineHeld() {
      return this.autoMineBindSetting.followsDefault() || KeyUtils.isPhysicallyDown(this.autoMineBindSetting.getValue());
   }

   /** Every valid target around nearby enemies, nearest first. */
   private List<BlockPos> findAutoTargets() {
      this.phaseRejection = null;
      List<Player> enemies = new ArrayList<>();
      int friends = 0;
      int distant = 0;
      for (Player player : mc.level.players()) {
         boolean friend = ClientContext.socialRegistry.isFriendEntity(player);
         double distance = player.distanceTo(mc.player);
         if (player != mc.player && !player.isSpectator() && !friend && distance <= this.enemyRangeSetting.getValue()) {
            enemies.add(player);
         } else if (friend) {
            friends++;
         } else if (distance > this.enemyRangeSetting.getValue()) {
            distant++;
         }
      }

      if (enemies.isEmpty()) {
         if (this.debugAutoMineSetting.getValue()) {
            this.autoMineStatus = String.format(Locale.ROOT, "No eligible players (friends=%d, distant=%d).", friends, distant);
         }

         return List.of();
      }

      this.autoAirRejected = this.autoSelfRejected = this.autoRangeRejected = this.autoDisabledRejected = this.autoUnbreakableRejected = 0;
      // Digging an enemy out beats chipping at their surround.
      List<BlockPos> targets = this.antiPhaseSetting.getValue() ? this.collect(enemies, this::phaseBlocks) : List.of();
      if (targets.isEmpty() && this.antiSurroundSetting.getValue()) {
         targets = this.collect(enemies, this::surroundBlocks);
      }

      if (this.debugAutoMineSetting.getValue() && targets.isEmpty()) {
         this.autoMineStatus = !this.antiPhaseSetting.getValue() && !this.antiSurroundSetting.getValue()
            ? "Both AntiPhase and AntiSurround are disabled."
            : String.format(
               Locale.ROOT,
               "No target blocks (air=%d, own=%d, range=%d, disabled=%d, unbreakable=%d).",
               this.autoAirRejected,
               this.autoSelfRejected,
               this.autoRangeRejected,
               this.autoDisabledRejected,
               this.autoUnbreakableRejected
            );
      }

      return targets;
   }

   private List<BlockPos> collect(List<Player> enemies, Function<Player, List<BlockPos>> candidates) {
      List<BlockPos> out = new ArrayList<>();
      for (Player enemy : enemies) {
         for (BlockPos pos : candidates.apply(enemy)) {
            if (this.outOfRange(pos)) {
               this.autoRangeRejected++;
            } else if (!out.contains(pos)) {
               out.add(pos);
            }
         }
      }

      Comparator<BlockPos> byDistance = Comparator.comparingDouble(pos -> mc.player.getEyePosition().distanceToSqr(pos.getCenter()));
      out.sort(this.feetFirstSetting.getValue() ? Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparing(byDistance) : byDistance);
      return out;
   }

   /** Every block the enemy's hitbox overlaps (merely touching a face is not phasing into it). */
   private List<BlockPos> phaseBlocks(Player enemy) {
      List<BlockPos> out = new ArrayList<>();
      AABB box = enemy.getBoundingBox();
      for (BlockPos pos : BlockPos.betweenClosed(
         BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(Math.nextDown(box.maxX), Math.nextDown(box.maxY), Math.nextDown(box.maxZ))
      )) {
         BlockPos candidate = pos.immutable();
         BlockState state = mc.level.getBlockState(candidate);
         if (this.outOfRange(candidate)) {
            if (!state.isAir()) {
               this.phaseRejection = "out of reach at " + candidate.toShortString();
            }

            this.autoRangeRejected++;
         } else if (this.mineable(candidate)) {
            out.add(candidate);
         } else if (!state.isAir()) {
            this.phaseRejection = (this.neverMineOwnSetting.getValue() && this.touchesSelf(candidate)
                  ? "NeverMineOwn protects "
                  : state.is(Blocks.BEDROCK) && !this.mineBedrockSetting.getValue() ? "MineBedrock is off at " : "not eligible at ")
               + candidate.toShortString();
         }
      }

      return out;
   }

   /** Everything boxing the enemy in: the surround ring, the head-level ring and the block above their head. */
   private List<BlockPos> surroundBlocks(Player enemy) {
      BlockPos feet = new BlockPos(enemy.getBlockX(), (int)Math.round(enemy.getY()), enemy.getBlockZ());
      BlockPos head = new BlockPos(enemy.getBlockX(), (int)Math.floor(enemy.getBoundingBox().maxY), enemy.getBlockZ());
      List<BlockPos> candidates = new ArrayList<>();
      for (Direction dir : Direction.Plane.HORIZONTAL) {
         candidates.add(feet.relative(dir));
         candidates.add(head.relative(dir));
      }

      candidates.add(head.above());
      List<BlockPos> out = new ArrayList<>();
      for (BlockPos pos : candidates) {
         if (this.mineable(pos)) {
            out.add(pos);
         }
      }

      return out;
   }

   /** True if the block is inside or directly against our own hitbox; breaking it drops or exposes us. */
   private boolean touchesSelf(BlockPos pos) {
      return mc.player.getBoundingBox().inflate(1.0).intersects(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
   }

   private boolean mineable(BlockPos pos) {
      BlockState state = mc.level.getBlockState(pos);
      if (state.isAir()) {
         this.autoAirRejected++;
         return false;
      }

      if (this.neverMineOwnSetting.getValue() && this.touchesSelf(pos)) {
         this.autoSelfRejected++;
         return false;
      }

      // Bedrock's hardness is -1, so canBreak always rejects it: check it first.
      if (state.is(Blocks.BEDROCK)) {
         if (!this.mineBedrockSetting.getValue()) {
            this.autoDisabledRejected++;
            return false;
         }

         return true;
      }

      if (this.shouldPauseMining() || this.bedrockOnlySetting.getValue() || !this.isStateAllowed(state)) {
         this.autoDisabledRejected++;
         return false;
      }

      if (!canBreak(pos, state)) {
         this.autoUnbreakableRejected++;
         return false;
      }

      return true;
   }

   /** Keeps the current bedrock target while it stays eligible, otherwise takes the first one. */
   static BlockPos selectBedrockTarget(BlockPos previous, List<BlockPos> targets, java.util.function.Predicate<BlockPos> bedrock) {
      if (previous != null && targets.contains(previous) && bedrock.test(previous)) {
         return previous;
      }

      return targets.stream().filter(bedrock).findFirst().orElse(null);
   }

   /**
    * Bedrock is a server-side progress bar driven by holding the dig, not something a START/STOP pair can
    * pop, so it goes through vanilla's continueDestroyBlock, one block at a time. No tool swap: bedrock
    * breaks at the same speed with anything.
    */
   private void mineBedrock(BlockPos pos) {
      if (mc.gameMode == null) {
         return;
      }

      this.bedrockPos = pos;
      if (this.bedrockRotateSetting.getValue()) {
         this.lookAt(pos);
      }

      mc.gameMode.continueDestroyBlock(pos, SpeedMineRange.nearestFace(pos));
      ClientContext.swingManager.swing(InteractionHand.MAIN_HAND, SwingAction.MINING);
   }

   private void stopBedrock() {
      if (this.bedrockPos != null) {
         this.abortBedrock(this.bedrockPos);
      }

      this.bedrockPos = null;
   }

   /** MultiPlayerGameModeInteractionMixin drops vanilla's abort packet while SpeedMine is on, so send it here. */
   private void abortBedrock(BlockPos pos) {
      if (mc.gameMode != null && mc.getConnection() != null) {
         mc.gameMode.stopDestroyBlock();
         PacketSender.send(new ServerboundPlayerActionPacket(Action.ABORT_DESTROY_BLOCK, pos, Direction.DOWN, 0));
      }
   }

   private boolean isEating() {
      return mc.player.isUsingItem() && mc.player.getUseItem().has(DataComponents.FOOD);
   }

   // ── Util ──────────────────────────────────────────────────────────────────

   /** Mining waits while an item is being used, unless MultiTask allows it. */
   private boolean shouldPauseMining() {
      return mc.player.isUsingItem() && (!multiTaskModule.isToggled() || multiTaskModule.shouldPauseModule(this));
   }

   private static boolean canBreak(BlockPos pos, BlockState state) {
      return !state.isAir() && state.getDestroySpeed(mc.level, pos) >= 0.0F && !state.getShape(mc.level, pos).isEmpty();
   }

   /** The Blocks/Mode filter. */
   private boolean isStateAllowed(BlockState state) {
      return ListFilterMode.matches(this.modeSetting.getValue(), state.getBlock(), this.blocksSetting);
   }

   private void info(String message) {
      ChatUtils.printChat(Component.literal(message).withStyle(ChatFormatting.GRAY), ChatUtils.moduleSignature(this));
   }

   private void resetRebreakMonitor() {
      this.rebreakMonitorStartNs = System.nanoTime();
      this.rebreakCount = 0;
      this.rebreakRate = "0.0";
   }

   private void updateRebreakMonitor() {
      if (!this.debugRebreakSetting.getValue()) {
         return;
      }

      long now = System.nanoTime();
      long elapsed = now - this.rebreakMonitorStartNs;
      if (elapsed >= 1_000_000_000L) {
         this.rebreakRate = String.format(Locale.ROOT, "%.1f", this.rebreakCount * 1.0E9 / elapsed);
         this.info("Rebreaks/s: " + this.rebreakRate + " attempts");
         this.rebreakMonitorStartNs = now;
         this.rebreakCount = 0;
      }
   }

   @Override
   public String getInfo() {
      if (this.debugRebreakSetting.getValue()) {
         return this.rebreakRate + " rebreaks/s";
      }

      return this.primary == null ? null : "%.2f".formatted(this.getRenderProgress());
   }

   /** Measured server TPS, or 20 before the first time update has been measured. */
   private float serverTps() {
      float tps = ClientContext.serverStateManager.getTickRate();
      return tps > 0.0F ? tps : 20.0F;
   }

   private float effectiveTps() {
      return this.tpsSyncSetting.getValue() ? this.serverTps() : 20.0F;
   }

   private boolean canStart() {
      return SpeedMineTiming.canStart(this.tpsSyncSetting.getValue(), this.effectiveTps(), this.startCredit);
   }

   private boolean isMining(BlockPos pos) {
      return this.primary != null && this.primary.pos.equals(pos)
         || this.secondary != null && this.secondary.pos.equals(pos)
         || this.pendingBreaks.stream().anyMatch(pending -> pending.normal() && pending.pos().equals(pos))
         || this.queue.contains(pos);
   }

   /** Server-side look at {@code pos}; the camera doesn't move. */
   private void lookAt(BlockPos pos) {
      ClientContext.rotationManager.requestRotation(RotationManager.getRotationsToPosition(SpeedMineRange.nearestPoint(pos)), ROTATION_PRIORITY, true);
   }

   private boolean outOfRange(BlockPos pos) {
      return !SpeedMineRange.isInRange(pos, this.rangeSetting.getValue());
   }

   /** The secondary block, mirrored into the shape the AutoMine/AutoCrystal helpers read. */
   private void updateExtraBreakTarget() {
      if (this.secondary == null) {
         this.extraBreakTarget = null;
         return;
      }

      if (this.extraBreakTarget == null || !this.extraBreakTarget.getPosition().equals(this.secondary.pos)) {
         this.extraBreakTarget = new ExtraBreakTarget(this.secondary.pos);
      }

      this.extraBreakTarget.setProgress((float)this.secondary.progress());
   }

   private boolean isSwapPageExpanded(Object ignored) {
      return this.swapPageSetting.isExpanded();
   }

   private boolean isCustomColorVisible(Object ignored) {
      return this.renderSetting.isExpanded() && this.colorModeSetting.getValue() == SpeedMineColorModeChoice.CUSTOM;
   }

   // ── Accessors read by other Argus modules ─────────────────────────────────

   public boolean isInMiningRange(BlockPos blockPos) {
      return !this.outOfRange(blockPos);
   }

   /** Whether {@code blockPos} can be mined and passes the Blocks/Mode filter. */
   public boolean isBlockAllowed(BlockPos blockPos) {
      return WorldUtils.canMineBlock(blockPos) && this.isStateAllowed(mc.level.getBlockState(blockPos));
   }

   public BlockPos getMiningPos() {
      return this.primary == null ? null : this.primary.pos;
   }

   public BlockPos getMiningOrNearlyBrokenPos() {
      if (this.isToggled()) {
         return this.getMiningPos();
      }

      BlockBreakingState gameMode = (BlockBreakingState)mc.gameMode;
      return mc.gameMode.isDestroying() && gameMode.getBreakingProgress() >= 0.9 ? gameMode.getCurrentBreakingBlock() : null;
   }

   /** The position a replacement block would be instantly rebroken at, or null with rebreaks off. */
   public BlockPos getRebreakPos() {
      return this.isInstantRebreak() ? this.lastBrokenPos : null;
   }

   public boolean isInstantRebreak() {
      return this.autoRebreakSetting.getValue() && this.rebreakModeSetting.getValue() != RebreakMode.OFF;
   }

   /** Raw mining damage on the primary block (it breaks at {@link #damageSetting}). */
   public float getMiningProgress() {
      if (this.primary == null) {
         return 0.0F;
      }

      return Mth.clamp((float)(this.primary.progress() * this.damageSetting.getValue()), 0.0F, 1.0F);
   }

   /** Primary progress as a 0-1 fraction of the break threshold. */
   public float getRenderProgress() {
      return this.primary == null ? 0.0F : (float)this.primary.progress();
   }

   /** Damage the primary block gains per server tick. */
   public double getTickBreakProgress() {
      return this.primary == null ? 0.0 : this.primary.calcDelta() * (this.effectiveTps() / 20.0F);
   }

   public ExtraBreakTarget getExtraBreakTarget() {
      return this.extraBreakTarget;
   }

   public ExtraBreakTracker getExtraBreakTracker() {
      return this.extraBreakTracker;
   }

   public boolean hasCrystalDelayElapsed(long delayMillis) {
      return this.crystalDelayTimer.hasElapsed(delayMillis);
   }

   public float getLineWidth() {
      return this.lineWidthSetting.getValue();
   }

   /** True while SpeedMine has a tool swapped in that other modules must not swap away (Alternative or 1.21 Tick). */
   public static boolean isSwapPending() {
      return swapPending;
   }

   /** Aborts both break slots and empties the queue, keeping the rebreak position. */
   public static void reset(SpeedMineModule owner) {
      if (!owner.isWorldUnavailable()) {
         owner.abort(owner.primary);
         owner.abort(owner.secondary);
         if (owner.primary != null) {
            owner.renderQueue.remove(owner.primary.pos);
         }
      }

      owner.primary = null;
      owner.secondary = null;
      owner.delayedFinish = null;
      owner.restartPrimary = false;
      owner.extraBreakTarget = null;
      owner.pendingBreaks.clear();
      owner.queue.clear();
   }

   /** {@link #reset} and forgets the rebreak position. */
   public static void clearRebreakAndReset(SpeedMineModule owner) {
      owner.lastBrokenPos = null;
      owner.lastStartedPos = null;
      owner.packetReplacement = null;
      owner.lastBreakConfirmed = false;
      owner.rebreakPending = false;
      reset(owner);
   }

   // ── Types ─────────────────────────────────────────────────────────────────

   record PendingBreak(BlockPos pos, int toolSlot, boolean normal, boolean secondary, long deadlineMs) {
   }

   private record AwaitedBreak(BlockState state, long deadlineMs) {
   }

   public enum RebreakTrigger implements NamedEntry {
      /** Rebreak on server updates and client-world replacements. */
      ON_PACKET("OnPacket"),
      /** Check for replacement blocks each client tick. */
      ON_CLIENT_WORLD("OnClientWorld");

      private final String name;

      RebreakTrigger(String name) {
         this.name = name;
      }

      @Override
      public String getName() {
         return this.name;
      }
   }

   public enum RebreakMode implements NamedEntry {
      /** Disable instant rebreaks. */
      OFF("Off"),
      /** Wait for server confirmation between rebreaks. */
      STRICT("Strict"),
      /** Skip confirmation waits and TPS start delays. */
      STRONG("Strong"),
      /** Strict until a rebreak is confirmed, then Strong. */
      BYPASS("Bypass");

      private final String name;

      RebreakMode(String name) {
         this.name = name;
      }

      @Override
      public String getName() {
         return this.name;
      }

      boolean isStrong(boolean confirmedRebreak) {
         return this == STRONG || this == BYPASS && confirmedRebreak;
      }

      boolean isPrimed(boolean confirmedBreak) {
         // A break and replacement in one server tick can omit the air update.
         return confirmedBreak || this == STRONG;
      }

      boolean canRebreak(boolean primed, boolean waiting, boolean confirmedRebreak) {
         return this != OFF && this.isPrimed(primed) && (!waiting || this.isStrong(confirmedRebreak));
      }
   }

   public enum SwapMode implements NamedEntry {
      /** Keep the tool through the next tick and consecutive rebreaks. */
      KEEP("KeepNextTick"),
      /** Restore the previous slot immediately after mining packets. */
      SAME_TICK("SameTick"),
      /** Restore the selected slot at the end of the tick. */
      END_OF_TICK("EndOfTick"),
      /** Hold the tool through server validation and three idle ticks. */
      TOOL_HOLD("ToolHold");

      private final String name;

      SwapMode(String name) {
         this.name = name;
      }

      @Override
      public String getName() {
         return this.name;
      }

      SwapMode forBreak(boolean instant, boolean primedRebreak) {
         return instant || primedRebreak ? this : TOOL_HOLD;
      }

      boolean shouldRelease(long ticksSinceAction, boolean mining, boolean replacement, boolean pending, int idleTicks) {
         return switch (this) {
            case SAME_TICK, END_OF_TICK -> true;
            case KEEP -> !mining && !replacement && ticksSinceAction >= 1L;
            case TOOL_HOLD -> !mining && !pending && idleTicks >= IDLE_RELEASE_TICKS;
         };
      }
   }

   private final class MineContext {
      final BlockPos pos;
      final BlockState state;
      final float hardness;
      boolean isPrimary;
      final boolean instaBreak;
      /** Inventory slot holding the tool this block was started with; -1 if none. */
      final int startSlot;
      boolean active = true;
      boolean rebreak;
      boolean packetRebreak;
      boolean tickDelayed;
      long lastProgressMs;
      double elapsedTicks;

      MineContext(BlockPos pos, BlockState state, boolean isPrimary) {
         this.pos = pos.immutable();
         this.state = state;
         this.hardness = mc.level != null ? state.getDestroySpeed(mc.level, pos) : 0.0F;
         this.isPrimary = isPrimary;
         this.startSlot = SpeedMineModule.this.miningToolSlot(state);
         this.lastProgressMs = System.currentTimeMillis();
         this.elapsedTicks = 1.0;
         this.instaBreak = this.calcDelta() >= 1.0F && this.instantBreakAllowed();
      }

      /** NoGlitchBlocks, and 1.21 with a tool that isn't held, rule out breaking in the START packet. */
      private boolean instantBreakAllowed() {
         if (SpeedMineModule.this.noGlitchBreaks()) {
            return false;
         }

         boolean held = this.startSlot < 0 || this.startSlot == mc.player.getInventory().getSelectedSlot();
         return held || !SpeedMineModule.this.isSilent() || SpeedMineModule.this.value121Setting.getValue() == SpeedMineValue121Choice.OFF;
      }

      boolean requiresValidation() {
         return !this.instaBreak && !this.rebreak;
      }

      /** Progress toward this block's break point: the threshold for the primary, 1.0 for the secondary. */
      double progress() {
         if (mc.player == null || mc.level == null || this.hardness < 0.0F) {
            return 0.0;
         }

         long now = System.currentTimeMillis();
         if (!SpeedMineModule.this.shouldPauseMining()) {
            this.elapsedTicks += SpeedMineTiming.serverTicks(now - this.lastProgressMs, SpeedMineModule.this.effectiveTps());
         }

         this.lastProgressMs = now;
         float perTick = this.calcDelta();
         if (perTick <= 0.0F) {
            return 0.0;
         }

         float target = this.isPrimary ? SpeedMineModule.this.damageSetting.getValue() : 1.0F;
         return Math.min(perTick * this.elapsedTicks / target, 1.0);
      }

      float calcDelta() {
         boolean slow = SpeedMineModule.this.isSilent()
            && SpeedMineModule.this.value121Setting.getValue() == SpeedMineValue121Choice.SLOW
            && this.startSlot >= 0
            && mc.player != null
            && this.startSlot != mc.player.getInventory().getSelectedSlot();
         return this.delta(!slow);
      }

      float unenchantedDelta() {
         return this.delta(false);
      }

      /** Vanilla's per-tick destroy progress with this block's tool; {@code enchanted} counts Efficiency. */
      private float delta(boolean enchanted) {
         if (mc.player == null || mc.level == null) {
            return 0.0F;
         }

         if (this.hardness <= 0.0F) {
            return this.hardness == 0.0F ? Float.MAX_VALUE : 0.0F;
         }

         // With AutoSwap None nothing is swapped in, and the server times the break with whatever is held
         // when STOP arrives, so follow the server-side hand instead of the slot the block was started with.
         ItemStack tool = SpeedMineModule.this.autoSwapModeSetting.getValue() == SpeedMineAutoSwapChoice.NONE
            ? SpeedMineModule.this.serverHandStack()
            : SpeedMineModule.this.toolStack(this.startSlot);
         int divisor = this.state.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(this.state) ? 100 : 30;
         float speed = tool.getDestroySpeed(this.state);
         if (enchanted && !tool.isEmpty() && speed > 1.0F) {
            speed += efficiencyBonus(tool);
         }

         if (MobEffectUtil.hasDigSpeed(mc.player)) {
            speed *= 1.0F + (MobEffectUtil.getDigSpeedAmplification(mc.player) + 1) * 0.2F;
         }

         if (mc.player.hasEffect(MobEffects.MINING_FATIGUE)) {
            speed *= switch (mc.player.getEffect(MobEffects.MINING_FATIGUE).getAmplifier()) {
               case 0 -> 0.3F;
               case 1 -> 0.09F;
               case 2 -> 0.0027F;
               default -> 8.1E-4F;
            };
         }

         speed *= (float)mc.player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED);
         if (mc.player.isEyeInFluid(FluidTags.WATER)) {
            speed *= (float)mc.player.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED);
         }

         if (!mc.player.onGround() && !SpeedMineModule.this.liftsAirMiningPenalty()) {
            speed /= 5.0F;
         }

         return speed / this.hardness / divisor;
      }
   }
}
