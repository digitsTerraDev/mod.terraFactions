package dev.terrafactions.anchor;

import com.mojang.serialization.MapCodec;
import dev.terrafactions.TerraFactions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

public final class FactionAnchorBlock extends BaseEntityBlock {
    public static final MapCodec<FactionAnchorBlock> CODEC = simpleCodec(properties ->
            new FactionAnchorBlock(properties, AnchorTier.BASIC));
    private final AnchorTier tier;

    public FactionAnchorBlock(BlockBehaviour.Properties properties, AnchorTier tier) {
        super(properties.strength(5.0F, 1200.0F).requiresCorrectToolForDrops()
                .pushReaction(PushReaction.BLOCK));
        this.tier = tier;
    }

    public AnchorTier tier() {
        return tier;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FactionAnchorBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;
        if (placer instanceof ServerPlayer player
                && level.getBlockEntity(pos) instanceof FactionAnchorBlockEntity anchor) {
            if (!TerraFactions.territories().placeAnchor(player, anchor)) rejectPlacement(level, pos, player, stack);
        } else {
            level.removeBlock(pos, false);
        }
    }

    private static void rejectPlacement(Level level, BlockPos pos, ServerPlayer player, ItemStack stack) {
        level.removeBlock(pos, false);
        // BlockItem consumes after setPlacedBy returns. Pre-refund survival stacks; creative is not consumed.
        if (!player.getAbilities().instabuild) stack.grow(1);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof FactionAnchorBlockEntity anchor) {
            TerraFactions.territories().openAnchor(serverPlayer, anchor);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof FactionAnchorBlockEntity anchor) {
            TerraFactions.territories().removeAnchor(anchor);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
