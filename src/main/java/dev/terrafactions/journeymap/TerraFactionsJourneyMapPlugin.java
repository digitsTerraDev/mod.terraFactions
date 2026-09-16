package dev.terrafactions.journeymap;

import dev.terrafactions.TerraFactions;
import dev.terrafactions.factions.FactionIdentity;
import dev.terrafactions.territory.TerraFactionsConfig;
import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.common.event.ServerEventRegistry;
import journeymap.api.v2.server.IServerAPI;
import journeymap.api.v2.server.IServerPlugin;
import journeymap.api.v2.server.event.PlayerRadarUpdateEvent;
import net.minecraft.server.level.ServerPlayer;

@JourneyMapPlugin(apiVersion = "2.0.0")
public final class TerraFactionsJourneyMapPlugin implements IServerPlugin {
    private static ClaimOverlayManager overlayManager;

    @Override
    public synchronized void initialize(IServerAPI api) {
        ServerEventRegistry.PLAYER_RADAR_UPDATE_EVENT.subscribe(
                TerraFactions.MOD_ID, TerraFactionsJourneyMapPlugin::filterPlayerRadar);
        if (overlayManager == null) {
            overlayManager = new ClaimOverlayManager(api.getOverlayApi());
            overlayManager.initialize();
        }
        TerraFactions.LOGGER.info("Native NeoForge JourneyMap integration initialized");
    }

    private static void filterPlayerRadar(PlayerRadarUpdateEvent event) {
        if (event.getAction() != PlayerRadarUpdateEvent.Action.UPDATE
                || !event.isVisible()
                || !TerraFactionsConfig.JOURNEYMAP_FACTION_ONLY_PLAYER_RADAR.get()
                || event.getReceiver().getUUID().equals(event.getRemoteId())) {
            return;
        }

        var factions = TerraFactions.territories().factions();
        if (!factions.isReady()) {
            event.setVisible(false);
            return;
        }

        FactionIdentity receiverFaction = factions.factionForPlayer(event.getReceiver().getUUID());
        FactionIdentity remoteFaction = factions.factionForPlayer(event.getRemoteId());
        if (receiverFaction == null || remoteFaction == null
                || !receiverFaction.id().equals(remoteFaction.id())) {
            event.setVisible(false);
        }
    }

    @Override
    public String getModId() {
        return TerraFactions.MOD_ID;
    }

    public static boolean setOverlayEnabled(ServerPlayer player, boolean enabled) {
        ClaimOverlayManager manager = overlayManager;
        if (manager == null) {
            return false;
        }
        manager.setEnabled(player, enabled);
        return true;
    }
}
