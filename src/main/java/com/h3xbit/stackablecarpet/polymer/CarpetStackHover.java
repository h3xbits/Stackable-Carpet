package com.h3xbit.stackablecarpet.polymer;

import com.h3xbit.stackablecarpet.StackableCarpet;
import eu.pb4.polydex.api.v1.hover.HoverDisplayBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

// Polydex (the server's "what am I looking at" display) names a stack with how many carpets are in it:
// "Carpet Stack (5 carpets)". The name falls back to plain English for players without the mod's language file.
public final class CarpetStackHover {
    private CarpetStackHover() {
    }

    public static void register() {
        HoverDisplayBuilder.register(StackableCarpet.CARPET_STACK, builder -> {
            BlockState state = builder.getTarget().blockState();
            if (state != null && state.hasProperty(StackableCarpet.LAYERS)) {
                builder.setComponent(HoverDisplayBuilder.NAME, Component.translatableWithFallback("block.stackable_carpet.mixed_carpet", "Carpet Stack")
                        .append(" (" + state.getValue(StackableCarpet.LAYERS) + " carpets)"));
            }
        });
    }
}
