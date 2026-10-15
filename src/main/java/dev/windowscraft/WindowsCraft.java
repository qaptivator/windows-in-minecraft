package dev.windowscraft;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WindowsCraft implements ModInitializer {
	public static final String MOD_ID = "windowscraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("WindowsCraft loaded");
	}
}
