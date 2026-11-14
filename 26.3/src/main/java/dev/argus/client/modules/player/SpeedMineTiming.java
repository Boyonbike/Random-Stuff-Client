/*
 * Adapted from Comet's SpeedmineTiming (THM Addons, GPL-3.0-only) by Boyonabike on 2026-11-14.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.argus.client.modules.player;

/** SpeedMine's server-tick pacing and start budget, free of the client so it can be unit tested. */
final class SpeedMineTiming {
   /** Most block starts that can be banked while TPS is low. */
   static final double MAX_START_CREDIT = 2.0;

   /** Instant rebreaks with Alternative on 2b2t-style servers never go below this delay. */
   static final float MIN_2B2T_INSTANT_DELAY = 0.1F;

   private SpeedMineTiming() {
   }

   /** The instant-rebreak delay in seconds, raised to the 2b2t floor when Alternative is in use there. */
   static float instantDelaySeconds(float configured, boolean alternativeAllowed, boolean is2b2tMode) {
      return alternativeAllowed && is2b2tMode ? Math.max(configured, MIN_2B2T_INSTANT_DELAY) : configured;
   }

   /** Server ticks elapsed over a wall-clock interval, at a TPS clamped to 0-20 (20 if unknown). */
   static double serverTicks(long elapsedMillis, float tps) {
      float safeTps = Float.isFinite(tps) ? tps : 20.0F;
      return Math.max(0L, elapsedMillis) / 1000.0 * Math.clamp(safeTps, 0.0F, 20.0F);
   }

   /**
    * Earns block starts at the server's tick rate. Fractional credit carries over so client-tick jitter
    * never throws progress away, and at most {@link #MAX_START_CREDIT} starts can be banked.
    */
   static double replenishStartCredit(double credit, long elapsedMillis, float tps) {
      return Math.min(MAX_START_CREDIT, credit + serverTicks(elapsedMillis, tps));
   }

   /** At full TPS, with an unknown TPS, or with sync off, a start never waits for credit. */
   static boolean canStart(boolean tpsSync, float tps, double credit) {
      return !tpsSync || !Float.isFinite(tps) || tps >= 20.0F || credit >= 1.0;
   }
}
