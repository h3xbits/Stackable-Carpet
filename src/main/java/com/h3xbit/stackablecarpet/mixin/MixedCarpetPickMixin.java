package com.h3xbit.stackablecarpet.mixin;

import com.h3xbit.stackablecarpet.StackableCarpet;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

// Middle-click on a mixed stack picks the carpet in the layer the player is aiming at. Vanilla asks the block for its
// pick item without saying who's picking, so this is done here where the player is known.
@Mixin(ServerGamePacketListenerImpl.class)
public class MixedCarpetPickMixin {

    @Shadow
    public ServerPlayer player;

    @WrapOperation(method = "handlePickItemFromBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getCloneItemStack(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack pickAimedLayer(BlockState state, LevelReader level, BlockPos pos, boolean includeData, Operation<ItemStack> original) {
        if (!(state.getBlock() instanceof StackableCarpet.MixedCarpet)) {
            return original.call(state, level, pos, includeData);
        }

        List<Block> layers = StackableCarpet.layersAt(level, pos, state);
        if (layers.isEmpty()) {
            return ItemStack.EMPTY;
        }

        int aimedLayer = StackableCarpet.targetLayer(this.player.pick(this.player.blockInteractionRange(), 1.0F, false), pos, layers.size());
        return new ItemStack(layers.get(aimedLayer - 1));
    }
}
