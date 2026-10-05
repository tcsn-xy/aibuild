package me.xiaoyan.aibuild.mixin;
import me.xiaoyan.aibuild.BuildBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
/** Only the host simulation keeps running; native client pause/input behavior stays intact. */
@Mixin(IntegratedServer.class)
public class IntegratedBackgroundMixin {
 @Redirect(method="tickServer",at=@At(value="INVOKE",target="Lnet/minecraft/client/Minecraft;isPaused()Z"))
 private boolean aibuild$hostPause(Minecraft mc){return !BuildBridge.background&&mc.isPaused();}
}
