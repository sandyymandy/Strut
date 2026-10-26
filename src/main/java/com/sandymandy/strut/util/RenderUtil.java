package com.sandymandy.strut.util;

import net.minecraft.client.util.GlfwUtil;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import it.unimi.dsi.fastutil.ints.IntIntImmutablePair;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.math.Direction;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import com.sandymandy.strut.cache.object.StrutCube;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.model.StrutModel;
import com.sandymandy.strut.renderer.StrutRenderer;
import com.sandymandy.strut.renderer.StrutReplacedEntityRenderer;

/**
 * Helper class for various methods and functions useful while rendering
 */
public final class RenderUtil {
	/**
	 * These bone-driven matrix helpers all read {@link GroupBoneHandle}'s {@code getRender*} accessors,
	 * not its plain ones - see {@link com.sandymandy.strut.animation.state.ModelInstance#renderedPose()} -
	 * so that everything drawn for a bone uses the same interpolated, partial-tick-smoothed pose,
	 * per the design: "visible geometry and attachments use exactly the same
	 * interpolated pose"
	 */
	public static void translateMatrixToBone(MatrixStack poseStack, GroupBoneHandle bone) {
		poseStack.translate(-bone.getRenderPosX() / 16f, bone.getRenderPosY() / 16f, bone.getRenderPosZ() / 16f);
	}

	public static void rotateMatrixAroundBone(MatrixStack poseStack, GroupBoneHandle bone) {
		RenderUtil.optionalRotateZYX(poseStack, bone.getRenderRotZ(), bone.getRenderRotY(), bone.getRenderRotX());
	}

	public static void rotateMatrixAroundCube(MatrixStack poseStack, StrutCube cube) {
		Vec3d rotation = cube.rotation();

		optionalRotateZYX(poseStack, rotation.getZ(), rotation.getY(), rotation.getX());
	}

	/**
	 * Applies a pivot-centred rotation directly to {@code pose} and {@code normalPose}, without
	 * touching a {@link MatrixStack} at all - the Matrix4f/Matrix3f equivalent of
	 * {@link #translateToPivotPoint}, {@link #optionalRotateZYX} and
	 * {@link #translateAwayFromPivotPoint} combined, for the two hot per-cube/per-mesh call sites
	 * ({@code StrutRenderer#renderCube}/{@code renderMesh}) that can no
	 * longer afford a {@code MatrixStack} push just to isolate one cube or mesh's own transform.
	 * <p>
	 * Skips the whole pivot dance - not just the rotation - when {@code rotation} is exactly zero:
	 * translating to a pivot, applying no rotation, and translating back is a no-op, and zero
	 * rotation is the common case for the overwhelming majority of cubes in a real model. Uses one
	 * combined ZYX quaternion rotation rather than three separate axis multiplies when more than
	 * one component is non-zero, mirroring {@link #optionalRotateZYX}'s own reasoning
	 */
	public static void applyPivotRotation(Matrix4f pose, Matrix3f normalPose, Vec3d pivot, Vec3d rotation) {
		double z = rotation.getZ();
		double y = rotation.getY();
		double x = rotation.getX();

		if (z == 0 && y == 0 && x == 0)
			return;

		float pivotX = (float)(pivot.getX() / 16f);
		float pivotY = (float)(pivot.getY() / 16f);
		float pivotZ = (float)(pivot.getZ() / 16f);

		pose.translate(pivotX, pivotY, pivotZ);

		if (x == 0 && y == 0) {
			pose.rotateZ((float)z);
			normalPose.rotateZ((float)z);
		}
		else if (x == 0 && z == 0) {
			pose.rotateY((float)y);
			normalPose.rotateY((float)y);
		}
		else if (y == 0 && z == 0) {
			pose.rotateX((float)x);
			normalPose.rotateX((float)x);
		}
		else {
			pose.rotateZYX((float)z, (float)y, (float)x);
			normalPose.rotateZYX((float)z, (float)y, (float)x);
		}

		pose.translate(-pivotX, -pivotY, -pivotZ);
	}

	/**
	 * The outward normal of a wound quad, by Newell's method
	 * <p>
	 * Newell rather than a single cross product because a triangle is baked as a degenerate quad
	 * with two coincident corners, and the one cross product a naive implementation picks there
	 * may be the zero vector - which renders black. On a triangulated model that is every face
	 * <p>
	 * Shared by the bake and by skinned rendering so the two cannot disagree about which way a
	 * face points; a deformed mesh whose normals were derived differently from its rest pose
	 * flickers between lighting states as it animates
	 *
	 * @param dest Receives the normalised result, and is returned
	 */
	public static Vector3f newellNormal(Vector3f a, Vector3f b, Vector3f c, Vector3f d, Vector3f dest) {
		dest.set(0, 0, 0);

		accumulateNewell(a, b, dest);
		accumulateNewell(b, c, dest);
		accumulateNewell(c, d, dest);
		accumulateNewell(d, a, dest);

		return dest.lengthSquared() > 1.0e-12f ? dest.normalize() : dest.set(0, 1, 0);
	}

	private static void accumulateNewell(Vector3f current, Vector3f next, Vector3f dest) {
		dest.add((current.y - next.y) * (current.z + next.z),
				(current.z - next.z) * (current.x + next.x),
				(current.x - next.x) * (current.y + next.y));
	}

	public static void rotateMatrixAroundMesh(MatrixStack poseStack, StrutMesh mesh) {
		Vec3d rotation = mesh.rotation();

		optionalRotateZYX(poseStack, rotation.getZ(), rotation.getY(), rotation.getX());
	}

	public static void scaleMatrixForBone(MatrixStack poseStack, GroupBoneHandle bone) {
		poseStack.scale(bone.getRenderScaleX(), bone.getRenderScaleY(), bone.getRenderScaleZ());
	}

	public static void translateToPivotPoint(MatrixStack poseStack, StrutCube cube) {
		Vec3d pivot = cube.pivot();
		poseStack.translate(pivot.getX() / 16f, pivot.getY() / 16f, pivot.getZ() / 16f);
	}

	public static void translateToPivotPoint(MatrixStack poseStack, StrutMesh mesh) {
		Vec3d pivot = mesh.pivot();

		poseStack.translate(pivot.getX() / 16f, pivot.getY() / 16f, pivot.getZ() / 16f);
	}

	public static void translateToPivotPoint(MatrixStack poseStack, GroupBoneHandle bone) {
		poseStack.translate(bone.getRenderPivotX() / 16f, bone.getRenderPivotY() / 16f, bone.getRenderPivotZ() / 16f);
	}

	public static void translateAwayFromPivotPoint(MatrixStack poseStack, StrutCube cube) {
		Vec3d pivot = cube.pivot();

		poseStack.translate(-pivot.getX() / 16f, -pivot.getY() / 16f, -pivot.getZ() / 16f);
	}

	public static void translateAwayFromPivotPoint(MatrixStack poseStack, StrutMesh mesh) {
		Vec3d pivot = mesh.pivot();

		poseStack.translate(-pivot.getX() / 16f, -pivot.getY() / 16f, -pivot.getZ() / 16f);
	}

	public static void translateAwayFromPivotPoint(MatrixStack poseStack, GroupBoneHandle bone) {
		poseStack.translate(-bone.getRenderPivotX() / 16f, -bone.getRenderPivotY() / 16f, -bone.getRenderPivotZ() / 16f);
	}

	public static void translateAndRotateMatrixForBone(MatrixStack poseStack, GroupBoneHandle bone) {
		translateToPivotPoint(poseStack, bone);
		rotateMatrixAroundBone(poseStack, bone);
	}

	public static void prepMatrixForBone(MatrixStack poseStack, GroupBoneHandle bone) {
		translateMatrixToBone(poseStack, bone);
		translateToPivotPoint(poseStack, bone);
		rotateMatrixAroundBone(poseStack, bone);
		scaleMatrixForBone(poseStack, bone);
		translateAwayFromPivotPoint(poseStack, bone);
	}
	
	public static Matrix4f invertAndMultiplyMatrices(Matrix4f baseMatrix, Matrix4f inputMatrix) {
		inputMatrix = new Matrix4f(inputMatrix);
		
		inputMatrix.invert();
		inputMatrix.mul(baseMatrix);

		return inputMatrix;
	}
	
	/**
     * Translates the provided {@link MatrixStack} to face towards the given {@link Entity}'s rotation
	 * <p>
     * Usually used for rotating projectiles towards their trajectory, in an {@link StrutRenderer#preRender} override
	 */
	public static void faceRotation(MatrixStack poseStack, Entity animatable, float partialTick) {
		poseStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(MathHelper.lerp(partialTick, animatable.prevYaw, animatable.getYaw()) - 90));
		poseStack.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.lerp(partialTick, animatable.prevPitch, animatable.getPitch())));
	}

	/**
	 * Add a positional vector to a matrix
	 * <p>
	 * This is specifically implemented to act as a translation of an x/y/z coordinate triplet to a render matrix
	 */
	public static Matrix4f translateMatrix(Matrix4f matrix, Vector3f vector) {
		return matrix.add(new Matrix4f().m30(vector.x).m31(vector.y).m32(vector.z));
	}
	
	/**
	 * Gets the actual dimensions of a texture resource from a given path
	 * <p>
	 * Not performance-efficient, and should not be relied upon
	 *
	 * @param texture The path of the texture resource to check
	 * @return The dimensions (width x height) of the texture, or null if unable to find or read the file
	 */
	@Nullable
	public static IntIntPair getTextureDimensions(Identifier texture) {
		if (texture == null)
			return null;

		AbstractTexture originalTexture = null;
		MinecraftClient mc = MinecraftClient.getInstance();

		try {
			originalTexture = mc.submit(() -> mc.getTextureManager().getTexture(texture)).get();
		}
		catch (Exception e) {
			StrutConstants.LOGGER.warn("Failed to load image for id {}", texture);
			e.printStackTrace();
		}

		if (originalTexture == null)
			return null;

		NativeImage image = null;

		try {
			image = originalTexture instanceof NativeImageBackedTexture dynamicTexture ? dynamicTexture.getImage()
					: NativeImage.read(mc.getResourceManager().getResource(texture).get().getInputStream());
		}
		catch (Exception e) {
			StrutConstants.LOGGER.error("Failed to read image for id {}", texture);
			e.printStackTrace();
		}

		return image == null ? null : IntIntImmutablePair.of(image.getWidth(), image.getHeight());
	}

	public static double getCurrentSystemTick() {
		return System.nanoTime() / 1E6 / 50d;
	}

	/**
	 * Returns the current time (in ticks) that the {@link org.lwjgl.glfw.GLFW GLFW} instance has been running
	 * <p>
	 * This is effectively a permanent timer that counts up since the game was launched.
	 */
	public static double getCurrentTick() {
		return GlfwUtil.getTime() * 20d;
	}

	/**
	 * Returns a float equivalent of a boolean
	 * <p>
	 * Output table:
	 * <ul>
	 *     <li>true -> 1</li>
	 *     <li>false -> 0</li>
	 * </ul>
	 */
	public static float booleanToFloat(boolean input) {
		return input ? 1f : 0f;
	}

	/**
	 * Converts a given double array to its {@link Vec3d} equivalent
	 */
	public static Vec3d arrayToVec(double[] array) {
		final double x;
		final double y;
		final double z;

		if (array.length == 1) {
			x = y = z = array[0];
		}
		else {
			x = array[0];
			y = array[1];
			z = array[2];
		}

		if (x == 0 && y == 0 && z == 0)
			return Vec3d.ZERO;

		return new Vec3d(x, y, z);
	}

	/**
	 * Rotates a {@link GroupBoneHandle} to match a provided {@link ModelPart}'s rotations
	 * <p>
	 * Usually used for items or armor rendering to match the rotations of other non-geo model parts
	 */
	public static void matchModelPartRot(ModelPart from, GroupBoneHandle to) {
		to.updateRotation(-from.pitch, -from.yaw, from.roll);
	}

	/**
	 * If a {@link StrutCube} is a 2d plane the {@link com.sandymandy.strut.cache.object.StrutQuad Quad's}
	 * normal is inverted in an intersecting plane,it can cause issues with shaders and other lighting tasks
	 * <p>
	 * This performs a pseudo-ABS function to help resolve some of those issues
	 */
	public static void fixInvertedFlatCube(StrutCube cube, Vector3f normal) {
		if (normal.x() < 0 && (cube.size().getY() == 0 || cube.size().getZ() == 0))
			normal.mul(-1, 1, 1);

		if (normal.y() < 0 && (cube.size().getX() == 0 || cube.size().getZ() == 0))
			normal.mul(1, -1, 1);

		if (normal.z() < 0 && (cube.size().getX() == 0 || cube.size().getY() == 0))
			normal.mul(1, 1, -1);
	}

	/**
	 * Rotate a [MatrixStack] around a given z/y/x axis, reducing the amount of work performed to the minimal required for the given values
	 */
	public static void optionalRotateZYX(MatrixStack poseStack, double z, double y, double x) {
		if (z == 0 && y == 0 && x == 0)
			return;

		final Quaternionf quat = new Quaternionf();

		if (x == 0 && y == 0) {
			quat.rotationZ((float)z);
		}
		else if (x == 0 && z == 0) {
			quat.rotationY((float)y);
		}
		else if (y == 0 && z == 0) {
			quat.rotationX((float)x);
		}
		else {
			quat.rotationZYX((float)z, (float)y, (float)x);
		}

		poseStack.multiply(quat);
	}

	/**
	 * Converts a {@link Direction} to a rotational float for rotation purposes
	 */
	public static float getDirectionAngle(Direction direction) {
		return switch(direction) {
			case SOUTH -> 90f;
			case NORTH -> 270f;
			case EAST -> 180f;
			default -> 0f;
		};
	}

	/**
	 * Special helper function for lerping yaw.
	 * <p>
	 * This exists because yaw in MinecraftClient handles its yaw a bit strangely, and can cause incorrect results if lerped without accounting for special-cases
	 */
	public static double lerpYaw(double delta, double start, double end) {
		start = MathHelper.wrapDegrees(start);
		end = MathHelper.wrapDegrees(end);
		double diff = start - end;
		end = diff > 180 || diff < -180 ? start + Math.copySign(360 - Math.abs(diff), diff) : end;

		return MathHelper.lerp(delta, start, end);
	}

	/**
	 * Gets a {@link StrutModel} instance from a given {@link EntityType}
	 * <p>
	 * This only works if you're calling this method for an EntityType known to be using a {@link StrutRenderer Strut Renderer}
	 * <p>
	 * Generally speaking you probably shouldn't be calling this method at all.
	 *
	 * @param entityType The {@code EntityType} to retrieve the StrutModel for
	 * @return The StrutModel, or null if one isn't found
	 */
	@Nullable
	public static StrutModel<?> getStrutModelForEntityType(EntityType<?> entityType) {
		EntityRenderer<?> renderer = MinecraftClient.getInstance().getEntityRenderDispatcher().renderers.get(entityType);

		return renderer instanceof StrutRenderer<?> strutRenderer ? strutRenderer.getStrutModel() : null;
	}

	/**
	 * Gets a StrutAnimatable instance that has been registered as the replacement renderer for a given {@link EntityType}
	 *
	 * @param entityType The {@code EntityType} to retrieve the replaced {@link StrutAnimatable} for
	 * @return The {@code StrutAnimatable} instance, or null if one isn't found
	 */
	@Nullable
	public static StrutAnimatable getReplacedAnimatable(EntityType<?> entityType) {
		EntityRenderer<?> renderer = MinecraftClient.getInstance().getEntityRenderDispatcher().renderers.get(entityType);

		return renderer instanceof StrutReplacedEntityRenderer<?, ?> replacedEntityRenderer ? replacedEntityRenderer.getAnimatable() : null;
	}

	/**
	 * Gets a {@link StrutModel} instance from a given {@link Entity}
	 * <p>
	 * This only works if you're calling this method for an Entity known to be using a {@link StrutRenderer Strut Renderer}
	 * <p>
	 * Generally speaking you probably shouldn't be calling this method at all.
	 *
	 * @param entity The {@code Entity} to retrieve the StrutModel for
	 * @return The StrutModel, or null if one isn't found
	 */
	@Nullable
	public static StrutModel<?> getStrutModelForEntity(Entity entity) {
		EntityRenderer<?> renderer = MinecraftClient.getInstance().getEntityRenderDispatcher().getRenderer(entity);

		return renderer instanceof StrutRenderer<?> strutRenderer ? strutRenderer.getStrutModel() : null;
	}

	/**
	 * Gets a {@link StrutModel} instance from a given {@link Item}
	 * <p>
	 * This only works if you're calling this method for an Item known to be using a {@link StrutRenderer Strut Renderer}
	 * <p>
	 * Generally speaking you probably shouldn't be calling this method at all.
	 *
	 * @param item The {@code ItemStack} to retrieve the StrutModel for
	 * @return The StrutModel, or null if one isn't found
	 */
	@Nullable
	public static StrutModel<?> getStrutModelForItem(ItemStack item) {
		return StrutServices.Client.ITEM_RENDERING.getStrutModelForItem(item);
	}

	/**
	 * Gets a {@link StrutModel} instance from a given {@link BlockEntity}
	 * <p>
	 * This only works if you're calling this method for a BlockEntity known to be using a {@link StrutRenderer Strut Renderer}
	 * <p>
	 * Generally speaking you probably shouldn't be calling this method at all
	 *
	 * @param blockEntity The {@code BlockEntity} to retrieve the StrutModel for
	 * @return The StrutModel, or null if one isn't found
	 */
	@Nullable
	public static StrutModel<?> getStrutModelForBlock(BlockEntity blockEntity) {
		BlockEntityRenderer<?> renderer = MinecraftClient.getInstance().getBlockEntityRenderDispatcher().get(blockEntity);

		return renderer instanceof StrutRenderer<?> strutRenderer ? strutRenderer.getStrutModel() : null;
	}

	/**
	 * Gets a {@link StrutModel} instance from a given {@link Item}
	 * <p>
	 * This only works if you're calling this method for an Item known to be using a {@link com.sandymandy.strut.renderer.StrutArmorRenderer StrutArmorRenderer}
	 * <p>
	 * Generally speaking you probably shouldn't be calling this method at all
	 *
	 * @param stack The ItemStack to retrieve the StrutModel for
	 * @return The StrutModel, or null if one isn't found
	 */
	@Nullable
	public static StrutModel<?> getStrutModelForArmor(ItemStack stack) {
		return StrutServices.Client.ITEM_RENDERING.getStrutModelForArmor(stack);
	}
}
