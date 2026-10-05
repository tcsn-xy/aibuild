package me.xiaoyan.aibuild;
import com.google.gson.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;
import net.minecraft.util.ProblemReporter;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;

/** Local live-snapshot relocation. No caller-supplied NBT and no source/target inventory duplication. */
public final class Moves {
 final Map<BlockState,String> names=new IdentityHashMap<>();final Engine e;Prepared prepared;public Run run;boolean loading;long generation;String pendingRequest;
 Moves(Engine e){this.e=e;}
 record Value(String state,String nbt){Map<String,Object> data(){return Map.of("state",state,"nbt",nbt);}}
 record Cell(BlockPos pos,Value before,Value after){Map<String,Object> data(){return Map.of("pos",Engine.coords(pos),"before",before.data(),"after",after.data());}}
 record Mob(UUID id,String before,String after){Map<String,Object> data(){return Map.of("uuid",id.toString(),"before",before,"after",after);}}
 static final Value AIR=new Value("minecraft:air","");
 class Prepared {
  String token=UUID.randomUUID().toString(),name="建筑现场搬迁";long expires;List<Cell> cells=new ArrayList<>();List<Mob> mobs=new ArrayList<>();List<JsonObject> ticks=new ArrayList<>(),beforeTicks=new ArrayList<>();Set<BlockPos> positions;ChunkLease.Scope scope;int[] offset;
  Map<String,Object> summary(){var map=new LinkedHashMap<String,Object>();map.put("token",token);map.put("name",name);map.put("offset",offset);map.put("changes",cells.stream().filter(c->!c.before.equals(c.after)).count());map.put("containers",cells.stream().filter(c->!c.before.nbt.isEmpty()).count());map.put("entities",mobs.size());map.put("batches_64",cells.stream().map(c->List.of(Math.floorDiv(c.pos.getX(),64),Math.floorDiv(c.pos.getY(),64),Math.floorDiv(c.pos.getZ(),64))).distinct().count());map.put("inventory_snapshots",cells.stream().filter(c->!c.before.nbt.isEmpty()||!c.after.nbt.isEmpty()).map(Cell::data).toList());return map;}
 }
 boolean busy(){return loading||run!=null&&run.running();}
 boolean unfinished(){return run!=null&&!Set.of("COMPLETE","UNDONE","CONFLICT").contains(run.phase);}
 ChunkLease.Scope scope(){return run!=null&&run.running()?run.p.scope:prepared==null?null:prepared.scope;}
 public Map<String,Object> status(){return run==null?Map.of("phase",loading?"READING":"IDLE"):run.progress();}
 void expire(){if(prepared!=null&&System.nanoTime()>prepared.expires)clearPrepared();}
 void clearPrepared(){if(prepared!=null)MoveGuard.release(e.level,prepared.token);prepared=null;}
 void fail(String error){if(run!=null&&run.running()){run.phase=run.requestUndo&&run.stage==6?"CONFLICT":"PAUSED";run.error=error;run.save();}clearPrepared();}
 void close(){generation++;loading=false;clearPrepared();if(run!=null&&run.running()){run.phase="PAUSED";run.save();}}
 void handle(String op,String request,JsonObject r){
  if(op.equals("move-check")){e.idle();clearPrepared();e.invalidatePrepared();e.transactions.clearPrepared();e.work=new Check(request,r);return;}
  if(op.equals("move")){e.idle();if(prepared==null||System.nanoTime()>prepared.expires||!prepared.token.equals(r.get("token").getAsString()))throw new IllegalArgumentException("搬迁检查令牌无效，请重新检查");prepared.expires=Long.MAX_VALUE;e.work=new Verify(request,prepared,()->{Prepared p=prepared;run=new Run("mv-"+UUID.randomUUID(),p);run.requestId=request;MoveGuard.hold(e.level,run.id,p.positions);clearPrepared();run.phase="MOVING";run.save();e.reply(request,true,run.progress(),null);});return;}
  if(op.equals("move-pause")||op.equals("move-cancel")){generation++;if(loading&&pendingRequest!=null)e.reply(pendingRequest,false,null,"搬迁记录读取已取消");loading=false;if(e.work instanceof Check||e.work instanceof Verify){e.work.cancel();e.work=null;}clearPrepared();if(run!=null&&run.running()){run.phase="PAUSED";run.error=op.endsWith("cancel")?"已停止；物品快照封存，需继续或完整回滚后解锁":"";run.save();}e.chunks.close();e.reply(request,true,status(),null);return;}
  if(op.equals("move-resume")||op.equals("move-undo")){
   if(e.work!=null||busy()||e.legacyBusy()||e.transactions.busy())throw new IllegalStateException("先暂停当前任务");String id=FilesIO.id(r.get("job").getAsString());if(unfinished()&&!run.id.equals(id))throw new IllegalStateException("先完成当前搬迁或回滚");clearPrepared();e.invalidatePrepared();e.transactions.clearPrepared();long gen=++generation;loading=true;pendingRequest=request;
   e.io.execute(()->{try{var j=FilesIO.read(path(id).resolve("journal.json"));var c=FilesIO.read(path(id).resolve("progress.json"));e.server.execute(()->{if(!e.connected||gen!=generation)return;try{loading=false;run=restore(j,c);MoveGuard.hold(e.level,run.id,run.p.positions);if(op.endsWith("undo")){if(run.phase.equals("UNDONE")){MoveGuard.release(e.level,run.id);e.reply(request,true,status(),null);return;}run.requestUndo=true;}else if(Set.of("COMPLETE","UNDONE","CONFLICT").contains(run.phase))throw new IllegalStateException("任务已完成");run.error="";run.verifyResume=!run.requestUndo;run.phase=run.reverse?"UNDOING":"MOVING";run.save();e.publish();e.reply(request,true,status(),null);}catch(Exception x){fail(x.getMessage());e.reply(request,false,null,x.getMessage());}});}catch(Exception x){e.server.execute(()->{if(gen!=generation)return;loading=false;e.reply(request,false,null,x.getMessage());});}});return;
  }throw new IllegalArgumentException("未知搬迁操作");
 }
 Value read(BlockPos p){e.loaded(p);return new Value(names.computeIfAbsent(e.level.getBlockState(p),Engine::describe),Transactions.text(e.transactions.be(p)));}
 boolean matches(BlockPos p,Value v){e.loaded(p);return e.level.getBlockState(p).equals(e.resolve(v.state))&&Transactions.text(e.transactions.be(p)).equals(v.nbt);}
 static BlockPos pos(JsonElement j){int[] q=Plan.triple(j);return new BlockPos(q[0],q[1],q[2]);}
 static Value readValue(JsonObject j){return new Value(j.get("state").getAsString(),j.get("nbt").getAsString());}
 void apply(BlockPos p,Value v){BlockState state=e.resolve(v.state);if(e.level.getBlockEntity(p)!=null)e.level.removeBlockEntity(p);if(!e.level.getBlockState(p).equals(state)&&!e.level.setBlock(p,state,Block.UPDATE_CLIENTS|Block.UPDATE_SKIP_ALL_SIDEEFFECTS))throw new IllegalStateException("搬迁设置失败："+p);if(!v.nbt.isEmpty()){var be=BlockEntity.loadStatic(p,state,ProjectionData.parse(v.nbt),e.server.registryAccess());if(be==null)throw new IllegalStateException("搬迁NBT恢复失败："+p);e.level.setBlockEntity(be);be.setChanged();}if(!e.level.getBlockState(p).equals(state)||!v.nbt.isEmpty()&&!matches(p,v))throw new IllegalStateException("搬迁写入回读失败："+p);}
 class Check extends Engine.Work {
  Prepared p=new Prepared();Map<BlockPos,String> source=new LinkedHashMap<>(),expected=new LinkedHashMap<>();Map<BlockPos,Value> after=new LinkedHashMap<>(),before=new LinkedHashMap<>();List<BlockPos> all;int index=0,stage=0;Map<BlockPos,BlockState> future=new HashMap<>();List<int[]> regions=new ArrayList<>();Set<BlockPos> excluded=new HashSet<>();
  Check(String request,JsonObject r){e.super(request);if(r.has("exclude"))for(var v:r.getAsJsonArray("exclude"))excluded.add(pos(v));p.offset=Plan.triple(r.get("offset"));if(Arrays.stream(p.offset).anyMatch(x->Math.abs((long)x)>512)||Arrays.stream(p.offset).allMatch(x->x==0))throw new IllegalArgumentException("移动偏移必须非零且各轴不超过512格");
   for(var v:r.getAsJsonArray("source")){var b=v.getAsJsonObject();BlockPos at=pos(b.get("pos"));String state=b.get("expected").getAsString();if(source.put(at,state)!=null)throw new IllegalArgumentException("重复源坐标");expected.put(at,state);after.put(at,AIR);}
   if(source.isEmpty()||source.size()>262144)throw new IllegalArgumentException("源建筑非空气方块数量超限");
   for(var v:r.getAsJsonArray("cleanup")){var b=v.getAsJsonObject();BlockPos at=pos(b.get("pos"));if(source.containsKey(at))throw new IllegalArgumentException("清理与搬迁源重复");expected.put(at,b.get("expected").getAsString());after.put(at,new Value(b.get("restore").getAsString(),""));}
   for(var v:r.getAsJsonArray("additions")){var b=v.getAsJsonObject();BlockPos at=pos(b.get("pos"));expected.putIfAbsent(at,b.get("expected").getAsString());after.put(at,new Value(b.get("state").getAsString(),""));}
   for(BlockPos at:source.keySet())after.putIfAbsent(at.offset(p.offset[0],p.offset[1],p.offset[2]),AIR);
   if(after.size()>524288)throw new IllegalArgumentException("搬迁联合范围过大");
   for(var v:r.getAsJsonArray("regions")){var a=v.getAsJsonObject();BlockPos o=pos(a.get("origin"));int[] sz=Plan.triple(a.get("size"));for(int n:sz)if(n<1||n>64)throw new IllegalArgumentException("每个源区域必须不超过64格");regions.add(new int[]{o.getX(),o.getY(),o.getZ(),o.getX()+sz[0]-1,o.getY()+sz[1]-1,o.getZ()+sz[2]-1});}
   if(regions.isEmpty()||regions.size()>16)throw new IllegalArgumentException("需要1至16个源区域");for(BlockPos at:source.keySet())if(regions.stream().noneMatch(b->Transactions.box(b).contains(Vec3.atCenterOf(at))))throw new IllegalArgumentException("源方块不在声明区域");
   all=new ArrayList<>(after.keySet());if(all.stream().anyMatch(excluded::contains))throw new IllegalArgumentException("搬迁/基础/清理覆盖明确排除的位置");p.positions=MoveGuard.index(all);scope=p.scope=new ChunkLease.Scope(e.level,all);
   for(BlockPos at:all)if(AssemblyGuard.contains(e.level,at))throw new IllegalArgumentException("此位置已有未完成任务："+at);
   MoveGuard.hold(e.level,p.token,p.positions);
  }
  @Override void fail(String s){MoveGuard.release(e.level,p.token);super.fail(s);}
  boolean step(long deadline){MoveGuard.available(e.level,p.positions);int count=0;
   if(stage==0){while(index<all.size()&&count++<512&&System.nanoTime()<deadline){BlockPos at=all.get(index++);Value v=read(at);if(expected.containsKey(at)&&!v.state.equals(expected.get(at)))throw new IllegalArgumentException("现场已改变，请刷新方案："+at);if(!source.containsKey(at)&&!expected.containsKey(at)&&!v.state.equals("minecraft:air"))throw new IllegalArgumentException("目标存在未授权障碍："+at);if(!v.nbt.isEmpty()&&!source.containsKey(at))throw new IllegalArgumentException("清理/基础位置有容器或方块实体，禁止删除："+at);if(v.state.startsWith("minecraft:moving_piston"))throw new IllegalArgumentException("不搬迁运行中的活塞");before.put(at,v);}if(index<all.size())return false;
    for(var entry:source.entrySet()){BlockPos from=entry.getKey(),to=from.offset(p.offset[0],p.offset[1],p.offset[2]);Value v=before.get(from);String nbt=v.nbt;if(!nbt.isEmpty()){CompoundTag tag=ProjectionData.parse(nbt);tag.putInt("x",to.getX());tag.putInt("y",to.getY());tag.putInt("z",to.getZ());BlockEntity copy=BlockEntity.loadStatic(to,e.resolve(v.state),tag,e.server.registryAccess());if(copy==null||!copy.saveWithFullMetadata(e.server.registryAccess()).equals(tag))throw new IllegalArgumentException("方块实体不能无损搬迁："+from);nbt=tag.toString();}after.put(to,new Value(v.state,nbt));}
    for(BlockPos at:all){Value a=after.get(at);future.put(at,e.resolve(a.state));p.cells.add(new Cell(at,before.get(at),a));}
    p.cells.sort(Comparator.comparingInt((Cell c)->Math.floorDiv(c.pos.getY(),64)).thenComparingInt(c->Math.floorDiv(c.pos.getX(),64)).thenComparingInt(c->Math.floorDiv(c.pos.getZ(),64)).thenComparingInt(c->c.pos.getY()));stage=1;index=0;return false;
   }
   if(stage==1){while(index<all.size()&&count++<512&&System.nanoTime()<deadline){BlockPos at=all.get(index++);if(!future.get(at).isAir())e.validatePlacement(at,future.get(at),future);}if(index<all.size())return false;
    Set<UUID> ids=new HashSet<>();for(int[] b:regions)for(Entity entity:e.level.getEntities((Entity)null,Transactions.box(b),x->!(x instanceof Player))){if(!ids.add(entity.getUUID()))continue;if(excluded.stream().anyMatch(at->entity.getBoundingBox().intersects(new AABB(at))))throw new IllegalArgumentException("展示实体接触排除位置，停止并重新划定范围："+entity.getUUID());String type=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();if(!Set.of("minecraft:item_frame","minecraft:glow_item_frame","minecraft:painting","minecraft:armor_stand").contains(type)||entity.isPassenger()||!entity.getPassengers().isEmpty())throw new IllegalArgumentException("源区存在尚不支持搬迁的实体，保留并停止："+type+" "+entity.getUUID());String original=Transactions.text(Transactions.snapshot(entity,e.level));CompoundTag shifted=ProjectionData.parse(original);var xyz=shifted.getList("Pos").orElseThrow();ListTag target=new ListTag();for(int k=0;k<3;k++)target.add(DoubleTag.valueOf(xyz.getDouble(k).orElseThrow()+p.offset[k]));shifted.put("Pos",target);if(shifted.contains("block_pos")){int[] bp=shifted.getIntArray("block_pos").orElseThrow();for(int k=0;k<3;k++)bp[k]+=p.offset[k];shifted.putIntArray("block_pos",bp);}p.mobs.add(new Mob(entity.getUUID(),original,shifted.toString()));}
    // Capture only scheduled events at edited coordinates; translate source events once.
    Set<Long> visited=new HashSet<>();for(BlockPos at:all){long key=((long)(at.getX()>>4)<<32)^((at.getZ()>>4)&0xffffffffL);if(!visited.add(key))continue;var chunk=e.level.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4);((net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.block.Block>)chunk.getBlockTicks()).getAll().filter(t->p.positions.contains(t.pos())).forEach(t->tick(t,"block",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(t.type()).toString()));((net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.material.Fluid>)chunk.getFluidTicks()).getAll().filter(t->p.positions.contains(t.pos())).forEach(t->tick(t,"fluid",net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(t.type()).toString()));}
    p.expires=System.nanoTime()+60_000_000_000L;prepared=p;e.reply(request,true,p.summary(),null);return true;
   }return false;
  }
  void tick(net.minecraft.world.ticks.ScheduledTick<?> tick,String type,String id){JsonObject t=e.transactions.tickData(tick,type,id);p.beforeTicks.add(t);if(source.containsKey(tick.pos())){t=t.deepCopy();t.add("pos",FilesIO.GSON.toJsonTree(Engine.coords(tick.pos().offset(p.offset[0],p.offset[1],p.offset[2]))));p.ticks.add(t);}}
 }
 class Verify extends Engine.Work {
  Prepared p;Runnable done;int index;
  Verify(String id,Prepared p,Runnable done){e.super(id);this.p=p;this.done=done;scope=p.scope;}
  boolean step(long deadline){MoveGuard.available(e.level,p.positions);int n=0;while(index<p.cells.size()&&n++<512&&System.nanoTime()<deadline){Cell c=p.cells.get(index++);if(!matches(c.pos,c.before))throw new IllegalStateException("现场或库存变化，重新检查："+c.pos);}if(index<p.cells.size())return false;for(Mob m:p.mobs){Entity ent=e.level.getEntity(m.id);if(ent==null||!Transactions.text(Transactions.snapshot(ent,e.level)).equals(m.before))throw new IllegalStateException("展示实体发生变化："+m.id);}done.run();return true;}
 }
 Path path(String id){return e.journals.resolve("moves").resolve(id);}
 public class Run {
  final String id;final Prepared p;String requestId;String phase="PAUSED",error="";int stage,index;boolean reverse,requestUndo,saved,verifyResume;int verifyIndex;CompletableFuture<Void> barrier=CompletableFuture.completedFuture(null);
  Run(String id,Prepared p){this.id=id;this.p=p;}
  boolean running(){return phase.equals("MOVING")||phase.equals("UNDOING");}
  public Map<String,Object> progress(){return Map.of("id",id,"phase",phase,"stage",stage,"done",index,"total",p.cells.size(),"reverse",reverse,"error",error);}
  void save(){var progress=progress();boolean first=!saved;saved=true;boolean rev=reverse;var previous=barrier;barrier=e.write(()->{previous.join();if(first){FilesIO.atomic(path(id).resolve("journal.json"),Map.of("version",1,"id",id,"world",e.world,"dimension",e.dimension,"offset",p.offset,"cells",p.cells.stream().map(Cell::data).toList(),"entities",p.mobs.stream().map(Mob::data).toList(),"ticks",p.ticks,"before_ticks",p.beforeTicks));if(requestId!=null)FilesIO.atomic(path(id).resolve("request.json"),Map.of("id",requestId,"session",e.session,"world",e.world,"dimension",e.dimension,"job",id));}FilesIO.atomic(path(id).resolve("progress.json"),progress);});}
  Value target(Cell c){return reverse?c.before:c.after;}
  Value expected(Cell c,int i){if(stage==0)return reverse?c.after:c.before;if(stage==1)return i<index?AIR:(reverse?c.after:c.before);if(stage==2)return i<index?new Value(target(c).state,""):AIR;if(stage==3)return i<index?target(c):new Value(target(c).state,"");return target(c);}
  void tick(long deadline){if(!barrier.isDone())return;barrier.join();MoveGuard.available(e.level,p.positions);
   if(requestUndo){int checked=0;while(verifyIndex<p.cells.size()&&checked++<512&&System.nanoTime()<deadline){Cell c=p.cells.get(verifyIndex);Value v=expected(c,verifyIndex);if(!matchesStage(c.pos,v))throw new IllegalStateException("回滚前发现后续改动，整批停止以防复制库存："+c.pos);verifyIndex++;}if(verifyIndex<p.cells.size())return;verifyIndex=0;for(Mob m:p.mobs){Entity entity=e.level.getEntity(m.id);if(entity==null)throw new IllegalStateException("展示实体失踪："+m.id);String current=Transactions.text(Transactions.snapshot(entity,e.level));String expected=stage>=5?(reverse?m.before:m.after):(reverse?m.after:m.before);if(!current.equals(expected)&&!(stage==4&&(current.equals(m.before)||current.equals(m.after))))throw new IllegalStateException("展示实体内容已改变，整批停止回滚："+m.id);}reverse=true;requestUndo=false;stage=0;index=0;save();return;}
   int n=0;
   if(verifyResume){while(verifyIndex<p.cells.size()&&n++<512&&System.nanoTime()<deadline){Cell c=p.cells.get(verifyIndex);if(!matchesStage(c.pos,expected(c,verifyIndex)))throw new IllegalStateException("暂停后现场变化，保留封存快照："+c.pos);verifyIndex++;}if(verifyIndex<p.cells.size())return;verifyResume=false;verifyIndex=0;return;}
   if(stage==0){ // Ensure durable escrow exists before removing any inventory.
    while(index<p.cells.size()&&n++<InteractionBudget.steps(System.nanoTime())&&System.nanoTime()<deadline){Cell c=p.cells.get(index++);Value want=reverse?c.after:c.before;if(!reverse&&!matches(c.pos,want))throw new IllegalStateException("搬迁前状态变化："+c.pos);}if(index<p.cells.size())return;stage=1;index=0;save();return;
   }
   if(stage>=1&&stage<=3){while(index<p.cells.size()&&n++<InteractionBudget.steps(System.nanoTime())&&System.nanoTime()<deadline){Cell c=p.cells.get(index);Value want=target(c);if(stage==1){int[] q=Engine.coords(c.pos);e.transactions.clearTicks(new int[]{q[0],q[1],q[2],q[0],q[1],q[2]});apply(c.pos,AIR);}else if(stage==2){if(!want.state.equals("minecraft:air"))apply(c.pos,new Value(want.state,""));}else if(!want.nbt.isEmpty())apply(c.pos,want);index++;}if(index==p.cells.size()){stage++;index=0;}save();return;}
   if(stage==4){for(Mob m:p.mobs){Entity ent=e.level.getEntity(m.id);if(ent==null)throw new IllegalStateException("实体未找到，禁止复制："+m.id);CompoundTag tag=ProjectionData.parse(reverse?m.before:m.after);var xyz=tag.getList("Pos").orElseThrow();ent.setPos(xyz.getDouble(0).orElseThrow(),xyz.getDouble(1).orElseThrow(),xyz.getDouble(2).orElseThrow());var problems=new ProblemReporter.Collector();ent.load(TagValueInput.create(problems,e.server.registryAccess(),tag));if(!problems.getReport().isBlank())throw new IllegalStateException(problems.getReport());ent.postDataManipulated();}stage=5;index=0;save();return;}
   if(stage==5){while(index<p.cells.size()&&n++<512&&System.nanoTime()<deadline){Cell c=p.cells.get(index++);if(!matches(c.pos,target(c)))throw new IllegalStateException("搬迁最终状态/NBT不一致："+c.pos);}if(index<p.cells.size())return;for(Mob m:p.mobs){Entity ent=e.level.getEntity(m.id);if(ent==null||!Transactions.text(Transactions.snapshot(ent,e.level)).equals(reverse?m.before:m.after))throw new IllegalStateException("实体搬迁回读不一致："+m.id);}for(var t:reverse?p.beforeTicks:p.ticks)e.transactions.schedule(t);phase=reverse?"UNDONE":"COMPLETE";stage=6;save();return;}
  }
  boolean matchesStage(BlockPos at,Value expected){if(!expected.nbt.isEmpty())return matches(at,expected);return e.level.getBlockState(at).equals(e.resolve(expected.state))&&(e.level.getBlockEntity(at)==null||emptyContainer(at));}
  boolean emptyContainer(BlockPos at){var b=e.level.getBlockEntity(at);if(b==null)return true;BlockState state=e.level.getBlockState(at);if(!(state.getBlock() instanceof EntityBlock eb))return false;var blank=eb.newBlockEntity(at,state);return blank!=null&&b.saveWithFullMetadata(e.server.registryAccess()).equals(blank.saveWithFullMetadata(e.server.registryAccess()));}
 }
 void cleanup(){if(run!=null&&Set.of("COMPLETE","UNDONE","CONFLICT").contains(run.phase)&&run.barrier.isDone()&&!run.barrier.isCompletedExceptionally())MoveGuard.release(e.level,run.id);}
 void tick(long deadline){if(run==null)return;try{if(run.running())run.tick(deadline);if(Set.of("COMPLETE","UNDONE","CONFLICT").contains(run.phase)&&run.barrier.isDone()){run.barrier.join();MoveGuard.release(e.level,run.id);}}catch(Exception x){fail(x.getMessage());e.message="搬迁暂停："+x.getMessage();}}
 Run restore(JsonObject j,JsonObject state){if(!j.get("world").getAsString().equals(e.world)||!j.get("dimension").getAsString().equals(e.dimension))throw new IllegalArgumentException("搬迁记录世界/维度不符");Prepared p=new Prepared();p.offset=Plan.triple(j.get("offset"));for(var v:j.getAsJsonArray("cells")){var c=v.getAsJsonObject();p.cells.add(new Cell(pos(c.get("pos")),readValue(c.getAsJsonObject("before")),readValue(c.getAsJsonObject("after"))));}for(var v:j.getAsJsonArray("entities")){var m=v.getAsJsonObject();p.mobs.add(new Mob(UUID.fromString(m.get("uuid").getAsString()),m.get("before").getAsString(),m.get("after").getAsString()));}for(var t:j.getAsJsonArray("ticks"))p.ticks.add(t.getAsJsonObject());for(var t:j.getAsJsonArray("before_ticks"))p.beforeTicks.add(t.getAsJsonObject());p.positions=MoveGuard.index(p.cells.stream().map(Cell::pos).toList());p.scope=new ChunkLease.Scope(e.level,p.positions);Run r=new Run(j.get("id").getAsString(),p);r.saved=true;r.phase=state.get("phase").getAsString();r.stage=Plan.integer(state.get("stage"));r.index=Plan.integer(state.get("done"));r.reverse=state.get("reverse").getAsBoolean();return r;}
 static void restoreGuards(net.minecraft.server.MinecraftServer server){for(ServerLevel level:server.getAllLevels()){Path root=server.getWorldPath(LevelResource.ROOT).resolve("aibuild-data").resolve(level.dimension().identifier().toString().replace(':','_').replace('/','_')).resolve("moves");if(!Files.exists(root))continue;try(var dirs=Files.list(root)){for(Path dir:dirs.toList()){Path progress=dir.resolve("progress.json");if(!Files.exists(progress))continue;var c=FilesIO.read(progress);if(Set.of("COMPLETE","UNDONE","CONFLICT").contains(c.get("phase").getAsString()))continue;var j=FilesIO.read(dir.resolve("journal.json"));Set<BlockPos> cells=new HashSet<>();for(var v:j.getAsJsonArray("cells"))cells.add(pos(v.getAsJsonObject().get("pos")));MoveGuard.hold(level,j.get("id").getAsString(),cells);}}catch(Exception ex){throw new IllegalStateException("无法恢复搬迁物品保护",ex);}}}
}
