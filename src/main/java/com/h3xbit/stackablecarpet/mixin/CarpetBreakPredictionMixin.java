package com.h3xbit.stackablecarpet.mixin;

import com.h3xbit.stackablecarpet.CarpetStacking;
import com.h3xbit.stackablecarpet.MixedCarpetBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

// Client-side destroyBlock() always predicts full removal (sets air/fluid locally the instant
// mining finishes, before the server round-trip even happens) purely for input responsiveness.
// Since the server actually keeps a shorter stack instead, that prediction was always wrong,
// and the engine's own BlockStatePredictionHandler was visibly correcting it every single break
// - the "flashing" is that correction. Predicting the same shortened state here (same raycast the
// outline and the server-side break already use) means the prediction is usually already right,
// so there's nothing left to visibly correct.
@Mixin(MultiPlayerGameMode.class)
public class CarpetBreakPredictionMixin {

    @Redirect(method = "destroyBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean stackable_carpet$predictLayerRemoval(Level level, BlockPos pos, BlockState newState, int flags) {
        BlockState oldState = level.getBlockState(pos);
        if (CarpetStacking.isStack(oldState)) {
            int layers = oldState.getValue(CarpetStacking.LAYERS);
            if (layers > 1) {
                int targetLayer = layers;
                if (Minecraft.getInstance().hitResult instanceof BlockHitResult hitResult && hitResult.getBlockPos().equals(pos)) {
                    double localY = hitResult.getLocation().y - pos.getY();
                    targetLayer = Math.max(1, Math.min(layers, (int) Math.ceil(localY * 16.0)));
                }
                int remaining = targetLayer - 1;
                if (remaining > 0 && oldState.getBlock() instanceof MixedCarpetBlock) {
                    List<Block> carpets = CarpetStacking.layersAt(level, pos, oldState);
                    if (carpets.size() >= remaining) {
                        return level.setBlock(pos, CarpetStacking.stateFor(carpets.subList(0, remaining)), flags);
                    }
                }
                if (remaining > 0) {
                    return level.setBlock(pos, oldState.setValue(CarpetStacking.LAYERS, remaining), flags);
                }
            }
        }
        return level.setBlock(pos, newState, flags);
    }
}
