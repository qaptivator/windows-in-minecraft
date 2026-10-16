package dev.windowscraft.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.windowscraft.WindowsCraft;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

/**
 * Submits window quads to the level render collector.
 *
 * Each window is two quads: a textured front face and a dark back face, so it can be
 * seen from both sides. Drawing goes through SubmitNodeCollector, the same path vanilla
 * uses for block entities, so no manual render pass or depth setup is needed.
 */
public final class WindowRenderer {
	private static final Supplier<GpuSampler> WINDOW_SAMPLER = () -> RenderSystem.getSamplerCache()
			.getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.NEAREST, false);

	private static final RenderPipeline.Snippet SNIPPET = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
			.withVertexShader(Identifier.fromNamespaceAndPath(WindowsCraft.MOD_ID, "core/rendertype_window"))
			.withFragmentShader(Identifier.fromNamespaceAndPath(WindowsCraft.MOD_ID, "core/rendertype_window"))
			.withSampler("Sampler0")
			.withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
			.buildSnippet();

	private static final RenderPipeline FRONT_PIPELINE = RenderPipelines.register(RenderPipeline.builder(SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(WindowsCraft.MOD_ID, "pipeline/window_front"))
			.withShaderDefine("ALPHA_CUTOUT")
			.build());

	private static final RenderPipeline BACK_PIPELINE = RenderPipelines.register(RenderPipeline.builder(SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(WindowsCraft.MOD_ID, "pipeline/window_back"))
			.withShaderDefine("ALPHA_CUTOUT")
			.withShaderDefine("NO_COLOR")
			.build());

	private static final Function<Identifier, RenderType> FRONT = Util.memoize((id) -> RenderType.create(
			"windowscraft_front",
			RenderSetup.builder(FRONT_PIPELINE).withTexture("Sampler0", id, WINDOW_SAMPLER).createRenderSetup()));

	private static final Function<Identifier, RenderType> BACK = Util.memoize((id) -> RenderType.create(
			"windowscraft_back",
			RenderSetup.builder(BACK_PIPELINE).withTexture("Sampler0", id, WINDOW_SAMPLER).createRenderSetup()));

	private WindowRenderer() {
	}

	/**
	 * Submits one window. origin is the top-left corner in the current pose space,
	 * spanX and spanY are the full width and height vectors.
	 */
	public static void submit(SubmitNodeCollector collector, PoseStack poseStack, Identifier texture, Vec3 origin, Vec3 spanX, Vec3 spanY) {
		collector.submitCustomGeometry(poseStack, FRONT.apply(texture), new Quad(origin, spanX, spanY, false));
		collector.submitCustomGeometry(poseStack, BACK.apply(texture), new Quad(origin, spanX, spanY, true));
	}

	private record Quad(Vec3 origin, Vec3 spanX, Vec3 spanY, boolean reverse) implements CustomGeometryRenderer {

		@Override
		public void render(Pose pose, VertexConsumer buffer) {
			Vec3 tl = origin;
			Vec3 bl = tl.add(spanY);
			Vec3 br = bl.add(spanX);
			Vec3 tr = tl.add(spanX);

			if (!reverse) {
				buffer.addVertex(pose, tl.toVector3f()).setUv(0.0f, 0.0f);
				buffer.addVertex(pose, bl.toVector3f()).setUv(0.0f, 1.0f);
				buffer.addVertex(pose, br.toVector3f()).setUv(1.0f, 1.0f);
				buffer.addVertex(pose, tr.toVector3f()).setUv(1.0f, 0.0f);
			}
			else {
				buffer.addVertex(pose, tr.toVector3f()).setUv(1.0f, 0.0f);
				buffer.addVertex(pose, br.toVector3f()).setUv(1.0f, 1.0f);
				buffer.addVertex(pose, bl.toVector3f()).setUv(0.0f, 1.0f);
				buffer.addVertex(pose, tl.toVector3f()).setUv(0.0f, 0.0f);
			}
		}

	}

}
