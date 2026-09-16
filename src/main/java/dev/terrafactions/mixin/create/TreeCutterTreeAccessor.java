package dev.terrafactions.mixin.create;

import com.simibubi.create.content.kinetics.saw.TreeCutter;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(value = TreeCutter.Tree.class, remap = false)
public interface TreeCutterTreeAccessor {
    @Accessor("logs")
    List<BlockPos> terraFactions$getLogs();

    @Accessor("leaves")
    List<BlockPos> terraFactions$getLeaves();

    @Accessor("attachments")
    List<BlockPos> terraFactions$getAttachments();
}
