package brightspark.asynclocator.logic;

import brightspark.asynclocator.mixins.MapItemAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Shared map-creation and update helpers used by every async-locate feature.
 *
 * <p>The "empty pending map" is the placeholder {@link Items#FILLED_MAP} returned to the game
 * synchronously while the async locate task is in flight. It is identified by an NBT marker
 * ({@link #PENDING_TAG_KEY}) rather than its hover-name string, so the check survives mods that
 * rename items in transit and is unambiguous even if the localisation key changes.</p>
 *
 * <p>If the loot table includes a {@code SetName} function (e.g.
 * {@code "name": "block.minecraft.shipwreck"}), its component is captured into
 * {@link #DEFERRED_NAME_TAG_KEY} on the placeholder so it can be re-applied to the final map
 * once locating completes. This avoids the upstream cache-by-{@code ItemStack} approach which
 * was fragile against stack copies and serialisation.</p>
 */
public final class CommonLogic {
	/** Localisation key for the in-progress hover-name tooltip. */
	public static final String MAP_HOVER_NAME_KEY = "asynclocator.map.locating";

	/** Top-level NBT key marking an in-progress async map. */
	public static final String PENDING_TAG_KEY = "asynclocator_pending";

	/**
	 * NBT key under which the deferred display-name component (from the loot table's
	 * {@code SetName} function) is stashed while the async task runs.
	 */
	public static final String DEFERRED_NAME_TAG_KEY = "asynclocator_deferred_name";

	private CommonLogic() {}

	/**
	 * Creates an empty placeholder {@link Items#FILLED_MAP} returned synchronously to the game
	 * while the async locate task is running. Carries the {@link #PENDING_TAG_KEY} NBT marker.
	 *
	 * @return the placeholder ItemStack
	 */
	public static ItemStack createEmptyMap() {
		ItemStack stack = new ItemStack(Items.FILLED_MAP);
		stack.setHoverName(new TranslatableComponent(MAP_HOVER_NAME_KEY));
		stack.addTagElement(PENDING_TAG_KEY, ByteTag.ONE);
		return stack;
	}

	/**
	 * Tests whether {@code stack} is a placeholder map produced by {@link #createEmptyMap()}
	 * whose async locate task has not yet completed.
	 *
	 * @param stack the stack to check (may be empty)
	 * @return {@code true} if it is an in-progress placeholder, {@code false} otherwise
	 */
	public static boolean isEmptyPendingMap(ItemStack stack) {
		return stack.is(Items.FILLED_MAP)
			&& stack.hasTag()
			&& stack.getTag().contains(PENDING_TAG_KEY);
	}

	/**
	 * Stashes a hover-name component on the placeholder for re-application once the async
	 * task completes. The component is serialised to NBT via {@link Component.Serializer}.
	 *
	 * @param stack the placeholder map (must be a pending map per {@link #isEmptyPendingMap})
	 * @param name  the component to defer
	 */
	public static void stashDeferredName(ItemStack stack, Component name) {
		if (!isEmptyPendingMap(stack) || name == null) return;
		String json = Component.Serializer.toJson(name);
		stack.addTagElement(DEFERRED_NAME_TAG_KEY, StringTag.valueOf(json));
	}

	/**
	 * Retrieves and clears a previously {@link #stashDeferredName stashed} hover-name component.
	 *
	 * @param stack the placeholder map
	 * @return the deferred component, or {@code null} if none was stashed
	 */
	public static Component popDeferredName(ItemStack stack) {
		if (!stack.hasTag()) return null;
		CompoundTag tag = stack.getTag();
		Tag deferred = tag.get(DEFERRED_NAME_TAG_KEY);
		if (deferred == null) return null;
		tag.remove(DEFERRED_NAME_TAG_KEY);
		return Component.Serializer.fromJson(deferred.getAsString());
	}

	/**
	 * Updates the map stack with the located feature data and clears the pending marker.
	 *
	 * @param mapStack        the map ItemStack to update (mutated in place)
	 * @param level           the server level
	 * @param pos             the located feature position
	 * @param scale           the map scale
	 * @param destinationType the map decoration type for the feature
	 */
	public static void updateMap(
		ItemStack mapStack,
		ServerLevel level,
		BlockPos pos,
		int scale,
		MapDecoration.Type destinationType
	) {
		updateMap(mapStack, level, pos, scale, destinationType, (Component) null);
	}

	/**
	 * Updates the map stack with the located feature data, optionally setting a hover name from
	 * a translation key, and clears the pending marker.
	 *
	 * @param mapStack        the map ItemStack to update (mutated in place)
	 * @param level           the server level
	 * @param pos             the located feature position
	 * @param scale           the map scale
	 * @param destinationType the map decoration type for the feature
	 * @param displayName     translation key for the hover name, or {@code null} to use a
	 *                        previously stashed deferred name if any
	 */
	public static void updateMap(
		ItemStack mapStack,
		ServerLevel level,
		BlockPos pos,
		int scale,
		MapDecoration.Type destinationType,
		String displayName
	) {
		updateMap(mapStack, level, pos, scale, destinationType,
			displayName != null ? new TranslatableComponent(displayName) : null);
	}

	/**
	 * Updates the map stack with the located feature data, optionally setting a hover-name
	 * component, and clears the pending marker.
	 *
	 * <p>If {@code displayName} is {@code null}, looks up a previously stashed deferred name
	 * (set by the loot-table {@code SetName} function via {@code SetNameFunctionMixin}) and
	 * applies that instead.</p>
	 *
	 * @param mapStack        the map ItemStack to update (mutated in place)
	 * @param level           the server level
	 * @param pos             the located feature position
	 * @param scale           the map scale
	 * @param destinationType the map decoration type for the feature
	 * @param displayName     hover-name component, or {@code null} to use deferred name if any
	 */
	public static void updateMap(
		ItemStack mapStack,
		ServerLevel level,
		BlockPos pos,
		int scale,
		MapDecoration.Type destinationType,
		Component displayName
	) {
		MapItemAccess.callCreateAndStoreSavedData(
			mapStack, level, pos.getX(), pos.getZ(), scale, true, true, level.dimension()
		);
		MapItem.renderBiomePreviewMap(level, mapStack);
		MapItemSavedData.addTargetDecoration(mapStack, pos, "+", destinationType);

		Component finalName = displayName != null ? displayName : popDeferredName(mapStack);
		if (finalName != null) {
			mapStack.setHoverName(finalName);
		} else if (mapStack.hasTag()) {
			// No name to set — clear the "Locating..." placeholder so vanilla "Filled Map" shows
			CompoundTag tag = mapStack.getTag();
			if (tag.contains("display")) {
				CompoundTag display = tag.getCompound("display");
				display.remove("Name");
				if (display.isEmpty()) tag.remove("display");
			}
		}
		mapStack.removeTagKey(PENDING_TAG_KEY);
	}

	/**
	 * Broadcasts slot changes to every player that has the chest container currently open.
	 * No-op when the BlockEntity isn't a {@link ChestBlockEntity}.
	 *
	 * @param level the server level
	 * @param be    the source block entity
	 */
	public static void broadcastChestChanges(ServerLevel level, BlockEntity be) {
		if (!(be instanceof ChestBlockEntity)) return;

		for (var player : level.players()) {
			AbstractContainerMenu container = player.containerMenu;
			if (container instanceof ChestMenu chestMenu && chestMenu.getContainer() == be) {
				chestMenu.broadcastChanges();
			}
		}
	}
}
