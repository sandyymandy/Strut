package com.sandymandy.strut.renderer.iris;

import com.sandymandy.strut.renderer.gpu.GpuModel;
import com.sandymandy.strut.renderer.gpu.GpuShaderProgram;
import com.sandymandy.strut.renderer.gpu.MeshDeformState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/**
 * The GPU compute pre-pass behind the Iris render path
 * Two compute stages per chunk of a group's instances:
 * <ol>
 *     <li><b>Pose</b> - reads what {@code GpuBatchQueue} already uploaded for the group (bone palette,
 *     per-instance data, per-bone visuals, skin matrices, the static skinned-mesh table) plus the
 *     model's own baked geometry, and writes one fully-posed {@code PrePassVertex} per (instance,
 *     corner) into {@link #OUTPUT_SSBO_BINDING}: the {@code groupBonePalette * E_mesh * skin(rest)}
 *     formula {@code GpuSkinningParityTest} pins, evaluated once per vertex in a compute invocation
 *     instead of inline in a raster vertex shader, with each quad's {@code mc_midTexCoord} and
 *     tangent computed the way Iris's own buffer builder does</li>
 *     <li><b>Pack</b> - repacks that into {@code IrisVertexFormats.ENTITY}'s byte layout so
 *     {@link IrisMainDraw} can draw it through the pack's own, unmodified program</li>
 * </ol>
 * Per-bone {@code COLOR} and {@code UV_OFFSET} overrides are applied here, in the one place, so every
 * pack sees them; {@code TEXTURE} overrides are a second draw of that bone's index range in
 * {@link IrisMainDraw}
 */
public final class IrisPrePass {
	/** Must match {@code GpuRenderBackend.PALETTE_SSBO_BINDING} exactly - the same buffer, already bound */
	private static final int PALETTE_SSBO_BINDING = 0;

	/** Must match {@code GpuRenderBackend.INSTANCE_SSBO_BINDING} exactly - the same buffer, already bound */
	private static final int INSTANCE_SSBO_BINDING = 1;

	/** Must match {@code GpuRenderBackend.VISUAL_SSBO_BINDING} exactly - per-bone COLOR / UV_OFFSET overrides */
	private static final int VISUAL_SSBO_BINDING = 2;

	/** Must match {@code GpuRenderBackend.SKIN_SSBO_BINDING} exactly - the same buffer, already bound */
	private static final int SKIN_SSBO_BINDING = 3;

	/** Must match {@code GpuRenderBackend.SKINNED_MESH_SSBO_BINDING} exactly - the same buffer, already bound */
	private static final int SKINNED_MESH_SSBO_BINDING = 4;

	/** {@link GpuModel#vbo()}, reinterpreted as an SSBO of raw {@code uint} words - see that method's own doc comment */
	private static final int VERTEX_SSBO_BINDING = 5;

	/** {@link GpuModel#skinVbo()}, reinterpreted the same way - only bound when the model has skinning */
	private static final int SKIN_STREAM_SSBO_BINDING = 6;

	/** The model's deform stream, the group's packed deformation and the model's mesh element matrices (cloth and flesh) */
	private static final int DEFORM_STREAM_SSBO_BINDING = 9;
	private static final int DEFORM_SSBO_BINDING = 10;
	private static final int DEFORM_MESH_SSBO_BINDING = 11;

	/** This pass's own output - one {@code PrePassVertex} per (instance, corner), read by the pack stage */
	static final int OUTPUT_SSBO_BINDING = 7;

	/** One base-geometry vertex's own stride in this buffer, in 4-byte words - see {@code GpuModel.STRIDE_BYTES} */
	private static final int VERTEX_STRIDE_WORDS = 9;

	/** One skin-stream vertex's own stride in this buffer, in 4-byte words - see {@code GpuModel.SKIN_STRIDE_BYTES} */
	private static final int SKIN_STREAM_STRIDE_WORDS = 7;

	/** One output {@code PrePassVertex}'s own std430 size - 5 vec4-sized fields, see the compute shader's own struct */
	private static final int OUTPUT_VERTEX_STRIDE_BYTES = 5 * 16;

	private static final int LOCAL_SIZE = 64;

	private static final String COMPUTE_SOURCE = """
			#version 430 core

			layout(local_size_x = 64) in;

			layout(std430, binding = 0) readonly buffer BonePaletteBuffer {
			    mat4 BonePalette[];
			};

			struct InstanceData {
			    int packedLight;
			    int packedOverlay;
			    vec4 colorModulator;
			    int deformInstance;
			    // the rest of the instance data (GpuRenderBackend reads it); here only so the struct is as long as the one that is written
			    float lights[12];
			    int lightColour[4];
			    float custom[4];
			};

			layout(std430, binding = 1) readonly buffer InstanceDataBuffer {
			    InstanceData Instances[];
			};

			struct BoneVisual {
			    vec4 color;
			    vec4 uvOffsetScale;
			    int flags;
			};

			layout(std430, binding = 2) readonly buffer BoneVisualBuffer {
			    BoneVisual BoneVisuals[];
			};

			layout(std430, binding = 3) readonly buffer SkinMatrixBuffer {
			    mat4 SkinMatrices[];
			};

			struct SkinnedMeshData {
			    mat4 elementMatrix;
			    int smoothShaded;
			    int _pad0;
			    int _pad1;
			    int _pad2;
			};

			layout(std430, binding = 4) readonly buffer SkinnedMeshBuffer {
			    SkinnedMeshData SkinnedMeshes[];
			};

			// The model's own static geometry, bound read-only as raw words rather than a typed struct -
			// GpuModel's interleaved vertex buffer is tightly packed (9 x 4-byte fields, no padding), which
			// std430's own struct alignment rules (vec3 aligned to 16 bytes) would not reproduce if declared
			// as a struct here instead
			layout(std430, binding = 5) readonly buffer VertexBuffer {
			    uint VertexWords[];
			};

			layout(std430, binding = 6) readonly buffer SkinStreamBuffer {
			    uint SkinWords[];
			};

			// Cloth and flesh (MeshDeformPack, GpuRenderBackend's vertex shader has the same): the model's (mesh, corner) pair for each vertex, the group's packed
			// offsets and the model's element matrix for each mesh
			layout(std430, binding = 9) readonly buffer DeformStreamBuffer {
			    int DeformInfoWords[];
			};

			layout(std430, binding = 10) readonly buffer DeformBuffer {
			    int DeformWords[];
			};

			layout(std430, binding = 11) readonly buffer DeformMeshBuffer {
			    mat4 DeformMeshElements[];
			};

			struct PrePassVertex {
			    vec4 position;
			    vec4 normal;
			    vec4 uv0Mid;
			    vec4 tangent;
			    ivec4 flatShaded;
			};

			layout(std430, binding = 7) writeonly buffer OutputBuffer {
			    PrePassVertex Output[];
			};

			uniform int BoneCount;
			uniform int SkinBoneCount;
			uniform bool HasSkin;
			uniform bool HasBoneVisuals;
			uniform int InstanceCount;
			uniform int QuadCount;
			uniform int InstanceOffset;
			uniform bool HasDeform;
			uniform bool HasDeformNormals;
			uniform int DeformMeshCount;
			uniform int DeformOffsetBase;
			uniform int DeformNormalBase;

			// A model's fixed normal (StrutModel#flatNormal), replacing every vertex normal. Not normalised, like a vanilla vertex normal
			uniform bool UseFlatNormal;
			uniform vec3 FlatNormal;

			// One raw base-geometry vertex, unpacked from VertexWords - see VertexBuffer's own doc comment
			struct RawVertex {
			    vec3 position;
			    vec2 uv;
			    vec3 normal;
			    int boneIndex;
			};

			RawVertex readVertex(uint vertexIndex) {
			    uint base = vertexIndex * 9u;
			    RawVertex v;

			    v.position = vec3(uintBitsToFloat(VertexWords[base]), uintBitsToFloat(VertexWords[base + 1u]), uintBitsToFloat(VertexWords[base + 2u]));
			    v.uv = vec2(uintBitsToFloat(VertexWords[base + 3u]), uintBitsToFloat(VertexWords[base + 4u]));
			    v.normal = vec3(uintBitsToFloat(VertexWords[base + 5u]), uintBitsToFloat(VertexWords[base + 6u]), uintBitsToFloat(VertexWords[base + 7u]));
			    v.boneIndex = int(VertexWords[base + 8u]);

			    return v;
			}

			// Poses one corner exactly like GpuRenderBackend's own vertex shader main() does, just against an
			// explicit instance index instead of gl_InstanceID - see that shader's own doc comment for why
			// each step is here, unchanged
			void poseVertex(int instance, RawVertex raw, uint vertexIndex, out vec3 outPos, out vec3 outNormal, out int outFlat) {
			    mat4 boneMatrix = BonePalette[instance * BoneCount + raw.boneIndex];
			    outFlat = 0;

			    vec3 deformOffset = vec3(0.0);
			    vec3 deformNormal = vec3(0.0);
			    vec3 deformNormalDelta = vec3(0.0);
			    vec4 rigidRow0 = vec4(0.0);
			    vec4 rigidRow1 = vec4(0.0);
			    vec4 rigidRow2 = vec4(0.0);
			    mat3 rigidTurn = mat3(1.0);
			    ivec3 deformInfo = ivec3(-1, 0, 0);
			    bool deformed = false;
			    bool deformedNormal = false;
			    bool deformedDelta = false;
			    bool deformedRigid = false;

			    if (HasDeform) {
			        deformInfo = ivec3(DeformInfoWords[vertexIndex * 3u], DeformInfoWords[vertexIndex * 3u + 1u], DeformInfoWords[vertexIndex * 3u + 2u]);

			        if (deformInfo.x >= 0) {
			            int entry = DeformWords[Instances[instance].deformInstance * DeformMeshCount + deformInfo.x];

			            if (entry >= 0) {
			                // a value for each corner
			                int at = DeformOffsetBase + (entry + deformInfo.y) * 4;

			                deformOffset = vec3(intBitsToFloat(DeformWords[at]), intBitsToFloat(DeformWords[at + 1]), intBitsToFloat(DeformWords[at + 2]));
			                deformed = true;

			                if (HasDeformNormals) {
			                    int normalAt = DeformNormalBase + (entry + deformInfo.y) * 4;

			                    if (intBitsToFloat(DeformWords[normalAt + 3]) > 0.5) {
			                        deformNormal = vec3(intBitsToFloat(DeformWords[normalAt]), intBitsToFloat(DeformWords[normalAt + 1]), intBitsToFloat(DeformWords[normalAt + 2]));
			                        deformedNormal = true;
			                    }
			                }
			            }
			            else if (entry <= -2) {
			                int code = -2 - entry;
			                int slot = code >> 1;

			                if ((code & 1) == 0) {
			                    // a value for each welded particle: the offset, and the change to the normal of the corners there
			                    int at = DeformOffsetBase + (slot + deformInfo.z) * 4;

			                    deformOffset = vec3(intBitsToFloat(DeformWords[at]), intBitsToFloat(DeformWords[at + 1]), intBitsToFloat(DeformWords[at + 2]));
			                    deformed = true;

			                    if (HasDeformNormals) {
			                        int normalAt = DeformNormalBase + (slot + deformInfo.z) * 4;

			                        deformNormalDelta = vec3(intBitsToFloat(DeformWords[normalAt]), intBitsToFloat(DeformWords[normalAt + 1]), intBitsToFloat(DeformWords[normalAt + 2]));
			                        deformedDelta = true;
			                    }
			                }
			                else {
			                    // one motion for the whole mesh: offset = A * position + b in three rows, and the rows of the matrix that turns its normals
			                    int at = DeformOffsetBase + slot * 4;

			                    rigidRow0 = vec4(intBitsToFloat(DeformWords[at]), intBitsToFloat(DeformWords[at + 1]), intBitsToFloat(DeformWords[at + 2]), intBitsToFloat(DeformWords[at + 3]));
			                    rigidRow1 = vec4(intBitsToFloat(DeformWords[at + 4]), intBitsToFloat(DeformWords[at + 5]), intBitsToFloat(DeformWords[at + 6]), intBitsToFloat(DeformWords[at + 7]));
			                    rigidRow2 = vec4(intBitsToFloat(DeformWords[at + 8]), intBitsToFloat(DeformWords[at + 9]), intBitsToFloat(DeformWords[at + 10]), intBitsToFloat(DeformWords[at + 11]));
			                    deformed = true;
			                    deformedRigid = true;

			                    if (HasDeformNormals) {
			                        int normalAt = DeformNormalBase + slot * 4;

			                        // the rows of the matrix, so the columns of a GLSL mat3
			                        vec3 row0 = vec3(intBitsToFloat(DeformWords[normalAt]), intBitsToFloat(DeformWords[normalAt + 1]), intBitsToFloat(DeformWords[normalAt + 2]));
			                        vec3 row1 = vec3(intBitsToFloat(DeformWords[normalAt + 4]), intBitsToFloat(DeformWords[normalAt + 5]), intBitsToFloat(DeformWords[normalAt + 6]));
			                        vec3 row2 = vec3(intBitsToFloat(DeformWords[normalAt + 8]), intBitsToFloat(DeformWords[normalAt + 9]), intBitsToFloat(DeformWords[normalAt + 10]));

			                        rigidTurn = transpose(mat3(row0, row1, row2));
			                        deformedNormal = true;
			                    }
			                }
			            }
			        }
			    }

			    int skinSlot = -1;
			    ivec4 joints = ivec4(0);
			    vec4 weights = vec4(0.0);

			    if (HasSkin) {
			        uint skinBase = vertexIndex * 7u;
			        uint word0 = SkinWords[skinBase];
			        uint word1 = SkinWords[skinBase + 1u];

			        joints = ivec4(int(word0 & 0xFFFFu), int((word0 >> 16) & 0xFFFFu), int(word1 & 0xFFFFu), int((word1 >> 16) & 0xFFFFu));
			        weights = vec4(uintBitsToFloat(SkinWords[skinBase + 2u]), uintBitsToFloat(SkinWords[skinBase + 3u]),
			                uintBitsToFloat(SkinWords[skinBase + 4u]), uintBitsToFloat(SkinWords[skinBase + 5u]));
			        skinSlot = int(SkinWords[skinBase + 6u]);
			    }

			    if (HasSkin && skinSlot >= 0) {
			        SkinnedMeshData meshData = SkinnedMeshes[skinSlot];
			        int skinBase = instance * SkinBoneCount;
			        vec4 blendedPosition = vec4(0.0);
			        vec3 blendedNormal = vec3(0.0);
			        float totalWeight = 0.0;

			        for (int i = 0; i < 4; i++) {
			            float weight = weights[i];

			            if (weight == 0.0) {
			                continue;
			            }

			            mat4 skinMatrix = SkinMatrices[skinBase + joints[i]];

			            blendedPosition += weight * (skinMatrix * vec4(raw.position, 1.0));
			            blendedNormal += weight * (mat3(skinMatrix) * raw.normal);
			            totalWeight += weight;
			        }

			        if (totalWeight == 0.0) {
			            blendedPosition = vec4(raw.position, 1.0);
			            blendedNormal = raw.normal;
			        }
			        else {
			            blendedPosition /= totalWeight;
			        }

			        mat4 composed = boneMatrix * meshData.elementMatrix;

			        outPos = (composed * vec4(blendedPosition.xyz + deformOffset, 1.0)).xyz;

			        mat3 composedNormal = transpose(inverse(mat3(composed)));
			        outNormal = normalize(composedNormal * (deformedNormal && !deformedRigid ? deformNormal : (deformedDelta ? normalize(blendedNormal) + deformNormalDelta : normalize(blendedNormal))));
			        outFlat = (deformed && !deformedNormal && !deformedDelta) || meshData.smoothShaded == 0 ? 1 : 0;
			    }
			    else {
			        mat3 element = deformed ? mat3(DeformMeshElements[deformInfo.x]) : mat3(1.0);

			        // a mesh that moves as one piece moves each vertex by a map of its own place (a carried mesh is not turned on its pivot, so that place is the raw position)
			        if (deformedRigid)
			            deformOffset = vec3(dot(rigidRow0.xyz, raw.position) + rigidRow0.w, dot(rigidRow1.xyz, raw.position) + rigidRow1.w, dot(rigidRow2.xyz, raw.position) + rigidRow2.w);

			        outPos = (boneMatrix * vec4(raw.position + element * deformOffset, 1.0)).xyz;

			        mat3 normalMatrix = transpose(inverse(mat3(boneMatrix)));
			        vec3 meshNormal = raw.normal;

			        if (deformedRigid)
			            meshNormal = rigidTurn * raw.normal;
			        else if (deformedNormal)
			            meshNormal = element * deformNormal;
			        else if (deformedDelta)
			            meshNormal = raw.normal + element * deformNormalDelta;

			        outNormal = normalize(normalMatrix * meshNormal);

			        // a face of a flat cube (its normal twice a unit one) is lit by the side the eye is on, as in GpuRenderBackend
			        if (length(raw.normal) > 1.5 && dot(outNormal, outPos) > 0.0)
			            outNormal = -outNormal;

			        outFlat = deformed && !deformedNormal && !deformedDelta ? 1 : 0;
			    }
			}

			void main() {
			    uint gid = gl_GlobalInvocationID.x;
			    uint totalQuads = uint(InstanceCount) * uint(QuadCount);

			    if (gid >= totalQuads) {
			        return;
			    }

			    int instance = int(gid / uint(QuadCount));
			    uint quad = gid % uint(QuadCount);
			    uint vBase = quad * 4u;

			    vec3 cornerPos[4];
			    vec3 cornerNormal[4];
			    vec2 cornerUv[4];
			    int cornerFlat[4];
			    int cornerBone[4];

			    for (int corner = 0; corner < 4; corner++) {
			        uint vertexIndex = vBase + uint(corner);
			        RawVertex raw = readVertex(vertexIndex);

			        poseVertex(InstanceOffset + instance, raw, vertexIndex, cornerPos[corner], cornerNormal[corner], cornerFlat[corner]);
			        cornerBone[corner] = raw.boneIndex;

			        // Per-bone UV_OFFSET, same lookup as GpuRenderBackend (gl_InstanceID * BoneCount + BoneIndex)
			        cornerUv[corner] = raw.uv;

			        if (HasBoneVisuals) {
			            vec4 uvOffsetScale = BoneVisuals[(InstanceOffset + instance) * BoneCount + raw.boneIndex].uvOffsetScale;

			            cornerUv[corner] = cornerUv[corner] * uvOffsetScale.zw + uvOffsetScale.xy;
			        }
			    }

			    vec2 midTexCoord = (cornerUv[0] + cornerUv[1] + cornerUv[2] + cornerUv[3]) * 0.25;

			    // A line-for-line port of Iris's own quad path (MixinBufferBuilder.fillExtendedData
			    // -> NormalHelper.computeFaceNormal/computeTangent, real source), so a normal-mapped pack reads
			    // exactly what it would from a vanilla entity quad. Face normal from the two diagonals; the
			    // tangent from corners 0/1/2 (not 0/1/3); f = 1 on a degenerate UV mapping rather than a made-up
			    // fixed tangent; handedness from the bitangent against tangent x normal - the previous
			    // normal x tangent had the sign backwards on every face
			    vec3 faceNormal = normalize(cross(cornerPos[2] - cornerPos[0], cornerPos[3] - cornerPos[1]));
			    vec3 edge1 = cornerPos[1] - cornerPos[0];
			    vec3 edge2 = cornerPos[2] - cornerPos[0];
			    vec2 deltaUv1 = cornerUv[1] - cornerUv[0];
			    vec2 deltaUv2 = cornerUv[2] - cornerUv[0];
			    float denom = deltaUv1.x * deltaUv2.y - deltaUv2.x * deltaUv1.y;
			    float f = denom == 0.0 ? 1.0 : 1.0 / denom;
			    vec3 tangent = normalize(f * (deltaUv2.y * edge1 - deltaUv1.y * edge2));
			    vec3 bitangent = normalize(f * (-deltaUv2.x * edge1 + deltaUv1.x * edge2));
			    float handedness = dot(bitangent, cross(tangent, faceNormal)) < 0.0 ? -1.0 : 1.0;

			    // A flat-shaded skinned mesh gets the deformed face's own normal, which is also what Iris
			    // itself does to every vanilla entity quad - GpuRenderBackend's raw-GL path does the same job
			    // per fragment with screen-space derivatives, which a pack's own
			    // shader can't be made to do. Smooth meshes keep their blended, authored normals
			    for (int corner = 0; corner < 4; corner++) {
			        if (cornerFlat[corner] == 1) {
			            cornerNormal[corner] = faceNormal;
			        }
			    }

			    if (UseFlatNormal) {
			        for (int corner = 0; corner < 4; corner++) {
			            cornerNormal[corner] = FlatNormal;
			        }
			    }

			    uint outBase = uint(instance) * uint(QuadCount) * 4u + vBase;

			    for (int corner = 0; corner < 4; corner++) {
			        PrePassVertex out0;

			        out0.position = vec4(cornerPos[corner], 1.0);
			        out0.normal = vec4(cornerNormal[corner], 0.0);
			        out0.uv0Mid = vec4(cornerUv[corner], midTexCoord);
			        out0.tangent = vec4(tangent, handedness);
			        out0.flatShaded = ivec4(cornerFlat[corner], cornerBone[corner], 0, 0);

			        Output[outBase + uint(corner)] = out0;
			    }
			}
			""";

	/**
	 * The second compute stage - repacks the first stage's clean-float {@code PrePassVertex}
	 * output into {@code IrisVertexFormats.ENTITY}'s real packed byte layout, so the same buffer can
	 * be bound as an ordinary {@code GL_ARRAY_BUFFER} and drawn through the pack's own unmodified
	 * program in {@link IrisMainDraw} - no GPU→CPU readback, the buffer stays resident throughout
	 * <p>
	 * Padded to {@link #PACKED_VERTEX_STRIDE_WORDS} (56 bytes), not {@code IrisVertexFormats.ENTITY}'s
	 * own reported 54 - that format's {@code mc_midTexCoord} field starts at byte 42, not 4-byte
	 * aligned, which two adjacent SSBO-written vertices sharing a machine word at that boundary would
	 * make a genuine data race between two different compute invocations (non-atomic read-modify-write
	 * on the same {@code uint}). {@link IrisMainDraw} supplies its own {@code glVertexAttribPointer}
	 * offsets/stride matching this padded layout instead of calling {@code VertexFormat.setupBufferState()}
	 * (which would assume the format's own unpadded 54-byte stride) - GL attribute binding has never
	 * required the stride to equal any particular {@code VertexFormat}'s reported size, only that
	 * offset/stride are self-consistent, which they are here
	 */
	private static final String PACK_COMPUTE_SOURCE = """
			#version 430 core

			layout(local_size_x = 64) in;

			struct PrePassVertex {
			    vec4 position;
			    vec4 normal;
			    vec4 uv0Mid;
			    vec4 tangent;
			    ivec4 flatShaded;
			};

			layout(std430, binding = 7) readonly buffer OutputBuffer {
			    PrePassVertex Vertices[];
			};

			struct InstanceData {
			    int packedLight;
			    int packedOverlay;
			    int entityId;
			    int blockEntityItemIds;
			    vec4 colorModulator;
			    int deformInstance;
			    // the rest of the instance data (GpuRenderBackend reads it); here only so the struct is as long as the one that is written
			    float lights[12];
			    int lightColour[4];
			    float custom[4];
			};

			layout(std430, binding = 1) readonly buffer InstanceDataBuffer {
			    InstanceData Instances[];
			};

			struct BoneVisual {
			    vec4 color;
			    vec4 uvOffsetScale;
			    int flags;
			};

			layout(std430, binding = 2) readonly buffer BoneVisualBuffer {
			    BoneVisual BoneVisuals[];
			};

			layout(std430, binding = 8) writeonly buffer PackedBuffer {
			    uint Packed[];
			};

			uniform int VertexCount;
			uniform int QuadCount;
			uniform int BoneCount;
			uniform bool HasBoneVisuals;
			uniform int InstanceOffset;

			uint packUbytes(vec4 c) {
			    uvec4 b = uvec4(round(clamp(c, 0.0, 1.0) * 255.0));

			    return b.x | (b.y << 8) | (b.z << 16) | (b.w << 24);
			}

			uint packSbyte(float v) {
			    int b = int(round(clamp(v, -1.0, 1.0) * 127.0));

			    return uint(b) & 0xFFu;
			}

			uint packSbytes4(vec4 v) {
			    return packSbyte(v.x) | (packSbyte(v.y) << 8) | (packSbyte(v.z) << 16) | (packSbyte(v.w) << 24);
			}

			void main() {
			    uint index = gl_GlobalInvocationID.x;

			    if (index >= uint(VertexCount)) {
			        return;
			    }

			    PrePassVertex v = Vertices[index];
			    int instance = int(index) / (QuadCount * 4);
			    InstanceData inst = Instances[InstanceOffset + instance];
			    uint base = index * 14u;

			    // Byte layout, matching IrisVertexFormats.ENTITY's own element order exactly (see this
			    // class's own doc comment on why the stride between vertices is padded to 56 bytes here
			    // rather than that format's own 54): Position(12) Color(4) UV0(8) UV1(4) UV2(4) Normal(3)+
			    // pad(1) iris_Entity(6) mc_midTexCoord(8) at_tangent(4)
			    Packed[base + 0u] = floatBitsToUint(v.position.x);
			    Packed[base + 1u] = floatBitsToUint(v.position.y);
			    Packed[base + 2u] = floatBitsToUint(v.position.z);
			    vec4 boneColor = HasBoneVisuals ? BoneVisuals[(InstanceOffset + instance) * BoneCount + v.flatShaded.y].color : vec4(1.0);
			    Packed[base + 3u] = packUbytes(inst.colorModulator * boneColor);
			    Packed[base + 4u] = floatBitsToUint(v.uv0Mid.x);
			    Packed[base + 5u] = floatBitsToUint(v.uv0Mid.y);
			    // Already packed low16(u)/high16(v) exactly like the target word format - the same
			    // convention OverlayTexture.packUv/LightmapTextureManager.pack use, which GpuRenderBackend's
			    // own fragment shader already unpacks this same way
			    Packed[base + 6u] = uint(inst.packedOverlay);
			    Packed[base + 7u] = uint(inst.packedLight);
			    Packed[base + 8u] = packSbytes4(vec4(v.normal.xyz, 0.0));
			    // iris_Entity (3 ushorts: entity, block entity, item), exactly as Iris's buffer builder writes
			    // entity, block entity, item ids - Iris's own values for this entity (see GpuBatchQueue.submit)
			    Packed[base + 9u] = (uint(inst.entityId) & 0xFFFFu) | ((uint(inst.blockEntityItemIds) & 0xFFFFu) << 16);
			    Packed[base + 10u] = uint(inst.blockEntityItemIds >> 16) & 0xFFFFu;
			    Packed[base + 11u] = floatBitsToUint(v.uv0Mid.z);
			    Packed[base + 12u] = floatBitsToUint(v.uv0Mid.w);
			    Packed[base + 13u] = packSbytes4(v.tangent);
			}
			""";

	/** {@link #PACK_COMPUTE_SOURCE}'s own output stride, in 4-byte words - see that field's own doc comment */
	static final int PACKED_VERTEX_STRIDE_WORDS = 14;

	/** {@link #PACK_COMPUTE_SOURCE}'s own output binding */
	private static final int PACKED_SSBO_BINDING = 8;

	private static GpuShaderProgram computeProgram;
	private static GpuShaderProgram packProgram;
	private static boolean initialised;
	private static boolean initFailed;

	private static int boneCountLoc;
	private static int skinBoneCountLoc;
	private static int hasSkinLoc;
	private static int instanceCountLoc;
	private static int quadCountLoc;
	private static int instanceOffsetLoc;
	private static int useFlatNormalLoc;
	private static int flatNormalLoc;
	private static int hasDeformLoc;
	private static int hasDeformNormalsLoc;
	private static int deformMeshCountLoc;
	private static int deformOffsetBaseLoc;
	private static int deformNormalBaseLoc;

	@Nullable
	private static Vector3f flatNormal;

	/** {@code GL_MAX_SHADER_STORAGE_BLOCK_SIZE}, queried once at init - see {@link #maxInstancesPerChunk} */
	private static long maxStorageBlockBytes;

	private static int packVertexCountLoc;
	private static int packQuadCountLoc;
	private static int packBoneCountLoc;
	private static int packHasVisualsLoc;
	private static int hasVisualsLoc;
	private static int packInstanceOffsetLoc;

	private static int outputSsbo;
	private static int packedSsbo;
	private static int dummySkinSsbo;

	private IrisPrePass() {}

	private static boolean ensureInitialised() {
		if (initialised)
			return !initFailed;

		initialised = true;

		try {
			computeProgram = GpuShaderProgram.compileCompute("strut:iris_prepass_compute", COMPUTE_SOURCE);
			boneCountLoc = computeProgram.uniformLocation("BoneCount");
			skinBoneCountLoc = computeProgram.uniformLocation("SkinBoneCount");
			hasSkinLoc = computeProgram.uniformLocation("HasSkin");
			hasVisualsLoc = computeProgram.uniformLocation("HasBoneVisuals");
			instanceCountLoc = computeProgram.uniformLocation("InstanceCount");
			quadCountLoc = computeProgram.uniformLocation("QuadCount");
			instanceOffsetLoc = computeProgram.uniformLocation("InstanceOffset");
			useFlatNormalLoc = computeProgram.uniformLocation("UseFlatNormal");
			hasDeformLoc = computeProgram.uniformLocation("HasDeform");
			hasDeformNormalsLoc = computeProgram.uniformLocation("HasDeformNormals");
			deformMeshCountLoc = computeProgram.uniformLocation("DeformMeshCount");
			deformOffsetBaseLoc = computeProgram.uniformLocation("DeformOffsetBase");
			deformNormalBaseLoc = computeProgram.uniformLocation("DeformNormalBase");
			flatNormalLoc = computeProgram.uniformLocation("FlatNormal");

			packProgram = GpuShaderProgram.compileCompute("strut:iris_prepass_pack", PACK_COMPUTE_SOURCE);
			packVertexCountLoc = packProgram.uniformLocation("VertexCount");
			packQuadCountLoc = packProgram.uniformLocation("QuadCount");
			packBoneCountLoc = packProgram.uniformLocation("BoneCount");
			packHasVisualsLoc = packProgram.uniformLocation("HasBoneVisuals");
			packInstanceOffsetLoc = packProgram.uniformLocation("InstanceOffset");

			outputSsbo = GL15.glGenBuffers();
			packedSsbo = GL15.glGenBuffers();
			dummySkinSsbo = GL15.glGenBuffers();
			maxStorageBlockBytes = GL32.glGetInteger64(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);

			GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, dummySkinSsbo);
			GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, SKIN_STREAM_STRIDE_WORDS * Integer.BYTES, GL15.GL_STATIC_DRAW);

			return true;
		}
		catch (RuntimeException ex) {
			initFailed = true;

			throw ex;
		}
	}


	/**
	 * How many instances of {@code model} one pre-pass dispatch may cover - the group is processed in
	 * chunks of at most this many, each posed, packed and drawn before the next reuses the buffers
	 * <p>
	 * Live-caught: this pass expands every vertex of every instance into its own output record (80
	 * bytes posed, then 56 packed), so one group of 50 entities of a 44,804-vertex model needed a
	 * ~179 MB output buffer - past {@code GL_MAX_SHADER_STORAGE_BLOCK_SIZE} (commonly 128 MB on AMD).
	 * Writes beyond that limit silently never land, so the later instances' vertices kept the previous
	 * frame's contents: invisible while standing still, but while moving every such face stretched
	 * between last frame's and this frame's camera-relative position - the parallel "slabs",
	 * longer at low fps. Capped at the smaller of the device's own limit and 64 MB, so memory stays
	 * bounded however many instances a group holds, and never less than one instance
	 */
	public static int maxInstancesPerChunk(GpuModel model) {
		ensureInitialised();

		long budget = Math.min(maxStorageBlockBytes > 0 ? maxStorageBlockBytes : Long.MAX_VALUE, 64L * 1024 * 1024);
		long perInstance = (long)(model.indexCount() / 6) * 4 * OUTPUT_VERTEX_STRIDE_BYTES;

		return (int)Math.max(1, Math.min(Integer.MAX_VALUE, budget / Math.max(1, perInstance)));
	}

	/**
	 * The entry point - poses a chunk of the group, then
	 * additionally repacks the result into {@code IrisVertexFormats.ENTITY}'s real byte layout so
	 * {@link IrisMainDraw} can draw it through Iris's own substituted program. Returns {@code false}
	 * (having drawn nothing) if the compute stages failed to compile or there is nothing to draw -
	 * callers should skip {@link IrisMainDraw#draw} for the frame in that case
	 */
	public static boolean runPosePass(GpuModel model, boolean hasSkin, int skinBoneCount, int instanceOffset, int instanceCount,
									  int paletteSsbo, int instanceSsbo, int skinSsbo, int skinnedMeshSsbo,
									  int visualsSsbo, boolean hasVisuals) {
		bindInputs(paletteSsbo, instanceSsbo, hasSkin, skinSsbo, skinnedMeshSsbo, visualsSsbo, hasVisuals);

		if (!ensureInitialised() || model.indexCount() == 0 || instanceCount == 0)
			return false;

		int quadCount = model.indexCount() / 6;

		dispatch(model, hasSkin, skinBoneCount, instanceOffset, instanceCount, quadCount, hasVisuals);
		pack(instanceOffset, instanceCount, quadCount, model.boneCount(), hasVisuals);

		return true;
	}

	/**
	 * The fixed normal the next pose passes write for every vertex, or null for the model's own. Set around a group's draws and cleared after,
	 * so it never leaks to the next group.
	 */
	public static void setFlatNormal(@Nullable Vector3f normal) {
		flatNormal = normal == null ? null : new Vector3f(normal);
	}

	/** This pass's own packed output buffer - see {@link #PACK_COMPUTE_SOURCE}'s own doc comment for its layout */
	static int packedSsbo() {
		return packedSsbo;
	}

	private static void pack(int instanceOffset, int instanceCount, int quadCount, int boneCount, boolean hasVisuals) {
		packProgram.use();

		int vertexCount = instanceCount * quadCount * 4;

		GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, OUTPUT_SSBO_BINDING, outputSsbo);
		ensurePackedCapacity(vertexCount * PACKED_VERTEX_STRIDE_WORDS * Integer.BYTES);
		GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, PACKED_SSBO_BINDING, packedSsbo);

		GL20.glUniform1i(packVertexCountLoc, vertexCount);
		GL20.glUniform1i(packQuadCountLoc, quadCount);
		GL20.glUniform1i(packBoneCountLoc, boneCount);
		GL20.glUniform1i(packHasVisualsLoc, hasVisuals ? 1 : 0);
		GL20.glUniform1i(packInstanceOffsetLoc, instanceOffset);

		int groups = (vertexCount + LOCAL_SIZE - 1) / LOCAL_SIZE;

		GL43.glDispatchCompute(groups, 1, 1);
		GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);
	}

	/**
	 * Unconditionally re-allocates (orphans) {@link #packedSsbo} every call, not just when growing -
	 * live-caught omission: a "reuse the same store unless it needs to grow" cache is exactly wrong
	 * for a buffer written fresh every frame by a compute shader and read that same frame by a draw
	 * call, since the GPU may still be consuming last frame's contents from the very same store when
	 * this frame's dispatch starts writing to it, with no CPU-visible synchronization forcing a wait.
	 * Seen live as alternating good/corrupted frames and vertices that appeared to "lag behind" or
	 * stretch while moving - the classic signature of two frames' worth of GPU work racing on one
	 * un-orphaned buffer. {@code GpuBatchQueue}'s own palette/instance/skin buffers already call
	 * {@code glBufferData} unconditionally every frame for the same reason (see its own doc comment on
	 * "buffer orphaning ... well-defined even if the GPU is still consuming the previous ... draw
	 * call") - this class's own buffers had drifted from that established pattern
	 */
	private static void ensurePackedCapacity(int bytesNeeded) {
		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, packedSsbo);
		GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, bytesNeeded, GL15.GL_STREAM_DRAW);
	}

	/**
	 * Binds this pass's own inputs itself (palette 0, instance data 1, skin matrices 3, skinned-mesh
	 * table 4), not relying on {@code GpuRenderBackend.drawInstances} having left them bound - between
	 * chunks {@code GpuBatchQueue} restores a shader pack's own SSBO bindings (see
	 * {@code SsboBindingGuard}) before letting the pack's program draw, so the next chunk's dispatch
	 * would otherwise read the pack's buffers instead
	 */
	private static void bindInputs(int paletteSsbo, int instanceSsbo, boolean hasSkin, int skinSsbo, int skinnedMeshSsbo,
									   int visualsSsbo, boolean hasVisuals) {
		if (hasVisuals)
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, VISUAL_SSBO_BINDING, visualsSsbo);

		GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, PALETTE_SSBO_BINDING, paletteSsbo);
		GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, INSTANCE_SSBO_BINDING, instanceSsbo);

		if (hasSkin) {
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SKIN_SSBO_BINDING, skinSsbo);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SKINNED_MESH_SSBO_BINDING, skinnedMeshSsbo);
		}
	}

	private static void dispatch(GpuModel model, boolean hasSkin, int skinBoneCount, int instanceOffset, int instanceCount, int quadCount, boolean hasVisuals) {
		computeProgram.use();

		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, model.vbo());
		GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, VERTEX_SSBO_BINDING, model.vbo());

		int skinStreamBuffer = hasSkin ? model.skinVbo() : dummySkinSsbo;

		GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SKIN_STREAM_SSBO_BINDING, skinStreamBuffer);

		ensureOutputCapacity(instanceCount * quadCount * 4 * OUTPUT_VERTEX_STRIDE_BYTES);
		GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, OUTPUT_SSBO_BINDING, outputSsbo);

		GL20.glUniform1i(boneCountLoc, model.boneCount());
		GL20.glUniform1i(skinBoneCountLoc, skinBoneCount);
		GL20.glUniform1i(hasSkinLoc, hasSkin ? 1 : 0);
		GL20.glUniform1i(instanceCountLoc, instanceCount);
		GL20.glUniform1i(quadCountLoc, quadCount);
		GL20.glUniform1i(hasVisualsLoc, hasVisuals ? 1 : 0);
		GL20.glUniform1i(instanceOffsetLoc, instanceOffset);
		GL20.glUniform1i(useFlatNormalLoc, flatNormal != null ? 1 : 0);

		// Cloth and flesh: the group's deformation (MeshDeformState, set by GpuRenderBackend#setDeform) is bound again here, a pack's own passes since may have
		// replaced the bindings
		boolean deforming = MeshDeformState.active && model.hasMeshes();

		GL20.glUniform1i(hasDeformLoc, deforming ? 1 : 0);

		if (deforming) {
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, DEFORM_STREAM_SSBO_BINDING, model.deformVbo());
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, DEFORM_SSBO_BINDING, MeshDeformState.dataSsbo);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, DEFORM_MESH_SSBO_BINDING, MeshDeformState.meshSsbo);
			GL20.glUniform1i(hasDeformNormalsLoc, MeshDeformState.hasNormals ? 1 : 0);
			GL20.glUniform1i(deformMeshCountLoc, MeshDeformState.meshCount);
			GL20.glUniform1i(deformOffsetBaseLoc, MeshDeformState.offsetBase);
			GL20.glUniform1i(deformNormalBaseLoc, MeshDeformState.normalBase);
		}

		if (flatNormal != null)
			GL20.glUniform3f(flatNormalLoc, flatNormal.x, flatNormal.y, flatNormal.z);

		int totalQuads = instanceCount * quadCount;
		int groups = (totalQuads + LOCAL_SIZE - 1) / LOCAL_SIZE;

		GL43.glDispatchCompute(groups, 1, 1);
		GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
	}


	/** Orphaned every call, same reasoning as {@link #ensurePackedCapacity}'s own doc comment */
	private static void ensureOutputCapacity(int bytesNeeded) {
		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, outputSsbo);
		GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, bytesNeeded, GL15.GL_STREAM_DRAW);
	}
}
