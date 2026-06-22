package brightspark.asynclocator.mixins;

import brightspark.asynclocator.AsyncLocatorConfig;
import brightspark.asynclocator.AsyncLocatorMod;
import brightspark.asynclocator.logic.CommonLogic;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents players from picking up an in-progress async-locator placeholder map.
 *
 * <p>If a player yanks the placeholder out of the source container before the locate task
 * completes, the {@code ItemStack} reference held by the async task points at a stack that is
 * no longer in any container slot. The locate result is silently dropped (and, on the original
 * 1.18.2-1.1.0, the "Couldn't find item handler capability" WARN fires).</p>
 *
 * <p>This mixin makes the placeholder un-pickable while {@link CommonLogic#PENDING_TAG_KEY} is
 * present. The slot becomes pickable as soon as the async task finishes and clears the tag.</p>
 */
@Mixin(Slot.class)
public abstract class SlotMixin {
	@Shadow
	public abstract ItemStack getItem();

	@Inject(
		method = "mayPickup",
		at = @At(value = "HEAD"),
		cancellable = true
	)
	public void asynclocator$preventPickupOfPendingMap(Player player, CallbackInfoReturnable<Boolean> cir) {
		// Allow creative-mode players to bypass the lock so admins can manually clear a stuck
		// placeholder map (e.g. after a misbehaving structure tag).
		if (player.isCreative()) return;

		if (AsyncLocatorConfig.EXPLORATION_MAP_ENABLED.get() && CommonLogic.isEmptyPendingMap(getItem())) {
			AsyncLocatorMod.logDebug("Blocked pickup of pending async-locator map by {}", player.getName().getString());
			cir.setReturnValue(false);
		}
	}
}
