package me.xiaoyan.aibuild.mixin;
import me.xiaoyan.aibuild.OfflineServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
/** Preserve owner permissions/UUID, but never treat a network disconnect as server shutdown. */
@Mixin(net.minecraft.server.network.ServerCommonPacketListenerImpl.class)
public abstract class OfflineListenerMixin {
 @Redirect(method="onDisconnect",at=@At(value="INVOKE",target="Lnet/minecraft/server/MinecraftServer;halt(Z)V"))
 private void persistentBackend(MinecraftServer server,boolean wait){if(!(server instanceof OfflineServer))server.halt(wait);}
}
