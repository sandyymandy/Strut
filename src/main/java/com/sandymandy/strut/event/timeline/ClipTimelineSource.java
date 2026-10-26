package com.sandymandy.strut.event.timeline;

import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * Somewhere the server can find a clip's {@link ClipTimeline}
 * <p>
 * The server never assumes where assets live: a custom character's animation may sit in a data pack
 * on the server while its textures and sounds sit in a client resource pack the server never sees. So
 * finding timelines is pluggable - Strut ships sources for Java code, data packs and mod jars, and a
 * mod with content stored somewhere else implements this and registers it with
 * {@link ClipTimelines#addSource}.
 * <p>
 * Implementations must not touch client-only classes: this runs on a dedicated server
 */
public interface ClipTimelineSource {
	/**
	 * @param file The animation file, identified the way a model's animation resource is (for
	 *             {@code assets/ns/animations/x.strutanim} that is {@code ns:animations/x.strutanim})
	 * @param clip The clip's name within that file
	 */
	Optional<ClipTimeline> find(Identifier file, String clip);

	/**
	 * Every file this source has a clip named {@code clip} in - for finding a clip when the caller
	 * knows its name but not its file. Empty if the source cannot enumerate
	 */
	default List<Identifier> filesContaining(String clip) {
		return List.of();
	}

	/**
	 * The names of the clips this source holds for {@code file}, in the order they appear in the file; empty if it has no such file or cannot enumerate
	 */
	default List<String> clipsOf(Identifier file) {
		return List.of();
	}

	/**
	 * Called whenever server data reloads (and once at startup), with the server's own resource
	 * manager. A source that reads from it rebuilds here; one that does not can ignore it
	 */
	default void reload(ResourceManager serverData) {}
}
