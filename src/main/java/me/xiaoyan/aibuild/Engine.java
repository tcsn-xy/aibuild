package me.xiaoyan.aibuild;
import com.google.gson.*;
import com.mojang.brigadier.StringReader;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** All world access is on the integrated server thread. All file parsing/writes on one IO worker. */
public final class Engine {
 public final MinecraftServer server; public final ServerLevel level; public final UUID owner;
 public final Path bridge,journals; public final String session=UUID.randomUUID().toString(),world,dimension;
 public volatile boolean connected=true;public BlockPos anchor;public Job job;public final Transactions transactions;public final ChunkLease chunks;public final Moves moves;
 final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"aibuild-files");t.setDaemon(true);return t;});
 private final Set<String> received=ConcurrentHashMap.newKeySet();private final Map<String,BlockState> states=new ConcurrentHashMap<>();
 private long loadGeneration;private String loadRequest;private Prepared prepared;Work work;private long tickCount;private boolean loading=false;String message="就绪";
 public Engine(MinecraftServer server,ServerPlayer player,Path bridge,BlockPos anchor){
  this.server=server;level=player.level();owner=player.getUUID();this.bridge=bridge;this.anchor=anchor;
  world=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString();dimension=level.dimension().identifier().toString();
  journals=server.getWorldPath(LevelResource.ROOT).resolve("aibuild-data").resolve(dimension.replace(':','_').replace('/','_'));
  moves=new Moves(this);chunks=new ChunkLease(level);transactions=new Transactions(this);io.scheduleWithFixedDelay(this::poll,0,150,TimeUnit.MILLISECONDS);publish();
 }
 private void poll(){if(!connected)return;try{
  Files.createDirectories(bridge.resolve("requests"));Files.createDirectories(bridge.resolve("results"));
  try(var stream=Files.list(bridge.resolve("requests"))){for(Path p:stream.filter(p->p.getFileName().toString().endsWith(".json")).limit(8).toList()){
   String filename=p.getFileName().toString();String id=filename.substring(0,filename.length()-5);FilesIO.id(id);
   if(received.contains(id)){Files.deleteIfExists(p);continue;}JsonObject request;
   try{request=FilesIO.read(p);if(!id.equals(request.get("id").getAsString()))throw new IllegalArgumentException("文件名与请求ID不一致");}
   catch(Exception e){received.add(id);writeResult(id,false,null,"无效请求："+e.getMessage());Files.deleteIfExists(p);continue;}
   Plan parsed=null;
   try{if(request.has("op")&&request.get("op").getAsString().equals("check"))parsed=Plan.parse(request.getAsJsonObject("plan"));}catch(Exception e){received.add(id);writeResult(id,false,null,e.getMessage());Files.deleteIfExists(p);continue;}
   final Plan parsedPlan=parsed;received.add(id);Files.deleteIfExists(p);
   server.execute(()->{if(!connected)return;try{auth(request);handle(request,id,parsedPlan);}catch(Exception e){reply(id,false,null,e.getMessage());}});
  }}
 }catch(Exception e){BuildBridge.LOG.error("建筑桥接文件读取失败",e);}}
 private void auth(JsonObject r){if(!session.equals(r.get("session").getAsString())||!world.equals(r.get("world").getAsString())||!dimension.equals(r.get("dimension").getAsString()))throw new IllegalArgumentException("会话、世界或维度不匹配，请重新获取连接状态");permission();}
 private ServerPlayer player(){return server.getPlayerList().getPlayer(owner);}
 private void permission(){var p=player();if(p==null||p.level()!=level||!p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)||!server.isSingleplayerOwner(p.nameAndId()))throw new IllegalStateException("房主离线、维度变化或命令权限不足");}
 public void local(String op,String id){permission();if(op.equals("status")){tell(FilesIO.GSON.toJson(status()));return;}JsonObject r=new JsonObject();r.addProperty("op",op);if(id!=null)r.addProperty("job",id);handle(r,null);}
 private void handle(JsonObject r,String id){handle(r,id,null);}
 private void handle(JsonObject r,String id,Plan parsed){String op=r.get("op").getAsString();if(op.startsWith("move")){moves.handle(op,id,r);publish();return;}if(Set.of("pause","cancel","resume","undo").contains(op)&&(r.has("job")?r.get("job").getAsString().startsWith("mv-"):moves.busy()||work instanceof Moves.Check||work instanceof Moves.Verify||moves.prepared!=null||work==null&&!legacyBusy()&&!transactions.busy()&&moves.unfinished())){if(!r.has("job")&&moves.run!=null)r.addProperty("job",moves.run.id);moves.handle("move-"+op,id,r);publish();return;}if(Set.of("pause","cancel","resume","undo").contains(op)&&(r.has("job")?r.get("job").getAsString().startsWith("tx-"):(transactions.busy()||work instanceof Transactions.Check||work instanceof Transactions.Verify||transactions.prepared!=null||!legacyBusy()&&work==null&&transactions.run!=null))){transactions.handle("transaction-"+op,id,r);publish();return;}if(op.startsWith("projection-")||op.startsWith("demolish")||op.startsWith("transaction-")){transactions.handle(op,id,r);publish();return;}switch(op){
  case "status"->reply(id,true,status(),null);
  case "spawn_carts"->{idle();invalidatePrepared();transactions.clearPrepared();work=new CartWork(id,r);message="部署投影矿车";}
  case "anchor"->{if(work!=null||busy())throw new IllegalStateException("正在检查或施工，先暂停/取消");invalidatePrepared();transactions.clearPrepared();anchor=r.has("pos")?new BlockPos(Plan.triple(r.get("pos"))[0],Plan.triple(r.get("pos"))[1],Plan.triple(r.get("pos"))[2]):pickAnchor();invalidatePrepared();publish();reply(id,true,Map.of("anchor",coords(anchor)),null);}
  case "scan"->{moves.clearPrepared();if(work!=null||loading||busy())throw new IllegalStateException("已有任务正在执行，请先暂停");int[] origin=r.has("origin")?Plan.triple(r.get("origin")):coords(anchor);int[] size=Plan.triple(r.get("size"));for(int n:size)if(n<1||n>64)throw new IllegalArgumentException("读取尺寸必须是1～64");invalidatePrepared();transactions.clearPrepared();work=new ScanWork(id,new BlockPos(origin[0],origin[1],origin[2]),size);message="读取场地";}
  case "check"->{idle();moves.clearPrepared();Plan p=parsed!=null?parsed:Plan.parse(r.getAsJsonObject("plan"));invalidatePrepared();transactions.clearPrepared();work=new CheckWork(id,p);message="检查施工图";}
  case "build"->{idle();if(prepared==null||System.nanoTime()>prepared.expires||!prepared.token.equals(r.get("token").getAsString()))throw new IllegalArgumentException("检查令牌不存在，请先 check");Prepared p=prepared;work=new VerifyWork(id,p,()->start(p,id));message="重新核对场地";}
  case "pause"->{cancelLoad();invalidatePrepared();transactions.clearPrepared();if(work!=null){work.cancel();work=null;}if(job!=null&&legacyBusy()){job.phase="PAUSED";job.save();}message=job!=null&&job.runtimeStarted?"已暂停施工；已启动的红石和液体仍按游戏机制运行":"已暂停";reply(id,true,status(),null);}
  case "cancel"->{cancelLoad();transactions.clearPrepared();if(work!=null){work.cancel();work=null;}invalidatePrepared();if(job!=null){job.phase="CANCELLED";job.save();}message="已取消，已施工部分可撤销";reply(id,true,status(),null);}
  case "resume","undo"->{if(work!=null||loading||busy())throw new IllegalStateException("已有任务正在执行，请先暂停");String requested=r.has("job")?FilesIO.id(r.get("job").getAsString()):job!=null?job.id:null;if(requested==null)throw new IllegalArgumentException("请指定任务ID，可用工具 history 查看");if(job!=null&&job.id.equals(requested))activate(op,id);else load(requested,op,id);}
  default->throw new IllegalArgumentException("未知操作："+op);
 }publish();}
 private boolean busy(){return moves.busy()||transactions.busy()||legacyBusy();}
 private BlockPos pickAnchor(){return BuildBridge.pick(player());}
 boolean legacyBusy(){return job!=null&&(job.phase.equals("BUILDING")||job.phase.equals("UNDOING"));}
 void idle(){if(moves.unfinished())throw new IllegalStateException("先继续或回滚未完成的搬迁，物品仍封存");if(transactions.unfinished())throw new IllegalStateException("先处理未完成的新格式事务");if(work!=null||loading||busy())throw new IllegalStateException("已有任务正在执行，请暂停或取消");if(job!=null&&job.phase.equals("PAUSED")&&job.cursor<job.edits.size())throw new IllegalStateException("存在未完成任务，请继续、取消或撤销后再检查新图");}
 public void invalidatePrepared(){prepared=null;}
 private void start(Prepared p,String id){if(p.edits.isEmpty()){reply(id,true,Map.of("changes",0,"message","施工图已与世界一致"),null);return;}
  job=new Job(UUID.randomUUID().toString(),p.name,p.edits,p.phases);Job created=job;prepared=null;job.phase="BUILDING";job.barrier=write(()->{FilesIO.atomic(jobPath(created.id).resolve("plan.json"),created.planData());});job.save();message="施工中";reply(id,true,Map.of("job",job.id,"steps",job.edits.size(),"changes",job.edits.stream().filter(e->!e.before.equals(e.after)).count()),null);tell("开始施工："+job.name+"，"+job.edits.size()+" 步，任务 "+job.id);
 }
 private Path jobPath(String id){return journals.resolve("jobs").resolve(FilesIO.id(id));}
 private void cancelLoad(){loadGeneration++;if(loading&&loadRequest!=null)reply(loadRequest,false,null,"读取任务已取消");loading=false;loadRequest=null;}
 private void load(String requested,String op,String requestId){long generation=++loadGeneration;loadRequest=requestId;loading=true;message="读取任务记录";io.execute(()->{try{JsonObject p=FilesIO.read(jobPath(requested).resolve("plan.json"));JsonObject c=FilesIO.read(jobPath(requested).resolve("progress.json"));Job restored=restoreJob(p,c);server.execute(()->{try{if(!connected||generation!=loadGeneration)return;job=restored;loading=false;loadRequest=null;activate(op,requestId);publish();}catch(Exception e){loading=false;loadRequest=null;reply(requestId,false,null,e.getMessage());}});}catch(Exception e){server.execute(()->{if(!connected||generation!=loadGeneration)return;loading=false;loadRequest=null;reply(requestId,false,null,"无法读取任务："+e.getMessage());});}});}
 private void activate(String op,String requestId){
  invalidatePrepared();transactions.clearPrepared();
  if(op.equals("undo")){if(job.phase.equals("UNDONE")){reply(requestId,true,status(),null);return;}job.phase="UNDOING";job.mode="UNDO";job.undoLimit=Math.max(job.cursor,job.recoveryLimit);job.undoCursor=0;job.skipped=0;job.undoConflicts.clear();job.save();message="撤销中";reply(requestId,true,status(),null);}
  else {if(job.mode.equals("UNDO")&&job.phase.equals("PAUSED")){job.phase="UNDOING";job.save();reply(requestId,true,status(),null);return;}if(Set.of("COMPLETE","UNDONE","CANCELLED").contains(job.phase))throw new IllegalArgumentException("该任务已完成、撤销或取消，不能继续");work=new ResumeWork(requestId);message="检查剩余场地";}
 }
 public void tick(){if(!connected)return;try{permission();}catch(Exception e){BuildBridge.disconnect();return;}
  long deadline=System.nanoTime()+InteractionBudget.nanos();try{
   if(prepared!=null&&System.nanoTime()>prepared.expires)invalidatePrepared();transactions.expirePrepared();moves.expire();moves.cleanup();
   boolean chunksReady=chunks.ensure(chunkScope());deadline=System.nanoTime()+InteractionBudget.nanos();
   if(!chunksReady){message="加载施工区："+chunks.status().get("ready")+"/"+chunks.status().get("total");}
   else if(work!=null){Work current=work;if(current.step(deadline)&&work==current){work=null;publish();}}
   else if(moves.busy())moves.tick(deadline);
   else if(transactions.busy())transactions.tick(deadline);
   else if(legacyBusy()&&!loading)job.tick(deadline);
   else transactions.tick(deadline);
  }catch(Exception e){if(work!=null){work.fail(e.getMessage());work=null;}if(job!=null&&legacyBusy()){job.phase="PAUSED";if(!job.barrier.isCompletedExceptionally())job.save();}moves.fail(e.getMessage());transactions.failActive(e.getMessage());invalidatePrepared();transactions.clearPrepared();chunks.close();message="停止："+e.getMessage();tell(message);}
  if(chunkScope()==null)chunks.close();else chunks.retain(chunkScope());
  if(++tickCount%20==0)publish();
 }
 public void close(){moves.close();cancelLoad();chunks.close();transactions.close();connected=false;if(work!=null){work.cancel();work=null;}if(job!=null&&legacyBusy()){job.phase="PAUSED";if(!job.barrier.isCompletedExceptionally())job.save();}message="连接已关闭";publish();io.shutdown();try{if(!io.awaitTermination(10,TimeUnit.SECONDS))BuildBridge.LOG.error("建筑日志写入未在10秒内结束");}catch(InterruptedException e){Thread.currentThread().interrupt();}}
 private void tell(String s){var p=player();if(p!=null)p.sendSystemMessage(Component.literal("[建筑] "+s));}
 public Map<String,Object> status(){var s=new LinkedHashMap<String,Object>();s.put("protocol",3);s.put("move",moves.status());s.put("chunk_loading",chunks.status());s.put("transaction",transactions.status());s.put("capabilities",List.of("ordered_phases","native_updates","liquids","host_lan","hopper_minecart","projection_nbt","demolition_snapshots","remote_chunks","snapshot_move"));s.put("lan",server.isPublished());s.put("connected",connected);s.put("session",session);s.put("world",world);s.put("dimension",dimension);s.put("anchor",coords(anchor));s.put("message",message);s.put("updated",System.currentTimeMillis());s.put("background",connected);s.put("journals",journals.toAbsolutePath().toString());s.put("reading",work!=null);s.put("loading",loading);if(prepared!=null)s.put("prepared",prepared.summary());if(job!=null)s.put("job",job.progress());return s;}
 public void publish(){Map<String,Object> data=status();write(()->FilesIO.atomic(bridge.resolve("status.json"),data));}
 CompletableFuture<Void> write(Throwing action){return CompletableFuture.runAsync(()->{try{action.run();}catch(Exception e){throw new CompletionException(e);}},io);}
 interface Throwing{void run()throws Exception;}
 private void writeResult(String id,boolean ok,Object data,String error)throws Exception{var out=new LinkedHashMap<String,Object>();out.put("id",id);out.put("session",session);out.put("ok",ok);if(data!=null)out.put("data",data);if(error!=null)out.put("error",error);FilesIO.atomic(bridge.resolve("results").resolve(id+".json"),out);}
 void reply(String id,boolean ok,Object data,String error){if(id==null){if(error!=null)tell(error);return;}write(()->writeResult(id,ok,data,error));}
 public static int[] coords(BlockPos p){return new int[]{p.getX(),p.getY(),p.getZ()};}
 public static String describe(BlockState s){String id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();var properties=s.getValues().map(v->v.property().getName()+"="+v.valueName()).sorted().toList();return properties.isEmpty()?id:id+"["+String.join(",",properties)+"]";}
 BlockState resolve(String text){return states.computeIfAbsent(text,s->{try{StringReader r=new StringReader(s);BlockState b=BlockStateParser.parseForBlock(server.registryAccess().lookupOrThrow(Registries.BLOCK),r,false).blockState();if(r.canRead())throw new IllegalArgumentException("方块状态有多余内容");return b;}catch(Exception e){throw new IllegalArgumentException("无效方块状态："+s+"："+e.getMessage());}});}
 ChunkLease.Scope chunkScope(){if(work!=null)return work.scope;if(moves.scope()!=null)return moves.scope();if(transactions.run!=null&&transactions.run.running())return transactions.run.scope;if(legacyBusy())return job.scope;if(transactions.prepared!=null)return transactions.prepared.scope;return prepared==null?null:prepared.scope;}
 void loaded(BlockPos p){ChunkLease.validate(level,p);if(level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)==null)throw new IllegalStateException("施工区加载意外失效："+p+"，已暂停，可继续重试");}

 private static boolean reactive(BlockState state){return !state.getFluidState().isEmpty()||state.isSignalSource()||state.getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock;}
 private static boolean live(Edit e){return e.updates.equals("live")||e.updates.equals("legacy")&&(reactive(e.before)||reactive(e.after));}
 private static int placementFlags(Edit e){
  if(e.updates.equals("deferred"))return Block.UPDATE_CLIENTS|Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
  return live(e)?Block.UPDATE_ALL|Block.UPDATE_SUPPRESS_DROPS:Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS;
 }
 private void notifySupport(BlockPos pos,BlockState state){
  if(state.hasProperty(BlockStateProperties.ATTACH_FACE)){
   var face=state.getValue(BlockStateProperties.ATTACH_FACE);
   BlockPos support=switch(face){case FLOOR->pos.below();case CEILING->pos.above();case WALL->pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());};
   level.updateNeighborsAt(support,state.getBlock(),null);
  }
 }
 void initialize(BlockPos pos,BlockState state){
  // Re-applying an unchanged state is an explicit activation step, not a silent no-op.
  if(!state.hasBlockEntity())state.onPlace(level,pos,state,false);
  level.neighborChanged(pos,state.getBlock(),null);
  level.updateNeighborsAt(pos,state.getBlock(),null);notifySupport(pos,state);
  var fluid=level.getFluidState(pos);if(!fluid.isEmpty())level.scheduleTick(pos,fluid.getType(),fluid.getType().getTickDelay(level));
 }
 record Edit(BlockPos pos,BlockState before,BlockState after,int phase,String updates){Map<String,Object> data(){return Map.of("pos",coords(pos),"before",describe(before),"after",describe(after),"phase",phase,"updates",updates);}}
 class Prepared {
  final String token=UUID.randomUUID().toString(),name;final long expires=System.nanoTime()+60_000_000_000L;ChunkLease.Scope scope;final List<Edit> edits;final List<Plan.Phase> phases;final Map<BlockPos,BlockState> originals;final Map<String,Integer> materials;final int replaced;final int[] bounds;final Set<BlockPos> editPositions;
  Prepared(String name,List<Edit> edits,Map<BlockPos,BlockState> originals,Map<String,Integer> materials,int replaced,int[] bounds,List<Plan.Phase> phases){this.phases=List.copyOf(phases);this.name=name;this.edits=List.copyOf(edits);this.originals=Map.copyOf(originals);this.materials=Map.copyOf(materials);this.replaced=replaced;this.bounds=bounds;editPositions=new HashSet<>();for(Edit e:edits)editPositions.add(e.pos);}
  Map<String,Object> summary(){return Map.of("token",token,"name",name,"steps",edits.size(),"changes",edits.stream().filter(e->!e.before.equals(e.after)).count(),"replaced_non_air",replaced,"materials",materials,"bounds",bounds,"phases",phases);}
 }
 abstract class Work {ChunkLease.Scope scope;final String request;Work(String request){this.request=request;}abstract boolean step(long deadline);void fail(String s){reply(request,false,null,s);}void cancel(){fail("检查/读取任务已取消");}}
 class CartWork extends Work {
  final String deployment;final List<double[]> positions=new ArrayList<>();final List<UUID> ids=new ArrayList<>();final List<float[]> angles=new ArrayList<>();
  final Path receipt;final CompletableFuture<JsonObject> prior;CompletableFuture<Void> commit;boolean spawned=false;
  CartWork(String request,JsonObject r){super(request);deployment=FilesIO.id(r.get("deployment_id").getAsString());receipt=journals.resolve("cart-deployments").resolve(deployment+".json");var carts=r.getAsJsonArray("carts");if(carts==null||carts.isEmpty()||carts.size()>16)throw new IllegalArgumentException("矿车数量必须1～16");
   for(int i=0;i<carts.size();i++){var cart=carts.get(i).getAsJsonObject();if(!cart.get("type").getAsString().equals("minecraft:hopper_minecart"))throw new IllegalArgumentException("仅支持空漏斗矿车");var array=cart.getAsJsonArray("pos");if(array==null||array.size()!=3)throw new IllegalArgumentException("矿车坐标必须为三个数");double[] p=new double[3];for(int k=0;k<3;k++){p[k]=array.get(k).getAsDouble();if(!Double.isFinite(p[k]))throw new IllegalArgumentException("矿车坐标非有限数");}if(Math.abs(p[0])>29999900||Math.abs(p[2])>29999900)throw new IllegalArgumentException("矿车超出世界边界");
    if(cart.has("motion"))for(var v:cart.getAsJsonArray("motion"))if(v.getAsDouble()!=0)throw new IllegalArgumentException("只允许静止部署，不复制旧运动速度");
    ChunkLease.validate(level,BlockPos.containing(p[0],p[1],p[2]));
    float yaw=0,pitch=0;if(cart.has("rotation")){var a=cart.getAsJsonArray("rotation");if(a.size()!=2)throw new IllegalArgumentException("矿车角度必须为两个数");yaw=a.get(0).getAsFloat();pitch=a.get(1).getAsFloat();if(!Float.isFinite(yaw)||!Float.isFinite(pitch))throw new IllegalArgumentException("矿车角度非有限数");}angles.add(new float[]{yaw,pitch});positions.add(p);ids.add(UUID.nameUUIDFromBytes((world+"|"+dimension+"|"+deployment+"|"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
   }
   scope=new ChunkLease.Scope(level,positions.stream().map(p->BlockPos.containing(p[0],p[1],p[2])).toList());
   prior=CompletableFuture.supplyAsync(()->{try{return Files.exists(receipt)?FilesIO.read(receipt):new JsonObject();}catch(Exception e){throw new CompletionException(e);}},io);
  }
  boolean step(long deadline){if(!prior.isDone())return false;JsonObject old=prior.join();if(!spawned){
    if(old.has("status")){if(old.get("status").getAsString().equals("complete")){if(!old.get("positions").equals(FilesIO.GSON.toJsonTree(positions)))throw new IllegalArgumentException("同一部署ID的坐标已改变，请使用新的部署ID");reply(request,true,Map.of("deployment_id",deployment,"already_deployed",true,"uuids",ids),null);return true;}throw new IllegalStateException("之前部署结果未确认，禁止自动重复生成矿车");}
    for(var p:positions){BlockPos pos=BlockPos.containing(p[0],p[1],p[2]);loaded(pos);loaded(pos.below());if(!level.getBlockState(pos).is(net.minecraft.tags.BlockTags.RAILS)&&!level.getBlockState(pos.below()).is(net.minecraft.tags.BlockTags.RAILS))throw new IllegalArgumentException("矿车位置没有轨道："+pos);}
    if(commit==null){commit=write(()->FilesIO.atomic(receipt,Map.of("world",world,"dimension",dimension,"deployment_id",deployment,"status","pending","positions",positions,"uuids",ids)));return false;}if(!commit.isDone())return false;commit.join();permission();
    for(int i=0;i<positions.size();i++){var p=positions.get(i);loaded(BlockPos.containing(p[0],p[1],p[2]));if(level.getEntity(ids.get(i))!=null)continue;var cart=net.minecraft.world.entity.EntityTypes.HOPPER_MINECART.create(level,net.minecraft.world.entity.EntitySpawnReason.COMMAND);if(cart==null)throw new IllegalStateException("矿车实体创建失败");cart.setUUID(ids.get(i));cart.setPos(p[0],p[1],p[2]);cart.setYRot(angles.get(i)[0]);cart.setXRot(angles.get(i)[1]);cart.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);cart.addTag("aibuild:"+deployment);if(!level.addFreshEntity(cart))throw new IllegalStateException("矿车加入世界失败");}
    spawned=true;commit=write(()->FilesIO.atomic(receipt,Map.of("world",world,"dimension",dimension,"deployment_id",deployment,"status","complete","positions",positions,"uuids",ids)));return false;
   }if(!commit.isDone())return false;commit.join();reply(request,true,Map.of("deployment_id",deployment,"count",ids.size(),"uuids",ids),null);message="投影矿车部署完成";return true;
  }
 }
 class ScanWork extends Work {
  final BlockPos origin;final int[] size;int cursor=0;final Map<String,Integer> palette=new LinkedHashMap<>();final List<Object> blocks=new ArrayList<>(),surfaces=new ArrayList<>();
  ScanWork(String r,BlockPos o,int[] size){super(r);origin=o;this.size=size;if((long)o.getX()+size[0]>30000000||(long)o.getZ()+size[2]>30000000)throw new IllegalArgumentException("读取区域超界");scope=new ChunkLease.Scope(level,BlockPos.betweenClosedStream(o,o.offset(size[0]-1,size[1]-1,size[2]-1)).map(BlockPos::immutable).toList());}
  boolean step(long deadline){int total=size[0]*size[1]*size[2],count=0;while(cursor<total&&count++<512&&System.nanoTime()<deadline){int x=cursor%size[0],z=(cursor/size[0])%size[2],y=cursor/(size[0]*size[2]);BlockPos p=origin.offset(x,y,z);loaded(p);if(y==0)surfaces.add(List.of(x,z,level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,p.getX(),p.getZ())-1));String s=describe(level.getBlockState(p));int pi=palette.computeIfAbsent(s,k->palette.size());blocks.add(List.of(x,y,z,pi,level.getBlockEntity(p)!=null));cursor++;}if(cursor==total){reply(request,true,Map.of("origin",coords(origin),"size",size,"palette",palette.keySet(),"blocks",blocks,"surface_heights",surfaces,"min_y",level.getMinY(),"max_y",level.getMaxY()),null);message="场地读取完成";return true;}return false;}
 }
 class CheckWork extends Work {
  final Plan plan;int cursor=0,replaced=0,pass=0,validationPhase=0,phaseStart=0;final List<Edit> edits=new ArrayList<>();final Map<BlockPos,BlockState> originals=new HashMap<>(),targets=new HashMap<>();final Map<String,Integer> materials=new TreeMap<>();final int[] bounds={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
  CheckWork(String r,Plan p){super(r);plan=p;scope=new ChunkLease.Scope(level,plan.blocks().stream().map(this::position).toList());}
  BlockPos position(Plan.Voxel b){int[] q=plan.rotated(b);return new BlockPos(plan.origin()[0]+q[0],plan.origin()[1]+q[1],plan.origin()[2]+q[2]);}
  BlockState state(Plan.Voxel b){return resolve(b.state()).rotate(switch(plan.rotation()){case 90->Rotation.CLOCKWISE_90;case 180->Rotation.CLOCKWISE_180;case 270->Rotation.COUNTERCLOCKWISE_90;default->Rotation.NONE;});}
  boolean step(long deadline){int count=0;
   while(count++<512&&System.nanoTime()<deadline){
    if(pass==0){
     if(cursor==plan.blocks().size()){targets.clear();cursor=0;pass=1;continue;}
     var b=plan.blocks().get(cursor++);BlockPos p=position(b);loaded(p);if(AssemblyGuard.contains(level,p))throw new IllegalArgumentException("区域有未完成事务，请先处理");BlockState after=state(b),actual=level.getBlockState(p),before=targets.getOrDefault(p,actual);
     if(level.getBlockEntity(p)!=null&&!actual.equals(after))throw new IllegalArgumentException("保护已有方块实体："+p);
     originals.putIfAbsent(p,actual);targets.put(p,after);int[] xyz=coords(p);for(int i=0;i<3;i++){bounds[i]=Math.min(bounds[i],xyz[i]);bounds[i+3]=Math.max(bounds[i+3],xyz[i]);}
     String updates=plan.phases().get(b.phase()).updates();
     if(!before.equals(after)||updates.equals("live")){edits.add(new Edit(p,before,after,b.phase(),updates));if(!before.equals(after)){if(!before.isAir())replaced++;if(!after.isAir())materials.merge(describe(after),1,Integer::sum);}}
    }else {
     if(validationPhase==plan.phases().size()){prepared=new Prepared(plan.name(),edits,originals,materials,replaced,bounds,plan.phases());prepared.scope=scope;reply(request,true,prepared.summary(),null);message="检查通过，等待聊天确认";return true;}
     if(cursor==plan.blocks().size()||plan.blocks().get(cursor).phase()!=validationPhase){
      if(pass==1){pass=2;cursor=phaseStart;}else{validationPhase++;phaseStart=cursor;pass=1;}continue;
     }
     var b=plan.blocks().get(cursor++);BlockPos p=position(b);BlockState after=state(b);if(pass==1)targets.put(p,after);else validatePlacement(p,after,targets);
    }
   }return false;
  }
 }
 private BlockState finalState(BlockPos p,Map<BlockPos,BlockState> targets){BlockState state=targets.get(p);if(state!=null)return state;loaded(p);state=level.getBlockState(p);if(work instanceof CheckWork c)c.originals.putIfAbsent(p,state);return state;}
 private boolean createdEntityUnchanged(Edit e){var current=level.getBlockEntity(e.pos);if(current==null)return !e.after.hasBlockEntity();if(!(e.after.getBlock() instanceof EntityBlock b))return false;var empty=b.newBlockEntity(e.pos,e.after);if(empty==null)return false;var actual=current.saveWithoutMetadata(server.registryAccess());var baseline=empty.saveWithoutMetadata(server.registryAccess());return EntityFingerprint.same(actual,baseline,current instanceof net.minecraft.world.level.block.entity.HopperBlockEntity);}
 private net.minecraft.world.level.LevelReader overlay(Map<BlockPos,BlockState> targets){return (net.minecraft.world.level.LevelReader)java.lang.reflect.Proxy.newProxyInstance(Engine.class.getClassLoader(),new Class[]{net.minecraft.world.level.LevelReader.class},(proxy,method,args)->{if(args!=null&&args.length==1&&args[0] instanceof BlockPos p){if(method.getName().equals("isEmptyBlock"))return finalState(p,targets).isAir();if(method.getName().equals("getBlockState"))return finalState(p,targets);if(method.getName().equals("getFluidState"))return finalState(p,targets).getFluidState();}try{return method.invoke(level,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}
 void validatePlacement(BlockPos p,BlockState state,Map<BlockPos,BlockState> targets){
  if(!state.canSurvive(overlay(targets),p))throw new IllegalArgumentException("方块缺少有效支撑或放置条件："+p+" "+describe(state));
  if(state.getBlock() instanceof DoorBlock){var half=state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);BlockPos other=half==DoubleBlockHalf.LOWER?p.above():p.below();BlockState pair=finalState(other,targets);if(pair.getBlock()!=state.getBlock()||!pair.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)||pair.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==half||pair.getValue(BlockStateProperties.HORIZONTAL_FACING)!=state.getValue(BlockStateProperties.HORIZONTAL_FACING)||pair.getValue(BlockStateProperties.DOOR_HINGE)!=state.getValue(BlockStateProperties.DOOR_HINGE)||pair.getValue(BlockStateProperties.OPEN)!=state.getValue(BlockStateProperties.OPEN)||pair.getValue(BlockStateProperties.POWERED)!=state.getValue(BlockStateProperties.POWERED))throw new IllegalArgumentException("门上下半不完整或朝向不一致："+p);if(half==DoubleBlockHalf.LOWER&&finalState(p.below(),targets).isAir())throw new IllegalArgumentException("门缺少支撑："+p);}
  if(state.getBlock() instanceof BedBlock){var part=state.getValue(BlockStateProperties.BED_PART);var face=state.getValue(BlockStateProperties.HORIZONTAL_FACING);BlockPos other=p.relative(part==BedPart.FOOT?face:face.getOpposite());BlockState pair=finalState(other,targets);if(pair.getBlock()!=state.getBlock()||!pair.hasProperty(BlockStateProperties.BED_PART)||pair.getValue(BlockStateProperties.BED_PART)==part||pair.getValue(BlockStateProperties.HORIZONTAL_FACING)!=face)throw new IllegalArgumentException("床头床尾不完整："+p);if(finalState(p.below(),targets).isAir())throw new IllegalArgumentException("床缺少支撑："+p);}
  if(state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)&&!(state.getBlock() instanceof DoorBlock)){var half=state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);BlockState pair=finalState(half==DoubleBlockHalf.LOWER?p.above():p.below(),targets);if(pair.getBlock()!=state.getBlock()||!pair.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)||pair.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==half)throw new IllegalArgumentException("双格方块不完整："+p);}
  // v1 forbids unstable/fluid/fire states rather than silently producing a different result.
  if(state.getBlock() instanceof net.minecraft.world.level.block.piston.MovingPistonBlock)throw new IllegalArgumentException("不支持运行中的活塞快照");

 }
 class VerifyWork extends Work {final Prepared p;final Iterator<Map.Entry<BlockPos,BlockState>> iterator;final Runnable complete;VerifyWork(String r,Prepared p,Runnable c){super(r);this.p=p;scope=p.scope;iterator=p.originals.entrySet().iterator();complete=c;}
  boolean step(long deadline){int count=0;while(iterator.hasNext()&&count++<512&&System.nanoTime()<deadline){var e=iterator.next();loaded(e.getKey());if(!level.getBlockState(e.getKey()).equals(e.getValue())||level.getBlockEntity(e.getKey())!=null&&p.edits.stream().anyMatch(x->x.pos.equals(e.getKey())&&!x.before.equals(x.after)))throw new IllegalArgumentException("场地已变化，请重新 check："+e.getKey());}if(!iterator.hasNext()){complete.run();return true;}return false;}
 }
 class ResumeWork extends Work {
  int cursor=0,pass=0;final Map<BlockPos,BlockState> applied=new LinkedHashMap<>(),future=new HashMap<>();Iterator<Map.Entry<BlockPos,BlockState>> verify;
  ResumeWork(String r){super(r);scope=job.scope;}
  boolean step(long deadline){int count=0;while(count++<512&&System.nanoTime()<deadline){
   if(pass==0){if(cursor<job.cursor){Edit e=job.edits.get(cursor++);applied.put(e.pos,e.after);}else{verify=applied.entrySet().iterator();pass=1;}}
   else if(pass==1){if(verify.hasNext()){var e=verify.next();loaded(e.getKey());if(!job.runtimeStarted&&!level.getBlockState(e.getKey()).equals(e.getValue()))throw new IllegalArgumentException("已施工区域发生变化："+e.getKey());}else pass=2;}
   else {if(cursor==job.edits.size()){job.phase="BUILDING";job.save();message="继续施工";reply(request,true,status(),null);return true;}
    int i=cursor++;Edit e=job.edits.get(i);loaded(e.pos);BlockState current=future.getOrDefault(e.pos,level.getBlockState(e.pos));
    if(!current.equals(e.before)&&!(i<job.recoveryLimit&&current.equals(e.after)))throw new IllegalArgumentException("剩余场地发生变化："+e.pos);
    if(level.getBlockEntity(e.pos)!=null&&!e.before.equals(e.after))throw new IllegalArgumentException("保护方块实体："+e.pos);future.put(e.pos,e.after);
   }
  }return false;}
 }
 public class Job {
  public final String id,name;final ChunkLease.Scope scope;final List<Edit> edits;final List<Plan.Phase> phases;final int[] stageEnds;
  public String phase="PAUSED",mode="BUILD";private int recoveryLimit=0;
  public int cursor=0,highwater=0,undoCursor=0,skipped=0,undoLimit=0,stageIndex=0,waitTicks=0;
  final List<Object> undoConflicts=new ArrayList<>();public boolean runtimeStarted=false;private int batchLimit=0;CompletableFuture<Void> barrier=CompletableFuture.completedFuture(null);
  Job(String id,String name,List<Edit> edits,List<Plan.Phase> phases){this.id=FilesIO.id(id);this.name=name;this.edits=List.copyOf(edits);this.phases=List.copyOf(phases);scope=new ChunkLease.Scope(level,edits.stream().map(Edit::pos).toList());stageEnds=new int[phases.size()];for(Edit e:edits)stageEnds[e.phase]++;for(int i=1;i<stageEnds.length;i++)stageEnds[i]+=stageEnds[i-1];}
  Map<String,Object> planData(){return Map.of("version",2,"id",id,"name",name,"world",world,"dimension",dimension,"phases",phases,"edits",edits.stream().map(Edit::data).toList());}
  public Map<String,Object> progress(){var p=new LinkedHashMap<String,Object>();p.put("id",id);p.put("name",name);p.put("phase",phase);p.put("mode",mode);p.put("done",cursor);p.put("total",edits.size());p.put("highwater",highwater);p.put("undo_done",undoCursor);p.put("undo_skipped",skipped);p.put("undo_conflicts",List.copyOf(undoConflicts));p.put("undo_limit",undoLimit);p.put("stage_index",stageIndex);p.put("stage",stageIndex<phases.size()?phases.get(stageIndex).name():"完成");p.put("wait_ticks",waitTicks);p.put("runtime_effects",runtimeStarted);return p;}
  void save(){var snapshot=progress();CompletableFuture<Void> previous=barrier;barrier=write(()->{previous.join();FilesIO.atomic(jobPath(id).resolve("progress.json"),snapshot);});}
  void tick(long deadline){if(!barrier.isDone())return;barrier.join();int count=0;
   if(phase.equals("BUILDING")){
    if(waitTicks>0){waitTicks--;if(waitTicks%20==0)save();return;}
    if(stageIndex==phases.size()){phase="COMPLETE";save();message=runtimeStarted?"投放及启动步骤完成，机器继续按游戏机制运行":"施工完成";tell(name+"："+message+"，"+cursor+" 步。");publish();return;}
    if(cursor>=stageEnds[stageIndex]){waitTicks=phases.get(stageIndex).waitTicks();stageIndex++;batchLimit=cursor;save();publish();return;}
    if(cursor>=batchLimit){batchLimit=Math.min(cursor+InteractionBudget.steps(System.nanoTime()),stageEnds[stageIndex]);highwater=Math.max(highwater,batchLimit);for(int i=cursor;i<batchLimit;i++)if(live(edits.get(i)))runtimeStarted=true;save();return;}
    while(cursor<batchLimit&&count++<InteractionBudget.steps(System.nanoTime())&&System.nanoTime()<deadline){
     Edit e=edits.get(cursor);loaded(e.pos);BlockState current=level.getBlockState(e.pos);
     if(current.equals(e.after)&&cursor<recoveryLimit&&!e.before.equals(e.after)){cursor++;continue;}
     if(!current.equals(e.before)||(level.getBlockEntity(e.pos)!=null&&!e.before.equals(e.after)))throw new IllegalArgumentException("施工区域发生变化，暂停："+e.pos);
     if(current.equals(e.after)){if(live(e))initialize(e.pos,current);}
     else {if(!level.setBlock(e.pos,e.after,placementFlags(e)))throw new IllegalStateException("方块放置失败："+e.pos);if(live(e)){notifySupport(e.pos,e.before);notifySupport(e.pos,e.after);}if(!live(e)&&!level.getBlockState(e.pos).equals(e.after))throw new IllegalStateException("结构阶段方块被修改："+e.pos);}
     cursor++;
    }if(cursor>=batchLimit)save();
   }else{
    while(undoCursor<undoLimit&&count++<InteractionBudget.steps(System.nanoTime())&&System.nanoTime()<deadline){
     Edit e=edits.get(undoLimit-1-undoCursor);loaded(e.pos);BlockState current=level.getBlockState(e.pos);
     if(e.before.equals(e.after)){undoCursor++;continue;}
     if(current.equals(e.after)&&createdEntityUnchanged(e)){if(!level.setBlock(e.pos,e.before,placementFlags(e)))throw new IllegalStateException("撤销放置失败："+e.pos);if(live(e)){notifySupport(e.pos,e.after);notifySupport(e.pos,e.before);}}else if(!current.equals(e.before)){skipped++;undoConflicts.add(Map.of("pos",coords(e.pos),"actual",describe(current),"expected",describe(e.after),"reason",current.equals(e.after)?"方块实体数据与创建时默认状态不同，未断言库存非空":"方块状态已变化"));}
     undoCursor++;
    }
    save();if(undoCursor==undoLimit){phase=skipped==0?"UNDONE":"PARTIAL";save();message=skipped==0?"撤销完成":"撤销部分完成，残留 "+skipped+" 格；需要拆除检查";tell(message);publish();}
   }
  }
 }
 private Job restoreJob(JsonObject p,JsonObject c){if(!world.equals(p.get("world").getAsString())||!dimension.equals(p.get("dimension").getAsString()))throw new IllegalArgumentException("任务属于其他世界/维度");List<Edit> edits=new ArrayList<>();for(var v:p.getAsJsonArray("edits")){var e=v.getAsJsonObject();int[] q=Plan.triple(e.get("pos"));edits.add(new Edit(new BlockPos(q[0],q[1],q[2]),resolve(e.get("before").getAsString()),resolve(e.get("after").getAsString()),e.has("phase")?Plan.integer(e.get("phase")):0,e.has("updates")?e.get("updates").getAsString():"legacy"));}List<Plan.Phase> phases=new ArrayList<>();if(p.has("phases"))for(var v:p.getAsJsonArray("phases")){var q=v.getAsJsonObject();phases.add(new Plan.Phase(q.get("name").getAsString(),q.get("updates").getAsString(),Plan.integer(q.get("waitTicks"))));}else phases.add(new Plan.Phase("施工","legacy",0));Job j=new Job(p.get("id").getAsString(),p.get("name").getAsString(),edits,phases);j.cursor=Plan.integer(c.get("done"));j.highwater=Plan.integer(c.get("highwater"));j.undoCursor=Plan.integer(c.get("undo_done"));j.skipped=Plan.integer(c.get("undo_skipped"));if(c.has("undo_conflicts"))for(var conflict:c.getAsJsonArray("undo_conflicts"))j.undoConflicts.add(conflict);j.undoLimit=c.has("undo_limit")?Plan.integer(c.get("undo_limit")):j.highwater;if(j.cursor<0||j.highwater<j.cursor||j.highwater>edits.size()||j.undoLimit>j.highwater||j.undoCursor<0||j.undoCursor>j.undoLimit)throw new IllegalArgumentException("任务进度记录损坏");j.phase=c.get("phase").getAsString();j.mode=c.has("mode")?c.get("mode").getAsString():"BUILD";j.recoveryLimit=j.highwater;j.stageIndex=c.has("stage_index")?Plan.integer(c.get("stage_index")):0;j.waitTicks=c.has("wait_ticks")?Plan.integer(c.get("wait_ticks")):0;j.runtimeStarted=c.has("runtime_effects")&&c.get("runtime_effects").getAsBoolean();if(j.stageIndex<0||j.stageIndex>phases.size()||j.waitTicks<0||j.waitTicks>1200)throw new IllegalArgumentException("阶段记录损坏");if(Set.of("BUILDING","UNDOING").contains(j.phase))j.phase="PAUSED";return j;}
}
