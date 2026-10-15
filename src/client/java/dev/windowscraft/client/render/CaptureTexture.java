package dev.windowscraft.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import java.lang.foreign.MemorySegment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * A Minecraft texture whose pixels are overwritten by the native capture each frame.
 *
 * The NativeImage is RGBA8 (bytes R,G,B,A per pixel). Native code writes straight
 * into its memory through pixels(), so there is no Java-side copy.
 */
public final class CaptureTexture implements AutoCloseable {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("windowscraft", "window_frame");

	private NativeImage image;
	private DynamicTexture texture;
	private MemorySegment pixels = MemorySegment.NULL;
	private int width;
	private int height;

	public Identifier id() {
		return ID;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public long byteSize() {
		return (long) width * height * 4;
	}

	/** Native-writable view of the NativeImage memory. Empty until ensureSize has run. */
	public MemorySegment pixels() {
		return pixels;
	}

	/** Reallocates the image and re-registers the texture when the size changes. */
	public void ensureSize(int newWidth, int newHeight) {
		if (image != null && newWidth == width && newHeight == height) {
			return;
		}

		release();
		width = newWidth;
		height = newHeight;
		image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
		texture = new DynamicTexture(() -> "windowscraft_window", image);
		Minecraft.getInstance().getTextureManager().register(ID, texture);
		pixels = MemorySegment.ofAddress(image.getPointer()).reinterpret(byteSize());
	}

	/** Pushes the pixels to the GPU. Call after native code has written them. */
	public void upload() {
		if (texture != null) {
			texture.upload();
		}
	}

	private void release() {
		if (texture != null) {
			Minecraft.getInstance().getTextureManager().release(ID);
		}
		image = null;
		texture = null;
		pixels = MemorySegment.NULL;
		width = 0;
		height = 0;
	}

	@Override
	public void close() {
		release();
	}
}
