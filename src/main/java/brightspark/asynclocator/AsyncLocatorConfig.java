package brightspark.asynclocator;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.Builder;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;

/**
 * Server-side configuration for AsyncLocator.
 *
 * <p>Layout matches the {@code 1.18.2-1.1.0} on-disk schema exactly so existing
 * {@code asynclocator-server.toml} files written by 1.1.0 are read without loss of
 * customisation. New keys introduced in this fork live under the {@code [Features]} category
 * and default to {@code true} (preserves vanilla 1.1.0 behaviour for upgrading servers).</p>
 *
 * <p>Each async-locate feature can be independently disabled. If a flag is {@code false}, that
 * interception path falls through to vanilla synchronous behaviour. This is the primary lever
 * for isolating performance issues or compatibility problems with other mods — disable the
 * offending feature without disabling the whole mod.</p>
 */
public final class AsyncLocatorConfig {
	public static final ForgeConfigSpec SPEC;

	// Top-level keys (preserved from 1.18.2-1.1.0 on-disk schema)
	public static final ConfigValue<Integer> LOCATOR_THREADS;
	public static final ConfigValue<Boolean> REMOVE_OFFER;

	// New in 1.18.2-1.3.0 — under [Features]
	public static final ConfigValue<Boolean> DOLPHIN_TREASURE_ENABLED;
	public static final ConfigValue<Boolean> EYE_OF_ENDER_ENABLED;
	public static final ConfigValue<Boolean> EXPLORATION_MAP_ENABLED;
	public static final ConfigValue<Boolean> LOCATE_COMMAND_ENABLED;
	public static final ConfigValue<Boolean> VILLAGER_TRADE_ENABLED;

	static {
		Builder builder = new Builder();

		LOCATOR_THREADS = builder
			.worldRestart()
			.comment(
				"The maximum number of threads in the async locator thread pool.",
				"There's no upper bound to this, however this should only be increased if you're experiencing",
				"simultaneous location lookups causing issues AND you have the hardware capable of handling",
				"the extra possible threads.",
				"The default of 1 should be suitable for most users."
			)
			.defineInRange("asyncLocatorThreads", 1, 1, Integer.MAX_VALUE);

		REMOVE_OFFER = builder
			.comment(
				"When a merchant's treasure map offer ends up not finding a feature location,",
				"whether the offer should be removed or marked as out of stock."
			)
			.define("removeMerchantInvalidMapOffer", false);

		builder.push("Features");
		builder.comment(
			"Per-feature toggles. Each one independently enables/disables the async-locate path for a",
			"specific game event. Disabling a feature falls back to vanilla synchronous behaviour for",
			"that path only; the rest of the mod continues to operate normally."
		);

		DOLPHIN_TREASURE_ENABLED = builder
			.comment("If true, enables asynchronous locating of structures for dolphin treasures.")
			.define("dolphinTreasureEnabled", true);

		EYE_OF_ENDER_ENABLED = builder
			.comment("If true, enables asynchronous locating of structures when Eyes Of Ender are thrown.")
			.define("eyeOfEnderEnabled", true);

		EXPLORATION_MAP_ENABLED = builder
			.comment("If true, enables asynchronous locating of structures for treasure / exploration maps found in chests.")
			.define("explorationMapEnabled", true);

		LOCATE_COMMAND_ENABLED = builder
			.comment("If true, enables asynchronous locating of structures for the /locate command.")
			.define("locateCommandEnabled", true);

		VILLAGER_TRADE_ENABLED = builder
			.comment("If true, enables asynchronous locating of structures for villager (cartographer) treasure-map trades.")
			.define("villagerTradeEnabled", true);

		builder.pop();

		SPEC = builder.build();
	}

	private AsyncLocatorConfig() {}
}
