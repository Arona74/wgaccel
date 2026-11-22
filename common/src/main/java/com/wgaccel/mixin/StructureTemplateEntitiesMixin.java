package com.wgaccel.mixin;

import com.wgaccel.gen.SandboxPlacing;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No entities from a structure template placed for the Voxy LODs (villagers, cats, item frames: never drawn there). Made
 * on the real level, they asked the stand-in for its time and difficulty (the village failed), or logged themselves at a
 * stand-in position. On the entity maker every loader keeps (the method calling it is placeEntities in vanilla,
 * addEntitiesToWorld under NeoForge); both descriptors, 26.1.2 adding a ProblemReporter (each version maps its own).
 */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateEntitiesMixin {
   @Inject(method = {
      "createEntityIgnoreException(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/Optional;",
      "createEntityIgnoreException(Lnet/minecraft/util/ProblemReporter;Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/Optional;"
   }, at = @At("HEAD"), cancellable = true, require = 0)
   private static void wgaccel$noLodEntities(CallbackInfoReturnable<Optional<Entity>> callback) {
      if (SandboxPlacing.active()) {
         callback.setReturnValue(Optional.empty());
      }
   }
}
