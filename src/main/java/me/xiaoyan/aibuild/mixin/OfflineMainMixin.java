package me.xiaoyan.aibuild.mixin;
import me.xiaoyan.aibuild.OfflineMain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(net.minecraft.server.Main.class)
public abstract class OfflineMainMixin {
 @Inject(method="main",at=@At(value="INVOKE",target="Lnet/minecraft/util/Util;startTimerHackThread()V",shift=At.Shift.AFTER),cancellable=true)
 private static void background(String[] args,CallbackInfo ci){String config=System.getProperty("aibuild.background.config");if(config!=null){OfflineMain.start(java.nio.file.Path.of(config));ci.cancel();}}
}
