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
 @org.spongepowered.asm.mixin.Unique private long aibuild$lastRemote;
 @org.spongepowered.asm.mixin.Unique private boolean aibuild$remote;
 // Do not let transient OS focus changes open a menu during a bridge session.
 // Manual Esc invokes pauseGame directly and is deliberately left untouched.
 @Inject(method="pauseIfInactive",at=@At("HEAD"),cancellable=true)
 private void aibuild$noAutomaticMenu(CallbackInfo ci){if(BuildBridge.background||aibuild$remote)ci.cancel();}
 @Inject(method="runTick",at=@At("HEAD"))
 private void aibuild$load(boolean tick,CallbackInfo ci){
  Minecraft mc=(Minecraft)(Object)this;
  long now=System.nanoTime();if(now-aibuild$lastRemote>1_000_000_000L){aibuild$lastRemote=now;aibuild$remote=false;
   try{var current=mc.getCurrentServer();var file=mc.gameDirectory.toPath().resolve("aibuild-background/connection.json");if(mc.level!=null&&current!=null&&java.nio.file.Files.exists(file)){var descriptor=me.xiaoyan.aibuild.FilesIO.read(file);aibuild$remote=current.ip.equals("127.0.0.1:"+descriptor.get("port").getAsInt())&&java.lang.ProcessHandle.of(descriptor.get("pid").getAsLong()).map(java.lang.ProcessHandle::isAlive).orElse(false);}}catch(Exception ignored){}
  }
  InteractionBudget.frame(BuildBridge.background&&mc.isWindowActive()&&mc.gui.screen()==null,System.nanoTime());
 }
}
