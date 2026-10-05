package me.xiaoyan.aibuild.qa;
import me.xiaoyan.aibuild.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import java.nio.file.*;
import java.util.Map;
/** Explicit disposable client only; selects the locked world through the real UI flow. */
public final class BackgroundQA implements ClientModInitializer {
 private boolean started;private int joins;
 public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(this::tick);}
 private void tick(Minecraft mc){try{
  Path root=mc.gameDirectory.toPath();if(!Files.exists(root.resolve(".aibuild-disposable-qa")))throw new IllegalStateException("not disposable");
  String world=System.getProperty("aibuild.qa.background");
  if(!started&&mc.gui.screen() instanceof TitleScreen){started=true;mc.createWorldOpenFlows().openWorld(world,()->mc.gui.setScreen(new TitleScreen()));}
  if(mc.level!=null&&mc.player!=null&&joins==0){joins=1;FilesIO.atomic(root.resolve("qa-background-ready.json"),Map.of("uuid",mc.player.getUUID().toString(),"integrated",mc.getSingleplayerServer()!=null,"backend",mc.options.preferredGraphicsBackend().get().toString()));}
  Path file=root.resolve("qa-background-client-control.json");
  if(Files.exists(file)){
   var command=FilesIO.read(file);Files.delete(file);String op=command.get("op").getAsString();String id=command.get("id").getAsString();
   switch(op){
    case "leave"->{mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("后台验收退出"));mc.gui.setScreen(new TitleScreen());}
    case "join"->mc.createWorldOpenFlows().openWorld(world,()->mc.gui.setScreen(new TitleScreen()));
    case "inspect"->{FilesIO.atomic(root.resolve("qa-background-client-results").resolve(id+".json"),Map.of("playing",mc.level!=null,"uuid",mc.player==null?"":mc.player.getUUID().toString(),"diamond",mc.player==null?0:mc.player.getInventory().getItem(0).getCount()));return;}
    case "finish"->{mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("后台验收退出"));mc.stop();}
    default->throw new IllegalArgumentException(op);
   }
   FilesIO.atomic(root.resolve("qa-background-client-results").resolve(id+".json"),Map.of("ok",true));
  }
 }catch(Exception e){e.printStackTrace();try{FilesIO.atomic(mc.gameDirectory.toPath().resolve("qa-failed.json"),Map.of("error",e.toString()));}catch(Exception ignored){}mc.stop();}}
}
