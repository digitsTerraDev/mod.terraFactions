package dev.terrafactions.mixin.create;

import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.saw.SawMovementBehaviour;
import com.simibubi.create.content.kinetics.saw.TreeCutter;
import dev.terrafactions.compat.create.CreateContraptionProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SawMovementBehaviour.class, remap = false)
abstract class SawMovementBehaviourMixin {
    @Inject(method = "onBlockBroken", at = @At("HEAD"), cancellable = true)
    private void terraFactions$protectCutTree(MovementContext context, BlockPos pos,
                                              BlockState state, CallbackInfo callback) {
        if (!CreateContraptionProtection.canModify(context.contraption.entity, context.world, pos)) {
            callback.cancel();
            return;
        }
        if (state.is(BlockTags.LEAVES) || TreeCutter.canDynamicTreeCutFrom(state.getBlock())) return;

        TreeCutter.Tree tree = TreeCutter.findTree(context.world, pos, state);
        TreeCutterTreeAccessor blocks = (TreeCutterTreeAccessor) (Object) tree;
        if (!CreateContraptionProtection.canModifyAll(
                    context.contraption.entity, context.world, blocks.terraFactions$getLogs())
                || !CreateContraptionProtection.canModifyAll(
                    context.contraption.entity, context.world, blocks.terraFactions$getLeaves())
                || !CreateContraptionProtection.canModifyAll(
                    context.contraption.entity, context.world, blocks.terraFactions$getAttachments())) {
            callback.cancel();
        }
    }
}
