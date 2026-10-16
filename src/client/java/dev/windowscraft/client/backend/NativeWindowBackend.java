package dev.windowscraft.client.backend;

import dev.windowscraft.client.capture.NativeCapture;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Backend for a real Windows window, captured through windowscraft_native.dll.
 *
 * The texture size is the source frame scaled down so its long side is at most
 * MAX_LONG_SIDE. Blocks are small, so full-resolution frames would waste upload
 * bandwidth. A resize request overrides that size; the native side scales to match.
 *
 * Clicks are forwarded to the real window with NativeCapture.click, which focuses the
 * window and sends the click at the mapped position. Motion and right/middle buttons
 * are not forwarded yet.
 */
public final class NativeWindowBackend implements WindowBackend {
	private static final int MAX_LONG_SIDE = 640;
	private static final int FALLBACK_WIDTH = 640;
	private static final int FALLBACK_HEIGHT = 360;
	private static final int MIN_SIZE = 64;
	private static final int MAX_SIZE = 2048;

	private final String title;
	private final long hwnd;
	private final MemorySegment session;
	private final MemorySegment sizeSlots;

	private int sourceWidth;
	private int sourceHeight;
	private int requestedWidth;
	private int requestedHeight;
	private boolean frameReady;
	private boolean closed;

	private NativeWindowBackend(String title, long hwnd, MemorySegment session) {
		this.title = title;
		this.hwnd = hwnd;
		this.session = session;
		// Two 32-bit ints: width at offset 0, height at offset 4.
		this.sizeSlots = Arena.ofAuto().allocate(8, 4);
	}

	/**
	 * Starts capturing the window. Returns empty when capture could not start, for example
	 * when the window is minimized or protected.
	 */
	public static Optional<WindowBackend> open(String title, long hwnd) throws IOException {
		NativeCapture.ensureReady();
		MemorySegment session = NativeCapture.openWindow(MemorySegment.ofAddress(hwnd));
		if (session.address() == 0) {
			return Optional.empty();
		}
		return Optional.of(new NativeWindowBackend(title, hwnd, session));
	}

	@Override
	public String title() {
		return title;
	}

	@Override
	public void update() {
		if (closed || !NativeCapture.poll(session)) {
			return;
		}

		if (NativeCapture.getSize(session, sizeSlots, sizeSlots.asSlice(4))) {
			int w = sizeSlots.get(ValueLayout.JAVA_INT, 0);
			int h = sizeSlots.get(ValueLayout.JAVA_INT, 4);
			if (w > 0 && h > 0) {
				sourceWidth = w;
				sourceHeight = h;
				frameReady = true;
			}
		}
	}

	@Override
	public int width() {
		if (requestedWidth > 0) {
			return requestedWidth;
		}
		if (sourceWidth <= 0) {
			return FALLBACK_WIDTH;
		}
		return fit(sourceWidth, sourceHeight)[0];
	}

	@Override
	public int height() {
		if (requestedHeight > 0) {
			return requestedHeight;
		}
		if (sourceHeight <= 0) {
			return FALLBACK_HEIGHT;
		}
		return fit(sourceWidth, sourceHeight)[1];
	}

	@Override
	public void resize(int newWidth, int newHeight) {
		requestedWidth = clamp(newWidth);
		requestedHeight = clamp(newHeight);
		frameReady = true;
	}

	@Override
	public void pointerMotion(int x, int y) {
		// Motion is not forwarded yet. The real window tracks the system cursor.
	}

	@Override
	public void pointerButton(int x, int y, int button, boolean pressed) {
		if (closed || !pressed || button != 0 || sourceWidth <= 0 || sourceHeight <= 0) {
			return;
		}

		// Map texture pixels back to source pixels.
		int w = width();
		int h = height();
		int sx = Math.max(0, Math.min(sourceWidth - 1, x * sourceWidth / w));
		int sy = Math.max(0, Math.min(sourceHeight - 1, y * sourceHeight / h));
		NativeCapture.click(MemorySegment.ofAddress(hwnd), sx, sy);
	}

	@Override
	public boolean renderFrame(ByteBuffer dst) {
		if (closed || !frameReady) {
			return false;
		}

		frameReady = false;
		long written = NativeCapture.copyRgbaScaled(session, MemorySegment.ofBuffer(dst), dst.capacity(), width(), height());
		return written > 0;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		NativeCapture.closeWindow(session);
	}

	private static int[] fit(int srcWidth, int srcHeight) {
		int longSide = Math.max(srcWidth, srcHeight);
		if (longSide <= MAX_LONG_SIDE) {
			return new int[] {srcWidth, srcHeight};
		}
		int w = Math.max(1, srcWidth * MAX_LONG_SIDE / longSide);
		int h = Math.max(1, srcHeight * MAX_LONG_SIDE / longSide);
		return new int[] {w, h};
	}

	private static int clamp(int value) {
		return Math.max(MIN_SIZE, Math.min(MAX_SIZE, value));
	}
}
