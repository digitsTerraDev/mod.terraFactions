package dev.terrafactions.compat.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import dev.terrafactions.TerraFactions;
import dev.terrafactions.factions.FactionIdentity;
import dev.terrafactions.factions.FactionRelation;
import dev.terrafactions.factions.NativeFactionService;
import dev.terrafactions.territory.ProtectionAction;
import dev.terrafactions.territory.TerritoryClaim;
import dev.terrafactions.territory.TerritoryKey;
import dev.terrafactions.territory.TerritoryService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.UUID;

/** Optional Create integration. This class is only loaded by the Create-gated mixin config. */
public final class CreateContraptionProtection {
    private static final String OWNER_TAG = "TerraFactionsOwner";
    private static final String OWNER_RESOLVED_TAG = "TerraFactionsOwnerResolved";

    private CreateContraptionProtection() {
    }

    /**
     * Records a contraption's faction once, at its initial world position. A
     * contraption assembled in wilderness remains unowned instead of acquiring
     * the faction whose border it later enters.
     */
    public static void resolveOwner(AbstractContraptionEntity entity) {
        Level level = entity.level();
        if (level.isClientSide()) return;

        TerritoryService territories = TerraFactions.territories();
        if (territories == null || !territories.factions().isReady()) return;

        CompoundTag data = entity.getPersistentData();
        if (data.getBoolean(OWNER_RESOLVED_TAG)) return;

        UUID owner = entity.getControllingPlayer()
                .map(level::getPlayerByUUID)
                .map(CreateContraptionProtection::buildingFaction)
                .orElse(null);
        if (owner == null) {
            TerritoryClaim sourceClaim = claimAt(level, entity.blockPosition());
            if (sourceClaim != null) owner = sourceClaim.factionId();
        }

        if (owner != null) data.putUUID(OWNER_TAG, owner);
        data.putBoolean(OWNER_RESOLVED_TAG, true);
    }

    /** Returns whether this contraption may change the target block. */
    public static boolean canModify(AbstractContraptionEntity entity, Level level, BlockPos target) {
        if (level.isClientSide()) return true;

        TerritoryService territories = TerraFactions.territories();
        NativeFactionService factions = territories.factions();
        if (!factions.isReady()) return true;

        TerritoryClaim claim = claimAt(level, target);
        if (claim == null || !factions.protectionEnabled(
                claim.factionId(), claim.type(), ProtectionAction.BLOCK_BREAKING)) {
            return true;
        }

        resolveOwner(entity);
        CompoundTag data = entity.getPersistentData();
        if (!data.hasUUID(OWNER_TAG)) return false;

        UUID owner = data.getUUID(OWNER_TAG);
        return owner.equals(claim.factionId())
                || factions.relation(claim.factionId(), owner) == FactionRelation.ALLIED;
    }

    public static boolean canModifyAll(AbstractContraptionEntity entity, Level level,
                                       Iterable<BlockPos> targets) {
        for (BlockPos target : targets) {
            if (!canModify(entity, level, target)) return false;
        }
        return true;
    }

    /**
     * Rejects an assembly that would use a contraption rooted outside a claim to
     * pull in claimed blocks through Super Glue, chassis, or other attachment
     * mechanics. An assembled contraption may only contain claimed blocks from
     * its source faction or one of that faction's allies.
     */
    public static boolean canAssemble(Contraption contraption, Level level) {
        if (level.isClientSide() || contraption.anchor == null) return true;

        TerritoryService territories = TerraFactions.territories();
        if (territories == null || !territories.factions().isReady()) return true;

        TerritoryClaim sourceClaim = claimAt(level, contraption.anchor);
        UUID sourceFaction = sourceClaim == null ? null : sourceClaim.factionId();
        for (BlockPos localPos : contraption.getBlocks().keySet()) {
            TerritoryClaim capturedClaim = claimAt(level, contraption.anchor.offset(localPos));
            if (capturedClaim == null) continue;

            // Wilderness assemblies must never be able to pull any claimed block.
            if (sourceFaction == null) return false;
            if (!sourceFaction.equals(capturedClaim.factionId())
                    && territories.factions().relation(capturedClaim.factionId(), sourceFaction)
                    != FactionRelation.ALLIED) {
                return false;
            }
        }
        return true;
    }

    private static UUID buildingFaction(Player player) {
        FactionIdentity identity = TerraFactions.territories().factions().factionForPlayer(player.getUUID());
        return identity != null && identity.rank().canBuild() ? identity.id() : null;
    }

    private static TerritoryClaim claimAt(Level level, BlockPos pos) {
        TerritoryService territories = TerraFactions.territories();
        if (territories == null || !territories.factions().isReady()) return null;
        return territories.claimAt(new TerritoryKey(
                level.dimension().location().toString(), pos.getX() >> 4, pos.getZ() >> 4));
    }
}
