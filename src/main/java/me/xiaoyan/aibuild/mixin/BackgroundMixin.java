package me.xiaoyan.aibuild.mixin;
import me.xiaoyan.aibuild.BuildBridge;
import me.xiaoyan.aibuild.InteractionBudget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public class BackgroundMixin {
 // Do not let transient OS focus changes open a menu during a bridge session.
 // Manual Esc invokes pauseGame directly and is deliberately left untouched.
 @Inject(method="pauseIfInactive",at=@At("HEAD"),cancellable=true)
 private void aibuild$noAutomaticMenu(CallbackInfo ci){if(BuildBridge.background)ci.cancel();}
 @Inject(method="runTick",at=@At("HEAD"))
 private void aibuild$load(boolean tick,CallbackInfo ci){
  Minecraft mc=(Minecraft)(Object)this;
  InteractionBudget.frame(BuildBridge.background&&mc.isWindowActive()&&mc.gui.screen()==null,System.nanoTime());
 }
}
