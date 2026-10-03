package com.h3xbit.stackablecarpet.gametest.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    @Inject(method = "createGlfwWindow", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private static void hideAndPlaceWindow(CallbackInfoReturnable<Long> cir) {
        int[] pos = testWindowPos();
        if (pos == null) {
            return;
        }
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_POSITION_X, pos[0]);
        GLFW.glfwWindowHint(GLFW.GLFW_POSITION_Y, pos[1]);
    }

    // The constructor works out a centered spot on the primary monitor afterwards - put ours back
    @Inject(method = "<init>", at = @At("RETURN"))
    private void moveWindow(CallbackInfo ci) {
        int[] pos = testWindowPos();
        if (pos == null) {
            return;
        }
        this.x = this.windowedX = pos[0];
        this.y = this.windowedY = pos[1];
        GLFW.glfwSetWindowPos(((Window) (Object) this).handle(), pos[0], pos[1]);
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
