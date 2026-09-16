package dev.terrafactions.war;

import dev.terrafactions.TerraFactions;
import dev.terrafactions.registry.TerraFactionsBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

public final class FactionWarCampBlockEntity extends BlockEntity {
    private UUID warCampId;

    public FactionWarCampBlockEntity(BlockPos pos, BlockState state) {
        super(TerraFactionsBlockEntities.WAR_CAMP.get(), pos, state);
    }

    public UUID warCampId() {
        return warCampId;
    }

    public void assign(UUID value) {
        warCampId = value;
        setChanged();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level == null || level.isClientSide || warCampId == null
                || !TerraFactions.territories().wars().isReady()) return;
        WarCampSnapshot camp = TerraFactions.territories().wars().getWarCamp(warCampId);
        if (camp == null || camp.state() == WarCampState.DESTROYED
                || !camp.dimension().equals(level.dimension().location().toString())
                || camp.x() != getBlockPos().getX() || camp.y() != getBlockPos().getY()
                || camp.z() != getBlockPos().getZ()) {
            level.removeBlock(getBlockPos(), false);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        warCampId = tag.hasUUID("war_camp") ? tag.getUUID("war_camp") : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (warCampId != null) tag.putUUID("war_camp", warCampId);
    }
}
