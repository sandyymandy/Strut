package com.sandymandy.strut.event.timeline;

import com.sandymandy.strut.event.type.EventTypeRegistry;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import com.sandymandy.strut.event.StrutEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import com.sandymandy.strut.event.dispatch.ClipResolution;

/**
 * The server's answer to "what are this clip's length, loop mode and events?" - asked of an ordered
 * list of {@link ClipTimelineSource}s, first hit wins
 * <p>
 * Default order: code registrations, then any source a mod adds with {@link #addSource}, then data
 * packs, then mod jars - so a server owner's data pack overrides a mod's own timelines, and Java code
 * overrides everything.
 * <p>
 * <b>A clip no source knows</b> is not an error that stops anything: its visuals and sounds still work
 * client-side, and it simply has no gameplay events. What it does get is one warning, once, naming the
 * clip - loud where an author can see it, never silent drift. Use {@link #warnMissingOnce}
 */
public final class ClipTimelines {
	private static final Logger LOGGER = LogManager.getLogger("Strut");
	private static ClipTimelines instance;

	private final CodeTimelineSource code = new CodeTimelineSource();
	private final List<ClipTimelineSource> custom = new CopyOnWriteArrayList<>();
	private final List<ClipTimelineSource> builtIn;
	private final Set<String> warned = ConcurrentHashMap.newKeySet();

	/**
	 * @param builtIn Sources consulted after code and custom ones, in priority order
	 */
	public ClipTimelines(List<ClipTimelineSource> builtIn) {
		this.builtIn = List.copyOf(builtIn);
	}

	/**
	 * The game's instance: data packs, then mod jars (whose roots {@code modJarRoots} supplies)
	 */
	public static ClipTimelines create(EventTypeRegistry registry, Supplier<List<Path>> modJarRoots) {
		return new ClipTimelines(List.of(new DataPackTimelineSource(registry), new ModJarTimelineSource(modJarRoots, registry)));
	}

	/**
	 * The instance the server dispatcher reads. Created by {@code ClipTimelinesFabric.init}
	 *
	 * @throws IllegalStateException if Strut has not initialised it (a dedicated test, or a caller too early)
	 */
	public static synchronized ClipTimelines instance() {
		if (instance == null)
			throw new IllegalStateException("ClipTimelines is not initialised; Strut creates it during mod initialisation");

		return instance;
	}

	static synchronized void setInstance(ClipTimelines timelines) {
		instance = timelines;
	}

	private static final ClientTimelineSource CLIENT_SOURCE = new ClientTimelineSource();
	private static final ClipTimelines CLIENT = new ClipTimelines(List.of(CLIENT_SOURCE));

	/**
	 * The client's own timelines - a separate instance from {@link #instance()}, because on an
	 * integrated server both exist in one JVM and must not see each other's sources: the client reads its
	 * resource packs, the server its data packs and mod jars
	 */
	public static ClipTimelines client() {
		return CLIENT;
	}

	/**
	 * Replaces the client's timelines - called from {@code StrutCache}'s resource reload, so they always
	 * describe exactly the clips the client just loaded
	 */
	public static void setClientTimelines(Map<Identifier, Map<String, ClipTimeline>> timelines) {
		CLIENT_SOURCE.set(timelines);
		CLIENT.warned.clear();
		CLIENT.validateRequirements("client", StrutEvents.markerRequirements());
	}

	/**
	 * Registers a source of your own, consulted before the built-in data pack and mod jar sources but
	 * after code registrations
	 */
	public void addSource(ClipTimelineSource source) {
		this.custom.add(source);
	}

	/**
	 * Pins a timeline from Java, above every other source
	 */
	public void register(Identifier file, String clip, ClipTimeline timeline) {
		this.code.register(file, clip, timeline);
	}

	private List<ClipTimelineSource> ordered() {
		List<ClipTimelineSource> sources = new ArrayList<>(2 + this.custom.size() + this.builtIn.size());

		sources.add(this.code);
		sources.addAll(this.custom);
		sources.addAll(this.builtIn);

		return sources;
	}

	/**
	 * @param file The animation file the clip lives in
	 */
	public Optional<ClipTimeline> find(Identifier file, String clip) {
		for (ClipTimelineSource source : ordered()) {
			Optional<ClipTimeline> found = source.find(file, clip);

			if (found.isPresent())
				return found;
		}

		return Optional.empty();
	}

	/**
	 * The clips of one animation file in the order they appear in the file, from the first source that has the file (the same order {@link #find} prefers);
	 * empty when no source has it. The server's answer to "which clips does this file contain, and which is the Nth": a catalogue built from it agrees with one
	 * built by the client from the same file
	 *
	 * @param file The animation file, as {@link #find} takes it
	 */
	public List<String> clipsOf(Identifier file) {
		for (ClipTimelineSource source : ordered()) {
			List<String> clips = source.clipsOf(file);

			if (!clips.isEmpty())
				return clips;
		}

		return List.of();
	}

	/**
	 * Finds a clip through a list of files in preference order, the first that has it: what an entity's {@code StrutEntity#getAnimationFiles()} is for. Does not
	 * fall back to a lookup by name and does not warn; {@code ClipResolution.resolve(StrutEntity, String)} is the
	 * entity-level call that does
	 */
	public Optional<ClipTimeline> find(List<Identifier> files, String clip) {
		for (Identifier file : files) {
			Optional<ClipTimeline> found = find(file, clip);

			if (found.isPresent())
				return found;
		}

		return Optional.empty();
	}

	/**
	 * Finds a clip when only its name is known: succeeds if exactly one file has it. Two files with the
	 * same clip name is ambiguous and warns once, since guessing between them would fire the wrong
	 * events
	 */
	public Optional<ClipTimeline> findUnique(String clip) {
		Set<Identifier> files = new LinkedHashSet<>();

		for (ClipTimelineSource source : ordered()) {
			files.addAll(source.filesContaining(clip));
		}

		if (files.size() == 1)
			return find(files.iterator().next(), clip);

		if (files.size() > 1)
			warnOnce("ambiguous:" + clip, "clip \"" + clip + "\" exists in " + files.size() + " animation files (" + files
					+ "), so its event timeline cannot be chosen by name alone; name the file");

		return Optional.empty();
	}

	/**
	 * Rebuilds every source from the server's data. Called at startup and on every data reload, and
	 * forgets which missing clips were already warned about, so a fixed pack that breaks again warns again
	 */
	public void reload(ResourceManager serverData) {
		this.warned.clear();

		for (ClipTimelineSource source : ordered()) {
			source.reload(serverData);
		}

		validateRequirements("server", StrutEvents.markerRequirements());
	}

	/**
	 * Checks every clip a consumer declared markers for ({@link StrutEvents#requireMarkers}) against what
	 * these timelines actually contain, warning once per problem
	 * <p>
	 * A clip found under several files is checked in each: which one a playback resolves to is decided later,
	 * and a requirement that only one of them meets is still worth hearing about. A required clip that has no
	 * timeline at all is a warning too - a typo in the clip name would otherwise mean the requirement quietly
	 * checks nothing
	 *
	 * @param side "server" or "client", for the message: a clip can be fine on one side and missing on the other
	 */
	public void validateRequirements(String side, MarkerRequirements requirements) {
		for (String clip : requirements.all().keySet()) {
			List<Identifier> files = new ArrayList<>();

			for (ClipTimelineSource source : ordered()) {
				for (Identifier file : source.filesContaining(clip)) {
					if (!files.contains(file))
						files.add(file);
				}
			}

			if (files.isEmpty()) {
				warnOnce("required-none:" + clip, "clip \"" + clip + "\" must contain the markers " + requirements.all().get(clip)
						+ ", but no " + side + " timeline was found for it (check the clip's name, and that its file is where the "
						+ side + " can read it)");

				continue;
			}

			for (Identifier file : files) {
				List<String> missing = requirements.missing(clip, find(file, clip).orElseThrow());

				if (!missing.isEmpty())
					warnOnce("required:" + file + "#" + clip, "clip \"" + clip + "\" in " + file + " is missing the marker(s) " + missing
							+ " that its " + side + " logic depends on");
			}
		}
	}

	/**
	 * Logs that the server started a clip no source has a timeline for - once per clip per reload
	 *
	 * @return Whether this call logged (false if it had already)
	 */
	public boolean warnMissingOnce(Identifier file, String clip) {
		return warnOnce("missing:" + file + "#" + clip, "no event timeline found for clip \"" + clip + "\" in " + file
				+ ". Its sounds and visuals still work, but it has no server events - put its .strutanim in a data pack under data/"
				+ file.getNamespace() + "/" + file.getPath() + ", or in the mod's assets");
	}

	/**
	 * {@link #warnMissingOnce} for a clip whose file is unknown - the animatable declared none and no
	 * source has the clip under any file
	 */
	public boolean warnMissingByNameOnce(String clip) {
		return warnOnce("missing-name:" + clip, "no event timeline found for clip \"" + clip + "\" in any data pack or mod. "
				+ "Its sounds and visuals still work, but it has no server events - put its .strutanim in a data pack under "
				+ "data/<namespace>/strut/animations/, or declare the file with StrutEntity#getAnimationFiles()");
	}

	private boolean warnOnce(String key, String message) {
		if (!this.warned.add(key))
			return false;

		LOGGER.warn("[Strut events] {}", message);

		return true;
	}

	/**
	 * How many distinct warnings have been logged since the last reload - for diagnostics and tests
	 */
	public int warningCount() {
		return this.warned.size();
	}
}
