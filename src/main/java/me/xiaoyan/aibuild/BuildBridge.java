package me.xiaoyan.aibuild;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.phys.*;
import org.slf4j.*;
public final class BuildBridge implements ModInitializer {
 public static final Logger LOG=LoggerFactory.getLogger("ai_builder");
 public static volatile boolean background=false;
 public static volatile Engine engine;
 @Override public void onInitialize(){
  ChunkLease.init();
  net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((p,l,h,hit)->MoveGuard.contains(l,hit.getBlockPos())?net.minecraft.world.InteractionResult.FAIL:net.minecraft.world.InteractionResult.PASS);
  net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((l,p,at,state,be)->!MoveGuard.contains(l,at));
  net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((p,l,h,ent,hit)->MoveGuard.contains(l,ent.blockPosition())?net.minecraft.world.InteractionResult.FAIL:net.minecraft.world.InteractionResult.PASS);
  net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((p,l,h,ent,hit)->MoveGuard.contains(l,ent.blockPosition())?net.minecraft.world.InteractionResult.FAIL:net.minecraft.world.InteractionResult.PASS);
  CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
   var root=Commands.literal("aibuild").executes(c->{c.getSource().sendSuccess(()->Component.literal("建筑桥接：connect / disconnect / anchor [x y z] / status / pause / resume [任务ID] / cancel / undo [任务ID]"),false);return 1;});
   root.then(Commands.literal("connect").executes(c->{var p=c.getSource().getPlayerOrException();try{connect(p,null);return 1;}catch(Exception e){c.getSource().sendFailure(Component.literal(e.getMessage()));return 0;}}));
   root.then(Commands.literal("disconnect").executes(c->{var p=c.getSource().getPlayerOrException();if(engine!=null&&engine.owner.equals(p.getUUID()))disconnect();p.sendSystemMessage(Component.literal("建筑连接已关闭，原暂停行为恢复。"));return 1;}));
   root.then(Commands.literal("anchor").executes(c->{var p=c.getSource().getPlayerOrException();return command(p,"anchor",null);}).then(Commands.argument("pos",BlockPosArgument.blockPos()).executes(c->{var p=c.getSource().getPlayerOrException();try{require(p);engine.anchor=BlockPosArgument.getBlockPos(c,"pos");engine.invalidatePrepared();engine.publish();return 1;}catch(Exception e){p.sendSystemMessage(Component.literal(e.getMessage()));return 0;}})));
   for(String op:new String[]{"status","pause","cancel"})root.then(Commands.literal(op).executes(c->command(c.getSource().getPlayerOrException(),op,null)));
   for(String op:new String[]{"resume","undo"})root.then(Commands.literal(op).executes(c->command(c.getSource().getPlayerOrException(),op,null)).then(Commands.argument("job",StringArgumentType.word()).executes(c->command(c.getSource().getPlayerOrException(),op,StringArgumentType.getString(c,"job")))));
   dispatcher.register(root);
  });
  ServerTickEvents.END_SERVER_TICK.register(server->{Engine e=engine;if(e!=null&&e.server==server)e.tick();});
  ServerLifecycleEvents.SERVER_STARTED.register(AssemblyGuard::restore);
  ServerLifecycleEvents.SERVER_STARTED.register(Moves::restoreGuards);
  ServerLifecycleEvents.SERVER_STOPPED.register(MoveGuard::clear);
  ServerLifecycleEvents.SERVER_STOPPED.register(AssemblyGuard::clear);
  ServerLifecycleEvents.SERVER_STOPPING.register(server->{if(engine!=null&&engine.server==server)disconnect();});
 }
 public static void require(ServerPlayer p){if(engine==null||!engine.connected||!engine.owner.equals(p.getUUID()))throw new IllegalStateException("先输入 /aibuild connect");if(p.level()!=engine.level)throw new IllegalStateException("维度已变化，请重新连接");}
 public static void connect(ServerPlayer p,BlockPos chosen){
  var server=p.level().getServer();if(server instanceof OfflineServer){if(!server.isSingleplayerOwner(p.nameAndId()))throw new IllegalStateException("仅本机主人可连接");if(engine!=null){if(engine.level!=p.level())throw new IllegalStateException("后台当前维度不同，请先在工具切换维度");if(chosen!=null)engine.anchor=chosen;p.sendSystemMessage(Component.literal("后台建筑桥接已经连接，不需要重复连接。"));return;}engine=new Engine(server,p.level(),p.getUUID(),((OfflineServer)server).bridge,chosen!=null?chosen:pick(p),true);return;}if(!(server instanceof net.minecraft.client.server.IntegratedServer)||!server.isSingleplayerOwner(p.nameAndId()))throw new IllegalStateException("仅本机世界的房主可以连接建筑桥接；支持开放局域网");
  if(BackgroundConnection.running(FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize()).isPresent())throw new IllegalStateException("另一后台世界正在使用桥接；可以正常游玩本世界，连接施工前先停止后台世界");
  if(!p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))throw new IllegalStateException("需要单人世界命令权限（允许作弊）");
  if(engine!=null)disconnect();BlockPos anchor=chosen!=null?chosen:pick(p);
  engine=new Engine(server,p,FabricLoader.getInstance().getGameDir().resolve("aibuild-bridge"),anchor);background=true;
  p.sendSystemMessage(Component.literal("建筑桥接已连接，原点 "+anchor.toShortString()+"。切出窗口后世界继续运行，生物和时间也继续；/aibuild disconnect 恢复暂停。"));
 }
 public static BlockPos pick(ServerPlayer p){HitResult hit=p.pick(64,0,false);return hit instanceof BlockHitResult b&&hit.getType()==HitResult.Type.BLOCK?b.getBlockPos().relative(b.getDirection()):p.blockPosition().relative(p.getDirection(),4);}
 private static int command(ServerPlayer p,String op,String id){try{require(p);if(op.equals("anchor")){engine.anchor=pick(p);engine.invalidatePrepared();engine.publish();p.sendSystemMessage(Component.literal("建筑原点："+engine.anchor.toShortString()));}else engine.local(op,id);return 1;}catch(Exception e){p.sendSystemMessage(Component.literal(e.getMessage()));return 0;}}
 public static void disconnect(){Engine e=engine;background=false;engine=null;if(e!=null)e.close();}
}
