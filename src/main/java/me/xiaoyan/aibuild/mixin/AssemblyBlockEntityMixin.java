package me.xiaoyan.aibuild.mixin;
import me.xiaoyan.aibuild.AssemblyGuard;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class AssemblyBlockEntityMixin {
 @Shadow @Final private BlockEntity blockEntity;
 @Inject(method="tick",at=@At("HEAD"),cancellable=true)
 private void tick(CallbackInfo ci){if(AssemblyGuard.contains(blockEntity.getLevel(),blockEntity.getBlockPos()))ci.cancel();}
}
