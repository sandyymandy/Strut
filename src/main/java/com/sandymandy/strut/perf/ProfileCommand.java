package com.sandymandy.strut.perf;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.sandymandy.strut.StrutConstants;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code /strutprofile [seconds]}: samples the game for a while (ten seconds unless given) with the JDK's own flight recorder, then writes where the render
 * thread spent its time and what it allocated to {@code strut-profile.txt} in the game folder. Nothing to install; for finding what makes a scene slow
 */
public final class ProfileCommand {
	private static final String THREAD = "Render thread";
	private static boolean running;

	private ProfileCommand() {}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommandManager.literal("strutprofile")
				.executes(context -> start(context.getSource(), 10))
				.then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(2, 120))
						.executes(context -> start(context.getSource(), IntegerArgumentType.getInteger(context, "seconds")))));
	}

	private static int start(FabricClientCommandSource source, int seconds) {
		if (running) {
			source.sendError(Text.literal("A profile is already being taken"));

			return 0;
		}

		running = true;

		Recording recording = new Recording();

		recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2));
		recording.enable("jdk.ObjectAllocationSample").with("throttle", "2000/s");
		recording.start();
		source.sendFeedback(Text.literal("Profiling for " + seconds + " seconds: keep looking at the scene"));

		Thread worker = new Thread(() -> {
			Path report = MinecraftClient.getInstance().runDirectory.toPath().resolve("strut-profile.txt");
			String message;

			try {
				Thread.sleep(seconds * 1000L);
				recording.stop();

				Path dump = Files.createTempFile("strut-profile", ".jfr");

				recording.dump(dump);
				write(dump, report, seconds);
				Files.deleteIfExists(dump);
				message = "Profile written to " + report.toAbsolutePath();
			}
			catch (Exception ex) {
				StrutConstants.LOGGER.error("The profile could not be taken", ex);
				message = "The profile could not be taken: " + ex.getMessage();
			}
			finally {
				recording.close();
				running = false;
			}

			String done = message;

			MinecraftClient.getInstance().execute(() -> {
				if (MinecraftClient.getInstance().player != null)
					MinecraftClient.getInstance().player.sendMessage(Text.literal(done), false);
			});
		}, "Strut profile");

		worker.setDaemon(true);
		worker.start();

		return 1;
	}

	private static void write(Path dump, Path report, int seconds) throws IOException {
		Map<String, Long> self = new HashMap<>();
		Map<String, Long> inclusive = new HashMap<>();
		Map<String, Long> firstStrut = new HashMap<>();
		Map<String, Long> firstMod = new HashMap<>();
		Map<String, Long> allocations = new HashMap<>();
		long samples = 0;
		long allocated = 0;

		for (RecordedEvent event : RecordingFile.readAllEvents(dump)) {
			RecordedStackTrace stack = event.getStackTrace();
			RecordedThread thread = event.getThread("sampledThread") != null ? event.getThread("sampledThread") : event.getThread();

			if (stack == null || stack.getFrames().isEmpty() || thread == null || !THREAD.equals(thread.getJavaName()))
				continue;

			List<RecordedFrame> frames = stack.getFrames();

			if (event.getEventType().getName().equals("jdk.ExecutionSample")) {
				samples++;
				self.merge(name(frames.get(0)), 1L, Long::sum);

				Set<String> seen = new HashSet<>();

				for (RecordedFrame frame : frames) {
					String method = method(frame);

					if (seen.add(method))
						inclusive.merge(method, 1L, Long::sum);
				}

				firstFrom(frames, "com.sandymandy.", firstStrut);
				firstNotFrom(frames, firstMod);
			}
			else if (event.getEventType().getName().equals("jdk.ObjectAllocationSample")) {
				long weight = event.getLong("weight");
				String site = "?";

				for (RecordedFrame frame : frames) {
					String type = frame.getMethod().getType().getName();

					if (!type.startsWith("java.") && !type.startsWith("jdk.") && !type.startsWith("it.unimi.") && !type.startsWith("com.google.")) {
						site = name(frame);
						break;
					}
				}

				allocated += weight;
				allocations.merge(event.getClass("objectClass").getName() + "  <-  " + site, weight, Long::sum);
			}
		}

		try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(report, StandardCharsets.UTF_8))) {
			out.printf("Strut profile of the render thread: %d samples over %d seconds, %.1f MB allocated in the samples' estimate%n%n", samples, seconds,
					allocated / 1048576.0);
			section(out, "Inclusive: the methods the time was spent in, or under", inclusive, samples, 60);
			section(out, "Self: the method running when sampled", self, samples, 40);
			section(out, "The first Strut method on the stack", firstStrut, samples, 40);
			section(out, "The first method outside the JDK and Minecraft on the stack (a mod's own code)", firstMod, samples, 30);
			section(out, "Allocated (by estimated bytes), and where", allocations, allocated, 30);
		}
	}

	private static void firstFrom(List<RecordedFrame> frames, String prefix, Map<String, Long> into) {
		for (RecordedFrame frame : frames) {
			if (frame.getMethod().getType().getName().startsWith(prefix)) {
				into.merge(name(frame), 1L, Long::sum);

				return;
			}
		}
	}

	private static void firstNotFrom(List<RecordedFrame> frames, Map<String, Long> into) {
		for (RecordedFrame frame : frames) {
			String type = frame.getMethod().getType().getName();

			if (!type.startsWith("java.") && !type.startsWith("jdk.") && !type.startsWith("sun.") && !type.startsWith("net.minecraft.")
					&& !type.startsWith("com.mojang.") && !type.startsWith("org.") && !type.startsWith("it.unimi.") && !type.startsWith("com.sandymandy.")
					&& !type.startsWith("net.fabricmc.") && !type.startsWith("com.google.")) {
				into.merge(name(frame), 1L, Long::sum);

				return;
			}
		}
	}

	private static String method(RecordedFrame frame) {
		return frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
	}

	private static String name(RecordedFrame frame) {
		return method(frame) + ":" + frame.getLineNumber();
	}

	private static void section(PrintWriter out, String title, Map<String, Long> counts, long total, int limit) {
		out.println(title);

		counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(limit)
				.forEach(e -> out.printf("  %6.2f%%  %s%n", 100.0 * e.getValue() / Math.max(1, total), e.getKey()));

		out.println();
	}
}
