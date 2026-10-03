package com.h3xbit.stackablecarpet.mixin;

import com.h3xbit.stackablecarpet.CarpetStacking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MossyCarpetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Pale moss carpet isn't a CarpetBlock, so it gets the same stacking as CarpetStackMixin here. Only its
// base (the part lying on the ground) stacks - the moss hanging off walls on its sides is left alone.
@Mixin(MossyCarpetBlock.class)
public class PaleMossCarpetStackMixin {

    @Shadow
    private static BlockState getUpdatedState(BlockState state, BlockGetter level, BlockPos pos, boolean placing) {
        throw new AssertionError();
    }

    @Inject(method = "createBlockStateDefinition", at = @At("TAIL"))
    private void stackable_carpet$addLayers(StateDefinition.Builder<Block, BlockState> builder, CallbackInfo ci) {
        builder.add(CarpetStacking.LAYERS);
    }

    @Inject(method = {"getShape", "getCollisionShape"}, at = @At("RETURN"), cancellable = true)
    private void stackable_carpet$getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (state.getValue(MossyCarpetBlock.BASE)) {
            cir.setReturnValue(Shapes.or(cir.getReturnValue(), CarpetStacking.shapeFor(state.getValue(CarpetStacking.LAYERS))));
        }
    }

    @Inject(method = "getStateForPlacement", at = @At("HEAD"), cancellable = true)
    private void stackable_carpet$stackOnPlacement(BlockPlaceContext context, CallbackInfoReturnable<BlockState> cir) {
        BlockState stacked = CarpetStacking.stateForPlacingOnto(context, (Block) (Object) this);
        if (stacked != null) {
            cir.setReturnValue(stacked.getBlock() instanceof MossyCarpetBlock
                    ? getUpdatedState(stacked, context.getLevel(), context.getClickedPos(), true) : stacked);
        }
    }

    // Hanging moss only grows above sides the carpet already has moss on, and vanilla only works those out
    // when a carpet is placed - so do the same whenever a stack is made or grows some other way (a mixed
    // stack broken down to only pale moss, CarpetStacking.place())
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!state.getValue(MossyCarpetBlock.BASE) || oldState.is(state.getBlock()) && oldState.getValue(CarpetStacking.LAYERS) >= state.getValue(CarpetStacking.LAYERS)) {
            return;
        }
        BlockState withSides = getUpdatedState(state, level, pos, true);
        if (withSides != state) {
            level.setBlock(pos, withSides, Block.UPDATE_CLIENTS);
        }
    }

    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        if (state.getValue(MossyCarpetBlock.BASE)) {
            return CarpetStacking.canStackOnto(state, context);
        }
        // Vanilla's default for the hanging-only state
        return state.canBeReplaced() && (context.getItemInHand().isEmpty() || !context.getItemInHand().is(((Block) (Object) this).asItem()));
    }
}
