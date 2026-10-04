package dev.terrafactions.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.terrafactions.anchor.FactionAnchorBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;

public final class FactionAnchorRenderer implements BlockEntityRenderer<FactionAnchorBlockEntity> {
    public FactionAnchorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(FactionAnchorBlockEntity anchor, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (!TerraFactionsClient.anchorBeamsVisible() || !anchor.beamActive() || anchor.getLevel() == null) return;
        BeaconRenderer.renderBeaconBeam(poseStack, buffers, BeaconRenderer.BEAM_LOCATION,
                partialTick, 1.0F, anchor.getLevel().getGameTime(), 1, BeaconRenderer.MAX_RENDER_Y,
                anchor.beamColor(), 0.2F, 0.25F);
    }

    @Override
    public boolean shouldRenderOffScreen(FactionAnchorBlockEntity anchor) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }

    @Override
    public AABB getRenderBoundingBox(FactionAnchorBlockEntity anchor) {
        var pos = anchor.getBlockPos();
        return new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0,
                BeaconRenderer.MAX_RENDER_Y, pos.getZ() + 1.0);
    }
}
