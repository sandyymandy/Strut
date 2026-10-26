package com.sandymandy.strut.animation.authority;

import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * The client's decision of when a server-driven controller blends: when the clip it mirrors changes, it blends from the pose it had been showing into the new clip
 * over the controller's own transition length, and otherwise not at all
 * <p>
 * The server only says which clip plays and when it started; how that looks (a cut or a blend, and how long) is the client's, set with
 * {@code AnimationController#transitionLength}. Pure and generic in what a "pose" is, so the rule is tested without a game
 * <ul>
 *   <li>The first clip a controller sees (a player coming into range mid-scene, the entity's first playback) does not blend: there is no pose of the old clip
 *       to blend from.</li>
 *   <li>The same clip again (a rate change, a seek, a restart) does not blend: it is the same motion.</li>
 *   <li>A different clip blends from the pose captured the moment the change was seen, over {@code length} ticks counted from then, so a late packet does not
 *       make the blend jump.</li>
 *   <li>A stopped controller forgets the clip: what plays next is a first clip again.</li>
 * </ul>
 */
public final class PlaybackBlend<P> {
	@Nullable
	private String clip;
	@Nullable
	private P from;
	private double start;

	/**
	 * Call once per frame with the clip the controller mirrors now
	 *
	 * @param now The game time plus the frame's partial tick
	 * @param length The blend length in ticks; 0 or less never blends
	 * @param capture Takes the pose being shown right now; called only on the frame a blend starts
	 * @return How many ticks into the blend this frame is, or -1 when the clip is not blending
	 */
	public double observe(String clipName, double now, double length, Supplier<P> capture) {
		if (!clipName.equals(this.clip)) {
			boolean hadClip = this.clip != null;

			this.clip = clipName;

			if (hadClip && length > 0) {
				this.from = capture.get();
				this.start = now;
			}
			else {
				this.from = null;
			}
		}

		if (this.from == null)
			return -1;

		double elapsed = now - this.start;

		if (length <= 0 || elapsed >= length || elapsed < 0) {
			this.from = null;

			return -1;
		}

		return elapsed;
	}

	/** The pose the blend starts from; valid after {@link #observe} returned a value of 0 or more */
	@Nullable
	public P from() {
		return this.from;
	}

	/** The controller stopped: the next clip is a first clip */
	public void reset() {
		this.clip = null;
		this.from = null;
	}
}
