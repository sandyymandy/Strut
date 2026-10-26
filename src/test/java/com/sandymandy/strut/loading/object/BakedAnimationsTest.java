package com.sandymandy.strut.loading.object;

import com.sandymandy.strut.animation.Animation;
import com.sandymandy.strut.animation.Animation.Keyframes;
import com.sandymandy.strut.animation.Animation.LoopType;
import com.sandymandy.strut.animation.keyframe.BoneAnimation;
import com.sandymandy.strut.animation.keyframe.event.data.CustomInstructionKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.ParticleKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.SoundKeyframeData;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BakedAnimations#nameAt} exists so a command like {@code /strut anim <index>} can select
 * a clip by position without knowing its name
 */
class BakedAnimationsTest {
	private static Animation animation(String name) {
		return new Animation(name, 1, LoopType.LOOP, new BoneAnimation[0],
				new Keyframes(new SoundKeyframeData[0], new ParticleKeyframeData[0], new CustomInstructionKeyframeData[0]));
	}

	@Test
	@DisplayName("resolves an index to the name at that position, in the map's own order")
	void resolvesByIndex() {
		Map<String, Animation> animations = new Object2ObjectLinkedOpenHashMap<>();

		animations.put("walk", animation("walk"));
		animations.put("idle", animation("idle"));
		animations.put("swing", animation("swing"));

		BakedAnimations baked = new BakedAnimations(animations);

		assertEquals("walk", baked.nameAt(0));
		assertEquals("idle", baked.nameAt(1));
		assertEquals("swing", baked.nameAt(2));
	}

	@Test
	@DisplayName("returns null rather than throwing for an index past the end")
	void returnsNullPastTheEnd() {
		BakedAnimations baked = new BakedAnimations(Map.of("only", animation("only")));

		assertNull(baked.nameAt(1));
		assertNull(baked.nameAt(100));
	}

	@Test
	@DisplayName("returns null rather than throwing for a negative index")
	void returnsNullForNegativeIndex() {
		BakedAnimations baked = new BakedAnimations(Map.of("only", animation("only")));

		assertNull(baked.nameAt(-1));
	}

	@Test
	@DisplayName("returns null on an empty animation map, however it's backed")
	void returnsNullWhenEmpty() {
		assertNull(new BakedAnimations(new Object2ObjectOpenHashMap<>()).nameAt(0));
	}
}
