package com.sandymandy.strut.model;

import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.StrutCube;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutPoly;
import com.sandymandy.strut.cache.object.StrutQuad;
import com.sandymandy.strut.cache.object.StrutVertex;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.util.RenderUtil;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceReloader;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.profiler.Profiler;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Each {@link BakedStrutModel}'s
 * rest-pose local bounding box, computed once and cached by identity, exactly like
 * {@link com.sandymandy.strut.renderer.gpu.GpuModelCache} but holding a plain {@link Box} rather
 * than a GL object (so this cache's own reload listener just clears the map, nothing to close)
 * <p>
 * Exists to fix a real correctness gap: vanilla's own frustum cull
 * (`EntityRenderer#shouldRender` → {@code Entity#getVisibilityBoundingBox()}) only ever knows an
 * entity's hitbox, so a model whose visible geometry reaches well past it (wings, tails, long
 * weapons) can pop out of view while still partly on screen. This class's own bounds feed
 * {@code EntityVisibilityBoundsMixin}, which unions them onto the vanilla box for any
 * {@code StrutAnimatable} entity
 * <p>
 * Computed as follows: every bone's rest-pose
 * model-space transform ({@link PosePalette#compute} against a {@link Pose} seeded from each bone's
 * {@link GroupBoneDefinition#getInitialRotX() initial rotation} - the same seeding
 * {@code ModelInstance#rebindIfNeeded} uses for a freshly bound instance) applied to that bone's own
 * cube/mesh corners (in the same bone-LOCAL space {@code GpuModel#bake} already establishes, reusing
 * {@link RenderUtil#applyPivotRotation} rather than a third reimplementation of cube/mesh transform
 * maths). Deliberately includes skinned meshes too (unlike {@code GpuModel#bake}, which excludes
 * them because the GPU path can't draw them) - their bind-pose extent is still a reasonable
 * approximation for culling purposes even though this class never accounts for skin deformation
 */
public final class ModelBounds {
	/**
	 * Fixed padding added to every rest-pose box, on top of vanilla's own {@code expand(0.5)} in
	 * {@code EntityRenderer#shouldRender} - covers ordinary animation motion (limb swings, idle
	 * breathing, etc.) without needing to track an animated envelope per model. A flat constant
	 * rather than a percentage of the model's own size, since a small model's animation amplitude
	 * isn't necessarily small - unvalidated beyond reasoning; a heuristic to revisit if a live test
	 * turns up a model whose motion still exceeds it
	 */
	private static final double ANIMATION_PADDING_BLOCKS = 1.0;

	/**
	 * Sentinel for "this model has no group-bone geometry at all" (an armature-only model, or a
	 * model with zero cubes/meshes), cached like any other result rather than recomputed every call -
	 * distinguished from a real, valid zero-size box by reference identity, checked in
	 * {@link #restPoseBoundsFor}
	 */
	private static final Box NO_BOUNDS = new Box(0, 0, 0, 0, 0, 0);

	private static final Reference2ObjectMap<BakedStrutModel, Box> BOUNDS = new Reference2ObjectOpenHashMap<>();

	private ModelBounds() {}

	/**
	 * {@code model}'s rest-pose local bounding box, in the same model-space convention
	 * {@link PosePalette} and the GPU render path already share (block units, model origin at the
	 * entity's own root) - {@code null} if the model has no group-bone geometry to derive one from
	 */
	@Nullable
	public static Box restPoseBoundsFor(BakedStrutModel model) {
		Box box = BOUNDS.computeIfAbsent(model, ModelBounds::compute);

		return box == NO_BOUNDS ? null : box;
	}

	private static Box compute(BakedStrutModel model) {
		GroupBoneDefinition[] definitions = model.flatBones();

		if (definitions.length == 0)
			return NO_BOUNDS;

		Pose restPose = new Pose(definitions.length);

		for (int i = 0; i < definitions.length; i++) {
			GroupBoneDefinition definition = definitions[i];

			restPose.setRotation(i, definition.getInitialRotX(), definition.getInitialRotY(), definition.getInitialRotZ());
		}

		Matrix4f[] palette = new Matrix4f[definitions.length];

		for (int i = 0; i < palette.length; i++) {
			palette[i] = new Matrix4f();
		}

		PosePalette.compute(model, restPose, palette);

		Matrix4f elementMatrix = new Matrix4f();
		Matrix3f elementNormalMatrix = new Matrix3f();
		Vector4f transformed = new Vector4f();
		double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
		boolean anyGeometry = false;

		for (int i = 0; i < definitions.length; i++) {
			GroupBoneDefinition bone = definitions[i];
			Matrix4f boneMatrix = palette[i];

			for (StrutCube cube : bone.getCubes()) {
				elementMatrix.identity();
				elementNormalMatrix.identity();
				RenderUtil.applyPivotRotation(elementMatrix, elementNormalMatrix, cube.pivot(), cube.rotation());

				for (StrutQuad quad : cube.quads()) {
					if (quad == null)
						continue;

					for (StrutVertex vertex : quad.vertices()) {
						anyGeometry = true;

						transform(elementMatrix, boneMatrix, vertex.position(), transformed);

						minX = Math.min(minX, transformed.x());
						minY = Math.min(minY, transformed.y());
						minZ = Math.min(minZ, transformed.z());
						maxX = Math.max(maxX, transformed.x());
						maxY = Math.max(maxY, transformed.y());
						maxZ = Math.max(maxZ, transformed.z());
					}
				}
			}

			for (StrutMesh mesh : bone.getMeshes()) {
				elementMatrix.identity();
				elementNormalMatrix.identity();
				RenderUtil.applyPivotRotation(elementMatrix, elementNormalMatrix, mesh.pivot(), mesh.rotation());

				for (StrutPoly poly : mesh.polys()) {
					for (StrutVertex vertex : poly.vertices()) {
						anyGeometry = true;

						transform(elementMatrix, boneMatrix, vertex.position(), transformed);

						minX = Math.min(minX, transformed.x());
						minY = Math.min(minY, transformed.y());
						minZ = Math.min(minZ, transformed.z());
						maxX = Math.max(maxX, transformed.x());
						maxY = Math.max(maxY, transformed.y());
						maxZ = Math.max(maxZ, transformed.z());
					}
				}
			}
		}

		if (!anyGeometry)
			return NO_BOUNDS;

		return new Box(minX, minY, minZ, maxX, maxY, maxZ).expand(ANIMATION_PADDING_BLOCKS);
	}

	/** {@code elementMatrix} (the cube/mesh's own local pivot rotation), then {@code boneMatrix} (that bone's rest-pose model-space transform) - the same two-stage transform {@code GpuModel}'s own {@code appendFace} applies, just without the shader deferring the second stage to per-instance data */
	private static void transform(Matrix4f elementMatrix, Matrix4f boneMatrix, Vector3f localPosition, Vector4f dest) {
		elementMatrix.transform(localPosition.x(), localPosition.y(), localPosition.z(), 1.0f, dest);
		boneMatrix.transform(dest);
	}

	/**
	 * Registers this cache's own reload listener - unlike {@link com.sandymandy.strut.renderer.gpu.GpuModelCache},
	 * there's no GL object to close, just the map to drop so a stale {@code BakedStrutModel} key
	 * doesn't accumulate forever across reloads
	 */
	public static void registerReloadListener() {
		ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES)
				.registerReloadListener(new IdentifiableResourceReloadListener() {
					@Override
					public Identifier getFabricId() {
						return StrutConstants.id("model_bounds_cache");
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
								.thenRunAsync(BOUNDS::clear, applyExecutor);
					}
				});
	}
}
