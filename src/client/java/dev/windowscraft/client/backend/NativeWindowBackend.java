package dev.windowscraft.client.backend;

import dev.windowscraft.client.capture.NativeCapture;
import dev.windowscraft.client.capture.Win32Input;
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
 * MAX_LONG_SIDE. A resize request overrides that size; the native side scales to match.
 *
 * Input is sent with PostMessageW (see Win32Input): left, right and middle buttons and
 * pointer motion. Messages are posted to the real window, so Minecraft keeps focus.
 */
public final class NativeWindowBackend implements WindowBackend {
	/**
	 * Largest texture side in pixels. 1920 keeps text readable at normal in-game distances.
	 * Higher values cost more upload bandwidth and GPU memory per window.
	 */
	private static final int MAX_LONG_SIDE = 1920;
	private static final int FALLBACK_WIDTH = 640;
	private static final int FALLBACK_HEIGHT = 360;
	private static final int MIN_SIZE = 64;
	private static final int MAX_SIZE = 4096;

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

	private int lastMotionX = -1;
	private int lastMotionY = -1;

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
		if (closed || sourceWidth <= 0) {
			return;
		}

		int[] source = textureToSource(x, y);
		if (source[0] == lastMotionX && source[1] == lastMotionY) {
			return;
		}
		lastMotionX = source[0];
		lastMotionY = source[1];
		post(Win32Input.WM_MOUSEMOVE, 0, source[0], source[1]);
	}

	@Override
	public void pointerButton(int x, int y, int button, boolean pressed) {
		if (closed || sourceWidth <= 0) {
			return;
		}

		int down;
		int up;
		long flag;
		switch (button) {
			case 0 -> {
				down = Win32Input.WM_LBUTTONDOWN;
				up = Win32Input.WM_LBUTTONUP;
				flag = Win32Input.MK_LBUTTON;
			}
			case 1 -> {
				down = Win32Input.WM_RBUTTONDOWN;
				up = Win32Input.WM_RBUTTONUP;
				flag = Win32Input.MK_RBUTTON;
			}
			case 2 -> {
				down = Win32Input.WM_MBUTTONDOWN;
				up = Win32Input.WM_MBUTTONUP;
				flag = Win32Input.MK_MBUTTON;
			}
			default -> {
				return;
			}
		}

		int[] source = textureToSource(x, y);
		// Move first, so the target knows where the pointer is before the button event.
		post(Win32Input.WM_MOUSEMOVE, 0, source[0], source[1]);
		if (pressed) {
			post(down, flag, source[0], source[1]);
		}
		else {
			post(up, 0, source[0], source[1]);
		}
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

	/** Maps a texture pixel to a source-frame pixel. */
	private int[] textureToSource(int x, int y) {
		int sx = Math.max(0, Math.min(sourceWidth - 1, x * sourceWidth / width()));
		int sy = Math.max(0, Math.min(sourceHeight - 1, y * sourceHeight / height()));
		return new int[] {sx, sy};
	}

	private void post(int msg, long flags, int sourceX, int sourceY) {
		int[] client = Win32Input.toClient(hwnd, sourceX, sourceY, sourceWidth, sourceHeight);
		if (client == null) {
			return;
		}
		Win32Input.post(hwnd, msg, flags, client[0], client[1]);
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
