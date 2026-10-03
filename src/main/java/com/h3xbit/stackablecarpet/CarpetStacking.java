package com.h3xbit.stackablecarpet;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.MossyCarpetBlock;
import net.minecraft.world.level.block.WoolCarpetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Shared between the stacking mixins, MixedCarpetBlock and CarpetModelProvider - they all need to
// agree on the exact same property instance, layer count and which carpets can be layers.
public final class CarpetStacking {

    // A full block - only mixed stacks get this high, the 16th layer of a single carpet turns it into its full block
    public static final int MAX_LAYERS = 16;
    public static final int MAX_SINGLE_COLOR_LAYERS = MAX_LAYERS - 1;
    public static final IntegerProperty LAYERS = IntegerProperty.create("layers", 1, MAX_LAYERS);

    // Layers for a mixed stack being placed - set in getStateForPlacement(), used up in MixedCarpetBlock.setPlacedBy()
    public static final ThreadLocal<List<Block>> PENDING_LAYERS = new ThreadLocal<>();

    private static final VoxelShape[] SHAPES = new VoxelShape[MAX_LAYERS + 1];

    static {
        for (int layers = 1; layers <= MAX_LAYERS; layers++) {
            SHAPES[layers] = Block.column(16.0, 0.0, layers);
        }
    }

    private CarpetStacking() {
    }

    public static VoxelShape shapeFor(int layers) {
        return SHAPES[layers];
    }

    // The 16 wool carpets, moss carpet and pale moss carpet - the carpets that can go into a mixed stack
    public static List<Block> layerCarpets() {
        List<Block> carpets = new ArrayList<>();
        for (DyeColor color : DyeColor.values()) {
            carpets.add(Blocks.CARPET.pick(color));
        }
        carpets.add(Blocks.MOSS_CARPET);
        carpets.add(Blocks.PALE_MOSS_CARPET);
        return carpets;
    }

    public static boolean isLayerCarpet(Block block) {
        return block instanceof WoolCarpetBlock || block == Blocks.MOSS_CARPET || block == Blocks.PALE_MOSS_CARPET;
    }

    // What 16 layers of one carpet turn into
    public static Block fullBlockFor(Block carpet) {
        if (carpet instanceof WoolCarpetBlock woolCarpet) {
            return Blocks.WOOL.pick(woolCarpet.getColor());
        }
        if (carpet == Blocks.MOSS_CARPET) {
            return Blocks.MOSS_BLOCK;
        }
        if (carpet == Blocks.PALE_MOSS_CARPET) {
            return Blocks.PALE_MOSS_BLOCK;
        }
        return null;
    }

    // The texture the carpet's own model uses
    public static Identifier textureFor(Block carpet) {
        if (carpet instanceof WoolCarpetBlock woolCarpet) {
            return Identifier.withDefaultNamespace("block/" + woolCarpet.getColor().getName() + "_wool");
        }
        if (carpet == Blocks.MOSS_CARPET) {
            return Identifier.withDefaultNamespace("block/moss_block");
        }
        return Identifier.withDefaultNamespace("block/pale_moss_carpet");
    }

    // A carpet lying on the ground (pale moss carpet only with its base, not just hanging off a wall) or a mixed stack
    public static boolean isStack(BlockState state) {
        if (state.getBlock() instanceof MossyCarpetBlock) {
            return state.getValue(MossyCarpetBlock.BASE);
        }
        return state.getBlock() instanceof CarpetBlock;
    }

    // Layers bottom first - empty for anything that isn't a stack of layer carpets
    public static List<Block> layersAt(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof MixedCarpetBlock) {
            if (level.getBlockEntity(pos) instanceof MixedCarpetBlock.Entity entity) {
                List<Block> layers = entity.layers();
                return layers.subList(0, Math.min(layers.size(), state.getValue(LAYERS)));
            }
            return List.of();
        }
        if (isStack(state) && isLayerCarpet(state.getBlock())) {
            return Collections.nCopies(state.getValue(LAYERS), state.getBlock());
        }
        return List.of();
    }

    public static List<Block> withLayerOnTop(List<Block> layers, Block top) {
        List<Block> result = new ArrayList<>(layers);
        result.add(top);
        return result;
    }

    // One carpet all the way up is a plain carpet stack, anything else a mixed one
    public static BlockState stateFor(List<Block> layers) {
        Block first = layers.getFirst();
        Block block = layers.stream().allMatch(layer -> layer == first) ? first : StackableCarpet.MIXED_CARPET;
        return block.defaultBlockState().setValue(LAYERS, layers.size());
    }

    public static void place(Level level, BlockPos pos, List<Block> layers) {
        level.setBlock(pos, stateFor(layers), Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof MixedCarpetBlock.Entity entity) {
            entity.setLayers(layers);
        }
    }

    // Who can go on top of a stack: a layer carpet onto a stack of layer carpets, otherwise only the same carpet
    public static boolean canStackOnto(BlockState state, BlockPlaceContext context) {
        if (!(context.getItemInHand().getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        Block held = blockItem.getBlock();
        int layers = state.getValue(LAYERS);
        if (isLayerCarpet(state.getBlock()) || state.getBlock() instanceof MixedCarpetBlock) {
            return isLayerCarpet(held) && layers < MAX_LAYERS;
        }
        return held == state.getBlock() && layers < MAX_SINGLE_COLOR_LAYERS;
    }

    // The state for placing `held` where a stack already is, or null when it isn't going onto a stack
    public static BlockState stateForPlacingOnto(BlockPlaceContext context, Block held) {
        BlockPos pos = context.getClickedPos();
        BlockState existing = context.getLevel().getBlockState(pos);
        if (!isStack(existing)) {
            return null;
        }
        if (existing.getBlock() == held) {
            int layers = existing.getValue(LAYERS);
            if (layers < MAX_SINGLE_COLOR_LAYERS) {
                return existing.setValue(LAYERS, layers + 1);
            }
            Block full = fullBlockFor(held);
            return full == null ? null : full.defaultBlockState();
        }
        if (isLayerCarpet(held)) {
            List<Block> below = layersAt(context.getLevel(), pos, existing);
            if (!below.isEmpty() && below.size() < MAX_LAYERS) {
                List<Block> layers = withLayerOnTop(below, held);
                PENDING_LAYERS.set(layers);
                return stateFor(layers);
            }
        }
        return null;
    }
}
