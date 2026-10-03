package com.h3xbit.stackablecarpet.mixin;

import com.h3xbit.stackablecarpet.CarpetStacking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CarpetBlock.class)
public class CarpetStackMixin {

    @Inject(method = "getShape", at = @At("RETURN"), cancellable = true)
    private void stackable_carpet$getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        cir.setReturnValue(CarpetStacking.shapeFor(state.getValue(CarpetStacking.LAYERS)));
    }

    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CarpetStacking.LAYERS);
    }

    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        return CarpetStacking.canStackOnto(state, context);
    }

    public BlockState getStateForPlacement(BlockPlaceContext context) {
        CarpetBlock self = (CarpetBlock) (Object) this;
        BlockState stacked = CarpetStacking.stateForPlacingOnto(context, self);
        return stacked != null ? stacked : self.defaultBlockState();
    }
}
