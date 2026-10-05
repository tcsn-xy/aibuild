package me.xiaoyan.aibuild.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.world.*;
@Mixin(CompoundContainer.class)
public interface CompoundContainerAccess {
 @Accessor("container1") Container aibuild$first();
 @Accessor("container2") Container aibuild$second();
}
