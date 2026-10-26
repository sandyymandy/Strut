package com.sandymandy.strut.animation.state;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutPoly;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-instance mesh offsets: a processor writes them during a frame's processing, the
 * renderer reads them, and a frame in which nothing writes them is drawn without. A model nobody deforms reports none, so it keeps the path it always had
 */
class MeshOffsetsTest {
	private final List<PoseProcessor> registered = new ArrayList<>();

	@AfterEach
	void unregister() {
		this.registered.forEach(PoseProcessors::unregister);
	}

	private static BakedStrutModel model() {
		GroupBoneDefinition root = new GroupBoneDefinition(null, "root", false, null, null, null);

		root.bakePivot(0, 0, 0);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null, null, null,
				"test", null, 16, 16, null, new double[] {0, 0, 0}, null);

		return new BakedStrutModel(List.of(root), properties);
	}

	private static StrutMesh mesh() {
		return new StrutMesh(new StrutPoly[0], Vec3d.ZERO, Vec3d.ZERO, false);
	}

	private static ModelInstance ready() {
		ModelInstance instance = new ModelInstance();

		instance.poseFor(model());
		instance.resetPoseHistory();

		return instance;
	}

	@Test
	void anInstanceNobodyDeformsHasNoOffsets() {
		ModelInstance instance = ready();

		instance.prepareForRender(3);

		assertFalse(instance.hasMeshOffsets());
		assertNull(instance.meshOffsets(mesh()));
	}

	@Test
	void offsetsAProcessorWritesAreReadBackForThatMeshOnly() {
		ModelInstance instance = ready();
		StrutMesh deformed = mesh();
		StrutMesh other = mesh();
		float[] offsets = new float[12];
		PoseProcessor processor = new PoseProcessor() {
			@Override
			public boolean appliesTo(BakedStrutModel m) {
				return true;
			}

			@Override
			public void process(Context context) {
				context.instance().setMeshOffsets(deformed, offsets);
			}
		};

		PoseProcessors.register(processor);
		this.registered.add(processor);
		instance.prepareForRender(3);

		assertTrue(instance.hasMeshOffsets());
		assertSame(offsets, instance.meshOffsets(deformed));
		assertNull(instance.meshOffsets(other), "another mesh is not deformed");
	}

	@Test
	void aFrameNothingWritesOffsetsIsDrawnWithout() {
		ModelInstance instance = ready();
		boolean[] write = {true};
		StrutMesh deformed = mesh();
		PoseProcessor processor = new PoseProcessor() {
			@Override
			public boolean appliesTo(BakedStrutModel m) {
				return true;
			}

			@Override
			public void process(Context context) {
				if (write[0])
					context.instance().setMeshOffsets(deformed, new float[12]);
			}
		};

		PoseProcessors.register(processor);
		this.registered.add(processor);
		instance.prepareForRender(3);
		assertTrue(instance.hasMeshOffsets());

		write[0] = false;
		instance.prepareForRender(3);
		assertFalse(instance.hasMeshOffsets(), "a processor that stops writing stops deforming");
	}
}
