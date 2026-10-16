package dev.windowscraft.client.window;

import dev.windowscraft.WindowsCraft;
import dev.windowscraft.client.backend.WindowBackend;
import dev.windowscraft.client.render.WindowTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * One window placed in the world: a backend, its texture, and the plane it lives on.
 *
 * Conventions, matching the waylandcraft design:
 * - pivot is the world position of the window center
 * - normal points from the window toward the viewer
 * - down is the window's downward direction, perpendicular to normal
 * - right = normal x down, so the image is not mirrored for the viewer
 * - one block equals PIXELS_PER_BLOCK pixels
 */
public final class WindowInstance {
	public static final double PIXELS_PER_BLOCK = 80.0;

	private static int nextId = 0;

	private final WindowBackend backend;
	private final WindowTexture texture;
	private final Identifier textureId;

	private Vec3 pivot;
	private Vec3 normal;
	private Vec3 down;
	private int width;
	private int height;

	public WindowInstance(WindowBackend backend, Vec3 pivot, Vec3 normal, Vec3 down) {
		this.backend = backend;
		this.pivot = pivot;
		this.normal = normal;
		this.down = down;
		this.width = backend.width();
		this.height = backend.height();
		this.textureId = Identifier.fromNamespaceAndPath(WindowsCraft.MOD_ID, "window/" + (nextId++));
		this.texture = new WindowTexture(width, height);
		Minecraft.getInstance().getTextureManager().register(textureId, texture);
	}

	public WindowBackend backend() {
		return backend;
	}

	public WindowTexture texture() {
		return texture;
	}

	public Identifier textureId() {
		return textureId;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public Vec3 pivot() {
		return pivot;
	}

	public void setPivot(Vec3 newPivot) {
		this.pivot = newPivot;
	}

	public Vec3 normal() {
		return normal;
	}

	public Vec3 down() {
		return down;
	}

	public Vec3 right() {
		return normal.cross(down);
	}

	/** World vector for one pixel step to the right. */
	public Vec3 localX() {
		return right().scale(1.0 / PIXELS_PER_BLOCK);
	}

	/** World vector for one pixel step down. */
	public Vec3 localY() {
		return down.scale(1.0 / PIXELS_PER_BLOCK);
	}

	/** World position of the top-left corner. */
	public Vec3 origin() {
		return pivot.subtract(localX().scale(width / 2.0)).subtract(localY().scale(height / 2.0));
	}

	/** Full-width span vector, used for rendering. */
	public Vec3 spanX() {
		return localX().scale(width);
	}

	/** Full-height span vector, used for rendering. */
	public Vec3 spanY() {
		return localY().scale(height);
	}

	/**
	 * Copies the backend's size into this object and resizes the texture.
	 * Keeps the pivot, so external size changes grow from the center.
	 */
	public void refresh() {
		int newWidth = backend.width();
		int newHeight = backend.height();
		if (newWidth != width || newHeight != height) {
			width = newWidth;
			height = newHeight;
			texture.resize(width, height);
		}
	}

	/**
	 * Resizes the window while keeping its top-left corner fixed, which is how a
	 * bottom-right resize drag should behave.
	 */
	public void resizeKeepingOrigin(int newWidth, int newHeight) {
		Vec3 topLeft = origin();
		backend.resize(newWidth, newHeight);
		width = backend.width();
		height = backend.height();
		texture.resize(width, height);
		pivot = topLeft.add(localX().scale(width / 2.0)).add(localY().scale(height / 2.0));
	}

	/** Intersection of a ray with the window's infinite plane. Returns null when parallel or behind. */
	@Nullable
	public Vec3 planeHit(Vec3 pos, Vec3 dir) {
		double facing = dir.dot(normal);
		if (Math.abs(facing) < 1.0e-6) {
			return null;
		}

		double t = pivot.subtract(pos).dot(normal) / facing;
		if (t <= 0.0) {
			return null;
		}

		return pos.add(dir.scale(t));
	}

	/** Surface-local pixel coordinates of a world point on the plane. Not bounds checked. */
	public double[] localPixels(Vec3 worldPoint) {
		Vec3 rel = worldPoint.subtract(origin());
		double x = rel.dot(right()) * PIXELS_PER_BLOCK;
		double y = rel.dot(down) * PIXELS_PER_BLOCK;
		return new double[] {x, y};
	}

	/**
	 * Ray hit test against the window surface.
	 *
	 * @return hit data when the ray hits the visible rectangle, otherwise null
	 */
	@Nullable
	public Hit intersect(Vec3 pos, Vec3 dir) {
		Vec3 hit = planeHit(pos, dir);
		if (hit == null) {
			return null;
		}

		double[] local = localPixels(hit);
		if (local[0] < 0 || local[1] < 0 || local[0] > width || local[1] > height) {
			return null;
		}

		return new Hit(this, hit, hit.subtract(pos).length(), local[0], local[1]);
	}

	/** Frees the backend and the texture, and unregisters the texture. */
	public void close() {
		backend.close();
		Minecraft.getInstance().getTextureManager().release(textureId);
	}

	public record Hit(WindowInstance window, Vec3 world, double distance, double x, double y) {
	}
}
