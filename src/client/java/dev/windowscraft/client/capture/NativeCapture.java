package dev.windowscraft.client.capture;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Raw FFM (java.lang.foreign) binding to windowscraft_native.dll.
 *
 * Every native function declared in native/src/exports.cpp has one handle here.
 * The C signatures and these descriptors must stay in sync by hand.
 */
public final class NativeCapture {
	private static final String DLL_RESOURCE = "/natives/windows-x64/windowscraft_native.dll";
	private static final Linker LINKER = Linker.nativeLinker();

	private static MethodHandle initHandle;
	private static MethodHandle shutdownHandle;
	private static MethodHandle findWindowHandle;
	private static MethodHandle openWindowHandle;
	private static MethodHandle closeWindowHandle;
	private static MethodHandle pollHandle;
	private static MethodHandle getSizeHandle;
	private static MethodHandle copyRgbaHandle;
	private static MethodHandle clickHandle;
	private static boolean loaded;

	private NativeCapture() {
	}

	/** Extracts the DLL from the mod jar and binds every symbol. Idempotent. */
	public static synchronized void load() throws IOException {
		if (loaded) {
			return;
		}

		SymbolLookup lookup = SymbolLookup.libraryLookup(extractDll(), Arena.global());
		initHandle = bind(lookup, "wc_init", FunctionDescriptor.of(ValueLayout.JAVA_INT));
		shutdownHandle = bind(lookup, "wc_shutdown", FunctionDescriptor.ofVoid());
		findWindowHandle = bind(lookup, "wc_find_window", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		openWindowHandle = bind(lookup, "wc_open_window", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		closeWindowHandle = bind(lookup, "wc_close", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
		pollHandle = bind(lookup, "wc_poll", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		getSizeHandle = bind(lookup, "wc_get_size", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		copyRgbaHandle = bind(lookup, "wc_copy_rgba", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
		clickHandle = bind(lookup, "wc_click", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
		loaded = true;
	}

	/** Creates the shared D3D11 device. Safe to call more than once. */
	public static boolean init() {
		try {
			return (int) initHandle.invokeExact() != 0;
		} catch (Throwable t) {
			throw failed("wc_init", t);
		}
	}

	public static synchronized void shutdown() {
		if (!loaded) {
			return;
		}
		try {
			shutdownHandle.invokeExact();
		} catch (Throwable t) {
			throw failed("wc_shutdown", t);
		}
	}

	/** Returns a zero-address segment when no visible window matches. */
	public static MemorySegment findWindow(String titleSubstring) {
		// wchar_t is UTF-16LE on Windows; the trailing NUL terminates the string.
		byte[] utf16 = (titleSubstring + '\0').getBytes(StandardCharsets.UTF_16LE);
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment buffer = arena.allocate(utf16.length);
			buffer.copyFrom(MemorySegment.ofArray(utf16));
			return (MemorySegment) findWindowHandle.invokeExact(buffer);
		} catch (Throwable t) {
			throw failed("wc_find_window", t);
		}
	}

	/** Returns a zero-address segment when the session could not be started. */
	public static MemorySegment openWindow(MemorySegment hwnd) {
		try {
			return (MemorySegment) openWindowHandle.invokeExact(hwnd);
		} catch (Throwable t) {
			throw failed("wc_open_window", t);
		}
	}

	public static void closeWindow(MemorySegment session) {
		try {
			closeWindowHandle.invokeExact(session);
		} catch (Throwable t) {
			throw failed("wc_close", t);
		}
	}

	/** True when a new frame has been copied into the native CPU buffer. */
	public static boolean poll(MemorySegment session) {
		try {
			return (int) pollHandle.invokeExact(session) != 0;
		} catch (Throwable t) {
			throw failed("wc_poll", t);
		}
	}

	/** Writes the current frame size into the two 4-byte int segments. */
	public static boolean getSize(MemorySegment session, MemorySegment width, MemorySegment height) {
		try {
			return (int) getSizeHandle.invokeExact(session, width, height) != 0;
		} catch (Throwable t) {
			throw failed("wc_get_size", t);
		}
	}

	/** Copies RGBA8 pixels into dst. Returns bytes written, 0 on failure. */
	public static long copyRgba(MemorySegment session, MemorySegment dst, long capacity) {
		try {
			return (long) copyRgbaHandle.invokeExact(session, dst, capacity);
		} catch (Throwable t) {
			throw failed("wc_copy_rgba", t);
		}
	}

	/** Clicks at frame-pixel coordinates (see native/src/input.cpp). */
	public static boolean click(MemorySegment hwnd, int x, int y) {
		try {
			return (int) clickHandle.invokeExact(hwnd, x, y) != 0;
		} catch (Throwable t) {
			throw failed("wc_click", t);
		}
	}

	private static Path extractDll() throws IOException {
		try (InputStream in = NativeCapture.class.getResourceAsStream(DLL_RESOURCE)) {
			if (in == null) {
				throw new IOException("Missing " + DLL_RESOURCE + ". Build the native project first, see BUILD.md");
			}

			// Copy to a temp file so the build directory can be rebuilt while the game is running.
			Path path = Files.createTempFile("windowscraft_native", ".dll");
			path.toFile().deleteOnExit();
			Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
			return path;
		}
	}

	private static MethodHandle bind(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
		MemorySegment symbol = lookup.find(name)
				.orElseThrow(() -> new IllegalStateException("Missing native symbol " + name));
		return LINKER.downcallHandle(symbol, descriptor);
	}

	private static IllegalStateException failed(String name, Throwable cause) {
		return new IllegalStateException("Native call " + name + " failed", cause);
	}
}
