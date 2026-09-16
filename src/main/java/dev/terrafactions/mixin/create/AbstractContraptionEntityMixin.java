package dev.terrafactions.mixin.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.terrafactions.compat.create.CreateContraptionProtection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractContraptionEntity.class, remap = false)
abstract class AbstractContraptionEntityMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void terraFactions$resolveOwner(CallbackInfo callback) {
        CreateContraptionProtection.resolveOwner((AbstractContraptionEntity) (Object) this);
    }
}
