package brightspark.asynclocator;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredStructureFeature;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.util.thread.SidedThreadGroups;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Entry point for queueing asynchronous structure-location tasks.
 *
 * <p>A single fixed-size thread pool (sized by {@link AsyncLocatorConfig#LOCATOR_THREADS}) is
 * started in {@link net.minecraftforge.event.server.ServerAboutToStartEvent} and shut down
 * cleanly in {@link net.minecraftforge.event.server.ServerStoppingEvent}. Each submitted task
 * wraps {@link ServerLevel#findNearestMapFeature} with full exception isolation: a thrown
 * exception is logged at WARN, the result future is completed with {@code null} so downstream
 * map-update logic invalidates the placeholder rather than leaking it, and the worker thread
 * continues to service further submissions.</p>
 *
 * <p>Use {@link LocateTask#thenOnServerThread(Consumer)} for any callback that touches game
 * state — the worker thread is not the server thread and the locate task is otherwise allowed
 * to run while the main thread ticks.</p>
 */
public final class AsyncLocator {
	/** Maximum time to wait for in-flight tasks to drain during a clean server stop. */
	private static final long SHUTDOWN_GRACE_SECONDS = 10L;

	private static volatile ExecutorService executor = null;
	/** Pool sequence number — used in thread names when the executor is restarted. */
	private static final AtomicInteger POOL_SEQ = new AtomicInteger(1);

	private AsyncLocator() {}

	// ─── Lifecycle ─────────────────────────────────────────────────────────────

	private static synchronized void startExecutor() {
		stopExecutor(); // safe no-op if already stopped

		int threads = AsyncLocatorConfig.LOCATOR_THREADS.get();
		AsyncLocatorMod.logInfo("Starting async-locator executor with {} thread(s)", threads);

		int poolId = POOL_SEQ.getAndIncrement();
		executor = Executors.newFixedThreadPool(threads, new AsyncLocatorThreadFactory(poolId));
	}

	private static synchronized void stopExecutor() {
		ExecutorService current = executor;
		if (current == null) return;
		executor = null;

		AsyncLocatorMod.logInfo("Shutting down async-locator executor");
		current.shutdown();
		try {
			if (!current.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
				AsyncLocatorMod.logWarn(
					"Async-locator executor did not terminate within {}s; forcing shutdown",
					SHUTDOWN_GRACE_SECONDS
				);
				current.shutdownNow();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			current.shutdownNow();
		}
	}

	static void handleServerAboutToStartEvent(ServerAboutToStartEvent ignored) {
		startExecutor();
	}

	static void handleServerStoppingEvent(ServerStoppingEvent ignored) {
		stopExecutor();
	}

	// ─── Public API ────────────────────────────────────────────────────────────

	/**
	 * Queues a task to locate the nearest structure matching {@code structureTag} via
	 * {@link ServerLevel#findNearestMapFeature(TagKey, BlockPos, int, boolean)}.
	 *
	 * @param level                the level to search in
	 * @param structureTag         the structure tag to match
	 * @param pos                  origin of the search
	 * @param searchRadius         radius in chunks
	 * @param skipKnownStructures  whether to skip structures already known to the chunk system
	 * @return a {@link LocateTask} whose future completes with the located {@link BlockPos} or
	 *         {@code null} if none was found / the search threw
	 */
	public static LocateTask<BlockPos> locate(
		ServerLevel level,
		TagKey<ConfiguredStructureFeature<?, ?>> structureTag,
		BlockPos pos,
		int searchRadius,
		boolean skipKnownStructures
	) {
		ExecutorService current = executor;
		if (current == null) {
			throw new IllegalStateException(
				"AsyncLocator executor is not running. locate() called outside the server lifecycle."
			);
		}

		AsyncLocatorMod.logDebug(
			"Queueing locate task for {} in {} around {} within {} chunks",
			structureTag, level, pos, searchRadius
		);
		CompletableFuture<BlockPos> result = new CompletableFuture<>();
		Future<?> task = current.submit(() -> safelyLocate(
			result, () -> level.findNearestMapFeature(structureTag, pos, searchRadius, skipKnownStructures),
			structureTag, level, pos, searchRadius
		));
		return new LocateTask<>(level.getServer(), result, task);
	}

	/**
	 * Queues a task to locate the nearest structure matching {@code structureSet} via
	 * {@link ChunkGenerator#findNearestMapFeature(ServerLevel, HolderSet, BlockPos, int, boolean)}.
	 *
	 * @return a {@link LocateTask} whose future completes with a (pos, holder) pair, or
	 *         {@code null} if none was found / the search threw
	 */
	public static LocateTask<Pair<BlockPos, Holder<ConfiguredStructureFeature<?, ?>>>> locate(
		ServerLevel level,
		HolderSet<ConfiguredStructureFeature<?, ?>> structureSet,
		BlockPos pos,
		int searchRadius,
		boolean skipKnownStructures
	) {
		ExecutorService current = executor;
		if (current == null) {
			throw new IllegalStateException(
				"AsyncLocator executor is not running. locate() called outside the server lifecycle."
			);
		}

		AsyncLocatorMod.logDebug(
			"Queueing locate task for {} in {} around {} within {} chunks",
			structureSet, level, pos, searchRadius
		);
		CompletableFuture<Pair<BlockPos, Holder<ConfiguredStructureFeature<?, ?>>>> result = new CompletableFuture<>();
		Future<?> task = current.submit(() -> safelyLocate(
			result,
			() -> level.getChunkSource().getGenerator()
				.findNearestMapFeature(level, structureSet, pos, searchRadius, skipKnownStructures),
			structureSet, level, pos, searchRadius
		));
		return new LocateTask<>(level.getServer(), result, task);
	}

	// ─── Internals ─────────────────────────────────────────────────────────────

	/**
	 * Wraps a locate call with logging + exception isolation. Always completes {@code result}
	 * exactly once, even if the underlying search throws or returns null.
	 */
	private static <T> void safelyLocate(
		CompletableFuture<T> result,
		java.util.function.Supplier<T> search,
		Object structureRef,
		ServerLevel level,
		BlockPos pos,
		int searchRadius
	) {
		try {
			AsyncLocatorMod.logInfo(
				"Locating {} in {} around {} within {} chunks", structureRef, level, pos, searchRadius
			);
			T found = search.get();
			if (found == null) {
				AsyncLocatorMod.logInfo("No {} found", structureRef);
			} else {
				AsyncLocatorMod.logInfo("Located {} → {}", structureRef, found);
			}
			result.complete(found);
		} catch (Throwable t) {
			AsyncLocatorMod.logWarn(
				"Locate task for {} threw: {} — completing with null so the placeholder invalidates",
				structureRef, t.toString()
			);
			AsyncLocatorMod.logDebug("Locate stack trace:", t);
			result.complete(null);
		}
	}

	/** Names threads {@code asynclocator-<pool>-thread-<n>} and installs an exception handler. */
	private static final class AsyncLocatorThreadFactory implements ThreadFactory {
		private final AtomicInteger threadNum = new AtomicInteger(1);
		private final String namePrefix;

		AsyncLocatorThreadFactory(int poolId) {
			this.namePrefix = "asynclocator-" + poolId + "-thread-";
		}

		@Override
		public Thread newThread(@NotNull Runnable r) {
			Thread t = new Thread(SidedThreadGroups.SERVER, r, namePrefix + threadNum.getAndIncrement());
			t.setDaemon(true);
			t.setUncaughtExceptionHandler((thread, ex) ->
				AsyncLocatorMod.logWarn("Uncaught exception in {}: {}", thread.getName(), ex.toString())
			);
			return t;
		}
	}

	// ─── LocateTask ────────────────────────────────────────────────────────────

	/**
	 * Handle for an async locate task.
	 *
	 * <p>{@link #completableFuture} carries the result; {@link #taskFuture} is the worker-thread
	 * handle and can be used to cancel the underlying search.</p>
	 *
	 * @param <T> the result type ({@code BlockPos} or {@code Pair<BlockPos, Holder<…>>})
	 */
	public record LocateTask<T>(
		MinecraftServer server,
		CompletableFuture<T> completableFuture,
		Future<?> taskFuture
	) {
		/**
		 * Attaches a callback to the result. The callback runs on the worker thread; do NOT
		 * touch game state from it — use {@link #thenOnServerThread(Consumer)} instead.
		 *
		 * <p>Exceptions thrown by the action are logged at WARN rather than silently dropped
		 * by {@link CompletableFuture}.</p>
		 */
		public LocateTask<T> then(Consumer<T> action) {
			completableFuture.whenComplete((value, ex) -> {
				if (ex != null) {
					AsyncLocatorMod.logWarn("AsyncLocator.then callback never invoked: {}", ex.toString());
					return;
				}
				try {
					action.accept(value);
				} catch (Throwable t) {
					AsyncLocatorMod.logWarn("AsyncLocator.then callback threw: {}", t.toString());
					AsyncLocatorMod.logDebug("then() callback stack trace:", t);
				}
			});
			return this;
		}

		/**
		 * Attaches a callback that is queued for execution on the main server thread.
		 *
		 * <p>The callback is wrapped in a try/catch so a thrown exception is logged at WARN
		 * rather than being silently dropped by the executor's submission queue.</p>
		 */
		public LocateTask<T> thenOnServerThread(Consumer<T> action) {
			completableFuture.whenComplete((value, ex) -> {
				if (ex != null) {
					AsyncLocatorMod.logWarn("AsyncLocator.thenOnServerThread callback never invoked: {}", ex.toString());
					return;
				}
				server.submit(() -> {
					try {
						action.accept(value);
					} catch (Throwable t) {
						AsyncLocatorMod.logWarn("AsyncLocator.thenOnServerThread callback threw: {}", t.toString());
						AsyncLocatorMod.logDebug("thenOnServerThread() callback stack trace:", t);
					}
				});
			});
			return this;
		}

		/**
		 * Cancels both the worker-thread search and the result future. Safe to call multiple
		 * times.
		 */
		public void cancel() {
			taskFuture.cancel(true);
			completableFuture.cancel(false);
		}
	}
}
