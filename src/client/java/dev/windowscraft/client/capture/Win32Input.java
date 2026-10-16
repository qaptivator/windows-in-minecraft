package dev.windowscraft.client.capture;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Sends mouse messages to a window with PostMessageW, through FFM. No native DLL is involved.
 *
 * Posted messages do not move the system cursor or change focus, so Minecraft keeps input.
 * Classic Win32 controls (Notepad, Notepad++, Explorer) respond to them. Apps that read raw
 * input or use their own input stack (many games, some Chromium and UWP surfaces) may ignore them.
 */
public final class Win32Input {
	public static final int WM_MOUSEMOVE = 0x0200;
	public static final int WM_LBUTTONDOWN = 0x0201;
	public static final int WM_LBUTTONUP = 0x0202;
	public static final int WM_RBUTTONDOWN = 0x0204;
	public static final int WM_RBUTTONUP = 0x0205;
	public static final int WM_MBUTTONDOWN = 0x0207;
	public static final int WM_MBUTTONUP = 0x0208;

	public static final long MK_LBUTTON = 0x0001;
	public static final long MK_RBUTTON = 0x0002;
	public static final long MK_MBUTTON = 0x0010;

	private static final int DWMWA_EXTENDED_FRAME_BOUNDS = 9;

	private static MethodHandle postMessage;
	private static MethodHandle clientToScreen;
	private static MethodHandle dwmGetWindowAttribute;
	private static boolean bound;

	private Win32Input() {
	}

	/**
	 * Posts a mouse message with client coordinates. wParam carries the button state flags.
	 *
	 * @return true when the message was queued
	 */
	public static synchronized boolean post(long hwnd, int msg, long wParam, int clientX, int clientY) {
		bind();
		// lParam packs the client position as two signed 16-bit values: y in the high word, x in the low word.
		long lParam = ((long) (clientY & 0xFFFF) << 16) | (clientX & 0xFFFF);
		try {
			return (int) postMessage.invokeExact(MemorySegment.ofAddress(hwnd), msg, wParam, lParam) != 0;
		} catch (Throwable t) {
			throw new IllegalStateException("PostMessageW failed", t);
		}
	}

	/**
	 * Maps a point in source-frame pixels to client coordinates of the window.
	 *
	 * The capture covers the window's extended frame bounds (no invisible resize border).
	 * The client origin comes from ClientToScreen, so the title bar and border are excluded.
	 *
	 * @return {x, y} client coordinates, or null when the window bounds are unavailable
	 */
	public static synchronized int[] toClient(long hwnd, int frameX, int frameY, int frameWidth, int frameHeight) {
		bind();
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment rect = arena.allocate(16, 4);
			int hr = (int) dwmGetWindowAttribute.invokeExact(MemorySegment.ofAddress(hwnd), DWMWA_EXTENDED_FRAME_BOUNDS, rect, 16);
			if (hr != 0) {
				return null;
			}

			int left = rect.get(ValueLayout.JAVA_INT, 0);
			int top = rect.get(ValueLayout.JAVA_INT, 4);
			int rectWidth = rect.get(ValueLayout.JAVA_INT, 8) - left;
			int rectHeight = rect.get(ValueLayout.JAVA_INT, 12) - top;
			if (rectWidth <= 0 || rectHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) {
				return null;
			}

			int screenX = left + (int) ((long) frameX * rectWidth / frameWidth);
			int screenY = top + (int) ((long) frameY * rectHeight / frameHeight);

			MemorySegment origin = arena.allocate(8, 4);
			origin.set(ValueLayout.JAVA_INT, 0, 0);
			origin.set(ValueLayout.JAVA_INT, 4, 0);
			int ok = (int) clientToScreen.invokeExact(MemorySegment.ofAddress(hwnd), origin);
			if (ok == 0) {
				return null;
			}

			int clientX = screenX - origin.get(ValueLayout.JAVA_INT, 0);
			int clientY = screenY - origin.get(ValueLayout.JAVA_INT, 4);
			return new int[] {clientX, clientY};
		} catch (Throwable t) {
			throw new IllegalStateException("Window coordinate mapping failed", t);
		}
	}

	private static void bind() {
		if (bound) {
			return;
		}

		Arena arena = Arena.global();
		SymbolLookup user32 = SymbolLookup.libraryLookup("user32.dll", arena);
		SymbolLookup dwmapi = SymbolLookup.libraryLookup("dwmapi.dll", arena);
		Linker linker = Linker.nativeLinker();

		postMessage = linker.downcallHandle(find(user32, "PostMessageW"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
		clientToScreen = linker.downcallHandle(find(user32, "ClientToScreen"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		dwmGetWindowAttribute = linker.downcallHandle(find(dwmapi, "DwmGetWindowAttribute"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

		bound = true;
	}

	private static MemorySegment find(SymbolLookup lookup, String name) {
		return lookup.find(name).orElseThrow(() -> new IllegalStateException("Missing Win32 symbol " + name));
	}
}
