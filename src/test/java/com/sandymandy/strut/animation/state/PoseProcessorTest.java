package com.sandymandy.strut.animation.state;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pose processor hook: with nothing registered the instance behaves exactly as it
 * did before; with one registered it edits a copy of the resolved pose and never the animation's own
 */
class PoseProcessorTest {
	private final List<PoseProcessor> registered = new ArrayList<>();

	@AfterEach
	void unregister() {
		this.registered.forEach(PoseProcessors::unregister);
	}

	private PoseProcessor register(PoseProcessor processor) {
		PoseProcessors.register(processor);
		this.registered.add(processor);

		return processor;
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

	private static PoseProcessor simple(Consumer<PoseProcessor.Context> body) {
		return new PoseProcessor() {
			@Override
			public boolean appliesTo(BakedStrutModel m) {
				return true;
			}

			@Override
			public void process(Context context) {
				body.accept(context);
			}
		};
	}

	@Test
	void withNoProcessorTheEffectivePoseIsTheRenderedPoseItself() {
		ModelInstance instance = ready(model());

		instance.prepareForRender(7);

		assertSame(instance.renderedPose(), instance.effectivePose());
	}

	@Test
	void aProcessorEditsACopyAndTheAnimationPoseIsUntouched() {
		ModelInstance instance = ready(model());

		register(simple(context -> {
			assertNotSame(context.animated(), context.pose());
			assertEquals(context.animated().getRotX(1), context.pose().getRotX(1), "it starts from the animation");
			context.pose().setRotation(1, 0.5f, 0, 0);
		}));

		instance.prepareForRender(7);

		assertEquals(0.5f, instance.effectivePose().getRotX(1), 1e-6);
		assertEquals(0f, instance.renderedPose().getRotX(1), 1e-6, "the animation state is not written to");

		instance.prepareForRender(7);

		assertEquals(0.5f, instance.effectivePose().getRotX(1), 1e-6, "every frame starts again from the animation, then the processor runs");
	}

	@Test
	void aProcessorThatDoesNotApplyIsNeverCalledAndNoCopyIsMade() {
		ModelInstance instance = ready(model());
		AtomicInteger calls = new AtomicInteger();

		register(new PoseProcessor() {
			@Override
			public boolean appliesTo(BakedStrutModel m) {
				return false;
			}

			@Override
			public void process(Context context) {
				calls.incrementAndGet();
			}
		});

		instance.prepareForRender(7);

		assertEquals(0, calls.get());
		assertSame(instance.renderedPose(), instance.effectivePose(), "a model no processor accepts costs nothing");
	}

	@Test
	void processorsRunInRegistrationOrderOnTheSameCopy() {
		ModelInstance instance = ready(model());

		register(simple(context -> context.pose().setRotation(1, 1f, 0, 0)));
		register(simple(context -> context.pose().setRotation(1, context.pose().getRotX(1) + 1f, 0, 0)));

		instance.prepareForRender(7);

		assertEquals(2f, instance.effectivePose().getRotX(1), 1e-6);
	}

	@Test
	void stateIsPerInstanceAndDroppedWhenTheModelChanges() {
		ModelInstance instance = ready(model());
		AtomicInteger created = new AtomicInteger();

		register(simple(context -> {
			int[] counter = context.state("k", () -> {
				created.incrementAndGet();

				return new int[1];
			});

			counter[0]++;
		}));

		instance.prepareForRender(7);
		instance.prepareForRender(7);

		assertEquals(1, created.get(), "kept between frames");

		instance.poseFor(model());
		instance.resetPoseHistory();
		instance.prepareForRender(7);

		assertEquals(2, created.get(), "a new model identity (a resource reload) starts fresh");
	}
}
