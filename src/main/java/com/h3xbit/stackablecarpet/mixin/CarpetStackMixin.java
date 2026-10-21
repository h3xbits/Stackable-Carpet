package com.h3xbit.stackablecarpet.mixin;

import com.h3xbit.stackablecarpet.StackableCarpet;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

// A wool or moss carpet lying on the ground takes another layer carpet on top, which makes it a carpet stack.
// The carpet itself keeps vanilla's block states, so plain Minecraft can still join.
@Mixin(CarpetBlock.class)
public abstract class CarpetStackMixin extends Block {

    private CarpetStackMixin(Properties properties) {
        super(properties);
    }

    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        if (StackableCarpet.isLayerCarpet(this) && StackableCarpet.holdsLayerCarpet(context)) {
            return true;
        }
        return super.canBeReplaced(state, context);
    }

    // Onto a carpet or a stack it makes a stack - see StackableCarpet.stackOnto()
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState stacked = StackableCarpet.stackOnto(this, context);
        if (stacked != null) {
            return stacked;
        }
        return super.getStateForPlacement(context);
    }
}
