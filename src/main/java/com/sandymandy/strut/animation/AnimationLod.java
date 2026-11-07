package com.sandymandy.strut.animation;

import com.sandymandy.strut.animatable.StrutAnimatable;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/**
 * Lets a distant entity's
 * {@link com.sandymandy.strut.model.StrutModel#handleAnimations} skip the expensive controller/bone
 * walk on some frames, reusing its last evaluated pose in between
 * <p>
 * Safe to skip because of three things already true of the animation system, not new invariants
 * this class introduces: poses are per-{@link com.sandymandy.strut.animation.state.ModelInstance
 * instance}, so a skipped instance simply keeps drawing its own last pose without touching any
 * shared state; {@link AnimatableManager#advanceAnimTime} accumulates real elapsed time into
 * {@code animTime} independently of whether the bone walk itself ran that frame, so the next real
 * evaluation lands at exactly the right animation time with no drift or catch-up; and
 * {@link AnimationController}'s keyframe events fire on threshold-crossing
 * ({@code adjustedTick >= startTick}), so a skip delays an event by at most one interval rather
 * than dropping it
 * <p>
 * Scoped to real entities only (checked via {@code instanceof Entity}, which
 * {@link com.sandymandy.strut.animatable.StrutReplacedEntity} deliberately does not satisfy) - items,
 * blocks, armor and replaced-entity animatables keep evaluating every frame until a later pass
 * covers them
 */
public final class AnimationLod {
	/** Every frame inside this radius - close enough that stepping would be visible */
	private static final double NEAR_DISTANCE = 24;

	/** Every 2nd frame between {@link #NEAR_DISTANCE} and this radius */
	private static final double MID_DISTANCE = 48;

	/** How many entities may be inside the full radii; with more, the radii shrink with the square root of how many more, so a crowd shares what it can afford */
	private static final int CROWD_FULL = 24;

	/** The radii never shrink below this share of the full ones: what is close enough to see the stepping is always every frame */
	private static final double SMALLEST = 0.45;

	private static double nearSq = NEAR_DISTANCE * NEAR_DISTANCE;
	private static double midSq = MID_DISTANCE * MID_DISTANCE;
	private static int crowd;

	/** Capped at this so even a very distant crowd still updates at a visible rate */
	private static final int MAX_INTERVAL = 4;

	private static long frameIndex;
	private static double cameraX, cameraY, cameraZ;
	private static boolean cameraKnown;

	private AnimationLod() {}

	/**
	 * Marks a new render frame and captures the camera's current position - called once per frame
	 * from {@link com.sandymandy.strut.mixin.client.LevelRendererMixin}, the same choke point
	 * {@link com.sandymandy.strut.perf.RenderPerfStats#beginFrame} already uses
	 */
	public static void beginFrame(Camera camera) {
		frameIndex++;

		// the radii for this frame, from how many entities asked last frame
		double scale = crowd <= CROWD_FULL ? 1 : Math.max(SMALLEST, Math.sqrt((double)CROWD_FULL / crowd));

		nearSq = NEAR_DISTANCE * NEAR_DISTANCE * scale * scale;
		midSq = MID_DISTANCE * MID_DISTANCE * scale * scale;
		crowd = 0;

		Vec3d pos = camera.getPos();
		cameraX = pos.x;
		cameraY = pos.y;
		cameraZ = pos.z;
		cameraKnown = true;
	}

	/**
	 * Whether {@code animatable}'s animation controllers should actually be evaluated this frame
	 * <p>
	 * Never skips an instance's first evaluation ({@code manager.isFirstTick()} - it has no pose yet
	 * to reuse) and never skips an animatable that opted out via {@link StrutAnimatable#useAnimationLod()}.
	 * Staggered by entity id ({@code (frameIndex + entityId) % interval == 0}) rather than every
	 * eligible instance sharing the same on-frame, so a crowd spawned in one tick spreads its
	 * evaluations evenly instead of spiking every Nth frame
	 */
	public static <T extends StrutAnimatable> boolean shouldEvaluate(T animatable, AnimatableManager<T> manager) {
		if (manager.isFirstTick() || !animatable.useAnimationLod() || !cameraKnown)
			return true;

		if (!(animatable instanceof Entity entity))
			return true;

		crowd++;

		int interval = intervalFor(entity.squaredDistanceTo(cameraX, cameraY, cameraZ));

		if (interval <= 1)
			return true;

		return (frameIndex + entity.getId()) % interval == 0;
	}

	private static int intervalFor(double squaredDistance) {
		if (squaredDistance < nearSq)
			return 1;

		if (squaredDistance < midSq)
			return 2;

		return MAX_INTERVAL;
	}
}
