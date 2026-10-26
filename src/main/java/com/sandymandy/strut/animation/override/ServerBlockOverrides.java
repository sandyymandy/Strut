package com.sandymandy.strut.animation.override;

import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.animatable.StrutBlockEntity;
import com.sandymandy.strut.network.packet.BlockBoneOverrideSnapshotPacket;
import com.sandymandy.strut.network.packet.BoneOverrideSnapshotPacket;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The authoritative bone overrides of every block entity on the server: what {@code blockEntity.overrides()} writes when it is called server side
 * <p>
 * The block-entity twin of the per-entity override state in {@code ServerAnimationStates}, but simpler, since a block does not start or stop being
 * tracked the way an entity does: a change is sent as the block's full set of overrides to every player tracking its chunk, once at the end of the
 * tick however many were made, and a player who loads the block later asks for it ({@code BlockBoneOverridesRequestPacket}, sent when the client
 * block entity loads). The full set rather than the change: a block's overrides are a few values that change rarely, and a snapshot cannot be applied
 * out of order into a wrong state
 * <p>
 * Nothing is saved: like entity overrides, they are state the owner sets when it needs them, and a block entity that loads again starts without any
 */
public final class ServerBlockOverrides {
	private record Key(RegistryKey<World> world, long pos) {}

	private static final Map<Key, ServerBoneOverrideState> STATES = new HashMap<>();

	private ServerBlockOverrides() {}

	/**
	 * Hooks the end-of-tick send and the cleanup on unload; called once from the common entrypoint
	 */
	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(ServerBlockOverrides::flush);
		ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, world) -> STATES.remove(new Key(world.getRegistryKey(), blockEntity.getPos().asLong())));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> STATES.clear());
	}

	/**
	 * The overrides {@code blockEntity} can be written to, created on first use
	 *
	 * @throws IllegalStateException If the block entity is not in a world yet
	 */
	public static ServerBoneOverrideState overridesFor(BlockEntity blockEntity) {
		World world = blockEntity.getWorld();

		if (world == null)
			throw new IllegalStateException("bone overrides were used on a block entity that is not in a world yet (" + blockEntity.getClass().getName() + ")");

		return STATES.computeIfAbsent(new Key(world.getRegistryKey(), blockEntity.getPos().asLong()), key -> new ServerBoneOverrideState());
	}

	/**
	 * What {@code blockEntity} has been given, or null if nothing was ever
	 */
	@Nullable
	public static ServerBoneOverrideState get(BlockEntity blockEntity) {
		World world = blockEntity.getWorld();

		return world == null ? null : STATES.get(new Key(world.getRegistryKey(), blockEntity.getPos().asLong()));
	}

	/**
	 * Answers one player's request for the overrides of the block entity at {@code pos}: nothing is sent if it has none
	 */
	public static void sendTo(ServerPlayerEntity player, BlockPos pos) {
		ServerWorld world = player.getServerWorld();

		if (!world.isChunkLoaded(pos) || !(world.getBlockEntity(pos) instanceof StrutBlockEntity))
			return;

		ServerBoneOverrideState state = STATES.get(new Key(world.getRegistryKey(), pos.asLong()));

		if (state != null && !state.snapshot().isEmpty())
			StrutServices.NETWORK.sendToPlayer(snapshotOf(pos, state), player);
	}

	private static void flush(MinecraftServer server) {
		if (STATES.isEmpty())
			return;

		Iterator<Map.Entry<Key, ServerBoneOverrideState>> it = STATES.entrySet().iterator();

		while (it.hasNext()) {
			Map.Entry<Key, ServerBoneOverrideState> entry = it.next();
			Set<OverrideKey> dirty = entry.getValue().drainDirty();

			if (dirty.isEmpty())
				continue;

			ServerWorld world = server.getWorld(entry.getKey().world());
			BlockPos pos = BlockPos.fromLong(entry.getKey().pos());

			if (world == null || !world.isChunkLoaded(pos))
				continue;

			StrutServices.NETWORK.sendToAllPlayersTrackingBlock(snapshotOf(pos, entry.getValue()), world, pos);

			// everything cleared and sent: nothing is left to hold
			if (entry.getValue().snapshot().isEmpty())
				it.remove();
		}
	}

	private static BlockBoneOverrideSnapshotPacket snapshotOf(BlockPos pos, ServerBoneOverrideState state) {
		List<BoneOverrideSnapshotPacket.Entry> entries = new ArrayList<>(state.snapshot().size());

		for (Map.Entry<OverrideKey, BoneOverride> entry : state.snapshot().entrySet())
			entries.add(new BoneOverrideSnapshotPacket.Entry(entry.getKey(), entry.getValue()));

		return new BlockBoneOverrideSnapshotPacket(pos, entries);
	}
}
