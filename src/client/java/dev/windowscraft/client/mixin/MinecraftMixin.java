package dev.windowscraft.client.mixin;

import dev.windowscraft.client.window.WindowManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the window pointer logic after vanilla picks the block under the crosshair,
 * and overrides the pick while a window is hovered or grabbed.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

	@Inject(method = "pick", at = @At("TAIL"))
	public void windowscraft$onPick(float partialTicks, CallbackInfo info) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) {
			return;
		}

		WindowManager manager = WindowManager.get();
		manager.updatePointer(minecraft, minecraft.hitResult);

		if (manager.overridePick()) {
			Vec3 eye = minecraft.player.getEyePosition(partialTicks);
			minecraft.hitResult = WindowManager.missResult(eye);
			minecraft.crosshairPickEntity = null;
		}
	}

}
