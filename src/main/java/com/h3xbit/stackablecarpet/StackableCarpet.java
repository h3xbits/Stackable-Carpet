package com.h3xbit.stackablecarpet;

import com.h3xbit.stackablecarpet.polymer.StackableCarpetPolymer;
import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.MossyCarpetBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.WoolCarpetBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// What CarpetStackMixin, PaleMossCarpetMixin and the rest of the mod share: the carpet stack block and what a stack
// is made of. A single carpet stays vanilla's own block, untouched - every stack of 2 or more layers, one carpet or
// mixed, is a carpet stack, its layers kept in its block entity. That way plain Minecraft can join: Polymer shows it
// the stack as snow layers with the carpets drawn over them (see StackableCarpetPolymer).
public class StackableCarpet implements ModInitializer {
    public static final String MOD_ID = "stackable_carpet";

    // A full block - only mixed stacks get this high, the 16th layer of a single carpet turns it into its full block
    public static final int MAX_LAYERS = 16;
    public static final int MAX_SINGLE_COLOR_LAYERS = MAX_LAYERS - 1;
    // A stack has at least 2 layers - one layer is the carpet itself
    public static final IntegerProperty LAYERS = IntegerProperty.create("layers", 2, MAX_LAYERS);

    private static final VoxelShape[] SHAPES = new VoxelShape[MAX_LAYERS + 1];

    static {
        for (int layers = 1; layers <= MAX_LAYERS; layers++) {
            SHAPES[layers] = Block.column(16.0, 0.0, layers);
        }
    }

    // Still called mixed_carpet, its name from when it only held mixed stacks, so those still load in older worlds
    public static final Identifier CARPET_STACK_ID = Identifier.fromNamespaceAndPath(MOD_ID, "mixed_carpet");
    public static Block CARPET_STACK;
    public static BlockEntityType<CarpetStackEntity> CARPET_STACK_ENTITY;
    // The layers about to go at a spot: a stack being placed (stackOnto() to setPlacedBy()),
    // or the layers under the aimed one of a stack being broken (playerWillDestroy() to destroy())
    public static final ThreadLocal<List<Block>> PENDING_LAYERS = new ThreadLocal<>();

    private static List<Block> layerCarpets;

    @Override
    public void onInitialize() {
        StackableCarpetConfig.load();
        StackableCarpetConfig.registerCommand();

        CARPET_STACK = Registry.register(BuiltInRegistries.BLOCK, CARPET_STACK_ID,
                new CarpetStack(BlockBehaviour.Properties.ofFullCopy(Blocks.CARPET.pick(DyeColor.WHITE))
                        .mapColor(state -> layerCarpets().get(state.getValue(CarpetStack.TOP)).defaultMapColor())
                        .setId(ResourceKey.create(Registries.BLOCK, CARPET_STACK_ID))));
        CARPET_STACK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, CARPET_STACK_ID,
                new BlockEntityType<>(CarpetStackEntity::new, Set.of(CARPET_STACK)));
        FlammableBlockRegistry.getDefaultInstance().add(CARPET_STACK, 60, 20);

        StackableCarpetPolymer.register();
    }

    public static VoxelShape shapeFor(int layers) {
        return SHAPES[layers];
    }

    // The 16 wool carpets, moss carpet and pale moss carpet - the carpets that stack
    public static List<Block> layerCarpets() {
        if (layerCarpets == null) {
            List<Block> carpets = new ArrayList<>();
            for (DyeColor color : DyeColor.values()) {
                carpets.add(Blocks.CARPET.pick(color));
            }
            carpets.add(Blocks.MOSS_CARPET);
            carpets.add(Blocks.PALE_MOSS_CARPET);
            layerCarpets = List.copyOf(carpets);
        }
        return layerCarpets;
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

    public static boolean holdsLayerCarpet(BlockPlaceContext context) {
        return context.getItemInHand().getItem() instanceof BlockItem heldItem && isLayerCarpet(heldItem.getBlock());
    }

    // A layer carpet lying on the ground (pale moss carpet only with its base, not just hanging off a wall) or a stack
    public static boolean isStack(BlockState state) {
        if (state.getBlock() instanceof CarpetStack) {
            return true;
        }
        if (state.getBlock() instanceof MossyCarpetBlock) {
            return state.getValue(MossyCarpetBlock.BASE);
        }
        return isLayerCarpet(state.getBlock());
    }

    // Layers bottom first - empty for anything that isn't a carpet or a stack
    public static List<Block> layersAt(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof CarpetStack) {
            if (level.getBlockEntity(pos) instanceof CarpetStackEntity entity) {
                return entity.layers.subList(0, Math.min(entity.layers.size(), state.getValue(LAYERS)));
            }
            return List.of();
        }
        if (isStack(state)) {
            return List.of(state.getBlock());
        }
        return List.of();
    }

    // One layer is just that carpet, 16 of one carpet its full block (unless the config turned that off), anything else a carpet stack
    public static BlockState stateFor(List<Block> layers) {
        Block first = layers.getFirst();

        if (layers.size() == 1) {
            return first.defaultBlockState();
        }
        if (StackableCarpetConfig.fullBlockConversion && layers.size() == MAX_LAYERS && layers.stream().allMatch(layer -> layer == first)) {
            return fullBlockFor(first).defaultBlockState();
        }
        return CARPET_STACK.defaultBlockState()
                .setValue(LAYERS, layers.size())
                .setValue(CarpetStack.TOP, layerCarpets().indexOf(layers.getLast()));
    }

    public static void place(LevelAccessor level, BlockPos pos, List<Block> layers) {
        BlockState state = stateFor(layers);
        // A single pale moss carpet gets the moss on its sides a freshly placed one would
        if (state.is(Blocks.PALE_MOSS_CARPET)) {
            state = Block.updateFromNeighbourShapes(state, level, pos);
        }
        level.setBlock(pos, state, Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof CarpetStackEntity entity) {
            entity.setLayers(layers);
        }
    }

    // What placing this carpet onto the carpet or stack at the clicked spot makes - null when there's none there.
    // A new stack gets its layers in setPlacedBy().
    public static @Nullable BlockState stackOnto(Block carpet, BlockPlaceContext context) {
        BlockState existing = context.getLevel().getBlockState(context.getClickedPos());
        List<Block> layersBelow = layersAt(context.getLevel(), context.getClickedPos(), existing);

        if (!isLayerCarpet(carpet) || layersBelow.isEmpty() || layersBelow.size() >= MAX_LAYERS) {
            return null;
        }

        List<Block> layers = new ArrayList<>(layersBelow);
        layers.add(carpet);

        BlockState state = stateFor(layers);
        if (state.getBlock() instanceof CarpetStack) {
            PENDING_LAYERS.set(layers);
        }
        return state;
    }

    // The layer a hit lands in - the top one when the hit isn't on this block
    public static int targetLayer(HitResult hit, BlockPos pos, int layers) {
        if (hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(pos)) {
            double localY = blockHit.getLocation().y - pos.getY();
            return Math.max(1, Math.min(layers, (int) Math.ceil(localY * 16.0)));
        }
        return layers;
    }

    // Every stack of 2 or more layers. It lies on the ground like a carpet: any layer carpet goes on top, and breaking
    // takes the aimed layer and everything above it.
    public static class CarpetStack extends Block implements EntityBlock {
        // Which carpet is on top, as its place in layerCarpets() - it's what the stack shows as on maps
        public static final IntegerProperty TOP = IntegerProperty.create("top", 0, DyeColor.values().length + 1);

        public CarpetStack(Properties properties) {
            super(properties);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(LAYERS, TOP);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new CarpetStackEntity(pos, state);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return shapeFor(state.getValue(LAYERS));
        }

        // Sounds like the carpet on top - moss like moss
        @Override
        protected SoundType getSoundType(BlockState state) {
            return layerCarpets().get(state.getValue(TOP)).defaultBlockState().getSoundType();
        }

        @Override
        protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
            return !level.isEmptyBlock(pos.below());
        }

        @Override
        protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction directionToNeighbour, BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
            if (!state.canSurvive(level, pos)) {
                return Blocks.AIR.defaultBlockState();
            }
            return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
        }

        // Any layer carpet goes on top until it's a full block
        @Override
        protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
            return holdsLayerCarpet(context) && state.getValue(LAYERS) < MAX_LAYERS;
        }

        // A stack that was just placed gets the layers stackOnto() worked out for it
        @Override
        public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);

            List<Block> layers = PENDING_LAYERS.get();
            PENDING_LAYERS.remove();

            if (layers != null && layers.size() == state.getValue(LAYERS) && level.getBlockEntity(pos) instanceof CarpetStackEntity entity) {
                entity.setLayers(layers);
            }
        }

        // A stack never breaks instantly in survival: a layer is left behind straight away, and an instant break lets
        // the same click carry on into it. Mined normally, vanilla waits 5 ticks before starting the next one.
        @Override
        protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
            return Math.min(super.getDestroyProgress(state, player, level, pos), 0.99F);
        }

        // Breaking takes the aimed layer and everything above it
        @Override
        public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
            List<Block> layers = layersAt(level, pos, state);
            int aimedLayer = layers.isEmpty() ? 1 : targetLayer(player.pick(player.blockInteractionRange(), 1.0F, false), pos, layers.size());

            // The layers under the aimed one - destroy() puts them back
            PENDING_LAYERS.set(List.copyOf(layers.subList(0, aimedLayer - 1)));

            // The block entity keeps only the broken layers, so the drops are exactly those carpets
            if (level.getBlockEntity(pos) instanceof CarpetStackEntity entity) {
                entity.layers = List.copyOf(layers.subList(aimedLayer - 1, layers.size()));
            }

            return super.playerWillDestroy(level, pos, state, player);
        }

        // The block's gone - put the layers that were under the aimed one back
        @Override
        public void destroy(LevelAccessor level, BlockPos pos, BlockState state) {
            super.destroy(level, pos, state);

            List<Block> layersUnder = PENDING_LAYERS.get();
            PENDING_LAYERS.remove();

            if (layersUnder != null && !layersUnder.isEmpty()) {
                place(level, pos, layersUnder);
            }
        }

        // One of each carpet in it
        @Override
        protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
            if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof CarpetStackEntity entity) {
                return entity.layers.stream().map(ItemStack::new).toList();
            }
            return List.of();
        }
    }

    // Which carpet each layer of a stack is, bottom first, saved with the world. It stays on the server: players are
    // shown the layers through Polymer.
    public static class CarpetStackEntity extends BlockEntity {
        private static final Codec<List<Block>> LAYERS_CODEC = BuiltInRegistries.BLOCK.byNameCodec().listOf();

        public List<Block> layers = List.of();

        public CarpetStackEntity(BlockPos pos, BlockState state) {
            super(CARPET_STACK_ENTITY, pos, state);
        }

        public void setLayers(List<Block> layers) {
            this.layers = List.copyOf(layers);
            setChanged();
            if (level instanceof ServerLevel serverLevel) {
                StackableCarpetPolymer.showLayers(serverLevel, worldPosition);
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
        }
    }
}
