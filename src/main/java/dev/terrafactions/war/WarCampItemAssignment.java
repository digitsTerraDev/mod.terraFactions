package dev.terrafactions.war;

import dev.terrafactions.registry.TerraFactionsBlocks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.UUID;

/** Persistent war assignment stored directly on a War Camp item stack. */
public final class WarCampItemAssignment {
    private static final String ASSIGNMENT_TAG = "TerraFactionsWarCamp";
    private static final String WAR_TAG = "War";
    private static final String OPPONENT_TAG = "Opponent";

    private WarCampItemAssignment() {
    }

    public static void assign(ItemStack stack, UUID warId, String opponentName) {
        if (!isWarCamp(stack)) throw new IllegalArgumentException("The held item is not a War Camp");
        CompoundTag assignment = new CompoundTag();
        assignment.putUUID(WAR_TAG, warId);
        assignment.putString(OPPONENT_TAG, opponentName);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(ASSIGNMENT_TAG, assignment));
    }

    /** Returns the War Camp currently held in either hand, preferring the main hand. */
    public static ItemStack heldWarCamp(ServerPlayer player) {
        ItemStack mainHand = player.getMainHandItem();
        if (isWarCamp(mainHand)) return mainHand;
        ItemStack offHand = player.getOffhandItem();
        return isWarCamp(offHand) ? offHand : ItemStack.EMPTY;
    }

    public static UUID warId(ItemStack stack) {
        CompoundTag assignment = assignment(stack);
        return assignment.hasUUID(WAR_TAG) ? assignment.getUUID(WAR_TAG) : null;
    }

    public static String opponentName(ItemStack stack) {
        return assignment(stack).getString(OPPONENT_TAG);
    }

    private static CompoundTag assignment(ItemStack stack) {
        CompoundTag customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return customData.getCompound(ASSIGNMENT_TAG);
    }

    private static boolean isWarCamp(ItemStack stack) {
        return stack.is(TerraFactionsBlocks.WAR_CAMP_ITEM.get());
    }
}
