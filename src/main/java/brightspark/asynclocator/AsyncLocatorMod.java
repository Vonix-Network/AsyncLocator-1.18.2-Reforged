package brightspark.asynclocator;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.network.NetworkConstants;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Forge entrypoint. Registers the config spec, attaches server lifecycle handlers, and exposes
 * the mod's {@link Logger} for use throughout the codebase.
 *
 * <p>All log calls go through the {@link #MARKER} SLF4J marker so log4j2 / logback pipelines
 * can route or filter AsyncLocator output independently of the rest of the server.</p>
 */
@Mod(AsyncLocatorMod.MOD_ID)
public final class AsyncLocatorMod {
	public static final String MOD_ID = "asynclocator";

	/** SLF4J marker attached to every log message emitted by this mod. */
	public static final Marker MARKER = MarkerFactory.getMarker("AsyncLocator");

	private static final Logger LOGGER = LogUtils.getLogger();

	public AsyncLocatorMod() {
		ModLoadingContext ctx = ModLoadingContext.get();

		// Server-only mod — clients don't need it, and the connection screen shouldn't flag it
		ctx.registerExtensionPoint(
			IExtensionPoint.DisplayTest.class,
			() -> new IExtensionPoint.DisplayTest(
				() -> NetworkConstants.IGNORESERVERONLY,
				(serverVersion, networkBool) -> true
			)
		);

		ctx.registerConfig(ModConfig.Type.SERVER, AsyncLocatorConfig.SPEC);

		IEventBus forgeEventBus = MinecraftForge.EVENT_BUS;
		forgeEventBus.addListener(AsyncLocator::handleServerStoppingEvent);
		forgeEventBus.addListener(AsyncLocator::handleServerAboutToStartEvent);
	}

	public static void logError(String msg, Object... args) { LOGGER.error(MARKER, msg, args); }
	public static void logWarn (String msg, Object... args) { LOGGER.warn (MARKER, msg, args); }
	public static void logInfo (String msg, Object... args) { LOGGER.info (MARKER, msg, args); }
	public static void logDebug(String msg, Object... args) { LOGGER.debug(MARKER, msg, args); }
}
