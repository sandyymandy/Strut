package com.sandymandy.strut.physics.tools;

import java.util.ArrayList;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import com.sandymandy.strut.physics.StrutPhysics;

/**
 * Runtime controls for one entity's physics: what a mod calls from client code to tune it live (a character-creator slider, a clip that holds a bone still,
 * a slap that should make a bone bounce). Client local and never synced, like the physics itself. Get one with
 * {@link StrutPhysics#of}
 * <p>
 * Every setter may be called every frame; nothing here allocates per call once a bone has been named once
 */
public final class PhysicsControls {
	private boolean enabled = true;
	private float firmness = 1f;
	private float weight = 1f;
	private float inertia = 1f;
	private final Vector3f wind = new Vector3f();
	private final Map<String, BoneControl> bones = new HashMap<>();
	private final List<Impulse> impulses = new CopyOnWriteArrayList<>();
	private volatile Grab grab;
	private volatile boolean ragdollOn;
	private volatile float ragdollStrength;
	private volatile float ragdollRecover;
	private final List<Impulse> ragdollHits = new CopyOnWriteArrayList<>();

	public static final class BoneControl {
		public float frequencyScale = 1f;
		public float weight = 1f;
	}

	public record Impulse(String bone, float x, float y, float z) {}

	/** A part held by the cursor: a chain particle (index 1 and up) or a spring bone (index 0), and where it is held, in world space */
	public record Grab(String part, int index, double x, double y, double z) {}

	/**
	 * Holds a part where you put it, until {@link #release}. For a chain, {@code index} is the particle (1 is the first bone's far end); for a spring bone
	 * it is 0 and the bone's mass is pulled toward the point. This is what the in-game tool's cursor drives, and it works from code too
	 */
	public PhysicsControls grab(String part, int index, double x, double y, double z) {
		this.grab = new Grab(part, index, x, y, z);

		return this;
	}

	public PhysicsControls release() {
		this.grab = null;

		return this;
	}

	public Grab grab() {
		return this.grab;
	}

	public PhysicsControls() {}

	/**
	 * Lets the body go: its ragdoll (the {@code ragdoll} section of the model's physics) takes over from the animation, starting with the entity's own motion.
	 * {@code strength} is the muscle: 0 is limp, 1 is exactly the animation, and in between is a body that keeps trying to stand and gets pushed about
	 */
	public PhysicsControls ragdoll(float strength) {
		this.ragdollStrength = Math.max(0f, Math.min(1f, strength));
		this.ragdollRecover = 0f;
		this.ragdollOn = true;

		return this;
	}

	/** Brings the muscle back up to full over {@code seconds} and then hands the body back to the animation */
	public PhysicsControls ragdollRecover(float seconds) {
		if (this.ragdollOn)
			this.ragdollRecover = Math.max(0.05f, seconds);

		return this;
	}

	/** Hands the body back to the animation at once (it fades over a quarter of a second) */
	public PhysicsControls ragdollOff() {
		this.ragdollOn = false;
		this.ragdollRecover = 0f;

		return this;
	}

	/** Shoves a bone of the ragdoll: a velocity in world blocks per second */
	public PhysicsControls ragdollHit(String bone, float x, float y, float z) {
		this.ragdollHits.add(new Impulse(bone, x, y, z));

		return this;
	}

	public boolean ragdollOn() {
		return this.ragdollOn;
	}

	public float ragdollStrength() {
		return this.ragdollStrength;
	}

	public float ragdollRecoverSeconds() {
		return this.ragdollRecover;
	}

	/** The recovery is complete: the body belongs to the animation again */
	public void ragdollFinished() {
		this.ragdollOn = false;
		this.ragdollRecover = 0f;
	}

	public List<Impulse> drainRagdollHits() {
		List<Impulse> out = new ArrayList<>(this.ragdollHits);

		this.ragdollHits.clear();

		return out;
	}

	/** Turns this entity's physics off (the pose is exactly the animation) or back on */
	public PhysicsControls enabled(boolean enabled) {
		this.enabled = enabled;

		return this;
	}

	public boolean enabled() {
		return this.enabled;
	}

	/** Scales every bone's frequency: above 1 is firmer and quicker, below 1 is softer and slower. A size or firmness slider maps here */
	public PhysicsControls firmness(float scale) {
		this.firmness = Math.max(0.05f, scale);

		return this;
	}

	/** How much of the physics reaches the pose for the whole model, 0 to 1 */
	public PhysicsControls weight(float weight) {
		this.weight = Math.max(0f, Math.min(1f, weight));

		return this;
	}

	/** How much of the entity's own motion reaches the bones, as a multiplier on each bone's own setting */
	public PhysicsControls inertia(float scale) {
		this.inertia = Math.max(0f, scale);

		return this;
	}

	/** How much of the physics reaches one bone, 0 to 1: hold a bone still during a clip by setting 0, release it by setting 1 */
	public PhysicsControls weight(String bone, float weight) {
		control(bone).weight = Math.max(0f, Math.min(1f, weight));

		return this;
	}

	/** Scales one bone's frequency, see {@link #firmness} */
	public PhysicsControls firmness(String bone, float scale) {
		control(bone).frequencyScale = Math.max(0.05f, scale);

		return this;
	}

	/** A steady extra push on every bone, in world blocks per second squared (wind, a moving vehicle). Zero clears it */
	public PhysicsControls wind(float x, float y, float z) {
		this.wind.set(x, y, z);

		return this;
	}

	/**
	 * Kicks one bone: {@code (x, y, z)} is a velocity in blocks per second given to the mass on the bone's lever, in the bone's own space. A slap on a
	 * pouch or a landing on a belly is a short, strong impulse
	 */
	public PhysicsControls impulse(String bone, float x, float y, float z) {
		this.impulses.add(new Impulse(bone, x, y, z));

		return this;
	}

	/** Back to the defaults: on, firmness 1, weight 1, no wind, no per-bone changes */
	public PhysicsControls reset() {
		this.enabled = true;
		this.firmness = 1f;
		this.weight = 1f;
		this.inertia = 1f;
		this.wind.zero();
		this.bones.clear();
		this.impulses.clear();
		this.grab = null;
		this.ragdollOn = false;
		this.ragdollRecover = 0f;
		this.ragdollHits.clear();

		return this;
	}

	public float firmness() {
		return this.firmness;
	}

	public float weight() {
		return this.weight;
	}

	public float inertia() {
		return this.inertia;
	}

	public Vector3f wind() {
		return this.wind;
	}

	public BoneControl boneOrNull(String bone) {
		return this.bones.get(bone);
	}

	/** The impulses queued since the last call, cleared */
	public List<Impulse> drainImpulses() {
		if (this.impulses.isEmpty())
			return List.of();

		List<Impulse> drained = List.copyOf(this.impulses);

		this.impulses.clear();

		return drained;
	}

	private BoneControl control(String bone) {
		return this.bones.computeIfAbsent(bone, b -> new BoneControl());
	}
}
