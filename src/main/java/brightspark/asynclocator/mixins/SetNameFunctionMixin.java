package brightspark.asynclocator.mixins;

import brightspark.asynclocator.AsyncLocatorConfig;
import brightspark.asynclocator.AsyncLocatorMod;
import brightspark.asynclocator.logic.CommonLogic;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.functions.SetNameFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Intercepts the loot-table {@code SetName} function when it targets an in-progress
 * async-locator placeholder map.
 *
 * <p>The vanilla call sequence is:
 * <pre>
 *   ExplorationMapFunction#run  →  produces placeholder
 *   SetNameFunction#run         →  sets "Treasure Map" / "Buried Treasure Map" / etc.
 * </pre>
 * Without this mixin the placeholder's hover-name would be immediately overwritten by the loot
 * table's name (e.g. "Buried Treasure Map"), losing the user-visible "Locating..." indication.
 * With this mixin, the intended name is stashed in NBT and applied to the final map by
 * {@link CommonLogic#updateMap(ItemStack, net.minecraft.server.level.ServerLevel,
 * net.minecraft.core.BlockPos, int,
 * net.minecraft.world.level.saveddata.maps.MapDecoration.Type, Component)} once locating
 * completes.</p>
 *
 * <p>If {@link AsyncLocatorConfig#EXPLORATION_MAP_ENABLED} is {@code false}, the rename is
 * applied immediately, matching vanilla behaviour.</p>
 */
@Mixin(SetNameFunction.class)
public class SetNameFunctionMixin {
	@Redirect(
		method = "run",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/item/ItemStack;setHoverName(Lnet/minecraft/network/chat/Component;)Lnet/minecraft/world/item/ItemStack;"
		)
	)
	public ItemStack asynclocator$deferNameOnPendingMap(ItemStack stack, Component name) {
		if (AsyncLocatorConfig.EXPLORATION_MAP_ENABLED.get() && CommonLogic.isEmptyPendingMap(stack)) {
			AsyncLocatorMod.logDebug("Deferred SetName on pending async-locator map: {}", name.getString());
			CommonLogic.stashDeferredName(stack, name);
			return stack;
		}
		return stack.setHoverName(name);
	}
}
