package me.xiaoyan.aibuild.qa;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.worldselection.*;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.server.level.ServerPlayer;
import me.xiaoyan.aibuild.*;
import com.google.gson.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
/** Graphic client controller; external Python drives the production file protocol. */
public final class BridgeQA implements ClientModInitializer {
 private int stage=0;private long entered=System.nanoTime();private boolean started=false;private final List<Map<String,Object>> checks=new ArrayList<>();
 public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(this::tick);}
 private void check(String name,boolean value){if(!value)throw new IllegalStateException(name);checks.add(Map.of("check",name,"passed",true));System.out.println("BUILD_QA_PASS "+name);}
 private void go(int next){stage=next;entered=System.nanoTime();System.out.println("BUILD_QA_STAGE "+stage);}
 private double seconds(){return(System.nanoTime()-entered)/1e9;}
 private void report(Minecraft mc,String result)throws Exception{FilesIO.atomic(mc.gameDirectory.toPath().resolve("qa-client-report.json"),Map.of("status",result,"checks",checks));}
 private interface Action{void run()throws Exception;}
 private void server(Minecraft mc,Action action){mc.getSingleplayerServer().execute(()->{try{action.run();}catch(Throwable e){e.printStackTrace();try{FilesIO.atomic(mc.gameDirectory.toPath().resolve("qa-failed.json"),Map.of("error",e.toString()));}catch(Exception ignored){}}});}
 private ServerPlayer player(Minecraft mc){return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());}
 private boolean focusProbe=false;private long focusLast=0;private int focusSamples=0,focusGrabLosses=0,focusPauseLeaks=0;private double focusMaxGapMs=0,focusMaxYawDelta=0;
 private void tick(Minecraft mc){try{
  if(focusProbe&&mc.player!=null){long now=System.nanoTime();if(focusLast!=0)focusMaxGapMs=Math.max(focusMaxGapMs,(now-focusLast)/1e6);focusLast=now;focusSamples++;if(!mc.mouseHandler.isMouseGrabbed())focusGrabLosses++;if(mc.isPaused())focusPauseLeaks++;float before=mc.player.getYRot();mc.mouseHandler.onMove(mc.getWindow().handle(),mc.mouseHandler.xpos(),mc.mouseHandler.ypos(),2,0);mc.mouseHandler.handleAccumulatedMovement();focusMaxYawDelta=Math.max(focusMaxYawDelta,Math.abs(mc.player.getYRot()-before));}

  Path root=mc.gameDirectory.toPath();if(stage<99&&Files.exists(root.resolve("qa-failed.json")))throw new IllegalStateException(Files.readString(root.resolve("qa-failed.json")));
  if(stage==0&&mc.gui.screen() instanceof TitleScreen){
   check("render backend unchanged",mc.options.preferredGraphicsBackend().get().toString().toLowerCase().contains("vulkan"));
   // Pure boundary checks use actual production parser.
   for(String json:new String[]{"{\"version\":1,\"origin\":[0,80,0],\"blocks\":[{\"pos\":[0,0,0],\"state\":\"minecraft:stone\"},{\"pos\":[0,0,0],\"state\":\"minecraft:stone\"}]}","{\"version\":1,\"origin\":[0,80,0],\"blocks\":[{\"pos\":[64,0,0],\"state\":\"minecraft:stone\"}]}","{\"version\":1,\"origin\":[0,80,0],\"blocks\":[{\"pos\":[0.5,0,0],\"state\":\"minecraft:stone\"}]}"}){boolean rejected=false;try{Plan.parse(JsonParser.parseString(json).getAsJsonObject());}catch(IllegalArgumentException e){rejected=true;}check("invalid plan rejected "+checks.size(),rejected);}
   CreateWorldScreen.openFresh(mc,()->mc.gui.setScreen(new TitleScreen()));go(1);
  }else if(stage==1&&mc.gui.screen() instanceof CreateWorldScreen screen){screen.getUiState().setName("建筑桥接临时验收");screen.getUiState().setSeed("124");screen.getUiState().setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);screen.getUiState().setAllowCommands(true);Method create=screen.getClass().getDeclaredMethod("onCreate");create.setAccessible(true);create.invoke(screen);go(2);
  }else if(stage==2&&mc.level!=null&&mc.player!=null&&seconds()>3){
   server(mc,()->{var p=player(mc);p.teleportTo(p.level(),8,150,8,Set.of(),0,0,true);p.getAbilities().flying=true;p.onUpdateAbilities();for(int z=-4;z<60;z++)for(int x=-4;x<60;x++)p.level().setBlock(new BlockPos(x,149,z),Blocks.STONE.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);BuildBridge.connect(p,new BlockPos(0,150,0));worldName=Path.of(BuildBridge.engine.world).getFileName().toString();started=true;});go(3);
  }else if(stage==3&&started&&seconds()>2){mc.gui.setScreen(new PauseScreen(true));go(8);
  }else if(stage==8&&seconds()>.7){check("client keeps native pause semantics",mc.isPaused());go(4);FilesIO.atomic(root.resolve("qa-ready.json"),Map.of("status","ready"));
  }else if(stage==4){
   Path control=root.resolve("qa-control.json");if(Files.exists(control)){JsonObject command=FilesIO.read(control);Files.delete(control);String op=command.get("op").getAsString();String id=command.get("id").getAsString();
    if(op.equals("focus_begin")){mc.gui.setScreen(null);mc.mouseHandler.grabMouse();focusProbe=true;focusLast=0;focusSamples=0;focusGrabLosses=0;focusPauseLeaks=0;focusMaxGapMs=0;focusMaxYawDelta=0;ack(root,id,Map.of("ok",true));}
    else if(op.equals("focus_end")){focusProbe=false;ack(root,id,Map.of("samples",focusSamples,"max_gap_ms",focusMaxGapMs,"max_yaw_delta",focusMaxYawDelta,"grab_losses",focusGrabLosses,"pause_leaks",focusPauseLeaks,"focused",mc.isWindowActive()));}
    else if(op.equals("auto_pause_probe")){
     mc.gui.setScreen(null);
     var window=mc.getWindow();Field focused=window.getClass().getDeclaredField("focused");focused.setAccessible(true);boolean wasFocused=focused.getBoolean(window);
     Field active=Minecraft.class.getDeclaredField("lastActiveTime");active.setAccessible(true);long prior=active.getLong(mc);boolean option=mc.options.pauseOnLostFocus;
     boolean opened;
     try{mc.options.pauseOnLostFocus=true;focused.setBoolean(window,false);active.setLong(mc,net.minecraft.util.Util.getMillis()-1000);Method auto=Minecraft.class.getDeclaredMethod("pauseIfInactive");auto.setAccessible(true);auto.invoke(mc);opened=mc.gui.screen() instanceof PauseScreen;}
     finally{focused.setBoolean(window,wasFocused);active.setLong(mc,prior);mc.options.pauseOnLostFocus=option;}
     ack(root,id,Map.of("opened",opened,"bridge_connected",BuildBridge.background));mc.gui.setScreen(new PauseScreen(true));
    }
    else if(op.equals("manual_pause_probe")){mc.gui.setScreen(null);mc.pauseGame(false);ack(root,id,Map.of("opened",mc.gui.screen() instanceof PauseScreen));}
    else if(op.equals("pause_state")){Field f=Minecraft.class.getDeclaredField("pause");f.setAccessible(true);ack(root,id,Map.of("getter",mc.isPaused(),"field",f.getBoolean(mc)));}
    else if(op.equals("pause_screen")){mc.gui.setScreen(new PauseScreen(true));ack(root,id,Map.of("paused",mc.isPaused(),"mouse_grabbed",mc.mouseHandler.isMouseGrabbed()));}
    else if(op.equals("disconnect")){mc.gui.setScreen(new PauseScreen(true));server(mc,()->BuildBridge.disconnect());go(5);}
    else if(op.equals("reconnect")){mc.gui.setScreen(null);server(mc,()->{BuildBridge.connect(player(mc),new BlockPos(0,150,0));ack(root,id,Map.of("ok",true));});}
    else if(op.equals("world_exit")){mc.disconnectWithSavingScreen();go(6);}
    else if(op.equals("screenshot")){int[] q=Plan.triple(command.get("pos"));float yaw=command.has("yaw")?command.get("yaw").getAsFloat():0;float pitch=command.has("pitch")?command.get("pitch").getAsFloat():25;mc.gui.setScreen(null);server(mc,()->{var p=player(mc);p.teleportTo(p.level(),q[0],q[1],q[2],Set.of(),yaw,pitch,true);});pendingShot=id;shotTime=System.nanoTime();}
    else server(mc,()->{
     var level=player(mc).level();int[] q=command.has("pos")?Plan.triple(command.get("pos")):new int[]{0,150,0};BlockPos pos=new BlockPos(q[0],q[1],q[2]);
     switch(op){
      case "force_chunk"->{level.setChunkForced(q[0]>>4,q[2]>>4,command.get("enabled").getAsBoolean());ack(root,id,Map.of("forced",level.getForceLoadedChunks().size()));}
      case "chunk_state"->{var cp=new net.minecraft.world.level.ChunkPos(q[0]>>4,q[2]>>4);ack(root,id,Map.of("loaded",level.getChunkSource().getChunkNow(cp.x(),cp.z())!=null,"entities_ready",level.areEntitiesActuallyLoadedAndTicking(cp),"player",Engine.coords(player(mc).blockPosition()),"forced",level.getForceLoadedChunks().size(),"lease",BuildBridge.engine==null?Map.of():BuildBridge.engine.chunks.status()));}
      case "move_player"->{var p=player(mc);p.teleportTo(level,q[0],q[1],q[2],Set.of(),p.getYRot(),p.getXRot(),true);ack(root,id,Map.of("ok",true));}
      case "lease_timeout"->{Field f=ChunkLease.class.getDeclaredField("started");f.setAccessible(true);f.setLong(BuildBridge.engine.chunks,System.nanoTime()-61_000_000_000L);ack(root,id,Map.of("ok",true));}
      case "expire_check"->{var e=BuildBridge.engine;Field f=Engine.class.getDeclaredField("prepared");f.setAccessible(true);Object p=f.get(e);if(p==null){f=Transactions.class.getDeclaredField("prepared");f.setAccessible(true);p=f.get(e.transactions);}if(p!=null){f=p.getClass().getDeclaredField("expires");f.setAccessible(true);f.setLong(p,0);}ack(root,id,Map.of("ok",true));}
      case "cart_item"->{var box=new net.minecraft.world.phys.AABB(pos.getX(),pos.getY(),pos.getZ(),pos.getX()+19,pos.getY()+20,pos.getZ()+13);var cart=level.getEntitiesOfClass(net.minecraft.world.entity.vehicle.minecart.MinecartHopper.class,box).getFirst();cart.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT,7));ack(root,id,Map.of("uuid",cart.getUUID().toString()));}
      case "fill_container"->{var c=(net.minecraft.world.Container)level.getBlockEntity(pos);for(int i=0;i<c.getContainerSize();i++){var stack=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,i+1);stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,Component.literal("搬迁槽位"+i));c.setItem(i,stack);}c.setChanged();ack(root,id,Map.of("ok",true));}
      case "be_snapshot"->{var be=level.getBlockEntity(pos);ack(root,id,Map.of("nbt",be==null?"":be.saveWithFullMetadata(level.registryAccess()).toString()));}
      case "open_container"->{player(mc).openMenu(level.getBlockState(pos).getMenuProvider(level,pos));ack(root,id,Map.of("open",player(mc).containerMenu!=player(mc).inventoryMenu));}
      case "close_container"->{player(mc).closeContainer();ack(root,id,Map.of("ok",true));}
      case "menu_open"->ack(root,id,Map.of("open",player(mc).containerMenu!=player(mc).inventoryMenu));
      case "frame"->{var frame=new net.minecraft.world.entity.decoration.ItemFrame(level,pos,net.minecraft.core.Direction.SOUTH);frame.setItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));level.addFreshEntity(frame);ack(root,id,Map.of("uuid",frame.getUUID().toString()));}
      case "entity_snapshot"->{var entity=level.getEntity(java.util.UUID.fromString(command.get("uuid").getAsString()));var out=net.minecraft.world.level.storage.TagValueOutput.createWithContext(new net.minecraft.util.ProblemReporter.Collector(),level.registryAccess());if(entity!=null)entity.save(out);ack(root,id,Map.of("nbt",out.buildResult().toString(),"exists",entity!=null,"pos",entity==null?java.util.List.of():java.util.List.of(entity.getX(),entity.getY(),entity.getZ())));}
      case "guarded_transfer"->{var from=(net.minecraft.world.Container)level.getBlockEntity(pos);var stack=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,3);stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,Component.literal("搬迁槽位0"));var left=net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(null,from,stack,net.minecraft.core.Direction.UP);ack(root,id,Map.of("remaining",left.getCount()));}
      case "set"->{var state=net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),command.get("state").getAsString(),false).blockState();level.setBlock(pos,state,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);ack(root,id,Map.of("ok",true));}
      case "verify"->{JsonObject p=command.getAsJsonObject("plan");Plan plan=Plan.parse(p);int mismatches=0;for(var b:plan.blocks()){int[] local=plan.rotated(b);BlockPos at=new BlockPos(plan.origin()[0]+local[0],plan.origin()[1]+local[1],plan.origin()[2]+local[2]);var expected=net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),b.state(),false).blockState().rotate(switch(plan.rotation()){case 90->net.minecraft.world.level.block.Rotation.CLOCKWISE_90;case 180->net.minecraft.world.level.block.Rotation.CLOCKWISE_180;case 270->net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;default->net.minecraft.world.level.block.Rotation.NONE;});if(!level.getBlockState(at).equals(expected))mismatches++;}ack(root,id,Map.of("ok",mismatches==0,"mismatches",mismatches));}
      case "get"->ack(root,id,Map.of("state",Engine.describe(level.getBlockState(pos)),"entity",level.getBlockEntity(pos)!=null));
      case "allow"->{mc.getSingleplayerServer().getWorldData().setAllowCommands(true);ack(root,id,Map.of("ok",true));}
      case "door"->{var state=level.getBlockState(pos);state.useWithoutItem(level,player(mc),new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),net.minecraft.core.Direction.NORTH,pos,false));ack(root,id,Map.of("open",level.getBlockState(pos).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN),"upper_open",level.getBlockState(pos.above()).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)));}
      case "walkable"->{int collisions=0;for(var v:command.getAsJsonArray("points")){var a=v.getAsJsonArray();double x=a.get(0).getAsDouble(),y=a.get(1).getAsDouble(),z=a.get(2).getAsDouble();if(!level.noCollision(player(mc),new net.minecraft.world.phys.AABB(x-.29,y+.01,z-.29,x+.29,y+1.8,z+.29)))collisions++;}ack(root,id,Map.of("ok",collisions==0,"collisions",collisions));}
      case "chest_item"->{((net.minecraft.world.Container)level.getBlockEntity(pos)).setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));ack(root,id,Map.of("ok",true));}
      case "publish"->{int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}boolean result=mc.getSingleplayerServer().publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN,false,port);ack(root,id,Map.of("ok",result,"published",mc.getSingleplayerServer().isPublished()));}
      case "unpublish"->{ack(root,id,Map.of("ok",mc.getSingleplayerServer().unpublishServer()));}
      case "reconnect_result"->{try{BuildBridge.connect(player(mc),new BlockPos(0,150,0));ack(root,id,Map.of("ok",true));}catch(Exception e){ack(root,id,Map.of("ok",false,"error",e.getMessage()));}}
      case "guest_connect"->{var guest=new net.minecraft.server.level.ServerPlayer(mc.getSingleplayerServer(),level,new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"BridgeGuest"),net.minecraft.server.level.ClientInformation.createDefault());try{BuildBridge.connect(guest,new BlockPos(0,150,0));ack(root,id,Map.of("ok",true));}catch(Exception e){ack(root,id,Map.of("ok",false,"error",e.getMessage()));}}
      case "put_item"->{var c=(net.minecraft.world.Container)level.getBlockEntity(pos);var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(command.get("item").getAsString()));c.setItem(command.get("slot").getAsInt(),new net.minecraft.world.item.ItemStack(item,command.get("count").getAsInt()));c.setChanged();ack(root,id,Map.of("ok",true));}
      case "feed_furnace"->{var container=(net.minecraft.world.Container)level.getBlockEntity(pos);container.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.RAW_IRON));container.setItem(1,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL));ack(root,id,Map.of("ok",true));}
      case "inventory"->{var container=(net.minecraft.world.Container)level.getBlockEntity(pos);java.util.Map<String,Integer> items=new java.util.TreeMap<>();for(int slot=0;slot<container.getContainerSize();slot++){var stack=container.getItem(slot);if(!stack.isEmpty())items.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getCount(),Integer::sum);}ack(root,id,items);}
      case "cart_count"->{var box=new net.minecraft.world.phys.AABB(45,148,20,58,154,32);ack(root,id,Map.of("count",level.getEntitiesOfClass(net.minecraft.world.entity.vehicle.minecart.MinecartHopper.class,box).size()));}
      case "entity"->{var e=level.getEntity(java.util.UUID.fromString(command.get("uuid").getAsString()));ack(root,id,Map.of("exists",e!=null,"type",e==null?"none":net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString()));}
      case "activate"->{var state=level.getBlockState(pos);state.useWithoutItem(level,player(mc),new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),net.minecraft.core.Direction.NORTH,pos,false));ack(root,id,Map.of("state",Engine.describe(level.getBlockState(pos))));}
      case "region_stats"->{int[] sz=Plan.triple(command.get("size"));var box=new net.minecraft.world.phys.AABB(pos.getX(),pos.getY(),pos.getZ(),pos.getX()+sz[0],pos.getY()+sz[1],pos.getZ()+sz[2]);var inventory=new java.util.TreeMap<String,Integer>();var blocks=new java.util.TreeMap<String,Integer>();var containers=new java.util.ArrayList<Object>();for(BlockPos at:BlockPos.betweenClosed(pos,pos.offset(sz[0]-1,sz[1]-1,sz[2]-1))){String block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).toString();blocks.merge(block,1,Integer::sum);if(level.getBlockEntity(at) instanceof net.minecraft.world.Container c){var contents=new java.util.TreeMap<String,Integer>();for(int slot=0;slot<c.getContainerSize();slot++){var stack=c.getItem(slot);if(!stack.isEmpty()){String item=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();inventory.merge(item,stack.getCount(),Integer::sum);contents.merge(item,stack.getCount(),Integer::sum);}}containers.add(Map.of("pos",Engine.coords(at),"items",contents));}}var entities=new java.util.ArrayList<Object>();for(var ent:level.getEntities((net.minecraft.world.entity.Entity)null,box,x->!(x instanceof net.minecraft.world.entity.player.Player))){entities.add(Map.of("id",ent.getUUID().toString(),"type",net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType()).toString(),"pos",java.util.List.of(ent.getX(),ent.getY(),ent.getZ())));if(ent instanceof net.minecraft.world.entity.item.ItemEntity item){var stack=item.getItem();inventory.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getCount(),Integer::sum);}if(ent instanceof net.minecraft.world.Container c)for(int slot=0;slot<c.getContainerSize();slot++){var stack=c.getItem(slot);if(!stack.isEmpty())inventory.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getCount(),Integer::sum);}}ack(root,id,Map.of("items",inventory,"blocks",blocks,"containers",containers,"entities",entities,"time",level.getGameTime()));}
      case "remove_stock"->{int[] sz=Plan.triple(command.get("size"));for(BlockPos at:BlockPos.betweenClosed(pos,pos.offset(sz[0]-1,sz[1]-1,sz[2]-1))){if(level.getBlockEntity(at) instanceof net.minecraft.world.Container c){for(int slot=0;slot<c.getContainerSize();slot++){var stack=c.getItem(slot);String name=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();if(name.endsWith("_carpet")||name.equals("minecraft:glass"))c.setItem(slot,net.minecraft.world.item.ItemStack.EMPTY);}}}var box=new net.minecraft.world.phys.AABB(pos.getX(),pos.getY(),pos.getZ(),pos.getX()+sz[0],pos.getY()+sz[1],pos.getZ()+sz[2]);for(var ent:level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,box))ent.discard();ack(root,id,Map.of("ok",true));}
      case "time"->ack(root,id,Map.of("time",level.getGameTime()));
      case "restrict"->{mc.getSingleplayerServer().getWorldData().setAllowCommands(false);ack(root,id,Map.of("ok",true));}
      default->throw new IllegalArgumentException(op);
     }
    });
   }
   if(pendingShot!=null&&(System.nanoTime()-shotTime)>3_000_000_000L){String name=pendingShot;Screenshot.grab(mc.gameDirectory,name+".png",mc.gameRenderer.mainRenderTarget(),1,c->{});ack(root,name,Map.of("ok",true));pendingShot=null;}
   if(Files.exists(root.resolve("qa-finish.json"))){mc.disconnectWithSavingScreen();go(99);report(mc,"passed");mc.stop();}
  }else if(stage==5&&BuildBridge.engine==null&&seconds()>1){check("disconnect restores pause",mc.isPaused());ack(root,"disconnect",Map.of("ok",true));go(4);
  }else if(stage==6&&mc.getSingleplayerServer()==null&&seconds()>2){check("exit clears background",!BuildBridge.background);mc.gui.setScreen(new TitleScreen());mc.createWorldOpenFlows().openWorld(worldName,()->mc.gui.setScreen(new TitleScreen()));go(7);
  }else if(stage==7&&mc.player!=null&&mc.level!=null&&seconds()>2){server(mc,()->{BuildBridge.connect(player(mc),new BlockPos(0,150,0));ack(root,"world_exit",Map.of("ok",true));});go(4);}
  if(stage<99&&seconds()>600)throw new IllegalStateException("QA timeout state "+stage);
 }catch(Throwable e){e.printStackTrace();try{FilesIO.atomic(mc.gameDirectory.toPath().resolve("qa-failed.json"),Map.of("error",e.toString()));report(mc,"failed");}catch(Exception ignored){}mc.stop();stage=99;}}
 private String pendingShot,worldName;private long shotTime;
 private void ack(Path root,String id,Object data){try{FilesIO.atomic(root.resolve("qa-control-results").resolve(id+".json"),data);}catch(Exception e){throw new RuntimeException(e);}}
}
