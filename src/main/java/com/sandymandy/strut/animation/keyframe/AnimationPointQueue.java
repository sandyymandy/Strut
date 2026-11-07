/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package com.sandymandy.strut.animation.keyframe;

import com.sandymandy.strut.animation.AnimationController;

import java.io.Serial;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * An {@link AnimationPoint} queue holds a queue of {@code AnimationPoints} which are used in
 * the {@link AnimationController} to lerp between values
 * <p>
 * It tells the {@link Used} list it was made with the first time something is added after it was last emptied, so the controller empties only the queues a frame
 * used and not the nine of every bone of the model
 */
public final class AnimationPointQueue extends LinkedList<AnimationPoint> {
	@Serial
	private static final long serialVersionUID = 5472797438476621193L;

	/** The queues that something was added to since the list was last emptied */
	public static final class Used {
		private final List<AnimationPointQueue> queues = new ArrayList<>();

		/** Empties every queue that was used, and forgets them */
		public void clear() {
			for (int i = 0; i < this.queues.size(); i++) {
				AnimationPointQueue queue = this.queues.get(i);

				queue.listed = false;
				queue.clear();
			}

			this.queues.clear();
		}
	}

	private final transient Used used;
	private boolean listed;

	public AnimationPointQueue() {
		this(null);
	}

	public AnimationPointQueue(Used used) {
		this.used = used;
	}

	private void use() {
		if (!this.listed && this.used != null) {
			this.listed = true;
			this.used.queues.add(this);
		}
	}

	@Override
	public boolean add(AnimationPoint point) {
		use();

		return super.add(point);
	}

	@Override
	public void addLast(AnimationPoint point) {
		use();
		super.addLast(point);
	}

	@Override
	public void addFirst(AnimationPoint point) {
		use();
		super.addFirst(point);
	}
}
