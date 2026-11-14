/*
 * Adapted from Comet's SpeedmineRange (THM Addons, GPL-3.0-only) by Boyonabike on 2026-11-14.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.argus.client.modules.player;

import dev.argus.client.ClientAccess;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Reach measured the way the server does: from the eyes to the nearest point of the block's full cube.
 * Measuring to the centre is up to 0.87 blocks pessimistic.
 */
final class SpeedMineRange {
   private SpeedMineRange() {
   }

   static Vec3 nearestPoint(BlockPos pos, Vec3 from) {
      return new Vec3(
         Mth.clamp(from.x, pos.getX(), pos.getX() + 1.0), Mth.clamp(from.y, pos.getY(), pos.getY() + 1.0), Mth.clamp(from.z, pos.getZ(), pos.getZ() + 1.0)
      );
   }

   static Vec3 nearestPoint(BlockPos pos) {
      LocalPlayer player = ClientAccess.argusclientMinecraft.player;
      return player == null ? pos.getCenter() : nearestPoint(pos, player.getEyePosition());
   }

   static boolean isInRange(BlockPos pos, Vec3 from, double reach) {
      return from.distanceToSqr(nearestPoint(pos, from)) <= reach * reach;
   }

   static boolean isInRange(BlockPos pos, float reach) {
      LocalPlayer player = ClientAccess.argusclientMinecraft.player;
      return player != null && isInRange(pos, player.getEyePosition(), reach);
   }

   /** The block face most squarely facing the player's eyes; the closest wall when the eyes are inside the block. */
   static Direction nearestFace(BlockPos pos) {
      LocalPlayer player = ClientAccess.argusclientMinecraft.player;
      if (player == null) {
         return Direction.UP;
      }

      Vec3 from = player.getEyePosition();
      Direction best = Direction.UP;
      double bestOutside = -Double.MAX_VALUE;
      for (Direction dir : Direction.values()) {
         double sign = dir.getAxisDirection().getStep();
         double plane = dir.getAxis().choose(pos.getX(), pos.getY(), pos.getZ()) + (sign > 0 ? 1.0 : 0.0);
         double outside = (dir.getAxis().choose(from.x, from.y, from.z) - plane) * sign;
         if (outside > bestOutside) {
            bestOutside = outside;
            best = dir;
         }
      }

      return best;
   }
}
