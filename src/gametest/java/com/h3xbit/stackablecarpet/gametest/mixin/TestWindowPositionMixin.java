package com.h3xbit.stackablecarpet.gametest.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Only with -Dstackable_carpet.testWindowPos=x,y: the test window opens at that screen position and
// never takes focus when shown, so it can run on another monitor while that one is in use.
@Mixin(Window.class)
public abstract class TestWindowPositionMixin {

    @Shadow
    private int x;

    @Shadow
    private int y;

    @Shadow
    private int windowedX;

    @Shadow
    private int windowedY;

    // Vanilla creates the window already showing on the main monitor - create it hidden instead, it's shown once it's in place
    @ModifyConstant(method = "createWindow", constant = @Constant(longValue = 8224L))
    private long createHidden(long windowFlags) {
        if (testWindowPos() == null) {
            return windowFlags;
        }
        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN, "0");
        return windowFlags | SDLVideo.SDL_WINDOW_HIDDEN;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void moveAndShowWindow(CallbackInfo ci) {
        int[] pos = testWindowPos();
        if (pos == null) {
            return;
        }

        this.x = this.windowedX = pos[0];
        this.y = this.windowedY = pos[1];

        long window = ((Window) (Object) this).handle();
        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN, "0");
        SDLVideo.SDL_SetWindowPosition(window, pos[0], pos[1]);
        SDLVideo.SDL_ShowWindow(window);
    }

    @Unique
    private static int[] testWindowPos() {
        String value = System.getProperty("stackable_carpet.testWindowPos");
        if (value == null) {
            return null;
        }
        String[] parts = value.split(",");
        return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
    }
}
