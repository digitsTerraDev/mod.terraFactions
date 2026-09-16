package dev.terrafactions.mixin.create;

import com.simibubi.create.content.contraptions.actors.harvester.HarvesterMovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import dev.terrafactions.compat.create.CreateContraptionProtection;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = HarvesterMovementBehaviour.class, remap = false)
abstract class HarvesterMovementBehaviourMixin {
    @Inject(method = "visitNewPosition", at = @At("HEAD"), cancellable = true)
    private void terraFactions$protectHarvestedBlock(MovementContext context, BlockPos pos, CallbackInfo callback) {
        if (!CreateContraptionProtection.canModify(context.contraption.entity, context.world, pos)) {
            callback.cancel();
        }
    }
}
