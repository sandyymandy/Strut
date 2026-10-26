package com.sandymandy.strut.animation.attachment;

import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.loading.binary.BinaryFixtures;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Binding of a worn model to its wearer by bone name: matched bones are posed as the wearer's are, the rest rest, and no common bone means no draw */
class ModelAttachmentTest {
	private static BakedStrutModel bake(byte[] bytes) {
		return BinaryModelBaker.bake(BinaryModelReader.read(bytes, "m.strutmodel"), "m.strutmodel");
	}

	private static ModelAttachment attachment() {
		return new ModelAttachment("slot", Identifier.of("strut", "worn"), Identifier.of("strut", "worn"));
	}

	@Test
	void aMatchedBoneIsPosedAsTheWearersIs() {
		BakedStrutModel wearer = bake(BinaryFixtures.riggedModel());
		BakedStrutModel worn = bake(BinaryFixtures.meshModel());
		Pose wearerPose = new Pose(wearer.flatBones().length);
		int root = indexOf(wearer, "root");

		wearerPose.setRotation(root, 0.5f, 0.25f, 0.125f);
		wearerPose.setPosition(root, 1f, 2f, 3f);
		wearerPose.setScale(root, 2f, 2f, 2f);

		ModelAttachment attachment = attachment();

		assertTrue(attachment.prepare(worn, wearer, wearerPose), "the two models share a bone named root");

		int wornRoot = indexOf(worn, "root");

		assertEquals(0.5f, attachment.pose().getRotX(wornRoot));
		assertEquals(0.125f, attachment.pose().getRotZ(wornRoot));
		assertEquals(3f, attachment.pose().getPosZ(wornRoot));
		assertEquals(2f, attachment.pose().getScaleY(wornRoot));
	}

	private static BakedStrutModel file(String path) throws Exception {
		return bake(Files.readAllBytes(Path.of("src/main/resources/assets/strut/strut/models/" + path + ".strutmodel")));
	}

	@Test
	void everyPieceOfTheDevArmourFollowsABoneOfTheDevWearer() throws Exception {
		BakedStrutModel wearer = file("entity/test_dummy");

		for (String slot : new String[] {"head", "chest", "legs", "feet"}) {
			BakedStrutModel worn = file("armour/test_" + slot);

			// a bone with geometry may have no match (the armour bones are the piece's own), but something above it has: it follows the wearer through that
			for (var bone : worn.flatBones()) {
				if (bone.getCubes().isEmpty() && bone.getMeshes().isEmpty())
					continue;

				boolean followsSomething = false;

				for (var at = bone; at != null; at = at.getParent())
					followsSomething |= wearer.getBone(at.getName()).isPresent();

				assertTrue(followsSomething, slot + " model: " + bone.getName() + " has nothing above it that the wearer has");
			}

			assertTrue(attachment().prepare(worn, wearer, new Pose(wearer.flatBones().length)), slot + " model is drawn on the dummy");
		}
	}

	@Test
	void aPieceHidesWithWhatIsAboveIt() throws Exception {
		BakedStrutModel wearer = file("entity/test_dummy");
		BakedStrutModel worn = file("armour/test_head");
		Pose wearerPose = new Pose(wearer.flatBones().length);
		ModelAttachment helmet = attachment();
		int pieceBone = indexOf(worn, "armorHelmet");

		assertTrue(helmet.prepare(worn, wearer, wearerPose));
		assertFalse(helmet.pose().isHidden(pieceBone));

		// the head hidden (first person) takes the piece, a bone of its own with no match, with it
		wearerPose.setHidden(indexOf(wearer, "head"), true);
		assertTrue(helmet.prepare(worn, wearer, wearerPose));
		assertTrue(helmet.pose().isHidden(pieceBone), "a helmet goes with the head");

		wearerPose.setHidden(indexOf(wearer, "head"), false);
		assertTrue(helmet.prepare(worn, wearer, wearerPose));
		assertFalse(helmet.pose().isHidden(pieceBone));
	}

	@Test
	void aBoneWithGeometryDoesNotTakeTheWearersOwnHiddenFlag() throws Exception {
		// the wearer hides its own bone of the piece's name (as a model with armour built in does): the worn piece is not hidden with it
		BakedStrutModel wearer = file("armour/test_head");
		BakedStrutModel worn = file("armour/test_head");
		Pose wearerPose = new Pose(wearer.flatBones().length);
		ModelAttachment helmet = attachment();

		wearerPose.setHidden(indexOf(wearer, "armorHelmet"), true);
		assertTrue(helmet.prepare(worn, wearer, wearerPose));
		assertFalse(helmet.pose().isHidden(indexOf(worn, "armorHelmet")));
	}

	@Test
	void aModelWithNoBoneInCommonIsNotDrawn() {
		BakedStrutModel wearer = bake(BinaryFixtures.cubeModel());
		BakedStrutModel worn = bake(BinaryFixtures.meshModel());

		assertFalse(attachment().prepare(worn, wearer, new Pose(wearer.flatBones().length)), "the cube model's bone is body, the other's is root");
	}

	@Test
	void aRequiredBoneTheWearerLacksMeansNothingIsDrawn() {
		BakedStrutModel wearer = bake(BinaryFixtures.riggedModel());
		BakedStrutModel worn = bake(BinaryFixtures.meshModel());
		Pose wearerPose = new Pose(wearer.flatBones().length);

		assertFalse(attachment().requireBone("cape").prepare(worn, wearer, wearerPose), "a cape is only drawn on a wearer with a cape bone");
		assertTrue(attachment().requireBone("root").prepare(worn, wearer, wearerPose));
	}

	@Test
	void aBoneTheAttachmentHidesStaysHidden() {
		BakedStrutModel wearer = bake(BinaryFixtures.riggedModel());
		BakedStrutModel worn = bake(BinaryFixtures.meshModel());
		ModelAttachment attachment = attachment().hideBone("root", true);

		assertTrue(attachment.prepare(worn, wearer, new Pose(wearer.flatBones().length)));
		assertTrue(attachment.pose().isHidden(indexOf(worn, "root")));
	}

	private static int indexOf(BakedStrutModel model, String name) {
		for (int i = 0; i < model.flatBones().length; i++)
			if (model.flatBones()[i].getName().equals(name))
				return i;

		throw new AssertionError("no bone " + name);
	}

	@Test
	void aBoneMapLetsAModelWithOtherNamesFollowTheWearer() {
		BakedStrutModel wearer = bake(BinaryFixtures.riggedModel());
		BakedStrutModel worn = bake(BinaryFixtures.cubeModel());
		Pose wearerPose = new Pose(wearer.flatBones().length);

		wearerPose.setRotation(indexOf(wearer, "root"), 0.5f, 0f, 0f);

		assertFalse(attachment().prepare(worn, wearer, wearerPose), "the cube model's bone is called body");

		ModelAttachment mapped = attachment().boneMap(Map.of("body", "root"));

		assertTrue(mapped.prepare(worn, wearer, wearerPose));
		assertEquals(0.5f, mapped.pose().getRotX(indexOf(worn, "body")));
	}

	@Test
	void aFitMovesTurnsAndScalesTheBoneFromTheWearersPose() {
		BakedStrutModel wearer = bake(BinaryFixtures.riggedModel());
		BakedStrutModel worn = bake(BinaryFixtures.meshModel());
		Pose wearerPose = new Pose(wearer.flatBones().length);
		int root = indexOf(wearer, "root");

		wearerPose.setPosition(root, 1f, 0f, 0f);
		wearerPose.setScale(root, 2f, 2f, 2f);

		ModelAttachment fitted = attachment().fit(Map.of("root", new BoneFit(new float[] {0f, 4f, 0f}, new float[] {0f, 90f, 0f}, new float[] {0.5f, 1f, 1f})));

		assertTrue(fitted.prepare(worn, wearer, wearerPose));

		int wornRoot = indexOf(worn, "root");

		assertEquals(1f, fitted.pose().getPosX(wornRoot));
		assertEquals(4f, fitted.pose().getPosY(wornRoot), "moved up from the wearer's pose");
		assertEquals((float) Math.toRadians(90), fitted.pose().getRotY(wornRoot), 1e-6f);
		assertEquals(1f, fitted.pose().getScaleX(wornRoot), "2 times 0.5");
		assertEquals(2f, fitted.pose().getScaleY(wornRoot));
	}
}
