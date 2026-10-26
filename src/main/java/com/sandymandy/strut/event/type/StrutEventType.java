package com.sandymandy.strut.event.type;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Function;
import com.sandymandy.strut.event.client.ClientEventContext;
import com.sandymandy.strut.event.client.ClientEventHandler;
import com.sandymandy.strut.event.server.EventContext;
import com.sandymandy.strut.event.server.ServerEventHandler;

/**
 * A registered kind of animation event: its identity, its payload's shape on the wire and in a clip,
 * and the delivery defaults an author gets unless a clip entry overrides them
 * <p>
 * This is the piece a consuming mod writes - one {@link #builder} call per event it owns - instead
 * of inventing a string parser and a trigger packet per event.
 * <p>
 * <b>Server handlers</b> ({@link Builder#serverHandler}) take an {@link EventContext} (entity, occurrence, exact
 * time, deterministic random). Client handlers ({@link Builder#clientHandler}) take a
 * {@link ClientEventContext}. Both run from the synced clock, each on its own side
 * <p>
 * <b>Payload codecs use plain {@link PacketByteBuf}.</b> A payload that references a registry entry
 * should carry its {@link Identifier} and resolve it at handling time, which is also what makes it
 * safe to decode on a client that lacks the entry
 *
 * @param <P> The payload type. A payload-less event uses {@link net.minecraft.util.Unit}
 */
public final class StrutEventType<P> {
	private final Identifier id;
	private final EventCategory category;
	private final EventAuthority defaultAuthority;
	private final ReversePolicy defaultReverse;
	private final PacketCodec<PacketByteBuf, P> codec;
	@Nullable
	private final Function<String, P> parser;
	@Nullable
	private final ServerEventHandler<P> serverHandler;
	@Nullable
	private final ClientEventHandler<P> clientHandler;

	private StrutEventType(Builder<P> builder) {
		this.id = builder.id;
		this.category = builder.category;
		this.defaultAuthority = builder.authority;
		this.defaultReverse = builder.reverse;
		this.codec = builder.codec;
		this.parser = builder.parser;
		this.serverHandler = builder.serverHandler;
		this.clientHandler = builder.clientHandler;
	}

	/**
	 * @param id The event's stable id - what clips and listeners name it by
	 * @param codec How the payload crosses the network, for the rare server-to-client path
	 */
	public static <P> Builder<P> builder(Identifier id, PacketCodec<PacketByteBuf, P> codec) {
		return new Builder<>(id, codec);
	}

	public Identifier id() {
		return this.id;
	}

	public EventCategory category() {
		return this.category;
	}

	/**
	 * Where this type runs when a clip entry does not say
	 */
	public EventAuthority defaultAuthority() {
		return this.defaultAuthority;
	}

	public ReversePolicy defaultReverse() {
		return this.defaultReverse;
	}

	public PacketCodec<PacketByteBuf, P> codec() {
		return this.codec;
	}

	/**
	 * Runs this type's own server behaviour for one occurrence, if it has any
	 * <p>
	 * The payload arrives untyped because the dispatcher works over many types at once; it was parsed
	 * by this type's own parser, so the cast cannot fail unless a caller hands it another type's payload
	 */
	@SuppressWarnings("unchecked")
	public void runServerHandler(EventContext context, Object payload) {
		if (this.serverHandler != null)
			this.serverHandler.handle(context, (P)payload);
	}

	/**
	 * Runs this type's own client behaviour for one occurrence, if it has any - see
	 * {@link #runServerHandler} on why the payload arrives untyped
	 */
	@SuppressWarnings("unchecked")
	public void runClientHandler(ClientEventContext context, Object payload) {
		if (this.clientHandler != null)
			this.clientHandler.handle(context, (P)payload);
	}

	/**
	 * Whether a clip may name this type. False for events only the engine raises
	 * ({@code strut:loop}, {@code strut:end})
	 */
	public boolean isAuthorable() {
		return this.parser != null;
	}

	/**
	 * Turns a clip's text payload into this type's payload
	 *
	 * @throws IllegalArgumentException if the text is not a valid payload for this type, or the type
	 *                                  cannot be authored. The message is meant to be shown to the
	 *                                  author next to the file and time it came from
	 */
	public P parse(String text) {
		if (this.parser == null)
			throw new IllegalArgumentException(this.id + " is raised by the engine and cannot be authored in a clip");

		try {
			return this.parser.apply(text);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException(this.id + ": " + ex.getMessage(), ex);
		}
		catch (RuntimeException ex) {
			throw new IllegalArgumentException(this.id + ": cannot read payload \"" + text + "\" (" + ex + ")", ex);
		}
	}

	@Override
	public String toString() {
		return "StrutEventType[" + this.id + "]";
	}

	public static final class Builder<P> {
		private final Identifier id;
		private final PacketCodec<PacketByteBuf, P> codec;
		private EventCategory category = EventCategory.SCRIPT;
		private EventAuthority authority = EventAuthority.BOTH;
		private ReversePolicy reverse = ReversePolicy.SKIP;
		@Nullable
		private Function<String, P> parser;
		@Nullable
		private ServerEventHandler<P> serverHandler;
		@Nullable
		private ClientEventHandler<P> clientHandler;

		private Builder(Identifier id, PacketCodec<PacketByteBuf, P> codec) {
			this.id = Objects.requireNonNull(id, "id");
			this.codec = Objects.requireNonNull(codec, "codec");
		}

		public Builder<P> category(EventCategory category) {
			this.category = Objects.requireNonNull(category, "category");

			return this;
		}

		public Builder<P> authority(EventAuthority authority) {
			this.authority = Objects.requireNonNull(authority, "authority");

			return this;
		}

		public Builder<P> reverse(ReversePolicy reverse) {
			this.reverse = Objects.requireNonNull(reverse, "reverse");

			return this;
		}

		/**
		 * What this type does on the server each time one fires - the type's own behaviour, run before any
		 * listener. Only called if the event's authority includes the server
		 */
		public Builder<P> serverHandler(ServerEventHandler<P> handler) {
			this.serverHandler = Objects.requireNonNull(handler, "handler");

			return this;
		}

		/**
		 * What this type does on each client every time one fires, from that client's own mirrored
		 * timeline - run before any client listener. Only called if the event's authority includes clients
		 */
		public Builder<P> clientHandler(ClientEventHandler<P> handler) {
			this.clientHandler = Objects.requireNonNull(handler, "handler");

			return this;
		}

		/**
		 * How a clip's text payload becomes a {@code P}. Throw {@link IllegalArgumentException} with
		 * a message an author can act on. Without a parser the type cannot be authored in a clip
		 */
		public Builder<P> parser(Function<String, P> parser) {
			this.parser = Objects.requireNonNull(parser, "parser");

			return this;
		}

		/**
		 * For a type with no payload: authorable, and the payload text must be blank
		 */
		public Builder<P> noPayload(P value) {
			return parser(text -> {
				if (!text.isBlank())
					throw new IllegalArgumentException("takes no payload, but got \"" + text.strip() + "\"");

				return value;
			});
		}

		public StrutEventType<P> build() {
			return new StrutEventType<>(this);
		}
	}
}
