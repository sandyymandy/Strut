package com.sandymandy.strut.animation.state;

import com.sandymandy.strut.animation.override.BoneOverride;
import com.sandymandy.strut.animation.override.ClientBoneOverrideStates;
import com.sandymandy.strut.animation.override.OverrideKey;
import com.sandymandy.strut.animation.override.OverrideProperty;
import com.sandymandy.strut.animation.override.OverrideScope;
import com.sandymandy.strut.animation.override.OverrideValue;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import com.sandymandy.strut.network.packet.BoneOverrideSnapshotPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bone overrides on a block entity: kept for a block position, read by a model instance that is prepared for that position, and sent as one
 * snapshot that replaces what the client held
 */
class BlockEntityOverridesTest {
	private static final BlockPos HERE = new BlockPos(100_001, 64, -20_002);
	private static final BlockPos THERE = new BlockPos(100_002, 64, -20_002);

	@AfterEach
	void cleanUp() {
		ClientBoneOverrideStates.clearBlocks();
	}

	private static BakedStrutModel model() {
		GroupBoneDefinition root = new GroupBoneDefinition(null, "root", false, null, null, null);
		GroupBoneDefinition child = new GroupBoneDefinition(root, "child", false, null, null, null);

		root.bakePivot(0, 0, 0);
		child.bakePivot(0, 8, 0);
		root.getChildBones().add(child);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null, null, null,
				"test", null, 16, 16, null, new double[] {0, 0, 0}, null);

		return new BakedStrutModel(List.of(root), properties);
	}

	private static ModelInstance ready(BakedStrutModel model) {
		ModelInstance instance = new ModelInstance();

		instance.poseFor(model);
		instance.resetPoseHistory();

		return instance;
	}

	private static void hide(BlockPos pos, String bone) {
		ClientBoneOverrideStates.forBlock(pos).setLocal(bone, OverrideProperty.HIDDEN, new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);
	}

	@Test
	void anOverrideOnABlockIsReadByAnInstancePreparedForThatBlock() {
		BakedStrutModel model = model();
		ModelInstance instance = ready(model);
		int child = model.getBone("child").orElseThrow().getIndex();

		hide(HERE, "child");
		instance.prepareForRender(HERE);

		assertTrue(instance.effectivePose().isHidden(child));
		assertFalse(instance.renderedPose().isHidden(child), "the animated pose is left as it was");
	}

	@Test
	void anotherBlockWithTheSameModelIsNotAffected() {
		BakedStrutModel model = model();
		ModelInstance instance = ready(model);
		int child = model.getBone("child").orElseThrow().getIndex();

		hide(HERE, "child");
		instance.prepareForRender(THERE);

		assertFalse(instance.effectivePose().isHidden(child));
	}

	@Test
	void aBlockAndAnEntityWithTheSameNumberDoNotShareOverrides() {
		BakedStrutModel model = model();
		ModelInstance instance = ready(model);
		int child = model.getBone("child").orElseThrow().getIndex();

		hide(HERE, "child");
		instance.prepareForRender((int) HERE.asLong());

		assertFalse(instance.effectivePose().isHidden(child));
	}

	@Test
	void removingTheBlockDropsItsOverrides() {
		BakedStrutModel model = model();
		ModelInstance instance = ready(model);
		int child = model.getBone("child").orElseThrow().getIndex();

		hide(HERE, "child");
		ClientBoneOverrideStates.removeBlock(HERE);
		instance.prepareForRender(HERE);

		assertNull(ClientBoneOverrideStates.getBlock(HERE));
		assertFalse(instance.effectivePose().isHidden(child));
	}

	@Test
	void anInstancePreparedForABlockThenAnEntityReadsTheEntitysOverrides() {
		BakedStrutModel model = model();
		ModelInstance instance = ready(model);
		int child = model.getBone("child").orElseThrow().getIndex();

		hide(HERE, "child");
		instance.prepareForRender(HERE);
		assertTrue(instance.effectivePose().isHidden(child));

		instance.prepareForRender(987_654);
		assertFalse(instance.effectivePose().isHidden(child), "what a block had is not read for an entity");
	}

	@Test
	void aSnapshotReplacesWhatTheClientHeldForTheBlock() {
		OverrideKey hidden = new OverrideKey("child", OverrideProperty.HIDDEN);
		BoneOverride override = new BoneOverride(new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f, 1L);

		ClientBoneOverrideStates.forBlock(HERE).applySnapshot(Map.of(hidden, override));

		assertEquals(override, ClientBoneOverrideStates.getBlock(HERE).resolve("child", OverrideProperty.HIDDEN));
		assertNull(ClientBoneOverrideStates.getBlock(THERE));

		// the server cleared it: the next snapshot is empty, and the override is gone
		ClientBoneOverrideStates.forBlock(HERE).applySnapshot(Map.of());

		assertNull(ClientBoneOverrideStates.getBlock(HERE).resolve("child", OverrideProperty.HIDDEN));
	}

	@Test
	void aSnapshotKeepsTheClientsOwnLocalOverrides() {
		hide(HERE, "root");

		ClientBoneOverrideStates.forBlock(HERE).applySnapshot(Map.of());

		assertNotNull(ClientBoneOverrideStates.getBlock(HERE).resolve("root", OverrideProperty.HIDDEN));
	}

	@Test
	void anEntryOfTheSnapshotSurvivesTheWire() {
		OverrideKey key = new OverrideKey("child", OverrideProperty.POSITION);
		BoneOverride override = new BoneOverride(new OverrideValue.Vec3Value(1f, 2f, 3f), OverrideScope.PERSISTENT, 2, 1f, 0f, 7L);
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());

		BoneOverrideSnapshotPacket.Entry.STREAM_CODEC.encode(buf, new BoneOverrideSnapshotPacket.Entry(key, override));
		BoneOverrideSnapshotPacket.Entry read = BoneOverrideSnapshotPacket.Entry.STREAM_CODEC.decode(buf);

		assertEquals(key, read.key());
		assertEquals(override, read.override());
	}
}
