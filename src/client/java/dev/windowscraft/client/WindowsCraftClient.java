package dev.windowscraft.client;

import dev.windowscraft.WindowsCraft;
import dev.windowscraft.client.capture.NativeCapture;
import dev.windowscraft.client.capture.WindowCapture;
import dev.windowscraft.client.render.CaptureTexture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. Draws the mirrored window as a HUD quad.
 * This is a placeholder for the in-world quad, which comes next.
 */
public final class WindowsCraftClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger(WindowsCraft.MOD_ID + "/client");
	private static final String TARGET_PROPERTY = "windowscraft.target";
	private static final String DEFAULT_TARGET = "Notepad";
	private static final long RETRY_DELAY_MS = 2000L;
	private static final int DISPLAY_WIDTH = 640;
	private static final int DISPLAY_X = 10;
	private static final int DISPLAY_Y = 10;

	private static final Identifier HUD_ELEMENT = Identifier.fromNamespaceAndPath(WindowsCraft.MOD_ID, "window_quad");

	private static CaptureTexture texture;
	private static WindowCapture capture;
	private static boolean fatalError;
	private static long nextAttemptMs;

	@Override
	public void onInitializeClient() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, HUD_ELEMENT, WindowsCraftClient::extract);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (!ensureCapture()) {
			return;
		}

		capture.updateTexture(texture);

		int width = texture.width();
		int height = texture.height();
		if (width == 0 || height == 0) {
			return;
		}

		int displayHeight = DISPLAY_WIDTH * height / width;
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture.id(), DISPLAY_X, DISPLAY_Y, 0, 0, DISPLAY_WIDTH, displayHeight, width, height);
	}

	/** Lazily loads the native library and opens the target window. Retries every few seconds. */
	private static boolean ensureCapture() {
		if (capture != null) {
			return true;
		}
		if (fatalError || System.currentTimeMillis() < nextAttemptMs) {
			return false;
		}

		try {
			NativeCapture.load();
			if (!NativeCapture.init()) {
				throw new IllegalStateException("wc_init returned failure, see native logs");
			}

			String target = System.getProperty(TARGET_PROPERTY, DEFAULT_TARGET);
			capture = WindowCapture.findByTitle(target).orElse(null);
			if (capture == null) {
				LOGGER.info("No visible window title contains '{}', retrying", target);
				nextAttemptMs = System.currentTimeMillis() + RETRY_DELAY_MS;
				return false;
			}

			texture = new CaptureTexture();
			LOGGER.info("Capturing window matching '{}'", target);
			return true;
		} catch (Throwable t) {
			fatalError = true;
			LOGGER.error("WindowsCraft capture disabled", t);
			return false;
		}
	}

	private static void shutdown() {
		if (capture != null) {
			capture.close();
			capture = null;
		}
		if (texture != null) {
			texture.close();
			texture = null;
		}
		NativeCapture.shutdown();
	}
}
