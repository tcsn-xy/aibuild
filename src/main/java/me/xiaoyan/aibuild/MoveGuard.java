package me.xiaoyan.aibuild;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.player.Player;
import me.xiaoyan.aibuild.mixin.CompoundContainerAccess;
/** Exact edited positions, plus a one-block ticking halo for external hoppers. */
public final class MoveGuard {
 static final Map<Level,Map<String,Set<BlockPos>>> locks=new IdentityHashMap<>();
 public static Set<BlockPos> index(Collection<BlockPos> cells){return Collections.unmodifiableSet(new HashSet<>(cells));}
 public static void hold(ServerLevel l,String id,Collection<BlockPos> cells){locks.computeIfAbsent(l,k->new HashMap<>()).put(id,index(cells));}
 public static void release(ServerLevel l,String id){var m=locks.get(l);if(m!=null)m.remove(id);}
 public static boolean core(Level l,BlockPos p){var m=locks.get(l);if(m!=null)for(var s:m.values())if(s.contains(p))return true;return false;}
 public static boolean contains(Level l,BlockPos p){if(core(l,p))return true;for(Direction d:Direction.values())if(core(l,p.relative(d)))return true;return false;}
 public static boolean container(Container c){if(c instanceof BlockEntity b)return contains(b.getLevel(),b.getBlockPos());if(c instanceof CompoundContainerAccess a)return container(a.aibuild$first())||container(a.aibuild$second());if(c instanceof net.minecraft.world.entity.Entity e)return contains(e.level(),e.blockPosition());return false;}
 public static boolean menu(Player p){if(p.containerMenu==p.inventoryMenu)return false;return p.containerMenu.slots.stream().anyMatch(s->container(s.container));}
 public static void available(ServerLevel l,Set<BlockPos> positions){for(ServerPlayer p:l.players()){
  if(menu(p))throw new IllegalStateException("搬迁范围的容器正在使用，请关闭后继续；不会强关界面");
  for(BlockPos q:BlockPos.betweenClosed(BlockPos.containing(p.getBoundingBox().minX,p.getBoundingBox().minY-1,p.getBoundingBox().minZ),BlockPos.containing(p.getBoundingBox().maxX,p.getBoundingBox().maxY,p.getBoundingBox().maxZ)))if(positions.contains(q))throw new IllegalStateException("玩家在搬迁/拆除位置上，请离开建筑后继续；不会传送玩家");
 }}
 public static void clear(net.minecraft.server.MinecraftServer s){locks.keySet().removeIf(l->l.getServer()==s);}
}
