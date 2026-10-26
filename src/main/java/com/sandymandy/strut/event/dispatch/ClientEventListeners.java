package com.sandymandy.strut.event.dispatch;

import com.sandymandy.strut.event.client.ClientEventListener;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The client-side listeners registered for event types and for named markers - the counterpart of
 * {@link ServerEventListeners}, kept separate so a listener registered for the server can never be
 * called with a client context or the reverse
 */
public final class ClientEventListeners {
	private final Map<Identifier, List<ClientEventListener>> byType = new ConcurrentHashMap<>();
	private final Map<String, List<ClientEventListener>> byMarker = new ConcurrentHashMap<>();

	public void add(Identifier type, ClientEventListener listener) {
		this.byType.computeIfAbsent(type, id -> new CopyOnWriteArrayList<>()).add(listener);
	}

	/**
	 * @param marker The marker name; compared case-insensitively
	 */
	public void addMarker(String marker, ClientEventListener listener) {
		this.byMarker.computeIfAbsent(marker.toLowerCase(Locale.ROOT), name -> new CopyOnWriteArrayList<>()).add(listener);
	}

	public List<ClientEventListener> forType(Identifier type) {
		return this.byType.getOrDefault(type, List.of());
	}

	public List<ClientEventListener> forMarker(String marker) {
		return this.byMarker.getOrDefault(marker.toLowerCase(Locale.ROOT), List.of());
	}

	public boolean hasListenersFor(Identifier type) {
		return !forType(type).isEmpty();
	}
}
