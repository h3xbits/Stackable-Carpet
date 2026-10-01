package com.h3xbit.stackablecarpet;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.VoxelShape;

// Shared between CarpetStackMixin, CarpetBreakMixin and CarpetModelProvider - all three need to
// agree on the exact same property instance and layer count.
public final class CarpetStacking {

    public static final int MAX_LAYERS = 15;
    public static final IntegerProperty LAYERS = IntegerProperty.create("layers", 1, MAX_LAYERS);

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

    public static Block woolFor(DyeColor color) {
        return Blocks.WOOL.pick(color);
    }
}
