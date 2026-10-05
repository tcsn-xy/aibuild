package me.xiaoyan.aibuild.mixin;
import me.xiaoyan.aibuild.BackgroundConnection;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(LevelSummary.class)
public abstract class OfflineWorldSummaryMixin {
 @Inject(method="isLocked",at=@At("HEAD"),cancellable=true)
 private void localBackend(CallbackInfoReturnable<Boolean> ci){
  Minecraft mc=Minecraft.getInstance();if(BackgroundConnection.find(mc.gameDirectory.toPath().toAbsolutePath().normalize(),((LevelSummary)(Object)this).getLevelId()).isPresent())ci.setReturnValue(false);
 }
 @Inject(method={"canUpload","canEdit","canRecreate","canDelete"},at=@At("HEAD"),cancellable=true)
 private void noConcurrentFileActions(CallbackInfoReturnable<Boolean> ci){Minecraft mc=Minecraft.getInstance();if(BackgroundConnection.find(mc.gameDirectory.toPath().toAbsolutePath().normalize(),((LevelSummary)(Object)this).getLevelId()).isPresent())ci.setReturnValue(false);}
}

