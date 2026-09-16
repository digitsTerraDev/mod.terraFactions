package dev.terrafactions.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.terrafactions.TerraFactions;
import dev.terrafactions.network.HudVisibilityPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@Mod(value = TerraFactions.MOD_ID, dist = Dist.CLIENT)
public final class TerraFactionsClient {
    private static final KeyMapping OPEN_FACTION_MENU = new KeyMapping(
            "key.terrafactions.open_faction_menu",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "key.categories.terrafactions");
    private static final KeyMapping TOGGLE_FACTION_HUD = new KeyMapping(
            "key.terrafactions.toggle_faction_hud",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            "key.categories.terrafactions");
    private static boolean hudVisibilitySynchronized;

    public TerraFactionsClient(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(TerraFactionsClient::registerGuiLayers);
        modBus.addListener(TerraFactionsClient::registerKeyMappings);
        NeoForge.EVENT_BUS.addListener(TerraFactionsClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(TerraFactionsClient::registerClientCommands);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_FACTION_MENU);
        event.register(TOGGLE_FACTION_HUD);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            hudVisibilitySynchronized = false;
            return;
        }
        if (!hudVisibilitySynchronized) {
            PacketDistributor.sendToServer(new HudVisibilityPayload(TerritoryRadarHud.hudVisible()));
            hudVisibilitySynchronized = true;
        }
        while (OPEN_FACTION_MENU.consumeClick()) {
            if (minecraft.screen == null) {
                minecraft.setScreen(new FactionDashboardScreen());
            }
        }
        while (TOGGLE_FACTION_HUD.consumeClick()) {
            if (minecraft.screen != null) continue;
            boolean visible = TerritoryRadarHud.toggleHudVisibility();
            PacketDistributor.sendToServer(new HudVisibilityPayload(visible));
            minecraft.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "TerraFactions UI " + (visible ? "shown" : "hidden") + "."), true);
        }
    }

    private static void registerClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("factionsui").executes(context -> {
            Minecraft.getInstance().setScreen(new FactionDashboardScreen());
            return 1;
        }));
    }

    private static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(TerraFactions.MOD_ID, "territory_radar"),
                TerritoryRadarHud::render);
    }
}
