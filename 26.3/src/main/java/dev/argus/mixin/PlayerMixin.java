package dev.argus.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.authlib.GameProfile;
import dev.argus.client.ClientAccess;
import dev.argus.client.ClientContext;
import dev.argus.client.commands.FakeplayerCommand;
import dev.argus.client.events.EventStage;
import dev.argus.client.events.PlayerAttackEvent;
import dev.argus.client.events.PlayerIsStayingOnGroundSurfaceEvent;
import dev.argus.client.managers.ModuleRegistry;
import dev.argus.client.managers.PlayerTickExecutor;
import dev.argus.client.modules.exploit.ReachModule;
import dev.argus.client.modules.misc.SocialEntry;
import dev.argus.client.modules.movement.NoSlowModule;
import dev.argus.client.modules.movement.SprintModule;
import dev.argus.client.modules.player.ScaffoldModule;
import dev.argus.client.settings.options.SocialRole;
import dev.argus.client.utils.FloatAnimation;
import dev.argus.client.utils.entity.EntityUtils;
import dev.argus.client.utils.entity.PlayerState;
import dev.argus.client.utils.world.BlockInteractionUtils;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin extends LivingEntity implements PlayerState {
   private static ScaffoldModule scaffoldModule = ClientContext.getModule(ScaffoldModule.class);
   private static SprintModule sprintModule = ClientContext.getModule(SprintModule.class);
   private static ReachModule reachModule = ClientContext.getModule(ReachModule.class);
   private static NoSlowModule noSlowModule = ClientContext.getModule(NoSlowModule.class);
   @Unique
   private long lastCrawlingTime;
   @Unique
   private long lastEatingTime;
   @Unique
   private SocialRole socialRole;
   @Unique
   private boolean nextToWall;
   @Unique
   private int lastAttackedTicksServer;
   @Unique
   private FloatAnimation healthAnimation;

   @Shadow
   public abstract Abilities getAbilities();

   protected PlayerMixin(EntityType entityType, Level level) {
      super(entityType, level);
   }

   @Inject(method = "<init>", at = @At("TAIL"))
   private void initializePlayerState(Level level, GameProfile profile, CallbackInfo callback) {
      this.healthAnimation = new FloatAnimation();
      this.healthAnimation.setValue(EntityUtils.getHealthFraction(this));

      for (Object entry : ClientContext.socialRegistry.getRegistry()) {
         SocialEntry socialEntry = (SocialEntry)entry;
         if (profile.name().equalsIgnoreCase(socialEntry.getName())) {
            this.argusclient$setRole(socialEntry.role());
            break;
         }
      }
   }

   @Inject(method = "tick", at = @At("HEAD"))
   private void updatePlayerState(CallbackInfo callback) {
      this.healthAnimation.setTarget(EntityUtils.getHealthFraction(this), 100L);
      if (this.getBoundingBox().getYsize() < 1.0) {
         this.lastCrawlingTime = System.currentTimeMillis();
      }

      this.nextToWall = !this.level().noCollision(this.getBoundingBox().inflate(1.0E-4, 0.0, 1.0E-4));
      if (!this.isUsingItem() || this.getItemUseProgress(this.getUseItem(), this) > 1.0F) {
         this.lastEatingTime = System.currentTimeMillis();
      }
   }

   @Inject(method = "isStayingOnGroundSurface", at = @At("HEAD"), cancellable = true)
   private void onIsStayingOnGroundSurface(CallbackInfoReturnable<Boolean> callback) {
      if (ClientAccess.argusclientMinecraft.player != null) {
         PlayerIsStayingOnGroundSurfaceEvent event = new PlayerIsStayingOnGroundSurfaceEvent();
         ClientAccess.eventBus.post(event);
         if (event.isCancelled()) {
            callback.setReturnValue(true);
         }
      }
   }

   @ModifyExpressionValue(method = "maybeBackOffFromEdge", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;maxUpStep()F"))
   private float modifySafeWalkStepHeight(float originalHeight) {
      if (ClientAccess.argusclientMinecraft.player == null) {
         return originalHeight;
      }

      if (scaffoldModule.isToggled() && scaffoldModule.safeWalkSetting.getValue()) {
         if (!(ClientAccess.argusclientMinecraft.player.getItemInHand(InteractionHand.MAIN_HAND).getItem() instanceof BlockItem blockItem)) {
            return originalHeight;
         } else {
            VoxelShape collisionShape = blockItem.getBlock().defaultBlockState().getCollisionShape(ClientAccess.argusclientMinecraft.level, BlockPos.ZERO);
            return collisionShape != null && !collisionShape.isEmpty() ? (float)collisionShape.bounds().getYsize() : originalHeight;
         }
      } else {
         return originalHeight;
      }
   }

   @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
   public void onAttackPre(Entity target, CallbackInfo callback) {
      PlayerAttackEvent event = new PlayerAttackEvent(EventStage.Pre, target);
      ClientAccess.eventBus.post(event);
      if (event.isCancelled()) {
         callback.cancel();
      }
   }

   @Inject(method = "attack", at = @At("TAIL"))
   private void onAttackPost(Entity attackedEntity, CallbackInfo callback) {
      PlayerAttackEvent event = new PlayerAttackEvent(EventStage.Post, attackedEntity);
      ClientAccess.eventBus.post(event);
   }

   @WrapWithCondition(
      method = "causeExtraKnockback",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V")
   )
   private boolean allowAttackMovementSlowdown(Player player, Vec3 movement) {
      return !sprintModule.isToggled() || !sprintModule.keepSetting.getValue();
   }

   @WrapWithCondition(method = "causeExtraKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;setSprinting(Z)V"))
   private boolean allowAttackSprintReset(Player player, boolean sprinting) {
      return !sprintModule.isToggled() || !sprintModule.keepSetting.getValue();
   }

   @WrapWithCondition(method = "causeExtraKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V"))
   private boolean allowSimulatedPlayerKnockback(LivingEntity entity, double strength, double directionX, double directionZ, net.minecraft.world.damagesource.DamageSource source, float extraKnockback, boolean ignoredFlag) {
      return !((Object)this instanceof FakeplayerCommand.SimulatedPlayer) || entity != ClientAccess.argusclientMinecraft.player;
   }

   /** SpeedMine's air miner: mine at ground speed while airborne (adapted from Quiettee Utils via Comet, GPL-3.0). */
   @ModifyReturnValue(method = "getDestroySpeed", at = @At("RETURN"))
   private float liftAirMiningPenalty(float speed, net.minecraft.world.level.block.state.BlockState state) {
      if ((Object)this != ClientAccess.argusclientMinecraft.player) {
         return speed;
      }

      dev.argus.client.modules.player.SpeedMineModule speedMine = ClientContext.getModule(dev.argus.client.modules.player.SpeedMineModule.class);
      return speedMine != null && speedMine.liftsAirMiningPenalty() ? speed * 5.0F : speed;
   }

   @ModifyReturnValue(method = "entityInteractionRange", at = @At("RETURN"))
   private double overrideEntityInteractionRange(double original) {
      return reachModule.entityReach(original);
   }

   @ModifyReturnValue(method = "blockInteractionRange", at = @At("RETURN"))
   private double overrideBlockInteractionRange(double original) {
      return reachModule.blockReach(original);
   }

   @ModifyReturnValue(method = "isSecondaryUseActive", at = @At("RETURN"))
   private boolean forceSecondaryUseWhilePlacing(boolean original) {
      return original || BlockInteractionUtils.isPlacingBlock;
   }

   @Inject(method = "resetOnlyAttackStrengthTicker", at = @At("HEAD"))
   private void resetServerAttackTicks(CallbackInfo callback) {
      this.argusclient$resetLastAttackedServer();
   }

   @Inject(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Player;attackStrengthTicker:I", ordinal = 0, opcode = 180))
   private void incrementServerAttackTicks(CallbackInfo callback) {
      if (!PlayerTickExecutor.isRunningExtraTick()) {
         this.lastAttackedTicksServer++;
      }
   }

   @ModifyReturnValue(method = "getSpeed", at = @At("RETURN"))
   private float applyNoSlowWalkingSpeed(float originalSpeed) {
      if (!this.level().isClientSide()) {
         return originalSpeed;
      }

      if (noSlowModule.isToggled() && noSlowModule.slownessSetting.getValue()) {
         float walkingSpeed = this.getAbilities().getWalkingSpeed();
         if (originalSpeed >= walkingSpeed) {
            return originalSpeed;
         } else {
            return this.isSprinting() ? walkingSpeed * 1.3000001F : walkingSpeed;
         }
      } else {
         return originalSpeed;
      }
   }

   @Override
   public long argusclient$getLastEatingTime() {
      return this.lastEatingTime;
   }

   @Override
   public SocialRole argusclient$getRole() {
      return this.socialRole;
   }

   @Override
   public void argusclient$setRole(SocialRole role) {
      this.socialRole = role;
   }

   @Override
   public float argusclient$getHealthFactor() {
      return this.healthAnimation.getValue();
   }

   @Override
   public boolean argusclient$isNextToWall() {
      return this.nextToWall;
   }

   @Override
   public boolean argusclient$wasCrawling() {
      return System.currentTimeMillis() - this.lastCrawlingTime <= 150L;
   }

   @Override
   public int argusclient$getLastAttackedTicksServer() {
      return this.lastAttackedTicksServer;
   }

   @Override
   public void argusclient$resetLastAttackedServer() {
      this.lastAttackedTicksServer = 0;
   }

   @Unique
   private static void argusclient$bindPlayerModules(ModuleRegistry moduleRegistry) {
      scaffoldModule = (ScaffoldModule)moduleRegistry.getModule(ScaffoldModule.class);
      sprintModule = (SprintModule)moduleRegistry.getModule(SprintModule.class);
      reachModule = (ReachModule)moduleRegistry.getModule(ReachModule.class);
      noSlowModule = (NoSlowModule)moduleRegistry.getModule(NoSlowModule.class);
   }
}
