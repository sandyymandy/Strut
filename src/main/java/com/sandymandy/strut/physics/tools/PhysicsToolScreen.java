package com.sandymandy.strut.physics.tools;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.PhysicsPoseProcessor;
import com.sandymandy.strut.physics.StrutPhysics;
import com.sandymandy.strut.physics.config.PhysicsConfigs;
import com.sandymandy.strut.physics.config.PhysicsOptions;

/**
 * The in-game physics tool: a screen that frees the cursor so you can poke at the physics ("how to test it in game")
 * <ul>
 * <li><b>Left-drag</b> a yellow dot (a chain particle or a spring bone's mass) to hold it under the cursor; let go and it springs back</li>
 * <li><b>Right-drag</b> an entity to slide it over the ground, and hold <b>Shift</b> to lift or lower it, so its motion drives the inertia and its body meets blocks</li>
 * <li>Buttons load a <b>test scenario</b> for the entity's model, one per physics system, with what to do and what you should see written on screen</li>
 * <li>Buttons for the debug lines, world collision, slow motion, and the master switch</li>
 * </ul>
 * The game keeps running behind it. Escape leaves and lets everything go
 */
public final class PhysicsToolScreen extends Screen {
	private static final double PICK_RADIUS = 0.13;

	private Entity target;
	private PhysicsScenarios.Scenario active;
	private String message = "";
	private PhysicsInstance.Pickable hover;
	private int hoverEntity = -1;
	private int grabEntity = -1;
	private PhysicsInstance.Pickable grabbed;
	private double grabDistance;
	private Entity dragged;
	private double dragOffsetX;
	private double dragOffsetY;
	private double dragOffsetZ;

	public PhysicsToolScreen() {
		super(Text.literal("Strut Physics Tool"));
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	protected void init() {
		MinecraftClient client = MinecraftClient.getInstance();

		this.target = client.targetedEntity;

		if (this.target == null) {
			for (int id : PhysicsPoseProcessor.LIVE.keySet()) {
				Entity e = client.world == null ? null : client.world.getEntityById(id);

				if (e != null && (this.target == null || e.squaredDistanceTo(client.player) < this.target.squaredDistanceTo(client.player)))
					this.target = e;
			}
		}

		PhysicsToolState.open = true;

		int x = 6;
		int y = 6;
		int w = 118;

		this.addDrawableChild(ButtonWidget.builder(Text.literal("Scenarios:"), b -> {}).dimensions(x, y, w, 14).build()).active = false;
		y += 16;

		for (PhysicsScenarios.Scenario scenario : PhysicsScenarios.all()) {
			this.addDrawableChild(ButtonWidget.builder(Text.literal(scenario.title()), b -> loadScenario(scenario)).dimensions(x, y, w, 18).build());
			y += 20;
		}

		this.addDrawableChild(ButtonWidget.builder(Text.literal("No scenario"), b -> clearScenario()).dimensions(x, y, w, 18).build());
		y += 26;
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Options:"), b -> {}).dimensions(x, y, w, 14).build()).active = false;
		y += 16;
		this.addDrawableChild(ButtonWidget.builder(label("Debug lines", PhysicsDebug.drawing), b -> {
			PhysicsDebug.drawing = !PhysicsDebug.drawing;
			b.setMessage(label("Debug lines", PhysicsDebug.drawing));
		}).dimensions(x, y, w, 18).build());
		y += 20;
		this.addDrawableChild(ButtonWidget.builder(label("World collision", PhysicsOptions.worldCollision()), b -> {
			PhysicsOptions.worldCollision(!PhysicsOptions.worldCollision());
			b.setMessage(label("World collision", PhysicsOptions.worldCollision()));
		}).dimensions(x, y, w, 18).build());
		y += 20;
		this.addDrawableChild(ButtonWidget.builder(label("Physics", PhysicsOptions.enabled()), b -> {
			PhysicsOptions.enabled(!PhysicsOptions.enabled());
			b.setMessage(label("Physics", PhysicsOptions.enabled()));
		}).dimensions(x, y, w, 18).build());
		y += 20;
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Speed: " + speedName()), b -> {
			float next = PhysicsOptions.timeScale() > 0.9f ? 0.25f : PhysicsOptions.timeScale() > 0.2f ? 0.1f : 1f;

			PhysicsOptions.timeScale(next);
			b.setMessage(Text.literal("Speed: " + speedName()));
		}).dimensions(x, y, w, 18).build());
		y += 20;
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Reset springs"), b -> PhysicsPoseProcessor.LIVE.values().forEach(PhysicsInstance::reset)).dimensions(x, y, w, 18).build());
		y += 20;
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Settings..."), b -> this.client.setScreen(new PhysicsOptionsScreen(this))).dimensions(x, y, w, 18).build());
		y += 26;
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Ragdoll:"), b -> {}).dimensions(x, y, w, 14).build()).active = false;
		y += 16;
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Let go (limp)"), b -> ragdoll(0f)).dimensions(x, y, w, 18).build());
		y += 20;
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Weak muscle"), b -> ragdoll(0.4f)).dimensions(x, y, w, 18).build());
		y += 20;
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Stand up"), b -> standUp()).dimensions(x, y, w, 18).build());
		y += 20;
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Shove"), b -> shove()).dimensions(x, y, w, 18).build());
	}

	private static Text label(String name, boolean on) {
		return Text.literal(name + ": " + (on ? "on" : "off"));
	}

	private static String speedName() {
		return PhysicsOptions.timeScale() > 0.9f ? "normal" : PhysicsOptions.timeScale() > 0.2f ? "slow 1/4" : "slow 1/10";
	}

	// ---- scenarios -------------------------------------------------------------------------------------------------------------------------------

	private Identifier stem() {
		if (this.target == null)
			return null;

		Identifier type = EntityType.getId(this.target.getType());

		return PhysicsConfigs.firstLoaded(List.of(Identifier.of(type.getNamespace(), "strut/models/entity/" + type.getPath()), Identifier.of(type.getNamespace(), "strut/models/" + type.getPath())));
	}

	private PhysicsControls targetControls() {
		if (this.target == null) {
			this.message = "No target entity: look at one, then open the tool";

			return null;
		}

		return StrutPhysics.of(this.target);
	}

	private void ragdoll(float strength) {
		PhysicsControls controls = targetControls();

		if (controls != null) {
			controls.ragdoll(strength);
			this.message = strength <= 0f ? "Let go: the ragdoll takes over (needs a ragdoll in the model's physics: the Ragdoll scenario)" : "Weak muscle: it tries to stand";
		}
	}

	private void standUp() {
		PhysicsControls controls = targetControls();

		if (controls != null) {
			controls.ragdollRecover(1.2f);
			this.message = "Standing up over about a second";
		}
	}

	private void shove() {
		PhysicsControls controls = targetControls();

		if (controls != null) {
			controls.ragdollHit("torso", 6f, 3f, 6f);
			this.message = "Shoved the torso";
		}
	}

	private void loadScenario(PhysicsScenarios.Scenario scenario) {
		Identifier stem = stem();

		if (stem == null) {
			this.message = "No target entity, or its model was not found. Look at the entity, then open the tool (scenarios are written for Strut's test dummy: /strutphysics test).";

			return;
		}

		PhysicsScenarios.apply(stem, scenario);

		if (this.target != null) {
			StrutPhysics.of(this.target).ragdollOff();
			PhysicsScenarios.start(scenario, StrutPhysics.of(this.target));
		}

		this.active = scenario;
		this.message = "Scenario \"" + scenario.title() + "\" loaded on " + stem;
	}

	private void clearScenario() {
		Identifier stem = stem();

		if (stem != null)
			PhysicsScenarios.clear(stem);

		this.active = null;
		this.message = "Scenario cleared: the model uses its own physics again";
	}

	// ---- picking and dragging ---------------------------------------------------------------------------------------------------------------------

	private double[] cameraOrigin() {
		Vec3d pos = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();

		return new double[] {pos.x, pos.y, pos.z};
	}

	private double[] ray(double mouseX, double mouseY) {
		MinecraftClient client = MinecraftClient.getInstance();
		Camera camera = client.gameRenderer.getCamera();
		double aspect = (double)this.width / Math.max(1, this.height);

		return PhysicsRay.direction(camera.getYaw(), camera.getPitch(), client.options.getFov().getValue(), aspect, mouseX / this.width * 2 - 1, 1 - mouseY / this.height * 2);
	}

	private void updateHover(double mouseX, double mouseY) {
		double[] origin = cameraOrigin();
		double[] dir = ray(mouseX, mouseY);
		double best = Double.MAX_VALUE;
		List<PhysicsInstance.Pickable> pickables = new ArrayList<>();

		this.hover = null;
		this.hoverEntity = -1;

		for (Map.Entry<Integer, PhysicsInstance> entry : PhysicsPoseProcessor.LIVE.entrySet()) {
			pickables.clear();
			entry.getValue().pickables(pickables);

			for (PhysicsInstance.Pickable p : pickables) {
				double t = PhysicsRay.sphere(origin, dir, p.x(), p.y(), p.z(), PICK_RADIUS);

				if (t >= 0 && t < best) {
					best = t;
					this.hover = p;
					this.hoverEntity = entry.getKey();
				}
			}
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button))
			return true;

		updateHover(mouseX, mouseY);

		if (button == 0 && this.hover != null) {
			double[] origin = cameraOrigin();

			this.grabbed = this.hover;
			this.grabEntity = this.hoverEntity;
			this.grabDistance = Math.sqrt(Math.pow(this.hover.x() - origin[0], 2) + Math.pow(this.hover.y() - origin[1], 2) + Math.pow(this.hover.z() - origin[2], 2));

			return true;
		}

		if (button == 1) {
			this.dragged = entityUnder(mouseX, mouseY);

			if (this.dragged != null) {
				double[] hit = dragPoint(mouseX, mouseY, false);

				this.dragOffsetX = hit == null ? 0 : this.dragged.getX() - hit[0];
				this.dragOffsetY = this.dragged.getY();
				this.dragOffsetZ = hit == null ? 0 : this.dragged.getZ() - hit[2];

				return true;
			}
		}

		return false;
	}

	private Entity entityUnder(double mouseX, double mouseY) {
		MinecraftClient client = MinecraftClient.getInstance();
		double[] origin = cameraOrigin();
		double[] dir = ray(mouseX, mouseY);
		Entity best = null;
		double bestT = Double.MAX_VALUE;

		for (int id : PhysicsPoseProcessor.LIVE.keySet()) {
			Entity e = client.world == null ? null : client.world.getEntityById(id);

			if (e == null)
				continue;

			Box b = e.getBoundingBox().expand(0.3, 0.2, 0.3);
			double t = PhysicsRay.box(origin, dir, new double[] {b.minX, b.minY, b.minZ}, new double[] {b.maxX, b.maxY, b.maxZ});

			if (t >= 0 && t < bestT) {
				bestT = t;
				best = e;
			}
		}

		return best;
	}

	/** Where the cursor points on the ground plane at the dragged entity's height, or on a vertical plane through it while Shift is held */
	private double[] dragPoint(double mouseX, double mouseY, boolean vertical) {
		double[] origin = cameraOrigin();
		double[] dir = ray(mouseX, mouseY);

		if (this.dragged == null)
			return null;

		if (!vertical)
			return PhysicsRay.plane(origin, dir, new double[] {0, this.dragged.getY(), 0}, new double[] {0, 1, 0});

		Camera camera = MinecraftClient.getInstance().gameRenderer.getCamera();
		double[] f = PhysicsRay.forward(camera.getYaw(), 0);

		return PhysicsRay.plane(origin, dir, new double[] {this.dragged.getX(), this.dragged.getY(), this.dragged.getZ()}, f);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		follow(mouseX, mouseY);

		return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		follow(mouseX, mouseY);
		super.mouseMoved(mouseX, mouseY);
	}

	/** Moves whatever is held to where the cursor is now */
	private void follow(double mouseX, double mouseY) {
		if (this.grabbed != null) {
			double[] origin = cameraOrigin();
			double[] dir = ray(mouseX, mouseY);

			StrutPhysics.ofId(this.grabEntity).grab(this.grabbed.part(), this.grabbed.index(), origin[0] + dir[0] * this.grabDistance, origin[1] + dir[1] * this.grabDistance,
					origin[2] + dir[2] * this.grabDistance);
		}

		if (this.dragged != null) {
			boolean vertical = Screen.hasShiftDown();
			double[] hit = dragPoint(mouseX, mouseY, vertical);

			if (hit != null) {
				double x = this.dragged.getX();
				double y = this.dragged.getY();
				double z = this.dragged.getZ();

				if (vertical)
					y = hit[1];
				else {
					x = hit[0] + this.dragOffsetX;
					z = hit[2] + this.dragOffsetZ;
				}

				// A client-side move: the previous position follows so nothing is smoothed, and the physics sees the entity go where the cursor goes
				this.dragged.setPosition(x, y, z);
				this.dragged.prevX = x;
				this.dragged.prevY = y;
				this.dragged.prevZ = z;
				this.dragged.lastRenderX = x;
				this.dragged.lastRenderY = y;
				this.dragged.lastRenderZ = z;
			}
		}
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (button == 0 && this.grabbed != null) {
			StrutPhysics.ofId(this.grabEntity).release();
			this.grabbed = null;
		}

		if (button == 1)
			this.dragged = null;

		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public void close() {
		if (this.grabbed != null)
			StrutPhysics.ofId(this.grabEntity).release();

		this.grabbed = null;
		this.dragged = null;
		PhysicsToolState.open = false;
		PhysicsToolState.crosses = List.of();
		super.close();
	}

	// ---- drawing -----------------------------------------------------------------------------------------------------------------------------------

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		// no dimming: the world is the point
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		if (this.grabbed == null && this.dragged == null)
			updateHover(mouseX, mouseY);
		else
			follow(mouseX, mouseY);

		refreshCrosses();
		super.render(context, mouseX, mouseY, delta);

		int x = 130;
		int y = 8;

		context.drawTextWithShadow(this.textRenderer, "Strut Physics Tool  -  Esc to leave", x, y, 0xFFFFFF);
		y += 12;
		context.drawTextWithShadow(this.textRenderer, "Left-drag a yellow dot: hold it.   Right-drag an entity: move it (Shift: up and down).", x, y, 0xB0B0B0);
		y += 14;

		if (this.active != null) {
			context.drawTextWithShadow(this.textRenderer, this.active.title() + "   (" + this.active.system() + ")", x, y, 0xFFD040);
			y += 12;

			for (String step : this.active.steps()) {
				context.drawTextWithShadow(this.textRenderer, "Do:  " + step, x, y, 0xE0E0E0);
				y += 11;
			}

			for (String expect : this.active.expect()) {
				context.drawTextWithShadow(this.textRenderer, "See: " + expect, x, y, 0x80E080);
				y += 11;
			}

			y += 3;
		}

		if (this.hover != null && this.grabbed == null)
			context.drawTextWithShadow(this.textRenderer, "hold: " + this.hover.part() + (this.hover.index() > 0 ? " #" + this.hover.index() : ""), x, y, 0xFFFFA0);
		else if (this.grabbed != null)
			context.drawTextWithShadow(this.textRenderer, "holding: " + this.grabbed.part() + (this.grabbed.index() > 0 ? " #" + this.grabbed.index() : ""), x, y, 0xA0FFA0);
		else if (this.dragged != null)
			context.drawTextWithShadow(this.textRenderer, "moving the entity", x, y, 0xA0FFA0);

		y += 12;

		if (!this.message.isEmpty())
			context.drawTextWithShadow(this.textRenderer, this.message, x, y, 0xFFB0B0);

		if (PhysicsPoseProcessor.LIVE.isEmpty())
			context.drawTextWithShadow(this.textRenderer, "No entity with physics is drawn right now: pick a scenario to give the entity you looked at some.", x, this.height - 14, 0xFFB0B0);
	}

	/** Puts a cross on every pickable part for the world pass to draw; the one under the cursor is bigger, the held one is green */
	private void refreshCrosses() {
		List<PhysicsToolState.Cross> crosses = new ArrayList<>();
		List<PhysicsInstance.Pickable> pickables = new ArrayList<>();

		for (Map.Entry<Integer, PhysicsInstance> entry : PhysicsPoseProcessor.LIVE.entrySet()) {
			pickables.clear();
			entry.getValue().pickables(pickables);

			for (PhysicsInstance.Pickable p : pickables) {
				boolean isHeld = this.grabbed != null && this.grabEntity == entry.getKey() && p.part().equals(this.grabbed.part()) && p.index() == this.grabbed.index();
				boolean isHover = this.hover != null && this.hoverEntity == entry.getKey() && p.part().equals(this.hover.part()) && p.index() == this.hover.index();

				crosses.add(new PhysicsToolState.Cross(p.x(), p.y(), p.z(), isHeld ? 0.09f : isHover ? 0.08f : 0.045f, isHeld ? 0x40FF40 : isHover ? 0xFFFFFF : 0xFFD040));
			}
		}

		PhysicsToolState.crosses = crosses;
	}
}
