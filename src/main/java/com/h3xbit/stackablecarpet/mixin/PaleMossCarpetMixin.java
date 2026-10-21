package com.h3xbit.stackablecarpet.mixin;

import com.h3xbit.stackablecarpet.StackableCarpet;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MossyCarpetBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Pale moss carpet stacks like the other carpets - only its base lying on the ground, the moss hanging off walls
// is left as it is. It keeps vanilla's block states; a stack of it is a carpet stack.
@Mixin(MossyCarpetBlock.class)
public abstract class PaleMossCarpetMixin extends Block {

    private PaleMossCarpetMixin(Properties properties) {
        super(properties);
    }

    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        if (state.getValue(MossyCarpetBlock.BASE) && StackableCarpet.holdsLayerCarpet(context)) {
            return true;
        }
        return super.canBeReplaced(state, context);
    }

    // Onto a carpet or a stack it makes a stack - see StackableCarpet.stackOnto()
    @ModifyReturnValue(method = "getStateForPlacement", at = @At("RETURN"))
    private BlockState stackOnPlacement(BlockState placed, BlockPlaceContext context) {
        BlockState stacked = StackableCarpet.stackOnto(this, context);
        if (stacked != null) {
            return stacked;
        }
        return placed;
    }
}
