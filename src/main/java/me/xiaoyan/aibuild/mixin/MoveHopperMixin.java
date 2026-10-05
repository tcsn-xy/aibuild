package me.xiaoyan.aibuild.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.*;
import me.xiaoyan.aibuild.MoveGuard;
@Mixin(HopperBlockEntity.class)
public abstract class MoveHopperMixin {
 @Inject(method="addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",at=@At("HEAD"),cancellable=true)
 private static void insert(Container from,Container to,ItemStack item,Direction side,CallbackInfoReturnable<ItemStack> ci){if(MoveGuard.container(from)||MoveGuard.container(to))ci.setReturnValue(item);}
 @Inject(method="tryTakeInItemFromSlot",at=@At("HEAD"),cancellable=true)
 private static void take(Hopper to,Container from,int slot,Direction side,CallbackInfoReturnable<Boolean> ci){if(MoveGuard.container(from))ci.setReturnValue(false);}
}
