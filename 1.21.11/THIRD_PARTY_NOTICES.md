# Third-party notices

## Design reference

The floating-window styling and combined module/alias/setting search interaction
were informed by [X-C-0/catppuccin-addon](https://github.com/X-C-0/catppuccin-addon),
inspected at commit `eaf2d93cb39db9261d490fd36c76aa8333a7fb79`.
The GUI implementation in this repository is independently written for this
client's APIs; no Meteor/add-on implementation or assets are bundled.

## Lexend Deca

The bundled Lexend Deca font comes from
[Google Fonts](https://github.com/google/fonts/tree/main/ofl/lexenddeca).
Copyright and SIL Open Font License 1.1 terms are included in
[`OFL-Lexend-Deca.txt`](src/main/resources/licenses/OFL-Lexend-Deca.txt)
and under `licenses/` in the client JAR. The unmodified
source font is in `tools/font-source/lexend-deca.ttf`; it is not loaded by Minecraft.

## VolytraFly

- **Project:** VolytraFly
- **Author:** Volizray
- **Source:** <https://github.com/Volizray/VolytraFly-Addon>
- **Licence:** CC0 1.0 Universal (public domain dedication)
- **Full licence text:** [licenses/VolytraFly-CC0.txt](licenses/VolytraFly-CC0.txt)

The **Volytra Fly** mode in Argus Client is a port and adaptation of the elytra
flight logic from VolytraFly. The original project is dedicated to the public
domain under CC0 1.0 Universal. Argus Client credits Volizray as the author of
the original flight logic; the adaptation, integration and any subsequent
changes are part of Argus Client.

This notice and the full CC0 text are included inside the built client JAR
under `THIRD_PARTY_NOTICES.md` and `licenses/VolytraFly-CC0.txt`.

## DEVILS Auto Wasp

- **Project:** DEVILS
- **Authors:** ThianYong-hub and DEVILS contributors
- **Source:** <https://github.com/ThianYong-hub/DEVILS>, revision `2440a168c3ba6f051f2adf8249a8e6fb5f3a5b02`
- **Also based on:** Meteor Client, Copyright (c) Meteor Development,
  <https://github.com/MeteorDevelopment/meteor-client>
- **Licence:** GNU GPL version 3 only (GPL-3.0-only)
- **Full licence text:** [licenses/GPL-3.0.txt](licenses/GPL-3.0.txt)

The **AutoWasp** module (`AutoWaspModule.java`, the `modules/movement/autowasp`
package and `mixin/AutoWaspFireworkAccessor.java`) is adapted from DEVILS' Auto
Wasp by way of the Comet add-on. Boyonabike modified it for Comet on 22-23
October 2026 (boost ramps, setback handling, server-glide wall guard, target
prediction, chest swapping and a time-budgeted path search) and ported it to
Argus Client on 10 November 2026, replacing the Meteor settings, events,
friends, chat and rotation calls with Argus's own. Each file carries its own
provenance notice.

These files are covered by GPL-3.0-only. A client JAR that contains them is a
combined work, so distributing that JAR means distributing it as a whole under
GPL-3.0-only, together with its corresponding source.

This notice and the full GPL text are included inside the built client JAR
under `THIRD_PARTY_NOTICES.md` and `licenses/GPL-3.0.txt`.

## Meteor Client tooltip previews

- **Project:** Meteor Client
- **Authors:** Meteor Development and contributors
- **Source:** <https://github.com/MeteorDevelopment/meteor-client>
- **Licence:** GNU GPL version 3 (GPL-3.0)
- **Full licence text:** [licenses/GPL-3.0.txt](licenses/GPL-3.0.txt)

The **Tooltips** module's ender chest, book, banner and bucket-mob previews,
its food and status-effect lines, the key-held display mode and the rebindable
open key are ported from Meteor Client's BetterTooltips module. They were
rewritten for Argus's settings, events and Mojang-mapped rendering calls on
10 November 2026 (`TooltipsModule.java`, `BookTooltipComponent.java`,
`BannerTooltipComponent.java`, `EntityTooltipComponent.java` and
`BookPreviewScreen.java`). These parts are covered by GPL-3.0 on the same
terms as the AutoWasp files above.

## Meteor Client boss bar stacking

- **Project:** Meteor Client
- **Authors:** Meteor Development and contributors
- **Source:** <https://github.com/MeteorDevelopment/meteor-client>
- **Licence:** GNU GPL version 3 (GPL-3.0)
- **Full licence text:** [licenses/GPL-3.0.txt](licenses/GPL-3.0.txt)

The **BetterBossBars** module (`BetterBossBarsModule.java` and its hooks in
`BossHealthOverlayMixin.java`) is ported from Meteor Client's BossStack module:
boss bars that share a name are stacked into one with an xN counter, names can
be hidden and the bar spacing changed. It was rewritten for Argus's settings and
Mojang-mapped boss bar overlay on 12 November 2026 and is covered by GPL-3.0 on
the same terms as the AutoWasp files above.

## Trouser-Streak Redstone Nuker

- **Project:** Trouser-Streak (a Meteor Client add-on)
- **Authors:** etianl and Trouser-Streak contributors; based on Meteor Client's
  Nuker, Copyright (c) Meteor Development
- **Source:** <https://github.com/etianl/Trouser-Streak>, revision
  `b2455f6c23cdc0cb3f375584c3d150b5e775d457`
- **Licence:** GNU GPL version 3 (GPL-3.0), inherited from Meteor Client
- **Full licence text:** [licenses/GPL-3.0.txt](licenses/GPL-3.0.txt)

The **RedstoneNuker** module (`RedstoneNukerModule.java`) is ported from
Trouser-Streak's RedstoneNuker, which breaks redstone parts near the player so
placed TNT can't be set off. It was rewritten for Argus's settings, events,
box renderer and hotbar selection on 12 November 2026 and is covered by GPL-3.0
on the same terms as the AutoWasp files above.

## Base Hunting modules

The **Base Hunting** window's modules (the `modules/basehunting` package, plus
`NewChunksAlerts.java`, `TunnelsStairs.java` and the `Tracked*WaypointAccessor`
mixins) were written for Argus Client on 13 November 2026 against Argus's own
settings, events and renderers. They follow the detection rules of these
Meteor Client add-ons, rather than copying their code wholesale:

- **Trouser-Streak** by etianl and contributors
  (<https://github.com/etianl/Trouser-Streak>, version 1.6.9): BaseFinder
  (including its default block lists), ActivatedSpawners, NoSpawner,
  PortalPatterns, CaveDisturbance, PlayerActivity, the CollectibleESP, PotESP
  and MobGearESP parts of HuntersESP, WaypointCoords, LodestoneCoords, BeeCoords, WorldEventCoords,
  and the staircase finder in Tunnels. GPL-3.0, as noted for RedstoneNuker
  above; [licenses/GPL-3.0.txt](licenses/GPL-3.0.txt).
- **bephax-community** (<https://github.com/bephaxcommunity/BepHaxCommunity>):
  AreaLoader (now part of Spiral), TrailFollower, RotationDetector, ODMob,
  the VanityESP part of HuntersESP and the stash checks folded into BaseFinder. No licence
  file was found in the copy used; check the upstream project's terms before
  distributing.
- **Stash hunting add-on** by Jeff ("Jefff Mod"): the old-chunk alerts and
  lava falls added to NewChunks. No licence file was found in the
  copy used; check the upstream project's terms before distributing.
- **Comet** by Boyonabike (GPL-3.0-only): StashReport, which copies its report
  to the clipboard instead of sending a Discord webhook, and whose dub count
  follows Majkey's DubCounter, used with the author's permission.

Where these parts derive from GPL-3.0 code they are covered by GPL-3.0 on the
same terms as the AutoWasp files above.

## NoTextureRotations and Comet: AntiCoords and AutoSponge

- **Project:** NoTextureRotations
- **Author:** rfresh2
- **Source:** <https://github.com/rfresh2/NoTextureRotations>, revisions
  `5f20453dce38f04a9b1671fe4638a68c882272d1` (26.x branch) and
  `8cb7deeede7205474be36b5863890b610d6095f3` (keyed-hash branch)
- **Licence:** Apache License 2.0
- **Full licence text:** [licenses/Apache-2.0-NoTextureRotations.txt](licenses/Apache-2.0-NoTextureRotations.txt)

The **AntiCoords** module's texture rotation and offset hiding is adapted from
NoTextureRotations, by way of the Anti Coords module in Comet by Boyonabike. The
adapted files are `AntiCoordsModule.java`, `utils/world/KeyedHash.java`,
`AntiCoordsBlockStateMixin.java` and `AntiCoordsEntityTickMixin.java`. They were
modified for Argus on 13 November 2026: rewritten against Argus's settings and
module system, with the key kept in the Argus config folder as `anti-coords.key`
and Argus's per-session texture seed salt stepping aside while the module
replaces seeds. The upstream project has no separate NOTICE file; this section and
the source headers provide the required attribution.

The **AutoSponge** module and AntiCoords' clean-screenshot hooks are ported from
Comet's Smart Sponge and Anti Coords (Comet original code, CC0-1.0).

## THM Addons Speedmine (via Comet)

- **Project:** THM Addons (a Meteor Client add-on), as adapted for the Comet add-on
- **Authors:** THM Addons contributors; air mining adapted from Quiettee Utils;
  Auto Mine target selection ported from BlackOut (KassuK and OLEPOSSU,
  <https://github.com/KassuK1/BlackOut>, GPL-3.0)
- **Source:** <https://github.com/Leonn170709/THM-Addons>
- **Licence:** GNU GPL version 3 only (GPL-3.0-only)
- **Full licence text:** [licenses/GPL-3.0.txt](licenses/GPL-3.0.txt)

The **SpeedMine** module (`SpeedMineModule.java`, `SpeedMineTiming.java`,
`SpeedMinePolicy.java`, `SpeedMineRange.java` and the air-mining hook in
`PlayerMixin.java`) is Comet's Speedmine, which Boyonabike adapted from THM
Addons for Minecraft 26.2 in October 2026. It replaced Argus's own SpeedMine
on 14 November 2026, rewritten for Argus's settings, events, rotation manager,
friends, MultiTask and box renderer, with Argus's own SpeedMine safety checks,
swap options and render modes (`SpeedMineBlockRenderState.java`,
`SpeedMineRenderQueue.java`) layered on top. These files are covered by GPL-3.0-only on the same
terms as the AutoWasp files above.
