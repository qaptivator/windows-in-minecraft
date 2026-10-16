package dev.windowscraft.client.window;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.windowscraft.client.backend.WindowBackend;
import dev.windowscraft.client.render.WindowRenderer;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Owns every placed window and handles pointer interaction with them.
 *
 * Input model:
 * - Left click on a window: press and release are forwarded to the backend at the hit pixel.
 * - Right drag: move the window, keeping the grab point under the crosshair.
 * - Ctrl + right drag: resize from the bottom-right corner, keeping the top-left fixed.
 * - X: close the hovered window.
 *
 * While a window is hovered or grabbed, the crosshair pick is overridden so block
 * interaction does not hit blocks behind the window (see MinecraftMixin).
 */
public final class WindowManager {
	private static final WindowManager INSTANCE = new WindowManager();

	/** Distance in blocks from the eye to a newly spawned window. */
	private static final double SPAWN_DISTANCE = 3.0;
	private static final int MIN_SIZE = 64;
	private static final int MAX_SIZE = 2048;

	private final List<WindowInstance> windows = new ArrayList<>();

	private @Nullable WindowInstance.Hit hovered;
	private @Nullable Grab grab;
	private @Nullable WindowInstance pressedWindow;
	private int pressedButton = -1;
	private int lastPressedX;
	private int lastPressedY;
	private boolean overridePick;

	private WindowManager() {
	}

	public static WindowManager get() {
		return INSTANCE;
	}

	public List<WindowInstance> windows() {
		return windows;
	}

	/** Places a new window in front of the camera, facing the viewer. */
	public WindowInstance spawn(WindowBackend backend, Camera camera) {
		Vec3 eye = camera.position();
		Vec3 look = new Vec3(camera.forwardVector());
		Vec3 up = new Vec3(camera.upVector());

		Vec3 center = eye.add(look.scale(SPAWN_DISTANCE));
		WindowInstance window = new WindowInstance(backend, center, look.reverse(), up.reverse());
		windows.add(window);
		return window;
	}

	/** Runs during extraction. Refreshes sizes and uploads new frames. */
	public void refresh() {
		for (WindowInstance window : windows) {
			window.refresh();
			if (window.backend().renderFrame(window.texture().pixels())) {
				window.texture().upload();
			}
		}
	}

	/** Runs during the level's collect-submits phase. Submits one quad pair per window. */
	public void submit(LevelRenderContext ctx) {
		if (windows.isEmpty()) {
			return;
		}

		Vec3 cameraPos = ctx.levelState().cameraRenderState.pos;
		PoseStack poseStack = ctx.poseStack();

		for (WindowInstance window : windows) {
			Vec3 rel = window.origin().subtract(cameraPos);
			poseStack.pushPose();
			poseStack.translate(rel.x, rel.y, rel.z);
			WindowRenderer.submit(ctx.submitNodeCollector(), poseStack, window.textureId(), Vec3.ZERO, window.spanX(), window.spanY());
			poseStack.popPose();
		}
	}

	/**
	 * Called from the crosshair pick. Updates hover, moves active grabs and remembers
	 * whether the pick should be overridden.
	 */
	public void updatePointer(Minecraft minecraft, @Nullable HitResult vanillaHit) {
		Camera camera = minecraft.gameRenderer.getMainCamera();
		Vec3 eye = camera.position();
		Vec3 look = new Vec3(camera.forwardVector());

		if (grab != null) {
			updateGrab(eye, look);
		}

		hovered = null;
		if (grab == null && minecraft.screen == null && minecraft.player != null) {
			hovered = findHover(eye, look, vanillaHit, minecraft.player.blockInteractionRange());
		}

		overridePick = hovered != null || grab != null;

		if (hovered != null) {
			WindowInstance.Hit hit = hovered;
			hit.window().backend().pointerMotion((int) Math.floor(hit.x()), (int) Math.floor(hit.y()));
			if (pressedWindow == hit.window()) {
				lastPressedX = (int) Math.floor(hit.x());
				lastPressedY = (int) Math.floor(hit.y());
			}
		}
	}

	public boolean overridePick() {
		return overridePick;
	}

	/**
	 * Mouse button input. Returns true when the event was consumed and should not reach
	 * vanilla handling.
	 */
	public boolean onButton(int button, boolean pressed) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen != null) {
			return false;
		}

		if (pressed) {
			if (hovered == null) {
				return false;
			}

			WindowInstance window = hovered.window();
			int x = (int) Math.floor(hovered.x());
			int y = (int) Math.floor(hovered.y());

			if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
				boolean resize = InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL);
				grab = resize ? Grab.resize(window, button, hovered.x(), hovered.y()) : Grab.move(window, button, window.pivot().subtract(hovered.world()));
				return true;
			}

			pressedWindow = window;
			pressedButton = button;
			lastPressedX = x;
			lastPressedY = y;
			window.backend().pointerButton(x, y, button, true);
			return true;
		}

		if (grab != null && grab.button == button) {
			grab = null;
			return true;
		}

		if (pressedWindow != null && pressedButton == button) {
			pressedWindow.backend().pointerButton(lastPressedX, lastPressedY, button, false);
			pressedWindow = null;
			pressedButton = -1;
			return true;
		}

		return false;
	}

	/** Closes the window under the crosshair, if any. */
	public void closeHovered() {
		if (hovered == null) {
			return;
		}
		remove(hovered.window());
		hovered = null;
	}

	/** Closes every window. Called on disconnect and client stop. */
	public void clear() {
		for (WindowInstance window : windows) {
			window.close();
		}
		windows.clear();
		hovered = null;
		grab = null;
		pressedWindow = null;
		pressedButton = -1;
		overridePick = false;
	}

	private void remove(WindowInstance window) {
		windows.remove(window);
		if (grab != null && grab.window == window) {
			grab = null;
		}
		if (pressedWindow == window) {
			pressedWindow = null;
			pressedButton = -1;
		}
		window.close();
	}

	private @Nullable WindowInstance.Hit findHover(Vec3 eye, Vec3 look, @Nullable HitResult vanillaHit, double reach) {
		WindowInstance.Hit best = null;
		for (WindowInstance window : windows) {
			WindowInstance.Hit hit = window.intersect(eye, look);
			if (hit == null || hit.distance() > reach) {
				continue;
			}
			if (best == null || hit.distance() < best.distance()) {
				best = hit;
			}
		}

		if (best == null) {
			return null;
		}

		// A block closer than the window wins, so the window is not hovered through walls.
		if (vanillaHit != null && vanillaHit.getType() != HitResult.Type.MISS) {
			double blockDistance = vanillaHit.getLocation().distanceTo(eye);
			if (blockDistance < best.distance()) {
				return null;
			}
		}

		return best;
	}

	private void updateGrab(Vec3 eye, Vec3 look) {
		WindowInstance window = grab.window;
		if (!windows.contains(window)) {
			grab = null;
			return;
		}

		Vec3 hit = window.planeHit(eye, look);
		if (hit == null) {
			return;
		}

		if (grab.type == Grab.Type.MOVE) {
			window.setPivot(hit.add(grab.offset));
			return;
		}

		double[] local = window.localPixels(hit);
		int newWidth = clamp((int) Math.round(grab.startWidth + (local[0] - grab.startX)));
		int newHeight = clamp((int) Math.round(grab.startHeight + (local[1] - grab.startY)));
		if (newWidth != window.width() || newHeight != window.height()) {
			window.resizeKeepingOrigin(newWidth, newHeight);
		}
	}

	private static int clamp(int value) {
		return Math.max(MIN_SIZE, Math.min(MAX_SIZE, value));
	}

	/** Crosshair override used by MinecraftMixin. Stops block picking behind a window. */
	public static BlockHitResult missResult(Vec3 eye) {
		return BlockHitResult.miss(eye, Direction.DOWN, BlockPos.containing(eye));
	}

	private static final class Grab {
		enum Type {
			MOVE, RESIZE
		}

		final Type type;
		final WindowInstance window;
		final int button;
		final Vec3 offset;
		final double startX;
		final double startY;
		final int startWidth;
		final int startHeight;

		private Grab(Type type, WindowInstance window, int button, Vec3 offset, double startX, double startY) {
			this.type = type;
			this.window = window;
			this.button = button;
			this.offset = offset;
			this.startX = startX;
			this.startY = startY;
			this.startWidth = window.width();
			this.startHeight = window.height();
		}

		static Grab move(WindowInstance window, int button, Vec3 offset) {
			return new Grab(Type.MOVE, window, button, offset, 0.0, 0.0);
		}

		static Grab resize(WindowInstance window, int button, double x, double y) {
			return new Grab(Type.RESIZE, window, button, Vec3.ZERO, x, y);
		}
	}
}
