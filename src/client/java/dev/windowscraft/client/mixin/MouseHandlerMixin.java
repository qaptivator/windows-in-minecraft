package dev.windowscraft.client.mixin;

import dev.windowscraft.client.window.WindowManager;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sends mouse button presses over a window to the window manager. When the manager
 * consumes the event, vanilla keybinds (attack, use, pick) are not set.
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

	@Inject(method = "onButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;set(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)V"), cancellable = true)
	public void windowscraft$onButton(long windowHandle, MouseButtonInfo buttonInfo, int action, CallbackInfo info) {
		if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) {
			return;
		}
		if (WindowManager.get().onButton(buttonInfo.button(), action == GLFW.GLFW_PRESS)) {
			info.cancel();
		}
	}

}
