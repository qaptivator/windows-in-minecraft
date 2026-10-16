package dev.windowscraft.client.gui;

import dev.windowscraft.client.backend.TestPatternBackend;
import dev.windowscraft.client.window.WindowManager;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Launcher opened with V. Each entry spawns a window in front of the player.
 * Icons are vanilla block textures for now, as placeholders.
 */
public final class AppLauncherScreen extends Screen {
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int ROW_HEIGHT = 24;
	private static final int ICON_SIZE = 16;

	private static final List<LauncherEntry> ENTRIES = List.of(
			new LauncherEntry("Test Pattern", block("grass_block_top"), 640, 360),
			new LauncherEntry("Small Pad", block("stone"), 320, 240),
			new LauncherEntry("Tall Panel", block("oak_planks"), 300, 600));

	public AppLauncherScreen() {
		super(Component.literal("WindowsCraft Launcher"));
	}

	private static Identifier block(String name) {
		return Identifier.withDefaultNamespace("textures/block/" + name + ".png");
	}

	@Override
	protected void init() {
		int top = height / 2 - (ENTRIES.size() * ROW_HEIGHT) / 2;
		int left = width / 2 - BUTTON_WIDTH / 2;

		for (int i = 0; i < ENTRIES.size(); i++) {
			LauncherEntry entry = ENTRIES.get(i);
			int y = top + i * ROW_HEIGHT;
			addRenderableWidget(Button.builder(Component.literal(entry.name()), (button) -> launch(entry))
					.bounds(left, y, BUTTON_WIDTH, BUTTON_HEIGHT)
					.build());
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTicks);

		int top = height / 2 - (ENTRIES.size() * ROW_HEIGHT) / 2;
		int left = width / 2 - BUTTON_WIDTH / 2;
		for (int i = 0; i < ENTRIES.size(); i++) {
			LauncherEntry entry = ENTRIES.get(i);
			int y = top + i * ROW_HEIGHT + (BUTTON_HEIGHT - ICON_SIZE) / 2;
			graphics.blit(RenderPipelines.GUI_TEXTURED, entry.icon(), left - ICON_SIZE - 6, y, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
		}
	}

	private void launch(LauncherEntry entry) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}

		WindowManager.get().spawn(new TestPatternBackend(entry.name(), entry.width(), entry.height()), minecraft.gameRenderer.getMainCamera());
		onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private record LauncherEntry(String name, Identifier icon, int width, int height) {
	}

}
