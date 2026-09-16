package dev.terrafactions.registry;

import dev.terrafactions.TerraFactions;
import dev.terrafactions.anchor.FactionAnchorBlock;
import dev.terrafactions.anchor.AnchorTier;
import dev.terrafactions.war.FactionWarCampBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class TerraFactionsBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(TerraFactions.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(TerraFactions.MOD_ID);

    public static final DeferredBlock<FactionAnchorBlock> FACTION_ANCHOR =
            BLOCKS.registerBlock("faction_anchor", properties -> new FactionAnchorBlock(properties, AnchorTier.BASIC));
    public static final DeferredBlock<FactionAnchorBlock> ADVANCED_FACTION_ANCHOR =
            BLOCKS.registerBlock("advanced_faction_anchor",
                    properties -> new FactionAnchorBlock(properties, AnchorTier.ADVANCED));
    public static final DeferredBlock<FactionAnchorBlock> MASTER_FACTION_ANCHOR =
            BLOCKS.registerBlock("master_faction_anchor",
                    properties -> new FactionAnchorBlock(properties, AnchorTier.MASTER));
    public static final DeferredBlock<FactionWarCampBlock> WAR_CAMP =
            BLOCKS.registerBlock("war_camp", FactionWarCampBlock::new);
    public static final DeferredItem<BlockItem> FACTION_ANCHOR_ITEM = ITEMS.register("faction_anchor",
            () -> new BlockItem(FACTION_ANCHOR.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> ADVANCED_FACTION_ANCHOR_ITEM = ITEMS.register("advanced_faction_anchor",
            () -> new BlockItem(ADVANCED_FACTION_ANCHOR.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> MASTER_FACTION_ANCHOR_ITEM = ITEMS.register("master_faction_anchor",
            () -> new BlockItem(MASTER_FACTION_ANCHOR.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> WAR_CAMP_ITEM = ITEMS.register("war_camp",
            () -> new BlockItem(WAR_CAMP.get(), new Item.Properties().stacksTo(1)));

    private TerraFactionsBlocks() {
    }
}
