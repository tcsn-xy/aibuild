package me.xiaoyan.aibuild;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import java.util.*;

/** One bounded lease per engine. Tickets are transient and distinct from /forceload. */
public final class ChunkLease implements AutoCloseable {
 public static final TicketType TYPE=Registry.register(BuiltInRegistries.TICKET_TYPE,Identifier.fromNamespaceAndPath("ai_builder","construction"),new TicketType(0,TicketType.FLAG_LOADING|TicketType.FLAG_SIMULATION|TicketType.FLAG_KEEP_DIMENSION_ACTIVE));
 public static void init(){} // Register before Minecraft freezes the registry.
 static final long TIMEOUT=60_000_000_000L;
 public static final class Scope {
  final Set<ChunkPos> chunks=new LinkedHashSet<>();
  Scope(ServerLevel level,Collection<BlockPos> positions){
   Set<ChunkPos> centers=new LinkedHashSet<>();
   for(BlockPos p:positions){validate(level,p);centers.add(new ChunkPos(p.getX()>>4,p.getZ()>>4));}
   for(ChunkPos c:centers)for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)chunks.add(new ChunkPos(c.x()+x,c.z()+z));
   if(chunks.size()>256)throw new IllegalArgumentException("施工及实体加载范围过大，请拆分任务（最多256个区块）");
  }
 }
 private final ServerLevel level;
 private final Set<ChunkPos> held=new LinkedHashSet<>();
 private Scope scope;private long started;private int ready;private String failure="";
 public ChunkLease(ServerLevel level){this.level=level;}
 static void validate(ServerLevel level,BlockPos p){if(p.getY()<level.getMinY()||p.getY()>level.getMaxY())throw new IllegalArgumentException("超出世界高度："+p);if(!level.getWorldBorder().isWithinBounds(p))throw new IllegalArgumentException("超出世界边界："+p);}
 boolean ready(ChunkPos p){return level.getChunkSource().getChunkNow(p.x(),p.z())!=null&&level.areEntitiesActuallyLoadedAndTicking(p);}
 /** Never blocks or synchronously generates a chunk. The normal server tick services tickets. */
 boolean ensure(Scope next){
  if(next!=scope){retain(next);scope=next;started=System.nanoTime();failure="";ready=0;}
  if(next==null)return true;
  int added=0;for(ChunkPos p:next.chunks)if(!held.contains(p)){level.getChunkSource().addTicketWithRadius(TYPE,p,2);held.add(p);if(++added==2)break;}
  ready=0;List<List<Integer>> missing=new ArrayList<>();
  for(ChunkPos p:next.chunks){if(held.contains(p)&&ready(p))ready++;else missing.add(List.of(p.x(),p.z()));}
  if(missing.isEmpty())return true;
  if(System.nanoTime()-started>TIMEOUT){failure="加载施工区超时（60秒），未就绪区块："+missing+"；任务已停止，可重试/继续";throw new IllegalStateException(failure);}
  return false;
 }
 void retain(Scope next){for(var it=held.iterator();it.hasNext();){ChunkPos p=it.next();if(next==null||!next.chunks.contains(p)){level.getChunkSource().removeTicketWithRadius(TYPE,p,2);it.remove();}}}
 public Map<String,Object> status(){return Map.of("phase",scope==null?"IDLE":ready==scope.chunks.size()?"READY":"LOADING","ready",ready,"total",scope==null?0:scope.chunks.size(),"tickets",held.size(),"elapsed_ms",scope==null?0:(System.nanoTime()-started)/1_000_000,"error",failure);}
 @Override public void close(){retain(null);scope=null;ready=0;}
}
