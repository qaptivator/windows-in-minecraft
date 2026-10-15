package dev.windowscraft.client.capture;

import dev.windowscraft.client.render.CaptureTexture;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Optional;

/**
 * One captured Windows window. Owns a native capture session and the HWND it mirrors.
 * All methods must be called from the Minecraft render thread.
 */
public final class WindowCapture implements AutoCloseable {
	private final MemorySegment hwnd;
	private final MemorySegment session;
	private final MemorySegment sizeBuffer;

	private WindowCapture(MemorySegment hwnd, MemorySegment session) {
		this.hwnd = hwnd;
		this.session = session;
		// Two 32-bit ints: width at offset 0, height at offset 4.
		this.sizeBuffer = Arena.ofAuto().allocate(8, 4);
	}

	public static Optional<WindowCapture> findByTitle(String titleSubstring) {
		MemorySegment hwnd = NativeCapture.findWindow(titleSubstring);
		if (hwnd.address() == 0) {
			return Optional.empty();
		}

		MemorySegment session = NativeCapture.openWindow(hwnd);
		if (session.address() == 0) {
			return Optional.empty();
		}

		return Optional.of(new WindowCapture(hwnd, session));
	}

	/**
	 * Pulls the newest frame and writes it into the texture.
	 * Returns true if the texture was updated this call.
	 */
	public boolean updateTexture(CaptureTexture texture) {
		if (!NativeCapture.poll(session)) {
			return false;
		}

		MemorySegment widthSlot = sizeBuffer;
		MemorySegment heightSlot = sizeBuffer.asSlice(4);
		if (!NativeCapture.getSize(session, widthSlot, heightSlot)) {
			return false;
		}

		int width = widthSlot.get(ValueLayout.JAVA_INT, 0);
		int height = heightSlot.get(ValueLayout.JAVA_INT, 0);
		if (width <= 0 || height <= 0) {
			return false;
		}

		texture.ensureSize(width, height);
		long copied = NativeCapture.copyRgba(session, texture.pixels(), texture.byteSize());
		if (copied == 0) {
			return false;
		}

		texture.upload();
		return true;
	}

	public boolean click(int x, int y) {
		return NativeCapture.click(hwnd, x, y);
	}

	@Override
	public void close() {
		NativeCapture.closeWindow(session);
	}
}
