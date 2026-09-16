package dev.terrafactions.mixin.create;

import com.simibubi.create.content.contraptions.actors.plough.PloughMovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import dev.terrafactions.compat.create.CreateContraptionProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PloughMovementBehaviour.class, remap = false)
abstract class PloughMovementBehaviourMixin {
    @Inject(method = "visitNewPosition", at = @At("HEAD"), cancellable = true)
    private void terraFactions$protectPloughedBlock(MovementContext context, BlockPos pos, CallbackInfo callback) {
        if (!CreateContraptionProtection.canModify(context.contraption.entity, context.world, pos)
                || !CreateContraptionProtection.canModify(context.contraption.entity, context.world, pos.below())) {
            callback.cancel();
        }
    }

    @Inject(method = "onBlockBroken", at = @At("HEAD"), cancellable = true)
    private void terraFactions$protectPloughDrops(MovementContext context, BlockPos pos,
                                                  BlockState state, CallbackInfo callback) {
        if (!CreateContraptionProtection.canModify(context.contraption.entity, context.world, pos)) {
            callback.cancel();
        }
    }
}
