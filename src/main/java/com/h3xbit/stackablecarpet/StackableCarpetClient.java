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

    private static class UnbakedMixedCarpetModel implements BlockStateModel.UnbakedRoot {
        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
        }

        @Override
        public BlockStateModel bake(BlockState state, ModelBaker baker) {
            Map<Block, Material.Baked> textures = new HashMap<>();
            for (Block carpet : CarpetStacking.layerCarpets()) {
                textures.put(carpet, baker.materials().get(new Material(CarpetStacking.textureFor(carpet)),
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
            return textures.get(layers(level, pos, state).getLast());
        }

        @Override
        public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<@Nullable Direction> cullTest) {
            List<Block> carpets = layers(level, pos, state);
            int layers = carpets.size();
            for (int i = 0; i < layers; i++) {
                Material.Baked material = textures.get(carpets.get(i));
                float bottom = i / 16.0F;
                float top = (i + 1) / 16.0F;
                if (i == 0 && !cullTest.test(Direction.DOWN)) {
                    emitFace(emitter, material, Direction.DOWN, 0, 0, 1, 1, 0, Direction.DOWN);
                }
                if (i == layers - 1 && !(top == 1 && cullTest.test(Direction.UP))) {
                    emitFace(emitter, material, Direction.UP, 0, 0, 1, 1, 1 - top, top == 1 ? Direction.UP : null);
                }
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    if (!cullTest.test(side)) {
                        emitFace(emitter, material, side, 0, bottom, 1, top, 0, side);
                    }
                }
            }
        }

        private static void emitFace(QuadEmitter emitter, Material.Baked material, Direction face, float left, float bottom, float right, float top, float depth, @Nullable Direction cullFace) {
            emitter.square(face, left, bottom, right, top, depth);
            // cullFace() also overwrites the nominal face, which BAKE_LOCK_UV needs - a null cull face left the top face with no UVs
            emitter.cullFace(cullFace);
            emitter.nominalFace(face);
            emitter.materialBake(material, MutableQuadView.BAKE_LOCK_UV);
            emitter.color(-1, -1, -1, -1);
            emitter.emit();
        }

        // Falls back to white carpet if the layers haven't arrived from the server yet
        private static List<Block> layers(BlockAndTintGetter level, BlockPos pos, BlockState state) {
            int layers = state.getValue(CarpetStacking.LAYERS);
            List<Block> carpets = level != null && level.getBlockEntityRenderData(pos) instanceof List<?> data ? castLayers(data) : List.of();
            if (carpets.size() >= layers) {
                return carpets.subList(0, layers);
            }
            return Collections.nCopies(layers, DEFAULT_LAYER);
        }

        @SuppressWarnings("unchecked")
        private static List<Block> castLayers(List<?> data) {
            return (List<Block>) data;
        }
    }
}
