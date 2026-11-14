package dev.argus.client.managers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.argus.client.ClientAccess;
import dev.argus.client.ClientContext;
import dev.argus.client.NamedFeature;
import dev.argus.client.config.LegacyBranding;
import dev.argus.client.events.EventHandler;
import dev.argus.client.events.InputPressEvent;
import dev.argus.client.events.WorldRenderEvent;
import dev.argus.client.integrations.baritone.BaritoneBridge;
import dev.argus.client.modules.HudWidgetModule;
import dev.argus.client.modules.Keybind;
import dev.argus.client.modules.Module;
import dev.argus.client.modules.ModuleCategory;
import dev.argus.client.modules.PlacementModule;
import dev.argus.client.modules.client.AntiCheatModule;
import dev.argus.client.modules.client.BaritoneModule;
import dev.argus.client.modules.client.ColorsModule;
import dev.argus.client.modules.client.DiscordModule;
import dev.argus.client.modules.client.FontsModule;
import dev.argus.client.modules.client.HUDModule;
import dev.argus.client.modules.client.NotificationsModule;
import dev.argus.client.modules.client.SettingsModule;
import dev.argus.client.modules.client.UIModule;
import dev.argus.client.modules.combat.AimAssistModule;
import dev.argus.client.modules.combat.AnchorAuraModule;
import dev.argus.client.modules.combat.AntiBotModule;
import dev.argus.client.modules.combat.AntiFeetPlaceModule;
import dev.argus.client.modules.combat.AntiPearlModule;
import dev.argus.client.modules.combat.AntiPhaseModule;
import dev.argus.client.modules.combat.ArrowsModule;
import dev.argus.client.modules.combat.AuraModule;
import dev.argus.client.modules.combat.AutoArmorModule;
import dev.argus.client.modules.combat.AutoBowReleaseModule;
import dev.argus.client.modules.combat.AutoClickerModule;
import dev.argus.client.modules.combat.AutoCrystalModule;
import dev.argus.client.modules.combat.AutoDropModule;
import dev.argus.client.modules.combat.AutoExpModule;
import dev.argus.client.modules.combat.AutoLogModule;
import dev.argus.client.modules.combat.AutoMaceModule;
import dev.argus.client.modules.combat.AutoMineModule;
import dev.argus.client.modules.combat.AutoPotModule;
import dev.argus.client.modules.combat.AutoTrapModule;
import dev.argus.client.modules.combat.BedAuraModule;
import dev.argus.client.modules.combat.BlockerModule;
import dev.argus.client.modules.combat.BowAimModule;
import dev.argus.client.modules.combat.CriticalsModule;
import dev.argus.client.modules.combat.FeetPlaceModule;
import dev.argus.client.modules.combat.FishTrapModule;
import dev.argus.client.modules.combat.HoleFillModule;
import dev.argus.client.modules.combat.KeyPearlModule;
import dev.argus.client.modules.combat.LegacyCrystalModule;
import dev.argus.client.modules.combat.MainhandModule;
import dev.argus.client.modules.combat.NoHitDelayModule;
import dev.argus.client.modules.combat.OffhandModule;
import dev.argus.client.modules.combat.PistonCrystalModule;
import dev.argus.client.modules.combat.PusherModule;
import dev.argus.client.modules.combat.SelfFillModule;
import dev.argus.client.modules.combat.SelfTrapModule;
import dev.argus.client.modules.combat.SelfWebModule;
import dev.argus.client.modules.combat.TriggerModule;
import dev.argus.client.modules.combat.WebAuraModule;
import dev.argus.client.modules.exploit.AntiHungerModule;
import dev.argus.client.modules.exploit.AntiVanishModule;
import dev.argus.client.modules.exploit.ChorusControlModule;
import dev.argus.client.modules.exploit.FakeVanillaModule;
import dev.argus.client.modules.exploit.FastLatencyModule;
import dev.argus.client.modules.exploit.HitboxDesyncModule;
import dev.argus.client.modules.exploit.IllegalDisconnectModule;
import dev.argus.client.modules.exploit.MultiTaskModule;
import dev.argus.client.modules.exploit.NewChunksModule;
import dev.argus.client.modules.exploit.NoMineAnimationModule;
import dev.argus.client.modules.exploit.PhaseModule;
import dev.argus.client.modules.exploit.PingSpoofModule;
import dev.argus.client.modules.exploit.PortalGodModeModule;
import dev.argus.client.modules.exploit.RaytraceBypassModule;
import dev.argus.client.modules.exploit.ReachModule;
import dev.argus.client.modules.exploit.RocketExtenderModule;
import dev.argus.client.modules.exploit.TimerModule;
import dev.argus.client.modules.exploit.TridentModule;
import dev.argus.client.modules.exploit.XCarryModule;
import dev.argus.client.modules.misc.AnnouncerModule;
import dev.argus.client.modules.misc.AntiAFKModule;
import dev.argus.client.modules.misc.AntiAimModule;
import dev.argus.client.modules.misc.AntiCyrillicModule;
import dev.argus.client.modules.misc.AntiQuitModule;
import dev.argus.client.modules.misc.AntiSpamModule;
import dev.argus.client.modules.misc.AutoLobbyModule;
import dev.argus.client.modules.misc.AutoSetHomeModule;
import dev.argus.client.modules.misc.AutoReconnectModule;
import dev.argus.client.modules.misc.AutoRespawnModule;
import dev.argus.client.modules.misc.AutoSignModule;
import dev.argus.client.modules.misc.BetterChatModule;
import dev.argus.client.modules.misc.ChatFilterModule;
import dev.argus.client.modules.misc.ChestSearchBarModule;
import dev.argus.client.modules.misc.CoordLoggerModule;
import dev.argus.client.modules.misc.CustomDeathTextModule;
import dev.argus.client.modules.misc.ExtraScreenshotModule;
import dev.argus.client.modules.misc.AntiCoordsModule;
import dev.argus.client.modules.misc.ExtraTabModule;
import dev.argus.client.modules.misc.NBTViewerModule;
import dev.argus.client.modules.misc.HeavenModule;
import dev.argus.client.modules.misc.KillEffectsModule;
import dev.argus.client.modules.misc.MiddleClickModule;
import dev.argus.client.modules.misc.NoNarratorModule;
import dev.argus.client.modules.misc.NoPacketKickModule;
import dev.argus.client.modules.misc.NoRotateModule;
import dev.argus.client.modules.misc.SkinFlickerModule;
import dev.argus.client.modules.misc.SoundBlockerModule;
import dev.argus.client.modules.misc.SpammerModule;
import dev.argus.client.modules.misc.SwingModule;
import dev.argus.client.modules.misc.VisualRangeModule;
import dev.argus.client.modules.movement.AutoWalkModule;
import dev.argus.client.modules.movement.AutoWaspModule;
import dev.argus.client.modules.movement.AvoidModule;
import dev.argus.client.modules.movement.ElytraFlyModule;
import dev.argus.client.modules.movement.BoatPhaseModule;
import dev.argus.client.modules.movement.EntityControlModule;
import dev.argus.client.modules.movement.FakeLagModule;
import dev.argus.client.modules.movement.FastClimbModule;
import dev.argus.client.modules.movement.FastFallModule;
import dev.argus.client.modules.movement.FastStairsModule;
import dev.argus.client.modules.movement.FastSwimModule;
import dev.argus.client.modules.movement.FastWebModule;
import dev.argus.client.modules.movement.FireworksModule;
import dev.argus.client.modules.movement.FlightModule;
import dev.argus.client.modules.movement.HighJumpModule;
import dev.argus.client.modules.movement.HoleSnapModule;
import dev.argus.client.modules.movement.JesusModule;
import dev.argus.client.modules.movement.LongJumpModule;
import dev.argus.client.modules.movement.NoFallModule;
import dev.argus.client.modules.movement.NoJumpDelayModule;
import dev.argus.client.modules.movement.NoSlowModule;
import dev.argus.client.modules.movement.ObstaclePasserModule;
import dev.argus.client.modules.movement.SafeWalkModule;
import dev.argus.client.modules.movement.SpeedModule;
import dev.argus.client.modules.movement.SprintModule;
import dev.argus.client.modules.movement.StepModule;
import dev.argus.client.modules.movement.VelocityModule;
import dev.argus.client.modules.movement.WarpModule;
import dev.argus.client.modules.player.AutoBreedModule;
import dev.argus.client.modules.player.AutoCraftModule;
import dev.argus.client.modules.player.AutoEatModule;
import dev.argus.client.modules.player.AutoFarmModule;
import dev.argus.client.modules.player.AutoFishModule;
import dev.argus.client.modules.player.AutoMountModule;
import dev.argus.client.modules.player.AutoNameTagModule;
import dev.argus.client.modules.player.AutoTameModule;
import dev.argus.client.modules.player.AutoTemplateModule;
import dev.argus.client.modules.player.AutoToolModule;
import dev.argus.client.modules.player.AutoTradeModule;
import dev.argus.client.modules.player.BlockCoatModule;
import dev.argus.client.modules.player.ChestStealerModule;
import dev.argus.client.modules.player.FastPlaceModule;
import dev.argus.client.modules.player.FastUseModule;
import dev.argus.client.modules.player.FreecamModule;
import dev.argus.client.modules.player.InventoryCleanerModule;
import dev.argus.client.modules.player.InventorySorterModule;
import dev.argus.client.modules.player.InventoryTweaksModule;
import dev.argus.client.modules.player.ItemSaverModule;
import dev.argus.client.modules.player.NameProtectModule;
import dev.argus.client.modules.player.NoEffectsModule;
import dev.argus.client.modules.player.NoGlitchBlocksModule;
import dev.argus.client.modules.player.NoInteractModule;
import dev.argus.client.modules.player.NukerModule;
import dev.argus.client.modules.player.RedstoneNukerModule;
import dev.argus.client.modules.player.ReplenishModule;
import dev.argus.client.modules.player.RotationLockModule;
import dev.argus.client.modules.player.ScaffoldModule;
import dev.argus.client.modules.player.SourceFillModule;
import dev.argus.client.modules.player.AutoSpongeModule;
import dev.argus.client.modules.player.SpeedMineModule;
import dev.argus.client.modules.render.AmbienceModule;
import dev.argus.client.modules.render.AnimationsModule;
import dev.argus.client.modules.render.BetterBossBarsModule;
import dev.argus.client.modules.render.BlurModule;
import dev.argus.client.modules.render.BordersModule;
import dev.argus.client.modules.render.BreakHighlightModule;
import dev.argus.client.modules.render.ChamsModule;
import dev.argus.client.modules.render.CrosshairModule;
import dev.argus.client.modules.render.ESPModule;
import dev.argus.client.modules.render.FreeLookModule;
import dev.argus.client.modules.render.GlintModule;
import dev.argus.client.modules.render.HighlightModule;
import dev.argus.client.modules.render.HitmarkerModule;
import dev.argus.client.modules.render.HoleESPModule;
import dev.argus.client.modules.render.LogoutSpotsModule;
import dev.argus.client.modules.render.NameTagsModule;
import dev.argus.client.modules.render.NoBobModule;
import dev.argus.client.modules.render.NoRenderModule;
import dev.argus.client.modules.render.ParticlesModule;
import dev.argus.client.modules.render.PhaseESPModule;
import dev.argus.client.modules.render.ShaderModule;
import dev.argus.client.modules.render.SkeletonModule;
import dev.argus.client.modules.render.SkyboxModule;
import dev.argus.client.modules.render.TooltipsModule;
import dev.argus.client.modules.render.TracersModule;
import dev.argus.client.modules.render.TrailsModule;
import dev.argus.client.modules.render.TrajectoriesModule;
import dev.argus.client.modules.render.TunnelsModule;
import dev.argus.client.modules.render.ViewClipModule;
import dev.argus.client.modules.render.ViewModelModule;
import dev.argus.client.modules.render.VoidESPModule;
import dev.argus.client.modules.render.WaypointsModule;
import dev.argus.client.modules.render.XrayModule;
import dev.argus.client.modules.render.ZoomModule;
import dev.argus.client.settings.JsonConfigurable;
import dev.argus.client.settings.NamedEntry;
import dev.argus.client.utils.ExperimentalFeatures;
import dev.argus.client.utils.KeyUtils;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ModuleRegistry extends Registry<Module> implements ClientAccess, JsonConfigurable {
   private final Object2ObjectOpenHashMap<Class<?>, Module> modulesByClass = new Object2ObjectOpenHashMap();

   public List<PlacementModule> getPlacementModules() {
      ArrayList<PlacementModule> placementModules = new ArrayList<>();
      List<Module> registeredModules = (List<Module>)this.entries;
      registeredModules.forEach(module -> {
         if (module instanceof PlacementModule placementModule) {
            placementModules.add(placementModule);
         }
      });
      return placementModules;
   }

   private void registerPathfindingModules() {
      BaritoneBridge bridge = ClientContext.baritoneBridge;
      BaritoneModule baritoneModule = new BaritoneModule();
      this.registerModule(baritoneModule);
      if (bridge.isAvailable()) {
         ObstaclePasserModule obstaclePasserModule = new ObstaclePasserModule();
         this.registerModule(obstaclePasserModule);
      }
   }

   public ModuleRegistry() {
      ClientAccess.eventBus.subscribe(this);
      if (!this.registerModule(new AimAssistModule())) {
         throw new IllegalStateException("Registration rejected AimAssistModule");
      }

      if (!this.registerModule(new AmbienceModule())) {
         throw new IllegalStateException("Registration rejected AmbienceModule");
      }

      if (!this.registerModule(new AnchorAuraModule())) {
         throw new IllegalStateException("Registration rejected AnchorAuraModule");
      }

      if (!this.registerModule(new AnimationsModule())) {
         throw new IllegalStateException("Registration rejected AnimationsModule");
      }

      if (!this.registerModule(new AnnouncerModule())) {
         throw new IllegalStateException("Registration rejected AnnouncerModule");
      }

      if (!this.registerModule(new AntiAFKModule())) {
         throw new IllegalStateException("Registration rejected AntiAFKModule");
      }

      if (!this.registerModule(new AntiAimModule())) {
         throw new IllegalStateException("Registration rejected AntiAimModule");
      }

      if (!this.registerModule(new AntiBotModule())) {
         throw new IllegalStateException("Registration rejected AntiBotModule");
      }

      if (!this.registerModule(new AntiCheatModule())) {
         throw new IllegalStateException("Registration rejected AntiCheatModule");
      }

      if (!this.registerModule(new AntiCyrillicModule())) {
         throw new IllegalStateException("Registration rejected AntiCyrillicModule");
      }

      if (!this.registerModule(new AntiFeetPlaceModule())) {
         throw new IllegalStateException("Registration rejected AntiFeetPlaceModule");
      }

      if (!this.registerModule(new AntiHungerModule())) {
         throw new IllegalStateException("Registration rejected AntiHungerModule");
      }

      if (!this.registerModule(new AntiPearlModule())) {
         throw new IllegalStateException("Registration rejected AntiPearlModule");
      }

      if (!this.registerModule(new AntiPhaseModule())) {
         throw new IllegalStateException("Registration rejected AntiPhaseModule");
      }

      if (!this.registerModule(new AntiQuitModule())) {
         throw new IllegalStateException("Registration rejected AntiQuitModule");
      }

      if (!this.registerModule(new AntiSpamModule())) {
         throw new IllegalStateException("Registration rejected AntiSpamModule");
      }

      if (!this.registerModule(new AntiVanishModule())) {
         throw new IllegalStateException("Registration rejected AntiVanishModule");
      }

      if (!this.registerModule(new AvoidModule())) {
         throw new IllegalStateException("Registration rejected AvoidModule");
      }

      if (!this.registerModule(new ArrowsModule())) {
         throw new IllegalStateException("Registration rejected ArrowsModule");
      }

      if (!this.registerModule(new AuraModule())) {
         throw new IllegalStateException("Registration rejected AuraModule");
      }

      if (!this.registerModule(new AutoArmorModule())) {
         throw new IllegalStateException("Registration rejected AutoArmorModule");
      }

      if (!this.registerModule(new AutoBowReleaseModule())) {
         throw new IllegalStateException("Registration rejected AutoBowReleaseModule");
      }

      if (!this.registerModule(new AutoBreedModule())) {
         throw new IllegalStateException("Registration rejected AutoBreedModule");
      }

      if (!this.registerModule(new AutoClickerModule())) {
         throw new IllegalStateException("Registration rejected AutoClickerModule");
      }

      if (!this.registerModule(new AutoCraftModule())) {
         throw new IllegalStateException("Registration rejected AutoCraftModule");
      }

      if (!this.registerModule(new AutoCrystalModule())) {
         throw new IllegalStateException("Registration rejected AutoCrystalModule");
      }

      if (!this.registerModule(new AutoDropModule())) {
         throw new IllegalStateException("Registration rejected AutoDropModule");
      }

      if (!this.registerModule(new AutoEatModule())) {
         throw new IllegalStateException("Registration rejected AutoEatModule");
      }

      if (!this.registerModule(new AutoExpModule())) {
         throw new IllegalStateException("Registration rejected AutoExpModule");
      }

      if (!this.registerModule(new AutoFarmModule())) {
         throw new IllegalStateException("Registration rejected AutoFarmModule");
      }

      if (!this.registerModule(new AutoFishModule())) {
         throw new IllegalStateException("Registration rejected AutoFishModule");
      }

      if (!this.registerModule(new AutoLogModule())) {
         throw new IllegalStateException("Registration rejected AutoLogModule");
      }

      if (!this.registerModule(new AutoMaceModule())) {
         throw new IllegalStateException("Registration rejected AutoMaceModule");
      }

      if (!this.registerModule(new AutoMineModule())) {
         throw new IllegalStateException("Registration rejected AutoMineModule");
      }

      if (!this.registerModule(new AutoMountModule())) {
         throw new IllegalStateException("Registration rejected AutoMountModule");
      }

      if (!this.registerModule(new AutoNameTagModule())) {
         throw new IllegalStateException("Registration rejected AutoNameTagModule");
      }

      if (!this.registerModule(new AutoPotModule())) {
         throw new IllegalStateException("Registration rejected AutoPotModule");
      }

      if (!this.registerModule(new AutoLobbyModule())) {
         throw new IllegalStateException("Registration rejected AutoLobbyModule");
      }

      if (!this.registerModule(new AutoSetHomeModule())) {
         throw new IllegalStateException("Registration rejected AutoSetHomeModule");
      }

      if (!this.registerModule(new AutoReconnectModule())) {
         throw new IllegalStateException("Registration rejected AutoReconnectModule");
      }

      if (!this.registerModule(new AutoRespawnModule())) {
         throw new IllegalStateException("Registration rejected AutoRespawnModule");
      }

      if (!this.registerModule(new AutoSignModule())) {
         throw new IllegalStateException("Registration rejected AutoSignModule");
      }

      if (!this.registerModule(new AutoTameModule())) {
         throw new IllegalStateException("Registration rejected AutoTameModule");
      }

      if (!this.registerModule(new AutoToolModule())) {
         throw new IllegalStateException("Registration rejected AutoToolModule");
      }

      if (!this.registerModule(new AutoTradeModule())) {
         throw new IllegalStateException("Registration rejected AutoTradeModule");
      }

      if (!this.registerModule(new AutoTrapModule())) {
         throw new IllegalStateException("Registration rejected AutoTrapModule");
      }

      if (!this.registerModule(new AutoTemplateModule())) {
         throw new IllegalStateException("Registration rejected AutoTemplateModule");
      }

      if (!this.registerModule(new AutoWalkModule())) {
         throw new IllegalStateException("Registration rejected AutoWalkModule");
      }

      if (!this.registerModule(new AutoWaspModule())) {
         throw new IllegalStateException("Registration rejected AutoWaspModule");
      }

      if (!this.registerModule(new BedAuraModule())) {
         throw new IllegalStateException("Registration rejected BedAuraModule");
      }

      if (!this.registerModule(new BetterChatModule())) {
         throw new IllegalStateException("Registration rejected BetterChatModule");
      }

      if (!this.registerModule(new BlockerModule())) {
         throw new IllegalStateException("Registration rejected BlockerModule");
      }

      if (!this.registerModule(new BetterBossBarsModule())) {
         throw new IllegalStateException("Registration rejected BetterBossBarsModule");
      }

      if (!this.registerModule(new BlockCoatModule())) {
         throw new IllegalStateException("Registration rejected BlockCoatModule");
      }

      if (!this.registerModule(new BlurModule())) {
         throw new IllegalStateException("Registration rejected BlurModule");
      }

      if (!this.registerModule(new BordersModule())) {
         throw new IllegalStateException("Registration rejected BordersModule");
      }

      if (!this.registerModule(new BowAimModule())) {
         throw new IllegalStateException("Registration rejected BowAimModule");
      }

      if (!this.registerModule(new BreakHighlightModule())) {
         throw new IllegalStateException("Registration rejected BreakHighlightModule");
      }

      if (!this.registerModule(new ChamsModule())) {
         throw new IllegalStateException("Registration rejected ChamsModule");
      }

      if (!this.registerModule(new ChatFilterModule())) {
         throw new IllegalStateException("Registration rejected ChatFilterModule");
      }

      if (!this.registerModule(new ChestSearchBarModule())) {
         throw new IllegalStateException("Registration rejected ChestSearchBarModule");
      }

      if (!this.registerModule(new ChestStealerModule())) {
         throw new IllegalStateException("Registration rejected ChestStealerModule");
      }

      if (!this.registerModule(new ChorusControlModule())) {
         throw new IllegalStateException("Registration rejected ChorusControlModule");
      }

      if (!this.registerModule(new ColorsModule())) {
         throw new IllegalStateException("Registration rejected ColorsModule");
      }

      if (!this.registerModule(new CoordLoggerModule())) {
         throw new IllegalStateException("Registration rejected CoordLoggerModule");
      }

      if (!this.registerModule(new CriticalsModule())) {
         throw new IllegalStateException("Registration rejected CriticalsModule");
      }

      if (!this.registerModule(new CrosshairModule())) {
         throw new IllegalStateException("Registration rejected CrosshairModule");
      }

      if (!this.registerModule(new CustomDeathTextModule())) {
         throw new IllegalStateException("Registration rejected CustomDeathTextModule");
      }

      if (!this.registerModule(new DiscordModule())) {
         throw new IllegalStateException("Registration rejected DiscordModule");
      }

      if (!this.registerModule(new ESPModule())) {
         throw new IllegalStateException("Registration rejected ESPModule");
      }

      if (!this.registerModule(new ElytraFlyModule())) {
         throw new IllegalStateException("Registration rejected ElytraFlyModule");
      }

      if (!this.registerModule(new BoatPhaseModule())) {
         throw new IllegalStateException("Registration rejected BoatPhaseModule");
      }

      if (!this.registerModule(new EntityControlModule())) {
         throw new IllegalStateException("Registration rejected EntityControlModule");
      }

      if (!this.registerModule(new ExtraScreenshotModule())) {
         throw new IllegalStateException("Registration rejected ExtraScreenshotModule");
      }

      if (!this.registerModule(new AntiCoordsModule())) {
         throw new IllegalStateException("Registration rejected AntiCoordsModule");
      }

      if (!this.registerModule(new ExtraTabModule())) {
         throw new IllegalStateException("Registration rejected ExtraTabModule");
      }

      if (!this.registerModule(new NBTViewerModule())) {
         throw new IllegalStateException("Registration rejected NBTViewerModule");
      }

      if (!this.registerModule(new FakeLagModule())) {
         throw new IllegalStateException("Registration rejected FakeLagModule");
      }

      if (!this.registerModule(new FakeVanillaModule())) {
         throw new IllegalStateException("Registration rejected FakeVanillaModule");
      }

      if (!this.registerModule(new FastClimbModule())) {
         throw new IllegalStateException("Registration rejected FastClimbModule");
      }

      if (!this.registerModule(new FastFallModule())) {
         throw new IllegalStateException("Registration rejected FastFallModule");
      }

      if (!this.registerModule(new FastLatencyModule())) {
         throw new IllegalStateException("Registration rejected FastLatencyModule");
      }

      if (!this.registerModule(new FastPlaceModule())) {
         throw new IllegalStateException("Registration rejected FastPlaceModule");
      }

      if (!this.registerModule(new FastStairsModule())) {
         throw new IllegalStateException("Registration rejected FastStairsModule");
      }

      if (!this.registerModule(new FastSwimModule())) {
         throw new IllegalStateException("Registration rejected FastSwimModule");
      }

      if (!this.registerModule(new FastUseModule())) {
         throw new IllegalStateException("Registration rejected FastUseModule");
      }

      if (!this.registerModule(new FastWebModule())) {
         throw new IllegalStateException("Registration rejected FastWebModule");
      }

      if (!this.registerModule(new FeetPlaceModule())) {
         throw new IllegalStateException("Registration rejected FeetPlaceModule");
      }

      if (!this.registerModule(new FireworksModule())) {
         throw new IllegalStateException("Registration rejected FireworksModule");
      }

      if (!this.registerModule(new FishTrapModule())) {
         throw new IllegalStateException("Registration rejected FishTrapModule");
      }

      if (!this.registerModule(new FlightModule())) {
         throw new IllegalStateException("Registration rejected FlightModule");
      }

      if (!this.registerModule(new FontsModule())) {
         throw new IllegalStateException("Registration rejected FontsModule");
      }

      if (!this.registerModule(new FreeLookModule())) {
         throw new IllegalStateException("Registration rejected FreeLookModule");
      }

      if (!this.registerModule(new FreecamModule())) {
         throw new IllegalStateException("Registration rejected FreecamModule");
      }

      if (!this.registerModule(new GlintModule())) {
         throw new IllegalStateException("Registration rejected GlintModule");
      }

      if (!this.registerModule(new HUDModule())) {
         throw new IllegalStateException("Registration rejected HUDModule");
      }

      if (!this.registerModule(new HeavenModule())) {
         throw new IllegalStateException("Registration rejected HeavenModule");
      }

      if (!this.registerModule(new HighJumpModule())) {
         throw new IllegalStateException("Registration rejected HighJumpModule");
      }

      if (!this.registerModule(new HighlightModule())) {
         throw new IllegalStateException("Registration rejected HighlightModule");
      }

      if (!this.registerModule(new HitboxDesyncModule())) {
         throw new IllegalStateException("Registration rejected HitboxDesyncModule");
      }

      if (!this.registerModule(new HitmarkerModule())) {
         throw new IllegalStateException("Registration rejected HitmarkerModule");
      }

      if (!this.registerModule(new HoleESPModule())) {
         throw new IllegalStateException("Registration rejected HoleESPModule");
      }

      if (!this.registerModule(new HoleFillModule())) {
         throw new IllegalStateException("Registration rejected HoleFillModule");
      }

      if (!this.registerModule(new HoleSnapModule())) {
         throw new IllegalStateException("Registration rejected HoleSnapModule");
      }

      if (!this.registerModule(new IllegalDisconnectModule())) {
         throw new IllegalStateException("Registration rejected IllegalDisconnectModule");
      }

      if (!this.registerModule(new InventoryCleanerModule())) {
         throw new IllegalStateException("Registration rejected InventoryCleanerModule");
      }

      if (!this.registerModule(new InventorySorterModule())) {
         throw new IllegalStateException("Registration rejected InventorySorterModule");
      }

      if (!this.registerModule(new InventoryTweaksModule())) {
         throw new IllegalStateException("Registration rejected InventoryTweaksModule");
      }

      if (!this.registerModule(new ItemSaverModule())) {
         throw new IllegalStateException("Registration rejected ItemSaverModule");
      }

      if (!this.registerModule(new JesusModule())) {
         throw new IllegalStateException("Registration rejected JesusModule");
      }

      if (!this.registerModule(new KeyPearlModule())) {
         throw new IllegalStateException("Registration rejected KeyPearlModule");
      }

      if (!this.registerModule(new KillEffectsModule())) {
         throw new IllegalStateException("Registration rejected KillEffectsModule");
      }

      if (!this.registerModule(new LegacyCrystalModule())) {
         throw new IllegalStateException("Registration rejected LegacyCrystalModule");
      }

      if (!this.registerModule(new LogoutSpotsModule())) {
         throw new IllegalStateException("Registration rejected LogoutSpotsModule");
      }

      if (!this.registerModule(new LongJumpModule())) {
         throw new IllegalStateException("Registration rejected LongJumpModule");
      }

      if (!this.registerModule(new MainhandModule())) {
         throw new IllegalStateException("Registration rejected MainhandModule");
      }

      if (!this.registerModule(new MiddleClickModule())) {
         throw new IllegalStateException("Registration rejected MiddleClickModule");
      }

      if (!this.registerModule(new MultiTaskModule())) {
         throw new IllegalStateException("Registration rejected MultiTaskModule");
      }

      if (!this.registerModule(new NameProtectModule())) {
         throw new IllegalStateException("Registration rejected NameProtectModule");
      }

      if (!this.registerModule(new NameTagsModule())) {
         throw new IllegalStateException("Registration rejected NameTagsModule");
      }

      if (!this.registerModule(new NewChunksModule())) {
         throw new IllegalStateException("Registration rejected NewChunksModule");
      }

      if (!this.registerModule(new NoBobModule())) {
         throw new IllegalStateException("Registration rejected NoBobModule");
      }

      if (!this.registerModule(new NoEffectsModule())) {
         throw new IllegalStateException("Registration rejected NoEffectsModule");
      }

      if (!this.registerModule(new NoFallModule())) {
         throw new IllegalStateException("Registration rejected NoFallModule");
      }

      if (!this.registerModule(new NoGlitchBlocksModule())) {
         throw new IllegalStateException("Registration rejected NoGlitchBlocksModule");
      }

      if (!this.registerModule(new NoHitDelayModule())) {
         throw new IllegalStateException("Registration rejected NoHitDelayModule");
      }

      if (!this.registerModule(new NoInteractModule())) {
         throw new IllegalStateException("Registration rejected NoInteractModule");
      }

      if (!this.registerModule(new NoJumpDelayModule())) {
         throw new IllegalStateException("Registration rejected NoJumpDelayModule");
      }

      if (!this.registerModule(new NoMineAnimationModule())) {
         throw new IllegalStateException("Registration rejected NoMineAnimationModule");
      }

      if (!this.registerModule(new NoNarratorModule())) {
         throw new IllegalStateException("Registration rejected NoNarratorModule");
      }

      if (!this.registerModule(new NoPacketKickModule())) {
         throw new IllegalStateException("Registration rejected NoPacketKickModule");
      }

      if (!this.registerModule(new NoRenderModule())) {
         throw new IllegalStateException("Registration rejected NoRenderModule");
      }

      if (!this.registerModule(new NoRotateModule())) {
         throw new IllegalStateException("Registration rejected NoRotateModule");
      }

      if (!this.registerModule(new NoSlowModule())) {
         throw new IllegalStateException("Registration rejected NoSlowModule");
      }

      if (!this.registerModule(new NotificationsModule())) {
         throw new IllegalStateException("Registration rejected NotificationsModule");
      }

      if (!this.registerModule(new NukerModule())) {
         throw new IllegalStateException("Registration rejected NukerModule");
      }

      if (!this.registerModule(new RedstoneNukerModule())) {
         throw new IllegalStateException("Registration rejected RedstoneNukerModule");
      }

      if (!this.registerModule(new OffhandModule())) {
         throw new IllegalStateException("Registration rejected OffhandModule");
      }

      if (!this.registerModule(new ParticlesModule())) {
         throw new IllegalStateException("Registration rejected ParticlesModule");
      }

      if (!this.registerModule(new PhaseModule())) {
         throw new IllegalStateException("Registration rejected PhaseModule");
      }

      if (!this.registerModule(new PhaseESPModule())) {
         throw new IllegalStateException("Registration rejected PhaseESPModule");
      }

      if (!this.registerModule(new PingSpoofModule())) {
         throw new IllegalStateException("Registration rejected PingSpoofModule");
      }

      if (!this.registerModule(new PortalGodModeModule())) {
         throw new IllegalStateException("Registration rejected PortalGodModeModule");
      }

      if (!this.registerModule(new PistonCrystalModule())) {
         throw new IllegalStateException("Registration rejected PistonCrystalModule");
      }

      if (!this.registerModule(new PusherModule())) {
         throw new IllegalStateException("Registration rejected PusherModule");
      }

      if (!this.registerModule(new RaytraceBypassModule())) {
         throw new IllegalStateException("Registration rejected RaytraceBypassModule");
      }

      if (!this.registerModule(new ReachModule())) {
         throw new IllegalStateException("Registration rejected ReachModule");
      }

      if (!this.registerModule(new ReplenishModule())) {
         throw new IllegalStateException("Registration rejected ReplenishModule");
      }

      if (!this.registerModule(new RocketExtenderModule())) {
         throw new IllegalStateException("Registration rejected RocketExtenderModule");
      }

      if (!this.registerModule(new RotationLockModule())) {
         throw new IllegalStateException("Registration rejected RotationLockModule");
      }

      if (!this.registerModule(new SafeWalkModule())) {
         throw new IllegalStateException("Registration rejected SafeWalkModule");
      }

      if (!this.registerModule(new ScaffoldModule())) {
         throw new IllegalStateException("Registration rejected ScaffoldModule");
      }

      if (!this.registerModule(new SelfFillModule())) {
         throw new IllegalStateException("Registration rejected SelfFillModule");
      }

      if (!this.registerModule(new SelfTrapModule())) {
         throw new IllegalStateException("Registration rejected SelfTrapModule");
      }

      if (!this.registerModule(new SelfWebModule())) {
         throw new IllegalStateException("Registration rejected SelfWebModule");
      }

      if (!this.registerModule(new SettingsModule())) {
         throw new IllegalStateException("Registration rejected SettingsModule");
      }

      if (!this.registerModule(new ShaderModule())) {
         throw new IllegalStateException("Registration rejected ShaderModule");
      }

      if (!this.registerModule(new SkeletonModule())) {
         throw new IllegalStateException("Registration rejected SkeletonModule");
      }

      if (!this.registerModule(new SkinFlickerModule())) {
         throw new IllegalStateException("Registration rejected SkinFlickerModule");
      }

      if (!this.registerModule(new SkyboxModule())) {
         throw new IllegalStateException("Registration rejected SkyboxModule");
      }

      if (!this.registerModule(new SoundBlockerModule())) {
         throw new IllegalStateException("Registration rejected SoundBlockerModule");
      }

      if (!this.registerModule(new SourceFillModule())) {
         throw new IllegalStateException("Registration rejected SourceFillModule");
      }

      if (!this.registerModule(new AutoSpongeModule())) {
         throw new IllegalStateException("Registration rejected AutoSpongeModule");
      }

      if (!this.registerModule(new SpammerModule())) {
         throw new IllegalStateException("Registration rejected SpammerModule");
      }

      if (!this.registerModule(new SpeedModule())) {
         throw new IllegalStateException("Registration rejected SpeedModule");
      }

      if (!this.registerModule(new SpeedMineModule())) {
         throw new IllegalStateException("Registration rejected SpeedMineModule");
      }

      if (!this.registerModule(new SprintModule())) {
         throw new IllegalStateException("Registration rejected SprintModule");
      }

      if (!this.registerModule(new StepModule())) {
         throw new IllegalStateException("Registration rejected StepModule");
      }

      if (!this.registerModule(new SwingModule())) {
         throw new IllegalStateException("Registration rejected SwingModule");
      }

      if (!this.registerModule(new TimerModule())) {
         throw new IllegalStateException("Registration rejected TimerModule");
      }

      if (!this.registerModule(new TooltipsModule())) {
         throw new IllegalStateException("Registration rejected TooltipsModule");
      }

      if (!this.registerModule(new TracersModule())) {
         throw new IllegalStateException("Registration rejected TracersModule");
      }

      if (!this.registerModule(new TrailsModule())) {
         throw new IllegalStateException("Registration rejected TrailsModule");
      }

      if (!this.registerModule(new TrajectoriesModule())) {
         throw new IllegalStateException("Registration rejected TrajectoriesModule");
      }

      if (!this.registerModule(new TridentModule())) {
         throw new IllegalStateException("Registration rejected TridentModule");
      }

      if (!this.registerModule(new TriggerModule())) {
         throw new IllegalStateException("Registration rejected TriggerModule");
      }

      if (!this.registerModule(new TunnelsModule())) {
         throw new IllegalStateException("Registration rejected TunnelsModule");
      }

      if (!this.registerModule(new UIModule())) {
         throw new IllegalStateException("Registration rejected UIModule");
      }

      if (!this.registerModule(new VelocityModule())) {
         throw new IllegalStateException("Registration rejected VelocityModule");
      }

      if (!this.registerModule(new ViewClipModule())) {
         throw new IllegalStateException("Registration rejected ViewClipModule");
      }

      if (!this.registerModule(new ViewModelModule())) {
         throw new IllegalStateException("Registration rejected ViewModelModule");
      }

      if (!this.registerModule(new VisualRangeModule())) {
         throw new IllegalStateException("Registration rejected VisualRangeModule");
      }

      if (!this.registerModule(new VoidESPModule())) {
         throw new IllegalStateException("Registration rejected VoidESPModule");
      }

      if (!this.registerModule(new WarpModule())) {
         throw new IllegalStateException("Registration rejected WarpModule");
      }

      if (!this.registerModule(new WaypointsModule())) {
         throw new IllegalStateException("Registration rejected WaypointsModule");
      }

      if (!this.registerModule(new WebAuraModule())) {
         throw new IllegalStateException("Registration rejected WebAuraModule");
      }

      if (!this.registerModule(new XCarryModule())) {
         throw new IllegalStateException("Registration rejected XCarryModule");
      }

      if (!this.registerModule(new XrayModule())) {
         throw new IllegalStateException("Registration rejected XrayModule");
      }

      if (!this.registerModule(new ZoomModule())) {
         throw new IllegalStateException("Registration rejected ZoomModule");
      }

      for (Module baseHuntingModule : dev.argus.client.modules.basehunting.BaseHuntingModules.create()) {
         if (!this.registerModule(baseHuntingModule)) {
            throw new IllegalStateException("Registration rejected " + baseHuntingModule.getClass().getSimpleName());
         }
      }

      this.registerPathfindingModules();
      List<Module> registeredModules = (List<Module>)this.entries;
      registeredModules.sort(Comparator.comparing(NamedFeature::getName));
   }

   @Override
   public boolean register(Object entry) {
      Module module = (Module)entry;
      return this.registerModule(module);
   }

   @Override
   public boolean unregister(Object entry) {
      Module module = (Module)entry;
      return this.unregisterModule(module);
   }

   public boolean unregisterModule(Module module) {
      Object2ObjectOpenHashMap<Class<?>, Module> cache = this.modulesByClass;
      Class moduleClass = module.getClass();
      cache.remove(moduleClass, module);
      return super.unregisterEntry(module);
   }

   @Override
   public void fromJson(JsonElement config) {
      for (Module module : (List<Module>)this.getRegistry()) {
         if (!(module instanceof HudWidgetModule)) {
            try {
               JsonObject configObject = config.getAsJsonObject();
               String moduleConfigName = module.getConfigName();
               JsonElement moduleConfig = LegacyBranding.findModuleConfig(configObject, moduleConfigName);
               module.fromJson(moduleConfig);
            } catch (Exception var8) {
            }
         }
      }

      // Search was merged into ESP: carry a saved Search section over once.
      try {
         if (this.getModule(ESPModule.class) instanceof ESPModule esp) {
            esp.mergeSearch(LegacyBranding.findModuleConfig(config.getAsJsonObject(), "Search"));
         }
      } catch (Exception ignored) {
      }
   }

   @Override
   public JsonElement toJson() {
      JsonObject config = new JsonObject();
      config.addProperty("client", "argusclient");
      config.addProperty("category", "all");

      for (Module module : (List<Module>)this.getRegistry()) {
         if (!(module instanceof HudWidgetModule)) {
            try {
               String moduleConfigName = module.getConfigName();
               JsonElement moduleConfig = module.toJson();
               config.add(moduleConfigName, moduleConfig);
            } catch (Exception var7) {
            }
         }
      }

      return config;
   }

   @Override
   public String getConfigName() {
      return "modules.json";
   }

   @Override
   public boolean registerEntry(NamedEntry entry) {
      Module module = (Module)entry;
      return this.registerModule(module);
   }

   @Deprecated
   @Override
   public NamedEntry getByClass(Class moduleType) {
      return this.getModule(moduleType);
   }

   public List getModulesByCategory(ModuleCategory category) {
      List<Module> registeredModules = (List<Module>)this.entries;
      Stream<Module> modules = registeredModules.stream();
      return modules.filter(module -> module.getCategory() == category).collect(Collectors.toList());
   }

   @EventHandler
   private void onInputPress(InputPressEvent inputEvent) {
      if (!KeyUtils.isPhysicalKeyDown(292)) {
         for (Module module : (List<Module>)this.entries) {
            Keybind keybindForDeviceCheck = module.getKeybind();
            boolean mouseBinding = keybindForDeviceCheck.mouse();
            if (mouseBinding == inputEvent.isMouse()) {
               Keybind keybindForCodeCheck = module.getKeybind();
               int bindingCode = keybindForCodeCheck.code();
               if (bindingCode == inputEvent.getCode()) {
                  Keybind keybindForModeCheck = module.getKeybind();
                  if (keybindForModeCheck.mode() == Keybind.Mode.TOGGLE
                     && !(module.isToggled() && module.claimsKeyPress(inputEvent.getCode(), inputEvent.isMouse()))) {
                     module.toggle();
                  }
               }
            }
         }
      }
   }

   @EventHandler(priority = -200)
   private void onWorldRender(WorldRenderEvent.Main renderEvent) {
      for (Module module : (List<Module>)this.entries) {
         Keybind keybind = module.getKeybind();
         boolean holdBinding = keybind.mode() == Keybind.Mode.HOLD;
         if (!keybind.isUnbound()
            && keybind.mode() != Keybind.Mode.TOGGLE
            && (ClientAccess.argusclientMinecraft.gui.screen() == null || module.isToggled() == holdBinding)) {
            boolean shouldToggle = holdBinding == KeyUtils.isPhysicallyDown(keybind);
            module.setToggled(shouldToggle);
         }
      }
   }

   @Override
   public boolean unregisterEntry(NamedEntry entry) {
      Module module = (Module)entry;
      return this.unregisterModule(module);
   }

   public boolean registerModule(Module module) {
      Class moduleType = module.getClass();
      if (ExperimentalFeatures.isClassHidden(moduleType)) {
         return false;
      }

      ExperimentalFeatures.unregisterHiddenSettings(module);
      Object2ObjectOpenHashMap<Class<?>, Module> cache = this.modulesByClass;
      Class moduleClass = module.getClass();
      cache.put(moduleClass, module);
      return super.registerEntry(module);
   }

   @Deprecated
   public Module getModule(Class moduleType) {
      Object2ObjectOpenHashMap<Class<?>, Module> cache = this.modulesByClass;
      return (Module)cache.get(moduleType);
   }
}
