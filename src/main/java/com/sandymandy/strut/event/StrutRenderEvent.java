package com.sandymandy.strut.event;

import net.minecraft.client.util.math.MatrixStack;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.renderer.*;

/**
 * Strut events base-class for the various event stages of rendering.<br>
 */
public interface StrutRenderEvent {
	/**
	 * Returns the renderer for this event
	 *
	 * @see com.sandymandy.strut.renderer.StrutArmorRenderer StrutArmorRenderer
	 * @see com.sandymandy.strut.renderer.StrutBlockRenderer StrutBlockRenderer
	 * @see com.sandymandy.strut.renderer.StrutEntityRenderer StrutEntityRenderer
	 * @see com.sandymandy.strut.animatable.StrutItem StrutItem
	 * @see com.sandymandy.strut.renderer.StrutObjectRenderer StrutObjectRenderer
	 * @see com.sandymandy.strut.renderer.StrutReplacedEntityRenderer StrutReplacedEntityRenderer
	 */
	StrutRenderer<?> getRenderer();

	/**
	 * Renderer events for armor pieces being rendered by {@link StrutArmorRenderer}
	 */
	abstract class Armor implements StrutRenderEvent {
		private final StrutArmorRenderer<?> renderer;

		public Armor(StrutArmorRenderer<?> renderer) {
			this.renderer = renderer;
		}

		/**
		 * Returns the renderer for this event
		 */
		@Override
		public StrutArmorRenderer<?> getRenderer() {
			return this.renderer;
		}

		/**
		 * Shortcut method for retrieving the entity being rendered
		 */
		@Nullable
		public net.minecraft.entity.Entity getEntity() {
			return getRenderer().getCurrentEntity();
		}

		/**
		 * Shortcut method for retrieving the ItemStack relevant to the armor piece being rendered
		 */
		@Nullable
		public ItemStack getItemStack() {
			return getRenderer().getCurrentStack();
		}

		/**
		 * Shortcut method for retrieving the equipped slot of the armor piece being rendered
		 */
		@Nullable
		public EquipmentSlot getEquipmentSlot() {
			return getRenderer().getCurrentSlot();
		}

		/**
		 * Pre-render event for armor pieces being rendered by {@link StrutArmorRenderer}
		 * <p>
		 * This event is called before rendering, but after {@link StrutRenderer#preRender}
		 * <p>
		 * This event is <u>cancellable</u><br>
		 * If the event is cancelled by returning false in the {@link Listener}, the armor piece will not be rendered and the corresponding {@link Post} event will not be fired.
		 */
		public static class Pre extends Armor {
			public static final Event<Listener> EVENT = EventFactory.createArrayBacked(Listener.class, event -> true, listeners -> event -> {
				for (Listener listener : listeners) {
					if (!listener.handle(event))
						return false;
				}

				return true;
			});

			private final MatrixStack poseStack;
			private final BakedStrutModel model;
			private final VertexConsumerProvider bufferSource;
			private final float partialTick;
			private final int packedLight;

			public Pre(StrutArmorRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
				super(renderer);

				this.poseStack = poseStack;
				this.model = model;
				this.bufferSource = bufferSource;
				this.partialTick = partialTick;
				this.packedLight = packedLight;
			}

			public MatrixStack getPoseStack() {
				return this.poseStack;
			}

			public BakedStrutModel getModel() {
				return this.model;
			}

			public VertexConsumerProvider getBufferSource() {
				return this.bufferSource;
			}

			public float getPartialTick() {
				return this.partialTick;
			}

			public int getPackedLight() {
				return this.packedLight;
			}

			/**
			 * Event listener interface for the Armor.Pre StrutRenderEvent
			 * <p>
			 * Return false to cancel the render pass
			 */
			@FunctionalInterface
			public interface Listener {
				boolean handle(Pre event);
			}
		}

		/**
		 * Post-render event for armor pieces being rendered by {@link StrutEntityRenderer}
		 * <p>
		 * This event is called after {@link StrutRenderer#postRender}
		 */
		public static class Post extends Armor {
			public static final Event<Listener> EVENT = EventFactory.createArrayBacked(Listener.class, post -> {}, listeners -> event -> {
				for (Listener listener : listeners) {
					listener.handle(event);
				}
			});

			private final MatrixStack poseStack;
			private final BakedStrutModel model;
			private final VertexConsumerProvider bufferSource;
			private final float partialTick;
			private final int packedLight;

			public Post(StrutArmorRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
				super(renderer);

				this.poseStack = poseStack;
				this.model = model;
				this.bufferSource = bufferSource;
				this.partialTick = partialTick;
				this.packedLight = packedLight;
			}

			public MatrixStack getPoseStack() {
				return this.poseStack;
			}

			public BakedStrutModel getModel() {
				return this.model;
			}

			public VertexConsumerProvider getBufferSource() {
				return this.bufferSource;
			}

			public float getPartialTick() {
				return this.partialTick;
			}

			public int getPackedLight() {
				return this.packedLight;
			}

			/**
			 * Event listener interface for the Armor.Post StrutRenderEvent
			 */
			@FunctionalInterface
			public interface Listener {
				void handle(Post event);
			}
		}

	}

	/**
	 * Renderer events for {@link BlockEntity BlockEntities} being rendered by {@link StrutBlockRenderer}
	 */
	abstract class Block implements StrutRenderEvent {
		private final StrutBlockRenderer<?> renderer;

		public Block(StrutBlockRenderer<?> renderer) {
			this.renderer = renderer;
		}

		/**
		 * Returns the renderer for this event
		 */
		@Override
		public StrutBlockRenderer<?> getRenderer() {
			return this.renderer;
		}

		/**
		 * Shortcut method for retrieving the block entity being rendered
		 */
		public BlockEntity getBlockEntity() {
			return getRenderer().getAnimatable();
		}

		/**
		 * Pre-render event for block entities being rendered by {@link StrutBlockRenderer}
		 * <p>
		 * This event is called before rendering, but after {@link StrutRenderer#preRender}
		 * <p>
		 * This event is <u>cancellable</u><br>
		 * If the event is cancelled by returning false in the {@link Listener}, the block entity will not be rendered and the corresponding {@link Post} event will not be fired.
		 */
		public static class Pre extends Block {
			public static final Event<Listener> EVENT = EventFactory.createArrayBacked(Listener.class, event -> true, listeners -> event -> {
				for (Listener listener : listeners) {
					if (!listener.handle(event))
						return false;
				}

				return true;
			});

			private final MatrixStack poseStack;
			private final BakedStrutModel model;
			private final VertexConsumerProvider bufferSource;
			private final float partialTick;
			private final int packedLight;

			public Pre(StrutBlockRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
				super(renderer);

				this.poseStack = poseStack;
				this.model = model;
				this.bufferSource = bufferSource;
				this.partialTick = partialTick;
				this.packedLight = packedLight;
			}

			public MatrixStack getPoseStack() {
				return this.poseStack;
			}

			public BakedStrutModel getModel() {
				return this.model;
			}

			public VertexConsumerProvider getBufferSource() {
				return this.bufferSource;
			}

			public float getPartialTick() {
				return this.partialTick;
			}

			public int getPackedLight() {
				return this.packedLight;
			}

			/**
			 * Event listener interface for the Block.Pre StrutRenderEvent
			 * <p>
			 * Return false to cancel the render pass
			 */
			@FunctionalInterface
			public interface Listener {
				boolean handle(Pre event);
			}
		}

		/**
		 * Post-render event for block entities being rendered by {@link StrutBlockRenderer}
		 * <p>
		 * This event is called after {@link StrutRenderer#postRender}
		 */
		public static class Post extends Block {
			public static final Event<Listener> EVENT = EventFactory.createArrayBacked(Listener.class, post -> {}, listeners -> event -> {
				for (Listener listener : listeners) {
					listener.handle(event);
				}
			});

			private final MatrixStack poseStack;
			private final BakedStrutModel model;
			private final VertexConsumerProvider bufferSource;
			private final float partialTick;
			private final int packedLight;

			public Post(StrutBlockRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
				super(renderer);

				this.poseStack = poseStack;
				this.model = model;
				this.bufferSource = bufferSource;
				this.partialTick = partialTick;
				this.packedLight = packedLight;
			}

			public MatrixStack getPoseStack() {
				return this.poseStack;
			}

			public BakedStrutModel getModel() {
				return this.model;
			}

			public VertexConsumerProvider getBufferSource() {
				return this.bufferSource;
			}

			public float getPartialTick() {
				return this.partialTick;
			}

			public int getPackedLight() {
				return this.packedLight;
			}

			/**
			 * Event listener interface for the Block.Post StrutRenderEvent
			 */
			@FunctionalInterface
			public interface Listener {
				void handle(Post event);
			}
		}

	}

	/**
	 * Renderer events for {@link net.minecraft.entity.Entity Entities} being rendered by {@link StrutEntityRenderer}, as well as
	 * {@code DynamicStrutEntityRenderer}
	 */
	abstract class Entity implements StrutRenderEvent {
		private final StrutEntityRenderer<?> renderer;

		public Entity(StrutEntityRenderer<?> renderer) {
			this.renderer = renderer;
		}

		/**
		 * Returns the renderer for this event
		 */
		@Override
		public StrutEntityRenderer<?> getRenderer() {
			return this.renderer;
		}

		/**
		 * Shortcut method for retrieving the entity being rendered
		 */
		public net.minecraft.entity.Entity getEntity() {
			return this.renderer.getAnimatable();
		}

		/**
		 * Pre-render event for entities being rendered by {@link StrutEntityRenderer}
		 * <p>
		 * This event is called before rendering, but after {@link StrutRenderer#preRender}
		 * <p>
		 * This event is <u>cancellable</u><br>
		 * If the event is cancelled by returning false in the {@link Listener}, the entity will not be rendered and the corresponding {@link Post} event will not be fired.
		 */
		public static class Pre extends Entity {
			public static final Event<Listener> EVENT = EventFactory.createArrayBacked(Listener.class, event -> true, listeners -> event -> {
				for (Listener listener : listeners) {
					if (!listener.handle(event))
						return false;
				}

				return true;
			});

			private final MatrixStack poseStack;
			private final BakedStrutModel model;
			private final VertexConsumerProvider bufferSource;
			private final float partialTick;
			private final int packedLight;

			public Pre(StrutEntityRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
				super(renderer);

				this.poseStack = poseStack;
				this.model = model;
				this.bufferSource = bufferSource;
				this.partialTick = partialTick;
				this.packedLight = packedLight;
			}

			public MatrixStack getPoseStack() {
				return this.poseStack;
			}

			public BakedStrutModel getModel() {
				return this.model;
			}

			public VertexConsumerProvider getBufferSource() {
				return this.bufferSource;
			}

			public float getPartialTick() {
				return this.partialTick;
			}

			public int getPackedLight() {
				return this.packedLight;
			}

			/**
			 * Event listener interface for the Armor.Pre StrutRenderEvent
			 * <p>
			 * Return false to cancel the render pass
			 */
			@FunctionalInterface
			public interface Listener {
				boolean handle(Pre event);
			}
		}

		/**
		 * Post-render event for entities being rendered by {@link StrutEntityRenderer}
		 * <p>
		 * This event is called after {@link StrutRenderer#postRender}
		 */
		public static class Post extends Entity {
			public static final Event<Listener> EVENT = EventFactory.createArrayBacked(Listener.class, post -> {}, listeners -> event -> {
				for (Listener listener : listeners) {
					listener.handle(event);
				}
			});

			private final MatrixStack poseStack;
			private final BakedStrutModel model;
			private final VertexConsumerProvider bufferSource;
			private final float partialTick;
			private final int packedLight;

			public Post(StrutEntityRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
				super(renderer);

				this.poseStack = poseStack;
				this.model = model;
				this.bufferSource = bufferSource;
				this.partialTick = partialTick;
				this.packedLight = packedLight;
			}

			public MatrixStack getPoseStack() {
				return this.poseStack;
			}

			public BakedStrutModel getModel() {
				return this.model;
			}

			public VertexConsumerProvider getBufferSource() {
				return this.bufferSource;
			}

			public float getPartialTick() {
				return this.partialTick;
			}

			public int getPackedLight() {
				return this.packedLight;
			}

			/**
			 * Event listener interface for the Entity.Post StrutRenderEvent
			 */
			@FunctionalInterface
			public interface Listener {
				void handle(Post event);
			}
		}

	}

	/**
	 * Renderer events for {@link ItemStack Items} being rendered by {@link StrutItemRenderer}
	 */
	abstract class Item implements StrutRenderEvent {
		private final StrutItemRenderer<?> renderer;

		public Item(StrutItemRenderer<?> renderer) {
			this.renderer = renderer;
		}

		/**
		 * Returns the renderer for this event
		 */
		@Override
		public StrutItemRenderer<?> getRenderer() {
			return this.renderer;
		}

		/**
		 * Shortcut method for retrieving the ItemStack being rendered
		 */
		public ItemStack getItemStack() {
			return getRenderer().getCurrentItemStack();
		}

		/**
		 * Pre-render event for armor being rendered by {@link StrutItemRenderer}
		 * <p>
		 * This event is called before rendering, but after {@link StrutRenderer#preRender}
		 * <p>
		 * This event is <u>cancellable</u><br>
		 * If the event is cancelled by returning false in the {@link Listener}, the ItemStack will not be rendered and the corresponding {@link Post} event will not be fired.
		 */
		public static class Pre extends Item {
			public static final Event<Listener> EVENT = EventFactory.createArrayBacked(Listener.class, event -> true, listeners -> event -> {
				for (Listener listener : listeners) {
					if (!listener.handle(event))
						return false;
				}

				return true;
			});

			private final MatrixStack poseStack;
			private final BakedStrutModel model;
			private final VertexConsumerProvider bufferSource;
			private final float partialTick;
			private final int packedLight;

			public Pre(StrutItemRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
				super(renderer);

				this.poseStack = poseStack;
				this.model = model;
				this.bufferSource = bufferSource;
				this.partialTick = partialTick;
				this.packedLight = packedLight;
			}

			public MatrixStack getPoseStack() {
				return this.poseStack;
			}

			public BakedStrutModel getModel() {
				return this.model;
			}

			public VertexConsumerProvider getBufferSource() {
				return this.bufferSource;
			}

			public float getPartialTick() {
				return this.partialTick;
			}

			public int getPackedLight() {
				return this.packedLight;
			}

			/**
			 * Event listener interface for the Item.Pre StrutRenderEvent
			 * <p>
			 * Return false to cancel the render pass
			 */
			@FunctionalInterface
			public interface Listener {
				boolean handle(Pre event);
			}
		}

		/**
		 * Post-render event for ItemStacks being rendered by {@link StrutItemRenderer}
		 * <p>
		 * This event is called after {@link StrutRenderer#postRender}
		 */
		public static class Post extends Item {
			public static final Event<Listener> EVENT = EventFactory.createArrayBacked(Listener.class, post -> {}, listeners -> event -> {
				for (Listener listener : listeners) {
					listener.handle(event);
				}
			});

			private final MatrixStack poseStack;
			private final BakedStrutModel model;
			private final VertexConsumerProvider bufferSource;
			private final float partialTick;
			private final int packedLight;

			public Post(StrutItemRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
				super(renderer);

				this.poseStack = poseStack;
				this.model = model;
				this.bufferSource = bufferSource;
				this.partialTick = partialTick;
				this.packedLight = packedLight;
			}

			public MatrixStack getPoseStack() {
				return this.poseStack;
			}

			public BakedStrutModel getModel() {
				return this.model;
			}

			public VertexConsumerProvider getBufferSource() {
				return this.bufferSource;
			}

			public float getPartialTick() {
				return this.partialTick;
			}

			public int getPackedLight() {
				return this.packedLight;
			}

			/**
			 * Event listener interface for the Item.Post StrutRenderEvent
			 */
			@FunctionalInterface
			public interface Listener {
				void handle(Post event);
			}
		}

	}

	/**
	 * Renderer events for miscellaneous {@link StrutAnimatable animatables} being rendered by {@link StrutObjectRenderer}
	 */
	abstract class Object implements StrutRenderEvent {
		private final StrutObjectRenderer<?> renderer;

		public Object(StrutObjectRenderer<?> renderer) {
			this.renderer = renderer;
		}

		/**
		 * Returns the renderer for this event
		 */
		@Override
		public StrutObjectRenderer<?> getRenderer() {
			return this.renderer;
		}

		/**
		 * Pre-render event for miscellaneous animatables being rendered by {@link StrutObjectRenderer}
		 * <p>
		 * This event is called before rendering, but after {@link StrutRenderer#preRender}
		 * <p>
		 * This event is <u>cancellable</u><br>
		 * If the event is cancelled by returning false in the {@link Listener}, the object will not be rendered and the corresponding {@link Post} event will not be fired.
		 */
		public static class Pre extends Object {
			public static final Event<Listener> EVENT = EventFactory.createArrayBacked(Listener.class, event -> true, listeners -> event -> {
				for (Listener listener : listeners) {
					if (!listener.handle(event))
						return false;
				}

				return true;
			});

			private final MatrixStack poseStack;
			private final BakedStrutModel model;
			private final VertexConsumerProvider bufferSource;
			private final float partialTick;
			private final int packedLight;

			public Pre(StrutObjectRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
				super(renderer);

				this.poseStack = poseStack;
				this.model = model;
				this.bufferSource = bufferSource;
				this.partialTick = partialTick;
				this.packedLight = packedLight;
			}

			public MatrixStack getPoseStack() {
				return this.poseStack;
			}

			public BakedStrutModel getModel() {
				return this.model;
			}

			public VertexConsumerProvider getBufferSource() {
				return this.bufferSource;
			}

			public float getPartialTick() {
				return this.partialTick;
			}

			public int getPackedLight() {
				return this.packedLight;
			}

			/**
			 * Event listener interface for the Object.Pre StrutRenderEvent
			 * <p>
			 * Return false to cancel the render pass
			 */
			@FunctionalInterface
			public interface Listener {
				boolean handle(Pre event);
			}
		}

		/**
		 * Post-render event for miscellaneous animatables being rendered by {@link StrutObjectRenderer}
		 * <p>
		 * This event is called after {@link StrutRenderer#postRender}
		 */
		public static class Post extends Object {
			public static final Event<Listener> EVENT = EventFactory.createArrayBacked(Listener.class, post -> {}, listeners -> event -> {
				for (Listener listener : listeners) {
					listener.handle(event);
				}
			});

			private final MatrixStack poseStack;
			private final BakedStrutModel model;
			private final VertexConsumerProvider bufferSource;
			private final float partialTick;
			private final int packedLight;

			public Post(StrutObjectRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
				super(renderer);

				this.poseStack = poseStack;
				this.model = model;
				this.bufferSource = bufferSource;
				this.partialTick = partialTick;
				this.packedLight = packedLight;
			}

			public MatrixStack getPoseStack() {
				return this.poseStack;
			}

			public BakedStrutModel getModel() {
				return this.model;
			}

			public VertexConsumerProvider getBufferSource() {
				return this.bufferSource;
			}

			public float getPartialTick() {
				return this.partialTick;
			}

			public int getPackedLight() {
				return this.packedLight;
			}

			/**
			 * Event listener interface for the Object.Post StrutRenderEvent
			 */
			@FunctionalInterface
			public interface Listener {
				void handle(Post event);
			}
		}

	}

	/**
	 * Renderer events for miscellaneous {@link com.sandymandy.strut.animatable.StrutReplacedEntity replaced entities} being rendered by {@link StrutReplacedEntityRenderer}
	 */
	abstract class ReplacedEntity implements StrutRenderEvent {
		private final StrutReplacedEntityRenderer<?, ?> renderer;

		public ReplacedEntity(StrutReplacedEntityRenderer<?, ?> renderer) {
			this.renderer = renderer;
		}

		/**
		 * Returns the renderer for this event
		 */
		@Override
		public StrutReplacedEntityRenderer<?, ?> getRenderer() {
			return this.renderer;
		}

		/**
		 * Shortcut method to get the Entity currently being rendered
		 */
		public net.minecraft.entity.Entity getReplacedEntity() {
			return getRenderer().getCurrentEntity();
		}

		/**
		 * Pre-render event for replaced entities being rendered by {@link StrutReplacedEntityRenderer}
		 * <p>
		 * This event is called before rendering, but after {@link StrutRenderer#preRender}
		 * <p>
		 * This event is <u>cancellable</u><br>
		 * If the event is cancelled by returning false in the {@link Listener}, the entity will not be rendered and the corresponding {@link Post} event will not be fired.
		 */
		public static class Pre extends ReplacedEntity {
			public static final Event<Listener> EVENT = EventFactory.createArrayBacked(Listener.class, event -> true, listeners -> event -> {
				for (Listener listener : listeners) {
					if (!listener.handle(event))
						return false;
				}

				return true;
			});

			private final MatrixStack poseStack;
			private final BakedStrutModel model;
			private final VertexConsumerProvider bufferSource;
			private final float partialTick;
			private final int packedLight;

			public Pre(StrutReplacedEntityRenderer<?, ?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
				super(renderer);

				this.poseStack = poseStack;
				this.model = model;
				this.bufferSource = bufferSource;
				this.partialTick = partialTick;
				this.packedLight = packedLight;
			}

			public MatrixStack getPoseStack() {
				return this.poseStack;
			}

			public BakedStrutModel getModel() {
				return this.model;
			}

			public VertexConsumerProvider getBufferSource() {
				return this.bufferSource;
			}

			public float getPartialTick() {
				return this.partialTick;
			}

			public int getPackedLight() {
				return this.packedLight;
			}

			/**
			 * Event listener interface for the ReplacedEntity.Pre StrutRenderEvent
			 * <p>
			 * Return false to cancel the render pass
			 */
			@FunctionalInterface
			public interface Listener {
				boolean handle(Pre event);
			}
		}

		/**
		 * Post-render event for replaced entities being rendered by {@link StrutReplacedEntityRenderer}
		 * <p>
		 * This event is called after {@link StrutRenderer#postRender}
		 */
		public static class Post extends ReplacedEntity {
			public static final Event<Listener> EVENT = EventFactory.createArrayBacked(Listener.class, post -> {}, listeners -> event -> {
				for (Listener listener : listeners) {
					listener.handle(event);
				}
			});

			private final MatrixStack poseStack;
			private final BakedStrutModel model;
			private final VertexConsumerProvider bufferSource;
			private final float partialTick;
			private final int packedLight;

			public Post(StrutReplacedEntityRenderer<?, ?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
				super(renderer);

				this.poseStack = poseStack;
				this.model = model;
				this.bufferSource = bufferSource;
				this.partialTick = partialTick;
				this.packedLight = packedLight;
			}

			public MatrixStack getPoseStack() {
				return this.poseStack;
			}

			public BakedStrutModel getModel() {
				return this.model;
			}

			public VertexConsumerProvider getBufferSource() {
				return this.bufferSource;
			}

			public float getPartialTick() {
				return this.partialTick;
			}

			public int getPackedLight() {
				return this.packedLight;
			}

			/**
			 * Event listener interface for the ReplacedEntity.Post StrutRenderEvent
			 */
			@FunctionalInterface
			public interface Listener {
				void handle(Post event);
			}
		}

	}
}
