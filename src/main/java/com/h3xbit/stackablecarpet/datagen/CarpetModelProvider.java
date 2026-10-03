package com.h3xbit.stackablecarpet.datagen;

import com.h3xbit.stackablecarpet.StackableCarpet;
import com.h3xbit.stackablecarpet.StackableCarpetClient;
import net.fabricmc.fabric.api.client.datagen.v1.provider.FabricModelProvider;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.MultiVariant;
import net.minecraft.client.data.models.blockstates.ConditionBuilder;
import net.minecraft.client.data.models.blockstates.MultiPartGenerator;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.blockstates.PropertyDispatch;
import net.minecraft.client.data.models.model.ModelLocationUtils;
import net.minecraft.client.data.models.model.ModelTemplate;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.renderer.block.dispatch.VariantMutator;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MossyCarpetBlock;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.WallSide;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CarpetModelProvider extends FabricModelProvider {
    public CarpetModelProvider(FabricPackOutput output) {
        super(output);
    }

    // Layer 1 keeps vanilla's own carpet model. Layers 2-16 each parent onto one of the 15
    // hand-authored height templates under assets/stackable_carpet/models/block/carpet_layers/
    // (Mojang's datagen API only ever generates a parent+texture override, never raw box
    // geometry, so those templates can't be generated - see StackableCarpet for the matching
    // Java-side property/shape).
    @Override
    public void generateBlockStateModels(BlockModelGenerators blockModelGenerators) {
        ModelTemplate[] heightTemplates = new ModelTemplate[StackableCarpet.MAX_LAYERS + 1];
        for (int layers = 2; layers <= StackableCarpet.MAX_LAYERS; layers++) {
            heightTemplates[layers] = new ModelTemplate(
                    Optional.of(Identifier.fromNamespaceAndPath(StackableCarpet.MOD_ID, "block/carpet_layers/template_layer_" + layers)),
                    Optional.empty(),
                    TextureSlot.WOOL);
        }

        for (Block carpet : StackableCarpet.layerCarpets()) {
            MultiVariant[] layerModels = layerModels(blockModelGenerators, heightTemplates, carpet);
            if (carpet == Blocks.PALE_MOSS_CARPET) {
                generatePaleMossCarpet(blockModelGenerators, layerModels);
                continue;
            }
            var dispatch = PropertyDispatch.initial(StackableCarpet.LAYERS);
            for (int layers = 1; layers <= StackableCarpet.MAX_LAYERS; layers++) {
                dispatch = dispatch.select(layers, layerModels[layers]);
            }
            blockModelGenerators.blockStateOutput.accept(MultiVariantGenerator.dispatch(carpet).with(dispatch));
        }
    }

    private static MultiVariant[] layerModels(BlockModelGenerators blockModelGenerators, ModelTemplate[] heightTemplates, Block carpet) {
        String carpetName = BuiltInRegistries.BLOCK.getKey(carpet).getPath();
        TextureMapping texture = new TextureMapping().put(TextureSlot.WOOL, new Material(StackableCarpetClient.textureFor(carpet)));
        MultiVariant[] models = new MultiVariant[StackableCarpet.MAX_LAYERS + 1];
        models[1] = BlockModelGenerators.plainVariant(ModelLocationUtils.getModelLocation(carpet));
        for (int layers = 2; layers <= StackableCarpet.MAX_LAYERS; layers++) {
            Identifier modelId = Identifier.fromNamespaceAndPath(StackableCarpet.MOD_ID, "block/carpet_layers/" + carpetName + "_" + layers);
            heightTemplates[layers].create(modelId, texture, blockModelGenerators.modelOutput);
            models[layers] = BlockModelGenerators.plainVariant(modelId);
        }
        return models;
    }

    // Vanilla's pale moss carpet multipart with the base picked by layer count; the moss hanging on its sides is unchanged
    private static void generatePaleMossCarpet(BlockModelGenerators blockModelGenerators, MultiVariant[] layerModels) {
        Block carpet = Blocks.PALE_MOSS_CARPET;
        MultiVariant tall = BlockModelGenerators.plainVariant(ModelLocationUtils.getModelLocation(carpet, "_side_tall"));
        MultiVariant small = BlockModelGenerators.plainVariant(ModelLocationUtils.getModelLocation(carpet, "_side_small"));
        ConditionBuilder noSides = BlockModelGenerators.condition().term(MossyCarpetBlock.BASE, false)
                .term(MossyCarpetBlock.NORTH, WallSide.NONE).term(MossyCarpetBlock.EAST, WallSide.NONE)
                .term(MossyCarpetBlock.SOUTH, WallSide.NONE).term(MossyCarpetBlock.WEST, WallSide.NONE);

        MultiPartGenerator generator = MultiPartGenerator.multiPart(carpet);
        for (int layers = 1; layers <= StackableCarpet.MAX_LAYERS; layers++) {
            generator.with(BlockModelGenerators.condition().term(MossyCarpetBlock.BASE, true).term(StackableCarpet.LAYERS, layers), layerModels[layers]);
        }
        generator.with(noSides, layerModels[1]);
        Map<Direction, VariantMutator> rotations = Map.of(
                Direction.NORTH, BlockModelGenerators.NOP,
                Direction.EAST, BlockModelGenerators.Y_ROT_90.then(BlockModelGenerators.UV_LOCK),
                Direction.SOUTH, BlockModelGenerators.Y_ROT_180.then(BlockModelGenerators.UV_LOCK),
                Direction.WEST, BlockModelGenerators.Y_ROT_270.then(BlockModelGenerators.UV_LOCK));
        for (Direction side : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            VariantMutator rotation = rotations.get(side);
            EnumProperty<WallSide> property = MossyCarpetBlock.getPropertyForFace(side);
            generator.with(BlockModelGenerators.condition().term(property, WallSide.TALL), tall.with(rotation));
            generator.with(BlockModelGenerators.condition().term(property, WallSide.LOW), small.with(rotation));
            generator.with(noSides, tall.with(rotation));
        }
        blockModelGenerators.blockStateOutput.accept(generator);
    }

    @Override
    public void generateItemModels(ItemModelGenerators itemModelGenerators) {
    }
}
