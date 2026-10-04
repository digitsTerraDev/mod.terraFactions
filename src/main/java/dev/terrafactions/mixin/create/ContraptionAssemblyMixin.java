package dev.terrafactions.mixin.create;

import com.simibubi.create.content.contraptions.Contraption;
import dev.terrafactions.compat.create.CreateContraptionProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents a wilderness contraption from collecting protected blocks at assembly time. */
@Mixin(value = Contraption.class, remap = false)
abstract class ContraptionAssemblyMixin {
    @Inject(method = "searchMovedStructure", at = @At("RETURN"), cancellable = true)
    private void terraFactions$protectAssembly(Level level, BlockPos anchor, Direction direction,
                                               CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ()
                && !CreateContraptionProtection.canAssemble((Contraption) (Object) this, level)) {
            callback.setReturnValue(false);
        }
    }
}
