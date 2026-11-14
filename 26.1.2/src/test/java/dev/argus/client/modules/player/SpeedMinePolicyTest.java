package dev.argus.client.modules.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SpeedMinePolicyTest {
   @Test
   void airClaimNeedsSlowSurvivalAirborneMovement() {
      assertTrue(SpeedMinePolicy.canClaimAir(false, false, false, false, false, 0.5, 1.0));
      assertFalse(SpeedMinePolicy.canClaimAir(true, false, false, false, false, 0.5, 1.0));
      assertFalse(SpeedMinePolicy.canClaimAir(false, true, false, false, false, 0.5, 1.0));
      assertTrue(SpeedMinePolicy.canClaimAir(false, true, true, false, false, 0.5, 1.0));
      assertFalse(SpeedMinePolicy.canClaimAir(false, false, false, true, false, 0.5, 1.0));
      assertFalse(SpeedMinePolicy.canClaimAir(false, false, false, false, true, 0.5, 1.0));
      assertFalse(SpeedMinePolicy.canClaimAir(false, false, false, false, false, 1.5, 1.0));
   }

   @Test
   void slotIsRestoredOnlyWhileSpeedMineOwnsIt() {
      assertTrue(SpeedMinePolicy.shouldRestoreSlot(3, 3));
      assertFalse(SpeedMinePolicy.shouldRestoreSlot(3, 4));
      assertFalse(SpeedMinePolicy.shouldRestoreSlot(-1, -1));
   }

   @Test
   void totemIsKeptInHandAtLowHealth() {
      assertFalse(SpeedMinePolicy.canSwapTools(4.0F, true));
      assertTrue(SpeedMinePolicy.canSwapTools(4.5F, true));
      assertTrue(SpeedMinePolicy.canSwapTools(2.0F, false));
   }

   @Test
   void alternativeNeedsRecentItemUseOn2b2t() {
      assertTrue(SpeedMinePolicy.isAlternativeAllowed(false, false, false, true));
      assertFalse(SpeedMinePolicy.isAlternativeAllowed(false, false, false, false));
      assertFalse(SpeedMinePolicy.isAlternativeAllowed(true, false, false, true));
      assertTrue(SpeedMinePolicy.isAlternativeAllowed(true, true, false, true));
      assertTrue(SpeedMinePolicy.isAlternativeAllowed(true, false, true, true));
   }

   @Test
   void rotateLimitWaitsForProgress() {
      assertTrue(SpeedMinePolicy.shouldRotate(1.0F, 0.0F, 1.0F, false, false, false));
      assertFalse(SpeedMinePolicy.shouldRotate(0.9F, 0.5F, 1.0F, false, false, false));
      assertTrue(SpeedMinePolicy.shouldRotate(0.9F, 0.95F, 1.0F, false, false, false));
      assertFalse(SpeedMinePolicy.shouldRotate(0.9F, 0.95F, 1.0F, true, true, false));
   }
}
