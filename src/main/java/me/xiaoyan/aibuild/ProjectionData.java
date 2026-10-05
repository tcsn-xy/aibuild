package me.xiaoyan.aibuild;
import com.google.gson.*;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import java.util.*;
/** Data-only input. Conversion is completed on the IO worker before world access. */
public final class ProjectionData {
 public record Cell(BlockPos pos,CompoundTag state,CompoundTag nbt){}
 public record Mob(String key,double[] pos,CompoundTag nbt){}
 public final List<Cell> cells=new ArrayList<>();public final List<Mob> entities=new ArrayList<>();
 public final JsonArray ticks,activation;public final String name;public final Rotation rotation;public final int angle;public final int[] origin;
 static final Set<String> ENTITY_IDS=Set.of("minecraft:hopper_minecart","minecraft:minecart","minecraft:chest_minecart","minecraft:item");
 static final Set<String> BE_IDS=Set.of("minecraft:chest","minecraft:trapped_chest","minecraft:barrel","minecraft:hopper","minecraft:furnace","minecraft:blast_furnace","minecraft:smoker","minecraft:comparator","minecraft:dispenser","minecraft:dropper","minecraft:sign","minecraft:hanging_sign");
 public static CompoundTag parse(String text){try{if(text.length()>1048576)throw new IllegalArgumentException("NBT过大");return TagParser.parseCompoundFully(text);}catch(Exception ex){throw new IllegalArgumentException("NBT解析失败："+ex.getMessage());}}
 static CompoundTag fix(CompoundTag nbt,com.mojang.datafixers.DSL.TypeReference type,int version){return (CompoundTag)DataFixers.getDataFixer().update(type,new Dynamic<>(NbtOps.INSTANCE,nbt),version,SharedConstants.getCurrentVersion().dataVersion().version()).getValue();}
 static void safe(Tag tag){if(tag instanceof CompoundTag c){for(String key:c.keySet()){if(Set.of("Command","command","click_event","clickEvent","Passengers","LootTable","loot_table").contains(key))throw new IllegalArgumentException("不支持可执行/嵌套实体/战利品载荷："+key);safe(c.get(key));}}else if(tag instanceof ListTag l)for(Tag t:l)safe(t);}
 public ProjectionData(JsonObject p){
  if(Plan.integer(p.get("version"))!=3)throw new IllegalArgumentException("完整投影需要version 3");
  name=p.get("name").getAsString();if(name.length()>120)throw new IllegalArgumentException("名称过长");origin=Plan.triple(p.get("origin"));angle=p.has("rotation")?Plan.integer(p.get("rotation")):0;
  rotation=switch(angle){case 0->Rotation.NONE;case 90->Rotation.CLOCKWISE_90;case 180->Rotation.CLOCKWISE_180;case 270->Rotation.COUNTERCLOCKWISE_90;default->throw new IllegalArgumentException("旋转角度错误");};
  int version=Plan.integer(p.get("data_version"));if(version<0||version>SharedConstants.getCurrentVersion().dataVersion().version())throw new IllegalArgumentException("不支持的数据版本");
  Set<BlockPos> seen=new HashSet<>();Map<String,CompoundTag> palette=new HashMap<>();int[] lo={64,64,64},hi={-64,-64,-64};
  for(var v:p.getAsJsonArray("blocks")){var b=v.getAsJsonObject();int[] q=Plan.triple(b.get("pos"));for(int k=0;k<3;k++){if(q[k]<-63||q[k]>63)throw new IllegalArgumentException("坐标超限");lo[k]=Math.min(lo[k],q[k]);hi[k]=Math.max(hi[k],q[k]);}
   BlockPos pos=position(q);if(!seen.add(pos))throw new IllegalArgumentException("重复方块");String key=b.get("state_nbt").getAsString();CompoundTag state=palette.computeIfAbsent(key,k->fix(parse(k),References.BLOCK_STATE,version));
   String id=state.getString("id").orElseThrow();if(Set.of("minecraft:moving_piston","minecraft:command_block","minecraft:chain_command_block","minecraft:repeating_command_block","minecraft:structure_block","minecraft:jigsaw","minecraft:spawner","minecraft:trial_spawner").contains(id))throw new IllegalArgumentException("不支持的方块/运行快照："+id);
   CompoundTag nbt=null;if(b.has("nbt")){nbt=parse(b.get("nbt").getAsString());safe(nbt);String beid=nbt.getString("id").orElseThrow();if(!BE_IDS.contains(beid))throw new IllegalArgumentException("未适配方块实体："+beid);nbt=fix(nbt,References.BLOCK_ENTITY,version);safe(nbt);nbt.putInt("x",pos.getX());nbt.putInt("y",pos.getY());nbt.putInt("z",pos.getZ());}
   cells.add(new Cell(pos,state,nbt));if(cells.size()>262144)throw new IllegalArgumentException("投影过大");
  }if(cells.isEmpty())throw new IllegalArgumentException("空投影");for(int k=0;k<3;k++)if(hi[k]-lo[k]+1>64)throw new IllegalArgumentException("范围超过64格");
  Set<String> keys=new HashSet<>();for(var v:p.getAsJsonArray("entities")){var a=v.getAsJsonObject();String key=a.get("key").getAsString();if(!keys.add(key))throw new IllegalArgumentException("重复实体键");CompoundTag nbt=parse(a.get("nbt").getAsString());safe(nbt);String id=nbt.getString("id").orElseThrow();if(!ENTITY_IDS.contains(id))throw new IllegalArgumentException("未适配实体："+id);nbt=fix(nbt,References.ENTITY,version);safe(nbt);
   var arr=a.getAsJsonArray("pos");if(arr.size()!=3)throw new IllegalArgumentException("实体坐标错误");double[] pos=new double[3];for(int k=0;k<3;k++){pos[k]=arr.get(k).getAsDouble();if(!Double.isFinite(pos[k])||pos[k]<lo[k]-1||pos[k]>hi[k]+2)throw new IllegalArgumentException("实体超出投影范围");}pos=position(pos);nbt.remove("UUID");nbt.remove("UUIDMost");nbt.remove("UUIDLeast");entities.add(new Mob(key,pos,nbt));if(entities.size()>512)throw new IllegalArgumentException("实体数量超过512");
  }
  ticks=p.has("ticks")?p.getAsJsonArray("ticks"):new JsonArray();activation=p.has("activation")?p.getAsJsonArray("activation"):new JsonArray();
  if(ticks.size()>4096||activation.size()>4096)throw new IllegalArgumentException("更新步骤过多");
  for(var v:ticks){var t=v.getAsJsonObject();BlockPos at=position(Plan.triple(t.get("pos")));if(!seen.contains(at))throw new IllegalArgumentException("刻时在投影外");int delay=Plan.integer(t.get("delay"));if(delay<0||delay>120000)throw new IllegalArgumentException("刻时延迟超限");if(!Set.of("block","fluid").contains(t.get("type").getAsString()))throw new IllegalArgumentException("刻时类型错误");}
  for(var v:activation)if(!seen.contains(position(Plan.triple(v))))throw new IllegalArgumentException("启动位置在投影外");
 }
 public double[] position(double[] q){double x=q[0],z=q[2];return switch(angle){case 90->new double[]{origin[0]+1-z,origin[1]+q[1],origin[2]+x};case 180->new double[]{origin[0]+1-x,origin[1]+q[1],origin[2]+1-z};case 270->new double[]{origin[0]+z,origin[1]+q[1],origin[2]+1-x};default->new double[]{origin[0]+x,origin[1]+q[1],origin[2]+z};};}
 public BlockPos position(int[] q){int x=q[0],z=q[2];return switch(angle){case 90->new BlockPos(origin[0]-z,origin[1]+q[1],origin[2]+x);case 180->new BlockPos(origin[0]-x,origin[1]+q[1],origin[2]-z);case 270->new BlockPos(origin[0]+z,origin[1]+q[1],origin[2]-x);default->new BlockPos(origin[0]+x,origin[1]+q[1],origin[2]+z);};}
}
