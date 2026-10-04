package com.h3xbit.stackablecarpet.mixin;

import com.h3xbit.stackablecarpet.StackableCarpet;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MossyCarpetBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Pale moss carpet stacks in 1/16 layers like the other carpets - only its base lying on the ground,
// the moss hanging off walls on its sides is left as it is.
@Mixin(MossyCarpetBlock.class)
public abstract class PaleMossCarpetMixin extends Block {

    private PaleMossCarpetMixin(Properties properties) {
        super(properties);
    }

    @Inject(method = "createBlockStateDefinition", at = @At("TAIL"))
    private void addLayersProperty(StateDefinition.Builder<Block, BlockState> builder, CallbackInfo ci) {
        builder.add(StackableCarpet.LAYERS);
    }

    // Called constantly (crosshair, movement collision), so it builds no new shape unless it really has to
    @ModifyReturnValue(method = {"getShape", "getCollisionShape"}, at = @At("RETURN"))
    private VoxelShape addLayeredShape(VoxelShape vanillaShape, BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // A single pale moss carpet - every one the world generates - keeps vanilla's shape
        if (!state.getValue(MossyCarpetBlock.BASE) || state.getValue(StackableCarpet.LAYERS) == 1) {
            return vanillaShape;
        }

        VoxelShape stackShape = StackableCarpet.shapeFor(state.getValue(StackableCarpet.LAYERS));

        // Only a stack with moss hanging off its sides needs that moss added to its shape
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (state.getValue(MossyCarpetBlock.getPropertyForFace(side)) != WallSide.NONE) {
                return Shapes.or(vanillaShape, stackShape);
            }
        }
        return stackShape;
    }

    // Any layer carpet can go onto a pale moss carpet stack
    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        if (!state.getValue(MossyCarpetBlock.BASE)) {
            return super.canBeReplaced(state, context);
        }

        if (!(context.getItemInHand().getItem() instanceof BlockItem heldItem)) {
            return false;
        }

        return StackableCarpet.isLayerCarpet(heldItem.getBlock()) && state.getValue(StackableCarpet.LAYERS) < StackableCarpet.MAX_LAYERS;
    }

    // Onto its own stack it adds a layer on top of vanilla's placement state, so the moss on its sides is worked out
    // like a freshly placed one - the 16th turns it into a pale moss block. Onto any other stack it makes a mixed stack.
    @ModifyReturnValue(method = "getStateForPlacement", at = @At("RETURN"))
    private BlockState stackOnPlacement(BlockState placed, BlockPlaceContext context) {
        BlockState existing = context.getLevel().getBlockState(context.getClickedPos());

        if (!StackableCarpet.isStack(existing)) {
            return placed;
        }

        if (existing.is(this)) {
            int layers = existing.getValue(StackableCarpet.LAYERS);
            if (layers < StackableCarpet.MAX_SINGLE_COLOR_LAYERS) {
                return placed.setValue(StackableCarpet.LAYERS, layers + 1);
            }
            return Blocks.PALE_MOSS_BLOCK.defaultBlockState();
        }

        List<Block> layersBelow = StackableCarpet.layersAt(context.getLevel(), context.getClickedPos(), existing);
        if (layersBelow.isEmpty() || layersBelow.size() >= StackableCarpet.MAX_LAYERS) {
            return placed;
        }

        List<Block> layers = new ArrayList<>(layersBelow);
        layers.add(this);

        // The mixed stack's setPlacedBy() hands these to it once it's placed
        StackableCarpet.PENDING_LAYERS.set(layers);
        return StackableCarpet.stateFor(layers);
    }

    // A stack of 2+ layers never breaks instantly in survival - see CarpetStackMixin
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        float progress = super.getDestroyProgress(state, player, level, pos);

        if (state.getValue(MossyCarpetBlock.BASE) && state.getValue(StackableCarpet.LAYERS) > 1) {
            return Math.min(progress, 0.99F);
        }
        return progress;
    }

    // Breaking takes the aimed layer and everything above it, the same way on the client and the server
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (state.getValue(MossyCarpetBlock.BASE)) {
            int aimedLayer = StackableCarpet.targetLayer(player.pick(player.blockInteractionRange(), 1.0F, false), pos, state.getValue(StackableCarpet.LAYERS));

            // The layers under the aimed one - destroy() puts them back
            StackableCarpet.PENDING_LAYERS.set(Collections.nCopies(aimedLayer - 1, this));
        }

        return super.playerWillDestroy(level, pos, state, player);
    }

    // The block's gone - put the layers that were under the aimed one back
    @Override
    public void destroy(LevelAccessor level, BlockPos pos, BlockState state) {
        super.destroy(level, pos, state);

        if (!state.getValue(MossyCarpetBlock.BASE)) {
            return;
        }

        List<Block> layersUnder = StackableCarpet.PENDING_LAYERS.get();
        StackableCarpet.PENDING_LAYERS.remove();

        if (layersUnder != null && !layersUnder.isEmpty()) {
            level.setBlock(pos, state.setValue(StackableCarpet.LAYERS, layersUnder.size()), Block.UPDATE_ALL);
        }
    }

    // The loot table drops one carpet - the rest of the broken layers drop too
    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity, ItemStack tool) {
        super.playerDestroy(level, player, pos, state, blockEntity, tool);

        if (!state.getValue(MossyCarpetBlock.BASE)) {
            return;
        }

        int layersLeft = 0;
        BlockState stateLeft = level.getBlockState(pos);
        if (stateLeft.is(this) && stateLeft.getValue(MossyCarpetBlock.BASE)) {
            layersLeft = stateLeft.getValue(StackableCarpet.LAYERS);
        }

        // Nothing drops when only the aimed layer broke - popResource() ignores an empty stack
        Block.popResource(level, pos, new ItemStack(this, state.getValue(StackableCarpet.LAYERS) - layersLeft - 1));
    }
}
