package com.h3xbit.stackablecarpet.mixin;

import com.h3xbit.stackablecarpet.CarpetStacking;
import com.h3xbit.stackablecarpet.MixedCarpetBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// destroyBlock() is vanilla's full break-and-drop path - letting it run unchanged means the
// player gets a correct single-carpet drop (loot table, tool checks, stats all untouched), we
// just re-place the stack shorter afterward instead of leaving the position empty. Both the
// pre-destroy state AND the targeted layer have to be captured at HEAD, before vanilla's own
// removeBlock() runs - raycasting for the target layer after the block is already gone means the
// ray no longer hits that position at all, which was silently defaulting every break to
// "remove just the top layer" regardless of where the player actually aimed.
@Mixin(ServerPlayerGameMode.class)
public class CarpetBreakMixin {

    @Shadow
    protected ServerLevel level;

    @Shadow
    protected ServerPlayer player;

    @Unique
    private BlockState stackable_carpet$stack;

    @Unique
    private int stackable_carpet$targetLayer;

    @Unique
    private List<Block> stackable_carpet$mixedLayers;

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void stackable_carpet$captureState(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockState state = this.level.getBlockState(pos);
        this.stackable_carpet$stack = CarpetStacking.isStack(state) ? state : null;
        this.stackable_carpet$mixedLayers = null;
        if (this.stackable_carpet$stack == null) {
            return;
        }
        int targetLayer = CarpetStacking.targetLayer(this.player.pick(this.player.blockInteractionRange(), 1.0F, false), pos, state.getValue(CarpetStacking.LAYERS));
        this.stackable_carpet$targetLayer = targetLayer;

        // Mixed stack: leave only the broken layers in the block entity, so vanilla's drops are exactly those carpets
        if (state.getBlock() instanceof MixedCarpetBlock && this.level.getBlockEntity(pos) instanceof MixedCarpetBlock.Entity entity) {
            List<Block> layers = CarpetStacking.layersAt(this.level, pos, state);
            this.stackable_carpet$mixedLayers = layers;
            entity.setLayersQuietly(layers.subList(targetLayer - 1, layers.size()));
        }
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void stackable_carpet$restoreRemainingLayers(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockState stack = this.stackable_carpet$stack;
        List<Block> mixedLayers = this.stackable_carpet$mixedLayers;
        int targetLayer = this.stackable_carpet$targetLayer;
        this.stackable_carpet$stack = null;
        this.stackable_carpet$mixedLayers = null;
        if (stack == null) {
            return;
        }
        if (!cir.getReturnValueZ()) {
            if (mixedLayers != null && this.level.getBlockEntity(pos) instanceof MixedCarpetBlock.Entity entity) {
                entity.setLayersQuietly(mixedLayers);
            }
            return;
        }
        if (mixedLayers != null) {
            if (targetLayer > 1) {
                CarpetStacking.place(this.level, pos, mixedLayers.subList(0, targetLayer - 1));
            }
            return;
        }

        if (targetLayer > 1) {
            this.level.setBlock(pos, stack.setValue(CarpetStacking.LAYERS, targetLayer - 1), 3);
        }
        int extraDrops = stack.getValue(CarpetStacking.LAYERS) - targetLayer;
        if (extraDrops > 0 && !this.player.preventsBlockDrops()) {
            Block.popResource(this.level, pos, new ItemStack(stack.getBlock().asItem(), extraDrops));
        }
    }
}
