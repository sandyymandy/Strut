package com.sandymandy.strut.event.dispatch;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Lets a person watch every event fire on chosen entities, live - what {@code /strut events trace} drives
 * <p>
 * A working event and one that silently never ran look identical from outside, which is why a live test
 * needs a way to see occurrences as they happen rather than infer them from side effects. Each traced entity
 * has a sink that receives one line per delivered event; nothing is recorded for entities nobody is tracing,
 * and the check is a single map lookup
 */
public final class EventTrace {
	private final Map<Integer, Consumer<String>> sinks = new ConcurrentHashMap<>();

	/**
	 * Starts sending a line per delivered event on {@code entityId} to {@code sink}, replacing any earlier sink
	 */
	public void start(int entityId, Consumer<String> sink) {
		this.sinks.put(entityId, sink);
	}

	public void stop(int entityId) {
		this.sinks.remove(entityId);
	}

	public boolean isTracing(int entityId) {
		return this.sinks.containsKey(entityId);
	}

	public int tracedCount() {
		return this.sinks.size();
	}

	/**
	 * Sends {@code line} to the entity's sink, if it has one
	 */
	public void report(int entityId, String line) {
		Consumer<String> sink = this.sinks.get(entityId);

		if (sink != null)
			sink.accept(line);
	}
}
