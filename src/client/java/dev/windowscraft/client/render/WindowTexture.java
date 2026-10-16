package dev.windowscraft.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * GPU texture that holds one window's pixels. It is registered with the TextureManager
 * under its own id, so the world renderer can sample it by identifier.
 *
 * Pixels are RGBA8. The CPU-side buffer is written by the window backend, then uploaded
 * with upload(). resize() recreates the GPU texture in place, so the registration stays valid.
 */
public final class WindowTexture extends AbstractTexture {
	private int width;
	private int height;
	private ByteBuffer pixels;
	private boolean closed;

	public WindowTexture(int width, int height) {
		allocate(width, height);
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** CPU-side RGBA8 buffer. Position is reset to 0 for each call. */
	public ByteBuffer pixels() {
		pixels.clear();
		return pixels;
	}

	/** Recreates the GPU texture and CPU buffer when the size changes. */
	public void resize(int newWidth, int newHeight) {
		if (newWidth == width && newHeight == height) {
			return;
		}
		releaseGpu();
		allocate(newWidth, newHeight);
	}

	/** Copies the CPU buffer to the GPU texture. Call after the backend wrote a new frame. */
	public void upload() {
		if (closed || texture == null) {
			return;
		}
		pixels.clear();
		RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, pixels, NativeImage.Format.RGBA, 0, 0, 0, 0, width, height);
	}

	private void allocate(int newWidth, int newHeight) {
		this.width = newWidth;
		this.height = newHeight;
		this.closed = false;

		texture = RenderSystem.getDevice().createTexture(
				"windowscraft-window-" + newWidth + "x" + newHeight,
				GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
				TextureFormat.RGBA8,
				newWidth,
				newHeight,
				1,
				1);
		textureView = RenderSystem.getDevice().createTextureView(texture);
		sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.NEAREST, false);
		pixels = ByteBuffer.allocateDirect(newWidth * newHeight * 4);

		// Opaque black until the first frame arrives. A transparent texture is discarded by the
		// cutout shader, which made new windows look missing.
		while (pixels.hasRemaining()) {
			pixels.put((byte) 0);
			pixels.put((byte) 0);
			pixels.put((byte) 0);
			pixels.put((byte) 255);
		}
		upload();
	}

	private void releaseGpu() {
		if (textureView != null) {
			textureView.close();
			textureView = null;
		}
		if (texture != null) {
			texture.close();
			texture = null;
		}
	}

	/** Idempotent. Frees GPU resources. Called by the TextureManager on release. */
	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		releaseGpu();
		pixels = null;
	}
}
