package dev.argus.client.modules.basehunting;

import dev.argus.client.modules.Module;
import java.util.List;

/** Every module written for the Base Hunting window, in one place so each Minecraft version registers the same set. */
public final class BaseHuntingModules {
   private BaseHuntingModules() {
   }

   public static List<Module> create() {
      return List.of(
         new SpiralModule(),
         new BaseFinderModule(),
         new ActivatedSpawnersModule(),
         new NoSpawnerModule(),
         new PortalPatternsModule(),
         new CaveDisturbanceModule(),
         new PlayerActivityModule(),
         new ODMobModule(),
         new RotationDetectorModule(),
         new HuntersESPModule(),
         new FlowESPModule(),
         new StashReportModule(),
         new WaypointCoordsModule(),
         new LodestoneCoordsModule(),
         new BeeCoordsModule(),
         new WorldEventCoordsModule(),
         new TrailFollowerModule()
      );
   }
}
