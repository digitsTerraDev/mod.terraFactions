package dev.terrafactions.mixin.create;

import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import dev.terrafactions.compat.create.CreateContraptionProtection;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BlockBreakingMovementBehaviour.class, remap = false)
abstract class BlockBreakingMovementBehaviourMixin {
    @Inject(method = "visitNewPosition", at = @At("HEAD"), cancellable = true)
    private void terraFactions$protectVisitedBlock(MovementContext context, BlockPos pos, CallbackInfo callback) {
        if (!CreateContraptionProtection.canModify(context.contraption.entity, context.world, pos)) {
            callback.cancel();
        }
    }

    // Recheck at destruction time in case a claim or its settings changed while
    // the breaker was displaying its gradual block-crack animation.
    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void terraFactions$protectDestroyedBlock(MovementContext context, BlockPos pos, CallbackInfo callback) {
        if (!CreateContraptionProtection.canModify(context.contraption.entity, context.world, pos)) {
            callback.cancel();
        }
    }
}
