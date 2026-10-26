package com.sandymandy.strut.service;

import com.sandymandy.strut.network.packet.EyeOffsetPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.Entity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.constant.dataticket.SerializableDataTicket;
import com.sandymandy.strut.network.packet.*;
import com.sandymandy.strut.util.StrutUtil;

/**
 * Loader-agnostic service interface for Strut's networking functionalities
 */
public interface StrutNetworking {
    static void init() {
        registerPacket(BlockEntityDataSyncPacket.TYPE, BlockEntityDataSyncPacket.CODEC, true);
        registerPacket(EntityDataSyncPacket.TYPE, EntityDataSyncPacket.CODEC, true);
        registerPacket(SingletonDataSyncPacket.TYPE, SingletonDataSyncPacket.CODEC, true);

        registerPacket(BlockEntityAnimTriggerPacket.TYPE, BlockEntityAnimTriggerPacket.CODEC, true);
        registerPacket(SingletonAnimTriggerPacket.TYPE, SingletonAnimTriggerPacket.CODEC, true);
        registerPacket(EntityAnimTriggerPacket.TYPE, EntityAnimTriggerPacket.CODEC, true);

        registerPacket(StopTriggeredBlockEntityAnimPacket.TYPE, StopTriggeredBlockEntityAnimPacket.CODEC, true);
        registerPacket(StopTriggeredEntityAnimPacket.TYPE, StopTriggeredEntityAnimPacket.CODEC, true);
        registerPacket(StopTriggeredSingletonAnimPacket.TYPE, StopTriggeredSingletonAnimPacket.CODEC, true);

        registerPacket(StatelessEntityPlayAnimPacket.TYPE, StatelessEntityPlayAnimPacket.CODEC, true);
        registerPacket(StatelessBlockEntityPlayAnimPacket.TYPE, StatelessBlockEntityPlayAnimPacket.CODEC, true);
        registerPacket(StatelessSingletonPlayAnimPacket.TYPE, StatelessSingletonPlayAnimPacket.CODEC, true);

        registerPacket(StatelessEntityStopAnimPacket.TYPE, StatelessEntityStopAnimPacket.CODEC, true);
        registerPacket(StatelessBlockEntityStopAnimPacket.TYPE, StatelessBlockEntityStopAnimPacket.CODEC, true);
        registerPacket(StatelessSingletonStopAnimPacket.TYPE, StatelessSingletonStopAnimPacket.CODEC, true);

        // Server-authoritative playback state.
        registerPacket(AnimationPlaybackSnapshotPacket.TYPE, AnimationPlaybackSnapshotPacket.CODEC, true);
        registerPacket(AnimationPlaybackDeltaPacket.TYPE, AnimationPlaybackDeltaPacket.CODEC, true);
        // The one server-bound packet - every other packet above is client-bound
        registerPacket(AnimationResyncRequestPacket.TYPE, AnimationResyncRequestPacket.CODEC, false);
        // Player bodies: where the eye bone of an immersive body is, for what the player aims at
        registerPacket(EyeOffsetPacket.TYPE, EyeOffsetPacket.CODEC, false);

        // Server-authoritative bone overrides -
        // rides the same start-tracking/tick/resync triggers as the animation packets just above,
        // via ServerAnimationStates' own per-entity Entry
        registerPacket(BoneOverrideSnapshotPacket.TYPE, BoneOverrideSnapshotPacket.CODEC, true);
        registerPacket(BoneOverrideDeltaPacket.TYPE, BoneOverrideDeltaPacket.CODEC, true);

        // Entity-declared attachments
        // - rides the identical lifecycle the bone-override packets just above do
        // Block entities' bone overrides: the full set for one position, and the one request a client makes for it when the block loads
        registerPacket(BlockBoneOverrideSnapshotPacket.TYPE, BlockBoneOverrideSnapshotPacket.CODEC, true);
        registerPacket(BlockBoneOverridesRequestPacket.TYPE, BlockBoneOverridesRequestPacket.CODEC, false);

        registerPacket(AttachmentSnapshotPacket.TYPE, AttachmentSnapshotPacket.CODEC, true);
        registerPacket(AttachmentDeltaPacket.TYPE, AttachmentDeltaPacket.CODEC, true);
    }

    /**
     * Register a Strut packet for use
     */
    @ApiStatus.Internal
    private static <B extends PacketByteBuf, P extends MultiloaderPacket> void registerPacket(CustomPayload.Id<P> payloadType, PacketCodec<B, P> codec, boolean isClientBound) {
        StrutServices.NETWORK.registerPacketInternal(payloadType, codec, isClientBound);
    }

    /**
     * Register a Strut packet for use
     * <p>
     * <b><u>FOR STRUT USE ONLY</u></b>
     */
    @ApiStatus.Internal
    <B extends PacketByteBuf, P extends MultiloaderPacket> void registerPacketInternal(CustomPayload.Id<P> payloadType, PacketCodec<B, P> codec, boolean isClientBound);

    /**
     * Send a packet to all players currently tracking a given entity
     * <p>
     * Good as a shortcut for sending a packet to all players that may have an interest in a given entity or its dealings
     * <p>
     * Will also send the packet to the entity itself if the entity is also a player
     */
    void sendToAllPlayersTrackingEntity(MultiloaderPacket packet, Entity trackingEntity);

    /**
     * Send a packet to all players tracking a given block position
     */
    void sendToAllPlayersTrackingBlock(MultiloaderPacket packet, ServerWorld level, BlockPos pos);

    /**
     * Send a packet to the given player
     */
    void sendToPlayer(MultiloaderPacket packet, ServerPlayerEntity player);

    /**
     * Sync a {@link SerializableDataTicket} from server to clientside for the given block
     */
    default <D> void syncBlockEntityAnimData(BlockPos pos, SerializableDataTicket<D> dataTicket, D data, ServerWorld level) {
        sendToAllPlayersTrackingBlock(new BlockEntityDataSyncPacket<>(pos, dataTicket, data), level, pos);
    }

    /**
     * Sync a {@link SerializableDataTicket} from server to clientside for the given entity
     */
    default <D> void syncEntityAnimData(Entity entity, boolean isReplacedEntity, SerializableDataTicket<D> dataTicket, D data) {
        sendToAllPlayersTrackingEntity(new EntityDataSyncPacket<>(entity.getId(), isReplacedEntity, dataTicket, data), entity);
    }

    /**
     * Sync a {@link SerializableDataTicket} from server to clientside for the given {@link com.sandymandy.strut.animatable.SingletonStrutAnimatable SingletonStrutAnimatable}
     *
     * @deprecated Use {@link #syncSingletonAnimData(StrutAnimatable, long, SerializableDataTicket, Object, Entity)}
     */
    @Deprecated(forRemoval = true)
    default <D> void syncSingletonAnimData(long instanceId, SerializableDataTicket<D> dataTicket, D data, Entity entityToTrack) {
        //sendToAllPlayersTrackingEntity(new SingletonDataSyncPacket<>(getClass().getName(), instanceId, dataTicket, data), entityToTrack);
    }

    /**
     * Sync a {@link SerializableDataTicket} from server to clientside for the given {@link com.sandymandy.strut.animatable.SingletonStrutAnimatable SingletonStrutAnimatable}
     *
     * @deprecated Use {@link #syncSingletonAnimData(StrutAnimatable, long, SerializableDataTicket, Object, Entity)}
     */
    @Deprecated(forRemoval = true)
    default <D> void syncSingletonAnimData(Class<?> animatableClass, long instanceId, SerializableDataTicket<D> dataTicket, D data, Entity entityToTrack) {
        //sendToAllPlayersTrackingEntity(new SingletonDataSyncPacket<>(animatableClass.getName(), instanceId, dataTicket, data), entityToTrack);
    }

    /**
     * Sync a {@link SerializableDataTicket} from server to clientside for the given {@link com.sandymandy.strut.animatable.SingletonStrutAnimatable SingletonStrutAnimatable}
     */
    default <D> void syncSingletonAnimData(StrutAnimatable animatable, long instanceId, SerializableDataTicket<D> dataTicket, D data, Entity entityToTrack) {
        sendToAllPlayersTrackingEntity(new SingletonDataSyncPacket<>(StrutUtil.getSyncedSingletonAnimatableId(animatable), instanceId, dataTicket, data), entityToTrack);
    }

    /**
     * {@link com.sandymandy.strut.animatable.StrutBlockEntity#triggerAnim(String, String) Trigger} an animation for the given {@link com.sandymandy.strut.animatable.StrutBlockEntity StrutBlockEntity}
     */
    default void triggerBlockEntityAnim(BlockPos pos, @Nullable String controllerName, String animName, ServerWorld level) {
        sendToAllPlayersTrackingBlock(new BlockEntityAnimTriggerPacket(pos, controllerName == null ? "" : controllerName, animName), level, pos);
    }

    /**
     * {@link com.sandymandy.strut.animatable.StrutEntity#triggerAnim(String, String) Trigger} an animation for the given {@link com.sandymandy.strut.animatable.StrutEntity StrutEntity}
     */
    default void triggerEntityAnim(Entity entity, boolean isReplacedEntity, @Nullable String controllerName, String animName) {
        sendToAllPlayersTrackingEntity(new EntityAnimTriggerPacket(entity.getId(), isReplacedEntity, controllerName == null ? "" : controllerName, animName), entity);
    }

    /**
     * {@link com.sandymandy.strut.animatable.SingletonStrutAnimatable#triggerAnim(Entity, long, String, String) Trigger} an animation for the given {@link com.sandymandy.strut.animatable.SingletonStrutAnimatable SingletonStrutAnimatable}
     *
     * @deprecated Use {@link #triggerSingletonAnim(StrutAnimatable, Entity, long, String, String)}
     */
    @Deprecated(forRemoval = true)
    default void triggerSingletonAnim(String animatableClassName, Entity entityToTrack, long instanceId, @Nullable String controllerName, String animName) {
        sendToAllPlayersTrackingEntity(new SingletonAnimTriggerPacket(animatableClassName, instanceId, controllerName, animName), entityToTrack);
    }

    /**
     * {@link com.sandymandy.strut.animatable.SingletonStrutAnimatable#triggerAnim(Entity, long, String, String) Trigger} an animation for the given {@link com.sandymandy.strut.animatable.SingletonStrutAnimatable SingletonStrutAnimatable}
     *
     * @deprecated Use {@link #triggerSingletonAnim(StrutAnimatable, Entity, long, String, String)}
     */
    @Deprecated(forRemoval = true)
    default void triggerSingletonAnim(Class<?> animatableClass, Entity entityToTrack, long instanceId, @Nullable String controllerName, String animName) {
        //triggerSingletonAnim(animatableClass.getName(), entityToTrack, instanceId, controllerName == null ? "" : controllerName, animName);
    }

    /**
     * {@link com.sandymandy.strut.animatable.SingletonStrutAnimatable#triggerAnim(Entity, long, String, String) Trigger} an animation for the given {@link com.sandymandy.strut.animatable.SingletonStrutAnimatable SingletonStrutAnimatable}
     */
    default void triggerSingletonAnim(StrutAnimatable animatable, Entity entityToTrack, long instanceId, @Nullable String controllerName, String animName) {
        triggerSingletonAnim(StrutUtil.getSyncedSingletonAnimatableId(animatable), entityToTrack, instanceId, controllerName == null ? "" : controllerName, animName);
    }

    /**
     * {@link com.sandymandy.strut.animatable.StrutBlockEntity#stopTriggeredAnim(String, String) Stop} a previously triggered
     * animation for the given {@link com.sandymandy.strut.animatable.StrutBlockEntity StrutBlockEntity}
     */
    default void stopTriggeredBlockEntityAnim(BlockPos pos, ServerWorld level, @Nullable String controllerName, @Nullable String animName) {
        sendToAllPlayersTrackingBlock(new StopTriggeredBlockEntityAnimPacket(pos, controllerName == null ? "" : controllerName, animName == null ? "" : animName), level, pos);
    }

    /**
     * {@link com.sandymandy.strut.animatable.StrutEntity#stopTriggeredAnim(String, String) Stop} a previously triggered
     * animation for the given {@link com.sandymandy.strut.animatable.StrutEntity StrutEntity}
     */
    default void stopTriggeredEntityAnim(Entity entity, boolean isReplacedEntity, @Nullable String controllerName, @Nullable String animName) {
        sendToAllPlayersTrackingEntity(new StopTriggeredEntityAnimPacket(entity.getId(), isReplacedEntity, controllerName == null ? "" : controllerName, animName == null ? "" : animName), entity);
    }

    /**
     * {@link com.sandymandy.strut.animatable.SingletonStrutAnimatable#stopTriggeredAnim(Entity, long, String, String) Stop}
     * a previously triggered animation for the given {@link com.sandymandy.strut.animatable.SingletonStrutAnimatable SingletonStrutAnimatable}
     *
     * @deprecated Use {@link #stopTriggeredSingletonAnim(StrutAnimatable, Entity, long, String, String)}
     */
    @Deprecated(forRemoval = true)
    default void stopTriggeredSingletonAnim(Class<?> animatableClass, Entity entityToTrack, long instanceId, @Nullable String controllerName, @Nullable String animName) {
        sendToAllPlayersTrackingEntity(new StopTriggeredSingletonAnimPacket(animatableClass.getName() + "0", instanceId, controllerName == null ? "" : controllerName, animName == null ? "" : animName), entityToTrack);
    }

    /**
     * {@link com.sandymandy.strut.animatable.SingletonStrutAnimatable#stopTriggeredAnim(Entity, long, String, String) Stop}
     * a previously triggered animation for the given {@link com.sandymandy.strut.animatable.SingletonStrutAnimatable SingletonStrutAnimatable}
     */
    default void stopTriggeredSingletonAnim(StrutAnimatable animatable, Entity entityToTrack, long instanceId, @Nullable String controllerName, @Nullable String animName) {
        sendToAllPlayersTrackingEntity(new StopTriggeredSingletonAnimPacket(StrutUtil.getSyncedSingletonAnimatableId(animatable), instanceId, controllerName == null ? "" : controllerName, animName == null ? "" : animName), entityToTrack);
    }
}
