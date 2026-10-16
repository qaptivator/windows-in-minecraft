package dev.windowscraft.client.backend;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Placeholder window content generated in Java. It has no native dependency.
 *
 * Draws color bars, a grid, a moving scanline, the last pointer position and the
 * recent clicks. This is enough to test placement, grabbing, resizing and clicking.
 */
public final class TestPatternBackend implements WindowBackend {
	private static final long FRAME_INTERVAL_NANOS = 50_000_000L;
	private static final int MAX_MARKERS = 32;
	private static final int MARKER_RADIUS = 6;
	private static final int GRID_STEP = 40;
	private static final int MIN_SIZE = 64;
	private static final int MAX_SIZE = 2048;

	private static final int[][] BAR_COLORS = {
			{255, 255, 255}, {255, 255, 0}, {0, 255, 255}, {0, 255, 0},
			{255, 0, 255}, {255, 0, 0}, {0, 0, 255}, {20, 20, 20}
	};

	private final String title;
	private final long startNanos = System.nanoTime();
	private final ArrayDeque<int[]> markers = new ArrayDeque<>();

	private int width;
	private int height;
	private int pointerX = -1;
	private int pointerY = -1;
	private long lastFrameNanos;
	private boolean dirty = true;

	public TestPatternBackend(String title, int width, int height) {
		this.title = title;
		this.width = clamp(width);
		this.height = clamp(height);
	}

	@Override
	public String title() {
		return title;
	}

	@Override
	public int width() {
		return width;
	}

	@Override
	public int height() {
		return height;
	}

	@Override
	public void resize(int newWidth, int newHeight) {
		int w = clamp(newWidth);
		int h = clamp(newHeight);
		if (w == width && h == height) {
			return;
		}
		width = w;
		height = h;
		dirty = true;
	}

	@Override
	public void pointerMotion(int x, int y) {
		if (x == pointerX && y == pointerY) {
			return;
		}
		pointerX = x;
		pointerY = y;
		dirty = true;
	}

	@Override
	public void pointerButton(int x, int y, int button, boolean pressed) {
		if (!pressed) {
			return;
		}
		markers.addLast(new int[] {x, y, button});
		while (markers.size() > MAX_MARKERS) {
			markers.removeFirst();
		}
		dirty = true;
	}

	@Override
	public boolean renderFrame(ByteBuffer dst) {
		long now = System.nanoTime();
		boolean animate = now - lastFrameNanos >= FRAME_INTERVAL_NANOS;
		if (!dirty && !animate) {
			return false;
		}

		lastFrameNanos = now;
		dirty = false;

		int phase = (int) ((now - startNanos) / 20_000_000L);
		int scanline = Math.floorMod(phase, Math.max(1, height));

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int[] color = BAR_COLORS[Math.min(BAR_COLORS.length - 1, x * BAR_COLORS.length / width)];
				int r = color[0];
				int g = color[1];
				int b = color[2];

				if (x % GRID_STEP == 0 || y % GRID_STEP == 0) {
					r = r * 3 / 4;
					g = g * 3 / 4;
					b = b * 3 / 4;
				}

				if (y == scanline) {
					r = 255 - r;
					g = 255 - g;
					b = 255 - b;
				}

				int index = (y * width + x) * 4;
				dst.put(index, (byte) r);
				dst.put(index + 1, (byte) g);
				dst.put(index + 2, (byte) b);
				dst.put(index + 3, (byte) 255);
			}
		}

		for (int[] marker : markers) {
			int color = marker[2] == 1 ? 0xFF0000 : 0xFF00FF;
			drawSquare(dst, marker[0], marker[1], MARKER_RADIUS, color);
		}

		if (pointerX >= 0 && pointerY >= 0) {
			drawCross(dst, pointerX, pointerY, 0xFFFFFF);
		}

		return true;
	}

	private void drawSquare(ByteBuffer dst, int cx, int cy, int radius, int rgb) {
		for (int y = cy - radius; y <= cy + radius; y++) {
			for (int x = cx - radius; x <= cx + radius; x++) {
				putPixel(dst, x, y, rgb);
			}
		}
	}

	private void drawCross(ByteBuffer dst, int cx, int cy, int rgb) {
		for (int d = -8; d <= 8; d++) {
			putPixel(dst, cx + d, cy, rgb);
			putPixel(dst, cx, cy + d, rgb);
		}
	}

	private void putPixel(ByteBuffer dst, int x, int y, int rgb) {
		if (x < 0 || y < 0 || x >= width || y >= height) {
			return;
		}
		int index = (y * width + x) * 4;
		dst.put(index, (byte) ((rgb >> 16) & 0xFF));
		dst.put(index + 1, (byte) ((rgb >> 8) & 0xFF));
		dst.put(index + 2, (byte) (rgb & 0xFF));
		dst.put(index + 3, (byte) 255);
	}

	private static int clamp(int value) {
		return Math.max(MIN_SIZE, Math.min(MAX_SIZE, value));
	}

	@Override
	public void close() {
		markers.clear();
	}
}
