package com.sandymandy.strut.event.dispatch;

import com.sandymandy.strut.event.server.ServerEventListener;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The server-side listeners registered for event types and for named markers
 * <p>
 * An instance so tests get a fresh one; the game's single instance sits behind
 * {@link com.sandymandy.strut.event.StrutEvents}. Copy-on-write lists, so a listener registering
 * another listener from inside a callback cannot break the iteration that called it
 */
public final class ServerEventListeners {
	private final Map<Identifier, List<ServerEventListener>> byType = new ConcurrentHashMap<>();
	private final Map<String, List<ServerEventListener>> byMarker = new ConcurrentHashMap<>();

	public void add(Identifier type, ServerEventListener listener) {
		this.byType.computeIfAbsent(type, id -> new CopyOnWriteArrayList<>()).add(listener);
	}

	/**
	 * @param marker The marker name; compared case-insensitively
	 */
	public void addMarker(String marker, ServerEventListener listener) {
		this.byMarker.computeIfAbsent(marker.toLowerCase(Locale.ROOT), name -> new CopyOnWriteArrayList<>()).add(listener);
	}

	public List<ServerEventListener> forType(Identifier type) {
		return this.byType.getOrDefault(type, List.of());
	}

	public List<ServerEventListener> forMarker(String marker) {
		return this.byMarker.getOrDefault(marker.toLowerCase(Locale.ROOT), List.of());
	}

	public boolean hasListenersFor(Identifier type) {
		return !forType(type).isEmpty();
	}
}
