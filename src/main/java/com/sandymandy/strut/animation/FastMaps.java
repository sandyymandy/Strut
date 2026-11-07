package com.sandymandy.strut.animation;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;

import java.util.Map;

/**
 * Going through the entries of a map that is walked every frame for every bone, without an entry object for each: a fastutil map hands out one entry it
 * reuses. The entry is only good until the next one is asked for, so it must not be kept
 */
final class FastMaps {
	private FastMaps() {}

	@SuppressWarnings("unchecked")
	static <K, V> Iterable<Map.Entry<K, V>> entries(Map<K, V> map) {
		if (map instanceof Object2ObjectMap<K, V> fast)
			return (Iterable<Map.Entry<K, V>>) (Iterable<?>) Object2ObjectMaps.fastIterable(fast);

		return map.entrySet();
	}
}
