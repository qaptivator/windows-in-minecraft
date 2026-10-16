package dev.windowscraft.client.gui;

import dev.windowscraft.WindowsCraft;
import dev.windowscraft.client.backend.NativeWindowBackend;
import dev.windowscraft.client.backend.TestPatternBackend;
import dev.windowscraft.client.backend.WindowBackend;
import dev.windowscraft.client.capture.Win32Windows;
import dev.windowscraft.client.window.WindowManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Launcher opened with V. Lists test patterns and the visible desktop windows.
 * Selecting an entry places it in front of the player.
 *
 * Desktop windows are listed when the screen opens. Apps are not launched from here yet.
 */
public final class AppLauncherScreen extends Screen {
	private static final int BUTTON_WIDTH = 220;
	private static final int BUTTON_HEIGHT = 20;
	private static final int ROW_HEIGHT = 24;
	private static final int ICON_SIZE = 16;
	private static final int PAGE_SIZE = 8;
	private static final int NAV_BUTTON_WIDTH = 40;

	private static final List<LauncherEntry> PLACEHOLDERS = List.of(
			LauncherEntry.placeholder("Test Pattern", "grass_block_top", 640, 360),
			LauncherEntry.placeholder("Small Pad", "stone", 320, 240),
			LauncherEntry.placeholder("Tall Panel", "oak_planks", 300, 600));

	private final List<LauncherEntry> entries = new ArrayList<>();
	private int page;
	private String status = "";

	public AppLauncherScreen() {
		super(Component.literal("WindowsCraft Launcher"));
	}

	@Override
	protected void init() {
		entries.clear();
		entries.addAll(PLACEHOLDERS);

		try {
			for (Win32Windows.Info info : Win32Windows.visibleTopLevel()) {
				entries.add(LauncherEntry.desktopWindow(info));
			}
		} catch (Throwable t) {
			status = "Window list unavailable: " + t.getMessage();
			WindowsCraft.LOGGER.warn("Could not list desktop windows", t);
		}

		buildWidgets();
	}

	private int pageCount() {
		return Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
	}

	private int top() {
		return height / 2 - (PAGE_SIZE * ROW_HEIGHT) / 2;
	}

	private int left() {
		return width / 2 - BUTTON_WIDTH / 2;
	}

	private void buildWidgets() {
		clearWidgets();

		page = Math.max(0, Math.min(page, pageCount() - 1));
		int top = top();
		int left = left();

		for (int i = 0; i < PAGE_SIZE; i++) {
			int index = page * PAGE_SIZE + i;
			if (index >= entries.size()) {
				break;
			}

			LauncherEntry entry = entries.get(index);
			int y = top + i * ROW_HEIGHT;
			addRenderableWidget(Button.builder(Component.literal(entry.name()), (button) -> launch(entry))
					.bounds(left, y, BUTTON_WIDTH, BUTTON_HEIGHT)
					.build());
		}

		int navY = top + PAGE_SIZE * ROW_HEIGHT + 4;
		if (page > 0) {
			addRenderableWidget(Button.builder(Component.literal("<"), (button) -> changePage(-1))
					.bounds(left, navY, NAV_BUTTON_WIDTH, BUTTON_HEIGHT)
					.build());
		}
		if (page < pageCount() - 1) {
			addRenderableWidget(Button.builder(Component.literal(">"), (button) -> changePage(1))
					.bounds(left + BUTTON_WIDTH - NAV_BUTTON_WIDTH, navY, NAV_BUTTON_WIDTH, BUTTON_HEIGHT)
					.build());
		}
	}

	private void changePage(int delta) {
		page += delta;
		buildWidgets();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTicks);

		int top = top();
		int left = left();
		for (int i = 0; i < PAGE_SIZE; i++) {
			int index = page * PAGE_SIZE + i;
			if (index >= entries.size()) {
				break;
			}

			LauncherEntry entry = entries.get(index);
			int y = top + i * ROW_HEIGHT + (BUTTON_HEIGHT - ICON_SIZE) / 2;
			graphics.blit(RenderPipelines.GUI_TEXTURED, entry.icon(), left - ICON_SIZE - 6, y, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
		}

		int navY = top + PAGE_SIZE * ROW_HEIGHT + 4 + (BUTTON_HEIGHT - font.lineHeight) / 2;
		String pageText = "Page " + (page + 1) + " / " + pageCount();
		graphics.text(font, Component.literal(pageText), width / 2 - font.width(pageText) / 2, navY, 0xFFFFFFFF);

		if (!status.isEmpty()) {
			graphics.text(font, Component.literal(status), width / 2 - font.width(status) / 2, height - 20, 0xFFFF5555);
		}
	}

	private void launch(LauncherEntry entry) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}

		try {
			Optional<WindowBackend> backend = entry.open();
			if (backend.isEmpty()) {
				status = "Could not start capture for " + entry.name() + " (minimized or protected?)";
				return;
			}

			WindowManager.get().spawn(backend.get(), minecraft.gameRenderer.getMainCamera());
			onClose();
		} catch (Exception e) {
			status = "Capture failed: " + e.getMessage();
			WindowsCraft.LOGGER.warn("Could not open '{}'", entry.name(), e);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static Identifier block(String name) {
		return Identifier.withDefaultNamespace("textures/block/" + name + ".png");
	}

	/** Creates a backend on demand, so a failed capture is reported when the entry is picked. */
	@FunctionalInterface
	private interface BackendOpener {
		Optional<WindowBackend> open() throws Exception;
	}

	private record LauncherEntry(String name, Identifier icon, BackendOpener opener) {

		static LauncherEntry placeholder(String name, String block, int width, int height) {
			return new LauncherEntry(name, block(block), () -> Optional.of(new TestPatternBackend(name, width, height)));
		}

		static LauncherEntry desktopWindow(Win32Windows.Info info) {
			String name = info.title().length() > 40 ? info.title().substring(0, 37) + "..." : info.title();
			return new LauncherEntry(name, block("glass"), () -> NativeWindowBackend.open(info.title(), info.hwnd()));
		}

		Optional<WindowBackend> open() throws Exception {
			return opener.open();
		}
	}

}
