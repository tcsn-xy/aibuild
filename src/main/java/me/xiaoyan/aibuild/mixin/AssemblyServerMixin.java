package me.xiaoyan.aibuild.mixin;
import me.xiaoyan.aibuild.AssemblyGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerLevel.class)
public abstract class AssemblyServerMixin {
 @Inject(method="tickBlock",at=@At("HEAD"),cancellable=true)
 private void block(BlockPos p,Block block,CallbackInfo ci){ServerLevel level=(ServerLevel)(Object)this;if(AssemblyGuard.contains(level,p)){level.scheduleTick(p,block,1);ci.cancel();}}
 @Inject(method="tickFluid",at=@At("HEAD"),cancellable=true)
 private void fluid(BlockPos p,Fluid fluid,CallbackInfo ci){ServerLevel level=(ServerLevel)(Object)this;if(AssemblyGuard.contains(level,p)){level.scheduleTick(p,fluid,1);ci.cancel();}}
 @Inject(method="tickNonPassenger",at=@At("HEAD"),cancellable=true)
 private void entity(Entity entity,CallbackInfo ci){if(!(entity instanceof Player)&&AssemblyGuard.entity(entity))ci.cancel();}
}
