package com.h3xbit.stackablecarpet.datagen;

import com.h3xbit.stackablecarpet.CarpetStacking;
import com.h3xbit.stackablecarpet.StackableCarpet;
import net.fabricmc.fabric.api.client.datagen.v1.provider.FabricModelProvider;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.blockstates.PropertyDispatch;
import net.minecraft.client.data.models.model.ModelLocationUtils;
import net.minecraft.client.data.models.model.ModelTemplate;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ColorCollection;

import java.util.Optional;

public class CarpetModelProvider extends FabricModelProvider {
    public CarpetModelProvider(FabricPackOutput output) {
        super(output);
    }

    // Layer 1 keeps vanilla's own carpet model. Layers 2-15 each parent onto one of the 14
    // hand-authored height templates under assets/stackable_carpet/models/block/carpet_layers/
    // (Mojang's datagen API only ever generates a parent+texture override, never raw box
    // geometry, so those templates can't be generated - see CarpetStacking for the matching
    // Java-side property/shape).
    @Override
    public void generateBlockStateModels(BlockModelGenerators blockModelGenerators) {
        ModelTemplate[] heightTemplates = new ModelTemplate[CarpetStacking.MAX_LAYERS + 1];
        for (int layers = 2; layers <= CarpetStacking.MAX_LAYERS; layers++) {
            heightTemplates[layers] = new ModelTemplate(
                    Optional.of(Identifier.fromNamespaceAndPath(StackableCarpet.MOD_ID, "block/carpet_layers/template_layer_" + layers)),
                    Optional.empty(),
                    TextureSlot.WOOL);
        }

        ColorCollection.zipApply(Blocks.WOOL, Blocks.CARPET, (wool, carpet) -> {
            String carpetName = BuiltInRegistries.BLOCK.getKey(carpet).getPath();
            TextureMapping woolTexture = new TextureMapping().put(TextureSlot.WOOL, TextureMapping.getBlockTexture(wool));

            var dispatch = PropertyDispatch.initial(CarpetStacking.LAYERS)
                    .select(1, BlockModelGenerators.plainVariant(ModelLocationUtils.getModelLocation(carpet)));
            for (int layers = 2; layers <= CarpetStacking.MAX_LAYERS; layers++) {
                Identifier modelId = Identifier.fromNamespaceAndPath(StackableCarpet.MOD_ID, "block/carpet_layers/" + carpetName + "_" + layers);
                heightTemplates[layers].create(modelId, woolTexture, blockModelGenerators.modelOutput);
                dispatch = dispatch.select(layers, BlockModelGenerators.plainVariant(modelId));
            }

            blockModelGenerators.blockStateOutput.accept(MultiVariantGenerator.dispatch(carpet).with(dispatch));
        });
    }

    @Override
    public void generateItemModels(ItemModelGenerators itemModelGenerators) {
    }
}
