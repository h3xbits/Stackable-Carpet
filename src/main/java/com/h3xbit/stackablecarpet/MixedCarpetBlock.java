package com.h3xbit.stackablecarpet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.ArrayList;
import java.util.List;

// A carpet stack with more than one kind of carpet in it. Extends CarpetBlock so the stacking mixins give it
// the same layers property, shape, outline and layer-by-layer breaking as a single-carpet stack; which
// carpet each layer is (bottom first) lives in the block entity.
public class MixedCarpetBlock extends CarpetBlock implements EntityBlock {
    public static final MapCodec<MixedCarpetBlock> CODEC = simpleCodec(MixedCarpetBlock::new);

    public MixedCarpetBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<MixedCarpetBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    // getStateForPlacement() worked out the full layer list just before this
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        List<Block> layers = CarpetStacking.PENDING_LAYERS.get();
        CarpetStacking.PENDING_LAYERS.remove();
        if (layers != null && level.getBlockEntity(pos) instanceof Entity entity) {
            entity.setLayers(layers);
        }
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = new ArrayList<>();
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof Entity entity) {
            for (Block layer : entity.layers()) {
                drops.add(new ItemStack(layer));
            }
        }
        return drops;
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        List<Block> layers = CarpetStacking.layersAt(level, pos, state);
        return layers.isEmpty() ? ItemStack.EMPTY : new ItemStack(layers.getLast());
    }

    public static class Entity extends BlockEntity {
        public static BlockEntityType<Entity> TYPE;
        private static final Codec<List<Block>> LAYERS_CODEC = BuiltInRegistries.BLOCK.byNameCodec().listOf();

        private List<Block> layers = List.of();

        public Entity(BlockPos pos, BlockState state) {
            super(TYPE, pos, state);
        }

        public List<Block> layers() {
            return layers;
        }

        public void setLayers(List<Block> layers) {
            setLayersQuietly(layers);
            setChanged();
            if (level != null) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
            }
        }

        // Without telling clients - for the server's break handling, where the block is removed right after
        public void setLayersQuietly(List<Block> layers) {
            this.layers = List.copyOf(layers);
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
