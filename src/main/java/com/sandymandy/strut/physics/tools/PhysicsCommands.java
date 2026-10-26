package com.sandymandy.strut.physics.tools;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.Locale;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.PhysicsPoseProcessor;
import com.sandymandy.strut.physics.StrutPhysics;
import com.sandymandy.strut.physics.collision.ModelSdfs;
import com.sandymandy.strut.physics.config.PhysicsOptions;
import com.sandymandy.strut.physics.config.PhysicsSettings;

/**
 * {@code /strutphysics ...}, the client commands for trying physics out
 * <ul>
 * <li>{@code on}, {@code off}: the master switch</li>
 * <li>{@code debug on|off}: draw each spring bone as lines (green rest, red moved)</li>
 * <li>{@code info}: print the spring offsets of the entity you are looking at</li>
 * <li>{@code impulse <bone> <x> <y> <z>}: kick a bone on that entity, in blocks per second</li>
 * <li>{@code distance <blocks>}: how far from the camera physics runs</li>
 * <li>{@code reset}: rest every spring</li>
 * <li>{@code tool}: open the cursor tool (hold parts, move the entity, load a test scenario)</li>
 * <li>{@code collide others|players|springs <true|false>}: whether soft parts collide with other models and mobs, or with players</li>
 * <li>{@code ragdoll limp|weak|stand|off}, {@code ragdoll hit <bone> <x> <y> <z>}: let the looked-at entity's body go, bring the muscle back, or shove it</li>
 * <li>{@code test <springs|chains|colliders|world|bodies|springworld|selfcollide|ragdoll|off>}: load a test scenario on Strut's test dummy and print what to do and what you should see</li>
 * </ul>
 */
public final class PhysicsCommands {
	private PhysicsCommands() {}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		// Only /strutphysics, never a client branch under /strut: Fabric runs a client command whose first word matches a client root and does not pass it to
		// the server when the rest does not parse, so a client /strut shadowed every one of Strut's own server /strut commands ("Incorrect argument ... strut")
		dispatcher.register(branch("strutphysics"));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> branch(String name) {
		return (ClientCommandManager.literal(name)
				.then(ClientCommandManager.literal("on").executes(c -> {
					PhysicsOptions.enabled(true);
					say(c.getSource(), "Physics on");

					return 1;
				}))
				.then(ClientCommandManager.literal("off").executes(c -> {
					PhysicsOptions.enabled(false);
					say(c.getSource(), "Physics off: models are drawn exactly as animated");

					return 1;
				}))
				.then(ClientCommandManager.literal("debug")
						.then(ClientCommandManager.literal("on").executes(c -> {
							PhysicsDebug.drawing = true;
							say(c.getSource(), "Physics debug lines on (green: rest, red: moved)");

							return 1;
						}))
						.then(ClientCommandManager.literal("off").executes(c -> {
							PhysicsDebug.drawing = false;
							say(c.getSource(), "Physics debug lines off");

							return 1;
						})))
				.then(ClientCommandManager.literal("info").executes(c -> {
					PhysicsInstance instance = targeted();

					if (instance == null) {
						say(c.getSource(), "Look at an entity that has physics (or none was simulated this frame)");

						return 0;
					}

					say(c.getSource(), "Physics: " + instance.boneCount() + " spring bones, last frame " + instance.lastSteps + " step(s)");
					instance.describe().forEach(line -> say(c.getSource(), line));

					return 1;
				}))
				.then(ClientCommandManager.literal("impulse")
						.then(ClientCommandManager.argument("bone", StringArgumentType.word())
								.then(ClientCommandManager.argument("x", DoubleArgumentType.doubleArg())
										.then(ClientCommandManager.argument("y", DoubleArgumentType.doubleArg())
												.then(ClientCommandManager.argument("z", DoubleArgumentType.doubleArg()).executes(c -> {
													Entity entity = MinecraftClient.getInstance().targetedEntity;

													if (entity == null) {
														say(c.getSource(), "Look at an entity first");

														return 0;
													}

													StrutPhysics.of(entity).impulse(StringArgumentType.getString(c, "bone"), (float)DoubleArgumentType.getDouble(c, "x"),
															(float)DoubleArgumentType.getDouble(c, "y"), (float)DoubleArgumentType.getDouble(c, "z"));

													return 1;
												}))))))
				.then(FluidCommands.branch())
				.then(ClientCommandManager.literal("colliders")
						.then(ClientCommandManager.literal("mesh").executes(c -> {
							PhysicsOptions.meshColliders(true);
							PhysicsSettings.save();
							say(c.getSource(), "Mesh colliders on: models made from now on (F3+T reloads the loaded ones) collide as their real shape");

							return 1;
						}))
						.then(ClientCommandManager.literal("proxy").executes(c -> {
							PhysicsOptions.meshColliders(false);
							PhysicsSettings.save();
							say(c.getSource(), "Mesh colliders off: boxes and capsules (F3+T reloads the loaded models)");

							return 1;
						}))
						.then(ClientCommandManager.literal("info").executes(c -> {
							say(c.getSource(), (PhysicsOptions.meshColliders() ? "Mesh colliders on. " : "Mesh colliders OFF. ") + ModelSdfs.summary());

							return 1;
						})))
				.then(ClientCommandManager.literal("quality")
						.then(ClientCommandManager.argument("level", StringArgumentType.word()).suggests((c, b) -> {
							for (PhysicsOptions.Quality q : PhysicsOptions.Quality.values())
								b.suggest(q.name().toLowerCase(Locale.ROOT));

							return b.buildFuture();
						}).executes(c -> {
							String level = StringArgumentType.getString(c, "level");

							try {
								PhysicsOptions.quality(PhysicsOptions.Quality.valueOf(level.toUpperCase(Locale.ROOT)));
								PhysicsSettings.save();
								say(c.getSource(), "Physics quality " + PhysicsOptions.quality().name().toLowerCase(Locale.ROOT));
							}
							catch (IllegalArgumentException ex) {
								say(c.getSource(), "Quality is low, medium or high");
							}

							return 1;
						})))
				.then(ClientCommandManager.literal("distance")
						.then(ClientCommandManager.argument("blocks", DoubleArgumentType.doubleArg(4)).executes(c -> {
							PhysicsOptions.distanceLimit(DoubleArgumentType.getDouble(c, "blocks"));
							say(c.getSource(), "Physics runs within " + PhysicsOptions.distanceLimit() + " blocks");

							return 1;
						})))
				.then(ClientCommandManager.literal("collide")
						.then(ClientCommandManager.literal("others")
								.then(ClientCommandManager.argument("on", BoolArgumentType.bool()).executes(c -> {
									PhysicsOptions.otherCollision(BoolArgumentType.getBool(c, "on"));
									say(c.getSource(), "Collision with other models and mobs " + (PhysicsOptions.otherCollision() ? "on" : "off"));

									return 1;
								})))
						.then(ClientCommandManager.literal("springs")
								.then(ClientCommandManager.argument("on", BoolArgumentType.bool()).executes(c -> {
									PhysicsOptions.springWorldCollision(BoolArgumentType.getBool(c, "on"));
									say(c.getSource(), "Spring bones colliding with blocks " + (PhysicsOptions.springWorldCollision() ? "on" : "off"));

									return 1;
								})))
						.then(ClientCommandManager.literal("water")
								.then(ClientCommandManager.argument("on", BoolArgumentType.bool()).executes(c -> {
									PhysicsOptions.water(BoolArgumentType.getBool(c, "on"));
									say(c.getSource(), "Water and lava " + (PhysicsOptions.water() ? "on" : "off"));

									return 1;
								})))
						.then(ClientCommandManager.literal("cloth")
								.then(ClientCommandManager.argument("on", BoolArgumentType.bool()).executes(c -> {
									PhysicsOptions.cloth(BoolArgumentType.getBool(c, "on"));
									say(c.getSource(), "Cloth " + (PhysicsOptions.cloth() ? "on" : "off"));

									return 1;
								})))
						.then(ClientCommandManager.literal("players")
								.then(ClientCommandManager.argument("on", BoolArgumentType.bool()).executes(c -> {
									PhysicsOptions.playerCollision(BoolArgumentType.getBool(c, "on"));
									say(c.getSource(), "Collision with players " + (PhysicsOptions.playerCollision() ? "on" : "off"));

									return 1;
								}))))
				.then(ClientCommandManager.literal("tool").executes(c -> {
					MinecraftClient client = MinecraftClient.getInstance();

					client.send(() -> client.setScreen(new PhysicsToolScreen()));

					return 1;
				}))
				.then(ClientCommandManager.literal("test")
						.then(ClientCommandManager.literal("off").executes(c -> {
							PhysicsScenarios.clear(PhysicsScenarios.DUMMY);
							say(c.getSource(), "Test scenario cleared");

							return 1;
						}))
						.then(ClientCommandManager.argument("scenario", StringArgumentType.word()).executes(c -> {
							PhysicsScenarios.Scenario scenario = PhysicsScenarios.byId(StringArgumentType.getString(c, "scenario"));

							if (scenario == null) {
								say(c.getSource(), "Scenarios: " + PhysicsScenarios.all().stream().map(PhysicsScenarios.Scenario::id).toList() + " (and off)");

								return 0;
							}

							PhysicsScenarios.apply(PhysicsScenarios.DUMMY, scenario);
							say(c.getSource(), "Scenario \"" + scenario.title() + "\" is on for the test dummy. Open /strutphysics tool to move things with the cursor.");
							scenario.steps().forEach(s -> say(c.getSource(), "Do:  " + s));
							scenario.expect().forEach(s -> say(c.getSource(), "See: " + s));

							return 1;
						})))
				.then(ClientCommandManager.literal("ragdoll")
						.then(ClientCommandManager.literal("limp").executes(c -> ragdoll(c.getSource(), controls -> controls.ragdoll(0f), "Let go: limp")))
						.then(ClientCommandManager.literal("weak").executes(c -> ragdoll(c.getSource(), controls -> controls.ragdoll(0.4f), "Let go with a weak muscle")))
						.then(ClientCommandManager.literal("stand").executes(c -> ragdoll(c.getSource(), controls -> controls.ragdollRecover(1.2f), "Standing up")))
						.then(ClientCommandManager.literal("off").executes(c -> ragdoll(c.getSource(), PhysicsControls::ragdollOff, "Ragdoll off")))
						.then(ClientCommandManager.literal("hit")
								.then(ClientCommandManager.argument("bone", StringArgumentType.word())
										.then(ClientCommandManager.argument("x", DoubleArgumentType.doubleArg())
												.then(ClientCommandManager.argument("y", DoubleArgumentType.doubleArg())
														.then(ClientCommandManager.argument("z", DoubleArgumentType.doubleArg()).executes(c -> ragdoll(c.getSource(),
																controls -> controls.ragdollHit(StringArgumentType.getString(c, "bone"), (float)DoubleArgumentType.getDouble(c, "x"),
																		(float)DoubleArgumentType.getDouble(c, "y"), (float)DoubleArgumentType.getDouble(c, "z")), "Hit"))))))))
				.then(ClientCommandManager.literal("reset").executes(c -> {
					PhysicsPoseProcessor.LIVE.values().forEach(PhysicsInstance::reset);
					say(c.getSource(), "Springs reset");

					return 1;
				})));
	}

	private static int ragdoll(FabricClientCommandSource source, Consumer<PhysicsControls> action, String message) {
		Entity entity = MinecraftClient.getInstance().targetedEntity;

		if (entity == null) {
			say(source, "Look at an entity first");

			return 0;
		}

		action.accept(StrutPhysics.of(entity));
		say(source, message + " (the model needs a ragdoll in its physics: /strutphysics test ragdoll)");

		return 1;
	}

	private static PhysicsInstance targeted() {
		Entity entity = MinecraftClient.getInstance().targetedEntity;

		return entity == null ? null : PhysicsPoseProcessor.LIVE.get(entity.getId());
	}

	private static void say(FabricClientCommandSource source, String text) {
		source.sendFeedback(Text.literal(text));
	}
}
