package me.xiaoyan.aibuild.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.inventory.*;
import net.minecraft.world.entity.player.Player;
import me.xiaoyan.aibuild.MoveGuard;
@Mixin(AbstractContainerMenu.class)
public abstract class MoveMenuMixin {
 @Inject(method="clicked",at=@At("HEAD"),cancellable=true)
 private void click(int slot,int button,ContainerInput input,Player p,CallbackInfo ci){if(MoveGuard.menu(p))ci.cancel();}
}
