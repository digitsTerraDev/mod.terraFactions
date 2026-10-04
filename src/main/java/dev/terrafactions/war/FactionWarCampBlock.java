package dev.terrafactions.war;

import com.mojang.serialization.MapCodec;
import dev.terrafactions.TerraFactions;
import dev.terrafactions.territory.TerraFactionsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

public final class FactionWarCampBlock extends BaseEntityBlock {
    public static final MapCodec<FactionWarCampBlock> CODEC = simpleCodec(FactionWarCampBlock::new);

    public FactionWarCampBlock(BlockBehaviour.Properties properties) {
        super(properties.strength(-1.0F, 3_600_000.0F).noOcclusion().pushReaction(PushReaction.BLOCK));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FactionWarCampBlockEntity(pos, state);
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
        if (!(placer instanceof ServerPlayer player)
                || !(level.getBlockEntity(pos) instanceof FactionWarCampBlockEntity blockEntity)) {
            level.removeBlock(pos, false);
            return;
        }
        try {
            UUID assignedWar = WarCampItemAssignment.warId(stack);
            WarCampSnapshot camp = TerraFactions.territories().wars().placeWarCampForPlayer(
                    player.getUUID(), assignedWar, level.dimension().location().toString(), pos);
            blockEntity.assign(camp.id());
            player.sendSystemMessage(Component.literal(camp.state() == WarCampState.ACTIVE
                    ? "War Camp established and active."
                    : "War Camp placed. It is now establishing."));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            player.sendSystemMessage(Component.literal(exception.getMessage()));
            level.removeBlock(pos, false);
            // BlockItem consumes after setPlacedBy returns. Pre-refund survival stacks; creative is not consumed.
            if (!player.getAbilities().instabuild) stack.grow(1);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        UUID warId = WarCampItemAssignment.warId(stack);
        if (warId == null) {
            tooltipComponents.add(Component.translatable("tooltip.terrafactions.war_camp.unassigned")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        String opponent = WarCampItemAssignment.opponentName(stack);
        String label = opponent.isBlank() ? warId.toString() : opponent + " (" + shortId(warId) + ")";
        tooltipComponents.add(Component.translatable("tooltip.terrafactions.war_camp.assigned", label)
                .withStyle(ChatFormatting.GOLD));
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)
                || !(level.getBlockEntity(pos) instanceof FactionWarCampBlockEntity blockEntity)
                || blockEntity.warCampId() == null) return InteractionResult.PASS;
        WarCampSnapshot camp = TerraFactions.territories().wars().getWarCamp(blockEntity.warCampId());
        if (camp == null) return InteractionResult.PASS;
        if (!player.isShiftKeyDown()) {
            int required = TerraFactionsConfig.WAR_CAMP_DESTRUCTION_INTERACTIONS.get();
            String activation = "";
            if (camp.state() == WarCampState.ESTABLISHING) {
                long seconds = Math.max(0L, (camp.activationTime() - level.getGameTime() + 19L) / 20L);
                activation = " | Active in " + seconds + "s";
            }
            serverPlayer.sendSystemMessage(Component.literal("War Camp: " + camp.state().name()
                    + activation + " | Sabotage " + camp.destructionProgress() + "/" + required));
            return InteractionResult.SUCCESS;
        }
        try {
            WarCampSnapshot damaged = TerraFactions.territories().wars().damageWarCamp(
                    player.getUUID(), camp.id(), 1);
            if (damaged.state() == WarCampState.DESTROYED) {
                level.removeBlock(pos, false);
                serverPlayer.sendSystemMessage(Component.literal("War Camp destroyed."));
            } else {
                serverPlayer.sendSystemMessage(Component.literal("War Camp sabotage: "
                        + damaged.destructionProgress() + "/"
                        + TerraFactionsConfig.WAR_CAMP_DESTRUCTION_INTERACTIONS.get()));
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            serverPlayer.sendSystemMessage(Component.literal(exception.getMessage()));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof FactionWarCampBlockEntity blockEntity
                && blockEntity.warCampId() != null) {
            WarManager wars = TerraFactions.territories().wars();
            if (wars.isReady() && wars.getWarCamp(blockEntity.warCampId()) != null) {
                wars.destroyWarCamp(blockEntity.warCampId());
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
