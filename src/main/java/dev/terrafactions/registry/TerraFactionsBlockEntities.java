package dev.terrafactions.registry;

import dev.terrafactions.TerraFactions;
import dev.terrafactions.anchor.FactionAnchorBlockEntity;
import dev.terrafactions.war.FactionWarCampBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class TerraFactionsBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, TerraFactions.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FactionAnchorBlockEntity>> FACTION_ANCHOR =
            BLOCK_ENTITIES.register("faction_anchor", () -> BlockEntityType.Builder.of(
                    FactionAnchorBlockEntity::new, TerraFactionsBlocks.FACTION_ANCHOR.get(),
                    TerraFactionsBlocks.ADVANCED_FACTION_ANCHOR.get(),
                    TerraFactionsBlocks.MASTER_FACTION_ANCHOR.get(),
                    TerraFactionsBlocks.ULTIMATE_FACTION_ANCHOR.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FactionWarCampBlockEntity>> WAR_CAMP =
            BLOCK_ENTITIES.register("war_camp", () -> BlockEntityType.Builder.of(
                    FactionWarCampBlockEntity::new, TerraFactionsBlocks.WAR_CAMP.get()).build(null));

    private TerraFactionsBlockEntities() {
    }
}
