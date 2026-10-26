package com.sandymandy.strut.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import com.sandymandy.strut.event.timeline.ClipTimelinesFabric;

/**
 * The event system runs on a dedicated server, so nothing under {@code event/} - and
 * nothing it is meant to share with the client - may reference a client-only class
 * <p>
 * Loading a client class on a dedicated server is a hard {@code NoClassDefFoundError} at first use,
 * and no unit test can catch it by running the code, since the test JVM has the client classes. So
 * this reads the source instead, the same way {@code MeshRenderCoverageTest} guards the renderers. A
 * green build says nothing about this.
 */
class EventSystemServerSafetyTest {
	private static final Path EVENT_SOURCES = Path.of(System.getProperty("user.dir"),
			"src", "main", "java", "com", "sandymandy", "strut", "event");

	/**
	 * Anything that only exists on a client. {@code ClipTimelinesFabric} is checked too: it registers a
	 * {@code SERVER_DATA} listener, which is common-side
	 */
	private static final List<String> FORBIDDEN = List.of(
			"net.minecraft.client", "net.fabricmc.fabric.api.client", "com.mojang.blaze3d",
			"com.sandymandy.strut.renderer", "com.sandymandy.strut.StrutClient", "MinecraftClient");

	/**
	 * The two files that predate the typed event system and share its package: render-pipeline events,
	 * client-side by design. Named rather than pattern-matched, so a new client-only file added to this
	 * package fails the guard instead of quietly slipping past it
	 */
	private static final List<String> CLIENT_BY_DESIGN = List.of("StrutRenderEvent.java", "StrutEventsFabric.java");

	@Test
	@DisplayName("no file under event/ references a client-only class")
	void eventPackageIsServerSafe() throws IOException {
		List<String> offences = new ArrayList<>();

		try (Stream<Path> files = Files.walk(EVENT_SOURCES)) {
			for (Path file : (Iterable<Path>)files.filter(p -> p.toString().endsWith(".java"))::iterator) {
				if (CLIENT_BY_DESIGN.contains(file.getFileName().toString()))
					continue;

				List<String> lines = Files.readAllLines(file);

				for (int i = 0; i < lines.size(); i++) {
					String line = lines.get(i);

					// Only real references count: the doc comments legitimately name these
					// classes to explain why they are avoided
					if (line.strip().startsWith("*") || line.strip().startsWith("//") || line.strip().startsWith("/*"))
						continue;

					for (String forbidden : FORBIDDEN) {
						if (line.contains(forbidden))
							offences.add(EVENT_SOURCES.relativize(file) + ":" + (i + 1) + " references " + forbidden);
					}
				}
			}
		}

		assertTrue(offences.isEmpty(), "client-only references in the event system: " + offences);
	}

	@Test
	@DisplayName("the guard itself finds the event sources - it would pass vacuously otherwise")
	void guardSeesTheSources() throws IOException {
		try (Stream<Path> files = Files.walk(EVENT_SOURCES)) {
			assertTrue(files.filter(p -> p.toString().endsWith(".java")).count() > 10,
					"expected the event package's sources under " + EVENT_SOURCES);
		}
	}
}
