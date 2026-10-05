package me.xiaoyan.aibuild;
import net.minecraft.nbt.CompoundTag;
/** Preserve contents/names/components; ignore only a hopper's automatic cooldown. */
public final class EntityFingerprint {
 public static boolean same(CompoundTag actual,CompoundTag initial,boolean hopper){
  if(!hopper)return actual.equals(initial);
  CompoundTag a=actual.copy(),b=initial.copy();a.remove("TransferCooldown");b.remove("TransferCooldown");return a.equals(b);
 }
}
