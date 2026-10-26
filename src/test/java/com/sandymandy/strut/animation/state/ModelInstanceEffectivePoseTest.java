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
import java.util.Map;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ModelInstance#effectivePose()}
 * has to layer a resolved bone override onto {@link ModelInstance#renderedPose()} correctly, and
 * has to leave {@code renderedPose()} itself completely untouched while doing it - the same
 * "read-only base, write into a scratch copy" contract {@code PosePalette.compute} already proves
 * for the palette itself
 * <p>
 * Each test claims its own entity id ({@link #nextEntityId}) and drops it in {@link #cleanUp} -
 * {@link ClientBoneOverrideStates} is a static, process-wide registry, so tests sharing an id would
 * silently see each other's state
 */
class ModelInstanceEffectivePoseTest {
	private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(900_000);

	private final int entityId = NEXT_ENTITY_ID.getAndIncrement();

	@AfterEach
	void cleanUp() {
		ClientBoneOverrideStates.remove(this.entityId);
	}

	private static BakedStrutModel buildModel() {
		GroupBoneDefinition root = new GroupBoneDefinition(null, "root", false, null, null, null);
		GroupBoneDefinition child = new GroupBoneDefinition(root, "child", false, null, null, null);

		root.bakePivot(0, 0, 0);
		child.bakePivot(0, 8, 0);
		root.getChildBones().add(child);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null, null, null,
				"test", null, 16, 16, null, new double[] {0, 0, 0}, null);

		return new BakedStrutModel(List.of(root), properties);
	}

	/**
	 * root -> child -> grandchild, plus a sibling of child - deep enough to prove
	 * {@code CHILDREN_HIDDEN} propagates past direct children (to {@code grandchild}) without
	 * leaking sideways onto an unrelated bone at the same depth ({@code sibling})
	 */
	private static BakedStrutModel buildDeepModel() {
		GroupBoneDefinition root = new GroupBoneDefinition(null, "root", false, null, null, null);
		GroupBoneDefinition child = new GroupBoneDefinition(root, "child", false, null, null, null);
		GroupBoneDefinition grandchild = new GroupBoneDefinition(child, "grandchild", false, null, null, null);
		GroupBoneDefinition sibling = new GroupBoneDefinition(root, "sibling", false, null, null, null);

		root.bakePivot(0, 0, 0);
		child.bakePivot(0, 8, 0);
		grandchild.bakePivot(0, 4, 0);
		sibling.bakePivot(5, 6, 1);

		root.getChildBones().add(child);
		root.getChildBones().add(sibling);
		child.getChildBones().add(grandchild);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null, null, null,
				"test", null, 16, 16, null, new double[] {0, 0, 0}, null);

		return new BakedStrutModel(List.of(root), properties);
	}

	private ModelInstance readyInstance(BakedStrutModel model) {
		ModelInstance instance = new ModelInstance();

		instance.poseFor(model);
		instance.resetPoseHistory();

		return instance;
	}

	@Test
	void effectivePoseIsRenderedPoseItselfWhenNothingOverridesAnything() {
		ModelInstance instance = readyInstance(buildModel());

		instance.prepareForRender(this.entityId);

		assertSame(instance.renderedPose(), instance.effectivePose());
	}

	@Test
	void effectivePoseIsRenderedPoseItselfBeforePrepareForRenderIsEverCalled() {
		ModelInstance instance = readyInstance(buildModel());

		assertSame(instance.renderedPose(), instance.effectivePose());
	}

	@Test
	void aHiddenOverrideAppliesToTheNamedBoneOnly() {
		BakedStrutModel model = buildModel();
		ModelInstance instance = readyInstance(model);

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.HIDDEN,
				new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		int childIndex = model.getBone("child").orElseThrow().getIndex();
		int rootIndex = model.getBone("root").orElseThrow().getIndex();

		assertTrue(instance.effectivePose().isHidden(childIndex));
		assertFalse(instance.effectivePose().isHidden(rootIndex), "the override must not leak onto an unrelated bone");
		assertFalse(instance.renderedPose().isHidden(childIndex), "renderedPose() itself must stay untouched");
	}

	@Test
	void aPositionOverrideAppliesToTheNamedBone() {
		BakedStrutModel model = buildModel();
		ModelInstance instance = readyInstance(model);

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.POSITION,
				new OverrideValue.Vec3Value(1f, 2f, 3f), OverrideScope.PERSISTENT, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		int childIndex = model.getBone("child").orElseThrow().getIndex();
		Pose effective = instance.effectivePose();

		assertEquals(1f, effective.getPosX(childIndex), 1.0e-6f);
		assertEquals(2f, effective.getPosY(childIndex), 1.0e-6f);
		assertEquals(3f, effective.getPosZ(childIndex), 1.0e-6f);
		assertEquals(0f, instance.renderedPose().getPosX(childIndex), 1.0e-6f, "renderedPose() itself must stay untouched");
	}

	@Test
	void aColorOverrideDoesNotTriggerAPoseRebuildAtAll() {
		BakedStrutModel model = buildModel();
		ModelInstance instance = readyInstance(model);

		// COLOR resolves into the GPU visual buffer, not the Pose - a bone with only a
		// COLOR override set must not pay for (or produce) a scratch copy at all
		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.COLOR,
				new OverrideValue.ColorValue(0xFFFF0000), OverrideScope.PERSISTENT, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		assertSame(instance.renderedPose(), instance.effectivePose());
	}

	@Test
	void boneVisualsIsNullWhenNoColorOrUvOffsetOverrideIsActive() {
		ModelInstance instance = readyInstance(buildModel());

		instance.prepareForRender(this.entityId);

		assertNull(instance.boneVisuals());
	}

	@Test
	void aColorOverrideResolvesIntoBoneVisualsOnTheNamedBoneOnly() {
		BakedStrutModel model = buildModel();
		ModelInstance instance = readyInstance(model);

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.COLOR,
				new OverrideValue.ColorValue(0xFFFF0000), OverrideScope.PERSISTENT, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		int childIndex = model.getBone("child").orElseThrow().getIndex();
		int rootIndex = model.getBone("root").orElseThrow().getIndex();
		BoneVisuals visuals = instance.boneVisuals();

		assertNotNull(visuals, "an active COLOR override must produce a non-null BoneVisuals");
		assertEquals(0xFFFF0000, visuals.getColor(childIndex));
		assertEquals(0xFFFFFFFF, visuals.getColor(rootIndex), "the override must not leak onto an unrelated bone");
	}

	@Test
	void aUvOffsetOverrideResolvesIntoBoneVisualsOnTheNamedBoneOnly() {
		BakedStrutModel model = buildModel();
		ModelInstance instance = readyInstance(model);

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.UV_OFFSET,
				new OverrideValue.Vec2Value(0.25f, 0.5f), OverrideScope.PERSISTENT, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		int childIndex = model.getBone("child").orElseThrow().getIndex();
		int rootIndex = model.getBone("root").orElseThrow().getIndex();
		BoneVisuals visuals = instance.boneVisuals();

		assertNotNull(visuals, "an active UV_OFFSET override must produce a non-null BoneVisuals");
		assertEquals(0.25f, visuals.getUOffset(childIndex), 1.0e-6f);
		assertEquals(0.5f, visuals.getVOffset(childIndex), 1.0e-6f);
		assertEquals(0f, visuals.getUOffset(rootIndex), 1.0e-6f, "the override must not leak onto an unrelated bone");
	}

	@Test
	void aUvSizeOverrideScalesTheNamedBonesUvsByTheModelsTextureSizeOverTheOverridesAndNothingElse() {
		BakedStrutModel model = buildModel();
		ModelInstance instance = readyInstance(model);

		// the model is made for a 16 x 16 texture; the override texture is a 64 x 32 one
		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.UV_SIZE,
				new OverrideValue.Vec2Value(64f, 32f), OverrideScope.PERSISTENT, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		int childIndex = model.getBone("child").orElseThrow().getIndex();
		int rootIndex = model.getBone("root").orElseThrow().getIndex();
		BoneVisuals visuals = instance.boneVisuals();

		assertNotNull(visuals, "an active UV_SIZE override must produce a non-null BoneVisuals");
		assertEquals(0.25f, visuals.getUScale(childIndex), 1.0e-6f);
		assertEquals(0.5f, visuals.getVScale(childIndex), 1.0e-6f);
		assertEquals(1f, visuals.getUScale(rootIndex), 1.0e-6f, "the override must not leak onto an unrelated bone");
		assertEquals(0f, visuals.getUOffset(childIndex), 1.0e-6f, "a size is not an offset");
	}

	@Test
	void aUvSizeOfZeroIsIgnoredRatherThanDividedBy() {
		BakedStrutModel model = buildModel();
		ModelInstance instance = readyInstance(model);

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.UV_SIZE,
				new OverrideValue.Vec2Value(0f, 64f), OverrideScope.PERSISTENT, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		int childIndex = model.getBone("child").orElseThrow().getIndex();

		assertEquals(1f, instance.boneVisuals().getUScale(childIndex), 1.0e-6f);
		assertEquals(1f, instance.boneVisuals().getVScale(childIndex), 1.0e-6f);
	}

	@Test
	void aHiddenOverrideAloneDoesNotProduceBoneVisualsAtAll() {
		BakedStrutModel model = buildModel();
		ModelInstance instance = readyInstance(model);

		// HIDDEN is pose-affecting only (see ModelInstance#affectsPose) - it has no business
		// allocating a BoneVisuals, the same "don't pay for a property that doesn't apply" rule
		// aColorOverrideDoesNotTriggerAPoseRebuildAtAll proves in the other direction
		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.HIDDEN,
				new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		assertNull(instance.boneVisuals());
	}

	@Test
	void anOverrideOnANonexistentBoneNameIsSilentlyIgnored() {
		BakedStrutModel model = buildModel();
		ModelInstance instance = readyInstance(model);

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("no_such_bone", OverrideProperty.HIDDEN,
				new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		assertDoesNotThrow(instance::effectivePose);
	}

	@Test
	void childrenHiddenPropagatesToGrandchildrenButNotSiblings() {
		BakedStrutModel model = buildDeepModel();
		ModelInstance instance = readyInstance(model);

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.CHILDREN_HIDDEN,
				new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		int childIndex = model.getBone("child").orElseThrow().getIndex();
		int grandchildIndex = model.getBone("grandchild").orElseThrow().getIndex();
		int siblingIndex = model.getBone("sibling").orElseThrow().getIndex();
		int rootIndex = model.getBone("root").orElseThrow().getIndex();
		Pose effective = instance.effectivePose();

		assertTrue(effective.isHidden(grandchildIndex), "CHILDREN_HIDDEN on child must fold into grandchild's HIDDEN bit");
		assertFalse(effective.isHidden(siblingIndex), "propagation must not leak onto an unrelated bone at the same depth");
		assertFalse(effective.isHidden(rootIndex), "propagation must not leak upward onto an ancestor");
		assertFalse(effective.isHidden(childIndex), "CHILDREN_HIDDEN hides descendants, not the bone that set it");
		assertFalse(instance.renderedPose().isHidden(grandchildIndex), "renderedPose() itself must stay untouched");
	}

	@Test
	void aHiddenOverrideInheritsToGrandchildrenButNotSiblingsOrAncestors() {
		BakedStrutModel model = buildDeepModel();
		ModelInstance instance = readyInstance(model);

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.HIDDEN,
				new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		int childIndex = model.getBone("child").orElseThrow().getIndex();
		int grandchildIndex = model.getBone("grandchild").orElseThrow().getIndex();
		int siblingIndex = model.getBone("sibling").orElseThrow().getIndex();
		int rootIndex = model.getBone("root").orElseThrow().getIndex();
		Pose effective = instance.effectivePose();

		assertTrue(effective.isHidden(childIndex), "the named bone itself must still be hidden");
		assertTrue(effective.isHidden(grandchildIndex), "HIDDEN must now inherit down to a descendant with no override of its own");
		assertFalse(effective.isHidden(siblingIndex), "inheritance must not leak onto an unrelated bone at the same depth");
		assertFalse(effective.isHidden(rootIndex), "inheritance must not leak upward onto an ancestor");
	}

	@Test
	void aBonesOwnHiddenOverrideWinsOverAnInheritedOne() {
		BakedStrutModel model = buildDeepModel();
		ModelInstance instance = readyInstance(model);

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.HIDDEN,
				new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);
		// "child first" - grandchild's own override must beat what it would otherwise inherit from child
		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("grandchild", OverrideProperty.HIDDEN,
				new OverrideValue.BoolValue(false), OverrideScope.PERSISTENT, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		int grandchildIndex = model.getBone("grandchild").orElseThrow().getIndex();

		assertFalse(instance.effectivePose().isHidden(grandchildIndex),
				"grandchild's own HIDDEN=false must win over child's inherited HIDDEN=true");
	}

	@Test
	void childrenHiddenStaysUnconditionalEvenAgainstADescendantsOwnHiddenOverride() {
		BakedStrutModel model = buildDeepModel();
		ModelInstance instance = readyInstance(model);

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.CHILDREN_HIDDEN,
				new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);
		// Unlike plain HIDDEN, CHILDREN_HIDDEN must not be overridable by a descendant's own opt-out -
		// that guarantee is the entire reason it exists separately from (now-inheriting) HIDDEN
		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("grandchild", OverrideProperty.HIDDEN,
				new OverrideValue.BoolValue(false), OverrideScope.PERSISTENT, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		int grandchildIndex = model.getBone("grandchild").orElseThrow().getIndex();

		assertTrue(instance.effectivePose().isHidden(grandchildIndex),
				"CHILDREN_HIDDEN must still force-hide grandchild despite its own HIDDEN=false");
	}

	@Test
	void aColorOverrideInheritsToGrandchildrenButNotSiblings() {
		BakedStrutModel model = buildDeepModel();
		ModelInstance instance = readyInstance(model);

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.COLOR,
				new OverrideValue.ColorValue(0xFFFF0000), OverrideScope.PERSISTENT, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		int grandchildIndex = model.getBone("grandchild").orElseThrow().getIndex();
		int siblingIndex = model.getBone("sibling").orElseThrow().getIndex();
		BoneVisuals visuals = instance.boneVisuals();

		assertNotNull(visuals);
		assertEquals(0xFFFF0000, visuals.getColor(grandchildIndex), "COLOR must inherit down to a descendant with no override of its own");
		assertEquals(0xFFFFFFFF, visuals.getColor(siblingIndex), "inheritance must not leak onto an unrelated bone at the same depth");
	}

	@Test
	void boneTexturesIsNullWhenNoTextureOverrideIsActive() {
		ModelInstance instance = readyInstance(buildModel());

		instance.prepareForRender(this.entityId);

		assertNull(instance.boneTextures());
	}

	@Test
	void aTextureOverrideResolvesOnTheNamedBoneOnly() {
		BakedStrutModel model = buildModel();
		ModelInstance instance = readyInstance(model);
		Identifier texture = Identifier.of("strut", "textures/entity/override.png");

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.TEXTURE,
				new OverrideValue.TextureValue(texture), OverrideScope.PERSISTENT, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		int childIndex = model.getBone("child").orElseThrow().getIndex();
		int rootIndex = model.getBone("root").orElseThrow().getIndex();
		BoneTextureOverrides textures = instance.boneTextures();

		assertNotNull(textures, "an active TEXTURE override must produce a non-null BoneTextureOverrides");
		assertEquals(texture, textures.get(childIndex));
		assertNull(textures.get(rootIndex), "the override must not leak onto an unrelated bone");
	}

	@Test
	void aTextureOverrideInheritsToGrandchildrenButNotSiblings() {
		BakedStrutModel model = buildDeepModel();
		ModelInstance instance = readyInstance(model);
		Identifier texture = Identifier.of("strut", "textures/entity/override.png");

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.TEXTURE,
				new OverrideValue.TextureValue(texture), OverrideScope.PERSISTENT, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		int grandchildIndex = model.getBone("grandchild").orElseThrow().getIndex();
		int siblingIndex = model.getBone("sibling").orElseThrow().getIndex();
		BoneTextureOverrides textures = instance.boneTextures();

		assertNotNull(textures);
		assertEquals(texture, textures.get(grandchildIndex), "TEXTURE must inherit down to a descendant with no override of its own");
		assertNull(textures.get(siblingIndex), "inheritance must not leak onto an unrelated bone at the same depth");
	}

	@Test
	void boneLayersIsNullWhenNeitherTranslucentNorAnOverlayIsActive() {
		BakedStrutModel model = buildModel();
		ModelInstance instance = readyInstance(model);

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.TEXTURE,
				new OverrideValue.TextureValue(Identifier.of("strut", "textures/entity/override.png")), OverrideScope.PERSISTENT, 0, 1f, 0f);
		instance.prepareForRender(this.entityId);

		assertNull(instance.boneLayers(), "a TEXTURE override is not a render layer");
	}

	@Test
	void translucentResolvesOnTheBoneAndItsDescendantsButNotItsSiblings() {
		BakedStrutModel model = buildDeepModel();
		ModelInstance instance = readyInstance(model);

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.TRANSLUCENT,
				new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);
		instance.prepareForRender(this.entityId);

		BoneRenderLayers layers = instance.boneLayers();

		assertNotNull(layers);
		assertTrue(layers.isTranslucent(model.getBone("child").orElseThrow().getIndex()));
		assertTrue(layers.isTranslucent(model.getBone("grandchild").orElseThrow().getIndex()), "inherits down the tree");
		assertFalse(layers.isTranslucent(model.getBone("sibling").orElseThrow().getIndex()), "does not leak sideways");
		assertFalse(layers.isTranslucent(model.getBone("root").orElseThrow().getIndex()), "does not leak upwards");
	}

	@Test
	void aDescendantCanOptBackOutOfTranslucent() {
		BakedStrutModel model = buildDeepModel();
		ModelInstance instance = readyInstance(model);

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.TRANSLUCENT,
				new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);
		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("grandchild", OverrideProperty.TRANSLUCENT,
				new OverrideValue.BoolValue(false), OverrideScope.PERSISTENT, 0, 1f, 0f);
		instance.prepareForRender(this.entityId);

		BoneRenderLayers layers = instance.boneLayers();

		assertNotNull(layers);
		assertTrue(layers.isTranslucent(model.getBone("child").orElseThrow().getIndex()));
		assertFalse(layers.isTranslucent(model.getBone("grandchild").orElseThrow().getIndex()), "the closer override wins");
	}

	@Test
	void overlayLayersResolveIndependentlyPerLayerAndInherit() {
		BakedStrutModel model = buildDeepModel();
		ModelInstance instance = readyInstance(model);
		Identifier first = Identifier.of("strut", "textures/entity/overlay_a.png");
		Identifier third = Identifier.of("strut", "textures/entity/overlay_c.png");

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.OVERLAY_0,
				new OverrideValue.TextureValue(first), OverrideScope.PERSISTENT, 0, 1f, 0f);
		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.OVERLAY_2,
				new OverrideValue.TextureValue(third), OverrideScope.PERSISTENT, 0, 1f, 0f);
		instance.prepareForRender(this.entityId);

		int child = model.getBone("child").orElseThrow().getIndex();
		int grandchild = model.getBone("grandchild").orElseThrow().getIndex();
		int sibling = model.getBone("sibling").orElseThrow().getIndex();
		BoneRenderLayers layers = instance.boneLayers();

		assertNotNull(layers);
		assertEquals(first, layers.overlay(child, 0));
		assertNull(layers.overlay(child, 1), "a layer nobody set stays empty");
		assertEquals(third, layers.overlay(child, 2));
		assertEquals(first, layers.overlay(grandchild, 0), "overlays inherit");
		assertNull(layers.overlay(sibling, 0));
		assertFalse(layers.isTranslucent(child), "an overlay does not make the base bone translucent");
	}

	@Test
	void aLocalOverlayBeatsASyncedOneAtEqualPriorityAndSurvivesASnapshot() {
		BakedStrutModel model = buildModel();
		ModelInstance instance = readyInstance(model);
		Identifier synced = Identifier.of("strut", "textures/entity/from_server.png");
		Identifier local = Identifier.of("strut", "textures/entity/my_skin.png");
		var state = ClientBoneOverrideStates.forEntity(this.entityId);

		state.applySnapshot(Map.of(new OverrideKey("child", OverrideProperty.OVERLAY_0),
				new BoneOverride(new OverrideValue.TextureValue(synced), OverrideScope.PERSISTENT, 0, 1f, 0f, 1)));
		state.setLocal("child", OverrideProperty.OVERLAY_0, new OverrideValue.TextureValue(local), OverrideScope.PERSISTENT, 0, 1f, 0f);
		// The server's next full snapshot replaces the synced side only
		state.applySnapshot(Map.of());

		instance.prepareForRender(this.entityId);

		assertEquals(local, instance.boneLayers().overlay(model.getBone("child").orElseThrow().getIndex(), 0),
				"a per-viewer overlay must survive the server clearing its own");
	}

	@Test
	void settingTheSameLocalOverrideAgainKeepsTheSameEntryAndAChangedOneReplacesIt() {
		var state = ClientBoneOverrideStates.forEntity(this.entityId);
		Identifier texture = Identifier.of("strut", "textures/entity/my_skin.png");

		state.setLocal("child", OverrideProperty.TEXTURE, new OverrideValue.TextureValue(texture), OverrideScope.PERSISTENT, 0, 1f, 0f);

		var first = state.resolve("child", OverrideProperty.TEXTURE);

		state.setLocal("child", OverrideProperty.TEXTURE, new OverrideValue.TextureValue(texture), OverrideScope.PERSISTENT, 0, 1f, 0f);

		assertSame(first, state.resolve("child", OverrideProperty.TEXTURE), "an unchanged value must not replace the stored override");

		state.setLocal("child", OverrideProperty.TEXTURE, new OverrideValue.TextureValue(Identifier.of("strut", "textures/entity/other.png")),
				OverrideScope.PERSISTENT, 0, 1f, 0f);

		assertNotSame(first, state.resolve("child", OverrideProperty.TEXTURE), "a changed value must replace it");
	}

	@Test
	void aHiddenOverrideAloneDoesNotProduceBoneTexturesAtAll() {
		BakedStrutModel model = buildModel();
		ModelInstance instance = readyInstance(model);

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.HIDDEN,
				new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		assertNull(instance.boneTextures());
	}

	@Test
	void aFrameScopedOverrideNoLongerAppliesOnceCleared() {
		BakedStrutModel model = buildModel();
		ModelInstance instance = readyInstance(model);

		ClientBoneOverrideStates.forEntity(this.entityId).setLocal("child", OverrideProperty.HIDDEN,
				new OverrideValue.BoolValue(true), OverrideScope.FRAME, 0, 1f, 0f);

		instance.prepareForRender(this.entityId);

		int childIndex = model.getBone("child").orElseThrow().getIndex();

		assertTrue(instance.effectivePose().isHidden(childIndex));

		ClientBoneOverrideStates.get(this.entityId).clearFrameScopedLocal();
		instance.prepareForRender(this.entityId);

		assertFalse(instance.effectivePose().isHidden(childIndex), "cleared FRAME override must no longer apply");
	}
}
