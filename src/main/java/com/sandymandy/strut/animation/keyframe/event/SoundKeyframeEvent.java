/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package com.sandymandy.strut.animation.keyframe.event;

import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.AnimationController;
import com.sandymandy.strut.animation.keyframe.event.data.SoundKeyframeData;

/**
 * The {@link KeyFrameEvent} specific to the {@link AnimationController#soundKeyframeHandler}
 * <p>
 * Called when a sound instruction keyframe is encountered
 */
public class SoundKeyframeEvent<T extends StrutAnimatable> extends KeyFrameEvent<T, SoundKeyframeData> {
	public SoundKeyframeEvent(T entity, double animationTick, AnimationController<T> controller, SoundKeyframeData keyFrameData) {
		super(entity, animationTick, controller, keyFrameData);
	}

	@Override
	public SoundKeyframeData getKeyframeData() {
		return super.getKeyframeData();
	}
}
