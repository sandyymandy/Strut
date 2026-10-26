package com.sandymandy.strut.model;

import com.sandymandy.strut.animatable.StrutAnimatable;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Function;

/**
 * Builds a {@link StrutModel} from three resource ids, each either a constant or a function of the animatable - a model
 * without writing a class
 * <pre>{@code
 * StrutModel<`CharacterEntity`> model = StrutModel.<`CharacterEntity`>builder()
 *         .model(creature -> id("strut/models/" + (creature.isStripped() ? "bare/" : "dressed/") + creature.getCreatureId() + ".strutmodel"))
 *         .texture(creature -> id("textures/entities/" + creature.getCreatureId() + ".png"))
 *         .animation(id("strut/animations/base/creature.strutanim"))
 *         .build();
 * }</pre>
 * For the usual layout, where the ids follow from the entity type's id, use {@link StrutModel#entity} instead. Anything the
 * builder does not cover (head turning, custom bone handling, animation fallbacks) is still a subclass of {@link StrutModel}
 */
public final class StrutModelBuilder<T extends StrutAnimatable> {
	@Nullable
	private Function<T, Identifier> model;
	@Nullable
	private Function<T, Identifier> texture;
	@Nullable
	private Function<T, Identifier> animation;

	StrutModelBuilder() {}

	public StrutModelBuilder<T> model(Identifier model) {
		Objects.requireNonNull(model, "model");

		return model(animatable -> model);
	}

	public StrutModelBuilder<T> model(Function<T, Identifier> model) {
		this.model = Objects.requireNonNull(model, "model");

		return this;
	}

	public StrutModelBuilder<T> texture(Identifier texture) {
		Objects.requireNonNull(texture, "texture");

		return texture(animatable -> texture);
	}

	public StrutModelBuilder<T> texture(Function<T, Identifier> texture) {
		this.texture = Objects.requireNonNull(texture, "texture");

		return this;
	}

	public StrutModelBuilder<T> animation(Identifier animation) {
		Objects.requireNonNull(animation, "animation");

		return animation(animatable -> animation);
	}

	public StrutModelBuilder<T> animation(Function<T, Identifier> animation) {
		this.animation = Objects.requireNonNull(animation, "animation");

		return this;
	}

	/**
	 * @throws IllegalStateException if the model, texture or animation was never set. Said here, at build time, rather than as a
	 *                               null pointer on the first frame
	 */
	public StrutModel<T> build() {
		if (this.model == null || this.texture == null || this.animation == null) {
			throw new IllegalStateException("StrutModel.builder() needs model, texture and animation; missing: "
					+ (this.model == null ? "model " : "") + (this.texture == null ? "texture " : "") + (this.animation == null ? "animation" : ""));
		}

		return new FunctionalStrutModel<>(this.model, this.texture, this.animation);
	}

	private static final class FunctionalStrutModel<T extends StrutAnimatable> extends StrutModel<T> {
		private final Function<T, Identifier> model;
		private final Function<T, Identifier> texture;
		private final Function<T, Identifier> animation;

		FunctionalStrutModel(Function<T, Identifier> model, Function<T, Identifier> texture, Function<T, Identifier> animation) {
			this.model = model;
			this.texture = texture;
			this.animation = animation;
		}

		@Override
		public Identifier getModelResource(T animatable) {
			return this.model.apply(animatable);
		}

		@Override
		public Identifier getTextureResource(T animatable) {
			return this.texture.apply(animatable);
		}

		@Override
		public Identifier getAnimationResource(T animatable) {
			return this.animation.apply(animatable);
		}
	}
}
