package dev.argus.client.modules.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SpeedMineTimingTest {
   @Test
   void serverTicksFollowClampedTps() {
      assertEquals(20.0, SpeedMineTiming.serverTicks(1000L, 20.0F), 1e-9);
      assertEquals(10.0, SpeedMineTiming.serverTicks(1000L, 10.0F), 1e-9);
      assertEquals(20.0, SpeedMineTiming.serverTicks(1000L, 40.0F), 1e-9);
      assertEquals(20.0, SpeedMineTiming.serverTicks(1000L, Float.NaN), 1e-9);
      assertEquals(0.0, SpeedMineTiming.serverTicks(-50L, 20.0F), 1e-9);
   }

   @Test
   void startCreditCarriesFractionsAndCapsAtTwo() {
      assertEquals(0.5, SpeedMineTiming.replenishStartCredit(0.0, 50L, 10.0F), 1e-9);
      assertEquals(SpeedMineTiming.MAX_START_CREDIT, SpeedMineTiming.replenishStartCredit(1.5, 1000L, 20.0F), 1e-9);
   }

   @Test
   void startsWaitForCreditOnlyWhenSyncedAndLagging() {
      assertTrue(SpeedMineTiming.canStart(false, 5.0F, 0.0));
      assertTrue(SpeedMineTiming.canStart(true, 20.0F, 0.0));
      assertTrue(SpeedMineTiming.canStart(true, Float.NaN, 0.0));
      assertFalse(SpeedMineTiming.canStart(true, 10.0F, 0.5));
      assertTrue(SpeedMineTiming.canStart(true, 10.0F, 1.0));
   }

   @Test
   void instantDelayHasA2b2tFloorOnlyWithAlternative() {
      assertEquals(0.0F, SpeedMineTiming.instantDelaySeconds(0.0F, false, true), 1e-6);
      assertEquals(0.0F, SpeedMineTiming.instantDelaySeconds(0.0F, true, false), 1e-6);
      assertEquals(SpeedMineTiming.MIN_2B2T_INSTANT_DELAY, SpeedMineTiming.instantDelaySeconds(0.0F, true, true), 1e-6);
      assertEquals(0.5F, SpeedMineTiming.instantDelaySeconds(0.5F, true, true), 1e-6);
   }
}
