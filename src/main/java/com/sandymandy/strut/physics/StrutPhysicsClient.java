package com.sandymandy.strut.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sandymandy.strut.animation.state.PoseProcessors;
import com.sandymandy.strut.event.binding.EventBindings;
import com.sandymandy.strut.perf.RenderPerfStats;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import com.sandymandy.strut.physics.collision.Bodies;
import com.sandymandy.strut.physics.collision.BodyPublisher;
import com.sandymandy.strut.physics.collision.EntityBodies;
import com.sandymandy.strut.physics.collision.ModelSdfs;
import com.sandymandy.strut.physics.config.PhysicsConfigs;
import com.sandymandy.strut.physics.config.PhysicsSettings;
import com.sandymandy.strut.physics.fluid.FluidEmission;
import com.sandymandy.strut.physics.fluid.FluidPresets;
import com.sandymandy.strut.physics.fluid.FluidSystem;
import com.sandymandy.strut.physics.fluid.StrutFluids;
import com.sandymandy.strut.physics.tools.FluidCommands;
import com.sandymandy.strut.physics.tools.PhysicsCommands;
import com.sandymandy.strut.physics.tools.PhysicsDebug;
import com.sandymandy.strut.physics.tools.PhysicsStats;
import com.sandymandy.strut.physics.tools.PhysicsToolScreen;

/**
 * Entry point of the optional physics module: registers the pose processor, the sidecar loader, the F9 line, the client commands, the debug lines and the
 * {@code strut_physics:impulse} event binding action
 */
public final class StrutPhysicsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		PhysicsSettings.load();
		PoseProcessors.register(PhysicsPoseProcessor.INSTANCE);
		PoseProcessors.register(FluidEmission.INSTANCE);
		ModelSdfs.enabled(true);
		PoseProcessors.register(BodyPublisher.INSTANCE);

		// The cursor tool: unbound by default (set a key in Controls, under Strut), or use /strutphysics tool
		KeyBinding toolKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.strut_physics.tool", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.strut"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toolKey.wasPressed()) {
				if (client.currentScreen == null && client.world != null)
					client.setScreen(new PhysicsToolScreen());
			}
		});
		ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new PhysicsConfigs());
		ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new FluidPresets());
		RenderPerfStats.addSummaryLineProvider(PhysicsStats::lines);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> PhysicsCommands.register(dispatcher));
		WorldRenderEvents.START.register(context -> {
			PhysicsStats.nextFrame();
			Bodies.nextFrame();
			EntityBodies.publish();
			FluidSystem.update();
		});
		WorldRenderEvents.AFTER_ENTITIES.register(FluidSystem::draw);
		WorldRenderEvents.LAST.register(PhysicsDebug::draw);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			StrutPhysics.clearAll();
			Bodies.clear();
			PhysicsPoseProcessor.LIVE.clear();
			FluidSystem.clear();
		});

		// An animation clip can kick a bone on every viewer: { "bone": "belly", "impulse": [0, -2, 0] }, or a list of those, in an event binding file
		EventBindings.registerAction(Identifier.of(StrutPhysics.MOD_ID, "impulse"), (context, config) -> {
			if (context.entity() == null)
				return;

			JsonArray list = new JsonArray();

			if (config.isJsonArray())
				list = config.getAsJsonArray();
			else if (config.isJsonObject())
				list.add(config);

			for (JsonElement element : list) {
				JsonObject object = element.getAsJsonObject();
				JsonArray v = object.getAsJsonArray("impulse");

				StrutPhysics.of(context.entity()).impulse(object.get("bone").getAsString(), v.get(0).getAsFloat(), v.get(1).getAsFloat(), v.get(2).getAsFloat());
			}
		});

		// An animation clip can spray the thick liquid from a bone: { "bone": "tip", "count": 14, "speed": 2.2, "spread": 0.25 }, or a list of those.
		// Optional: "preset" (default strut_physics:thick), "duration" (seconds to spread the particles over, 0 for a burst), "jitter", "inherit",
		// "offset": [x, y, z] (blocks, from the pivot of the bone) and "direction": [x, y, z] (in the space of the bone; the default is its Blockbench front, 0 0 -1)
		EventBindings.registerAction(Identifier.of(StrutPhysics.MOD_ID, "fluid"), (context, config) -> {
			if (context.entity() == null)
				return;

			JsonArray list = new JsonArray();

			if (config.isJsonArray())
				list = config.getAsJsonArray();
			else if (config.isJsonObject())
				list.add(config);

			for (JsonElement element : list) {
				JsonObject object = element.getAsJsonObject();

				StrutFluids.emit(context.entity(), object.get("bone").getAsString(), FluidCommands.emissionOf(object));
			}
		});
	}
}
