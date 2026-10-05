package me.xiaoyan.aibuild;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.SystemReport;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.*;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.notifications.NotificationManager;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.permissions.*;
import net.minecraft.server.players.*;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.debugchart.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;

/** One writer, one local player. No console, GUI, LAN broadcast or external listener. */
public final class OfflineServer extends net.minecraft.server.dedicated.DedicatedServer {
 private final JsonObject settings;
 private final LocalSampleLogger samples=new LocalSampleLogger(8);
 public final UUID originalOwner;
 public final Path bridge;private final Path control,connection;
 public OfflineServer(Thread thread,LevelStorageSource.LevelStorageAccess access,PackRepository packs,WorldStem stem,Services services,JsonObject settings){
  super(thread,access,packs,stem,Optional.empty(),new net.minecraft.server.dedicated.DedicatedServerSettings(Path.of(settings.get("runtime").getAsString()).resolve("server.properties")),DataFixers.getDataFixer(),services,null,new NotificationManager());
  this.settings=settings;notificationManager().setServer(this);
  originalOwner=stem.worldDataAndGenSettings().data().getSinglePlayerUUID();
  if(originalOwner==null)throw new IllegalArgumentException("世界缺少单人玩家UUID，停止避免生成新库存");
  bridge=Path.of(settings.get("bridge").getAsString());control=Path.of(settings.get("runtime").getAsString()).resolve("stop.json");
  connection=Path.of(settings.get("connection").getAsString());
  setSingleplayerProfile(new GameProfile(originalOwner,"AIBuildOwner"));setUsesAuthentication(false);setPort(settings.get("port").getAsInt());
 }
 @Override protected boolean initServer() throws IOException {
  setPlayerList(new net.minecraft.server.dedicated.DedicatedPlayerList(this,registries(),playerDataStorage){
   @Override public Component canPlayerLogin(SocketAddress address,NameAndId profile){
    if(!(address instanceof InetSocketAddress a)||!a.getAddress().isLoopbackAddress()||!profile.id().equals(originalOwner))return Component.literal("仅允许本机世界主人");
    if(getPlayerCount()>0)return Component.literal("该世界已有本机玩家");return null;
   }
   @Override public boolean isOp(NameAndId profile){return profile.id().equals(originalOwner)&&worldData.isAllowCommands();}
   @Override public int getMaxPlayers(){return 1;}
  });
  getPlayerList().setViewDistance(8);getPlayerList().setSimulationDistance(5);
  loadLevel();
  ResourceKey<Level> dimension=ResourceKey.create(Registries.DIMENSION,Identifier.parse(settings.get("dimension").getAsString()));
  var level=getLevel(dimension);if(level==null)throw new IllegalArgumentException("世界没有指定维度");
  BuildBridge.engine=new Engine(this,level,originalOwner,bridge,new BlockPos(0,level.getMinY()+1,0),true);
  getConnection().startTcpServerListener(InetAddress.getByName("127.0.0.1"),getPort());
  try{FilesIO.atomic(connection,Map.of("pid",ProcessHandle.current().pid(),"port",getPort(),"world",BuildBridge.engine.world,"dimension",BuildBridge.engine.dimension,"session",BuildBridge.engine.session,"bridge",bridge.toString(),"version","1.6.0+mc26.3","runtime",settings.get("runtime").getAsString()));}catch(Exception e){throw new IOException("无法发布后台连接",e);}
  return true;
 }
 @Override protected void tickServer(BooleanSupplier time){
  if(Files.exists(control)){try{var command=FilesIO.read(control);Files.delete(control);if(getPlayerCount()>0||getConnection().getConnections().stream().anyMatch(net.minecraft.network.Connection::isConnected)){FilesIO.atomic(control.getParent().resolve("stop-result.json"),Map.of("id",command.get("id").getAsString(),"error","玩家正在进入或游玩，不停止后台世界"));}else{halt(false);return;}}catch(Exception e){BuildBridge.LOG.error("后台停止请求失败",e);}}

  InteractionBudget.remotePlaying(getPlayerCount()>0);
  super.tickServer(time);
 }
 @Override protected void stopServer(){
  BuildBridge.disconnect();super.stopServer();
  try{FilesIO.atomic(control.getParent().resolve("exit.json"),Map.of("saved",!failed,"pid",ProcessHandle.current().pid()));}catch(Exception e){BuildBridge.LOG.error("无法写保存退出回执",e);}
  try{if(Files.exists(connection)&&FilesIO.read(connection).get("pid").getAsLong()==ProcessHandle.current().pid())Files.delete(connection);}catch(Exception e){BuildBridge.LOG.error("无法清理后台连接记录",e);}
 }
 @Override public LevelBasedPermissionSet operatorUserPermissions(){return LevelBasedPermissionSet.ALL;}
 @Override public PermissionSet getFunctionCompilationPermissions(){return PermissionSet.ALL_PERMISSIONS;}
 @Override public boolean shouldRconBroadcast(){return false;}
 @Override protected SampleLogger getTickTimeLogger(){return samples;}
 @Override public boolean isTickTimeLoggingEnabled(){return false;}
 @Override public SystemReport fillServerSystemReport(SystemReport report){report.setDetail("AIBuild","local background world");return report;}
 @Override public boolean isDedicatedServer(){return true;}
 @Override public boolean isSingleplayer(){return false;}
 @Override protected void forceDifficulty(){}
 public boolean failed;
 @Override protected void onServerCrash(net.minecraft.CrashReport report){failed=true;super.onServerCrash(report);}
 @Override public int getRateLimitPacketsPerSecond(){return 0;}
 @Override public int getCommandSpamThresholdSeconds(){return 0;}
 @Override public int getChatSpamThresholdSeconds(){return 0;}
 @Override public boolean useNativeTransport(){return false;}
 @Override public boolean isPublished(){return true;}
 @Override public boolean shouldInformAdmins(){return false;}
 @Override public boolean isSingleplayerOwner(NameAndId profile){return profile.id().equals(originalOwner);}
 @Override public boolean enforceSecureProfile(){return false;}
 @Override public int getMaxPlayers(){return 1;}
}
