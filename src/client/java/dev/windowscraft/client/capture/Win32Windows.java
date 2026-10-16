package dev.windowscraft.client.capture;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists visible top-level windows by calling user32 and dwmapi through FFM directly.
 * No native DLL is involved, so this works without the capture library.
 */
public final class Win32Windows {
	/** One visible window. hwnd is the raw HWND value. */
	public record Info(long hwnd, String title) {
	}

	private static final int GW_HWNDNEXT = 2;
	private static final int DWMWA_CLOAKED = 14;
	private static final String EXCLUDED_TITLE = "Program Manager";

	private static MethodHandle getTopWindow;
	private static MethodHandle getWindow;
	private static MethodHandle isWindowVisible;
	private static MethodHandle getWindowTextLength;
	private static MethodHandle getWindowText;
	private static MethodHandle getWindowThreadProcessId;
	private static MethodHandle dwmGetWindowAttribute;
	private static boolean bound;

	private Win32Windows() {
	}

	/**
	 * Visible, non-cloaked top-level windows with a title. Windows owned by this process
	 * (the Minecraft window) are skipped.
	 */
	public static synchronized List<Info> visibleTopLevel() {
		bind();

		long ownPid = ProcessHandle.current().pid();
		List<Info> result = new ArrayList<>();

		try (Arena arena = Arena.ofConfined()) {
			MemorySegment scratch = arena.allocate(8, 8);
			MemorySegment hwnd = (MemorySegment) getTopWindow.invokeExact(MemorySegment.NULL);

			while (hwnd.address() != 0) {
				Info info = describe(arena, scratch, hwnd, ownPid);
				if (info != null) {
					result.add(info);
				}
				hwnd = (MemorySegment) getWindow.invokeExact(hwnd, GW_HWNDNEXT);
			}
		} catch (Throwable t) {
			throw new IllegalStateException("Window enumeration failed", t);
		}

		return result;
	}

	private static Info describe(Arena arena, MemorySegment scratch, MemorySegment hwnd, long ownPid) throws Throwable {
		int visible = (int) isWindowVisible.invokeExact(hwnd);
		if (visible == 0) {
			return null;
		}

		scratch.set(ValueLayout.JAVA_INT, 0, 0);
		int threadId = (int) getWindowThreadProcessId.invokeExact(hwnd, scratch);
		int pid = scratch.get(ValueLayout.JAVA_INT, 0);
		if (pid == ownPid || threadId == 0) {
			return null;
		}

		scratch.set(ValueLayout.JAVA_INT, 0, 0);
		int hr = (int) dwmGetWindowAttribute.invokeExact(hwnd, DWMWA_CLOAKED, scratch, 4);
		if (hr == 0 && scratch.get(ValueLayout.JAVA_INT, 0) != 0) {
			return null;
		}

		int length = (int) getWindowTextLength.invokeExact(hwnd);
		if (length <= 0) {
			return null;
		}

		MemorySegment buffer = arena.allocate((long) (length + 1) * 2, 2);
		int copied = (int) getWindowText.invokeExact(hwnd, buffer, length + 1);
		if (copied <= 0) {
			return null;
		}

		char[] chars = buffer.asSlice(0, (long) copied * 2).toArray(ValueLayout.JAVA_CHAR);
		String title = new String(chars);
		if (title.isBlank() || EXCLUDED_TITLE.equals(title)) {
			return null;
		}

		return new Info(hwnd.address(), title);
	}

	private static void bind() {
		if (bound) {
			return;
		}

		Arena arena = Arena.global();
		SymbolLookup user32 = SymbolLookup.libraryLookup("user32.dll", arena);
		SymbolLookup dwmapi = SymbolLookup.libraryLookup("dwmapi.dll", arena);
		Linker linker = Linker.nativeLinker();

		getTopWindow = linker.downcallHandle(find(user32, "GetTopWindow"),
				FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		getWindow = linker.downcallHandle(find(user32, "GetWindow"),
				FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
		isWindowVisible = linker.downcallHandle(find(user32, "IsWindowVisible"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		getWindowTextLength = linker.downcallHandle(find(user32, "GetWindowTextLengthW"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		getWindowText = linker.downcallHandle(find(user32, "GetWindowTextW"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
		getWindowThreadProcessId = linker.downcallHandle(find(user32, "GetWindowThreadProcessId"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		dwmGetWindowAttribute = linker.downcallHandle(find(dwmapi, "DwmGetWindowAttribute"),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

		bound = true;
	}

	private static MemorySegment find(SymbolLookup lookup, String name) {
		return lookup.find(name).orElseThrow(() -> new IllegalStateException("Missing Win32 symbol " + name));
	}
}
