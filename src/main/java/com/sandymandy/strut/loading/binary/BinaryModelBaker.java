package com.sandymandy.strut.loading.binary;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.StrutArmature;
import com.sandymandy.strut.cache.object.ArmatureBoneDefinition;
import com.sandymandy.strut.cache.object.StrutCube;
import com.sandymandy.strut.cache.object.StrutQuad;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutPoly;
import com.sandymandy.strut.cache.object.StrutSkin;
import com.sandymandy.strut.cache.object.StrutVertex;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawCube;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawCubeFace;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawElement;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawFace;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawArmature;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawBone;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawGroupBone;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawInfluence;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawMesh;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawTexture;
import com.sandymandy.strut.loading.json.raw.FaceUV;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import com.sandymandy.strut.loading.object.BakedModelFactory;
import com.sandymandy.strut.util.RenderUtil;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a parsed {@link RawBinaryModel} into a renderable {@link BakedStrutModel}
 * <p>
 * Everything the reader deliberately left alone happens here: indices become object
 * references, the model's authoring grid is normalised to Minecraft's, n-gons are
 * triangulated, and face normals are derived from the wound corners
 *
 * <h2>Why this bake does not mirror anything</h2>
 *
 * <p>The JSON path negates every X it touches, and this baker used to copy that, on the reasoning
 * that "Minecraft renders models mirrored relative to how they are authored". That reasoning was
 * wrong, and it mirrored every binary model along X for as long as the format existed. It went
 * unnoticed because a mirror is invisible on a symmetric model, and every model loaded in anger
 * until then - the skinning demo's limb, the male base character - was symmetric.
 *
 * <p>The two paths start from <b>different data</b>. Blockbench's own Bedrock exporter negates X
 * on the way out, so a {@code .geo.json} holds Bedrock-convention values; {@code BakedModelFactory}
 * then negates X on the way back in. The two cancel: <b>render space equals Blockbench's authoring
 * space</b>. A {@code .strutmodel} skips that round trip entirely - both writers ({@code
 * bbmodel_to_strutmodel.py} and the Blockbench plugin) read Blockbench's live values directly - so
 * the wire format already holds authoring-space values, and applying a mirror here adds one that
 * the JSON path never had.
 *
 * <p>Measured against {@code ava.geo.json} and the same rig exported to {@code .strutmodel}:
 * {@code armR}'s pivot is X = -3.94 in the Bedrock JSON and X = +4.81 in the binary, and its Z
 * rotation keeps its sign across both. Baking the JSON value the JSON path's way lands the arm at
 * +3.94; baking the binary value the mirrored way landed it at -4.81, on the wrong side of the
 * body.
 *
 * <p>So: <b>positions, pivots, rotations and winding all pass through unchanged here</b>, beyond
 * the units-per-block normalisation and the divide by 16. A face keeps its authored corner order,
 * because nothing flips its handedness any more.
 */
public final class BinaryModelBaker {
	private BinaryModelBaker() {}

	/**
	 * Bake a parsed model
	 *
	 * @param model The parsed model
	 * @param source The file's name or path, quoted in any error
	 */
	public static BakedStrutModel bake(RawBinaryModel model, String source) {
		/*
		 * Everything downstream assumes Minecraft's 16-units-per-block grid, so a model authored
		 * on another one is normalised here rather than at every point of use. A model exported
		 * from a 32-unit project renders at half size otherwise, with nothing saying why.
		 */
		float unitScale = 16f / model.unitsPerBlock();
		ModelProperties properties = propertiesOf(model);
		GroupBoneDefinition[] bones = new GroupBoneDefinition[model.groupBones().size()];
		List<GroupBoneDefinition> topLevel = new ObjectArrayList<>();

		/*
		 * A bone's parent must exist before the bone does, since GroupBoneDefinition takes its parent at
		 * construction. The exporter writes parents before children, but a file that does not is
		 * a file whose hierarchy cannot be built, so it is refused rather than silently reparented.
		 */
		for (int i = 0; i < bones.length; i++) {
			RawGroupBone raw = model.groupBones().get(i);
			GroupBoneDefinition parent = null;

			if (raw.parentIndex() >= 0) {
				if (raw.parentIndex() >= i)
					throw new BinaryFormatException("\"" + source + "\" has bone \"" + raw.name()
							+ "\" naming parent " + raw.parentIndex() + ", which is declared after it;"
							+ " a bone's parent must come before it in the file");

				parent = bones[raw.parentIndex()];
			}

			GroupBoneDefinition bone = new GroupBoneDefinition(parent, raw.name(), null, null, false, null);
			Vector3f pivot = new Vector3f(raw.pivot()).mul(unitScale);
			Vector3f rotation = raw.rotation();

			bone.bakeInitialRotation((float)Math.toRadians(rotation.x), (float)Math.toRadians(rotation.y),
					(float)Math.toRadians(rotation.z));
			bone.bakePivot(pivot.x, pivot.y, pivot.z);

			bones[i] = bone;

			if (parent == null) {
				topLevel.add(bone);
			}
			else {
				parent.getChildBones().add(bone);
			}
		}

		List<StrutArmature> armatures = new ObjectArrayList<>(model.armatures().size());

		for (RawArmature armature : model.armatures()) {
			armatures.add(bakeArmature(armature, model, unitScale, source));
		}

		for (int i = 0; i < bones.length; i++) {
			for (RawElement element : model.groupBones().get(i).elements()) {
				if (element.kind() == StrutBinaryFormats.ELEMENT_KIND_MESH)
					bones[i].getMeshes().add(bakeMesh(model.meshes().get(element.index()), model, armatures, unitScale));
				else if (element.kind() == StrutBinaryFormats.ELEMENT_KIND_CUBE)
					bones[i].getCubes().add(bakeCube(model.cubes().get(element.index()), model, unitScale));
			}
		}

		return new BakedStrutModel(topLevel, List.copyOf(armatures), properties, model.extensions());
	}

	// ===============================================================
	// Meshes
	// ===============================================================

	private static StrutMesh bakeMesh(RawMesh mesh, RawBinaryModel model, List<StrutArmature> armatures, float unitScale) {
		RawTexture texture = model.textures().get(mesh.textureIndex());
		float uvWidth = Math.max(1, texture.uvWidth());
		float uvHeight = Math.max(1, texture.uvHeight());
		Vector3f origin = new Vector3f(mesh.origin()).mul(unitScale);

		/*
		 * Vertices are baked into the owning bone's space, exactly as cubes are, so the render
		 * loop only ever has to apply the mesh's own rotation. Dividing by 16 here rather than at
		 * render time is what makes a mesh cost the same as a cube to draw.
		 */
		float[] positions = mesh.positions();
		Vector3f[] world = new Vector3f[mesh.vertexCount()];
		Matrix4f skinnedMeshToModel = mesh.isSkinned() ? skinnedMeshToModel(mesh, model, origin, unitScale) : null;

		for (int v = 0; v < world.length; v++) {
			if (skinnedMeshToModel != null) {
				world[v] = skinnedMeshToModel.transformPosition(new Vector3f(
						positions[v * 3] * unitScale / 16f,
						positions[v * 3 + 1] * unitScale / 16f,
						positions[v * 3 + 2] * unitScale / 16f));

				continue;
			}

			world[v] = new Vector3f(
					(positions[v * 3] * unitScale + origin.x) / 16f,
					(positions[v * 3 + 1] * unitScale + origin.y) / 16f,
					(positions[v * 3 + 2] * unitScale + origin.z) / 16f);
		}

		List<StrutPoly> polys = new ArrayList<>(mesh.faces().size());
		/*
		 * Triangulation duplicates a vertex into every face that shares it, so the baked corners
		 * alone cannot say which vertices are the same vertex. Skinning needs to know, or it
		 * transforms a shared vertex once per face it appears in and the seams pull apart.
		 */
		List<int[]> sources = new ArrayList<>(mesh.faces().size());

		for (RawFace face : mesh.faces()) {
			bakeFace(face, world, uvWidth, uvHeight, polys, sources);
		}

		StrutPoly[] baked = polys.toArray(new StrutPoly[0]);

		if (mesh.smooth())
			applySmoothNormals(baked, sources);

		StrutSkin skin = mesh.isSkinned()
				? bakeSkin(mesh, armatures.get(mesh.armatureIndex()), sources)
				: null;
		Vector3f[] restNormals = mesh.smooth() && skin != null ? restNormalsOf(baked, sources, skin.vertexCount()) : null;

		// A skinned mesh's own origin and rotation are already baked into its vertices (see
		// skinnedMeshToModel), so it carries none left for the renderer to apply after skinning
		if (skinnedMeshToModel != null)
			return new StrutMesh(baked, Vec3d.ZERO, Vec3d.ZERO, false, skin, world, restNormals);

		return new StrutMesh(baked,
				new Vec3d(origin.x, origin.y, origin.z),
				new Vec3d(Math.toRadians(mesh.rotation().x), Math.toRadians(mesh.rotation().y),
						Math.toRadians(mesh.rotation().z)),
				false, skin, skin != null ? world : null, restNormals);
	}

	/**
	 * Where a skinned mesh's own vertices sit in model space, reproducing how Blockbench places a
	 * mesh parented to an Armature (real 5.1.6 source):
	 * <ul>
	 *     <li>An Armature is added to its parent group's scene object with an identity transform, so
	 *     "armature space" is that group's own frame, anchored at the group's pivot.</li>
	 *     <li>A mesh's origin is only absolute when its parent has {@code use_absolute_position}
	 *     (Group and Cube do, Armature does not), so a mesh under an Armature has an origin
	 *     relative to that frame, not the model.</li>
	 *     <li>{@code Armature#calculateVertexDeformation} skins vertices after the mesh's own
	 *     origin and rotation have already been applied (its bind matrix includes the mesh's
	 *     {@code matrixWorld}).</li>
	 * </ul>
	 * Treating the origin as absolute dropped the armature group's pivot and put the mesh at the
	 * model's feet. Applying the rotation after skinning instead of before made bone motion come
	 * out mirrored for any mesh with its own rotation (a 180° turn negates X). Baking the whole chain
	 * into the rest positions here fixes both. The armature's own bones are put in the same frame
	 * by {@link #bakeArmature}. The group's own rotation is still left to that group's palette entry
	 * at render time, exactly as for its cubes.
	 */
	private static Matrix4f skinnedMeshToModel(RawMesh mesh, RawBinaryModel model, Vector3f origin, float unitScale) {
		Vector3f frame = armatureFrameOrigin(model.armatures().get(mesh.armatureIndex()), model, unitScale);

		return new Matrix4f()
				.translation((frame.x + origin.x) / 16f, (frame.y + origin.y) / 16f, (frame.z + origin.z) / 16f)
				.rotateZYX((float)Math.toRadians(mesh.rotation().z), (float)Math.toRadians(mesh.rotation().y),
						(float)Math.toRadians(mesh.rotation().x));
	}

	/**
	 * The origin of an armature's own frame in model units: its parent group's pivot, since
	 * Blockbench attaches an Armature to that group with an identity transform. A root armature's
	 * frame is the model origin
	 */
	private static Vector3f armatureFrameOrigin(RawArmature armature, RawBinaryModel model, float unitScale) {
		if (armature.parentGroupBone() < 0)
			return new Vector3f();

		return new Vector3f(model.groupBones().get(armature.parentGroupBone()).pivot()).mul(unitScale);
	}

	/**
	 * Pull each unique source vertex's smoothed rest normal back out of the baked, per-corner
	 * vertices, indexed the same way {@link #bakeSkin} indexes rest positions
	 * <p>
	 * A skinned smooth mesh needs this at render time: its normals are deformed the same way its
	 * positions are, by blending the same bones' matrices, rather than recomputed per frame from
	 * the deformed corners the way a flat mesh's are
	 */
	private static Vector3f[] restNormalsOf(StrutPoly[] polys, List<int[]> sources, int vertexCount) {
		Vector3f[] restNormals = new Vector3f[vertexCount];

		for (int p = 0; p < polys.length; p++) {
			StrutVertex[] vertices = polys[p].vertices();
			int[] src = sources.get(p);

			for (int corner = 0; corner < vertices.length; corner++) {
				restNormals[src[corner]] = vertices[corner].normal();
			}
		}

		return restNormals;
	}

	/**
	 * Bake one face into one or more quads
	 * <p>
	 * A quad passes through as-is, a triangle becomes a quad with its last corner repeated, and
	 * anything larger is fanned into triangles first. Corners keep their authored order - nothing
	 * flips the handedness of a face here any more (see this class's own javadoc)
	 */
	private static void bakeFace(RawFace face, Vector3f[] world, float uvWidth, float uvHeight,
								 List<StrutPoly> polys, List<int[]> sources) {
		int corners = face.cornerCount();

		if (corners == 4) {
			polys.add(quad(face, world, uvWidth, uvHeight, 0, 1, 2, 3));
			sources.add(sourcesOf(face, 0, 1, 2, 3));
		}
		else if (corners == 3) {
			polys.add(quad(face, world, uvWidth, uvHeight, 0, 1, 2, 2));
			sources.add(sourcesOf(face, 0, 1, 2, 2));
		}
		else {
			/*
			 * A fan is only correct for a convex face. Blockbench cannot author a concave n-gon,
			 * and anything above four corners is vanishingly rare in exported geometry, so the
			 * cost of a general triangulation is not worth paying for a case that does not arise.
			 */
			for (int i = 1; i < corners - 1; i++) {
				polys.add(quad(face, world, uvWidth, uvHeight, 0, i, i + 1, i + 1));
				sources.add(sourcesOf(face, 0, i, i + 1, i + 1));
			}
		}
	}

	private static StrutPoly quad(RawFace face, Vector3f[] world, float uvWidth, float uvHeight,
								int a, int b, int c, int d) {
		StrutVertex[] vertices = new StrutVertex[] {
				vertex(face, world, uvWidth, uvHeight, a),
				vertex(face, world, uvWidth, uvHeight, b),
				vertex(face, world, uvWidth, uvHeight, c),
				vertex(face, world, uvWidth, uvHeight, d)};
		Vector3f normal = normalOf(vertices);

		/*
		 * Every vertex starts out with its own face's flat normal, whether or not the mesh
		 * ends up smooth-shaded - a smooth mesh's normals are replaced in a second pass once
		 * every face is baked and each source vertex's faces are all known, but a flat mesh
		 * needs its final answer right here.
		 */
		for (int i = 0; i < vertices.length; i++) {
			vertices[i] = vertices[i].withNormal(normal);
		}

		return new StrutPoly(vertices, normal);
	}

	private static int[] sourcesOf(RawFace face, int a, int b, int c, int d) {
		return new int[] {face.vertices()[a], face.vertices()[b], face.vertices()[c], face.vertices()[d]};
	}

	private static StrutVertex vertex(RawFace face, Vector3f[] world, float uvWidth, float uvHeight, int corner) {
		return new StrutVertex(world[face.vertices()[corner]],
				face.uvs()[corner * 2] / uvWidth,
				face.uvs()[corner * 2 + 1] / uvHeight,
				new Vector3f());
	}

	/**
	 * Replace every vertex's flat per-face normal with the average of the face normals of every
	 * poly sharing its source vertex, so adjacent faces blend at the seam instead of each
	 * showing its own facet
	 * <p>
	 * A degenerate triangle (baked as a quad with its last corner repeated, see {@link #bakeFace})
	 * must not let its repeated corner count that face's normal twice - {@code sources} is
	 * consulted per unique source vertex, not per corner, to avoid exactly that
	 */
	private static void applySmoothNormals(StrutPoly[] polys, List<int[]> sources) {
		Map<Integer, Vector3f> sums = new HashMap<>();

		for (int p = 0; p < polys.length; p++) {
			Vector3f faceNormal = polys[p].normal();
			int[] src = sources.get(p);
			IntSet seen = new IntOpenHashSet(src.length);

			for (int corner : src) {
				if (seen.add(corner))
					sums.computeIfAbsent(corner, k -> new Vector3f()).add(faceNormal);
			}
		}

		for (int p = 0; p < polys.length; p++) {
			StrutVertex[] vertices = polys[p].vertices();
			int[] src = sources.get(p);

			for (int corner = 0; corner < vertices.length; corner++) {
				Vector3f averaged = new Vector3f(sums.get(src[corner])).normalize();

				vertices[corner] = vertices[corner].withNormal(averaged);
			}
		}
	}

	/**
	 * The outward normal of a wound face
	 * <p>
	 * Delegates to {@link RenderUtil#newellNormal}, which skinned rendering also uses, so a
	 * deformed face and its rest pose can never disagree about which way it points
	 */
	private static Vector3f normalOf(StrutVertex[] vertices) {
		return RenderUtil.newellNormal(vertices[0].position(), vertices[1].position(),
				vertices[2].position(), vertices[3].position(), new Vector3f());
	}

	// ===============================================================
	// Cubes
	// ===============================================================

	/**
	 * Bedrock's own face-id numbering, matching {@code CUBE_FACE_IDS} in
	 * {@code bbmodel_to_strutmodel.py} and the plugin's own {@code strutCodec.ts}
	 */
	private static final Direction[] CUBE_FACE_DIRECTIONS =
			{Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.UP, Direction.DOWN};

	/**
	 * Bake a cube, reusing {@link BakedModelFactory.Builtin}'s own vertex-corner geometry
	 * ({@link BakedModelFactory.VertexSet}) rather than re-deriving box-corner winding a second,
	 * independently fallible way
	 * <p>
	 * Unlike the JSON `.geo.json` path this factory exists for, a binary cube's faces already
	 * carry an explicit UV rectangle each ({@link RawCubeFace#u1()}..{@link RawCubeFace#v2()}) -
	 * the exporter resolves Bedrock box UV into per-face rectangles before the wire format ever
	 * sees them (see `strutCodec.ts`'s own note on why `boxUV` is round-trip metadata only, not
	 * something a reader has to reconstruct) - so there is no separate box-UV code path to carry
	 * over here, only the ordinary explicit-rectangle one
	 * <p>
	 * Positions, pivot and rotation all pass through unmirrored, like every other element this
	 * class bakes - see its own top javadoc for why the JSON factory's {@code (-x, -y, z)} does
	 * not apply to a binary model's authoring-space values
	 * <p>
	 * Every face the file carries is baked, including one whose UV rectangle has zero size. Blockbench and GeckoLib both draw such a face in
	 * the single texel colour at its UV corner, and models rely on it: a consumer mod fills the gaps between its many small cubes with
	 * faces like this (on the imp, 584 of them sample an opaque texel). Dropping them, as this baker once did, opened exactly those gaps
	 * as dotted slits on every model. A face its author removed never reaches the file: the exporter skips a face whose texture is null
	 */
	private static StrutCube bakeCube(RawCube cube, RawBinaryModel model, float unitScale) {
		RawTexture texture = model.textures().get(cube.textureIndex());
		float texWidth = Math.max(1, texture.uvWidth());
		float texHeight = Math.max(1, texture.uvHeight());

		float fromX = cube.from().x * unitScale;
		float toX = cube.to().x * unitScale;
		float fromY = cube.from().y * unitScale;
		float toY = cube.to().y * unitScale;
		float fromZ = cube.from().z * unitScale;
		float toZ = cube.to().z * unitScale;

		/*
		 * Blockbench's own "from" is normally the smaller corner, but nothing in the format
		 * guarantees it, so the box's min/max are resolved rather than assumed
		 */
		float minX = Math.min(fromX, toX) / 16f;
		float maxX = Math.max(fromX, toX) / 16f;
		float minY = Math.min(fromY, toY) / 16f;
		float maxY = Math.max(fromY, toY) / 16f;
		float minZ = Math.min(fromZ, toZ) / 16f;
		float maxZ = Math.max(fromZ, toZ) / 16f;

		double inflate = cube.inflate() * unitScale / 16d;
		Vec3d origin = new Vec3d(minX, minY, minZ);
		Vec3d vertexSize = new Vec3d(maxX - minX, maxY - minY, maxZ - minZ);
		BakedModelFactory.VertexSet vertices = new BakedModelFactory.VertexSet(origin, vertexSize, inflate);
		List<StrutQuad> quads = new ObjectArrayList<>(cube.faces().size());

		for (RawCubeFace face : cube.faces())
			quads.add(bakeCubeFace(face, vertices, cube.mirror(), texWidth, texHeight));

		Vector3f pivot = new Vector3f(cube.origin()).mul(unitScale);
		Vector3f rotation = cube.rotation();

		return new StrutCube(quads.toArray(new StrutQuad[0]),
				new Vec3d(pivot.x, pivot.y, pivot.z),
				new Vec3d(Math.toRadians(rotation.x), Math.toRadians(rotation.y), Math.toRadians(rotation.z)),
				new Vec3d(Math.abs(toX - fromX), Math.abs(toY - fromY), Math.abs(toZ - fromZ)),
				inflate, cube.mirror());
	}

	/**
	 * One face, exactly where the cube puts it, as GeckoLib bakes it. A flat cube's faces are not moved off their plane: a lift against
	 * z-fighting was tried here and removed, because it opened hairline cracks between the many flat cubes a model with
	 * many small parts is built from
	 */
	private static StrutQuad bakeCubeFace(RawCubeFace face, BakedModelFactory.VertexSet vertices, boolean mirror, float texWidth, float texHeight) {
		Direction direction = CUBE_FACE_DIRECTIONS[face.faceId()];
		StrutVertex[] corners = vertices.verticesForQuad(direction, false, mirror);
		FaceUV.Rotation uvRotation = FaceUV.Rotation.values()[face.uvRotation()];

		return StrutQuad.build(corners, face.u1(), face.v1(), face.u2() - face.u1(), face.v2() - face.v1(),
				uvRotation, texWidth, texHeight, mirror, direction);
	}

	// ===============================================================
	// Armatures
	// ===============================================================

	/**
	 * Bake an armature, precomputing each bone's rest transform and its inverse
	 * <p>
	 * A bone's head and rotation are local to its parent, matching Blockbench
	 * ({@code ArmatureBone}'s preview controller sets {@code bone.position} from {@code origin} and
	 * adds it to its parent's scene object). A root bone's parent is the armature, whose frame is
	 * anchored at its parent group's pivot (see {@link #armatureFrameOrigin}). So a bone's rest
	 * transform relative to its parent is simply its own head and rotation, and its model-space
	 * bind transform accumulates down the chain from the armature's frame. This puts the bones in
	 * the same model space {@link #skinnedMeshToModel} bakes the mesh into
	 */
	private static StrutArmature bakeArmature(RawArmature armature, RawBinaryModel model, float unitScale,
											String source) {
		List<ArmatureBoneDefinition> bones = new ObjectArrayList<>(armature.bones().size());
		Matrix4f[] bindModel = new Matrix4f[armature.bones().size()];
		Vector3f frame = armatureFrameOrigin(armature, model, unitScale);

		for (int i = 0; i < armature.bones().size(); i++) {
			RawBone raw = armature.bones().get(i);
			ArmatureBoneDefinition parent = null;

			if (raw.parentIndex() >= 0) {
				if (raw.parentIndex() >= i)
					throw new BinaryFormatException("\"" + source + "\" has armature \"" + armature.name()
							+ "\" bone \"" + raw.name() + "\" naming parent " + raw.parentIndex()
							+ ", which is declared after it; a bone's parent must come before it");

				parent = bones.get(raw.parentIndex());
			}

			Vector3f head = new Vector3f(raw.head()).mul(unitScale);
			Matrix4f local = new Matrix4f()
					.translation(head.x / 16f, head.y / 16f, head.z / 16f)
					.rotateZ((float)Math.toRadians(raw.rotation().z))
					.rotateY((float)Math.toRadians(raw.rotation().y))
					.rotateX((float)Math.toRadians(raw.rotation().x));
			Matrix4f bindLocal = parent == null
					? new Matrix4f().translation(frame.x / 16f, frame.y / 16f, frame.z / 16f).mul(local)
					: local;
			Matrix4f bind = parent == null
					? new Matrix4f(bindLocal)
					: new Matrix4f(bindModel[parent.getIndex()]).mul(bindLocal);

			bindModel[i] = bind;

			bones.add(new ArmatureBoneDefinition(parent, raw.name(), bindLocal, new Matrix4f(bind).invert(),
					raw.length() * unitScale / 16f, i));
		}

		String parentBone = armature.parentGroupBone() >= 0
				? model.groupBones().get(armature.parentGroupBone()).name()
				: null;

		return new StrutArmature(armature.name(), List.copyOf(bones), parentBone);
	}

	/**
	 * Bake a mesh's per-vertex influences into the fixed-width form the skinning loop reads
	 * <p>
	 * Padded to four per vertex with zero weights rather than stored ragged, so the blend is an
	 * unrolled loop over a flat array instead of a walk of nested lists. Weights are copied as
	 * authored: the exporter caps, sorts and normalises them, and redoing that here would hide an
	 * exporter that had stopped
	 */
	private static StrutSkin bakeSkin(RawMesh mesh, StrutArmature armature, List<int[]> sources) {
		int vertexCount = mesh.vertexCount();
		int[] boneIndices = new int[vertexCount * StrutSkin.MAX_INFLUENCES];
		float[] weights = new float[vertexCount * StrutSkin.MAX_INFLUENCES];

		for (int vertex = 0; vertex < vertexCount; vertex++) {
			List<RawInfluence> influences = mesh.skin().get(vertex);
			int base = vertex * StrutSkin.MAX_INFLUENCES;

			for (int i = 0; i < influences.size() && i < StrutSkin.MAX_INFLUENCES; i++) {
				RawInfluence influence = influences.get(i);

				if (influence.boneIndex() >= armature.bones().size())
					throw new BinaryFormatException("mesh \"" + mesh.name() + "\" weights a vertex to bone "
							+ influence.boneIndex() + " of armature \"" + armature.name() + "\", which has "
							+ armature.bones().size() + " bone(s)");

				boneIndices[base + i] = influence.boneIndex();
				weights[base + i] = influence.weight();
			}
		}

		int[] polyCorners = new int[sources.size() * 4];

		for (int poly = 0; poly < sources.size(); poly++) {
			System.arraycopy(sources.get(poly), 0, polyCorners, poly * 4, 4);
		}

		return new StrutSkin(armature, boneIndices, weights, polyCorners);
	}

	// ===============================================================
	// Properties
	// ===============================================================

	/**
	 * Synthesise the {@link ModelProperties} the JSON path carries in its header
	 * <p>
	 * A binary model has no such block. What downstream actually reads from it is the texture
	 * size, which here is the UV grid of the model's first texture - the grid its face
	 * coordinates were measured against, which is not necessarily the texture's pixel size
	 */
	private static ModelProperties propertiesOf(RawBinaryModel model) {
		double uvWidth = 16;
		double uvHeight = 16;

		if (!model.textures().isEmpty()) {
			uvWidth = model.textures().getFirst().uvWidth();
			uvHeight = model.textures().getFirst().uvHeight();
		}

		return new ModelProperties(null, null, null, null, null, null, null, null, null, null,
				model.modelId(), null, uvHeight, uvWidth, null, new double[] {0, 0, 0}, null);
	}
}
