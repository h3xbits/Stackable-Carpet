package com.h3xbit.stackablecarpet.mixin;

import com.h3xbit.stackablecarpet.StackableCarpet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Every carpet - wool, moss and the mixed carpet - stacks in 1/16 layers.
@Mixin(CarpetBlock.class)
public abstract class CarpetStackMixin extends Block {

    private CarpetStackMixin(Properties properties) {
        super(properties);
    }

    @Inject(method = "getShape", at = @At("RETURN"), cancellable = true)
    private void layeredShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        cir.setReturnValue(StackableCarpet.shapeFor(state.getValue(StackableCarpet.LAYERS)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(StackableCarpet.LAYERS);
    }

    // A layer carpet can go onto a stack of layer carpets, any other carpet only onto a stack of itself
    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        if (!(context.getItemInHand().getItem() instanceof BlockItem heldItem)) {
            return false;
        }

        Block heldCarpet = heldItem.getBlock();
        int layers = state.getValue(StackableCarpet.LAYERS);

        if (StackableCarpet.isLayerCarpet(this) || (Object) this instanceof StackableCarpet.MixedCarpet) {
            return StackableCarpet.isLayerCarpet(heldCarpet) && layers < StackableCarpet.MAX_LAYERS;
        }

        return heldCarpet == this && layers < StackableCarpet.MAX_SINGLE_COLOR_LAYERS;
    }

    // On a stack, the same carpet adds a layer and any other layer carpet makes a mixed stack
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState existing = context.getLevel().getBlockState(context.getClickedPos());

        if (!StackableCarpet.isStack(existing)) {
            return defaultBlockState();
        }

        // The same carpet adds a layer - the 16th turns the stack into its full block
        if (existing.is(this)) {
            int layers = existing.getValue(StackableCarpet.LAYERS);
            if (layers < StackableCarpet.MAX_SINGLE_COLOR_LAYERS) {
                return existing.setValue(StackableCarpet.LAYERS, layers + 1);
            }

            Block fullBlock = StackableCarpet.fullBlockFor(this);
            if (fullBlock == null) {
                return defaultBlockState();
            }
            return fullBlock.defaultBlockState();
        }

        // Any other layer carpet on top makes a mixed stack
        List<Block> layersBelow = StackableCarpet.layersAt(context.getLevel(), context.getClickedPos(), existing);

        if (!StackableCarpet.isLayerCarpet(this) || layersBelow.isEmpty() || layersBelow.size() >= StackableCarpet.MAX_LAYERS) {
            return defaultBlockState();
        }

        List<Block> layers = new ArrayList<>(layersBelow);
        layers.add(this);

        // setPlacedBy() hands these to the mixed stack once it's placed
        StackableCarpet.PENDING_LAYERS.set(layers);
        return StackableCarpet.stateFor(layers);
    }

    // A mixed stack that was just placed gets the layers getStateForPlacement() worked out for it
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        List<Block> layers = StackableCarpet.PENDING_LAYERS.get();
        StackableCarpet.PENDING_LAYERS.remove();

        if (layers == null) {
            return;
        }

        if (level.getBlockEntity(pos) instanceof StackableCarpet.MixedCarpetEntity entity) {
            entity.setLayers(layers);
        }
    }

    // A stack of 2+ layers never breaks instantly in survival: a layer is left behind straight away, and an instant
    // break lets the same click carry on into it. Mined normally, vanilla waits 5 ticks before starting the next one.
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        float progress = super.getDestroyProgress(state, player, level, pos);

        if (state.getValue(StackableCarpet.LAYERS) > 1) {
            return Math.min(progress, 0.99F);
        }
        return progress;
    }

    // Breaking takes the aimed layer and everything above it. playerWillDestroy(), destroy() and playerDestroy() run
    // the same way on the client and the server, so both end up with the same shorter stack in the same tick.
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // Each layer of the stack, bottom first
        List<Block> layers;
        if ((Object) this instanceof StackableCarpet.MixedCarpet) {
            layers = StackableCarpet.layersAt(level, pos, state);
        } else {
            layers = Collections.nCopies(state.getValue(StackableCarpet.LAYERS), this);
        }

        int aimedLayer = StackableCarpet.targetLayer(player.pick(player.blockInteractionRange(), 1.0F, false), pos, layers.size());

        // The layers under the aimed one - destroy() puts them back
        StackableCarpet.PENDING_LAYERS.set(List.copyOf(layers.subList(0, aimedLayer - 1)));

        // A mixed stack keeps only the broken layers, so its drops are exactly those carpets
        if (level.getBlockEntity(pos) instanceof StackableCarpet.MixedCarpetEntity entity) {
            entity.layers = List.copyOf(layers.subList(aimedLayer - 1, layers.size()));
        }

        return super.playerWillDestroy(level, pos, state, player);
    }

    // The block's gone - put the layers that were under the aimed one back
    @Override
    public void destroy(LevelAccessor level, BlockPos pos, BlockState state) {
        super.destroy(level, pos, state);

        List<Block> layersUnder = StackableCarpet.PENDING_LAYERS.get();
        StackableCarpet.PENDING_LAYERS.remove();

        if (layersUnder == null || layersUnder.isEmpty()) {
            return;
        }

        if ((Object) this instanceof StackableCarpet.MixedCarpet) {
            StackableCarpet.place(level, pos, layersUnder);
        } else {
            level.setBlock(pos, state.setValue(StackableCarpet.LAYERS, layersUnder.size()), Block.UPDATE_ALL);
        }
    }

    // The loot table drops one carpet - the rest of the broken layers drop here
    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity, ItemStack tool) {
        super.playerDestroy(level, player, pos, state, blockEntity, tool);

        // A mixed stack's drops all come from its block entity
        if ((Object) this instanceof StackableCarpet.MixedCarpet) {
            return;
        }

        int layersLeft = 0;
        BlockState stateLeft = level.getBlockState(pos);
        if (stateLeft.is(this)) {
            layersLeft = stateLeft.getValue(StackableCarpet.LAYERS);
        }

        // Nothing drops when only the aimed layer broke - popResource() ignores an empty stack
        Block.popResource(level, pos, new ItemStack(this, state.getValue(StackableCarpet.LAYERS) - layersLeft - 1));
    }

    // A mixed stack drops one of each carpet in it
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof StackableCarpet.MixedCarpetEntity entity) {
            return entity.layers.stream().map(ItemStack::new).toList();
        }
        return super.getDrops(state, params);
    }
}
