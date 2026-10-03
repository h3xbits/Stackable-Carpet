package com.h3xbit.stackablecarpet;

import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.MossyCarpetBlock;
import net.minecraft.world.level.block.WoolCarpetBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

// The stacking itself lives in the mixins - this holds what more than one of them needs: the layers
// property, the mixed carpet, and looking up what a stack is made of.
public class StackableCarpet implements ModInitializer {
    public static final String MOD_ID = "stackable_carpet";

    // A full block - only mixed stacks get this high, the 16th layer of a single carpet turns it into its full block
    public static final int MAX_LAYERS = 16;
    public static final int MAX_SINGLE_COLOR_LAYERS = MAX_LAYERS - 1;
    public static final IntegerProperty LAYERS = IntegerProperty.create("layers", 1, MAX_LAYERS);

    private static final VoxelShape[] SHAPES = new VoxelShape[MAX_LAYERS + 1];

    static {
        for (int layers = 1; layers <= MAX_LAYERS; layers++) {
            SHAPES[layers] = Block.column(16.0, 0.0, layers);
        }
    }

    public static final Identifier MIXED_CARPET_ID = Identifier.fromNamespaceAndPath(MOD_ID, "mixed_carpet");
    // Set in onInitialize() - the mixins load this class while vanilla's own blocks are still being created
    public static Block MIXED_CARPET;
    public static BlockEntityType<MixedCarpetEntity> MIXED_CARPET_ENTITY;

    @Override
    public void onInitialize() {
        MIXED_CARPET = Registry.register(BuiltInRegistries.BLOCK, MIXED_CARPET_ID,
                new MixedCarpet(BlockBehaviour.Properties.ofFullCopy(Blocks.CARPET.pick(DyeColor.WHITE))
                        .setId(ResourceKey.create(Registries.BLOCK, MIXED_CARPET_ID))));
        MIXED_CARPET_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, MIXED_CARPET_ID,
                new BlockEntityType<>(MixedCarpetEntity::new, Set.of(MIXED_CARPET)));
        FlammableBlockRegistry.getDefaultInstance().add(MIXED_CARPET, 60, 20);
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
        return fullBlockFor(block) != null;
    }

    // What 16 layers of one carpet turn into - null for carpets that can't be layers
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

    // A carpet lying on the ground (pale moss carpet only with its base, not just hanging off a wall) or a mixed stack
    public static boolean isStack(BlockState state) {
        if (state.getBlock() instanceof MossyCarpetBlock) {
            return state.getValue(MossyCarpetBlock.BASE);
        }
        return state.getBlock() instanceof CarpetBlock;
    }

    // Layers bottom first - empty for anything that isn't a stack of layer carpets
    public static List<Block> layersAt(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof MixedCarpet) {
            if (level.getBlockEntity(pos) instanceof MixedCarpetEntity entity) {
                return entity.layers.subList(0, Math.min(entity.layers.size(), state.getValue(LAYERS)));
            }
            return List.of();
        }
        if (isStack(state) && isLayerCarpet(state.getBlock())) {
            return Collections.nCopies(state.getValue(LAYERS), state.getBlock());
        }
        return List.of();
    }

    // One carpet all the way up is a plain carpet stack, anything else a mixed one
    public static BlockState stateFor(List<Block> layers) {
        Block first = layers.getFirst();
        Block block = layers.stream().allMatch(layer -> layer == first) ? first : MIXED_CARPET;
        return block.defaultBlockState().setValue(LAYERS, layers.size());
    }

    public static void place(Level level, BlockPos pos, List<Block> layers) {
        level.setBlock(pos, stateFor(layers), Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof MixedCarpetEntity entity) {
            entity.setLayers(layers);
        }
    }

    // The layer a hit lands in - the top one when the hit isn't on this block
    public static int targetLayer(HitResult hit, BlockPos pos, int layers) {
        if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(pos)) {
            double localY = blockHit.getLocation().y - pos.getY();
            return Math.max(1, Math.min(layers, (int) Math.ceil(localY * 16.0)));
        }
        return layers;
    }

    // Who can go on top of a stack: a layer carpet onto a stack of layer carpets, otherwise only the same carpet
    public static boolean canStackOnto(BlockState state, BlockPlaceContext context) {
        if (!(context.getItemInHand().getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        Block held = blockItem.getBlock();
        int layers = state.getValue(LAYERS);
        if (isLayerCarpet(state.getBlock()) || state.getBlock() instanceof MixedCarpet) {
            return isLayerCarpet(held) && layers < MAX_LAYERS;
        }
        return held == state.getBlock() && layers < MAX_SINGLE_COLOR_LAYERS;
    }

    // Only here because a block needs its own class to have a block entity - the mixins give it everything else
    public static class MixedCarpet extends CarpetBlock implements EntityBlock {
        public MixedCarpet(Properties properties) {
            super(properties);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new MixedCarpetEntity(pos, state);
        }
    }

    // Which carpet each layer of a mixed stack is, bottom first, saved with the world and sent to clients
    public static class MixedCarpetEntity extends BlockEntity {
        private static final Codec<List<Block>> LAYERS_CODEC = BuiltInRegistries.BLOCK.byNameCodec().listOf();

        public List<Block> layers = List.of();

        public MixedCarpetEntity(BlockPos pos, BlockState state) {
            super(MIXED_CARPET_ENTITY, pos, state);
        }

        public void setLayers(List<Block> layers) {
            this.layers = List.copyOf(layers);
            setChanged();
            if (level != null) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
            }
        }

        @Override
        protected void saveAdditional(ValueOutput output) {
            super.saveAdditional(output);
            output.store("layers", LAYERS_CODEC, layers);
        }

        @Override
        protected void loadAdditional(ValueInput input) {
            super.loadAdditional(input);
            layers = List.copyOf(input.read("layers", LAYERS_CODEC).orElse(List.of()));
            // The chunk mesh reads the layers when it's built, so rebuild it once the server's copy arrives
            if (level != null && level.isClientSide()) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
            }
        }

        @Override
        public Packet<ClientGamePacketListener> getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }

        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            return saveCustomOnly(registries);
        }

        @Override
        public Object getRenderData() {
            return layers;
        }
    }
}
