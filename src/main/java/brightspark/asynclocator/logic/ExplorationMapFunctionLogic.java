package brightspark.asynclocator.logic;

import brightspark.asynclocator.AsyncLocator;
import brightspark.asynclocator.AsyncLocatorMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.feature.ConfiguredStructureFeature;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.function.BiConsumer;

// TODO: Need to test this
public class ExplorationMapFunctionLogic {
	private ExplorationMapFunctionLogic() {}

	public static void invalidateMap(ItemStack mapStack, ServerLevel level, BlockPos pos) {
		// Vonix-Refined fix: walk player containers/inventories FIRST.
		// This avoids the BlockEntity item-handler capability lookup, which spams
		// "Couldn't find item handler capability on chest" WARNs and contributes
		// to chunk-load deadlocks when the source chest is a Lootr inventory
		// (Lootr exposes no capability by design — see
		// https://github.com/LootrMinecraft/Lootr/issues/793).
		// Fallback to the legacy BE-capability path only if the map cannot be
		// located via any online player's open container or inventory.
		if (tryReplaceMapInPlayerSlots(mapStack, level, new ItemStack(Items.MAP))) return;

		handleUpdateMapInChest(mapStack, level, pos, (handler, slot) -> {
			if (handler instanceof IItemHandlerModifiable modifiableHandler) {
				modifiableHandler.setStackInSlot(slot, new ItemStack(Items.MAP));
			} else {
				handler.extractItem(slot, Item.MAX_STACK_SIZE, false);
				handler.insertItem(slot, new ItemStack(Items.MAP), false);
			}
		});
	}

	public static void updateMap(
		ItemStack mapStack,
		ServerLevel level,
		BlockPos pos,
		int scale,
		MapDecoration.Type destinationType,
		BlockPos invPos
	) {
		CommonLogic.updateMap(mapStack, level, pos, scale, destinationType);

		// Vonix-Refined fix: the map ItemStack instance is the same one the
		// player sees in their container slot (reference equality holds on
		// 1.18.2), so we just need to nudge the menu to broadcast the updated
		// NBT to clients. Works regardless of whether the source block entity
		// exposes an item-handler capability (Lootr chests do not).
		if (broadcastMapInPlayerSlots(mapStack, level)) return;

		// Shouldn't need to set the stack in its slot again, as we're modifying the same instance
		handleUpdateMapInChest(mapStack, level, invPos, (handler, slot) -> {});
	}

	/**
	 * Walks every online player's open container and inventory looking for
	 * {@code mapStack} by reference equality. If found, replaces the slot with
	 * {@code replacement} and broadcasts the change.
	 *
	 * <p>This is the first-line path for the map-invalidation callback because
	 * it is compatible with mods that hold their loot inventory off the world
	 * (e.g. Lootr), which expose no item-handler capability on the source
	 * block entity.</p>
	 *
	 * @return {@code true} if the map was found and replaced, {@code false} otherwise
	 */
	public static boolean tryReplaceMapInPlayerSlots(
		ItemStack mapStack, ServerLevel level, ItemStack replacement
	) {
		MinecraftServer server = level.getServer();
		if (server == null) return false;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			AbstractContainerMenu menu = player.containerMenu;
			if (menu != null) {
				// Open container slots (chest, hopper, dispenser, etc.)
				for (int i = 0; i < menu.slots.size(); i++) {
					Slot slot = menu.getSlot(i);
					if (slot.getItem() == mapStack) {
						slot.set(replacement);
						menu.broadcastChanges();
						AsyncLocatorMod.logInfo(
							"Replaced async-locator map via {}'s open container slot {}",
							player.getName().getString(), i
						);
						return true;
					}
				}
				// Carried stack (player is mid-drag with the placeholder on the cursor)
				if (menu.getCarried() == mapStack) {
					menu.setCarried(replacement);
					menu.broadcastChanges();
					AsyncLocatorMod.logInfo(
						"Replaced async-locator map on {}'s cursor", player.getName().getString()
					);
					return true;
				}
			}
			Inventory inv = player.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				if (inv.getItem(i) == mapStack) {
					inv.setItem(i, replacement);
					if (menu != null) menu.broadcastChanges();
					AsyncLocatorMod.logInfo(
						"Replaced async-locator map in {}'s inventory slot {}",
						player.getName().getString(), i
					);
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Walks every online player's open container and inventory looking for
	 * {@code mapStack} by reference equality. If found, triggers a slot-changed
	 * broadcast so the updated NBT is sent to all watching clients.
	 *
	 * @return {@code true} if the map was found and broadcast, {@code false} otherwise
	 */
	public static boolean broadcastMapInPlayerSlots(ItemStack mapStack, ServerLevel level) {
		MinecraftServer server = level.getServer();
		if (server == null) return false;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			AbstractContainerMenu menu = player.containerMenu;
			if (menu != null) {
				for (int i = 0; i < menu.slots.size(); i++) {
					Slot slot = menu.getSlot(i);
					if (slot.getItem() == mapStack) {
						slot.set(mapStack); // same instance, triggers slot-changed bookkeeping
						menu.broadcastChanges();
						AsyncLocatorMod.logInfo(
							"Broadcast updated async-locator map via {}'s open container slot {}",
							player.getName().getString(), i
						);
						return true;
					}
				}
				if (menu.getCarried() == mapStack) {
					menu.broadcastChanges();
					AsyncLocatorMod.logInfo(
						"Broadcast updated async-locator map on {}'s cursor", player.getName().getString()
					);
					return true;
				}
			}
			Inventory inv = player.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				if (inv.getItem(i) == mapStack) {
					inv.setChanged();
					AsyncLocatorMod.logInfo(
						"Broadcast updated async-locator map in {}'s inventory slot {}",
						player.getName().getString(), i
					);
					return true;
				}
			}
		}
		return false;
	}

	public static void handleUpdateMapInChest(
		ItemStack mapStack,
		ServerLevel level,
		BlockPos invPos,
		BiConsumer<IItemHandler, Integer> handleSlotFound
	) {
		BlockEntity be = level.getBlockEntity(invPos);
		if (be != null) {
			be.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY).resolve().ifPresentOrElse(
				itemHandler -> {
					for (int i = 0; i < itemHandler.getSlots(); i++) {
						ItemStack slotStack = itemHandler.getStackInSlot(i);
						if (slotStack == mapStack) {
							handleSlotFound.accept(itemHandler, i);
							CommonLogic.broadcastChestChanges(level, be);
							return;
						}
					}
				},
				// Demoted from WARN to DEBUG: with the player-slot fallback running
				// first, this path being reached for capability-less BEs (Lootr et al.)
				// is no longer a failure mode worth spamming the log over.
				() -> AsyncLocatorMod.logDebug(
					"No item-handler capability on chest {} at {} (likely Lootr or similar); " +
						"player-slot fallback was attempted first.",
					be.getClass().getSimpleName(), invPos
				)
			);
		} else {
			AsyncLocatorMod.logDebug(
				"No block entity on chest {} at {} (chunk likely unloaded); " +
					"player-slot fallback was attempted first.",
				level.getBlockState(invPos), invPos
			);
		}
	}

	public static void handleLocationFound(
		ItemStack mapStack,
		ServerLevel level,
		BlockPos pos,
		int scale,
		MapDecoration.Type destinationType,
		BlockPos invPos
	) {
		if (pos == null) {
			AsyncLocatorMod.logInfo("No location found - invalidating map stack");
			invalidateMap(mapStack, level, invPos);
		} else {
			AsyncLocatorMod.logInfo("Location found - updating treasure map in chest");
			updateMap(mapStack, level, pos, scale, destinationType, invPos);
		}
	}

	public static ItemStack updateMapAsync(
		ServerLevel level,
		BlockPos blockPos,
		int scale,
		int searchRadius,
		boolean skipKnownStructures,
		MapDecoration.Type destinationType,
		TagKey<ConfiguredStructureFeature<?, ?>> destination
	) {
		ItemStack mapStack = CommonLogic.createEmptyMap();
		AsyncLocator.locate(level, destination, blockPos, searchRadius, skipKnownStructures)
			.thenOnServerThread(pos -> handleLocationFound(mapStack, level, pos, scale, destinationType, blockPos));
		return mapStack;
	}
}
