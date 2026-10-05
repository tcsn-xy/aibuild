package me.xiaoyan.aibuild;
import com.google.gson.*;
import java.util.*;
/** Declarative, bounded, ordered construction. No executable code or arbitrary NBT. */
public record Plan(String name,int[] origin,int rotation,List<Voxel> blocks,List<Phase> phases) {
 public record Voxel(int x,int y,int z,String state,int phase) {}
 public record Phase(String name,String updates,int waitTicks) {}
 public static int integer(JsonElement e){if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("必须是整数");try{return e.getAsBigDecimal().intValueExact();}catch(Exception ex){throw new IllegalArgumentException("整数超限或含小数");}}
 public static int[] triple(JsonElement e){if(e==null||!e.isJsonArray()||e.getAsJsonArray().size()!=3)throw new IllegalArgumentException("坐标必须是三个整数");var a=e.getAsJsonArray();return new int[]{integer(a.get(0)),integer(a.get(1)),integer(a.get(2))};}
 public static Plan parse(JsonObject p){
  int version=integer(p.get("version"));if(version!=1&&version!=2)throw new IllegalArgumentException("施工图版本必须为1或2");
  String name=p.has("name")?p.get("name").getAsString():"建筑";if(name.length()>120)throw new IllegalArgumentException("名称过长");
  int[] origin=triple(p.get("origin"));if(Math.abs((long)origin[0])>29999900||Math.abs((long)origin[2])>29999900)throw new IllegalArgumentException("超出世界边界");
  int rotation=p.has("rotation")?integer(p.get("rotation")):0;if(!Set.of(0,90,180,270).contains(rotation))throw new IllegalArgumentException("朝向仅支持0/90/180/270");
  List<Phase> phases=new ArrayList<>();List<JsonArray> lists=new ArrayList<>();
  if(version==1){phases.add(new Phase("施工",p.has("fluidSources")&&p.get("fluidSources").getAsBoolean()?"live":"legacy",0));lists.add(p.getAsJsonArray("blocks"));}
  else {
   JsonArray array=p.getAsJsonArray("phases");if(array==null||array.isEmpty()||array.size()>16)throw new IllegalArgumentException("阶段数量必须为1～16");
   for(var v:array){JsonObject phase=v.getAsJsonObject();String title=phase.has("name")?phase.get("name").getAsString():"阶段 "+(phases.size()+1);if(title.length()>80)throw new IllegalArgumentException("阶段名过长");String updates=phase.has("updates")?phase.get("updates").getAsString():"deferred";if(!Set.of("deferred","live").contains(updates))throw new IllegalArgumentException("updates必须为deferred或live");int wait=phase.has("wait_ticks")?integer(phase.get("wait_ticks")):0;if(wait<0||wait>1200)throw new IllegalArgumentException("阶段等待必须为0～1200 tick");phases.add(new Phase(title,updates,wait));lists.add(phase.getAsJsonArray("blocks"));}
  }
  List<Voxel> blocks=new ArrayList<>();int[] min={64,64,64},max={-64,-64,-64};
  for(int index=0;index<lists.size();index++){
   JsonArray list=lists.get(index);if(list==null)throw new IllegalArgumentException("阶段缺少blocks数组");Set<String> seen=new HashSet<>();
   for(JsonElement item:list){JsonObject b=item.getAsJsonObject();int[] q=triple(b.get("pos"));for(int i=0;i<3;i++){if(q[i]<-63||q[i]>63)throw new IllegalArgumentException("相对坐标必须在-63～63内");min[i]=Math.min(min[i],q[i]);max[i]=Math.max(max[i],q[i]);}
    if(!seen.add(Arrays.toString(q)))throw new IllegalArgumentException("同一阶段重复位置："+Arrays.toString(q));String state=b.get("state").getAsString();if(state.length()>400||!state.matches("[a-z0-9_.:/=,\\[\\]-]+"))throw new IllegalArgumentException("非法方块状态或NBT");
    blocks.add(new Voxel(q[0],q[1],q[2],state,index));if(blocks.size()>262144)throw new IllegalArgumentException("施工步骤超过262144");
   }
  }
  if(blocks.isEmpty())throw new IllegalArgumentException("施工图不能为空");for(int i=0;i<3;i++)if(max[i]-min[i]+1>64)throw new IllegalArgumentException("建筑尺寸超出64×64×64");
  return new Plan(name,origin,rotation,List.copyOf(blocks),List.copyOf(phases));
 }
 public int[] rotated(Voxel b){return switch(rotation){case 90->new int[]{-b.z,b.y,b.x};case 180->new int[]{-b.x,b.y,-b.z};case 270->new int[]{b.z,b.y,-b.x};default->new int[]{b.x,b.y,b.z};};}
}
