package dev.terrafactions.anchor;

import dev.terrafactions.TerraFactions;
import dev.terrafactions.registry.TerraFactionsBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

public final class FactionAnchorBlockEntity extends BlockEntity {
    private UUID factionId;
    private int allocatedPower;
    private AnchorVulnerabilityState vulnerabilityState = AnchorVulnerabilityState.INACTIVE;
    private boolean skyExposed;
    private boolean capital;

    public FactionAnchorBlockEntity(BlockPos pos, BlockState state) {
        super(TerraFactionsBlockEntities.FACTION_ANCHOR.get(), pos, state);
    }

    public UUID factionId() {
        return factionId;
    }

    public int allocatedPower() {
        return allocatedPower;
    }

    public AnchorTier tier() {
        return getBlockState().getBlock() instanceof FactionAnchorBlock anchor
                ? anchor.tier() : AnchorTier.BASIC;
    }

    public AnchorVulnerabilityState vulnerabilityState() {
        return vulnerabilityState;
    }

    public boolean beamActive() {
        return skyExposed && vulnerabilityState != AnchorVulnerabilityState.INACTIVE;
    }

    public int beamColor() {
        return switch (vulnerabilityState) {
            // A healthy capital is deliberately blue rather than the normal green Border beam.
            // Vulnerability colors still take precedence so the siege state remains obvious.
            case PROTECTED -> capital ? 0x3399FF : 0x33CC66;
            case VULNERABLE -> 0xFFAA00;
            case FRACTURED -> 0xAA44FF;
            case SIEGE_BREACHED -> 0xFF2222;
            case PENDING -> 0xFFDD33;
            case INACTIVE -> 0x555555;
        };
    }

    public void assign(UUID factionId) {
        this.factionId = factionId;
        setChanged();
    }

    public void updateProjection(int allocatedPower) {
        this.allocatedPower = allocatedPower;
        setChanged();
    }

    public void updateOperationalState(boolean exposed, AnchorVulnerabilityState state, boolean isCapital) {
        if (skyExposed == exposed && vulnerabilityState == state && capital == isCapital) return;
        skyExposed = exposed;
        vulnerabilityState = state;
        capital = isCapital;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) TerraFactions.territories().loadAnchor(this);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        factionId = tag.hasUUID("faction") ? tag.getUUID("faction") : null;
        allocatedPower = Math.max(0, tag.contains("allocated_power") ? tag.getInt("allocated_power")
                : tag.contains("dedicated_power") ? tag.getInt("dedicated_power") : tag.getInt("radius"));
        skyExposed = tag.getBoolean("sky_exposed");
        capital = tag.getBoolean("capital_anchor");
        try {
            vulnerabilityState = AnchorVulnerabilityState.valueOf(tag.getString("anchor_state"));
        } catch (IllegalArgumentException ignored) {
            vulnerabilityState = AnchorVulnerabilityState.INACTIVE;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (factionId != null) tag.putUUID("faction", factionId);
        tag.putInt("allocated_power", allocatedPower);
        tag.putBoolean("sky_exposed", skyExposed);
        tag.putBoolean("capital_anchor", capital);
        tag.putString("anchor_state", vulnerabilityState.name());
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet,
                             HolderLookup.Provider registries) {
        CompoundTag tag = packet.getTag();
        if (tag != null) loadAdditional(tag, registries);
    }
}
