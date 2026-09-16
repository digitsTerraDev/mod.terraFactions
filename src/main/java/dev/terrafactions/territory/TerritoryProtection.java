package dev.terrafactions.territory;

import dev.terrafactions.anchor.FactionAnchorBlockEntity;
import dev.terrafactions.factions.FactionIdentity;
import dev.terrafactions.factions.NativeFactionService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

final class TerritoryProtection {
    private final TerritoryService territories;
    private final NativeFactionService factions;

    TerritoryProtection(TerritoryService territories, NativeFactionService factions) {
        this.territories = territories;
        this.factions = factions;
    }

    void register() {
        NeoForge.EVENT_BUS.addListener(this::onBreakBlock);
        NeoForge.EVENT_BUS.addListener(this::onPlaceBlock);
        NeoForge.EVENT_BUS.addListener(this::onUseBlock);
        NeoForge.EVENT_BUS.addListener(this::onUseItem);
        NeoForge.EVENT_BUS.addListener(this::onUseEntity);
        NeoForge.EVENT_BUS.addListener(this::onUseEntitySpecific);
        NeoForge.EVENT_BUS.addListener(this::onAttackEntity);
        NeoForge.EVENT_BUS.addListener(this::onExplosion);
    }

    private void onBreakBlock(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (territories.isOccupiedAnchor(event.getLevel(), event.getPos())) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal(
                    "This occupied anchor must be liberated through a siege interaction."), true);
            return;
        }
        if (territories.isLastCapitalAnchor(event.getLevel(), event.getPos(), player.getUUID())) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal(
                    "You cannot remove the capital's last anchor. Move the capital to another anchored chunk first."),
                    true);
            return;
        }
        if (event.getLevel().getBlockEntity(event.getPos()) instanceof FactionAnchorBlockEntity anchor) {
            FactionIdentity actor = factions.factionForPlayer(player.getUUID());
            if (actor != null && actor.rank().canBuild() && actor.id().equals(anchor.factionId())) {
                // An anchor remains the property of its stored faction even if border pressure changes
                // ownership of the chunk around it.
                return;
            }
            event.setCanceled(true);
            player.displayClientMessage(Component.literal(
                    "Only a building member of the anchor's faction can remove it."), true);
            return;
        }
        TerritoryClaim claim = claimAt(event.getLevel(), event.getPos().getX() >> 4, event.getPos().getZ() >> 4);
        if (isProtectedAgainst(player, claim, ProtectionAction.BLOCK_BREAKING)) {
            if (TerraFactionsConfig.PLUNDER_ALLOW_BLOCK_BREAKING.get()
                    && territories.hasActivePlunderAccess(player, claim)) return;
            event.setCanceled(true);
            warn(player, claim.type());
        }
    }

    private void onPlaceBlock(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        TerritoryClaim claim = claimAt(event.getLevel(), event.getPos().getX() >> 4, event.getPos().getZ() >> 4);
        if (isProtectedAgainst(player, claim, ProtectionAction.BLOCK_PLACEMENT)) {
            if (TerraFactionsConfig.PLUNDER_ALLOW_BLOCK_PLACEMENT.get()
                    && territories.hasActivePlunderAccess(player, claim)) return;
            event.setCanceled(true);
            warn(player, claim.type());
        }
    }

    private void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (denyLiquidPlacement(player, event.getItemStack(), event.getHitVec())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }
        if (player.getItemInHand(event.getHand()).isEmpty()
                && event.getLevel().getBlockEntity(event.getPos()) instanceof FactionAnchorBlockEntity) {
            // Empty-hand anchor interaction is status/configuration only; capture progresses by chunk presence.
            return;
        }
        cancelProtectedInteraction(player, claimAt(event.getLevel(), event.getPos()),
                ProtectionAction.BLOCK_INTERACTIONS, event);
    }

    private void onUseItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getItemStack().getItem() instanceof BucketItem bucket)
                || bucket.content == Fluids.EMPTY) return;
        BlockHitResult hit = Item.getPlayerPOVHitResult(
                event.getLevel(), player, ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK
                || !denyLiquidPlacement(player, event.getItemStack(), hit)) return;
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
    }

    private boolean denyLiquidPlacement(ServerPlayer player, ItemStack stack, BlockHitResult hit) {
        if (!(stack.getItem() instanceof BucketItem bucket) || bucket.content == Fluids.EMPTY) return false;
        Level level = player.serverLevel();
        BlockPos clicked = hit.getBlockPos();
        BlockState clickedState = level.getBlockState(clicked);
        BlockPos destination = clickedState.getBlock() instanceof LiquidBlockContainer container
                && container.canPlaceLiquid(player, level, clicked, clickedState, bucket.content)
                ? clicked : clicked.relative(hit.getDirection());
        TerritoryClaim claim = claimAt(level, destination);
        if (!isProtectedAgainst(player, claim, ProtectionAction.LIQUID_PLACEMENT)) return false;
        if (TerraFactionsConfig.PLUNDER_ALLOW_BLOCK_PLACEMENT.get()
                && territories.hasActivePlunderAccess(player, claim)) return false;
        warn(player, claim.type());
        return true;
    }

    private void onUseEntity(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        cancelProtectedInteraction(player, claimAt(event.getLevel(), event.getTarget().blockPosition()),
                ProtectionAction.ENTITY_INTERACTIONS, event);
    }

    private void onUseEntitySpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        cancelProtectedInteraction(player, claimAt(event.getLevel(), event.getTarget().blockPosition()),
                ProtectionAction.ENTITY_INTERACTIONS, event);
    }

    private void onAttackEntity(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Territory protection must never make PvP one-way. Player damage is
        // governed by normal Minecraft/friendly-fire rules in both directions.
        if (event.getTarget() instanceof ServerPlayer) return;
        TerritoryClaim claim = claimAt(player.level(), event.getTarget().blockPosition());
        if (isProtectedAgainst(player, claim, ProtectionAction.ENTITY_INTERACTIONS)) {
            if (TerraFactionsConfig.PLUNDER_ALLOW_INTERACTIONS.get()
                    && territories.hasActivePlunderAccess(player, claim)) return;
            event.setCanceled(true);
            warn(player, claim.type());
        }
    }

    private void onExplosion(ExplosionEvent.Detonate event) {
        event.getAffectedBlocks().removeIf(pos -> {
            TerritoryClaim claim = claimAt(event.getLevel(), pos);
            return protectionEnabled(claim, ProtectionAction.EXPLOSIONS);
        });
        event.getAffectedEntities().removeIf(entity -> {
            TerritoryClaim claim = claimAt(event.getLevel(), entity.blockPosition());
            return !(entity instanceof ServerPlayer) && protectionEnabled(claim, ProtectionAction.EXPLOSIONS);
        });
    }

    private void cancelProtectedInteraction(ServerPlayer player, TerritoryClaim claim,
                                              ProtectionAction action,
                                              net.neoforged.bus.api.ICancellableEvent event) {
        if (isProtectedAgainst(player, claim, action)) {
            if (TerraFactionsConfig.PLUNDER_ALLOW_INTERACTIONS.get()
                    && territories.hasActivePlunderAccess(player, claim)) return;
            event.setCanceled(true);
            warn(player, claim.type());
        }
    }

    private boolean isProtectedAgainst(ServerPlayer player, TerritoryClaim claim, ProtectionAction action) {
        return protectionEnabled(claim, action)
                && !factions.hasBlockPermission(claim.factionId(), player.getUUID());
    }

    private boolean protectionEnabled(TerritoryClaim claim, ProtectionAction action) {
        return claim != null && factions.protectionEnabled(claim.factionId(), claim.type(), action);
    }

    private TerritoryClaim claimAt(LevelAccessor level, BlockPos pos) {
        return claimAt(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    private TerritoryClaim claimAt(LevelAccessor level, int x, int z) {
        if (!(level instanceof Level actualLevel)) {
            return null;
        }
        TerritoryKey key = new TerritoryKey(actualLevel.dimension().location().toString(), x, z);
        return territories.claimAt(key);
    }

    private static void warn(Player player, TerritoryType type) {
        player.displayClientMessage(Component.literal("You cannot modify blocks in this faction's "
                + type.name().toLowerCase() + "."), true);
    }
}
