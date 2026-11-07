package com.sandymandy.strut.perf;

import com.sandymandy.strut.animation.AnimatableManager;
import com.sandymandy.strut.animation.Animation;
import com.sandymandy.strut.animation.AnimationState;
import com.sandymandy.strut.animation.keyframe.event.data.CustomInstructionKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.ParticleKeyframeData;
import com.sandymandy.strut.animation.keyframe.event.data.SoundKeyframeData;
import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.animation.state.SkinningState;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.StrutArmature;
import com.sandymandy.strut.loading.binary.BinaryAnimationBaker;
import com.sandymandy.strut.loading.binary.BinaryAnimationReader;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import com.sandymandy.strut.loading.object.BakedAnimations;
import com.sandymandy.strut.model.StrutModel;
import com.sandymandy.strut.player.PlayerBody;
import com.sandymandy.strut.player.PlayerMotion;
import com.sandymandy.strut.player.PlayerTickets;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.FloatBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What one frame of a crowd costs on the CPU, stage by stage, for a real model and its real clips: the animation step, the bone overrides, the pose the draw
 * reads, the armature skinning and the bone matrices packed for the GPU. Not a test: it prints, and only runs when {@code STRUT_BENCH_MODEL} and
 * {@code STRUT_BENCH_ANIM} name a model and an animation file ({@code STRUT_BENCH_CLIP} the looping clip to play, {@code animation.base.walk} unless set)
 */
class RenderCostBench {
	private static final int INSTANCES = Integer.parseInt(System.getenv().getOrDefault("STRUT_BENCH_INSTANCES", "100"));
	private static final int WARMUP_FRAMES = 300;
	private static final int FRAMES = 300;

	@Test
	void aCrowdFrame() throws Exception {
		String modelPath = System.getenv("STRUT_BENCH_MODEL");
		String animPath = System.getenv("STRUT_BENCH_ANIM");

		Assumptions.assumeTrue(modelPath != null && animPath != null, "set STRUT_BENCH_MODEL and STRUT_BENCH_ANIM to run");

		String clipName = System.getenv().getOrDefault("STRUT_BENCH_CLIP", "animation.base.walk");
		BakedStrutModel baked = BinaryModelBaker.bake(BinaryModelReader.read(Files.readAllBytes(Path.of(modelPath)), "bench"), "bench");
		BakedAnimations anims = BinaryAnimationBaker.bake(BinaryAnimationReader.read(Files.readAllBytes(Path.of(animPath)), "bench_anim"), "bench_anim");
		String prefix = clipName.substring(0, clipName.lastIndexOf('.') + 1);
		PlayerBody body = PlayerBody.builder(Identifier.of("strut", "bench")).locomotion().clipPrefix(prefix).build();
		StrutModel<PlayerBody> model = new StrutModel<>() {
			@Override
			public Animation getAnimation(PlayerBody animatable, String name) {
				Animation a = anims.getAnimation(name);

				return a == null ? null : new Animation(a.name(), a.length(), a.loopType(), a.boneAnimations(),
						new Animation.Keyframes(new SoundKeyframeData[0], new ParticleKeyframeData[0], new CustomInstructionKeyframeData[0]));
			}

			@Override
			public Identifier getModelResource(PlayerBody animatable) {
				return Identifier.of("strut", "bench");
			}

			@Override
			public Identifier getTextureResource(PlayerBody animatable) {
				return Identifier.of("strut", "bench");
			}

			@Override
			public Identifier getAnimationResource(PlayerBody animatable) {
				return Identifier.of("strut", "bench_anim");
			}
		};

		model.getAnimationProcessor().setActiveModel(baked);

		List<AnimatableManager<PlayerBody>> managers = new ArrayList<>();

		for (int i = 0; i < INSTANCES; i++)
			managers.add(new AnimatableManager<>(body));

		// STRUT_BENCH_TRANSLUCENT=n: n bones of each instance drawn translucent through a bone override, set again every frame as a mod's custom animation may
		int translucent = Integer.parseInt(System.getenv().getOrDefault("STRUT_BENCH_TRANSLUCENT", "0"));
		List<String> translucentBones = new ArrayList<>();

		for (var bone : baked.flatBones()) {
			if (translucentBones.size() < translucent && !bone.getCubes().isEmpty())
				translucentBones.add(bone.getName());
		}

		int boneCount = baked.flatBones().length;
		Matrix4f[] palette = new Matrix4f[boneCount];

		for (int i = 0; i < boneCount; i++)
			palette[i] = new Matrix4f();

		Matrix4f world = new Matrix4f();
		Matrix4f entity = new Matrix4f().translate(3, 64, -2);
		FloatBuffer paletteBuffer = ByteBuffer.allocateDirect(INSTANCES * boneCount * 64).order(ByteOrder.nativeOrder()).asFloatBuffer();
		int skinBones = baked.armatures().stream().mapToInt(a -> a.bones().size()).sum();
		FloatBuffer skinBuffer = ByteBuffer.allocateDirect(Math.max(1, INSTANCES * skinBones * 64)).order(ByteOrder.nativeOrder()).asFloatBuffer();
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		long thread = Thread.currentThread().threadId();
		long[] nanos = new long[8];
		long[] bytes = new long[8];
		String[] names = {"animation step", "bone overrides", "pose with hidden bones", "armature skinning", "bone matrices packed", "skin matrices packed",
				"body colliders published", "physics update"};

		// STRUT_BENCH_PHYSICS names the model's physics file: each instance then runs it, in a crowd on a grid a block and a half apart, with its body published
		// for the others' parts to collide with
		String physicsPath = System.getenv("STRUT_BENCH_PHYSICS");
		List<com.sandymandy.strut.physics.PhysicsInstance> physics = new ArrayList<>();
		List<com.sandymandy.strut.physics.collision.ColliderRig> rigs = new ArrayList<>();
		List<com.sandymandy.strut.animation.state.Pose> physicsPoses = new ArrayList<>();
		List<com.sandymandy.strut.physics.collision.Collider.Placed> placed = new ArrayList<>();

		if (physicsPath != null) {
			com.sandymandy.strut.physics.config.PhysicsConfig config = com.sandymandy.strut.physics.config.PhysicsConfig.parse(Files.readString(Path.of(physicsPath)), "bench");

			// STRUT_BENCH_FIELDS: the model's signed distance fields are made first, as they are in game a moment after the model is first drawn
			if (System.getenv("STRUT_BENCH_FIELDS") != null) {
				com.sandymandy.strut.physics.collision.ModelSdfs.enabled(true);
				com.sandymandy.strut.physics.collision.ModelSdfs.put(baked, com.sandymandy.strut.physics.collision.ModelSdfs.buildAll(baked));
			}

			List<com.sandymandy.strut.physics.collision.Collider> shapes = com.sandymandy.strut.physics.collision.AutoColliders.generate(baked,
					com.sandymandy.strut.physics.collision.AutoColliders.Options.DEFAULT, java.util.Set.of());

			for (int i = 0; i < INSTANCES; i++) {
				com.sandymandy.strut.physics.PhysicsInstance p = new com.sandymandy.strut.physics.PhysicsInstance(baked, config);

				p.entityId(1_000_000 + i);

				// STRUT_BENCH_WORLD: blocks round the crowd, as the game reads them: a floor of one box a block with a pillar every few blocks
				if (System.getenv("STRUT_BENCH_WORLD") != null) {
					p.world(new com.sandymandy.strut.physics.world.WorldSource() {
						@Override
						public void boxes(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, BoxSink sink) {
							for (int bx = (int)Math.floor(minX); bx <= (int)Math.floor(maxX); bx++) {
								for (int bz = (int)Math.floor(minZ); bz <= (int)Math.floor(maxZ); bz++) {
									if (minY < 64 && maxY > 63)
										sink.box(bx, 63, bz, bx + 1, 64, bz + 1);

									if (Math.floorMod(bx, 5) == 0 && Math.floorMod(bz, 5) == 0) {
										for (int by = 64; by < 68; by++) {
											if (minY < by + 1 && maxY > by)
												sink.box(bx, by, bz, bx + 1, by + 1, bz + 1);
										}
									}
								}
							}
						}
					});
				}

				physics.add(p);
				rigs.add(new com.sandymandy.strut.physics.collision.ColliderRig(baked, List.of(), shapes, new ArrayList<>()));
				physicsPoses.add(new Pose(boneCount));
			}
		}

		jdk.jfr.Recording recording = null;

		// STRUT_BENCH_SCHEDULE: the crowd is scheduled as the game schedules it, seen from a camera at one corner of it: the nearest have their turn every
		// frame, the others take turns within the budget, and what the flesh feels falls off with the distance
		boolean schedule = System.getenv("STRUT_BENCH_SCHEDULE") != null;
		int[] starved = new int[INSTANCES];
		com.sandymandy.strut.physics.tools.PhysicsControls controls = new com.sandymandy.strut.physics.tools.PhysicsControls();

		for (int i = 0; i < INSTANCES; i++)
			starved[i] = Math.floorMod(1_000_000 + i, com.sandymandy.strut.physics.tools.PhysicsStats.MAX_CADENCE);

		com.sandymandy.strut.physics.tools.PhysicsStats.reset();

		for (int frame = 0; frame < WARMUP_FRAMES + FRAMES; frame++) {
			boolean measured = frame >= WARMUP_FRAMES;

			if (schedule)
				com.sandymandy.strut.physics.tools.PhysicsStats.nextFrame();

			// with STRUT_BENCH_JFR set, the measured frames are sampled: where the time goes and where the memory is allocated
			if (frame == WARMUP_FRAMES && System.getenv("STRUT_BENCH_JFR") != null) {
				recording = new jdk.jfr.Recording();
				recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1));
				recording.enable("jdk.ObjectAllocationSample").with("throttle", "10000/s");
				recording.start();
			}
			double time = frame / 3.0;

			paletteBuffer.clear();
			skinBuffer.clear();

			for (int i = 0; i < INSTANCES; i++) {
				AnimatableManager<PlayerBody> manager = managers.get(i);
				ModelInstance instance = manager.getModelInstance();
				AnimationState<PlayerBody> state = new AnimationState<>(body, 0, 0, 0, true);
				boolean still = System.getenv("STRUT_BENCH_STILL") != null;
				PlayerMotion motion = new PlayerMotion(7, true, false, false, false, false, false, false, false, false, false, false, PlayerMotion.Vehicle.NONE,
						false, false, false, !still, still ? 0f : 0.2f, 0f, false, true, -1, PlayerMotion.Use.NONE);

				state.setData(PlayerTickets.MOTION, motion);
				state.setData(PlayerTickets.HAS_CLIP, clip -> anims.getAnimation(clip) != null);

				long a0 = threads.getThreadAllocatedBytes(thread);
				long t0 = System.nanoTime();

				instance.poseFor(baked);
				model.getAnimationProcessor().tickAnimation(body, model, manager, time + i * 0.37, state, false);

				long t1 = System.nanoTime();
				long a1 = threads.getThreadAllocatedBytes(thread);

				for (String bone : translucentBones)
					com.sandymandy.strut.animation.override.ClientBoneOverrideStates.forEntity(1_000_000 + i).setLocal(bone,
							com.sandymandy.strut.animation.override.OverrideProperty.TRANSLUCENT, new com.sandymandy.strut.animation.override.OverrideValue.BoolValue(true),
							com.sandymandy.strut.animation.override.OverrideScope.PERSISTENT, 0, 1f, 0f);

				instance.prepareForRender(1_000_000 + i, true);

				long t2 = System.nanoTime();
				long a2 = threads.getThreadAllocatedBytes(thread);

				Pose pose = instance.effectivePoseWithHidden(baked, List.of());

				long t3 = System.nanoTime();
				long a3 = threads.getThreadAllocatedBytes(thread);

				SkinningState skinning = manager.getSkinningState();

				if (!baked.armatures().isEmpty())
					skinning.poseArmatures(baked, instance);

				long t4 = System.nanoTime();
				long a4 = threads.getThreadAllocatedBytes(thread);

				// as the draw does it: the palette carried by the entity's transform, a hidden bone collapsed
				PosePalette.compute(baked, pose, palette, entity);

				int base = i * boneCount * 16;

				for (int bone = 0; bone < boneCount; bone++) {
					if (pose.isHidden(bone))
						world.zero().get(base + bone * 16, paletteBuffer);
					else
						palette[bone].get(base + bone * 16, paletteBuffer);
				}

				long t5 = System.nanoTime();
				long a5 = threads.getThreadAllocatedBytes(thread);
				int skinBase = i * skinBones * 16;

				for (StrutArmature armature : baked.armatures()) {
					for (Matrix4f m : skinning.skinMatricesFor(armature)) {
						m.get(skinBase, skinBuffer);
						skinBase += 16;
					}
				}

				long t6 = System.nanoTime();
				long a6 = threads.getThreadAllocatedBytes(thread);
				// STRUT_BENCH_SPACING: how far apart the crowd stands, blocks (1.5 by default; a packed pen is a third of a block)
				double spacing = Double.parseDouble(System.getenv().getOrDefault("STRUT_BENCH_SPACING", "1.5"));
				double x = (i % 10) * spacing;
				// STRUT_BENCH_STILL: the crowd stands where it is (idle), rather than drifting back and forth (walking)
				double z = (i / 10) * spacing + (System.getenv("STRUT_BENCH_STILL") != null ? 0 : Math.sin(time * 0.3 + i) * 0.5);
				// STRUT_BENCH_JUMP: every entity jumps 1.2 blocks once a second (frames are a sixtieth of a second apart)
				double y = 64 + (System.getenv("STRUT_BENCH_JUMP") != null ? 1.2 * Math.abs(Math.sin(Math.PI * (time / 20.0 + i * 0.1))) : 0);

				if (!physics.isEmpty()) {
					placed.clear();
					rigs.get(i).prepare(instance.renderedPose());
					rigs.get(i).place(x, y, z, 0f, null, placed);
					com.sandymandy.strut.physics.collision.Bodies.publishModel(1_000_000 + i, new ArrayList<>(placed));
				}

				long t7 = System.nanoTime();
				long a7 = threads.getThreadAllocatedBytes(thread);

				if (!physics.isEmpty()) {
					Pose target = physicsPoses.get(i);
					com.sandymandy.strut.physics.PhysicsInstance p = physics.get(i);

					target.copyFrom(instance.renderedPose());

					if (schedule) {
						double distance = Math.sqrt((x + 3) * (x + 3) + (y + 1.6 - 65.6) * (y + 1.6 - 65.6) + (z + 3) * (z + 3));
						long started = System.nanoTime();

						p.fleshContact(distance <= 8 ? com.sandymandy.strut.physics.flesh.FleshInstance.CONTACT_ALL
								: distance <= 16 ? com.sandymandy.strut.physics.flesh.FleshInstance.CONTACT_BODY : com.sandymandy.strut.physics.flesh.FleshInstance.CONTACT_NONE,
								distance <= 5 ? com.sandymandy.strut.physics.flesh.FleshInstance.MOST_OTHERS : com.sandymandy.strut.physics.flesh.FleshInstance.MOST_OTHERS / 2);

						com.sandymandy.strut.physics.tools.PhysicsStats.Turn turn = p.canHold() ? com.sandymandy.strut.physics.tools.PhysicsStats.admit(starved[i], distance, false)
								: com.sandymandy.strut.physics.tools.PhysicsStats.Turn.FULL;

						if (turn == com.sandymandy.strut.physics.tools.PhysicsStats.Turn.HOLD) {
							starved[i]++;
							p.hold(target, controls);
						}
						else {
							starved[i] = 0;
							p.update(baked, instance.renderedPose(), target, new com.sandymandy.strut.physics.PhysicsInstance.Sample(x, y, z, 0f, time / 20.0), controls);
						}

						com.sandymandy.strut.physics.tools.PhysicsStats.record(p.lastSimulatedBones, p.lastSteps, System.nanoTime() - started);
					}
					else {
						p.update(baked, instance.renderedPose(), target, new com.sandymandy.strut.physics.PhysicsInstance.Sample(x, y, z, 0f, time / 20.0), controls);
					}
				}

				long t8 = System.nanoTime();
				long a8 = threads.getThreadAllocatedBytes(thread);

				if (measured) {
					long[] t = {t0, t1, t2, t3, t4, t5, t6, t7, t8};
					long[] a = {a0, a1, a2, a3, a4, a5, a6, a7, a8};

					for (int s = 0; s < 8; s++) {
						nanos[s] += t[s + 1] - t[s];
						bytes[s] += a[s + 1] - a[s];
					}
				}
			}
		}

		if (recording != null) {
			recording.stop();

			Path dump = Files.createTempFile("strut-bench", ".jfr");

			recording.dump(dump);
			recording.close();
			report(dump);
		}

		if (schedule)
			com.sandymandy.strut.physics.tools.PhysicsStats.lines().forEach(l -> System.out.println("      " + l));

		long total = 0;

		System.out.printf("%n%s: %d group bones, %d cubes, %d meshes, %d skinned bones; %d instances, a frame each%n", modelPath, boneCount,
				countCubes(baked), countMeshes(baked), skinBones, INSTANCES);

		for (int s = 0; s < 8; s++) {
			total += nanos[s];
			System.out.printf("  %-24s %7.3f ms a frame   %7.1f KB a frame%n", names[s], nanos[s] / 1e6 / FRAMES, bytes[s] / 1024.0 / FRAMES);
		}

		if (!physics.isEmpty()) {
			// what the physics of the first instance made and could not make, and where it is now
			physics.get(0).problems().forEach(p -> System.out.println("  problem: " + p));
			physics.get(0).describe().stream().filter(l -> !l.contains(": rot (")).forEach(l -> System.out.println("  " + l));

			int resting = 0;
			int regions = 0;

			for (com.sandymandy.strut.physics.PhysicsInstance p : physics) {
				for (String line : p.describe()) {
					java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+) resting").matcher(line);

					if (line.startsWith("flesh ") && m.find()) {
						resting += Integer.parseInt(m.group(1));
						regions += 2;
					}
				}
			}

			if (regions > 0)
				System.out.println("  soft regions resting at the end: " + resting + " of " + regions);

			int[] meshes = {0};

			physics.get(0).clothDeforms((mesh, deform) -> meshes[0]++);
			System.out.println("  meshes moved this frame: " + meshes[0]);
		}

		System.out.printf("  %-24s %7.3f ms a frame (%.2f us an instance)%n", "total", total / 1e6 / FRAMES, total / 1e3 / FRAMES / INSTANCES);
	}

	/** The methods the samples land in most (the top frame, and the first of Strut's frames) and the places that allocate the most */
	private static void report(Path dump) throws Exception {
		java.util.Map<String, Long> self = new java.util.HashMap<>();
		java.util.Map<String, Long> strut = new java.util.HashMap<>();
		java.util.Map<String, Long> alloc = new java.util.HashMap<>();
		java.util.Map<String, Long> inclusive = new java.util.HashMap<>();
		long samples = 0;
		long allocated = 0;

		for (jdk.jfr.consumer.RecordedEvent event : jdk.jfr.consumer.RecordingFile.readAllEvents(dump)) {
			jdk.jfr.consumer.RecordedStackTrace stack = event.getStackTrace();

			if (stack == null || stack.getFrames().isEmpty())
				continue;

			String name = event.getEventType().getName();

			if (name.equals("jdk.ExecutionSample")) {
				samples++;
				self.merge(frame(stack.getFrames().get(0)), 1L, Long::sum);

				for (jdk.jfr.consumer.RecordedFrame f : stack.getFrames()) {
					if (f.getMethod().getType().getName().startsWith("com.sandymandy")) {
						strut.merge(frame(f), 1L, Long::sum);
						break;
					}
				}

				java.util.Set<String> seen = new java.util.HashSet<>();

				for (jdk.jfr.consumer.RecordedFrame f : stack.getFrames()) {
					String method = f.getMethod().getType().getName().replace("com.sandymandy.strut.", "") + "." + f.getMethod().getName();

					if (method.startsWith("physics.") && seen.add(method))
						inclusive.merge(method, 1L, Long::sum);
				}
			}
			else if (name.equals("jdk.ObjectAllocationSample")) {
				long weight = event.getLong("weight");
				String type = event.getClass("objectClass").getName();
				String site = "?";

				for (jdk.jfr.consumer.RecordedFrame f : stack.getFrames()) {
					if (f.getMethod().getType().getName().startsWith("com.sandymandy")) {
						site = frame(f);
						break;
					}
				}

				allocated += weight;
				alloc.merge(type + "  <-  " + site, weight, Long::sum);
			}
		}

		print("where the time goes (top frame)", self, samples, false);
		print("where the time goes (first Strut frame)", strut, samples, false);
		print("what is allocated, and where", alloc, allocated, true);
		print("the physics methods the time was spent in or under", inclusive, samples, false);
	}

	private static String frame(jdk.jfr.consumer.RecordedFrame f) {
		return f.getMethod().getType().getName().replace("com.sandymandy.strut.", "") + "." + f.getMethod().getName() + ":" + f.getLineNumber();
	}

	private static void print(String title, java.util.Map<String, Long> counts, long total, boolean bytes) {
		System.out.println("  " + title + ":");

		counts.entrySet().stream().sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed()).limit(18).forEach(e ->
				System.out.printf("    %5.1f%%  %s%n", 100.0 * e.getValue() / Math.max(1, total), e.getKey()));
	}

	private static int countMeshes(BakedStrutModel model) {
		int n = 0;

		for (var bone : model.flatBones())
			n += bone.getMeshes().size();

		return n;
	}

	private static int countCubes(BakedStrutModel model) {
		int n = 0;

		for (var bone : model.flatBones())
			n += bone.getCubes().size();

		return n;
	}
}
