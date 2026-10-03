package com.h3xbit.stackablecarpet;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class StackableCarpetClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ModelLoadingPlugin.register(context -> context.registerBlockStateResolver(StackableCarpet.MIXED_CARPET, resolver -> {
            for (BlockState state : resolver.block().getStateDefinition().getPossibleStates()) {
                resolver.setModel(state, new UnbakedMixedCarpetModel());
            }
        }));
    }

    // The texture the carpet's own model uses - its full block's, except pale moss carpet has its own
    public static Identifier textureFor(Block carpet) {
        if (carpet == Blocks.PALE_MOSS_CARPET) {
            return BuiltInRegistries.BLOCK.getKey(carpet).withPrefix("block/");
        }
        return BuiltInRegistries.BLOCK.getKey(StackableCarpet.fullBlockFor(carpet)).withPrefix("block/");
    }

    private static class UnbakedMixedCarpetModel implements BlockStateModel.UnbakedRoot {
        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
        }

        @Override
        public BlockStateModel bake(BlockState state, ModelBaker baker) {
            Map<Block, Material.Baked> textures = new HashMap<>();
            for (Block carpet : StackableCarpet.layerCarpets()) {
                textures.put(carpet, baker.materials().get(new Material(textureFor(carpet)),
                        () -> StackableCarpet.MIXED_CARPET_ID.toString()));
            }
            return new MixedCarpetModel(textures);
        }

        @Override
        public Object visualEqualityGroup(BlockState state) {
            return state;
        }
    }

    // One 1/16 slab per layer in that carpet's texture, built while the chunk mesh is - so it gets
    // the same smooth lighting and face culling as the plain carpet stack models
    private record MixedCarpetModel(Map<Block, Material.Baked> textures) implements BlockStateModel {
        private static final Block DEFAULT_LAYER = Blocks.CARPET.pick(DyeColor.WHITE);

        @Override
        public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
        }

        @Override
        public Material.Baked particleMaterial() {
            return textures.get(DEFAULT_LAYER);
        }

        @Override
        public int materialFlags() {
            return 0;
        }

        @Override
        public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
            return textures.get(layersOf(level, pos, state).getLast());
        }

        @Override
        public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<@Nullable Direction> cullTest) {
            List<Block> layers = layersOf(level, pos, state);
            for (int i = 0; i < layers.size(); i++) {
                Material.Baked material = textures.get(layers.get(i));
                float bottom = i / 16.0F;
                float top = (i + 1) / 16.0F;

                if (i == 0 && !cullTest.test(Direction.DOWN)) {
                    emitFace(emitter, material, Direction.DOWN, 0, 1, 0, Direction.DOWN);
                }

                // The top of the stack - only hidden by the block above when the stack is a full block tall
                if (i == layers.size() - 1) {
                    if (top < 1) {
                        emitFace(emitter, material, Direction.UP, 0, 1, 1 - top, null);
                    } else if (!cullTest.test(Direction.UP)) {
                        emitFace(emitter, material, Direction.UP, 0, 1, 0, Direction.UP);
                    }
                }

                for (Direction side : Direction.Plane.HORIZONTAL) {
                    if (!cullTest.test(side)) {
                        emitFace(emitter, material, side, bottom, top, 0, side);
                    }
                }
            }
        }

        // A face the full width of the block, from bottom to top on it
        private static void emitFace(QuadEmitter emitter, Material.Baked material, Direction face, float bottom, float top, float depth, @Nullable Direction cullFace) {
            emitter.square(face, 0, bottom, 1, top, depth);
            // cullFace() also overwrites the nominal face, which BAKE_LOCK_UV needs - a null cull face left the top face with no UVs
            emitter.cullFace(cullFace);
            emitter.nominalFace(face);
            emitter.materialBake(material, MutableQuadView.BAKE_LOCK_UV);
            emitter.color(-1, -1, -1, -1);
            emitter.emit();
        }

        // The stack's layers from its block entity - white carpet if they haven't arrived from the server yet
        @SuppressWarnings("unchecked")
        private static List<Block> layersOf(BlockAndTintGetter level, BlockPos pos, BlockState state) {
            int count = state.getValue(StackableCarpet.LAYERS);
            if (level != null && level.getBlockEntityRenderData(pos) instanceof List<?> renderData && renderData.size() >= count) {
                return ((List<Block>) renderData).subList(0, count);
            }
            return Collections.nCopies(count, DEFAULT_LAYER);
        }
    }
}
