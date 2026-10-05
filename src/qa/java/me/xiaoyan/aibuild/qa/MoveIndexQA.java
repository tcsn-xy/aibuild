package me.xiaoyan.aibuild.qa;
import java.util.*;
import net.minecraft.core.BlockPos;
import me.xiaoyan.aibuild.MoveGuard;
public final class MoveIndexQA {
 public static void main(String[] args){List<BlockPos> cells=new ArrayList<>();for(int y=80;y<112;y++)for(int x=832;x<896;x++)for(int z=-240;z<-176;z++)cells.add(new BlockPos(x,y,z));
  Set<BlockPos> old=Set.copyOf(cells),fixed=MoveGuard.index(cells);List<BlockPos> miss=new ArrayList<>();for(int i=0;i<1000;i++)miss.add(new BlockPos(835+i%55,70,-237+i%55));
  for(var p:cells)if(!fixed.contains(p))throw new AssertionError("missing member");for(var p:miss)if(old.contains(p)||fixed.contains(p))throw new AssertionError("false positive");
  long a=System.nanoTime();for(var p:miss)old.contains(p);long slow=System.nanoTime()-a;
  a=System.nanoTime();for(int r=0;r<10;r++)for(var p:miss)fixed.contains(p);long fast=(System.nanoTime()-a)/10;
  if(fast>100_000_000L||slow>5_000_000L&&fast*4>slow)throw new AssertionError("lookup performance regression "+slow+" / "+fast);
  System.out.println("MOVE_INDEX_QA_PASS cells="+cells.size()+" old_1000_miss_ms="+slow/1e6+" fixed_1000_miss_ms="+fast/1e6);
 }
}
