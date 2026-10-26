package com.sandymandy.strut.animation.authority;

import com.sandymandy.strut.animation.RawAnimation;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;

/**
 * One controller's authoritative playback state: which clip is playing, from when, and how fast
 * <p>
 * The whole point of this record is that it is <b>enough on its own</b> to work out what a clip
 * should look like on any frame, on any client, without that client having watched the clip start.
 * Everything needed is absolute - the clip, the game tick it began on, where in the clip it began,
 * and the rate - so sampling it is a pure function of the current time (see {@link #sampleTick}).
 * Nothing accumulates, so nothing can drift.
 *
 * <h2>Why this is not how animation time works today</h2>
 *
 * <p>{@link com.sandymandy.strut.animation.AnimatableManager#advanceAnimTime} accumulates frame
 * deltas into a running {@code animTime}, and {@code StrutModel#handleAnimations} only advances it
 * while the game is not paused. That clock cannot be synchronised between clients even in
 * principle: it permanently offsets on every pause, and its base ({@code entity.age}) counts from
 * whenever each side first saw the entity, so a player who joined late is permanently out of phase.
 * A clip driven from this record instead has neither problem, because no part of the answer depends
 * on how long this client has been watching.
 *
 * <p>
 *
 * @param controller The controller this drives, by name
 * @param animation The clip to play, by name - resolved the same way {@link RawAnimation.Stage#animationName}
 *                  and {@code StrutModel#getAnimation(animatable, name)} already resolve one: against
 *                  whatever file the model's own {@code getAnimationResource} points to. Not an
 *                  {@link net.minecraft.util.Identifier}, deliberately - a clip is not a registered
 *                  resource on its own, only a named entry inside one
 * @param startGameTick The value of {@code World#getTime()} when this playback began. That counter
 *                      is chosen deliberately: it always advances (unlike {@code getTimeOfDay},
 *                      which {@code /time set} moves) and vanilla already synchronises it to every
 *                      client
 * @param startOffsetTicks Where in the clip playback began, so a clip can be started mid-way - and,
 *                         more importantly, so the server can re-base a playback when its rate
 *                         changes without the clip jumping
 * @param playbackRate Clip ticks per game tick. 1 is real time, 0 freezes, negative runs backwards
 * @param loopMode What happens once the clip's end is reached
 * @param sequence Monotonic per controller, per animatable. A client applies an update only if its
 *                 sequence beats the one it already holds, so a delayed packet can never restore an
 *                 older playback. Unused until the protocol lands, and introduced here anyway
 *                 because retrofitting an ordering guarantee into a shipped protocol is far worse
 *                 than carrying the field early
 */
public record AnimationPlayback(String controller, String animation, long startGameTick,
								float startOffsetTicks, float playbackRate, LoopMode loopMode,
								long sequence) {
	/**
	 * Manual rather than {@link PacketCodec#tuple}, which this record's seven fields overrun -
	 * {@code LoopMode} rides as a single byte via its own {@link LoopMode#fromWire}, the same
	 * ordinal convention the binary model/animation wire format already uses
	 */
	public static final PacketCodec<PacketByteBuf, AnimationPlayback> STREAM_CODEC = PacketCodec.of(
			(playback, buf) -> {
				buf.writeString(playback.controller);
				buf.writeString(playback.animation);
				buf.writeVarLong(playback.startGameTick);
				buf.writeFloat(playback.startOffsetTicks);
				buf.writeFloat(playback.playbackRate);
				buf.writeByte(playback.loopMode.ordinal());
				buf.writeVarLong(playback.sequence);
			},
			buf -> new AnimationPlayback(buf.readString(), buf.readString(), buf.readVarLong(),
					buf.readFloat(), buf.readFloat(), LoopMode.fromWire(buf.readByte()), buf.readVarLong()));

	/**
	 * How far this playback has progressed, in clip ticks, ignoring the clip's own length
	 * <p>
	 * May run past the clip's end, or before its start on a negative rate - {@link #sampleTick}
	 * is what folds it into a real clip
	 *
	 * @param gameTime The current {@code World#getTime()}
	 * @param partialTick The fraction of the way through the current tick, for smooth rendering
	 */
	public double elapsedTicks(long gameTime, float partialTick) {
		return this.startOffsetTicks + (gameTime - this.startGameTick + partialTick) * this.playbackRate;
	}

	/**
	 * The game time at which this playback reaches the end of a clip of {@code clipLengthTicks}, for a clip that does not loop; {@link Double#NaN} for one that
	 * never ends (it loops, is stopped by a rate of 0, or is running backwards)
	 * <p>
	 * Exact for the rate the playback holds: {@link ServerAnimationState#setRate} replaces the playback with one re-based on where the clip is, so asking the new
	 * playback gives the new end time, and nothing computed from the old one stays valid. A server polls this (or compares the game time against it) instead of
	 * waiting for an event: there is no clip-finished event
	 *
	 * @param clipLengthTicks The clip's length, {@link com.sandymandy.strut.event.timeline.ClipTimeline#lengthTicks}
	 */
	public double endGameTime(double clipLengthTicks) {
		if (this.loopMode == LoopMode.LOOP || this.playbackRate <= 0f)
			return Double.NaN;

		return this.startGameTick + (clipLengthTicks - this.startOffsetTicks) / this.playbackRate;
	}

	/**
	 * Whether a clip that does not loop has reached its end by {@code gameTime} (always false for a looping one)
	 */
	public boolean hasEnded(double clipLengthTicks, long gameTime) {
		double end = endGameTime(clipLengthTicks);

		return !Double.isNaN(end) && gameTime >= end;
	}

	/**
	 * Where in a clip of {@code clipLengthTicks} this playback sits right now
	 * <p>
	 * Looping is resolved here, from absolute time, rather than by a controller noticing it has run
	 * off the end. That is the difference between a loop that stays in phase across clients and one
	 * that does not: a client that joins twenty seconds into a looping clip lands on the same frame
	 * as everyone else, because the answer never depended on having seen the first nineteen
	 *
	 * @param clipLengthTicks The clip's own length. A non-positive length has no frames to sample,
	 *                        and yields 0 rather than dividing by it (a looping one runs on: its time is the elapsed time)
	 * @return A tick within {@code [0, clipLengthTicks]}
	 */
	public double sampleTick(double clipLengthTicks, long gameTime, float partialTick) {
		double elapsed = elapsedTicks(gameTime, partialTick);

		// a looping clip with no length (only molang drives it) has no end to wrap at: its time runs on
		if (clipLengthTicks <= 0)
			return this.loopMode == LoopMode.LOOP ? Math.max(elapsed, 0) : 0;

		if (this.loopMode != LoopMode.LOOP)
			return Math.clamp(elapsed, 0, clipLengthTicks);

		/*
		 * Math.floorMod's sign convention, in floating point: a negative elapsed (a clip running
		 * backwards past its own start) has to wrap up to the end of the clip rather than staying
		 * negative, or a reversed loop would clamp to 0 and stop instead of looping.
		 */
		double wrapped = elapsed % clipLengthTicks;

		return wrapped < 0 ? wrapped + clipLengthTicks : wrapped;
	}
}
