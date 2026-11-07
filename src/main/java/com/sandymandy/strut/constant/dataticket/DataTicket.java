package com.sandymandy.strut.constant.dataticket;

import java.util.Map;
import java.util.Objects;

/**
 * Ticket object to define a typed data object
 */
public class DataTicket<D> {
	private final String id;
	private final Class<? extends D> objectType;

	public DataTicket(String id, Class<? extends D> objectType) {
		this.id = id;
		this.objectType = objectType;
		this.hash = Objects.hash(id, objectType);
	}

	// worked out once: a ticket is looked up in an animation state's data several times a frame for every model drawn
	private final int hash;

	public String id() {
		return this.id;
	}

	public Class<? extends D> objectType() {
		return this.objectType;
	}

	@Override
	public int hashCode() {
		return this.hash;
	}

	/**
	 * Reverse getter function for consistent operation of ticket data retrieval
	 *
	 * @param dataMap The data map to retrieve the data from
	 * @return The data from the map, or null if the data hasn't been stored
	 */
	public <D> D getData(Map<? extends DataTicket<?>, ?> dataMap) {
		return (D)dataMap.get(this);
	}
}
