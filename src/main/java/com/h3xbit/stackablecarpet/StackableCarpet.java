package com.h3xbit.stackablecarpet;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class StackableCarpet implements ModInitializer {
    public static final String MOD_ID = "stackable_carpet";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Identifier MIXED_CARPET_ID = Identifier.fromNamespaceAndPath(MOD_ID, "mixed_carpet");
    public static final Block MIXED_CARPET = Registry.register(BuiltInRegistries.BLOCK, MIXED_CARPET_ID,
            new MixedCarpetBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.CARPET.pick(DyeColor.WHITE))
                    .setId(ResourceKey.create(Registries.BLOCK, MIXED_CARPET_ID))));

    @Override
    public void onInitialize() {
        MixedCarpetBlock.Entity.TYPE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, MIXED_CARPET_ID,
                new BlockEntityType<>(MixedCarpetBlock.Entity::new, Set.of(MIXED_CARPET)));
        FlammableBlockRegistry.getDefaultInstance().add(MIXED_CARPET, 60, 20);
    }
}
