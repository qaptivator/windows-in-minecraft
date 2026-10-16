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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * FFM binding to windowscraft_native.dll (native/src/exports.cpp).
 *
 * The signatures below must match exports.cpp exactly. Every call must run on the
 * client thread. Capture sessions are created with open() and released with close().
 */
public final class NativeCapture {
	private static final String DLL_RESOURCE = "/natives/windows-x64/windowscraft_native.dll";
	private static final Linker LINKER = Linker.nativeLinker();

	private static MethodHandle initHandle;
	private static MethodHandle shutdownHandle;
	private static MethodHandle openWindowHandle;
	private static MethodHandle closeWindowHandle;
	private static MethodHandle pollHandle;
	private static MethodHandle getSizeHandle;
	private static MethodHandle copyRgbaScaledHandle;
	private static MethodHandle clickHandle;

	private static boolean loaded;
	private static boolean initialized;

	private NativeCapture() {
	}

	/** Loads the DLL from the mod jar and creates the shared D3D11 device. Safe to call repeatedly. */
	public static synchronized void ensureReady() throws IOException {
		if (!loaded) {
			load();
		}
		if (!initialized) {
			if (!init()) {
				throw new IllegalStateException("wc_init failed, see the native log");
			}
			initialized = true;
		}
	}

	private static void load() throws IOException {
		SymbolLookup lookup = SymbolLookup.libraryLookup(extractDll(), Arena.global());
		initHandle = bind(lookup, "wc_init", FunctionDescriptor.of(ValueLayout.JAVA_INT));
		shutdownHandle = bind(lookup, "wc_shutdown", FunctionDescriptor.ofVoid());
		openWindowHandle = bind(lookup, "wc_open_window", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		closeWindowHandle = bind(lookup, "wc_close", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
		pollHandle = bind(lookup, "wc_poll", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		getSizeHandle = bind(lookup, "wc_get_size", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		copyRgbaScaledHandle = bind(lookup, "wc_copy_rgba_scaled", FunctionDescriptor.of(
				ValueLayout.JAVA_LONG,
				ValueLayout.ADDRESS,
				ValueLayout.ADDRESS,
				ValueLayout.JAVA_LONG,
				ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT));
		clickHandle = bind(lookup, "wc_click", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
		loaded = true;
	}

	private static boolean init() {
		try {
			return (int) initHandle.invokeExact() != 0;
		} catch (Throwable t) {
			throw failed("wc_init", t);
		}
	}

	/** Releases the D3D11 device. Close all sessions first. */
	public static synchronized void shutdown() {
		if (!initialized) {
			return;
		}
		try {
			shutdownHandle.invokeExact();
		} catch (Throwable t) {
			throw failed("wc_shutdown", t);
		}
		initialized = false;
	}

	/** Starts a capture session for hwnd. Returns a zero-address segment if capture could not start. */
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

	/** True when the session produced a new CPU frame. */
	public static boolean poll(MemorySegment session) {
		try {
			return (int) pollHandle.invokeExact(session) != 0;
		} catch (Throwable t) {
			throw failed("wc_poll", t);
		}
	}

	/** Writes the frame size into the two 4-byte int slots. */
	public static boolean getSize(MemorySegment session, MemorySegment width, MemorySegment height) {
		try {
			return (int) getSizeHandle.invokeExact(session, width, height) != 0;
		} catch (Throwable t) {
			throw failed("wc_get_size", t);
		}
	}

	/**
	 * Box-filters the newest frame to width x height RGBA8 into dst.
	 * dst must hold at least width * height * 4 bytes. Returns bytes written, 0 on failure.
	 */
	public static long copyRgbaScaled(MemorySegment session, MemorySegment dst, long capacity, int width, int height) {
		try {
			return (long) copyRgbaScaledHandle.invokeExact(session, dst, capacity, width, height);
		} catch (Throwable t) {
			throw failed("wc_copy_rgba_scaled", t);
		}
	}

	/** Left click at frame-pixel coordinates of the captured window. */
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
				throw new IOException("Missing " + DLL_RESOURCE + ". Build the native project first, see README");
			}

			// Copy to a temp file so the build output can be replaced while the game runs.
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
