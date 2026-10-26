package com.sandymandy.strut.renderer.gpu;

import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceReloader;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Identity-keyed cache of {@link GpuModel} per {@link BakedStrutModel}, baked on demand the first
 * time a model is drawn on the GPU path and reused by every instance and every frame after that
 * <p>
 * {@code StrutCache.MODELS} is <em>replaced wholesale</em>, not mutated, on a resource reload
 * (`StrutCache.java`'s own {@code reload}) - so a stale {@code BakedStrutModel} key here becomes
 * unreachable on its own once that happens, and identity keying means a reloaded copy of "the same"
 * model (same path, re-decoded from disk) bakes fresh rather than being confused for the old one.
 * What does not happen on its own is freeing the stale entry's GL objects - those leak unless this
 * cache's own reload listener explicitly deletes them, which is the entire reason this class needs
 * one at all rather than just being a bare map
 */
public final class GpuModelCache {
	private static final Reference2ObjectMap<BakedStrutModel, GpuModel> MODELS = new Reference2ObjectOpenHashMap<>();

	private GpuModelCache() {}

	/**
	 * Returns {@code model}'s {@link GpuModel}, baking it via {@code GpuModel#bake} the first time
	 * this identity is asked for
	 */
	public static GpuModel get(BakedStrutModel model) {
		return MODELS.computeIfAbsent(model, GpuModel::bake);
	}

	/**
	 * Registers this cache's own reload listener, mirroring {@code StrutClient}'s registration of
	 * {@code StrutCache}'s - depends on {@code StrutCache}'s own fabric id so its cleanup runs after
	 * {@code StrutCache.MODELS} has already been swapped to the new set, on the client resource
	 * manager's apply-stage executor. That executor is the same {@code gameExecutor} `StrutCache`'s
	 * own {@code reload} documents as running on the render thread - required here, since GL object
	 * deletion is only safe from the thread that owns the GL context
	 */
	public static void registerReloadListener() {
		ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES)
				.registerReloadListener(new IdentifiableResourceReloadListener() {
					@Override
					public Identifier getFabricId() {
						return StrutConstants.id("gpu_model_cache");
					}

					@Override
					public Collection<Identifier> getFabricDependencies() {
						return Collections.singletonList(StrutConstants.id("models_animations"));
					}

					@Override
					public CompletableFuture<Void> reload(ResourceReloader.Synchronizer synchronizer, ResourceManager manager,
														   Profiler prepareProfiler, Profiler applyProfiler,
														   Executor prepareExecutor, Executor applyExecutor) {
						return CompletableFuture.<Void>completedFuture(null)
								.thenCompose(synchronizer::whenPrepared)
								.thenRunAsync(GpuModelCache::clear, applyExecutor);
					}
				});
	}

	private static void clear() {
		for (GpuModel model : MODELS.values()) {
			model.close();
		}

		MODELS.clear();
	}
}
