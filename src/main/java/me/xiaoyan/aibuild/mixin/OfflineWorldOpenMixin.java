package me.xiaoyan.aibuild.mixin;
import me.xiaoyan.aibuild.BackgroundConnection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(net.minecraft.client.gui.screens.worldselection.WorldOpenFlows.class)
public abstract class OfflineWorldOpenMixin {
 @Inject(method="openWorld",at=@At("HEAD"),cancellable=true)
 private void joinBackground(String id,Runnable fallback,CallbackInfo ci){
  Minecraft client=Minecraft.getInstance();var match=BackgroundConnection.find(client.gameDirectory.toPath().toAbsolutePath().normalize(),id);
  if(match.isPresent()){
   String address="127.0.0.1:"+match.get().get("port").getAsInt();
   ConnectScreen.startConnecting(client.gui.screen()==null?new TitleScreen():client.gui.screen(),client,ServerAddress.parseString(address),new ServerData("本机后台世界",address,ServerData.Type.OTHER),false,null);ci.cancel();
  }
 }
}
