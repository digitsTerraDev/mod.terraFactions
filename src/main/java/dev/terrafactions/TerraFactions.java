package dev.terrafactions;

import com.mojang.logging.LogUtils;
import dev.terrafactions.journeymap.TerraFactionsClientConfig;
import dev.terrafactions.network.TerraFactionsNetwork;
import dev.terrafactions.registry.TerraFactionsBlockEntities;
import dev.terrafactions.registry.TerraFactionsBlocks;
import dev.terrafactions.territory.TerraFactionsConfig;
import dev.terrafactions.territory.TerritoryService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.slf4j.Logger;

@Mod(TerraFactions.MOD_ID)
public final class TerraFactions {
    public static final String MOD_ID = "terrafactions";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static TerritoryService territories;

    public TerraFactions(IEventBus modBus, ModContainer container) {
        TerraFactionsBlocks.BLOCKS.register(modBus);
        TerraFactionsBlocks.ITEMS.register(modBus);
        TerraFactionsBlockEntities.BLOCK_ENTITIES.register(modBus);
        modBus.addListener(TerraFactions::addCreativeTabContents);
        container.registerConfig(ModConfig.Type.SERVER, TerraFactionsConfig.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, TerraFactionsClientConfig.SPEC);
        modBus.addListener(TerraFactionsNetwork::registerPayloads);
        territories = new TerritoryService();
        territories.register();
        LOGGER.info("TerraFactions initialized on NeoForge");
    }

    public static TerritoryService territories() {
        return territories;
    }

    private static void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.FUNCTIONAL_BLOCKS)) {
            event.accept(TerraFactionsBlocks.FACTION_ANCHOR_ITEM.get());
            event.accept(TerraFactionsBlocks.ADVANCED_FACTION_ANCHOR_ITEM.get());
            event.accept(TerraFactionsBlocks.MASTER_FACTION_ANCHOR_ITEM.get());
            event.accept(TerraFactionsBlocks.WAR_CAMP_ITEM.get());
        }
    }
}
