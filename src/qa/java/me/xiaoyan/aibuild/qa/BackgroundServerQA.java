package me.xiaoyan.aibuild.qa;
import me.xiaoyan.aibuild.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.item.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import java.nio.file.*;
import java.util.*;
/** Never packaged in the gameplay JAR. */
public final class BackgroundServerQA implements ModInitializer {
 public void onInitialize(){ServerTickEvents.END_SERVER_TICK.register(server->{
  var engine=BuildBridge.engine;if(engine==null||!(server instanceof OfflineServer))return;
  Path root=engine.bridge.getParent(),file=root.resolve("qa-background-server-control.json");
  if(!Files.exists(root.resolve(".aibuild-disposable-qa"))||!Files.exists(file))return;
  String id="unknown";try{
   var command=FilesIO.read(file);Files.delete(file);id=command.get("id").getAsString();String op=command.get("op").getAsString();
   var player=server.getPlayerList().getPlayer(engine.owner);var result=new LinkedHashMap<String,Object>();
   if(op.equals("put_item")){if(player==null)throw new IllegalStateException("no player");var stack=new ItemStack(Items.DIAMOND,20);stack.set(DataComponents.CUSTOM_NAME,Component.literal("后台身份与库存验收"));player.getInventory().setItem(0,stack);player.inventoryMenu.broadcastChanges();}
   result.put("players",server.getPlayerCount());result.put("uuid",engine.owner.toString());
   result.put("diamond",player==null?0:player.getInventory().getItem(0).getCount());
   result.put("name",player==null?"":String.valueOf(player.getInventory().getItem(0).get(DataComponents.CUSTOM_NAME)));
   FilesIO.atomic(root.resolve("qa-background-server-results").resolve(id+".json"),result);
  }catch(Exception e){try{FilesIO.atomic(root.resolve("qa-background-server-results").resolve(id+".json"),Map.of("error",e.toString()));}catch(Exception ignored){}}
 });}
}
