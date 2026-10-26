package com.sandymandy.strut.event.timeline;

import com.sandymandy.strut.event.type.EventTypeRegistry;
import com.sandymandy.strut.event.builtin.StrutBuiltinEvents;
import com.sandymandy.strut.loading.binary.BinaryFixtures;
import com.sandymandy.strut.loading.binary.BinaryOutput;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats;
import java.io.InputStream;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourcePack;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a server finds timelines - data packs, mod jars, code - and in which
 * order it prefers them
 */
class ClipTimelineSourcesTest {
	private static final Identifier FILE = Identifier.of("testns", "strut/animations/entity/thing.strutanim");

	private static EventTypeRegistry registry() {
		EventTypeRegistry registry = new EventTypeRegistry();

		StrutBuiltinEvents.registerAll(registry);

		return registry;
	}

	/** A resource manager that only knows how to list and open a fixed set of files */
	private static ResourceManager manager(Map<Identifier, byte[]> files) {
		return new ResourceManager() {
			@Override
			public Set<String> getAllNamespaces() {
				return Set.of();
			}

			@Override
			public List<Resource> getAllResources(Identifier id) {
				return List.of();
			}

			@Override
			public Map<Identifier, Resource> findResources(String startingPath, Predicate<Identifier> allowedPathPredicate) {
				Map<Identifier, Resource> found = new HashMap<>();

				files.forEach((id, bytes) -> {
					if (id.getPath().startsWith(startingPath) && allowedPathPredicate.test(id))
						found.put(id, new Resource((ResourcePack)null, (InputSupplier<InputStream>)() -> new ByteArrayInputStream(bytes)));
				});

				return found;
			}

			@Override
			public Map<Identifier, List<Resource>> findAllResources(String startingPath, Predicate<Identifier> allowedPathPredicate) {
				return Map.of();
			}

			@Override
			public Optional<Resource> getResource(Identifier id) {
				return Optional.empty();
			}

			@Override
			public Stream<ResourcePack> streamResourcePacks() {
				return Stream.empty();
			}
		};
	}

	/** A valid animation file whose only clip, "walk", has no channels or events and the given length */
	private static byte[] emptyWalkClip(float seconds) {
		BinaryFixtures.Strings strings = new BinaryFixtures.Strings();
		int name = strings.intern("walk");

		return BinaryFixtures.animationFile(strings, 1, 0, new BinaryOutput()
				.u16(name).f32(seconds).u8(LoopMode.LOOP.ordinal()).u16(0).u16(0));
	}

	private static ClipTimeline marker(double length) {
		return new ClipTimeline(length, LoopMode.LOOP, List.of(), 1L);
	}

	@Test
	@DisplayName("a data pack's animation file is found by the id a resource manager gives it")
	void dataPackSourceReadsStrutanim() {
		DataPackTimelineSource source = new DataPackTimelineSource(registry());

		source.reload(manager(Map.of(FILE, BinaryFixtures.animation())));

		ClipTimeline walk = source.find(FILE, "walk").orElseThrow();

		assertEquals(20, walk.lengthTicks(), 1e-9, "the fixture's clip is one second");
		assertEquals(1, walk.events().size());
		assertSame(StrutBuiltinEvents.SOUND, walk.events().get(0).parsed().type());
		assertTrue(source.find(FILE, "nope").isEmpty());
		assertTrue(source.find(Identifier.of("testns", "strut/animations/other.strutanim"), "walk").isEmpty());
	}

	@Test
	@DisplayName("a corrupt file is skipped and does not take the good ones down with it")
	void dataPackSourceSurvivesACorruptFile() {
		DataPackTimelineSource source = new DataPackTimelineSource(registry());
		Identifier bad = Identifier.of("testns", "strut/animations/entity/bad.strutanim");

		source.reload(manager(Map.of(FILE, BinaryFixtures.animation(), bad, new byte[] {1, 2, 3})));

		assertTrue(source.find(FILE, "walk").isPresent());
		assertEquals(1, source.fileCount());
	}

	@Test
	@DisplayName("only .strutanim files under animations/ are read")
	void dataPackSourceIgnoresOtherFiles() {
		DataPackTimelineSource source = new DataPackTimelineSource(registry());

		source.reload(manager(Map.of(
				Identifier.of("testns", "strut/animations/entity/thing.json"), BinaryFixtures.animation(),
				Identifier.of("testns", "creatures/thing.strutanim"), BinaryFixtures.animation())));

		assertEquals(0, source.fileCount());
	}

	@Test
	@DisplayName("a reload replaces the contents, so a removed pack file stops being found")
	void dataPackSourceReloadReplaces() {
		DataPackTimelineSource source = new DataPackTimelineSource(registry());

		source.reload(manager(Map.of(FILE, BinaryFixtures.animation())));
		assertTrue(source.find(FILE, "walk").isPresent());

		source.reload(manager(Map.of()));
		assertTrue(source.find(FILE, "walk").isEmpty());
	}

	@Test
	@DisplayName("a mod jar's assets are read from disk and get the id a resource manager would give them")
	void modJarSourceReadsAssets(@TempDir Path assets) throws IOException {
		Path dir = Files.createDirectories(assets.resolve("testns/strut/animations/entity"));

		Files.write(dir.resolve("thing.strutanim"), BinaryFixtures.animation());
		Files.write(dir.resolve("ignored.txt"), new byte[] {1});
		Files.createDirectories(assets.resolve("testns/models"));

		ModJarTimelineSource source = new ModJarTimelineSource(() -> List.of(assets, assets.resolve("does-not-exist")), registry());

		source.load();

		assertTrue(source.find(FILE, "walk").isPresent(), "assets/testns/strut/animations/entity/thing.strutanim -> testns:animations/entity/thing.strutanim");
		assertEquals(1, source.fileCount());
		assertEquals(List.of(FILE), source.filesContaining("walk"));
	}

	/** A file with three clips whose names are deliberately not alphabetical, one second each */
	private static byte[] threeClips() {
		BinaryFixtures.Strings strings = new BinaryFixtures.Strings();
		BinaryOutput clips = new BinaryOutput();

		for (String name : List.of("zulu", "alpha", "mike"))
			clips.u16(strings.intern(name)).f32(1f).u8(LoopMode.LOOP.ordinal()).u16(0).u16(0);

		return BinaryFixtures.animationFile(strings, 3, 0, clips);
	}

	@Test
	@DisplayName("the clips of a file are listed in file order, from a mod jar, through ClipTimelines, and by the first source that has the file")
	void clipsOfListsInFileOrder(@TempDir Path assets) throws IOException {
		Path dir = Files.createDirectories(assets.resolve("testns/strut/animations/scenes"));
		Identifier scene = Identifier.of("testns", "strut/animations/scenes/bow.strutanim");

		Files.write(dir.resolve("bow.strutanim"), threeClips());

		ModJarTimelineSource jar = new ModJarTimelineSource(() -> List.of(assets), registry());
		ClipTimelines timelines = new ClipTimelines(List.of(new DataPackTimelineSource(registry()), jar));

		jar.load();

		assertEquals(List.of("zulu", "alpha", "mike"), jar.clipsOf(scene), "not sorted: the Nth clip is the Nth in the file");
		assertEquals(List.of("zulu", "alpha", "mike"), timelines.clipsOf(scene));
		assertEquals(List.of(), timelines.clipsOf(Identifier.of("testns", "strut/animations/scenes/none.strutanim")));

		// a data pack that has the same file outranks the jar, as find does
		DataPackTimelineSource pack = new DataPackTimelineSource(registry());
		ClipTimelines both = new ClipTimelines(List.of(pack, jar));

		pack.reload(manager(Map.of(scene, emptyWalkClip(1f))));
		assertEquals(List.of("walk"), both.clipsOf(scene));
	}

	@Test
	@DisplayName("a mod jar and a data pack read animations from strut/animations as well as animations, under the id of the file's own path")
	void strutAnimationsFolder(@TempDir Path assets) throws IOException {
		Path dir = Files.createDirectories(assets.resolve("testns/strut/animations/scenes"));
		Identifier jarFile = Identifier.of("testns", "strut/animations/scenes/bow.strutanim");
		Identifier packFile = Identifier.of("testns", "strut/animations/scenes/other.strutanim");

		Files.write(dir.resolve("bow.strutanim"), threeClips());

		ModJarTimelineSource jar = new ModJarTimelineSource(() -> List.of(assets), registry());

		jar.load();

		assertEquals(List.of("zulu", "alpha", "mike"), jar.clipsOf(jarFile));
		assertTrue(jar.find(jarFile, "alpha").isPresent());

		DataPackTimelineSource pack = new DataPackTimelineSource(registry());

		pack.reload(manager(Map.of(packFile, emptyWalkClip(2f), Identifier.of("testns", "animations/old.strutanim"), emptyWalkClip(1f))));

		assertEquals(40, pack.find(packFile, "walk").orElseThrow().lengthTicks(), 1e-9);
		assertTrue(pack.find(Identifier.of("testns", "animations/old.strutanim"), "walk").isEmpty(), "the older folder is no longer read");
		assertTrue(StrutBinaryFormats.isInAnimationFolder("strut/animations/a.strutanim"));
		assertTrue(StrutBinaryFormats.isInAnimationFolder("strut/animations/a.strutanim"));
		assertFalse(StrutBinaryFormats.isInAnimationFolder("geo/a.strutanim"));
	}

	@Test
	@DisplayName("a clip is found through a list of files in preference order, without a name search")
	void findThroughAFileList() {
		CodeTimelineSource code = new CodeTimelineSource();
		ClipTimelines timelines = new ClipTimelines(List.of(code));
		Identifier first = Identifier.of("testns", "strut/animations/a.strutanim");
		Identifier second = Identifier.of("testns", "strut/animations/b.strutanim");

		timelines.register(first, "clip", marker(10));
		timelines.register(second, "clip", marker(30));
		timelines.register(second, "only_b", marker(5));

		assertEquals(10, timelines.find(List.of(first, second), "clip").orElseThrow().lengthTicks(), "the first file that has it");
		assertEquals(30, timelines.find(List.of(second, first), "clip").orElseThrow().lengthTicks());
		assertEquals(5, timelines.find(List.of(first, second), "only_b").orElseThrow().lengthTicks());
		assertTrue(timelines.find(List.of(first), "only_b").isEmpty(), "not found in the listed files means not found, whatever other files hold");
		assertTrue(timelines.find(List.of(), "clip").isEmpty());
	}

	@Test
	@DisplayName("code registrations are found by file and clip")
	void codeSource() {
		CodeTimelineSource source = new CodeTimelineSource();
		ClipTimeline timeline = marker(10);

		source.register(FILE, "clip", timeline);

		assertSame(timeline, source.find(FILE, "clip").orElseThrow());
		assertTrue(source.find(FILE, "other").isEmpty());
		assertEquals(List.of(FILE), source.filesContaining("clip"));
	}

	@Test
	@DisplayName("priority: code, then added sources, then data packs, then mod jars")
	void priorityOrder(@TempDir Path assets) throws IOException {
		Path dir = Files.createDirectories(assets.resolve("testns/strut/animations/entity"));

		Files.write(dir.resolve("thing.strutanim"), BinaryFixtures.animation());

		DataPackTimelineSource dataPack = new DataPackTimelineSource(registry());
		ModJarTimelineSource jar = new ModJarTimelineSource(() -> List.of(assets), registry());
		ClipTimelines timelines = new ClipTimelines(List.of(dataPack, jar));

		jar.load();

		assertEquals(20, timelines.find(FILE, "walk").orElseThrow().lengthTicks(), 1e-9, "only the jar has it");

		// A data pack's "walk" of a different length (3 s, not the jar's 1 s) outranks the jar
		dataPack.reload(manager(Map.of(FILE, emptyWalkClip(3f))));
		assertEquals(60, timelines.find(FILE, "walk").orElseThrow().lengthTicks(), 1e-9, "the data pack must win over the jar");

		// A custom source outranks both, and code outranks that
		CodeTimelineSource custom = new CodeTimelineSource();

		custom.register(FILE, "walk", marker(111));
		timelines.addSource(custom);
		assertEquals(111, timelines.find(FILE, "walk").orElseThrow().lengthTicks(), 1e-9);

		timelines.register(FILE, "walk", marker(222));
		assertEquals(222, timelines.find(FILE, "walk").orElseThrow().lengthTicks(), 1e-9);
	}

	@Test
	@DisplayName("a clip found by name alone must be unique across files")
	void findUnique() {
		ClipTimelines timelines = new ClipTimelines(List.of());
		Identifier other = Identifier.of("testns", "strut/animations/entity/other.strutanim");

		timelines.register(FILE, "only_here", marker(5));
		timelines.register(FILE, "shared", marker(6));
		timelines.register(other, "shared", marker(7));

		assertEquals(5, timelines.findUnique("only_here").orElseThrow().lengthTicks(), 1e-9);
		assertTrue(timelines.findUnique("shared").isEmpty(), "two files have it, so guessing would fire the wrong events");
		assertTrue(timelines.findUnique("nowhere").isEmpty());
		assertEquals(1, timelines.warningCount(), "the ambiguity warned");
	}

	@Test
	@DisplayName("a missing timeline warns once per clip, and a reload lets it warn again")
	void missingWarnsOnce() {
		ClipTimelines timelines = new ClipTimelines(List.of());

		assertTrue(timelines.warnMissingOnce(FILE, "walk"));
		assertFalse(timelines.warnMissingOnce(FILE, "walk"), "same clip, already said");
		assertTrue(timelines.warnMissingOnce(FILE, "run"), "a different clip is a different problem");

		timelines.reload(manager(Map.of()));

		assertTrue(timelines.warnMissingOnce(FILE, "walk"), "a fixed pack that breaks again should warn again");
	}
}
