package com.h3xbit.stackablecarpet.polymer;

import com.h3xbit.stackablecarpet.StackableCarpet;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

// Players on plain Minecraft (no mods) can join and see every stack, the way Realistic Fire and Uninvited do it:
// single carpets are vanilla's own blocks anyway, and a carpet stack is sent as snow layers - about as high, so
// walking over it feels right - with its carpets drawn over the snow as block displays. Players with the mod get the
// same, so everyone sees the same thing. Nothing needs a resource pack.
public final class StackableCarpetPolymer {
    // How far the carpets are drawn out past the snow under them, so the snow never shows through
    private static final float EDGE = 1 / 256F;

    private StackableCarpetPolymer() {
    }

    public static void register() {
        // Polydex is optional: only touch its classes when it's there
        if (FabricLoader.getInstance().isModLoaded("polydex")) {
            CarpetStackHover.register();
        }
        PolymerBlock.registerOverlay(StackableCarpet.CARPET_STACK, new PolymerBlock() {
            // Two carpet layers to a snow layer, rounded down so the snow stays under the carpets
            @Override
            public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
                return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, state.getValue(StackableCarpet.LAYERS) / 2);
            }

            // The server walks players over the snow too, so it agrees with their game on where they stand
            @Override
            public boolean overridePlayerCollisionsWithPolymer(BlockGetter level, BlockPos pos, BlockState state, ServerPlayer player) {
                return true;
            }

            // Breaking it sounds like and throws bits of the carpet on top, not snow
            @Override
            public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
                return topCarpet(state).defaultBlockState();
            }
        });
        BlockWithElementHolder.registerOverlay(StackableCarpet.CARPET_STACK, new BlockWithElementHolder() {
            @Override
            public ElementHolder createElementHolder(ServerLevel level, BlockPos pos, BlockState initialBlockState) {
                return new StackDisplay(level, pos.immutable());
            }
        });
        // The layers stay on the server - players only get the displays
        PolymerBlockUtils.registerBlockEntity(StackableCarpet.CARPET_STACK_ENTITY);
    }

    // The stack at pos changed its layers
    public static void showLayers(ServerLevel level, BlockPos pos) {
        BlockBoundAttachment attachment = BlockBoundAttachment.get(level, pos);
        if (attachment != null && attachment.holder() instanceof StackDisplay display) {
            display.update();
        }
    }

    private static Block topCarpet(BlockState state) {
        return StackableCarpet.layerCarpets().get(state.getValue(StackableCarpet.CarpetStack.TOP));
    }

    // A stack's carpets over its snow: one block display per run of the same carpet, its full block squashed to the
    // run's height. Kept up to date when the layers change rather than every tick, there can be a lot of stacks.
    private static final class StackDisplay extends ElementHolder {
        private final ServerLevel level;
        private final BlockPos pos;
        private final List<BlockDisplayElement> runs = new ArrayList<>();
        private List<Block> shown = List.of();
        private boolean lifted = false;

        StackDisplay(ServerLevel level, BlockPos pos) {
            this.level = level;
            this.pos = pos;
        }

        // The layers are read only once someone looks: when a chunk loads, its stacks get their block entities after this
        @Override
        public boolean startWatching(ServerGamePacketListenerImpl player) {
            update();
            return super.startWatching(player);
        }

        @Override
        public void notifyUpdate(HolderAttachment.UpdateType updateType) {
            if (updateType == BlockBoundAttachment.BLOCK_STATE_UPDATE) {
                update();
            }
        }

        void update() {
            // While the block changes, the attachment already has the new state and the level not yet
            BlockState state = getAttachment() instanceof BlockBoundAttachment attachment ? attachment.getBlockState() : level.getBlockState(pos);
            if (!(state.getBlock() instanceof StackableCarpet.CarpetStack)) {
                return;
            }

            List<Block> layers = StackableCarpet.layersAt(level, pos, state);
            // Entities are lit by the light where they are, and there's none inside a full block: a full stack is
            // shown from the spot above it, lit as the top of a full block would be
            boolean lift = state.getValue(StackableCarpet.LAYERS) == StackableCarpet.MAX_LAYERS;
            if (layers.equals(shown) && lift == lifted) {
                return;
            }
            shown = List.copyOf(layers);
            lifted = lift;

            int run = 0;
            for (int bottom = 0; bottom < layers.size(); run++) {
                Block carpet = layers.get(bottom);
                int top = bottom + 1;
                while (top < layers.size() && layers.get(top) == carpet) {
                    top++;
                }

                // Only the outside of the whole stack sticks out past the snow - runs meet exactly
                float from = bottom / 16F - (bottom == 0 ? EDGE : 0);
                float to = top / 16F + (top == layers.size() ? EDGE : 0);

                boolean isNew = run == runs.size();
                BlockDisplayElement element = isNew ? new BlockDisplayElement() : runs.get(run);
                element.setBlockState(StackableCarpet.fullBlockFor(carpet).defaultBlockState());
                element.setOffset(new Vec3(0, lift ? 1 : 0, 0));
                // From the middle of the block (or the one above), where the holder is
                element.setTranslation(new Vector3f(-0.5F - EDGE, from - 0.5F - (lift ? 1 : 0), -0.5F - EDGE));
                element.setScale(new Vector3f(1 + 2 * EDGE, to - from, 1 + 2 * EDGE));

                if (isNew) {
                    runs.add(element);
                    addElement(element);
                } else {
                    // Sends what changed to everyone watching
                    element.tick();
                }
                bottom = top;
            }

            while (runs.size() > run) {
                removeElement(runs.removeLast());
            }
        }
    }
}
