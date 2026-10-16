package dev.windowscraft.client.backend;

import java.nio.ByteBuffer;

/**
 * Source of pixels and input target for one placed window.
 *
 * Everything the world-side code needs is behind this interface. A native Windows
 * capture backend implements the same methods later, so the window manager,
 * renderer and input code do not change.
 *
 * All methods are called on the Minecraft render/client thread.
 */
public interface WindowBackend extends AutoCloseable {

	/** Human-readable name, shown in the window title and logs. */
	String title();

	/**
	 * Called once per frame during extraction, before the window reads its size.
	 * Backends that receive frames asynchronously poll here.
	 */
	default void update() {
	}

	/** Current surface size in pixels. */
	int width();

	int height();

	/**
	 * Requests a new surface size in pixels. Backends may apply it later; the caller
	 * re-reads width() and height() on the next refresh.
	 */
	void resize(int width, int height);

	/** Pointer position over the surface, in surface pixels, from the top-left corner. */
	void pointerMotion(int x, int y);

	/**
	 * Mouse button press or release over the surface.
	 *
	 * @param button GLFW button index: 0 left, 1 right, 2 middle
	 */
	void pointerButton(int x, int y, int button, boolean pressed);

	/**
	 * Writes the newest frame as RGBA8 (bytes R, G, B, A per pixel) into dst.
	 * dst holds exactly width() * height() * 4 bytes.
	 *
	 * @return true when dst was written, false when there is no new frame
	 */
	boolean renderFrame(ByteBuffer dst);

	@Override
	void close();
}
