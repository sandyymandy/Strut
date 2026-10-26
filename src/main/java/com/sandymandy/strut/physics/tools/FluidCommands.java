package com.sandymandy.strut.physics.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import com.sandymandy.strut.physics.config.PhysicsOptions;
import com.sandymandy.strut.physics.config.PhysicsSettings;
import com.sandymandy.strut.physics.fluid.FluidSystem;
import com.sandymandy.strut.physics.fluid.StrutFluids;

/**
 * {@code /strutphysics fluid ...}: try the thick liquid out without an animation. {@code spray} and {@code burst} start in front of you, {@code bone} starts at
 * a bone of the entity you are looking at (the test dummy's {@code cock} bone is a good one), {@code clear} removes every particle and {@code info} says
 * what is going on
 */
public final class FluidCommands {
	private FluidCommands() {}

	static LiteralArgumentBuilder<FabricClientCommandSource> branch() {
		return ClientCommandManager.literal("fluid")
				.then(ClientCommandManager.literal("on").executes(c -> {
					PhysicsOptions.fluid(true);
					PhysicsSettings.save();
					say(c.getSource(), "Fluid on");

					return 1;
				}))
				.then(ClientCommandManager.literal("off").executes(c -> {
					PhysicsOptions.fluid(false);
					PhysicsSettings.save();
					say(c.getSource(), "Fluid off");

					return 1;
				}))
				.then(ClientCommandManager.literal("spray").executes(c -> spray(c.getSource(), 60)).then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 800))
						.executes(c -> spray(c.getSource(), IntegerArgumentType.getInteger(c, "count")))))
				.then(ClientCommandManager.literal("burst").executes(c -> burst(c.getSource(), 80)).then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 800))
						.executes(c -> burst(c.getSource(), IntegerArgumentType.getInteger(c, "count")))))
				.then(ClientCommandManager.literal("bone").then(ClientCommandManager.argument("bone", StringArgumentType.word())
						.executes(c -> fromBone(c.getSource(), StringArgumentType.getString(c, "bone"), 24))
						.then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 800))
								.executes(c -> fromBone(c.getSource(), StringArgumentType.getString(c, "bone"), IntegerArgumentType.getInteger(c, "count"))))))
				.then(ClientCommandManager.literal("clear").executes(c -> {
					StrutFluids.clear();
					say(c.getSource(), "Fluid cleared");

					return 1;
				}))
				.then(ClientCommandManager.literal("info").executes(c -> {
					String line = FluidSystem.line();

					say(c.getSource(), (PhysicsOptions.fluid() ? "Fluid on, " : "Fluid OFF, ") + "up to " + FluidSystem.cap() + " particles. " + (line == null ? "Nothing in the world." : line));

					return 1;
				}));
	}

	private static int spray(FabricClientCommandSource source, int count) {
		Entity player = MinecraftClient.getInstance().player;

		if (player == null)
			return 0;

		Vec3d eye = player.getEyePos();
		Vec3d look = player.getRotationVec(1f);

		StrutFluids.emitAt(StrutFluids.THICK, eye.x + look.x * 0.8, eye.y - 0.2 + look.y * 0.8, eye.z + look.z * 0.8, look.x, look.y + 0.25, look.z,
				StrutFluids.Emission.stream(StrutFluids.THICK, count, 1.5f).speed(2.4f).spread(0.12f));
		say(source, "Spraying " + count + " particles from in front of you");

		return 1;
	}

	private static int burst(FabricClientCommandSource source, int count) {
		Entity player = MinecraftClient.getInstance().player;

		if (player == null)
			return 0;

		Vec3d eye = player.getEyePos();
		Vec3d look = player.getRotationVec(1f);

		StrutFluids.emitAt(StrutFluids.THICK, eye.x + look.x * 1.2, eye.y - 0.1 + look.y * 1.2, eye.z + look.z * 1.2, 0, 1, 0,
				StrutFluids.Emission.burst(StrutFluids.THICK, count).speed(3.2f).spread(0.6f));
		say(source, "Burst of " + count + " particles in front of you");

		return 1;
	}

	private static int fromBone(FabricClientCommandSource source, String bone, int count) {
		Entity target = MinecraftClient.getInstance().targetedEntity;

		if (target == null) {
			say(source, "Look at an entity with a model first");

			return 0;
		}

		StrutFluids.emit(target, bone, StrutFluids.Emission.stream(StrutFluids.THICK, count, 0.8f).speed(2.0f).spread(0.2f));
		say(source, "Emitting " + count + " particles from bone \"" + bone + "\" (nothing appears if the model has no such bone)");

		return 1;
	}

	/** The emission an event binding or a data file describes; see the {@code strut_physics:fluid} action */
	public static StrutFluids.Emission emissionOf(JsonObject object) {
		Identifier preset = object.has("preset") ? Identifier.tryParse(object.get("preset").getAsString()) : StrutFluids.THICK;
		StrutFluids.Emission e = new StrutFluids.Emission(preset == null ? StrutFluids.THICK : preset, object.has("count") ? object.get("count").getAsInt() : 12,
				object.has("duration") ? object.get("duration").getAsFloat() : 0f, object.has("speed") ? object.get("speed").getAsFloat() : 1.5f,
				object.has("spread") ? object.get("spread").getAsFloat() : 0.25f, object.has("jitter") ? object.get("jitter").getAsFloat() : 0.2f, null, null,
				object.has("inherit") ? object.get("inherit").getAsFloat() : 0.5f);

		if (object.has("offset")) {
			JsonArray v = object.getAsJsonArray("offset");

			e = e.offset(v.get(0).getAsFloat(), v.get(1).getAsFloat(), v.get(2).getAsFloat());
		}

		if (object.has("direction")) {
			JsonArray v = object.getAsJsonArray("direction");

			e = e.direction(v.get(0).getAsFloat(), v.get(1).getAsFloat(), v.get(2).getAsFloat());
		}

		return e;
	}

	private static void say(FabricClientCommandSource source, String text) {
		source.sendFeedback(Text.literal(text));
	}
}
