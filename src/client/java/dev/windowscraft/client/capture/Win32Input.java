package dev.windowscraft.client.capture;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Win32 input helpers, bound through FFM. No native DLL is involved.
 *
 * Two click paths are provided:
 *
 * - Posted path (sendButton, postMove): PostMessageW to the window, no focus change.
 *   Works for classic Win32 controls. Ignored by many modern apps.
 * - Active path (focus, setCursor, sendMouseButton, cursor): brings the window to the
 *   foreground and sends a real hardware-style click through SendInput. Used by
 *   NativeWindowBackend, which restores focus and the cursor on a later frame.
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

	public static final int MOUSEEVENTF_LEFTDOWN = 0x0002;
	public static final int MOUSEEVENTF_LEFTUP = 0x0004;
	public static final int MOUSEEVENTF_RIGHTDOWN = 0x0008;
	public static final int MOUSEEVENTF_RIGHTUP = 0x0010;
	public static final int MOUSEEVENTF_MIDDLEDOWN = 0x0020;
	public static final int MOUSEEVENTF_MIDDLEUP = 0x0040;

	private static final int INPUT_MOUSE = 0;
	private static final int INPUT_SIZE = 40;
	private static final int DWMWA_EXTENDED_FRAME_BOUNDS = 9;
	private static final int RDW_INVALIDATE = 0x0001;
	private static final int RDW_UPDATENOW = 0x0100;
	private static final int RDW_ALLCHILDREN = 0x0080;

	private static MethodHandle postMessage;
	private static MethodHandle clientToScreen;
	private static MethodHandle dwmGetWindowAttribute;
	private static MethodHandle getWindowThreadProcessId;
	private static MethodHandle attachThreadInput;
	private static MethodHandle redrawWindow;
	private static MethodHandle getCurrentThreadId;
	private static MethodHandle getForegroundWindow;
	private static MethodHandle setForegroundWindow;
	private static MethodHandle getCursorPos;
	private static MethodHandle setCursorPos;
	private static MethodHandle sendInput;
	private static boolean bound;

	private Win32Input() {
	}

	// ---- Posted path ----

	/** Posts a pointer move to client coordinates. No thread attachment needed. */
	public static synchronized boolean postMove(long hwnd, int clientX, int clientY) {
		return post(hwnd, WM_MOUSEMOVE, 0L, clientX, clientY);
	}

	/**
	 * Posts a button message with the threads attached, then forces a repaint of the window.
	 * wParam carries the button state flags.
	 */
	public static synchronized void sendButton(long hwnd, int msg, long wParam, int clientX, int clientY) {
		bind();
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment target = MemorySegment.ofAddress(hwnd);
			int targetThread = (int) getWindowThreadProcessId.invokeExact(target, MemorySegment.NULL);
			int myThread = (int) getCurrentThreadId.invokeExact();
			boolean attached = targetThread != 0 && targetThread != myThread
					&& (int) attachThreadInput.invokeExact(myThread, targetThread, 1) != 0;
			try {
				post(hwnd, msg, wParam, clientX, clientY);
				// Repaint so the UI shows the click even without focus.
				int ignored = (int) redrawWindow.invokeExact(target, MemorySegment.NULL, MemorySegment.NULL,
						RDW_INVALIDATE | RDW_UPDATENOW | RDW_ALLCHILDREN);
			} finally {
				if (attached) {
					int detached = (int) attachThreadInput.invokeExact(myThread, targetThread, 0);
				}
			}
		} catch (Throwable t) {
			throw new IllegalStateException("Could not send click to window", t);
		}
	}

	// ---- Active path ----

	/** Returns the handle of the current foreground window, or 0 when there is none. */
	public static synchronized long foreground() {
		bind();
		try {
			MemorySegment fg = (MemorySegment) getForegroundWindow.invokeExact();
			return fg.address();
		} catch (Throwable t) {
			throw new IllegalStateException("GetForegroundWindow failed", t);
		}
	}

	/**
	 * Makes hwnd the foreground window. The calling thread is attached to the current
	 * foreground thread first, which lets SetForegroundWindow succeed when Windows would
	 * otherwise refuse it.
	 */
	public static synchronized boolean focus(long hwnd) {
		bind();
		try {
			MemorySegment fg = (MemorySegment) getForegroundWindow.invokeExact();
			int myThread = (int) getCurrentThreadId.invokeExact();
			int fgThread = fg.address() == 0 ? 0 : (int) getWindowThreadProcessId.invokeExact(fg, MemorySegment.NULL);
			boolean attached = fgThread != 0 && fgThread != myThread
					&& (int) attachThreadInput.invokeExact(myThread, fgThread, 1) != 0;
			try {
				return (int) setForegroundWindow.invokeExact(MemorySegment.ofAddress(hwnd)) != 0;
			} finally {
				if (attached) {
					int ignored = (int) attachThreadInput.invokeExact(myThread, fgThread, 0);
				}
			}
		} catch (Throwable t) {
			throw new IllegalStateException("SetForegroundWindow failed", t);
		}
	}

	/** Returns the current OS cursor position in screen coordinates. */
	public static synchronized int[] cursor() {
		bind();
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment point = arena.allocate(8, 4);
			int ok = (int) getCursorPos.invokeExact(point);
			if (ok == 0) {
				return null;
			}
			return new int[] {point.get(ValueLayout.JAVA_INT, 0), point.get(ValueLayout.JAVA_INT, 4)};
		} catch (Throwable t) {
			throw new IllegalStateException("GetCursorPos failed", t);
		}
	}

	/** Moves the OS cursor to a screen position. */
	public static synchronized void setCursor(int screenX, int screenY) {
		bind();
		try {
			int ignored = (int) setCursorPos.invokeExact(screenX, screenY);
		} catch (Throwable t) {
			throw new IllegalStateException("SetCursorPos failed", t);
		}
	}

	/**
	 * Sends one mouse button event at the current cursor position. Uses SendInput, so the
	 * event goes to whichever window is under the cursor and has focus.
	 *
	 * @param button 0 left, 1 right, 2 middle
	 */
	public static synchronized void sendMouseButton(int button, boolean pressed) {
		int flags = buttonFlags(button, pressed);
		if (flags == 0) {
			return;
		}
		bind();
		try (Arena arena = Arena.ofConfined()) {
			// INPUT: type at 0, MOUSEINPUT at 8 (dx 8, dy 12, mouseData 16, dwFlags 20, time 24, extraInfo 32).
			MemorySegment input = arena.allocate(INPUT_SIZE, 8);
			input.fill((byte) 0);
			input.set(ValueLayout.JAVA_INT, 0, INPUT_MOUSE);
			input.set(ValueLayout.JAVA_INT, 20, flags);
			int sent = (int) sendInput.invokeExact(1, input, INPUT_SIZE);
			if (sent != 1) {
				throw new IllegalStateException("SendInput sent no events");
			}
		} catch (IllegalStateException e) {
			throw e;
		} catch (Throwable t) {
			throw new IllegalStateException("SendInput failed", t);
		}
	}

	/**
	 * Converts a client-area point of a window to screen coordinates.
	 *
	 * @return {x, y} screen coordinates, or null when the conversion fails
	 */
	public static synchronized int[] clientPointToScreen(long hwnd, int clientX, int clientY) {
		bind();
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment point = arena.allocate(8, 4);
			point.set(ValueLayout.JAVA_INT, 0, clientX);
			point.set(ValueLayout.JAVA_INT, 4, clientY);
			int ok = (int) clientToScreen.invokeExact(MemorySegment.ofAddress(hwnd), point);
			if (ok == 0) {
				return null;
			}
			return new int[] {point.get(ValueLayout.JAVA_INT, 0), point.get(ValueLayout.JAVA_INT, 4)};
		} catch (Throwable t) {
			throw new IllegalStateException("ClientToScreen failed", t);
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

	private static int buttonFlags(int button, boolean pressed) {
		return switch (button) {
			case 0 -> pressed ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP;
			case 1 -> pressed ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP;
			case 2 -> pressed ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP;
			default -> 0;
		};
	}

	private static boolean post(long hwnd, int msg, long wParam, int clientX, int clientY) {
		bind();
		// lParam packs the client position as two signed 16-bit values: y in the high word, x in the low word.
		long lParam = ((long) (clientY & 0xFFFF) << 16) | (clientX & 0xFFFF);
		try {
			return (int) postMessage.invokeExact(MemorySegment.ofAddress(hwnd), msg, wParam, lParam) != 0;
		} catch (Throwable t) {
			throw new IllegalStateException("PostMessageW failed", t);
		}
	}

	private static void bind() {
		if (bound) {
			return;
		}

		Arena arena = Arena.global();
		SymbolLookup user32 = SymbolLookup.libraryLookup("user32.dll", arena);
		SymbolLookup dwmapi = SymbolLookup.libraryLookup("dwmapi.dll", arena);
		SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32.dll", arena);
		Linker linker = Linker.nativeLinker();

		postMessage = linker.downcallHandle(find(user32, "PostMessageW"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
		clientToScreen = linker.downcallHandle(find(user32, "ClientToScreen"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		dwmGetWindowAttribute = linker.downcallHandle(find(dwmapi, "DwmGetWindowAttribute"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
		getWindowThreadProcessId = linker.downcallHandle(find(user32, "GetWindowThreadProcessId"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		attachThreadInput = linker.downcallHandle(find(user32, "AttachThreadInput"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
		redrawWindow = linker.downcallHandle(find(user32, "RedrawWindow"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
		getCurrentThreadId = linker.downcallHandle(find(kernel32, "GetCurrentThreadId"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT));
		getForegroundWindow = linker.downcallHandle(find(user32, "GetForegroundWindow"),
				FunctionDescriptor.of(ValueLayout.ADDRESS));
		setForegroundWindow = linker.downcallHandle(find(user32, "SetForegroundWindow"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		getCursorPos = linker.downcallHandle(find(user32, "GetCursorPos"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		setCursorPos = linker.downcallHandle(find(user32, "SetCursorPos"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
		sendInput = linker.downcallHandle(find(user32, "SendInput"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

		bound = true;
	}

	private static MemorySegment find(SymbolLookup lookup, String name) {
		return lookup.find(name).orElseThrow(() -> new IllegalStateException("Missing Win32 symbol " + name));
	}
}
