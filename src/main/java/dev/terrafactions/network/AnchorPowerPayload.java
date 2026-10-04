package dev.terrafactions.network;

import dev.terrafactions.TerraFactions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record AnchorPowerPayload(BlockPos pos, int power, int priority) implements CustomPacketPayload {
    public static final Type<AnchorPowerPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TerraFactions.MOD_ID, "anchor_power"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AnchorPowerPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.pos);
                buffer.writeInt(payload.power);
                buffer.writeInt(payload.priority);
            },
            buffer -> new AnchorPowerPayload(buffer.readBlockPos(), buffer.readInt(), buffer.readInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
