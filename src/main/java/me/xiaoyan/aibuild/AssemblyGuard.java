package me.xiaoyan.aibuild;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import java.util.*;
import java.nio.file.*;
/** Only incomplete transaction regions are held; the rest of the world keeps ticking. */
public final class AssemblyGuard {
 private static final Map<Level,Map<String,Set<java.util.UUID>>> entities=new IdentityHashMap<>();
 private static final Map<Level,Map<String,int[]>> held=new IdentityHashMap<>();
 public static void hold(ServerLevel level,String id,int[] bounds){held.computeIfAbsent(level,k->new HashMap<>()).put(id,bounds.clone());}
 public static void holdEntities(ServerLevel level,String id,Set<java.util.UUID> ids){entities.computeIfAbsent(level,k->new HashMap<>()).put(id,Set.copyOf(ids));}
 public static boolean entity(net.minecraft.world.entity.Entity entity){if(contains(entity.level(),entity.blockPosition()))return true;var map=entities.get(entity.level());return map!=null&&map.values().stream().anyMatch(ids->ids.contains(entity.getUUID()));}
 public static void release(ServerLevel level,String id){var ids=entities.get(level);if(ids!=null)ids.remove(id);var m=held.get(level);if(m!=null)m.remove(id);}
 public static boolean contains(Level level,BlockPos p){if(MoveGuard.contains(level,p))return true;var m=held.get(level);if(m==null)return false;for(int[] b:m.values())if(p.getX()>=b[0]&&p.getY()>=b[1]&&p.getZ()>=b[2]&&p.getX()<=b[3]&&p.getY()<=b[4]&&p.getZ()<=b[5])return true;return false;}
 public static void restore(MinecraftServer server){for(ServerLevel level:server.getAllLevels()){
  Path root=server.getWorldPath(LevelResource.ROOT).resolve("aibuild-data").resolve(level.dimension().identifier().toString().replace(':','_').replace('/','_')).resolve("transactions");if(!Files.exists(root))continue;
  try(var files=Files.list(root)){for(Path dir:files.toList()){Path f=dir.resolve("progress.json");if(!Files.exists(f))f=dir.resolve("journal.json");if(!Files.exists(f))continue;var j=FilesIO.read(f);var p=j.getAsJsonObject("progress");if(!Set.of("COMPLETE","UNDONE").contains(p.get("phase").getAsString())&&Plan.integer(p.get("stage"))<5){hold(level,j.get("id").getAsString(),FilesIO.GSON.fromJson(j.get("bounds"),int[].class));if(j.has("entity_ids")){Set<java.util.UUID> ids=new HashSet<>();for(var id:j.getAsJsonArray("entity_ids"))ids.add(java.util.UUID.fromString(id.getAsString()));holdEntities(level,j.get("id").getAsString(),ids);}}}}
  catch(Exception ex){throw new IllegalStateException("无法恢复未完成建筑隔离范围",ex);}
 }}
 public static void clear(MinecraftServer server){held.keySet().removeIf(l->l.getServer()==server);entities.keySet().removeIf(l->l.getServer()==server);}
}
