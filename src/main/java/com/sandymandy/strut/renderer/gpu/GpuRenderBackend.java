package com.sandymandy.strut.renderer.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.sandymandy.strut.cache.texture.AutoGlowingTexture;
import com.sandymandy.strut.model.DepthBias;
import com.sandymandy.strut.model.ShadingMode;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.perf.SeamDebug;
import com.sandymandy.strut.renderer.hook.StrutShaderHooks;
import com.sandymandy.strut.renderer.hook.StrutShaderParams;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * The shared vanilla-parity shader and its per-frame/per-draw GL calls, used by
 * {@link GpuBatchQueue}, and the seam the Iris backend slots into, so
 * keeping every raw draw behind this one class from the start is cheaper than retrofitting it
 * once a second backend exists
 * <p>
 * Replicates vanilla's own {@code rendertype_entity_cutout_no_cull} line-for-line (decompiled from
 * the client jar, not approximated); most of the
 * shading, fog and overlay reasoning is unchanged here.
 * <p>
 * Two things moved here from that first cut, both driven by needing this shader to serve real,
 * independently-lit entities rather than one probe grid at a time:
 * <ul>
 *     <li>{@code CameraRelativeOffset} is gone. {@code ModelViewMat} is now purely the camera's
 *     rotation ({@link WorldRenderContext#positionMatrix()}, set once per frame in
 *     {@link #beginFrame}) - every caller is expected to fold its own camera-relative-but-unrotated
 *     translation directly into each instance's own bone matrices instead (exactly how vanilla's
 *     own {@code BufferBuilder} bakes translation into {@code Position} and leaves rotation to the
 *     shader). {@code posedPosition} is therefore already the correct fog-distance input on its
 *     own, with nothing left to add</li>
 *     <li>{@code PackedLight}/{@code PackedOverlay}/{@code ColorModulator} are no longer per-draw
 *     uniforms (which could only ever hold one value for a whole instanced group) - they're now an
 *     {@code InstanceDataBuffer} SSBO, one entry per instance, read in the vertex stage (where
 *     {@code gl_InstanceID} exists) and handed to the fragment stage as flat/plain varyings. Real
 *     entities in one group are lit differently from each other; a single uniform could never have
 *     expressed that</li>
 * </ul>
 */
public final class GpuRenderBackend {
	/** {@code BonePaletteBuffer}'s binding point in {@link #VERTEX_SOURCE} - must match exactly */
	static final int PALETTE_SSBO_BINDING = 0;

	/** {@code InstanceDataBuffer}'s binding point in {@link #VERTEX_SOURCE} - must match exactly */
	static final int INSTANCE_SSBO_BINDING = 1;

	/** {@code BoneVisualBuffer}'s binding point in {@link #VERTEX_SOURCE} - must match exactly */
	static final int VISUAL_SSBO_BINDING = 2;

	/**
	 * {@code SkinMatrixBuffer}'s binding point in {@link #VERTEX_SOURCE} - the per-instance
	 * skin matrices, must match exactly
	 */
	static final int SKIN_SSBO_BINDING = 3;

	/**
	 * {@code SkinnedMeshBuffer}'s binding point in {@link #VERTEX_SOURCE} - the static,
	 * per-model skinned-mesh-record table, must match exactly
	 */
	static final int SKINNED_MESH_SSBO_BINDING = 4;

	/** The group's mesh deformation (see {@link MeshDeformPack}) and the model's mesh element matrices */
	static final int DEFORM_SSBO_BINDING = 5;
	static final int DEFORM_MESH_SSBO_BINDING = 6;

	/**
	 * One {@code InstanceData} struct's size under std430 layout: {@code int}/{@code int} (8 bytes),
	 * padded to 16 for {@code vec4}'s own alignment, then the {@code vec4} itself (16 bytes) - 32
	 * bytes total, already a multiple of the struct's own 16-byte alignment so no further padding is
	 * needed between array elements
	 */
	static final int INSTANCE_STRIDE_BYTES = 128;

	/**
	 * One {@code BoneVisual} struct's size under std430 layout: {@code color} (16 bytes) +
	 * {@code uvOffsetScale} (16 bytes) + {@code flags} (4 bytes, padded to the struct's own 16-byte
	 * alignment) - 48 bytes total. {@code uvOffsetScale.zw} (the {@code UV_SIZE} override's UV scale, 1 when unset) is applied before the offset; {@code flags}
	 * (the emissive toggle) is reserved, uploaded as an inert default
	 */
	static final int VISUAL_STRIDE_BYTES = 48;

	private static final String VERTEX_SOURCE = """
			#version 430 core

			layout(location = 0) in vec3 Position;
			layout(location = 1) in vec2 UV;
			layout(location = 2) in vec3 Normal;
			layout(location = 3) in int BoneIndex;

			// Only meaningful when SkinSlot >= 0. A rigid
			// vertex's Position/Normal above are already bone-local and pre-transformed by their own
			// element matrix at bake time; a skinned vertex's are the true, untransformed rest data
			// instead (see GpuModel#accumulate's own doc comment for why), consumed here instead of
			// there
			layout(location = 4) in ivec4 Joints;
			layout(location = 5) in vec4 Weights;
			layout(location = 6) in int SkinSlot;

			// Which mesh of the model a vertex belongs to (-1 for a cube's), which corner of it (polygon * 4 + corner) and which welded particle that corner is: where
			// the offsets that cloth and flesh move a mesh by (MeshDeformPack) are looked up. Only meaningful when HasDeform
			layout(location = 7) in ivec3 DeformInfo;

			// One instance's worth of bone matrices is BoneCount entries long, so instance N's
			// palette starts at N * BoneCount - gl_InstanceID is which copy of the geometry this
			// vertex belongs to, not anything this shader decides. BoneCount differs per model,
			// reset before each group's own draw call
			layout(std430, binding = 0) readonly buffer BonePaletteBuffer {
			    mat4 BonePalette[];
			};

			struct InstanceData {
			    int packedLight;
			    int packedOverlay;
			    vec4 colorModulator;
			    int deformInstance;
			    // the lights on this instance (LightProbe): for each, its direction scaled by its strength (zero for none) and its colour packed 0xRRGGBB
			    float lights[12];
			    int lightColour[4];
			    // four floats of the mod's own (StrutRenderHooks.SUBMIT), read by the shader hooks
			    float custom[4];
			};

			// One entry per instance, not per bone - a real entity's own light level, overlay (hurt
			// flash) and tint differ from its neighbour's, which a per-draw uniform could never
			// express once a group holds more than one genuinely different entity
			layout(std430, binding = 1) readonly buffer InstanceDataBuffer {
			    InstanceData Instances[];
			};

			struct BoneVisual {
			    vec4 color;
			    vec4 uvOffsetScale;
			    int flags;
			};

			// Indexed exactly like BonePaletteBuffer (gl_InstanceID * BoneCount + BoneIndex) - only
			// actually read when HasBoneVisuals is true, since most groups have no instance overriding
			// anything at all and paying for this buffer's bandwidth on every draw would nearly double
			// what BonePaletteBuffer alone already costs
			layout(std430, binding = 2) readonly buffer BoneVisualBuffer {
			    BoneVisual BoneVisuals[];
			};

			// Per-instance data - one instance's worth of
			// skin matrices is SkinBoneCount entries long (every armature this model carries,
			// concatenated in BakedStrutModel.armatures() order), the same "N * count" indexing
			// BonePaletteBuffer already uses. Only ever read when HasSkin is true and a vertex's own
			// SkinSlot is not -1 - a rigid-only model never binds real data here at all
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

			// Static, per-model data - one entry per SkinSlot, uploaded once at bake time
			// (GpuModel#bake) and never rebuilt per frame or per instance, unlike every other buffer
			// here. smoothShaded picks which of FlatShaded's two lighting paths a flat-skinned
			// fragment takes - see the fragment shader's own comment on why flat shading has to move
			// there at all for a skinned mesh
			layout(std430, binding = 4) readonly buffer SkinnedMeshBuffer {
			    SkinnedMeshData SkinnedMeshes[];
			};

			// This group's deformation, one buffer of 32-bit words: a table for each instance and mesh of where the mesh's offsets start (-1 for none), then the
			// offsets, then the normals (see MeshDeformPack). The element matrix of each mesh is for turning an offset into the space the baked rigid vertices are in
			layout(std430, binding = 5) readonly buffer DeformBuffer {
			    int DeformWords[];
			};

			layout(std430, binding = 6) readonly buffer DeformMeshBuffer {
			    mat4 DeformMeshElements[];
			};

			uniform bool HasDeform;
			uniform bool HasDeformNormals;
			uniform int DeformMeshCount;
			uniform int DeformOffsetBase;
			uniform int DeformNormalBase;

			uniform mat4 ProjMat;
			uniform mat4 ModelViewMat;
			uniform int BoneCount;
			uniform int SkinBoneCount;
			uniform bool HasSkin;
			uniform vec3 Light0_Direction;
			uniform vec3 Light1_Direction;
			uniform int ShadingMode;

			// How a surface facing n is lit by the two directional lights. Mode 0 is the game's own (a base of 0.4, hard between faces), 3 is no shading by direction. The
			// others wrap the light round the surface, add a tint from the sky side to the ground side (the sky is the way both lights come from) and keep the range between
			// 0.55 and 1, so no face goes dark and the many small faces of a character blend
			float shadeWrap(float d, float wrap) {
			    return clamp((d + wrap) / (1.0 + wrap), 0.0, 1.0);
			}

			float strutShade(vec3 n, vec3 keys[4]) {
			    if (ShadingMode == 3) {
			        return 1.0;
			    }

			    if (ShadingMode == 0) {
			        float l0 = max(0.0, dot(Light0_Direction, n));
			        float l1 = max(0.0, dot(Light1_Direction, n));

			        return min(1.0, (l0 + l1) * 0.6 + 0.4);
			    }

			    vec3 unit = n / max(length(n), 0.0001);
			    vec3 sky = normalize(Light0_Direction + Light1_Direction);
			    float hemisphere = dot(unit, sky) * 0.5 + 0.5;
			    float total = 0.0;
			    float lit = 0.0;

			    // the lights that are really there (the sun or the moon, the torches, a mod's: LightProbe), each lighting the side of the surface that faces it, so two torches
			    // either side of a model light both sides; the stronger they are the more of a difference they make, so in a dim place the model is flat and beside a lamp it
			    // is shaped by it
			    for (int i = 0; i < 4; i++) {
			        float s = length(keys[i]);

			        if (s > 0.001) {
			            lit += s * shadeWrap(dot(unit, keys[i] / s), 0.15);
			            total += s;
			        }
			    }

			    float strength = clamp(total, 0.0, 1.0);

			    if (total > 0.001) {
			        lit = clamp(lit, 0.0, 1.0);

			        float contrast = clamp(strength * 1.25, 0.3, 1.0);
			        // the sky side only matters where there is little light to go by: with a real light on the model, its direction is what shapes it
			        float skyShare = mix(0.35, 0.08, clamp(strength, 0.0, 1.0));

			        return 0.55 + 0.45 * mix(0.5, clamp(lit * (1.0 - skyShare) + hemisphere * skyShare, 0.0, 1.0), contrast);
			    }

			    float fixedKey = 0.5 * (shadeWrap(dot(Light0_Direction, unit), 0.4) + shadeWrap(dot(Light1_Direction, unit), 0.4));

			    return 0.55 + 0.45 * clamp(fixedKey * 0.6 + hemisphere * 0.4, 0.0, 1.0);
			}

			// What the colour of the lights does to a surface: each light tints the side that faces it, by how much of the surface's light it gives. A white light (1, 1, 1)
			// changes nothing; a red one takes the green and blue out of what it lights
			vec3 strutTint(vec3 n, vec3 keys[4], vec3 colours[4]) {
			    if (ShadingMode == 0 || ShadingMode == 3) {
			        return vec3(1.0);
			    }

			    vec3 unit = n / max(length(n), 0.0001);
			    vec3 tint = vec3(1.0);

			    for (int i = 0; i < 4; i++) {
			        float s = length(keys[i]);

			        if (s > 0.001) {
			            tint += s * shadeWrap(dot(unit, keys[i] / s), 0.15) * (colours[i] - vec3(1.0));
			        }
			    }

			    return clamp(tint, vec3(0.0), vec3(1.5));
			}
			uniform int FogShape;
			uniform bool HasBoneVisuals;
			uniform vec4 StrutParams[8];

			//@hook vertex_decls

			out vec2 texCoord;
			out vec4 vertexColor;
			out float vertexDistance;
			flat out int instPackedLight;
			flat out int instPackedOverlay;
			out vec4 instColorModulator;
			out vec4 boneVisualColor;
			out vec3 fragPos;
			flat out int flatShaded;
			flat out vec3 lightKey[4];
			flat out vec3 lightColour[4];
			out vec3 lightTint;
			flat out vec4 strutCustom;
			out vec4 strutVarying0;
			out vec4 strutVarying1;
			out float rimView;

			float fog_distance(vec3 pos, int shape) {
			    if (shape == 0) {
			        return length(pos);
			    }

			    return max(length(pos.xz), abs(pos.y));
			}

			void main() {
			    mat4 boneMatrix = BonePalette[gl_InstanceID * BoneCount + BoneIndex];
			    InstanceData instData = Instances[gl_InstanceID];
			    for (int i = 0; i < 4; i++) {
			        lightKey[i] = vec3(instData.lights[i * 3], instData.lights[i * 3 + 1], instData.lights[i * 3 + 2]);

			        int packed = instData.lightColour[i];

			        lightColour[i] = vec3(float((packed >> 16) & 255), float((packed >> 8) & 255), float(packed & 255)) / 255.0;
			    }

			    strutCustom = vec4(instData.custom[0], instData.custom[1], instData.custom[2], instData.custom[3]);
			    strutVarying0 = vec4(0.0);
			    strutVarying1 = vec4(0.0);
			    vec4 posedPosition;
			    vec3 worldNormal;
			    flatShaded = 0;

			    // Cloth and flesh: this corner is moved by an offset in the mesh's own space, given for the corner or for its welded particle, or by a motion the whole mesh
			    // has (a rigid piece riding on cloth). It is lit by a normal the physics gave (for the corner, or a change to add to its own for the particle, or a turn of its
			    // own for the rigid piece), else by the face's (the fragment shader's flat path, as a flat skinned mesh)
			    vec3 deformOffset = vec3(0.0);
			    vec3 deformNormal = vec3(0.0);
			    vec3 deformNormalDelta = vec3(0.0);
			    vec4 rigidRow0 = vec4(0.0);
			    vec4 rigidRow1 = vec4(0.0);
			    vec4 rigidRow2 = vec4(0.0);
			    mat3 rigidTurn = mat3(1.0);
			    bool deformed = false;
			    bool deformedNormal = false;
			    bool deformedDelta = false;
			    bool deformedRigid = false;

			    if (HasDeform && DeformInfo.x >= 0) {
			        int entry = DeformWords[instData.deformInstance * DeformMeshCount + DeformInfo.x];

			        if (entry >= 0) {
			            // a value for each corner
			            int at = DeformOffsetBase + (entry + DeformInfo.y) * 4;

			            deformOffset = vec3(intBitsToFloat(DeformWords[at]), intBitsToFloat(DeformWords[at + 1]), intBitsToFloat(DeformWords[at + 2]));
			            deformed = true;

			            if (HasDeformNormals) {
			                int normalAt = DeformNormalBase + (entry + DeformInfo.y) * 4;

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
			                int at = DeformOffsetBase + (slot + DeformInfo.z) * 4;

			                deformOffset = vec3(intBitsToFloat(DeformWords[at]), intBitsToFloat(DeformWords[at + 1]), intBitsToFloat(DeformWords[at + 2]));
			                deformed = true;

			                if (HasDeformNormals) {
			                    int normalAt = DeformNormalBase + (slot + DeformInfo.z) * 4;

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

			    // groupBonePalette * E_mesh * skin(rest) - the
			    // formula GpuSkinningParityTest pins against the real CPU render path. The skin blend
			    // runs on the true rest position/normal first (SkinnedMeshData.elementMatrix is
			    // deliberately not baked into Position/Normal - see GpuModel#accumulate), then the
			    // owning group bone's palette entry and the mesh's own pivot rotation are applied
			    // together, exactly like StrutRenderer#renderSkinnedMesh applies poseState after
			    // StrutSkin#skin rather than before it
			    if (HasSkin && SkinSlot >= 0) {
			        SkinnedMeshData meshData = SkinnedMeshes[SkinSlot];
			        int skinBase = gl_InstanceID * SkinBoneCount;
			        vec4 blendedPosition = vec4(0.0);
			        vec3 blendedNormal = vec3(0.0);
			        float totalWeight = 0.0;

			        for (int i = 0; i < 4; i++) {
			            float weight = Weights[i];

			            if (weight == 0.0) {
			                continue;
			            }

			            // Not an inverse-transpose per bone - StrutSkin#skinNormals doesn't use one
			            // either (Vector3f#transformDirection applies a matrix's linear part directly),
			            // so matching it exactly here means diverging from vanilla-parity precision the
			            // same amount the already-proven CPU reference already does, not introducing a
			            // new one
			            mat4 skinMatrix = SkinMatrices[skinBase + Joints[i]];

			            blendedPosition += weight * (skinMatrix * vec4(Position, 1.0));
			            blendedNormal += weight * (mat3(skinMatrix) * Normal);
			            totalWeight += weight;
			        }

			        // Same as StrutSkin#skin: an unweighted vertex stays at rest rather than collapsing
			        // to the origin, and weights are normalised to sum to one (Blockbench does both)
			        if (totalWeight == 0.0) {
			            blendedPosition = vec4(Position, 1.0);
			            blendedNormal = Normal;
			        }
			        else {
			            blendedPosition /= totalWeight;
			        }

			        mat4 composed = boneMatrix * meshData.elementMatrix;

			        posedPosition = composed * vec4(blendedPosition.xyz + deformOffset, 1.0);

			        // One final normalize on the fully composed result, matching the rigid branch's own
			        // convention below, rather than StrutRenderer#renderSkinnedMesh's own two-stage
			        // normalize (once after the skin blend, again implicitly never after the outer
			        // transform) - a disclosed simplification, not an oversight
			        mat3 composedNormal = transpose(inverse(mat3(composed)));
			        worldNormal = normalize(composedNormal * (deformedNormal && !deformedRigid ? deformNormal : (deformedDelta ? normalize(blendedNormal) + deformNormalDelta : normalize(blendedNormal))));

			        // A flat-shaded skinned mesh gets its
			        // per-face normal from screen-space derivatives in the fragment shader instead -
			        // the vertex-level worldNormal computed above is discarded for this case (see
			        // vertexColor's own note below), and flatShaded tells the fragment shader to
			        // compute lighting itself rather than trust the interpolated vertexColor
			        flatShaded = (deformed && !deformedNormal && !deformedDelta) || meshData.smoothShaded == 0 ? 1 : 0;
			    }
			    else {
			        // A rigid mesh's baked vertices are already turned by its element matrix, so an offset (given in the mesh's own space) is turned by it too
			        mat3 element = deformed ? mat3(DeformMeshElements[DeformInfo.x]) : mat3(1.0);

			        // a mesh that moves as one piece moves each vertex by a map of its own place (a carried mesh is not turned on its pivot, so that place is Position)
			        if (deformedRigid)
			            deformOffset = vec3(dot(rigidRow0.xyz, Position) + rigidRow0.w, dot(rigidRow1.xyz, Position) + rigidRow1.w, dot(rigidRow2.xyz, Position) + rigidRow2.w);

			        posedPosition = boneMatrix * vec4(Position + element * deformOffset, 1.0);

			        // A plain mat3(boneMatrix) would be wrong under non-uniform bone scale (an
			        // animation channel can scale a bone non-uniformly) - the inverse-transpose is what
			        // MatrixStack.Entry.computeNormal does for exactly the same reason on the CPU path
			        mat3 normalMatrix = transpose(inverse(mat3(boneMatrix)));
			        vec3 meshNormal = Normal;

			        if (deformedRigid)
			            meshNormal = rigidTurn * Normal;
			        else if (deformedNormal)
			            meshNormal = element * deformNormal;
			        else if (deformedDelta)
			            meshNormal = Normal + element * deformNormalDelta;

			        worldNormal = normalize(normalMatrix * meshNormal);

			        // A face of a flat cube (its normal is twice as long as a unit one) has a twin on the same plane facing the other way, and either may be the one in
			        // front: it is lit by the side the eye is on, so the two look alike. Judged in view space, where the eye is the origin of a perspective view (the world)
			        // and the view is straight down the z axis in an orthographic one (a model drawn in a screen, where the position says nothing of where the eye is)
			        if (length(Normal) > 1.5) {
			            vec3 viewPosition = (ModelViewMat * posedPosition).xyz;
			            vec3 viewNormal = mat3(ModelViewMat) * worldNormal;
			            vec3 towardEye = ProjMat[3][3] > 0.5 ? vec3(0.0, 0.0, 1.0) : -viewPosition;

			            if (dot(viewNormal, towardEye) < 0.0)
			                worldNormal = -worldNormal;
			        }

			        flatShaded = deformed && !deformedNormal && !deformedDelta ? 1 : 0;
			    }

			    //@hook vertex_pose

			    gl_Position = ProjMat * ModelViewMat * posedPosition;

			    if (HasBoneVisuals) {
			        BoneVisual visual = BoneVisuals[gl_InstanceID * BoneCount + BoneIndex];

			        texCoord = UV * visual.uvOffsetScale.zw + visual.uvOffsetScale.xy;
			        boneVisualColor = visual.color;
			    }
			    else {
			        texCoord = UV;
			        boneVisualColor = vec4(1.0);
			    }

			    // Computed unconditionally, including for a flat-skinned vertex whose own lighting
			    // this value is never actually used for (the fragment shader recomputes it there
			    // instead, gated by flatShaded) - a plain per-vertex assignment is cheaper than a
			    // branch to skip it, and the wasted value is harmless
			    vertexColor = vec4(vec3(strutShade(worldNormal, lightKey)), 1.0);
			    lightTint = strutTint(worldNormal, lightKey, lightColour);

			    // how far the surface is turned from the eye (0 facing it, 1 edge on), for the toon rim; judged in view space like the flat cube above
			    rimView = 0.0;

			    if (ShadingMode == 2) {
			        vec3 rimPosition = (ModelViewMat * posedPosition).xyz;
			        vec3 rimNormal = normalize(mat3(ModelViewMat) * worldNormal);
			        vec3 rimToEye = ProjMat[3][3] > 0.5 ? vec3(0.0, 0.0, 1.0) : normalize(-rimPosition);

			        rimView = 1.0 - abs(dot(rimNormal, rimToEye));
			    }

			    // boneMatrix already folds in the caller's own camera-relative-but-unrotated
			    // instance transform (see this class's own doc comment), so posedPosition is
			    // already the right fog-distance input with nothing further to add
			    vertexDistance = fog_distance(posedPosition.xyz, FogShape);

			    // Same space Light0_Direction/Light1_Direction are already used in above (posedPosition
			    // before ModelViewMat's own rotation) - taking screen-space derivatives of this in the
			    // fragment shader gives a face normal in that same space, so no extra transform is
			    // needed to keep it consistent with the vertex-lit path's own convention
			    fragPos = posedPosition.xyz;

			    instPackedLight = instData.packedLight;
			    instPackedOverlay = instData.packedOverlay;
			    instColorModulator = instData.colorModulator;

			    //@hook vertex_color
			}
			""";

	private static final String FRAGMENT_SOURCE = """
			#version 430 core

			in vec2 texCoord;
			in vec4 vertexColor;
			in float vertexDistance;
			flat in int instPackedLight;
			flat in int instPackedOverlay;
			in vec4 instColorModulator;
			in vec4 boneVisualColor;
			in vec3 fragPos;
			flat in int flatShaded;
			flat in vec3 lightKey[4];
			flat in vec3 lightColour[4];
			in vec3 lightTint;
			flat in vec4 strutCustom;
			in vec4 strutVarying0;
			in vec4 strutVarying1;
			in float rimView;
			uniform vec4 StrutParams[8];

			//@hook fragment_decls
			out vec4 fragColor;

			uniform sampler2D Sampler0;
			uniform sampler2D Sampler1;
			uniform sampler2D Sampler2;
			uniform sampler2D Sampler3;
			uniform bool HasEmissive;
			// Diagnostic only, see com.sandymandy.strut.perf.SeamDebug: 0 normal, 1 no alpha discard, 2 flat colour per quad, 3 exact texel fetch
			uniform int DebugSeams;

			// A translucent model is drawn in two kinds of fragment: 0 all of them, 1 only those that are opaque (their depth hides what is behind them), 2 only
			// those that are not (they blend over what the opaque ones left, so a see-through shell shows the core inside it)
			uniform int AlphaPass;

			uniform vec3 Light0_Direction;
			uniform vec3 Light1_Direction;
			uniform int ShadingMode;

			// How a surface facing n is lit by the two directional lights. Mode 0 is the game's own (a base of 0.4, hard between faces), 3 is no shading by direction. The
			// others wrap the light round the surface, add a tint from the sky side to the ground side (the sky is the way both lights come from) and keep the range between
			// 0.55 and 1, so no face goes dark and the many small faces of a character blend
			float shadeWrap(float d, float wrap) {
			    return clamp((d + wrap) / (1.0 + wrap), 0.0, 1.0);
			}

			float strutShade(vec3 n, vec3 keys[4]) {
			    if (ShadingMode == 3) {
			        return 1.0;
			    }

			    if (ShadingMode == 0) {
			        float l0 = max(0.0, dot(Light0_Direction, n));
			        float l1 = max(0.0, dot(Light1_Direction, n));

			        return min(1.0, (l0 + l1) * 0.6 + 0.4);
			    }

			    vec3 unit = n / max(length(n), 0.0001);
			    vec3 sky = normalize(Light0_Direction + Light1_Direction);
			    float hemisphere = dot(unit, sky) * 0.5 + 0.5;
			    float total = 0.0;
			    float lit = 0.0;

			    // the lights that are really there (the sun or the moon, the torches, a mod's: LightProbe), each lighting the side of the surface that faces it, so two torches
			    // either side of a model light both sides; the stronger they are the more of a difference they make, so in a dim place the model is flat and beside a lamp it
			    // is shaped by it
			    for (int i = 0; i < 4; i++) {
			        float s = length(keys[i]);

			        if (s > 0.001) {
			            lit += s * shadeWrap(dot(unit, keys[i] / s), 0.15);
			            total += s;
			        }
			    }

			    float strength = clamp(total, 0.0, 1.0);

			    if (total > 0.001) {
			        lit = clamp(lit, 0.0, 1.0);

			        float contrast = clamp(strength * 1.25, 0.3, 1.0);
			        // the sky side only matters where there is little light to go by: with a real light on the model, its direction is what shapes it
			        float skyShare = mix(0.35, 0.08, clamp(strength, 0.0, 1.0));

			        return 0.55 + 0.45 * mix(0.5, clamp(lit * (1.0 - skyShare) + hemisphere * skyShare, 0.0, 1.0), contrast);
			    }

			    float fixedKey = 0.5 * (shadeWrap(dot(Light0_Direction, unit), 0.4) + shadeWrap(dot(Light1_Direction, unit), 0.4));

			    return 0.55 + 0.45 * clamp(fixedKey * 0.6 + hemisphere * 0.4, 0.0, 1.0);
			}

			// What the colour of the lights does to a surface: each light tints the side that faces it, by how much of the surface's light it gives. A white light (1, 1, 1)
			// changes nothing; a red one takes the green and blue out of what it lights
			vec3 strutTint(vec3 n, vec3 keys[4], vec3 colours[4]) {
			    if (ShadingMode == 0 || ShadingMode == 3) {
			        return vec3(1.0);
			    }

			    vec3 unit = n / max(length(n), 0.0001);
			    vec3 tint = vec3(1.0);

			    for (int i = 0; i < 4; i++) {
			        float s = length(keys[i]);

			        if (s > 0.001) {
			            tint += s * shadeWrap(dot(unit, keys[i] / s), 0.15) * (colours[i] - vec3(1.0));
			        }
			    }

			    return clamp(tint, vec3(0.0), vec3(1.5));
			}

			// The toon look, applied per fragment to the texture's own colour: the shadow is not the colour made darker but a deeper, more saturated and slightly cool one
			// (the colour squared), the light a touch warm, with a soft-edged step between them and a middle tone, and a thin soft rim of light at the outline on the lit
			// side. shade is what strutShade gave (0.55 in the dark, 1 in the light), rim how far the surface is turned from the eye (0 facing it, 1 edge on)
			vec3 toonColor(vec3 albedo, float shade, float rim) {
			    //@hook fragment_toon
			    float t = clamp((shade - 0.55) / 0.45, 0.0, 1.0);
			    float w = max(fwidth(t), 0.12);
			    float rimWidth = max(fwidth(rim), 0.08);

			    // the colour squared is deeper and more saturated; half of it mixed back with the colour made darker keeps the hue and takes some of the saturation off
			    vec3 shadow = mix(albedo * 0.66, albedo * albedo, 0.4) * vec3(0.95, 0.93, 1.0);
			    vec3 middle = mix(shadow, albedo * 0.94, 0.55);
			    vec3 light = albedo * vec3(0.97, 0.95, 0.93);

			    vec3 result = mix(shadow, middle, smoothstep(0.26 - w, 0.26 + w, t));
			    result = mix(result, light, smoothstep(0.60 - w, 0.60 + w, t));

			    float rimLight = smoothstep(0.62 - rimWidth, 0.62 + rimWidth, rim) * smoothstep(0.30, 0.70, t);

			    return result + rimLight * 0.11 * mix(albedo, vec3(1.0), 0.55);
			}

			uniform float FogStart;
			uniform float FogEnd;
			uniform vec4 FogColor;

			vec4 linear_fog(vec4 inColor, float dist, float fogStart, float fogEnd, vec4 fogColor) {
			    if (dist <= fogStart) {
			        return inColor;
			    }

			    float fogValue = dist < fogEnd ? smoothstep(fogStart, fogEnd, dist) : 1.0;

			    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
			}

			void main() {
			    vec4 color;

			    if (DebugSeams == 3) {
			        ivec2 texSize = textureSize(Sampler0, 0);

			        color = texelFetch(Sampler0, clamp(ivec2(floor(texCoord * vec2(texSize))), ivec2(0), texSize - 1), 0);
			    }
			    else if (DebugSeams == 2) {
			        float quadId = float(gl_PrimitiveID / 2);

			        color = vec4(fract(sin(quadId * 12.9898) * 43758.5453), fract(sin(quadId * 78.233) * 43758.5453), fract(sin(quadId * 39.425) * 43758.5453), 1.0);
			    }
			    else {
			        color = texture(Sampler0, texCoord);

			        if (DebugSeams == 1) {
			            color.a = 1.0;
			        }
			    }

			    // Both models render through getEntityCutoutNoCull on the CPU path - a binary
			    // discard at this threshold, not smooth alpha blending, matching vanilla's own
			    // cutout convention
			    if (color.a < 0.1) {
			        discard;
			    }

			    //@hook fragment_albedo

			    // A flat-shaded skinned mesh has no single
			    // per-bone matrix to carry a vertex normal through - a vertex on the seam between two
			    // bones deforms along a curve no rigid transform describes, so any normal fixed at
			    // vertex time would light the bend as if it hadn't happened. StrutRenderer#renderSkinnedMesh
			    // sidesteps this on the CPU by recomputing a face normal from the *deformed* corners
			    // every frame instead of transforming a rest normal; dFdx/dFdy of the already-deformed,
			    // already-interpolated fragPos is that same idea running per fragment instead of per
			    // face, which a vertex shader has no way to do (it never sees a whole triangle at once).
			    // gl_FrontFacing corrects the sign - which way cross(dFdx, dFdy) points depends on
			    // screen-space winding, which flips with viewing angle, not just with which face this is
			    vec4 shadeColor = vertexColor;
			    vec3 tint = lightTint;

			    if (flatShaded == 1) {
			        vec3 faceNormal = normalize(cross(dFdx(fragPos), dFdy(fragPos)));

			        if (!gl_FrontFacing) {
			            faceNormal = -faceNormal;
			        }

			        shadeColor = vec4(vec3(strutShade(faceNormal, lightKey)), 1.0);
			        tint = strutTint(faceNormal, lightKey, lightColour);
			    }

			    if (ShadingMode == 2) {
			        color.rgb = toonColor(color.rgb, shadeColor.r, rimView);
			        shadeColor = vec4(1.0);
			    }

			    color *= shadeColor * instColorModulator * boneVisualColor;
			    color.rgb *= tint;

			    if ((AlphaPass == 1 && color.a < 0.99) || (AlphaPass == 2 && color.a >= 0.99)) {
			        discard;
			    }

			    // Packed exactly like OverlayTexture.packUv/LightmapTextureManager.pack do it
			    // (verified against their real bytecode, not assumed) - low 16 bits and high 16
			    // bits of one int each, rather than the two real ivec2 vertex attributes vanilla's
			    // own format carries, since both arrive here as per-instance data already
			    ivec2 overlayUv = ivec2(instPackedOverlay & 0xFFFF, (instPackedOverlay >> 16) & 0xFFFF);
			    vec4 overlayColor = texelFetch(Sampler1, overlayUv, 0);

			    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);

			    // the light level may lie between two whole levels (a model's light is eased from one level to the next, see LightProbe#smooth): the lightmap is read at
			    // the four levels round it and mixed, so walking toward a torch brightens a model smoothly and not a level at a time
			    vec2 lightPos = vec2(instPackedLight & 0xFFFF, (instPackedLight >> 16) & 0xFFFF) / 16.0;
			    ivec2 lightLow = ivec2(floor(lightPos));
			    ivec2 lightHigh = min(lightLow + 1, ivec2(15));
			    vec2 lightFrac = lightPos - vec2(lightLow);
			    vec4 lightMapColor = mix(mix(texelFetch(Sampler2, lightLow, 0), texelFetch(Sampler2, ivec2(lightHigh.x, lightLow.y), 0), lightFrac.x),
			            mix(texelFetch(Sampler2, ivec2(lightLow.x, lightHigh.y), 0), texelFetch(Sampler2, lightHigh, 0), lightFrac.x), lightFrac.y);

			    if (ShadingMode == 2) {
			        // the toon look keeps a floor under the world's light, so a model in the dark is still a shape, in the hue the light has
			        float lightLevel = max(lightMapColor.r, max(lightMapColor.g, lightMapColor.b));

			        lightMapColor.rgb *= max(lightLevel, 0.2) / max(lightLevel, 0.0001);
			    }

			    color *= lightMapColor;

			    // Composited after lighting, deliberately - a glowing texel reads at full brightness
			    // regardless of the scene's own light level, the same "ignore the lightmap entirely"
			    // effect AutoGlowingStrutLayer achieves by re-rendering at LightmapTextureManager.MAX_SKY_LIGHT_COORDINATE.
			    // Gated by HasEmissive (AutoGlowingTexture#hasEmissiveLayer, checked once per draw
			    // group) rather than sampled unconditionally - most textures have no glowmask at all,
			    // and Sampler3 is left bound to whatever the previous group's was when this is false,
			    // so sampling it here regardless would be reading arbitrary unrelated texture data
			    if (HasEmissive) {
			        vec4 glow = texture(Sampler3, texCoord);

			        color.rgb = mix(color.rgb, glow.rgb, glow.a);
			    }

			    //@hook fragment_final

			    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
			}
			""";

	private static GpuShaderProgram shader;
	private static boolean initialised;
	private static boolean initFailed;

	private static int projMatLocation;
	private static int modelViewMatLocation;
	private static int samplerLocation;
	private static int overlaySamplerLocation;
	private static int lightmapSamplerLocation;
	private static int emissiveSamplerLocation;
	private static int hasEmissiveLocation;
	private static int debugSeamsLocation;
	private static int alphaPassLocation;
	private static int boneCountLocation;
	private static int skinBoneCountLocation;
	private static int hasSkinLocation;
	private static int hasBoneVisualsLocation;
	private static int hasDeformLocation;
	private static int hasDeformNormalsLocation;
	private static int deformMeshCountLocation;
	private static int deformOffsetBaseLocation;
	private static int deformNormalBaseLocation;
	private static int shadingModeLocation;
	private static int light0Location;
	private static int light1Location;
	private static int fogShapeLocation;
	private static int fogStartLocation;
	private static int fogEndLocation;
	private static int fogColorLocation;
	private static int paramsLocation;
	private static int compiledHooksVersion;

	private GpuRenderBackend() {}

	/**
	 * The shader with the hooks of the mods put in ({@link StrutShaderHooks}). If it does not compile, the shader without them is built instead, the ids of the hooks are
	 * logged and the failure is remembered until the hooks change, so one bad hook costs a log line and not the frame
	 */
	private static GpuShaderProgram compileWithHooks() {
		if (!StrutShaderHooks.any() || StrutShaderHooks.failed())
			return GpuShaderProgram.compile("strut:gpu_entity", StrutShaderHooks.compose(VERTEX_SOURCE, false), StrutShaderHooks.compose(FRAGMENT_SOURCE, false));

		try {
			return GpuShaderProgram.compile("strut:gpu_entity", StrutShaderHooks.compose(VERTEX_SOURCE), StrutShaderHooks.compose(FRAGMENT_SOURCE));
		}
		catch (RuntimeException ex) {
			StrutShaderHooks.markFailed(StrutShaderHooks.version());
			StrutConstants.LOGGER.error("The shader with the hooks of {} did not compile, so they are left out: {}", StrutShaderHooks.ids(), ex.getMessage());

			return GpuShaderProgram.compile("strut:gpu_entity", StrutShaderHooks.compose(VERTEX_SOURCE, false), StrutShaderHooks.compose(FRAGMENT_SOURCE, false));
		}
	}

	/**
	 * Compiles the shared shader on first use and looks up every uniform location, caching failure
	 * so a broken shader is reported once rather than every frame forever after
	 */
	private static boolean ensureInitialised() {
		if (initialised)
			return !initFailed;

		initialised = true;

		try {
			shader = compileWithHooks();
			projMatLocation = shader.uniformLocation("ProjMat");
			modelViewMatLocation = shader.uniformLocation("ModelViewMat");
			samplerLocation = shader.uniformLocation("Sampler0");
			overlaySamplerLocation = shader.uniformLocation("Sampler1");
			lightmapSamplerLocation = shader.uniformLocation("Sampler2");
			emissiveSamplerLocation = shader.uniformLocation("Sampler3");
			hasEmissiveLocation = shader.uniformLocation("HasEmissive");
			debugSeamsLocation = shader.uniformLocation("DebugSeams");
			alphaPassLocation = shader.uniformLocation("AlphaPass");
			boneCountLocation = shader.uniformLocation("BoneCount");
			skinBoneCountLocation = shader.uniformLocation("SkinBoneCount");
			hasSkinLocation = shader.uniformLocation("HasSkin");
			hasBoneVisualsLocation = shader.uniformLocation("HasBoneVisuals");
			hasDeformLocation = shader.uniformLocation("HasDeform");
			hasDeformNormalsLocation = shader.uniformLocation("HasDeformNormals");
			deformMeshCountLocation = shader.uniformLocation("DeformMeshCount");
			deformOffsetBaseLocation = shader.uniformLocation("DeformOffsetBase");
			deformNormalBaseLocation = shader.uniformLocation("DeformNormalBase");
			shadingModeLocation = shader.uniformLocation("ShadingMode");
			light0Location = shader.uniformLocation("Light0_Direction");
			light1Location = shader.uniformLocation("Light1_Direction");
			fogShapeLocation = shader.uniformLocation("FogShape");
			fogStartLocation = shader.uniformLocation("FogStart");
			fogEndLocation = shader.uniformLocation("FogEnd");
			fogColorLocation = shader.uniformLocation("FogColor");
			paramsLocation = shader.uniformLocation("StrutParams[0]");
			compiledHooksVersion = StrutShaderHooks.version();

			return true;
		}
		catch (RuntimeException ex) {
			initFailed = true;

			throw ex;
		}
	}

	/**
	 * Binds the shader and uploads every uniform that is the same for every draw this frame -
	 * projection, the camera's own rotation, the two sampler-unit bindings, sun/sky light
	 * directions, and fog - plus the overlay/lightmap textures themselves, which (unlike a model's
	 * own texture) never change draw to draw within one frame either
	 * <p>
	 * Returns {@code false} (having done nothing) if the shader failed to compile - callers should
	 * skip their own draw work for the frame rather than call {@link #drawInstances}
	 */
	public static boolean beginFrame(WorldRenderContext context) {
		return beginFrame(context.positionMatrix(), context.projectionMatrix());
	}

	/**
	 * {@link #beginFrame(WorldRenderContext)} with the matrices given: for a draw made outside the world's render (an entity drawn into a screen), where the
	 * render system's own are the ones to use
	 */
	/**
	 * Sets how the next draws are shaded (see {@link com.sandymandy.strut.model.ShadingMode}); the uniform stays as it is set until the next call, so each group sets
	 * it before its draws. Raw path only: a shader pack lights a model with its own
	 */
	public static void setShadingMode(ShadingMode mode) {
		if (shader != null && initialised)
			GL20.glUniform1i(shadingModeLocation, mode.ordinal());
	}

	public static boolean beginFrame(Matrix4f positionMatrix, Matrix4f projectionMatrix) {
		// hooks were added or taken out since the shader was built: it is built again
		if (initialised && !initFailed && compiledHooksVersion != StrutShaderHooks.version() && !StrutShaderHooks.failed()) {
			shader.close();
			initialised = false;
		}

		if (!ensureInitialised())
			return false;

		RenderSystem.enableDepthTest();
		RenderSystem.depthFunc(GL11.GL_LEQUAL);
		RenderSystem.depthMask(true);
		disableCullSaving();

		shader.use();
		GpuShaderProgram.uploadMatrix(projMatLocation, projectionMatrix);
		GpuShaderProgram.uploadMatrix(modelViewMatLocation, positionMatrix);
		GL20.glUniform1i(samplerLocation, 0);
		GL20.glUniform1i(debugSeamsLocation, SeamDebug.mode());
		GL20.glUniform1i(overlaySamplerLocation, 1);
		GL20.glUniform1i(lightmapSamplerLocation, 2);
		GL20.glUniform1i(emissiveSamplerLocation, 3);

		GameRenderer gameRenderer = MinecraftClient.getInstance().gameRenderer;
		Vector3f[] lightDirections = RenderSystem.shaderLightDirections;

		GL20.glUniform3f(light0Location, lightDirections[0].x, lightDirections[0].y, lightDirections[0].z);
		GL20.glUniform3f(light1Location, lightDirections[1].x, lightDirections[1].y, lightDirections[1].z);
		if (paramsLocation >= 0)
			GL20.glUniform4fv(paramsLocation, StrutShaderParams.snapshot());

		GL20.glUniform1i(fogShapeLocation, RenderSystem.getShaderFogShape().getId());
		GL20.glUniform1f(fogStartLocation, RenderSystem.getShaderFogStart());
		GL20.glUniform1f(fogEndLocation, RenderSystem.getShaderFogEnd());

		float[] fogColor = RenderSystem.getShaderFogColor();
		GL20.glUniform4f(fogColorLocation, fogColor[0], fogColor[1], fogColor[2], fogColor[3]);

		RenderSystem.activeTexture(GL13.GL_TEXTURE1);
		RenderSystem.bindTexture(gameRenderer.getOverlayTexture().texture.getGlId());
		RenderSystem.activeTexture(GL13.GL_TEXTURE2);
		RenderSystem.bindTexture(gameRenderer.getLightmapTextureManager().texture.getGlId());
		RenderSystem.activeTexture(GL13.GL_TEXTURE0);

		return true;
	}

	/**
	 * Uploads {@code paletteBuffer}/{@code instanceBuffer} to {@code paletteSsbo}/
	 * {@code instanceSsbo} (both already positioned/limited by the caller - an absolute-indexed
	 * palette write leaves position at 0 and limit at exactly what was filled, a relative-write
	 * instance buffer needs its own {@code flip()} first) and issues one
	 * {@code glDrawElementsInstanced} call for {@code instanceCount} copies of {@code model}'s
	 * geometry, textured with {@code texture}
	 * <p>
	 * {@code paletteSsbo}/{@code instanceSsbo}/{@code visualsSsbo} are the caller's own buffer
	 * objects, not owned here - {@link GpuBatchQueue} reuses one shared set for every group each
	 * frame via buffer orphaning (a fresh {@code glBufferData} call on the same target is
	 * well-defined even while a previous draw reading the old contents is still in flight)
	 * <p>
	 * {@code visualsBuffer} is {@code null} exactly when {@code hasBoneVisuals} is {@code false} - no
	 * instance in this group has an active {@code COLOR}/{@code UV_OFFSET} override, so
	 * {@code visualsSsbo} is left with whatever it already held (never read, since
	 * {@code HasBoneVisuals} gates the shader's own fetch) and re-uploading it would just spend
	 * bandwidth on a buffer nothing looks at this draw
	 * <p>
	 * {@code skinBuffer} is {@code null} exactly when {@code model.hasSkinning()} is {@code false} -
	 * The same "skip the upload, leave {@code HasSkin} false" shape
	 * {@code visualsBuffer} already established. {@code skinnedMeshSsbo} is never re-uploaded here at
	 * all (unlike every other buffer this method touches) - it is {@code model}'s own static,
	 * bake-time table, resident on the GPU for this model's whole lifetime
	 */
	public static void drawInstances(GpuModel model, Identifier texture, int paletteSsbo, FloatBuffer paletteBuffer,
									  int instanceSsbo, ByteBuffer instanceBuffer, int visualsSsbo,
									  @Nullable ByteBuffer visualsBuffer, int skinSsbo, @Nullable FloatBuffer skinBuffer,
									  int skinnedMeshSsbo, int skinBoneCount, int instanceCount) {
		drawInstances(model, texture, paletteSsbo, paletteBuffer, instanceSsbo, instanceBuffer, visualsSsbo,
				visualsBuffer, skinSsbo, skinBuffer, skinnedMeshSsbo, skinBoneCount, instanceCount, true);
	}

	/**
	 * {@code rasterize} is {@code false} exactly when {@code IrisCompat.isActive()} - the SSBO upload
	 * still runs (bindings 0-4 are what the Iris path's compute passes read), but the raw
	 * {@code glDrawElementsInstanced} below must not: this path writes colour only, into vanilla's own
	 * framebuffer rather than the pack's, and the pack's own program draws the entity instead
	 */
	public static void drawInstances(GpuModel model, Identifier texture, int paletteSsbo, FloatBuffer paletteBuffer,
									  int instanceSsbo, ByteBuffer instanceBuffer, int visualsSsbo,
									  @Nullable ByteBuffer visualsBuffer, int skinSsbo, @Nullable FloatBuffer skinBuffer,
									  int skinnedMeshSsbo, int skinBoneCount, int instanceCount, boolean rasterize) {
		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, paletteSsbo);
		GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, paletteBuffer, GL15.GL_STREAM_DRAW);
		GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, PALETTE_SSBO_BINDING, paletteSsbo);

		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, instanceSsbo);
		GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, instanceBuffer, GL15.GL_STREAM_DRAW);
		GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, INSTANCE_SSBO_BINDING, instanceSsbo);

		if (visualsBuffer != null) {
			GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, visualsSsbo);
			GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, visualsBuffer, GL15.GL_STREAM_DRAW);
		}

		GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, VISUAL_SSBO_BINDING, visualsSsbo);
		GL20.glUniform1i(hasBoneVisualsLocation, visualsBuffer != null ? 1 : 0);

		if (skinBuffer != null) {
			GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, skinSsbo);
			GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, skinBuffer, GL15.GL_STREAM_DRAW);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SKIN_SSBO_BINDING, skinSsbo);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SKINNED_MESH_SSBO_BINDING, skinnedMeshSsbo);
		}

		GL20.glUniform1i(hasSkinLocation, skinBuffer != null ? 1 : 0);
		GL20.glUniform1i(skinBoneCountLocation, skinBoneCount);

		GL20.glUniform1i(boneCountLocation, model.boneCount());
		bindDeform();

		if (!rasterize)
			return;

		bindTextureAndEmissive(texture);

		GL30.glBindVertexArray(model.vao());

		drawPasses(() -> GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, model.indexCount(), GL11.GL_UNSIGNED_INT, 0L, instanceCount));
		restoreOuterVao();
	}

	/**
	 * Binds {@code texture} to unit 0, and - via {@link AutoGlowingTexture#hasEmissiveLayer} - its
	 * emissive twin to unit 3 if it genuinely has one, setting {@code HasEmissive} to match. Shared by
	 * {@link #drawInstances} and {@link #drawBoneRange}, which both need exactly this before their own
	 * (differently-shaped) draw call
	 * <p>
	 * Not a plain null-check - {@code AutoGlowingTexture#getEmissiveResource} always returns a
	 * registered {@link Identifier} even for a texture with no {@code _glowmask} at all (a
	 * modder-facing convenience), so binding it unconditionally would sample an unloaded,
	 * never-uploaded GL texture for the vast majority of models that were never meant to glow.
	 * Checked, and the texture rebound, every draw rather than cached per group - the same cost class
	 * as the base texture lookup, and correctly picks up a glowmask resource appearing or
	 * disappearing across a reload
	 */
	private static void bindTextureAndEmissive(Identifier texture) {
		// Looked up fresh every draw rather than cached - TextureManager's own map lookup is cheap,
		// and caching the GL id would go stale across a resource reload (F3+T), which reassigns it
		int textureGlId = MinecraftClient.getInstance().getTextureManager().getTexture(texture).getGlId();

		RenderSystem.activeTexture(GL13.GL_TEXTURE0);
		RenderSystem.bindTexture(textureGlId);

		boolean hasEmissive = AutoGlowingTexture.hasEmissiveLayer(texture);

		GL20.glUniform1i(hasEmissiveLocation, hasEmissive ? 1 : 0);

		if (hasEmissive) {
			int emissiveGlId = MinecraftClient.getInstance().getTextureManager().getTexture(AutoGlowingTexture.getEmissiveResource(texture)).getGlId();

			RenderSystem.activeTexture(GL13.GL_TEXTURE3);
			RenderSystem.bindTexture(emissiveGlId);
			RenderSystem.activeTexture(GL13.GL_TEXTURE0);
		}
	}

	/**
	 * Redraws just one bone's own index range, for exactly one instance, with a different texture
	 * bound - a per-bone
	 * {@code TEXTURE} override. Composited over the group's own main {@link #drawInstances} call
	 * (already drawn with the group's default texture) rather than excluding the overridden bone's
	 * geometry from it - both draws' fragments land at identical depth (the same vertices, the same
	 * transform), and {@code GL_LEQUAL} (set once in {@link #beginFrame}, never {@code GL_LESS}) lets
	 * the second draw's fragments pass the depth test and overwrite colour, "painting over" the
	 * base-texture triangles correctly rather than z-fighting against them
	 * <p>
	 * {@code paletteBuffer}/{@code instanceBuffer} hold exactly one instance's own data - {@code Model}'s
	 * bone palette is per-instance, not per-draw-call, so a single-bone, single-instance redraw still
	 * needs a full {@code BoneCount}-sized palette for that one instance (the shader indexes it by
	 * {@code gl_InstanceID * BoneCount + BoneIndex}, and {@code gl_InstanceID} is {@code 0} here
	 * regardless of which instance this actually is). This overload leaves {@code HasBoneVisuals} {@code false}: the redraw of a
	 * {@code TEXTURE} override uses the overload below with the instance's own visuals, so the bone keeps its tint, UV offset and
	 * {@code UV_SIZE} scale
	 * <p>
	 * {@code skinBuffer} is this same one instance's own concatenated skin matrices (not the group's
	 * multi-instance one {@link #drawInstances} uploaded) - needed because of a gap
	 * this method used to have: {@code gl_InstanceID} is {@code 0} here regardless of which instance
	 * is actually being redrawn, so leaving the main draw's own multi-instance buffer bound would
	 * have read instance 0's skin matrices for every redraw instead of this instance's own. {@code null}
	 * exactly when {@code model.hasSkinning()} is {@code false}, the same shape {@link #drawInstances}
	 * already established
	 */
	public static void drawBoneRange(GpuModel model, Identifier texture, int paletteSsbo, FloatBuffer paletteBuffer,
									  int instanceSsbo, ByteBuffer instanceBuffer, int skinSsbo,
									  @Nullable FloatBuffer skinBuffer, int skinnedMeshSsbo, int skinBoneCount,
									  int indexStart, int indexCount) {
		drawBoneRange(model, texture, paletteSsbo, paletteBuffer, instanceSsbo, instanceBuffer, skinSsbo, skinBuffer, skinnedMeshSsbo,
				skinBoneCount, indexStart, indexCount, 0, null);
	}

	/**
	 * As above, carrying this one instance's own per-bone {@code COLOR}/{@code UV_OFFSET} ({@code visualsBuffer}, one instance's worth,
	 * or null for none) - what an overlay needs to keep its bone's tint and UV offset
	 */
	public static void drawBoneRange(GpuModel model, Identifier texture, int paletteSsbo, FloatBuffer paletteBuffer,
									  int instanceSsbo, ByteBuffer instanceBuffer, int skinSsbo,
									  @Nullable FloatBuffer skinBuffer, int skinnedMeshSsbo, int skinBoneCount,
									  int indexStart, int indexCount, int visualsSsbo, @Nullable ByteBuffer visualsBuffer) {
		drawBoneRange(model, texture, paletteSsbo, paletteBuffer, instanceSsbo, instanceBuffer, skinSsbo, skinBuffer, skinnedMeshSsbo, skinBoneCount, indexStart, indexCount,
				visualsSsbo, visualsBuffer, 1);
	}

	/**
	 * As above for {@code instanceCount} instances at once: the palette, instance, skin and visuals buffers hold that many instances' worth, and one draw
	 * covers them all (what the texture redraw of many instances sharing a skin needs, in one call instead of one for each)
	 */
	public static void drawBoneRange(GpuModel model, Identifier texture, int paletteSsbo, FloatBuffer paletteBuffer,
									  int instanceSsbo, ByteBuffer instanceBuffer, int skinSsbo,
									  @Nullable FloatBuffer skinBuffer, int skinnedMeshSsbo, int skinBoneCount,
									  int indexStart, int indexCount, int visualsSsbo, @Nullable ByteBuffer visualsBuffer, int instanceCount) {
		if (visualsBuffer != null) {
			GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, visualsSsbo);
			GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, visualsBuffer, GL15.GL_STREAM_DRAW);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, VISUAL_SSBO_BINDING, visualsSsbo);
		}

		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, paletteSsbo);
		GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, paletteBuffer, GL15.GL_STREAM_DRAW);
		GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, PALETTE_SSBO_BINDING, paletteSsbo);

		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, instanceSsbo);
		GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, instanceBuffer, GL15.GL_STREAM_DRAW);
		GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, INSTANCE_SSBO_BINDING, instanceSsbo);

		if (skinBuffer != null) {
			GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, skinSsbo);
			GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, skinBuffer, GL15.GL_STREAM_DRAW);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SKIN_SSBO_BINDING, skinSsbo);
			GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SKINNED_MESH_SSBO_BINDING, skinnedMeshSsbo);
		}

		GL20.glUniform1i(hasSkinLocation, skinBuffer != null ? 1 : 0);
		GL20.glUniform1i(skinBoneCountLocation, skinBoneCount);
		GL20.glUniform1i(hasBoneVisualsLocation, visualsBuffer != null ? 1 : 0);
		GL20.glUniform1i(boneCountLocation, model.boneCount());
		bindDeform();
		bindTextureAndEmissive(texture);

		GL30.glBindVertexArray(model.vao());

		drawPasses(() -> GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, (long)indexStart * Integer.BYTES, instanceCount));
		restoreOuterVao();
	}

	/**
	 * Uploads {@code pack} (see {@link MeshDeformPack}) as the deformation every draw of {@code model} from here applies, until {@link #clearDeform()}: the
	 * group's buffer is replaced (orphaned) like the others, and bound with the model's own mesh table. With a null {@code pack} nothing is deformed. Call it
	 * while this backend's program is bound
	 */
	public static void setDeform(GpuModel model, @Nullable MeshDeformPack pack, int dataSsbo) {
		if (pack == null || !model.hasMeshes()) {
			MeshDeformState.active = false;
			GL20.glUniform1i(hasDeformLocation, 0);

			return;
		}

		GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, dataSsbo);
		GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, pack.data(), GL15.GL_STREAM_DRAW);

		MeshDeformState.active = true;
		MeshDeformState.dataSsbo = dataSsbo;
		MeshDeformState.meshSsbo = model.deformMeshSsbo();
		MeshDeformState.streamVbo = model.deformVbo();
		MeshDeformState.meshCount = pack.meshCount();
		MeshDeformState.offsetBase = pack.offsetBase();
		MeshDeformState.normalBase = pack.normalBase();
		MeshDeformState.hasNormals = pack.hasNormals();
		bindDeform();
	}

	/** Forgets the deformation set by {@link #setDeform}: the next group has its own */
	public static void clearDeform() {
		MeshDeformState.active = false;

		if (hasDeformLocation >= 0 && shader != null)
			GL20.glUniform1i(hasDeformLocation, 0);
	}

	/**
	 * Binds the group's deformation buffers and sets what the shader reads about them, every draw: a shader pack's passes between two draws put their own
	 * buffers on the same binding points, and a draw that comes after must find its own
	 */
	private static void bindDeform() {
		if (!MeshDeformState.active) {
			GL20.glUniform1i(hasDeformLocation, 0);

			return;
		}

		GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, DEFORM_SSBO_BINDING, MeshDeformState.dataSsbo);
		GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, DEFORM_MESH_SSBO_BINDING, MeshDeformState.meshSsbo);
		GL20.glUniform1i(hasDeformLocation, 1);
		GL20.glUniform1i(hasDeformNormalsLocation, MeshDeformState.hasNormals ? 1 : 0);
		GL20.glUniform1i(deformMeshCountLocation, MeshDeformState.meshCount);
		GL20.glUniform1i(deformOffsetBaseLocation, MeshDeformState.offsetBase);
		GL20.glUniform1i(deformNormalBaseLocation, MeshDeformState.normalBase);
	}

	/**
	 * Puts the GL state for a blended pass in place: standard alpha blending, the polygon offset {@code bias} asks for, and depth writes
	 * on or off. A whole translucent model writes depth (vanilla's own translucent entity does); a translucent bone or an overlay does
	 * not, so the surface it sits on is not occluded by it. {@code cullBackFaces} drops the faces turned away from the camera, which for a closed cube removes its inside faces that a blend would otherwise show. Pair with {@link #endBlendPass}
	 */
	public static void beginBlendPass(DepthBias bias, boolean writeDepth, boolean cullBackFaces) {
		beginBlendPass(bias, writeDepth, cullBackFaces, false);
	}

	/**
	 * {@link #beginBlendPass(com.sandymandy.strut.model.DepthBias, boolean, boolean)}, adding the draw to what is under it when {@code additive}: the source
	 * colour, scaled by its alpha, is summed into the frame (an enchantment's glint)
	 */
	public static void beginBlendPass(DepthBias bias, boolean writeDepth, boolean cullBackFaces, boolean additive) {
		depthPrepass = false;
		if (cullBackFaces)
			RenderSystem.enableCull();

		RenderSystem.enableBlend();

		if (additive)
			// the game's glint blend: the texture's colour, scaled by its own, summed into the frame (a dark texture adds little: the sheen is only its bright parts)
			RenderSystem.blendFuncSeparate(GL11.GL_SRC_COLOR, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
		else
			RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		RenderSystem.depthMask(writeDepth);
		RenderSystem.enablePolygonOffset();
		RenderSystem.polygonOffset(bias.factor(), bias.units());
	}

	/**
	 * Whether the next draws in this blended pass first write their nearest surface's depth (no colour), then blend only that surface. Without
	 * it the blended faces of one mesh, which write no depth of their own, land in mesh order and a back face drawn late covers a front face.
	 * Set per draw, so an overlay (same geometry as its bone, already depth-tested against it) can leave it off.
	 */
	public static void setDepthPrepass(boolean enabled) {
		depthPrepass = enabled;
	}

	private static boolean depthPrepass;

	/**
	 * The vertex array that was bound when this backend's caller started (vanilla's, or Iris's, mid-draw), put back after every draw instead of
	 * VAO 0. Vanilla's buffer renderer can assume its own vertex array is still bound and skip re-binding it, so leaving 0 bound made a later
	 * vanilla or Iris draw read its indices from address 0: a native crash inside the driver, in Iris's translucent entity draw, seen on the
	 * live client
	 */
	private static int outerVao;

	/**
	 * Records the currently bound vertex array. Call once, before any draw of a flush, while the caller's own binding is still current
	 */
	public static void saveOuterVao() {
		outerVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
	}

	/**
	 * Binds the vertex array {@link #saveOuterVao} recorded
	 */
	public static void restoreOuterVao() {
		GL30.glBindVertexArray(outerVao);
	}

	/**
	 * Makes one draw of the bound geometry: once as it is, or, in a blended pass with the depth prepass on, in three steps by what each fragment is. First the opaque
	 * fragments, with colour and depth, so what is behind them is hidden. Then the depth of the nearest of the rest (no colour), and last those, blended, where
	 * that depth is: a see-through shell over an opaque core shows the core through it (the core is in front of the shell's far side and behind its near side), and
	 * of the see-through faces only the nearest shows, so a closed shell does not show its own back
	 */
	private static void drawPasses(Runnable draw) {
		if (!depthPrepass) {
			GL20.glUniform1i(alphaPassLocation, 0);
			draw.run();

			return;
		}

		RenderSystem.depthMask(true);
		GL20.glUniform1i(alphaPassLocation, 1);
		draw.run();

		GL20.glUniform1i(alphaPassLocation, 2);
		prepassOn();
		draw.run();
		prepassOff();

		draw.run();
		GL20.glUniform1i(alphaPassLocation, 0);
	}

	private static void prepassOn() {
		RenderSystem.colorMask(false, false, false, false);
		RenderSystem.depthMask(true);
	}

	private static void prepassOff() {
		RenderSystem.depthMask(false);
		RenderSystem.colorMask(true, true, true, true);
	}

	/**
	 * Restores what {@link #beginFrame} set: no blending, depth writes on, no polygon offset
	 */
	public static void endBlendPass() {
		depthPrepass = false;
		// beginFrame leaves culling off for every GPU draw; a pass that turned it on puts it back
		RenderSystem.disableCull();
		RenderSystem.polygonOffset(0f, 0f);
		RenderSystem.disablePolygonOffset();
		RenderSystem.depthMask(true);
		RenderSystem.depthFunc(GL11.GL_LEQUAL);
		RenderSystem.disableBlend();
	}

	/**
	 * Backface culling off for the duration of a GPU draw, restoring whatever was set before - the
	 * CPU walk draws through {@code RenderLayer#getEntityCutoutNoCull}, whose own render state disables
	 * culling, but neither GPU path ever went through a render layer's state setup, so they drew with
	 * whatever culling state happened to be current (normally on). Any face seen from behind - a
	 * single-sided plane, an inward-wound mesh face - rendered on CPU and silently vanished on GPU.
	 * Saved and restored rather than re-enabled unconditionally: Iris's own shadow pass runs with
	 * culling off and the shadow-queue flush happens in the middle of it
	 */
	public static void disableCullSaving() {
		cullWasEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);

		RenderSystem.disableCull();
	}

	public static void restoreCull() {
		if (cullWasEnabled)
			RenderSystem.enableCull();
	}

	private static boolean cullWasEnabled;

	public static void endFrame() {
		restoreCull();
		GpuShaderProgram.unbind();
	}

	/**
	 * Re-binds this backend's own shader program after something else (the Iris pose/pack passes and
	 * main draw) bound a different one mid-frame
	 * - every uniform {@link #beginFrame} already set stays live on this program regardless of what
	 * was current in between, so nothing needs re-uploading, only re-binding
	 */
	public static boolean rebind() {
		if (shader == null)
			return false;

		shader.use();

		return true;
	}
}
