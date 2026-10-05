package me.xiaoyan.aibuild;

import com.google.gson.JsonObject;
import java.net.Proxy;
import java.nio.file.*;
import java.util.Optional;
import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.*;
import net.minecraft.server.notifications.NotificationManager;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.LevelStorageSource;

/** Existing world only. Uses Minecraft's world loader, lock and normal save path. */
public final class OfflineMain {
 public static void start(Path config) {
  try {
   JsonObject settings=FilesIO.read(config);
   Path world=Path.of(settings.get("world").getAsString()).toRealPath();
   if(!Files.isRegularFile(world.resolve("level.dat")))throw new IllegalArgumentException("不是已有世界，不创建新世界");
   var source=LevelStorageSource.createDefault(world.getParent());
   var access=source.validateAndCreateAccess(world.getFileName().toString());
   boolean transferred=false;
   try {
    var tag=access.getUnfixedDataTagWithFallback();
    var summary=access.fixAndGetSummaryFromTag(tag);
    if(summary.requiresManualConversion()||!summary.isCompatible())throw new IllegalArgumentException("世界版本不兼容，禁止自动转换");
    var repository=ServerPacksSource.createPackRepository(access);
    var init=new WorldLoader.InitConfig(new WorldLoader.PackConfig(repository,LevelStorageSource.readDataConfig(tag),false,false),Commands.CommandSelection.ALL,PermissionSet.ALL_PERMISSIONS);
    WorldStem stem=Util.blockUntilDone(executor->WorldLoader.load(init,context->{
     var dimensions=context.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM);
     var data=LevelStorageSource.getLevelDataAndDimensions(access,tag,context.dataConfiguration(),dimensions,context.datapackWorldRegistries());
     return new WorldLoader.DataLoadOutput<>(data.worldDataAndGenSettings(),data.dimensions().dimensionsRegistryAccess());
    },WorldStem::new,Util.backgroundExecutor(),executor)).get();
    var services=Services.create(MinecraftServicesDiscoveryService.create(Proxy.NO_PROXY),Path.of(settings.get("runtime").getAsString()).toFile());
    OfflineServer server=MinecraftServer.spin(thread->new OfflineServer(thread,access,repository,stem,services,settings));
    transferred=true;
    Runtime.getRuntime().addShutdownHook(new Thread(()->server.halt(true),"aibuild-background-save"));
    server.getRunningThread().join();
    System.exit(server.failed?1:0);
   } finally {if(!transferred)access.close();}
  } catch(Exception error){throw new IllegalStateException("后台世界启动失败",error);}
 }
}
