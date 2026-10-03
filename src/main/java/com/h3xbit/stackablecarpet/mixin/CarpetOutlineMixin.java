package com.h3xbit.stackablecarpet.mixin;

import com.h3xbit.stackablecarpet.StackableCarpet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// The hover outline for a carpet stack should highlight only the layer being aimed at, not the
// whole stack - narrows the shape extractBlockOutline() already computed to a single 1/16 slice
// based on where the player's current hit result actually lands vertically within the block.
@Mixin(LevelExtractor.class)
public abstract class CarpetOutlineMixin {

    @Shadow
    private Minecraft minecraft;

    @Shadow
    private ClientLevel level;

    @ModifyVariable(method = "extractBlockOutline", at = @At("STORE"), name = "shape")
    private VoxelShape stackable_carpet$narrowToHoveredLayer(VoxelShape shape) {
        if (!(this.minecraft.hitResult instanceof BlockHitResult blockHitResult)) {
            return shape;
        }
        BlockPos pos = blockHitResult.getBlockPos();
        BlockState state = this.level.getBlockState(pos);
        if (!StackableCarpet.isStack(state)) {
            return shape;
        }
        int targetLayer = StackableCarpet.targetLayer(blockHitResult, pos, state.getValue(StackableCarpet.LAYERS));
        return Block.column(16.0, targetLayer - 1, targetLayer);
    }
}
