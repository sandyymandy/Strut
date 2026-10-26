package com.sandymandy.strut;

import com.sandymandy.strut.animatable.StrutBlockEntity;
import com.sandymandy.strut.animation.attachment.ClientModelAttachments;
import com.sandymandy.strut.animation.attachment.WearCommand;
import com.sandymandy.strut.armour.ArmourClient;
import com.sandymandy.strut.armour.ArmourCommand;
import com.sandymandy.strut.armour.ArmourData;
import com.sandymandy.strut.network.packet.BlockBoneOverridesRequestPacket;
import com.sandymandy.strut.perf.SeamDebug;
import com.sandymandy.strut.player.client.FirstPersonBody;
import com.sandymandy.strut.renderer.RenderPass;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import com.sandymandy.strut.event.type.StrutEventTypes;
import com.sandymandy.strut.animation.attachment.ClientAttachmentStates;
import com.sandymandy.strut.animation.authority.ClientAnimationState;
import com.sandymandy.strut.animation.authority.ClientAnimationStates;
import com.sandymandy.strut.assetsync.client.ClientAssetSyncFabric;
import com.sandymandy.strut.client.ClientBindingsReloader;
import com.sandymandy.strut.client.ClientEventSweep;
import com.sandymandy.strut.animation.override.ClientBoneOverrideStates;
import com.sandymandy.strut.cache.StrutCache;
import com.sandymandy.strut.model.ModelBounds;
import com.sandymandy.strut.network.packet.MultiloaderPacket;
import com.sandymandy.strut.perf.RenderPerfOverlay;
import com.sandymandy.strut.renderer.gpu.GpuBatchQueue;
import com.sandymandy.strut.renderer.gpu.GpuModelCache;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.model.StrutModel;
import com.sandymandy.strut.renderer.StrutEntityRenderer;
import com.sandymandy.strut.animatable.StrutItem;
import com.sandymandy.strut.animatable.client.StrutRenderProviders;
import com.sandymandy.strut.renderer.StrutArmorRenderer;
import com.sandymandy.strut.renderer.StrutBlockRenderer;
import com.sandymandy.strut.renderer.StrutItemRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.item.Item;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceReloader;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;
import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Main Strut client entrypoint
 */
public class StrutClient implements ClientModInitializer {
    /**
     * Renders an entity type with a Strut model found by convention from the type's registry id
     * <p>
     * {@code mymod:ava} renders {@code mymod:strut/models/entity/ava.strutmodel} with {@code mymod:textures/entity/ava.png} and
     * {@code mymod:strut/animations/entity/ava.strutanim}. Call it from your client initialiser, once per entity type - it replaces the
     * model class, the renderer class and the registration call
     */
    public static <T extends Entity & StrutAnimatable> void entity(EntityType<? extends T> type) {
        EntityRendererRegistry.register(type, context -> new StrutEntityRenderer<>(context, type));
    }

    /**
     * Renders an entity type with a model you supply - a {@link StrutModel#builder built} one for files that do not follow the
     * convention, or a {@link StrutModel#entity conventional} one with a part changed:
     * <pre>{@code
     * StrutClient.entity(MY_TYPE, StrutModel.<MyEntity>entity(id("my_entity")).withAltTexture(id("shared_skin")));
     * }</pre>
     */
    public static <T extends Entity & StrutAnimatable> void entity(EntityType<? extends T> type, StrutModel<T> model) {
        EntityRendererRegistry.register(type, context -> new StrutEntityRenderer<>(context, model));
    }

    /**
     * Renders an entity type with a renderer you build yourself: a subclass of {@link StrutEntityRenderer}, or one configured with
     * {@code withScale} and render layers. The registration line is the only boilerplate left
     */
    public static <T extends Entity & StrutAnimatable> void entity(EntityType<? extends T> type, EntityRendererFactory<T> factory) {
        EntityRendererRegistry.register(type, factory);
    }

    /**
     * Renders an item with a Strut model found by convention from the item's registry id: {@code <ns>:geo/item/<path>.strutmodel},
     * {@code <ns>:textures/item/<path>.png} and {@code <ns>:animations/item/<path>.strutanim}
     * <p>
     * Replaces the {@code createStrutRenderer} override and its anonymous render provider. The item's model json must have
     * {@code "parent": "builtin/entity"} so the game hands rendering to Strut. Items are also registered for animation syncing
     * automatically, so nothing is needed in the item's constructor
     */
    public static <T extends Item & StrutAnimatable> void item(T item) {
        StrutRenderProviders.registerItem(item, StrutRenderProviders.itemRecipe(item, null));
    }

    /**
     * Renders an item with a model you supply: a {@link StrutModel#builder built} one, or a {@link StrutModel#item conventional}
     * one with a part changed
     */
    public static <T extends Item & StrutAnimatable> void item(T item, StrutModel<T> model) {
        StrutRenderProviders.registerItem(item, StrutRenderProviders.itemRecipe(item, model));
    }

    /**
     * Renders an item with a renderer you build yourself, for a subclass of {@link StrutItemRenderer} or one with extra options
     */
    public static <T extends Item & StrutAnimatable> void item(T item, Supplier<? extends StrutItemRenderer<T>> renderer) {
        StrutRenderProviders.registerItem(item, renderer);
    }

    /**
     * Renders worn armor with a Strut model found by convention: {@code <ns>:geo/armor/<path>.strutmodel} and so on. An armor item
     * usually also wants {@link #item(Item)} for how it looks in the inventory and in the hand
     */
    public static <T extends Item & StrutItem> void armor(T item) {
        StrutRenderProviders.registerArmor(item, () -> new StrutArmorRenderer<>(item));
    }

    /**
     * Renders worn armor with a model you supply
     */
    public static <T extends Item & StrutItem> void armor(T item, StrutModel<T> model) {
        StrutRenderProviders.registerArmor(item, () -> new StrutArmorRenderer<>(model));
    }

    /**
     * Renders a block entity type with a Strut model found by convention from the type's registry id:
     * {@code <ns>:geo/block/<path>.strutmodel} and so on
     */
    public static <T extends BlockEntity & StrutAnimatable> void blockEntity(BlockEntityType<? extends T> type) {
        BlockEntityRendererFactories.register(type, context -> new StrutBlockRenderer<>(type));
    }

    /**
     * Renders a block entity type with a model you supply
     */
    public static <T extends BlockEntity & StrutAnimatable> void blockEntity(BlockEntityType<? extends T> type, StrutModel<T> model) {
        BlockEntityRendererFactories.register(type, context -> new StrutBlockRenderer<>(model));
    }

    /**
     * Renders a block entity type with a renderer you build yourself
     */
    public static <T extends BlockEntity & StrutAnimatable> void blockEntity(BlockEntityType<? extends T> type, BlockEntityRendererFactory<T> factory) {
        BlockEntityRendererFactories.register(type, factory);
    }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(FirstPersonBody::sendEyeToServer);

        // Enforcement, not ordering: every mod registers event types in its initialiser, which runs
        // before the client's first resource reload, so clips already see the full set. This just makes
        // a late registration a loud error instead of a type that some clips resolved without
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> StrutEventTypes.freeze());

        // The F9-toggled render-stats overlay
        RenderPerfOverlay.init();

        // F10 cycles the seam diagnostic (SeamDebug's own comment says what each mode shows)
        SeamDebug.init();


        // Frees a GpuModel's GL objects once its BakedStrutModel key
        // falls out of StrutCache.MODELS on reload - see GpuModelCache's own doc comment
        GpuModelCache.registerReloadListener();

        // Same reasoning, for the rest-pose bounding boxes
        // EntityVisibilityBoundsMixin reads
        ModelBounds.registerReloadListener();

        // Every Strut entity is drawn through GpuBatchQueue; the old StrutRenderLayer system (since removed) never
        // drew for a model this catches; see GpuBatchQueue's own doc comment
        GpuBatchQueue.init();
        RenderPass.init();

        // Drop a mirrored animation state
        // once its entity is gone, the same cleanup ServerAnimationStates does server-side on
        // ServerEntityEvents#ENTITY_UNLOAD. ClientEntityEvents is client-only, which is why this
        // registration lives here rather than in ClientAnimationStates itself or in Strut#onInitialize
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            ClientAnimationStates.remove(entity.getId());
            // The same cleanup, for bone overrides
            ClientBoneOverrideStates.remove(entity.getId());
            // The same cleanup, for attachments
            ClientAttachmentStates.remove(entity.getId());
            ClientModelAttachments.remove(entity.getId());
            ArmourClient.remove(entity.getId());
            // And the event dispatcher's cursors and log
            ClientEventSweep.forget(entity.getId());
        });

        // Block entities' bone overrides: a Strut block entity asks the server for its overrides as it loads, and its mirrored state is dropped as it
        // unloads (and all of them when the client leaves the world)
        ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, world) -> {
            if (blockEntity instanceof StrutBlockEntity && ClientPlayNetworking.canSend(BlockBoneOverridesRequestPacket.TYPE))
                ClientPlayNetworking.send(new BlockBoneOverridesRequestPacket(blockEntity.getPos()));
        });
        ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, world) -> ClientBoneOverrideStates.removeBlock(blockEntity.getPos()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientBoneOverrideStates.clearBlocks();
            ClientModelAttachments.clearEverything();
            ArmourClient.clearEverything();
        });

        // Fires each mirrored clip's client-authority
        // events from the client tick - not from rendering, so culled entities still play their sounds
        ClientEventSweep.init();
        ArmourData.init();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            WearCommand.register(dispatcher);
            ArmourCommand.register(dispatcher);
        });
        ClientAssetSyncFabric.init();

        // Per-character event bindings, read from resource packs on every reload
        ClientBindingsReloader.init();

        // Installs how ClientAnimationStates#requestResync actually reaches the network -
        // see that method's own doc comment on why the send call itself has to live here
        ClientAnimationStates.setSender(ClientPlayNetworking::send);

        // Stamp each applied animation change with the world time it arrived at, so a
        // client whose tick stalled does not lose the events between a packet and its next sweep
        ClientAnimationStates.setClock(() -> {
            var world = MinecraftClient.getInstance().world;

            return world != null ? world.getTime() : ClientAnimationState.Applied.UNKNOWN_TIME;
        });

        // Drops every FRAME-scoped
        // local bone override at the very end of each rendered frame, so one set this frame is gone
        // by the next unless something (the first-person-own-head pattern, for instance) sets it
        // again. WorldRenderEvents.END, not a tick event - "frame" here means rendered frame, and a
        // high-FPS client renders far more often than it ticks
        WorldRenderEvents.END.register(context -> ClientBoneOverrideStates.clearFrameScopedLocalForAll());

        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES)
                .registerReloadListener(new IdentifiableResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return StrutConstants.id("models_animations");
                    }

                    @Override
                    public CompletableFuture<Void> reload(ResourceReloader.Synchronizer synchronizer, ResourceManager manager,
                                                          Profiler prepareProfiler, Profiler applyProfiler, Executor prepareExecutor,
                                                          Executor applyExecutor) {
                        return StrutCache.reload(synchronizer, manager, prepareProfiler, applyProfiler, prepareExecutor, applyExecutor);
                    }
                });
    }

    @ApiStatus.Internal
    public static <P extends MultiloaderPacket> void registerPacket(CustomPayload.Id<P> packetType) {
        ClientPlayNetworking.registerGlobalReceiver(packetType, (packet, context) -> packet.receiveMessage(context.player(), context.client()::execute));
    }
}
