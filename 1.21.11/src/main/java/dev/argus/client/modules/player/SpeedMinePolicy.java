/*
 * Adapted from Comet's SpeedminePolicy (THM Addons, GPL-3.0-only) by Boyonabike on 2026-11-14.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.argus.client.modules.player;

/** SpeedMine's totem guard, Alternative, air-mining and tool-slot ownership rules, free of the client so they can be unit tested. */
final class SpeedMinePolicy {
   /** At or below this health, a held totem is never swapped away for a tool. */
   static final float TOTEM_GUARD_HEALTH = 4.0F;

   private SpeedMinePolicy() {
   }

   /**
    * Whether to rotate to the block this tick. With a Limit below 1, rotation waits until progress reaches
    * that fraction of the damage threshold, and with HoldingBest and no AutoSwap, until the tool is held.
    */
   static boolean shouldRotate(float limit, float progress, float damage, boolean autoSwapNone, boolean holdingBest, boolean holdingTool) {
      if (limit == 1.0F) {
         return true;
      }

      if (!(progress / damage >= limit)) {
         return false;
      }

      return !(autoSwapNone && holdingBest && !holdingTool);
   }

   /** Tools may be swapped in unless health is low and a totem is in the main hand. */
   static boolean canSwapTools(float health, boolean holdingTotem) {
      return health > TOTEM_GUARD_HEALTH || !holdingTotem;
   }

   /**
    * Alternative (inventory swap) is blocked in 2b2t mode unless item use is restricted or was active
    * in the last 150 ms; otherwise it follows the setting.
    */
   static boolean isAlternativeAllowed(boolean is2b2tMode, boolean itemUseRestricted, boolean itemUseRecent, boolean alternative) {
      return (!is2b2tMode || itemUseRestricted || itemUseRecent) && alternative;
   }

   /** Air mining is claimed only while airborne, allowed and slow enough. */
   static boolean canClaimAir(boolean onGround, boolean gliding, boolean allowGliding, boolean creative, boolean spectator, double speed, double maximum) {
      return !onGround && (!gliding || allowGliding) && !creative && !spectator && speed <= maximum;
   }

   /** A held tool is only restored while SpeedMine still owns the server slot. */
   static boolean shouldRestoreSlot(int held, int server) {
      return held >= 0 && held == server;
   }
}
