package me.xiaoyan.aibuild;
import com.google.gson.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.resources.Identifier;
import net.minecraft.world.ticks.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
/** Separate, journaled destructive operations. Legacy undo never becomes forced deletion. */
public final class Transactions {
 final Engine e; Prepared prepared;public Run run;CompletableFuture<?> pending;String pendingRequest;ProjectionData loadingProjection;
 public Transactions(Engine e){this.e=e;}
 public boolean busy(){return pending!=null||run!=null&&run.running();}
 public boolean unfinished(){return run!=null&&(!Set.of("COMPLETE","UNDONE","PARTIAL").contains(run.phase)||run.phase.equals("PARTIAL")&&run.stage<5);}
 public Map<String,Object> status(){return run==null?Map.of("state",pending==null?"IDLE":"READING"):run.progress();}
 static CompoundTag snapshot(Entity entity,ServerLevel level){var problems=new ProblemReporter.Collector();var out=TagValueOutput.createWithContext(problems,level.registryAccess());if(!entity.save(out))throw new IllegalArgumentException("实体不能保存："+entity.getUUID());if(!problems.getReport().isBlank())throw new IllegalArgumentException(problems.getReport());return out.buildResult();}
 CompoundTag be(BlockPos p){var b=e.level.getBlockEntity(p);return b==null?null:b.saveWithFullMetadata(e.server.registryAccess());}
 static String text(CompoundTag n){return n==null?"":n.toString();}
 record Cell(BlockPos pos,BlockState before,String beforeNbt,BlockState after,String afterNbt){Map<String,Object> data(){return Map.of("pos",Engine.coords(pos),"before",Engine.describe(before),"before_nbt",beforeNbt,"after",Engine.describe(after),"after_nbt",afterNbt);}}
 record Mob(UUID id,String before,String after){Map<String,Object> data(){return Map.of("uuid",id.toString(),"before",before,"after",after);}}
 class Prepared {
  String token=UUID.randomUUID().toString(),name,kind;long expires=System.nanoTime()+60_000_000_000L;List<Cell> cells=new ArrayList<>();List<Mob> mobs=new ArrayList<>();List<BlockPos> activation=new ArrayList<>();List<JsonObject> ticks=new ArrayList<>(),beforeTicks=new ArrayList<>();int[] bounds;ChunkLease.Scope scope;Map<UUID,String> observed=new LinkedHashMap<>();
  Map<String,Object> summary(){return Map.of("token",token,"name",name,"kind",kind,"bounds",bounds,"blocks",cells.stream().filter(c->!c.before.equals(c.after)||!c.beforeNbt.equals(c.afterNbt)).count(),"containers",cells.stream().filter(c->!c.beforeNbt.isEmpty()).count(),"entities",mobs.size(),"contents","delete with demolition; snapshots retained for rollback","targets",cells.stream().filter(c->!c.before.isAir()).map(Cell::data).toList(),"entity_targets",mobs.stream().map(Mob::data).toList());}
 }
 Path path(String id){return e.journals.resolve("transactions").resolve(FilesIO.id(id));}
 public void handle(String op,String request,JsonObject r){
  if(op.equals("projection-check")){ensureIdle();e.invalidatePrepared();clearPrepared();JsonObject raw=r.getAsJsonObject("plan").deepCopy();pending=CompletableFuture.supplyAsync(()->new ProjectionData(raw),e.io);pendingRequest=request;return;}
  if(op.equals("demolish-check")){ensureIdle();e.invalidatePrepared();clearPrepared();pending=CompletableFuture.supplyAsync(()->resolveTargets(r),e.io);pendingRequest=request;return;}
  if(op.equals("projection-build")||op.equals("demolish")){ensureIdle();if(prepared==null||System.nanoTime()>prepared.expires||!prepared.token.equals(r.get("token").getAsString())||!prepared.kind.equals(op.equals("demolish")?"demolish":"projection"))throw new IllegalArgumentException("检查令牌不存在或类型不符");e.work=new Verify(request,prepared);return;}
  if(op.equals("transaction-pause")||op.equals("transaction-cancel")){if(e.work!=null){e.work.cancel();e.work=null;}if(pending!=null){e.reply(pendingRequest,false,null,"读取任务已取消");pending.cancel(false);pending=null;loadAction=null;}clearPrepared();e.invalidatePrepared();e.chunks.close();if(run==null){e.reply(request,true,status(),null);return;}if(Set.of("COMPLETE","UNDONE","PARTIAL").contains(run.phase)){e.reply(request,true,status(),null);return;}run.phase=op.endsWith("cancel")?"CANCELLED":"PAUSED";run.save();e.reply(request,true,status(),null);return;}
  if(op.equals("transaction-resume")||op.equals("transaction-undo")){
   if(busy()||e.legacyBusy()||e.work!=null)throw new IllegalStateException("先暂停当前任务");clearPrepared();e.invalidatePrepared();String id=r.has("job")?FilesIO.id(r.get("job").getAsString()):run==null?null:run.id;if(id==null)throw new IllegalArgumentException("需要任务ID");
   if(unfinished()&&!run.id.equals(id))throw new IllegalStateException("先处理未完成任务");
   pending=CompletableFuture.supplyAsync(()->{try{var j=FilesIO.read(path(id).resolve("journal.json"));Path progress=path(id).resolve("progress.json");if(Files.exists(progress)){var checkpoint=FilesIO.read(progress);j.add("progress",checkpoint.get("progress"));j.add("undo",checkpoint.get("undo"));j.add("block_highwater",checkpoint.get("block_highwater"));j.add("mob_highwater",checkpoint.get("mob_highwater"));}return j;}catch(Exception ex){throw new CompletionException(ex);}},e.io);pendingRequest=request;loadAction=op;return;
  }
  throw new IllegalArgumentException("未知事务命令");
 }
 String loadAction;
 void clearPrepared(){if(prepared!=null)AssemblyGuard.release(e.level,prepared.token);prepared=null;}
 void ensureIdle(){e.idle();if(unfinished())throw new IllegalStateException("先继续或撤销未完成事务");}
 JsonObject resolveTargets(JsonObject request){try{
  JsonObject r=request.deepCopy();JsonArray positions=new JsonArray(),ids=new JsonArray(),entityPositions=new JsonArray();
  if(r.has("job")){var p=FilesIO.read(e.journals.resolve("jobs").resolve(FilesIO.id(r.get("job").getAsString())).resolve("plan.json"));if(!p.get("world").getAsString().equals(e.world)||!p.get("dimension").getAsString().equals(e.dimension))throw new IllegalArgumentException("任务世界不符");for(var v:p.getAsJsonArray("edits"))positions.add(v.getAsJsonObject().get("pos"));}
  if(r.has("deployments"))for(var d:r.getAsJsonArray("deployments")){var p=FilesIO.read(e.journals.resolve("cart-deployments").resolve(FilesIO.id(d.getAsString())+".json"));if(!p.get("world").getAsString().equals(e.world)||!p.get("dimension").getAsString().equals(e.dimension))throw new IllegalArgumentException("部署世界不符");for(var id:p.getAsJsonArray("uuids"))ids.add(id);if(p.has("positions"))for(var pos:p.getAsJsonArray("positions"))entityPositions.add(pos);}
  r.add("entity_positions",entityPositions);r.add("positions",positions);r.add("entity_ids",ids);return r;
 }catch(Exception ex){throw new CompletionException(ex);}}
 void expirePrepared(){if(prepared!=null&&System.nanoTime()>prepared.expires)clearPrepared();}
 void failActive(String msg){if(run!=null&&run.running()){run.phase="PAUSED";run.error=msg;run.save();}}
 public void tick(long deadline){try{
  expirePrepared();
  if(pending!=null){if(!pending.isDone())return;Object result=pending.join();String request=pendingRequest;pending=null;
   if(result instanceof ProjectionData p)e.work=new Check(request,p,null);
   else if(loadAction!=null){run=restore((JsonObject)result);String op=loadAction;loadAction=null;if(op.endsWith("undo")){if(run.phase.equals("UNDONE")){e.reply(request,true,status(),null);pendingRequest=null;return;}run.undo=true;run.index=0;run.stage=0;run.conflicts.clear();run.phase="UNDOING";}else{if(Set.of("COMPLETE","UNDONE","PARTIAL","CANCELLED").contains(run.phase))throw new IllegalArgumentException("该任务不能继续，请撤销或重新检查");run.phase=run.undo?"UNDOING":"BUILDING";}run.save();e.reply(request,true,status(),null);}
   else e.work=new Check(request,null,(JsonObject)result);pendingRequest=null;return;
  }
  if(run!=null&&run.running())run.tick(deadline);
 }catch(Exception ex){String msg=ex.getCause()==null?ex.getMessage():ex.getCause().getMessage();if(pendingRequest!=null)e.reply(pendingRequest,false,null,msg);pending=null;pendingRequest=null;loadAction=null;if(run!=null&&run.running()){run.phase="PAUSED";run.error=msg;run.save();}e.message="事务暂停："+msg;e.publish();}}
 public void close(){if(prepared!=null)AssemblyGuard.release(e.level,prepared.token);if(run!=null&&run.running()){run.phase="PAUSED";run.save();}}
 class Check extends Engine.Work {
  Prepared p=new Prepared();ProjectionData source;List<BlockPos> positions=new ArrayList<>();Map<BlockPos,ProjectionData.Cell> sourceCells=new HashMap<>();Map<BlockPos,BlockState> future=new HashMap<>();int cursor=0,pass=0;Set<UUID> explicit=new LinkedHashSet<>();boolean range=false;
  Check(String request,ProjectionData source,JsonObject demolition){e.super(request);this.source=source;p.kind=source==null?"demolish":"projection";p.name=source==null?"明确范围拆除（内容物一并删除）":source.name;
   if(source!=null){for(var c:source.cells){positions.add(c.pos());sourceCells.put(c.pos(),c);String sid=c.state().getString("id").orElseThrow();var properties=c.state().getCompound("properties");String stateText=sid;if(properties.isPresent()){List<String> pairs=new ArrayList<>();for(String key:properties.get().keySet())pairs.add(key+"="+properties.get().getString(key).orElseThrow());if(!pairs.isEmpty())stateText+="["+String.join(",",pairs)+"]";}var s=e.resolve(stateText).rotate(source.rotation);if(!Engine.describe(s).startsWith(c.state().getString("id").orElseThrow()))throw new IllegalArgumentException("未知方块");future.put(c.pos(),s);}}
   else{Set<BlockPos> set=new LinkedHashSet<>();if(demolition.has("origin")){range=true;int[] o=Plan.triple(demolition.get("origin")),size=Plan.triple(demolition.get("size"));for(int n:size)if(n<1||n>64)throw new IllegalArgumentException("拆除范围必须1～64");for(int y=0;y<size[1];y++)for(int z=0;z<size[2];z++)for(int x=0;x<size[0];x++)set.add(new BlockPos(o[0]+x,o[1]+y,o[2]+z));}
    for(var q:demolition.getAsJsonArray("positions")){int[] xyz=Plan.triple(q);set.add(new BlockPos(xyz[0],xyz[1],xyz[2]));}positions.addAll(set);for(var id:demolition.getAsJsonArray("entity_ids"))explicit.add(UUID.fromString(id.getAsString()));if(set.isEmpty())throw new IllegalArgumentException("需要明确范围或历史任务");}
   List<BlockPos> loadPositions=new ArrayList<>(positions);
   if(source!=null)for(var m:source.entities)loadPositions.add(BlockPos.containing(m.pos()[0],m.pos()[1],m.pos()[2]));
   else if(demolition.has("entity_positions"))for(var v:demolition.getAsJsonArray("entity_positions")){var a=v.getAsJsonArray();loadPositions.add(BlockPos.containing(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble()));}
   for(UUID id:explicit){Entity ent=e.level.getEntity(id);if(ent!=null)loadPositions.add(ent.blockPosition());}
   scope=p.scope=new ChunkLease.Scope(e.level,loadPositions);
   p.bounds=bounds(positions);for(int k=0;k<3;k++)if(p.bounds[k+3]-p.bounds[k]+1>64)throw new IllegalArgumentException("拆分超过64格的任务");
   for(BlockPos pos:positions)if(AssemblyGuard.contains(e.level,pos))throw new IllegalArgumentException("此区域有未完成/等待确认事务，请先继续或撤销");
   if(source==null){AssemblyGuard.hold(e.level,p.token,p.bounds);AssemblyGuard.holdEntities(e.level,p.token,explicit);}
  }
  @Override void fail(String message){AssemblyGuard.release(e.level,p.token);super.fail(message);}
  boolean step(long deadline){int count=0;while(cursor<positions.size()&&count++<InteractionBudget.steps(System.nanoTime())&&System.nanoTime()<deadline){BlockPos pos=positions.get(cursor++);e.loaded(pos);
   if(pass==0){BlockState before=e.level.getBlockState(pos),after=source==null?Blocks.AIR.defaultBlockState():future.get(pos);String beforeTag=text(be(pos)),afterTag="";
    if(source!=null){if(!beforeTag.isEmpty())throw new IllegalArgumentException("现有方块实体受保护，请先拆除："+pos);CompoundTag tag=sourceCells.get(pos).nbt();if(tag==null&&after.getBlock() instanceof EntityBlock eb){var blank=eb.newBlockEntity(pos,after);if(blank!=null)tag=blank.saveWithFullMetadata(e.server.registryAccess());}if(tag!=null){var b=BlockEntity.loadStatic(pos,after,tag,e.server.registryAccess());if(b==null)throw new IllegalArgumentException("方块实体加载失败："+pos);afterTag=b.saveWithFullMetadata(e.server.registryAccess()).toString();}}
    p.cells.add(new Cell(pos,before,beforeTag,after,afterTag));
   }else if(source!=null)e.validatePlacement(pos,future.get(pos),future);
  }if(cursor<positions.size())return false;if(pass++==0){cursor=0;return false;}
   var box=box(p.bounds);for(Entity ent:e.level.getEntities((Entity)null,box,x->!(x instanceof Player))){if(source!=null)throw new IllegalArgumentException("投影区域已有实体，请先调整位置/拆除："+ent.getUUID());if(range||positions.contains(ent.blockPosition()))explicit.add(ent.getUUID());}
   for(UUID id:explicit){Entity ent=e.level.getEntity(id);if(ent==null)throw new IllegalArgumentException("已加载部署记录位置，仍未找到实体（可能已移动或删除）；停止并核对："+id);if(ent instanceof Player)continue;e.loaded(ent.blockPosition());String n=text(snapshot(ent,e.level));p.observed.put(id,n);p.mobs.add(new Mob(id,n,""));}
   if(source!=null){String salt=UUID.randomUUID().toString();for(var m:source.entities){UUID id=UUID.nameUUIDFromBytes((e.world+"|"+e.dimension+"|"+salt+"|"+m.key()).getBytes(java.nio.charset.StandardCharsets.UTF_8));Entity ent=entity(m.nbt(),id);ent.setPos(m.pos()[0],m.pos()[1],m.pos()[2]);ent.setYRot(ent.getYRot()+source.angle);var velocity=ent.getDeltaMovement();double x=velocity.x,z=velocity.z;ent.setDeltaMovement(switch(source.angle){case 90->new Vec3(-z,velocity.y,x);case 180->new Vec3(-x,velocity.y,-z);case 270->new Vec3(z,velocity.y,-x);default->velocity;});p.mobs.add(new Mob(id,"",text(snapshot(ent,e.level))));}
    for(var v:source.activation)p.activation.add(source.position(Plan.triple(v)));
    for(var v:source.ticks){var t=v.getAsJsonObject().deepCopy();t.add("pos",FilesIO.GSON.toJsonTree(Engine.coords(source.position(Plan.triple(t.get("pos"))))));validateTick(t);p.ticks.add(t);}
   }
   for(int cx=p.bounds[0]>>4;cx<=p.bounds[3]>>4;cx++)for(int cz=p.bounds[2]>>4;cz<=p.bounds[5]>>4;cz++){
    var chunk=e.level.getChunk(cx,cz);((LevelChunkTicks<Block>)chunk.getBlockTicks()).getAll().filter(t->box.contains(Vec3.atCenterOf(t.pos()))).forEach(t->p.beforeTicks.add(tickData(t,"block",BuiltInRegistries.BLOCK.getKey(t.type()).toString())));((LevelChunkTicks<net.minecraft.world.level.material.Fluid>)chunk.getFluidTicks()).getAll().filter(t->box.contains(Vec3.atCenterOf(t.pos()))).forEach(t->p.beforeTicks.add(tickData(t,"fluid",BuiltInRegistries.FLUID.getKey(t.type()).toString())));
   }
   // Delete live sources first; all writes remain without side effects until complete.
   if(source==null)p.cells.sort(Comparator.comparingInt(c->(!c.before.getFluidState().isEmpty()||c.before.isSignalSource())?0:1));
   p.expires=System.nanoTime()+60_000_000_000L;prepared=p;e.reply(request,true,p.summary(),null);return true;
  }
 }
 class Verify extends Engine.Work {
  Prepared p;int cursor=0;Verify(String request,Prepared p){e.super(request);this.p=p;scope=p.scope;}
  boolean step(long deadline){int count=0;while(cursor<p.cells.size()&&count++<InteractionBudget.steps(System.nanoTime())&&System.nanoTime()<deadline){Cell c=p.cells.get(cursor++);e.loaded(c.pos);if(!matches(c.pos,c.before,c.beforeNbt))throw new IllegalArgumentException("场地或内容物变化，重新检查："+c.pos);}
   if(cursor<p.cells.size())return false;
   for(var m:p.observed.entrySet()){var ent=e.level.getEntity(m.getKey());if(ent==null||!text(snapshot(ent,e.level)).equals(m.getValue()))throw new IllegalArgumentException("实体移动或内容变化，重新检查："+m.getKey());}
   if(p.kind.equals("projection")&&!e.level.getEntities((Entity)null,box(p.bounds),x->!(x instanceof Player)).isEmpty())throw new IllegalArgumentException("区域新出现实体，重新检查");
   e.job=null;run=new Run("tx-"+UUID.randomUUID(),p);run.requestId=request;AssemblyGuard.hold(e.level,run.id,p.bounds);AssemblyGuard.holdEntities(e.level,run.id,new HashSet<>(p.mobs.stream().map(Mob::id).toList()));AssemblyGuard.release(e.level,p.token);prepared=null;run.phase="BUILDING";run.save();e.reply(request,true,run.progress(),null);return true;
  }
 }
 boolean matches(BlockPos pos,BlockState state,String tag){return e.level.getBlockState(pos).equals(state)&&text(be(pos)).equals(tag);}
 Entity entity(CompoundTag tag,UUID id){var problems=new ProblemReporter.Collector();Entity entity=EntityType.create(TagValueInput.create(problems,e.server.registryAccess(),tag),e.level,new EntitySpawnRequest(EntitySpawnReason.COMMAND,false)).orElseThrow(()->new IllegalArgumentException("无法加载实体"));if(entity instanceof Player)throw new IllegalArgumentException("禁止玩家实体");if(!problems.getReport().isBlank())throw new IllegalArgumentException("实体数据错误："+problems.getReport());entity.setUUID(id);return entity;}
 void set(Cell c,boolean reverse){if(reverse){int[] q=Engine.coords(c.pos);clearTicks(new int[]{q[0],q[1],q[2],q[0],q[1],q[2]});}BlockState state=reverse?c.before:c.after;String tag=reverse?c.beforeNbt:c.afterNbt;
  if(e.level.getBlockEntity(c.pos)!=null)e.level.removeBlockEntity(c.pos);
  if(!e.level.getBlockState(c.pos).equals(state)&&!e.level.setBlock(c.pos,state,Block.UPDATE_CLIENTS|Block.UPDATE_SKIP_ALL_SIDEEFFECTS))throw new IllegalStateException("设置方块失败："+c.pos);
  if(!tag.isEmpty()){var b=BlockEntity.loadStatic(c.pos,state,ProjectionData.parse(tag),e.server.registryAccess());if(b==null)throw new IllegalStateException("恢复方块实体失败："+c.pos);e.level.setBlockEntity(b);b.setChanged();}
  if(!matches(c.pos,state,tag))throw new IllegalStateException("方块/数据回读不一致："+c.pos);
 }
 static int[] bounds(Collection<BlockPos> list){int[] b={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};for(BlockPos p:list){int[] q=Engine.coords(p);for(int k=0;k<3;k++){b[k]=Math.min(b[k],q[k]);b[k+3]=Math.max(b[k+3],q[k]);}}return b;}
 static AABB box(int[] b){return new AABB(b[0],b[1],b[2],b[3]+1,b[4]+1,b[5]+1);}
 JsonObject tickData(ScheduledTick<?> tick,String kind,String id){JsonObject t=new JsonObject();t.add("pos",FilesIO.GSON.toJsonTree(Engine.coords(tick.pos())));t.addProperty("id",id);t.addProperty("type",kind);t.addProperty("delay",Math.max(0,tick.triggerTick()-e.level.getGameTime()));t.addProperty("priority",tick.priority().getValue());return t;}
 void clearTicks(int[] b){var box=new net.minecraft.world.level.levelgen.structure.BoundingBox(b[0],b[1],b[2],b[3],b[4],b[5]);e.level.getBlockTicks().clearArea(box);e.level.getFluidTicks().clearArea(box);e.level.clearBlockEvents(box);}
 void validateTick(JsonObject t){String id=t.get("id").getAsString();var registry=t.get("type").getAsString().equals("fluid")?BuiltInRegistries.FLUID:BuiltInRegistries.BLOCK;if(!registry.containsKey(Identifier.parse(id)))throw new IllegalArgumentException("未知刻时目标："+id);int priority=Plan.integer(t.get("priority"));if(priority< -3||priority>3)throw new IllegalArgumentException("刻时优先级错误");}
 void schedule(JsonObject t){int[] q=Plan.triple(t.get("pos"));BlockPos p=new BlockPos(q[0],q[1],q[2]);int delay=Plan.integer(t.get("delay"));var priority=TickPriority.byValue(Plan.integer(t.get("priority")));if(t.get("type").getAsString().equals("fluid"))e.level.scheduleTick(p,BuiltInRegistries.FLUID.getValue(Identifier.parse(t.get("id").getAsString())),delay,priority);else e.level.scheduleTick(p,BuiltInRegistries.BLOCK.getValue(Identifier.parse(t.get("id").getAsString())),delay,priority);}
 public class Run {
  final String id;final Prepared p;final ChunkLease.Scope scope;public String phase="PAUSED",error="";public int stage=0,index=0,applied=0,mobApplied=0;boolean planSaved=false;int blockLimit=0,mobLimit=0;public boolean undo=false;final List<Object> conflicts=new ArrayList<>();CompletableFuture<Void> barrier=CompletableFuture.completedFuture(null);
  String requestId;
  Run(String id,Prepared p){this.id=id;this.p=p;
   List<BlockPos> positions=new ArrayList<>(p.cells.stream().map(Cell::pos).toList());
   for(Mob m:p.mobs){for(String n:List.of(m.before,m.after))if(!n.isEmpty()){var tag=ProjectionData.parse(n);var list=tag.getList("Pos").or(()->tag.getList("pos")).orElseThrow(()->new IllegalArgumentException("实体记录缺少位置："+m.id));if(list.size()!=3)throw new IllegalArgumentException("实体位置损坏："+m.id);positions.add(BlockPos.containing(list.getDouble(0).orElseThrow(),list.getDouble(1).orElseThrow(),list.getDouble(2).orElseThrow()));}var ent=e.level.getEntity(m.id);if(ent!=null)positions.add(ent.blockPosition());}
   scope=p.scope=new ChunkLease.Scope(e.level,positions);
  }
  boolean running(){return phase.equals("BUILDING")||phase.equals("UNDOING");}
  public Map<String,Object> progress(){return Map.of("id",id,"name",p.name,"kind",p.kind,"phase",phase,"stage",stage,"done",index,"blocks_applied",applied,"entities_applied",mobApplied,"conflicts",List.copyOf(conflicts),"error",error);}
  void save(){
   var progress=progress();boolean undoSnapshot=undo;int blockHigh=blockLimit,mobHigh=mobLimit;
   CompletableFuture<Void> old=barrier;boolean writePlan=!planSaved;planSaved=true;
   barrier=e.write(()->{old.join();
    if(writePlan){var j=new LinkedHashMap<String,Object>();j.put("version",3);j.put("world",e.world);j.put("dimension",e.dimension);j.put("id",id);j.put("kind",p.kind);j.put("name",p.name);j.put("bounds",p.bounds);j.put("cells",p.cells.stream().map(Cell::data).toList());j.put("mobs",p.mobs.stream().map(Mob::data).toList());j.put("activation",p.activation.stream().map(Engine::coords).toList());j.put("ticks",p.ticks);j.put("before_ticks",p.beforeTicks);j.put("progress",progress);j.put("undo",undoSnapshot);j.put("block_highwater",blockHigh);j.put("mob_highwater",mobHigh);FilesIO.atomic(path(id).resolve("journal.json"),j);if(requestId!=null)FilesIO.atomic(path(id).resolve("request.json"),Map.of("id",requestId,"session",e.session,"world",e.world,"dimension",e.dimension,"job",id));}
    FilesIO.atomic(path(id).resolve("progress.json"),Map.of("progress",progress,"undo",undoSnapshot,"block_highwater",blockHigh,"mob_highwater",mobHigh,"bounds",p.bounds,"id",id,"entity_ids",p.mobs.stream().map(m->m.id.toString()).toList()));
   });
  }

  void tick(long deadline){if(!barrier.isDone())return;barrier.join();int count=0;
   if(stage==0){AssemblyGuard.hold(e.level,id,p.bounds);AssemblyGuard.holdEntities(e.level,id,new HashSet<>(p.mobs.stream().map(Mob::id).toList()));if(!undo&&p.kind.equals("demolish"))clearTicks(p.bounds);stage=1;index=0;save();return;}
   if(!undo){
    if(stage==1){if(index>=blockLimit&&index<p.cells.size()){blockLimit=Math.min(index+InteractionBudget.steps(System.nanoTime()),p.cells.size());save();return;}while(index<blockLimit&&count++<InteractionBudget.steps(System.nanoTime())&&System.nanoTime()<deadline){Cell c=p.cells.get(index);e.loaded(c.pos);if(!matches(c.pos,c.before,c.beforeNbt)&&!matches(c.pos,c.after,c.afterNbt))throw new IllegalArgumentException("施工区域变化："+c.pos);set(c,false);index++;applied=index;}if(index==p.cells.size()){stage=2;index=0;}save();return;}
    if(stage==2){if(index>=mobLimit&&index<p.mobs.size()){mobLimit=Math.min(index+32,p.mobs.size());save();return;}while(index<mobLimit&&count++<32&&System.nanoTime()<deadline){Mob m=p.mobs.get(index);Entity current=e.level.getEntity(m.id);if(m.after.isEmpty()){if(current!=null){if(current instanceof Player)throw new IllegalStateException("玩家不能删除");if(current instanceof net.minecraft.world.Container cargo)cargo.clearContent();current.discard();}}else if(current==null){Entity created=entity(ProjectionData.parse(m.after),m.id);created.addTag("aibuild:"+id);if(!e.level.addFreshEntity(created))throw new IllegalStateException("实体部署失败");}index++;mobApplied=index;}if(index==p.mobs.size()){stage=3;index=0;}save();return;}
    if(stage==3){while(index<p.cells.size()&&count++<InteractionBudget.steps(System.nanoTime())&&System.nanoTime()<deadline){Cell c=p.cells.get(index++);if(!matches(c.pos,c.after,c.afterNbt))conflicts.add(Map.of("pos",Engine.coords(c.pos),"reason","施工回读不同"));}if(index<p.cells.size())return;
     for(Mob m:p.mobs)if((e.level.getEntity(m.id)!=null)==m.after.isEmpty())conflicts.add(Map.of("uuid",m.id.toString(),"reason","实体部署结果不同"));
     if(!conflicts.isEmpty()){phase="PARTIAL";save();return;}stage=4;index=0;save();return;}
    if(stage==4){while(index<p.cells.size()&&count++<InteractionBudget.steps(System.nanoTime())&&System.nanoTime()<deadline){Cell c=p.cells.get(index++);if(p.kind.equals("projection")){var fluid=e.level.getFluidState(c.pos);if(!fluid.isEmpty())e.level.scheduleTick(c.pos,fluid.getType(),fluid.getType().getTickDelay(e.level));if(c.after.getBlock() instanceof FallingBlock||c.after.getBlock() instanceof FireBlock)e.level.scheduleTick(c.pos,c.after.getBlock(),1);}}if(index<p.cells.size()){save();return;}AssemblyGuard.release(e.level,id);for(var t:p.ticks)schedule(t);for(BlockPos pos:p.activation)e.initialize(pos,e.level.getBlockState(pos));phase="COMPLETE";stage=5;save();return;}
   }else{
    // Delete newly introduced entities before restoring supports and containers.
    if(stage==1){while(index<mobApplied&&count++<32&&System.nanoTime()<deadline){Mob m=p.mobs.get(mobApplied-1-index++);Entity ent=e.level.getEntity(m.id);if(m.before.isEmpty()){if(ent==null){conflicts.add(Map.of("uuid",m.id.toString(),"reason","记录位置已加载，实体仍未找到，可能已移动或删除；未确认清除"));continue;}if(ent!=null){CompoundTag actual=snapshot(ent,e.level),expected=ProjectionData.parse(m.after);if(!sameEntityContents(actual,expected)){conflicts.add(Map.of("uuid",m.id.toString(),"reason","实体内容已改变"));continue;}if(ent instanceof net.minecraft.world.Container cargo)cargo.clearContent();ent.discard();}}}if(index==mobApplied){stage=2;index=0;}save();return;}
    if(stage==2){while(index<applied&&count++<InteractionBudget.steps(System.nanoTime())&&System.nanoTime()<deadline){Cell c=p.cells.get(applied-1-index++);e.loaded(c.pos);if(matches(c.pos,c.before,c.beforeNbt))continue;if(matches(c.pos,c.after,c.afterNbt))set(c,true);else conflicts.add(Map.of("pos",Engine.coords(c.pos),"reason","玩家或运行状态已改变"));}if(index==applied){stage=3;index=0;}save();return;}
    if(stage==3){while(index<mobApplied&&count++<32&&System.nanoTime()<deadline){Mob m=p.mobs.get(index++);if(!m.before.isEmpty()){if(e.level.getEntity(m.id)!=null){conflicts.add(Map.of("uuid",m.id.toString(),"reason","原UUID已存在"));continue;}Entity restored=entity(ProjectionData.parse(m.before),m.id);if(!e.level.addFreshEntity(restored))throw new IllegalStateException("实体恢复失败");}}if(index<mobApplied){save();return;}AssemblyGuard.release(e.level,id);for(var t:p.beforeTicks){int[] q=Plan.triple(t.get("pos"));BlockPos pos=new BlockPos(q[0],q[1],q[2]);if(p.cells.stream().anyMatch(c->c.pos.equals(pos)&&matches(pos,c.before,c.beforeNbt)))schedule(t);}phase=conflicts.isEmpty()?"UNDONE":"PARTIAL";stage=5;save();}
   }
  }
 }
 static boolean sameEntityContents(CompoundTag a,CompoundTag b){for(String k:List.of("id","Items","Item","CustomName","components","items","item","custom_name"))if(!Objects.equals(a.get(k),b.get(k)))return false;return true;}
 Run restore(JsonObject j){if(!e.world.equals(j.get("world").getAsString())||!e.dimension.equals(j.get("dimension").getAsString()))throw new IllegalArgumentException("事务世界不符");Prepared p=new Prepared();p.kind=j.get("kind").getAsString();p.name=j.get("name").getAsString();p.bounds=FilesIO.GSON.fromJson(j.get("bounds"),int[].class);
  for(var v:j.getAsJsonArray("cells")){var c=v.getAsJsonObject();int[] q=Plan.triple(c.get("pos"));p.cells.add(new Cell(new BlockPos(q[0],q[1],q[2]),e.resolve(c.get("before").getAsString()),c.get("before_nbt").getAsString(),e.resolve(c.get("after").getAsString()),c.get("after_nbt").getAsString()));}
  for(var v:j.getAsJsonArray("mobs")){var m=v.getAsJsonObject();p.mobs.add(new Mob(UUID.fromString(m.get("uuid").getAsString()),m.get("before").getAsString(),m.get("after").getAsString()));}
  for(var v:j.getAsJsonArray("activation")){int[] q=Plan.triple(v);p.activation.add(new BlockPos(q[0],q[1],q[2]));}for(var v:j.getAsJsonArray("ticks"))p.ticks.add(v.getAsJsonObject());if(j.has("before_ticks"))for(var v:j.getAsJsonArray("before_ticks"))p.beforeTicks.add(v.getAsJsonObject());Run r=new Run(j.get("id").getAsString(),p);r.planSaved=true;var progress=j.getAsJsonObject("progress");r.phase=progress.get("phase").getAsString();r.stage=Plan.integer(progress.get("stage"));r.index=Plan.integer(progress.get("done"));r.applied=Plan.integer(progress.get("blocks_applied"));r.mobApplied=Plan.integer(progress.get("entities_applied"));r.undo=j.get("undo").getAsBoolean();r.blockLimit=j.has("block_highwater")?Plan.integer(j.get("block_highwater")):r.applied;r.mobLimit=j.has("mob_highwater")?Plan.integer(j.get("mob_highwater")):r.mobApplied;if(!r.undo){r.applied=Math.max(r.applied,r.blockLimit);r.mobApplied=Math.max(r.mobApplied,r.mobLimit);}for(var v:progress.getAsJsonArray("conflicts"))r.conflicts.add(v);return r;}
}
