package com.sandymandy.strut.renderer.gpu;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.MeshWeld;
import com.sandymandy.strut.cache.object.StrutArmature;
import com.sandymandy.strut.cache.object.StrutCube;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutPoly;
import com.sandymandy.strut.cache.object.StrutQuad;
import com.sandymandy.strut.cache.object.StrutSkin;
import com.sandymandy.strut.cache.object.StrutVertex;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.util.RenderUtil;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;

/**
 * One {@link BakedStrutModel}'s worth of GPU-resident, bone-LOCAL geometry - a VAO/VBO/EBO baked
 * once and reused by every instance and every frame that draws this model, however many there are
 * <p>
 * Instances are looked up through {@link GpuModelCache}
 * <p>
 * Deliberately holds only what is shared across every instance of one model: geometry and bone
 * count. The bone palette that poses it is per-draw, per-instance state - {@link GpuBatchQueue}
 * owns that, uploading it fresh every frame to its own SSBO
 */
public final class GpuModel implements AutoCloseable {
	/** Vertex layout: 3 floats position, 2 floats UV, 3 floats normal, 1 int bone index - 36 bytes */
	private static final int STRIDE_BYTES = 9 * Integer.BYTES;

	/**
	 * Second stream's layout:
	 * 4 unsigned shorts (joint indices, global - already offset into the per-instance concatenated
	 * skin buffer, see {@link SkinnedMeshRecord#armatureBase()}), 4 floats (weights), 1 int
	 * (SkinSlot) - 8 + 16 + 4 = 28 bytes. A separate VBO from the base stream rather than a wider
	 * interleaved one, so a rigid-only model's own vertex format and its existing, live-verified GL
	 * upload stay byte-for-byte unchanged - this stream is only ever created, bound or paid for when
	 * {@link #hasSkinning()} is true
	 */
	private static final int SKIN_STRIDE_BYTES = 4 * Short.BYTES + 4 * Float.BYTES + Integer.BYTES;

	/**
	 * One {@link SkinnedMeshRecord} entry's std430 layout: a {@code mat4} (64 bytes, already
	 * 16-byte aligned) plus one {@code int} (the smooth/flat flag) explicitly padded out to a full
	 * 16-byte slot - relying on a compiler's own implicit tail padding for a trailing scalar in an
	 * array-of-structs SSBO has a real history of vendor disagreement, so this pads by hand the same
	 * way {@link GpuRenderBackend}'s own {@code InstanceData}/{@code BoneVisual} structs already do
	 */
	private static final int SKINNED_MESH_RECORD_STRIDE_BYTES = 16 * Float.BYTES + 4 * Integer.BYTES;

	private final int vao;
	private final int vbo;
	private final int ebo;
	private final int skinVbo;
	private final int skinnedMeshSsbo;
	private final int deformVbo;
	private final int deformMeshSsbo;
	private final StrutMesh[] meshes;
	private final int indexCount;
	private final int boneCount;
	private final int totalSkinBoneCount;
	private final int[] boneIndexStart;
	private final SkinnedMeshRecord[] skinnedMeshes;

	private GpuModel(int vao, int vbo, int ebo, int skinVbo, int skinnedMeshSsbo, int deformVbo, int deformMeshSsbo, StrutMesh[] meshes, int indexCount, int boneCount,
					  int totalSkinBoneCount, int[] boneIndexStart, SkinnedMeshRecord[] skinnedMeshes) {
		this.deformVbo = deformVbo;
		this.deformMeshSsbo = deformMeshSsbo;
		this.meshes = meshes;
		this.vao = vao;
		this.vbo = vbo;
		this.ebo = ebo;
		this.skinVbo = skinVbo;
		this.skinnedMeshSsbo = skinnedMeshSsbo;
		this.indexCount = indexCount;
		this.boneCount = boneCount;
		this.totalSkinBoneCount = totalSkinBoneCount;
		this.boneIndexStart = boneIndexStart;
		this.skinnedMeshes = skinnedMeshes;
	}

	public int vao() {
		return vao;
	}

	/**
	 * This model's own interleaved geometry buffer (position/UV/normal/bone index, see
	 * {@link #STRIDE_BYTES}) - bound as an ordinary {@code GL_ARRAY_BUFFER} for the VAO's own vertex
	 * attributes by {@link #bake}, and reused unchanged as a {@code GL_SHADER_STORAGE_BUFFER} input
	 * by the compute pre-pass ({@code IrisPrePass})
	 * - a GL buffer object is not tied to the target it was first bound to, so the same bytes are
	 * readable both ways with no copy
	 */
	public int vbo() {
		return vbo;
	}

	/**
	 * This model's own index buffer - already bound to {@link #vao()}, exposed directly for
	 * {@code IrisMainDraw}, which draws through a
	 * VAO of its own (the packed pre-pass output, not this model's rigid vertex buffer) but still
	 * reuses this same index buffer for its {@code glMultiDrawElementsBaseVertex} calls
	 */
	public int ebo() {
		return ebo;
	}

	/**
	 * This model's second, skin-only stream (joints/weights/SkinSlot, see {@link #SKIN_STRIDE_BYTES})
	 * - {@code 0} exactly when {@link #hasSkinning()} is {@code false}, the same shape every other
	 * skin-only field on this class already has. Read the same dual-purpose way {@link #vbo()} is
	 */
	public int skinVbo() {
		return skinVbo;
	}

	public int indexCount() {
		return indexCount;
	}

	/**
	 * Every mesh of the model (skinned or not), in the order the deform stream numbers them: what a mesh's deformation (cloth, flesh) is looked up by.
	 * Empty for a model with only cubes
	 */
	public StrutMesh[] meshes() {
		return this.meshes;
	}

	/** Whether the model has any mesh, which is to say whether it can be deformed per vertex (see {@link MeshDeformPack}) */
	public boolean hasMeshes() {
		return this.meshes.length > 0;
	}

	/**
	 * The second per-vertex stream, {@code (mesh, corner, particle)} as three ints: which of {@link #meshes()} a vertex belongs to ({@code -1} for a cube's), which
	 * corner of it, as {@code polygon * 4 + corner}, the index a value given for each corner is found by, and which welded particle it is (see {@link MeshWeld}), the
	 * index a value given for each particle is found by. {@code 0} for a model with no mesh. Read the same dual-purpose way
	 * {@link #vbo()} is
	 */
	public int deformVbo() {
		return this.deformVbo;
	}

	/** The static table of each mesh's own element matrix (its pivot rotation), one {@code mat4} each, in {@link #meshes()} order; {@code 0} with no mesh */
	int deformMeshSsbo() {
		return this.deformMeshSsbo;
	}

	public int boneCount() {
		return boneCount;
	}

	/**
	 * Whether this model carries at least one skinned mesh, so a draw needs the skin matrices and the skinned-mesh table
	 */
	public boolean hasSkinning() {
		return this.skinnedMeshes.length > 0;
	}

	/**
	 * This model's skinned meshes' own static records, in {@code SkinSlot} order - each vertex's
	 * baked {@code SkinSlot} attribute indexes directly into this array
	 */
	public SkinnedMeshRecord[] skinnedMeshes() {
		return this.skinnedMeshes;
	}

	/**
	 * This model's own static skinned-mesh-record SSBO - one {@link SkinnedMeshRecord} per
	 * {@code SkinSlot}, uploaded once at bake time and reused by every instance and every frame,
	 * exactly like the VAO/VBO/EBO already are. Never rebuilt or re-uploaded per frame, unlike the
	 * per-instance skin-matrix buffer {@link GpuBatchQueue} builds fresh every group - this data
	 * never changes once baked
	 */
	int skinnedMeshSsbo() {
		return this.skinnedMeshSsbo;
	}

	/**
	 * The total bone count across every armature this model carries, concatenated in
	 * {@link BakedStrutModel#armatures()} order - the length each instance's own per-frame skin-matrix
	 * upload must be, and what {@code gl_InstanceID * totalSkinBoneCount} indexes into in the shader
	 */
	public int totalSkinBoneCount() {
		return this.totalSkinBoneCount;
	}

	/**
	 * A skinned mesh's own static, per-model (not per-instance) data - added alongside
	 * the ordinary bone palette every model already had
	 *
	 * @param elementMatrix       The mesh's own pivot rotation ({@link RenderUtil#applyPivotRotation}
	 *                            on {@link StrutMesh#pivot()}/{@link StrutMesh#rotation()}), applied
	 *                            <em>after</em> the skin blend rather than baked into the vertex the
	 *                            way a rigid mesh's is - the skin blend needs the vertex in its true
	 *                            rest frame, per {@code StrutRenderer#renderSkinnedMesh}'s own order
	 * @param elementNormalMatrix The same pivot rotation's effect on normals - a pure rotation, so no
	 *                            inverse-transpose correction is needed here (see
	 *                            {@link RenderUtil#applyPivotRotation}'s own doc comment)
	 * @param armatureBase        Where this mesh's armature's bones start within the per-instance
	 *                            concatenated skin-matrix buffer (every armature in
	 *                            {@link BakedStrutModel#armatures()} order) - baked into every one of
	 *                            this mesh's vertices' joint indices already, so the shader never
	 *                            needs to look this up itself
	 * @param smooth              Whether this mesh blends its rest normals through the skin (a smooth
	 *                            mesh) or recomputes a per-face normal from the deformed corners
	 *                            instead (a flat one) - carried here
	 *                            since it is static, per-mesh data like the rest of
	 *                            this record
	 */
	public record SkinnedMeshRecord(Matrix4f elementMatrix, Matrix3f elementNormalMatrix, int armatureBase, boolean smooth) {}

	/**
	 * Bone {@code boneIndex}'s own starting offset into this model's index buffer, in indices (not
	 * bytes) - for a per-bone
	 * {@code TEXTURE} override's extra {@code glDrawElementsInstanced} call over just that bone's own
	 * range. Valid because {@link #bake} appends every bone's faces in one contiguous run before
	 * moving to the next bone (the outer loop over {@code boneDefinitions} in flat, parent-before-child
	 * order) - a bone's own indices are never interleaved with any other bone's
	 */
	public int boneIndexStart(int boneIndex) {
		return this.boneIndexStart[boneIndex];
	}

	/**
	 * Bone {@code boneIndex}'s own index count - see {@link #boneIndexStart(int)}. Zero for a bone
	 * with no geometry of its own (a pure pivot), which is a perfectly ordinary case, not an error -
	 * a caller drawing this range asks for zero indices and correctly draws nothing
	 */
	public int boneIndexCount(int boneIndex) {
		return this.boneIndexStart[boneIndex + 1] - this.boneIndexStart[boneIndex];
	}

	/**
	 * Everything {@link #accumulate} produces - pure CPU-side data, no GL calls - so {@link #bake}
	 * has something to upload and a unit test has something to assert against without needing a live
	 * GL context. {@code skinSlots}/{@code jointIndices}/{@code weights} are parallel to
	 * {@code vertexBoneIndices} (one entry, or four for the latter two, per baked vertex);
	 * {@code skinSlots[v] == }{@link #NO_SKIN} for an ordinary rigid vertex
	 */
	record Accumulated(FloatArrayList vertexData, IntArrayList vertexBoneIndices, IntArrayList indices,
						int[] boneIndexStart, IntArrayList skinSlots, IntArrayList jointIndices,
						FloatArrayList weights, SkinnedMeshRecord[] skinnedMeshes, IntArrayList deformInfo, List<StrutMesh> meshes, List<Matrix4f> meshElements) {}

	/** {@code SkinSlot} for an ordinary rigid vertex - not bound to any skinned mesh */
	static final int NO_SKIN = -1;

	/**
	 * Flattens {@code model}'s cubes and meshes into one vertex/index buffer, in bone-LOCAL space -
	 * each vertex carries the pivot/rotation transform of the cube or mesh that owns it (static,
	 * baked once) plus its owning bone's flat index, but <em>not</em> that bone's own transform. The
	 * bone transform is applied live in the vertex shader via that index, against whatever palette
	 * the caller uploads for its own instances that frame - the actual shape
	 * the design describes: static geometry baked once, a small per-instance
	 * palette uploaded every frame
	 * <p>
	 * A skinned mesh's own vertices are baked as true, untransformed rest positions/normals -
	 * <em>not</em> pre-multiplied by the mesh's own {@code elementMatrix} the way a rigid mesh's are
	 * - because the skin blend needs to operate on the
	 * true rest frame, exactly like {@code StrutRenderer#renderSkinnedMesh} feeds
	 * {@code mesh.restPositions()} straight into {@code StrutSkin#skin} before applying the mesh's own
	 * pivot rotation afterward. Each such vertex's {@code SkinSlot} instead indexes a
	 * {@link SkinnedMeshRecord} carrying that same {@code elementMatrix}, applied by the shader after
	 * the blend
	 * <p>
	 * Reuses {@link RenderUtil#applyPivotRotation} (seeded from identity, not a bone's palette
	 * entry) rather than a third reimplementation of cube/mesh transform maths - already proven
	 * correct by its own unit test, so this bake inherits that correctness instead of risking a
	 * fresh transcription error
	 */
	static Accumulated accumulate(BakedStrutModel model) {
		GroupBoneDefinition[] boneDefinitions = model.flatBones();
		FloatArrayList vertexData = new FloatArrayList();
		IntArrayList vertexBoneIndices = new IntArrayList();
		IntArrayList indices = new IntArrayList();
		IntArrayList skinSlots = new IntArrayList();
		IntArrayList jointIndices = new IntArrayList();
		FloatArrayList weights = new FloatArrayList();
		List<SkinnedMeshRecord> skinnedMeshes = new ObjectArrayList<>();
		// per baked vertex, (mesh index, polygon * 4 + corner, welded particle), the mesh index -1 for a cube's: where a mesh's per-vertex offsets (cloth, flesh) are looked up
		IntArrayList deformInfo = new IntArrayList();
		List<StrutMesh> meshList = new ObjectArrayList<>();
		List<Matrix4f> meshElements = new ObjectArrayList<>();
		Matrix4f elementMatrix = new Matrix4f();
		Matrix3f elementNormalMatrix = new Matrix3f();
		Vector4f transformedPosition = new Vector4f();
		int nextIndex = 0;
		int[] boneIndexStart = new int[boneDefinitions.length + 1];
		int[] armatureBoneOffsets = armatureBoneOffsets(model.armatures());

		for (int i = 0; i < boneDefinitions.length; i++) {
			GroupBoneDefinition bone = boneDefinitions[i];

			boneIndexStart[i] = indices.size();

			// shouldNeverRender() is NOT a permanent bake-time exclusion here, deliberately - the
			// CPU path only ever treats it as this bone's *initial* Pose hidden state
			// (ModelInstance#rebindIfNeeded seeds pose.setHidden from it, nothing more), freely
			// toggleable afterward through the ordinary hidden mechanism (an entity bone override,
			// among others). Permanently excluding this bone's geometry from the GPU model would
			// make it un-toggleable there specifically - a real, live-found divergence from the CPU
			// path: test_dummy's own armorChestPlate (shouldNeverRender=true, meant to switch on with
			// equipped armor) could never be shown on the GPU path no matter what set its override,
			// since there was no geometry there to show in the first place. Baking it like any other
			// bone and letting the same Pose#isHidden mechanism gate it - the same one every other
			// bone already goes through - is what keeps the two paths in agreement
			for (StrutCube cube : bone.getCubes()) {
				elementMatrix.identity();
				elementNormalMatrix.identity();
				RenderUtil.applyPivotRotation(elementMatrix, elementNormalMatrix, cube.pivot(), cube.rotation());

				for (StrutQuad quad : cube.quads()) {
					if (quad == null)
						continue;

					// A cube's four corners share one face normal, matching how the CPU path's
					// renderCube computes it once per quad rather than reading each corner's own -
					// including the same fixInvertedFlatCube correction a flat-axis cube (a plane,
					// a card) needs or its shading is wrong the same way it was before that fix
					// landed on the CPU path. StrutVertex is immutable, so the corrected normal is
					// baked onto a copy of each corner rather than mutated in place
					Vector3f faceNormal = new Vector3f(quad.normal());

					RenderUtil.fixInvertedFlatCube(cube, faceNormal);

					// A flat cube (a plane, a card) has two faces on one plane, one for each side, and which one is on top flips with the angle. The shader
					// lights a vertex marked by a normal twice as long as a unit one by whichever way of its normal faces the eye, so both faces are lit alike
					if (cube.size().x == 0 || cube.size().y == 0 || cube.size().z == 0)
						faceNormal.mul(FLAT_FACE_NORMAL_LENGTH);

					StrutVertex[] corners = quad.vertices();
					StrutVertex[] withFaceNormal = new StrutVertex[corners.length];

					for (int corner = 0; corner < corners.length; corner++) {
						withFaceNormal[corner] = corners[corner].withNormal(faceNormal);
					}

					nextIndex = appendFace(vertexData, vertexBoneIndices, indices, elementMatrix, elementNormalMatrix,
							transformedPosition, withFaceNormal, i, nextIndex);
					appendNoSkin(skinSlots, jointIndices, weights, withFaceNormal.length);
					appendNoDeform(deformInfo, withFaceNormal.length);
				}
			}

			for (StrutMesh mesh : bone.getMeshes()) {
				int meshId = meshList.size();

				meshList.add(mesh);

				if (mesh.isSkinned()) {
					StrutSkin skin = mesh.skin();
					int armatureBase = armatureBoneOffsets[armatureIndexOf(model.armatures(), skin.getArmature())];
					int skinSlot = skinnedMeshes.size();

					elementMatrix.identity();
					elementNormalMatrix.identity();
					RenderUtil.applyPivotRotation(elementMatrix, elementNormalMatrix, mesh.pivot(), mesh.rotation());
					skinnedMeshes.add(new SkinnedMeshRecord(new Matrix4f(elementMatrix), new Matrix3f(elementNormalMatrix),
							armatureBase, mesh.restNormals() != null));

					meshElements.add(new Matrix4f(elementMatrix));

					int[] skinnedParticles = MeshWeld.of(mesh).cornerParticle();

					for (int p = 0; p < mesh.polys().length; p++) {
						nextIndex = appendSkinnedFace(vertexData, vertexBoneIndices, indices, skinSlots, jointIndices,
								weights, mesh, skin, p, armatureBase, skinSlot, i, nextIndex);
						appendMeshCorners(deformInfo, meshId, p, skinnedParticles);
					}

					continue;
				}

				elementMatrix.identity();
				elementNormalMatrix.identity();
				RenderUtil.applyPivotRotation(elementMatrix, elementNormalMatrix, mesh.pivot(), mesh.rotation());
				meshElements.add(new Matrix4f(elementMatrix));

				int polyIndex = 0;
				int[] particles = MeshWeld.of(mesh).cornerParticle();

				for (StrutPoly poly : mesh.polys()) {
					// Unlike a cube's shared face normal, each of a mesh's corners already carries
					// its own correctly-averaged normal (smooth-shading support, baked at model-bake
					// time) - used as-is here, matching the CPU path's createVerticesOfPoly
					nextIndex = appendFace(vertexData, vertexBoneIndices, indices, elementMatrix, elementNormalMatrix,
							transformedPosition, poly.vertices(), i, nextIndex);
					appendNoSkin(skinSlots, jointIndices, weights, poly.vertices().length);
					appendMeshCorners(deformInfo, meshId, polyIndex++, particles);
				}
			}
		}

		boneIndexStart[boneDefinitions.length] = indices.size();

		return new Accumulated(vertexData, vertexBoneIndices, indices, boneIndexStart, skinSlots, jointIndices,
				weights, skinnedMeshes.toArray(new SkinnedMeshRecord[0]), deformInfo, meshList, meshElements);
	}

	/**
	 * Where each of {@code armatures}' bones starts within a per-instance concatenated skin buffer
	 * holding every armature's matrices back to back, in {@link BakedStrutModel#armatures()} order -
	 * the same order {@code SkinningState}/{@code GpuBatchQueue} will need to
	 * upload them in
	 */
	private static int[] armatureBoneOffsets(List<StrutArmature> armatures) {
		int[] offsets = new int[armatures.size()];
		int running = 0;

		for (int i = 0; i < armatures.size(); i++) {
			offsets[i] = running;
			running += armatures.get(i).boneCount();
		}

		return offsets;
	}

	/**
	 * {@code armatures}' index of {@code target}, by reference identity rather than
	 * {@link StrutArmature}'s own generated {@code equals} - {@code StrutArmature} is a record, and two
	 * structurally-identical-but-distinct armatures should never collide just because a hand-built
	 * fixture happens to describe them the same way
	 */
	private static int armatureIndexOf(List<StrutArmature> armatures, StrutArmature target) {
		for (int i = 0; i < armatures.size(); i++) {
			if (armatures.get(i) == target)
				return i;
		}

		throw new IllegalStateException("skinned mesh references an armature not present in this model's own armatures() list");
	}

	/**
	 * Appends one skinned mesh poly's four vertices as true, untransformed rest data - see
	 * {@link #accumulate}'s own doc comment for why a skinned vertex is baked differently from a
	 * rigid one. Every corner's own joint indices are looked up via {@link StrutSkin#sourceVertexOf}
	 * (triangulation duplicates a source vertex across every face sharing it, exactly as
	 * {@link StrutSkin#skin} itself accounts for) and offset by {@code armatureBase} so the shader can
	 * index the per-instance concatenated skin buffer directly, with no per-vertex armature lookup
	 * of its own
	 */
	private static int appendSkinnedFace(FloatArrayList vertexData, IntArrayList vertexBoneIndices, IntArrayList indices,
										  IntArrayList skinSlots, IntArrayList jointIndices, FloatArrayList weights,
										  StrutMesh mesh, StrutSkin skin, int poly, int armatureBase, int skinSlot,
										  int boneIndex, int nextIndex) {
		StrutVertex[] corners = mesh.polys()[poly].vertices();

		for (int corner = 0; corner < corners.length; corner++) {
			StrutVertex vertex = corners[corner];
			int sourceVertex = skin.sourceVertexOf(poly, corner);
			Vector3f restPosition = mesh.restPositions()[sourceVertex];
			Vector3f restNormal = mesh.restNormals() != null ? mesh.restNormals()[sourceVertex] : vertex.normal();

			vertexData.add(restPosition.x());
			vertexData.add(restPosition.y());
			vertexData.add(restPosition.z());
			vertexData.add(vertex.texU());
			vertexData.add(vertex.texV());
			vertexData.add(restNormal.x());
			vertexData.add(restNormal.y());
			vertexData.add(restNormal.z());
			vertexBoneIndices.add(boneIndex);

			skinSlots.add(skinSlot);

			for (int influence = 0; influence < StrutSkin.MAX_INFLUENCES; influence++) {
				jointIndices.add(armatureBase + skin.boneIndex(sourceVertex, influence));
				weights.add(skin.weight(sourceVertex, influence));
			}
		}

		indices.add(nextIndex);
		indices.add(nextIndex + 1);
		indices.add(nextIndex + 2);
		indices.add(nextIndex);
		indices.add(nextIndex + 2);
		indices.add(nextIndex + 3);

		return nextIndex + 4;
	}

	/** The length given to the normal of a face of a flat cube: the shaders read a normal longer than this as "light me from the side the eye is on" */
	static final float FLAT_FACE_NORMAL_LENGTH = 2f;

	/** The ints the deform stream has for each vertex: the mesh, the corner (polygon * 4 + corner) and the welded particle (see {@link MeshWeld}) */
	static final int DEFORM_STRIDE = 3;

	/** A cube's vertices belong to no mesh: {@code (-1, 0, 0)} each, in the deform stream */
	private static void appendNoDeform(IntArrayList deformInfo, int vertexCount) {
		for (int v = 0; v < vertexCount; v++) {
			deformInfo.add(-1);
			deformInfo.add(0);
			deformInfo.add(0);
		}
	}

	/**
	 * One mesh polygon's four corners in the deform stream: the mesh, {@code polygon * 4 + corner} (the index a value given for each corner is found by) and the
	 * particle the corner is welded into (the index a value given for each particle is found by)
	 */
	private static void appendMeshCorners(IntArrayList deformInfo, int meshId, int polygon, int[] cornerParticle) {
		for (int corner = 0; corner < 4; corner++) {
			deformInfo.add(meshId);
			deformInfo.add(polygon * 4 + corner);
			deformInfo.add(cornerParticle[polygon * 4 + corner]);
		}
	}

	/**
	 * Pads an ordinary rigid vertex's slice of the second stream with {@link #NO_SKIN} and zero
	 * influences, so {@code skinSlots}/{@code jointIndices}/{@code weights} stay exactly as long
	 * (per-vertex, and per-vertex times four respectively) as {@code vertexBoneIndices} regardless of
	 * whether this model turns out to need the second stream at all - simpler than tracking two
	 * different vertex counts through the rest of the bake
	 */
	private static void appendNoSkin(IntArrayList skinSlots, IntArrayList jointIndices, FloatArrayList weights, int vertexCount) {
		for (int v = 0; v < vertexCount; v++) {
			skinSlots.add(NO_SKIN);

			for (int influence = 0; influence < StrutSkin.MAX_INFLUENCES; influence++) {
				jointIndices.add(0);
				weights.add(0f);
			}
		}
	}

	/**
	 * Uploads what {@link #accumulate} computed as this model's real GL objects
	 */
	public static GpuModel bake(BakedStrutModel model) {
		Accumulated baked = accumulate(model);
		FloatArrayList vertexData = baked.vertexData();
		IntArrayList vertexBoneIndices = baked.vertexBoneIndices();
		IntArrayList indices = baked.indices();
		int[] boneIndexStart = baked.boneIndexStart();
		int boneCount = model.flatBones().length;

		int vertexCount = vertexBoneIndices.size();
		int vao = GL30.glGenVertexArrays();
		// Baked lazily, in the middle of a vanilla entity render: whatever vertex array is bound now is vanilla's and must be bound again after
		int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

		GL30.glBindVertexArray(vao);

		int vbo = GL15.glGenBuffers();

		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

		// MemoryStack (what GpuShaderProgram#uploadMatrix uses for its own tiny per-frame 16-float
		// uniform upload) is deliberately not used here - it draws from a small fixed-size
		// thread-local buffer (tens of KB), and a dense model's vertex/index data can run past a
		// megabyte. Pushing that onto MemoryStack overflowed it - live-verified the hard way, as an
		// out-of-memory crash, not a clean exception. BufferUtils allocates an ordinary off-heap
		// direct buffer sized for exactly what's being uploaded, safe to let the GC reclaim once
		// glBufferData has copied it to the GPU
		//
		// A plain ByteBuffer, not FloatBuffer, since one vertex now mixes floats (position, UV,
		// normal) and an int (bone index) in one interleaved stride - put the raw bytes directly
		// rather than bit-casting the int through a float slot
		ByteBuffer vertexBuffer = BufferUtils.createByteBuffer(vertexCount * STRIDE_BYTES);

		for (int v = 0; v < vertexCount; v++) {
			int floatBase = v * 8;

			vertexBuffer.putFloat(vertexData.getFloat(floatBase));
			vertexBuffer.putFloat(vertexData.getFloat(floatBase + 1));
			vertexBuffer.putFloat(vertexData.getFloat(floatBase + 2));
			vertexBuffer.putFloat(vertexData.getFloat(floatBase + 3));
			vertexBuffer.putFloat(vertexData.getFloat(floatBase + 4));
			vertexBuffer.putFloat(vertexData.getFloat(floatBase + 5));
			vertexBuffer.putFloat(vertexData.getFloat(floatBase + 6));
			vertexBuffer.putFloat(vertexData.getFloat(floatBase + 7));
			vertexBuffer.putInt(vertexBoneIndices.getInt(v));
		}

		vertexBuffer.flip();
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STATIC_DRAW);

		GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE_BYTES, 0L);
		GL20.glEnableVertexAttribArray(0);
		GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, STRIDE_BYTES, 3L * Float.BYTES);
		GL20.glEnableVertexAttribArray(1);
		GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, STRIDE_BYTES, 5L * Float.BYTES);
		GL20.glEnableVertexAttribArray(2);
		GL30.glVertexAttribIPointer(3, 1, GL11.GL_INT, STRIDE_BYTES, 8L * Float.BYTES);
		GL20.glEnableVertexAttribArray(3);

		int ebo = GL15.glGenBuffers();

		GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);

		IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.size());

		indexBuffer.put(indices.elements(), 0, indices.size()).flip();
		GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL15.GL_STATIC_DRAW);

		boolean hasSkinning = baked.skinnedMeshes().length > 0;
		int skinVbo = hasSkinning
				? uploadSkinStream(vertexCount, baked.skinSlots(), baked.jointIndices(), baked.weights())
				: 0;
		int skinnedMeshSsbo = hasSkinning ? uploadSkinnedMeshSsbo(baked.skinnedMeshes()) : 0;
		boolean hasMeshes = !baked.meshes().isEmpty();
		int deformVbo = hasMeshes ? uploadDeformStream(baked.deformInfo()) : 0;
		int deformMeshSsbo = hasMeshes ? uploadDeformMeshes(baked.meshElements()) : 0;
		int totalSkinBoneCount = 0;

		for (StrutArmature armature : model.armatures()) {
			totalSkinBoneCount += armature.boneCount();
		}

		// Restore vanilla's vertex array first, and leave GL_ELEMENT_ARRAY_BUFFER alone: that binding is vertex array state, so clearing it now
		// would wipe the index buffer of the vertex array just restored. (With 0 restored, as it used to be, it was harmless.)
		GL30.glBindVertexArray(previousVao);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

		return new GpuModel(vao, vbo, ebo, skinVbo, skinnedMeshSsbo, deformVbo, deformMeshSsbo, baked.meshes().toArray(new StrutMesh[0]), indices.size(), boneCount, totalSkinBoneCount,
				boneIndexStart, baked.skinnedMeshes());
	}

	/**
	 * Uploads the deform stream (three ints for each vertex, see {@link #deformVbo()}) and binds it to attribute location 7. Only for a model with a mesh; the
	 * currently bound vertex array (the caller's) is what the attribute attaches to
	 */
	private static int uploadDeformStream(IntArrayList deformInfo) {
		int vbo = GL15.glGenBuffers();

		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

		IntBuffer buffer = BufferUtils.createIntBuffer(deformInfo.size());

		buffer.put(deformInfo.elements(), 0, deformInfo.size()).flip();
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);
		GL30.glVertexAttribIPointer(7, DEFORM_STRIDE, GL11.GL_INT, DEFORM_STRIDE * Integer.BYTES, 0L);
		GL20.glEnableVertexAttribArray(7);

		return vbo;
	}

	/** Uploads each mesh's element matrix as {@code mat4}s, once, in mesh order */
	private static int uploadDeformMeshes(List<Matrix4f> meshElements) {
		int ssbo = GL15.glGenBuffers();

		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo);

		ByteBuffer buffer = BufferUtils.createByteBuffer(meshElements.size() * 16 * Float.BYTES);
		float[] elements = new float[16];

		for (Matrix4f matrix : meshElements) {
			matrix.get(elements);

			for (float element : elements)
				buffer.putFloat(element);
		}

		buffer.flip();
		GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, buffer, GL15.GL_STATIC_DRAW);
		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

		return ssbo;
	}

	/**
	 * Uploads this model's static skinned-mesh-record table as its own persistent SSBO, once, at
	 * bake time - {@code std430}-laid-out as {@link #SKINNED_MESH_RECORD_STRIDE_BYTES} per entry, in
	 * {@code SkinSlot} order, matching the {@code SkinnedMeshData} struct
	 * {@link GpuRenderBackend}'s shader declares
	 */
	private static int uploadSkinnedMeshSsbo(SkinnedMeshRecord[] skinnedMeshes) {
		int ssbo = GL15.glGenBuffers();

		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo);

		ByteBuffer buffer = BufferUtils.createByteBuffer(skinnedMeshes.length * SKINNED_MESH_RECORD_STRIDE_BYTES);
		float[] matrixElements = new float[16];

		for (SkinnedMeshRecord record : skinnedMeshes) {
			record.elementMatrix().get(matrixElements);

			for (float element : matrixElements) {
				buffer.putFloat(element);
			}

			buffer.putInt(record.smooth() ? 1 : 0);
			buffer.putInt(0);
			buffer.putInt(0);
			buffer.putInt(0);
		}

		buffer.flip();
		GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, buffer, GL15.GL_STATIC_DRAW);
		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

		return ssbo;
	}

	/**
	 * Uploads the second stream (see {@link #SKIN_STRIDE_BYTES}'s own doc comment) and binds it to
	 * attribute locations 4 (Joints), 5 (Weights) and 6 (SkinSlot) - only ever called for a model
	 * that actually has at least one skinned mesh, so a rigid-only model never creates this buffer
	 * at all. The currently-bound VAO (the caller's) is what these attribute bindings attach to
	 */
	private static int uploadSkinStream(int vertexCount, IntArrayList skinSlots, IntArrayList jointIndices, FloatArrayList weights) {
		int skinVbo = GL15.glGenBuffers();

		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, skinVbo);

		ByteBuffer skinBuffer = BufferUtils.createByteBuffer(vertexCount * SKIN_STRIDE_BYTES);

		for (int v = 0; v < vertexCount; v++) {
			int influenceBase = v * StrutSkin.MAX_INFLUENCES;

			for (int influence = 0; influence < StrutSkin.MAX_INFLUENCES; influence++) {
				skinBuffer.putShort((short)jointIndices.getInt(influenceBase + influence));
			}

			for (int influence = 0; influence < StrutSkin.MAX_INFLUENCES; influence++) {
				skinBuffer.putFloat(weights.getFloat(influenceBase + influence));
			}

			skinBuffer.putInt(skinSlots.getInt(v));
		}

		skinBuffer.flip();
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, skinBuffer, GL15.GL_STATIC_DRAW);

		GL30.glVertexAttribIPointer(4, 4, GL11.GL_UNSIGNED_SHORT, SKIN_STRIDE_BYTES, 0L);
		GL20.glEnableVertexAttribArray(4);
		GL20.glVertexAttribPointer(5, 4, GL11.GL_FLOAT, false, SKIN_STRIDE_BYTES, 4L * Short.BYTES);
		GL20.glEnableVertexAttribArray(5);
		GL30.glVertexAttribIPointer(6, 1, GL11.GL_INT, SKIN_STRIDE_BYTES, 4L * Short.BYTES + 4L * Float.BYTES);
		GL20.glEnableVertexAttribArray(6);

		return skinVbo;
	}

	/**
	 * Appends one quad's (or triangulated poly's) four vertices to {@code vertexData}/
	 * {@code vertexBoneIndices} and their two triangles' worth of indices to {@code indices},
	 * returning the next free vertex index
	 * <p>
	 * Every face baked by this class is exactly four {@link StrutVertex}s - a triangle already has
	 * its last corner repeated at bake time, the same convention {@code createVerticesOfQuad}/
	 * {@code createVerticesOfPoly} rely on - so cubes and meshes share this one method
	 * <p>
	 * The normal is transformed by {@code elementNormalMatrix} only - the cube or mesh's own local
	 * rotation, exactly mirroring {@code elementMatrix}'s treatment of position. The owning bone's
	 * rotation (which can include non-uniform scale from an animation channel) is applied live in
	 * the vertex shader instead, via the same {@code BoneIndex} lookup the position uses
	 */
	private static int appendFace(FloatArrayList vertexData, IntArrayList vertexBoneIndices, IntArrayList indices,
								   Matrix4f elementMatrix, Matrix3f elementNormalMatrix, Vector4f transformedPosition,
								   StrutVertex[] vertices, int boneIndex, int nextIndex) {
		Vector3f transformedNormal = new Vector3f();

		for (StrutVertex vertex : vertices) {
			Vector3f position = vertex.position();

			elementMatrix.transform(position.x(), position.y(), position.z(), 1.0f, transformedPosition);
			elementNormalMatrix.transform(vertex.normal(), transformedNormal);
			vertexData.add(transformedPosition.x());
			vertexData.add(transformedPosition.y());
			vertexData.add(transformedPosition.z());
			vertexData.add(vertex.texU());
			vertexData.add(vertex.texV());
			vertexData.add(transformedNormal.x());
			vertexData.add(transformedNormal.y());
			vertexData.add(transformedNormal.z());
			vertexBoneIndices.add(boneIndex);
		}

		indices.add(nextIndex);
		indices.add(nextIndex + 1);
		indices.add(nextIndex + 2);
		indices.add(nextIndex);
		indices.add(nextIndex + 2);
		indices.add(nextIndex + 3);

		return nextIndex + 4;
	}

	/**
	 * Deletes this model's GL objects. Called by {@link GpuModelCache} once its {@code BakedStrutModel}
	 * key has fallen out of {@code StrutCache.MODELS} on a resource reload - identity keying makes
	 * the stale entry unreachable naturally, but the GL objects it owns leak unless freed explicitly
	 */
	@Override
	public void close() {
		GL15.glDeleteBuffers(vbo);
		GL15.glDeleteBuffers(ebo);

		if (this.skinVbo != 0)
			GL15.glDeleteBuffers(this.skinVbo);

		if (this.skinnedMeshSsbo != 0)
			GL15.glDeleteBuffers(this.skinnedMeshSsbo);

		if (this.deformVbo != 0)
			GL15.glDeleteBuffers(this.deformVbo);

		if (this.deformMeshSsbo != 0)
			GL15.glDeleteBuffers(this.deformMeshSsbo);

		GL30.glDeleteVertexArrays(vao);
	}
}
