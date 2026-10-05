package me.xiaoyan.aibuild.mixin;
import com.mojang.authlib.GameProfile;
import me.xiaoyan.aibuild.OfflineServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(net.minecraft.server.network.ServerLoginPacketListenerImpl.class)
public abstract class OfflineLoginMixin {
 @Shadow @Final private MinecraftServer server;
 @ModifyVariable(method="startClientVerification",at=@At("HEAD"),argsOnly=true)
 private GameProfile owner(GameProfile profile){return server instanceof OfflineServer background?new GameProfile(background.originalOwner,profile.name()):profile;}
}
