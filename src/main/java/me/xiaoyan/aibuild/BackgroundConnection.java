package me.xiaoyan.aibuild;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.util.Optional;
/** Local descriptor discovery only; never opens a save or starts a client world. */
public final class BackgroundConnection {
 public static Optional<JsonObject> running(Path instance){try{var descriptor=FilesIO.read(instance.resolve("aibuild-background/connection.json"));return find(instance,Path.of(descriptor.get("world").getAsString()).getFileName().toString());}catch(Exception e){return Optional.empty();}}
 public static Optional<JsonObject> find(Path instance,String id){
  try{
   Path descriptor=instance.resolve("aibuild-background/connection.json");
   if(!Files.isRegularFile(descriptor))return Optional.empty();
   JsonObject d=FilesIO.read(descriptor);Path world=instance.resolve("saves").resolve(id).normalize();
   if(!world.getParent().equals(instance.resolve("saves"))||!world.toRealPath().equals(Path.of(d.get("world").getAsString()).toRealPath()))return Optional.empty();
   var process=ProcessHandle.of(d.get("pid").getAsLong());
   if(process.isEmpty()||!process.get().isAlive()||!process.get().info().commandLine().orElse("").contains(d.get("runtime").getAsString()))return Optional.empty();
   int port=d.get("port").getAsInt();if(port<1||port>65535)return Optional.empty();return Optional.of(d);
  }catch(Exception error){return Optional.empty();}
 }
}
