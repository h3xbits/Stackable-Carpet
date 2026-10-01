package com.h3xbit.stackablecarpet.mixin;

import com.h3xbit.stackablecarpet.CarpetStacking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
    private BlockState stackable_carpet$preDestroyState;

    @Unique
    private int stackable_carpet$targetLayer;

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void stackable_carpet$captureState(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockState state = this.level.getBlockState(pos);
        this.stackable_carpet$preDestroyState = state;

        int targetLayer = 1;
        if (state.getBlock() instanceof CarpetBlock) {
            int layers = state.getValue(CarpetStacking.LAYERS);
            targetLayer = layers;
            if (this.player.pick(this.player.blockInteractionRange(), 1.0F, false) instanceof BlockHitResult hitResult
                    && hitResult.getBlockPos().equals(pos)) {
                double localY = hitResult.getLocation().y - pos.getY();
                targetLayer = Math.max(1, Math.min(layers, (int) Math.ceil(localY * 16.0)));
            }
        }
        this.stackable_carpet$targetLayer = targetLayer;
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void stackable_carpet$restoreRemainingLayers(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockState preDestroyState = this.stackable_carpet$preDestroyState;
        int targetLayer = this.stackable_carpet$targetLayer;
        this.stackable_carpet$preDestroyState = null;
        if (preDestroyState == null || !Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        if (!(preDestroyState.getBlock() instanceof CarpetBlock)) {
            return;
        }
        int layers = preDestroyState.getValue(CarpetStacking.LAYERS);
        if (layers <= 1) {
            return;
        }

        int remaining = targetLayer - 1;
        if (remaining > 0) {
            this.level.setBlock(pos, preDestroyState.setValue(CarpetStacking.LAYERS, remaining), 3);
        }

        int extraDrops = layers - targetLayer;
        if (extraDrops > 0 && !this.player.preventsBlockDrops()) {
            Block.popResource(this.level, pos, new ItemStack(preDestroyState.getBlock().asItem(), extraDrops));
        }
    }
}
