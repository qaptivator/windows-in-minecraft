package dev.windowscraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.windowscraft.WindowsCraft;
import dev.windowscraft.client.capture.NativeCapture;
import dev.windowscraft.client.gui.AppLauncherScreen;
import dev.windowscraft.client.window.WindowManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client entrypoint. Wires keybinds, render events and cleanup.
 *
 * V opens the launcher. X closes the window under the crosshair.
 */
public final class WindowsCraftClient implements ClientModInitializer {
	private static KeyMapping launcherKey;
	private static KeyMapping closeKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(WindowsCraft.MOD_ID, "controls"));
		launcherKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + WindowsCraft.MOD_ID + ".launcher", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, category));
		closeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + WindowsCraft.MOD_ID + ".close", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, category));

		ClientTickEvents.END_CLIENT_TICK.register(WindowsCraftClient::onClientTick);
		LevelRenderEvents.END_EXTRACTION.register((context) -> WindowManager.get().refresh());
		LevelRenderEvents.COLLECT_SUBMITS.register((context) -> WindowManager.get().submit(context));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, minecraft) -> WindowManager.get().clear());
		ClientLifecycleEvents.CLIENT_STOPPING.register((client) -> {
			// Sessions close first, then the shared D3D device is released.
			WindowManager.get().clear();
			NativeCapture.shutdown();
		});
	}

	private static void onClientTick(Minecraft minecraft) {
		while (launcherKey.consumeClick()) {
			if (minecraft.player != null && minecraft.screen == null) {
				minecraft.setScreen(new AppLauncherScreen());
			}
		}

		while (closeKey.consumeClick()) {
			if (minecraft.player != null && minecraft.screen == null) {
				WindowManager.get().closeHovered();
			}
		}
	}
}
