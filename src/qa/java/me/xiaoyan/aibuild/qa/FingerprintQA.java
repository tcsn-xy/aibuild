package me.xiaoyan.aibuild.qa;
import me.xiaoyan.aibuild.EntityFingerprint;
import net.minecraft.nbt.*;
public final class FingerprintQA {
 public static void main(String[] args){
  CompoundTag before=new CompoundTag();before.putInt("TransferCooldown",-1);before.put("Items",new ListTag());CompoundTag after=before.copy();after.putInt("TransferCooldown",0);
  if(before.equals(after))throw new AssertionError("旧完整NBT比较应当复现误判");
  if(!EntityFingerprint.same(after,before,true))throw new AssertionError("空漏斗计时应忽略");
  if(EntityFingerprint.same(after,before,false))throw new AssertionError("其他实体不能忽略此字段");
  CompoundTag item=new CompoundTag();item.putString("id","minecraft:diamond");item.putInt("count",1);ListTag inventory=new ListTag();inventory.add(item);after.put("Items",inventory);
  if(EntityFingerprint.same(after,before,true))throw new AssertionError("实际物品必须保留");
  after=before.copy();after.putString("CustomName","玩家的箱子");if(EntityFingerprint.same(after,before,true))throw new AssertionError("自定义名字必须保留");
  if(!before.getInt("TransferCooldown").orElseThrow().equals(-1))throw new AssertionError("不能修改原始快照");
  System.out.println("Fingerprint regression checks passed: cooldown ignored; inventory/name preserved; snapshots immutable.");
 }
}
