package com.sandymandy.strut.loading.object;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.cache.object.*;
import com.sandymandy.strut.loading.json.raw.*;
import com.sandymandy.strut.util.StrutUtil;
import com.sandymandy.strut.util.RenderUtil;

import java.util.List;
import java.util.Map;

/**
 * Base interface for a factory of {@link BakedStrutModel} objects
 * <p>
 * Handled by default by Strut, but custom implementations may be added by other mods for special needs
 */
public interface BakedModelFactory {
	Map<String, BakedModelFactory> FACTORIES = new Object2ObjectOpenHashMap<>(1);
	BakedModelFactory DEFAULT_FACTORY = new Builtin();

	/**
	 * Construct the output model from the given {@link GeometryTree}.<br>
	 */
	BakedStrutModel constructStrutModel(GeometryTree geometryTree);

	/**
	 * Construct a {@link GroupBoneDefinition} from the relevant raw input data
	 *
	 * @param boneStructure The {@code BoneStructure} comprising the structure of the bone and its children
	 * @param properties The loaded properties for the model
	 * @param parent The parent bone for this bone, or null if a top-level bone
	 */
	GroupBoneDefinition constructBone(BoneStructure boneStructure, ModelProperties properties, @Nullable GroupBoneDefinition parent);

	/**
	 * Construct a {@link StrutCube} from the relevant raw input data
	 *
	 * @param cube The raw {@code Cube} comprising the structure and properties of the cube
	 * @param properties The loaded properties for the model
	 * @param bone The bone this cube belongs to
	 */
	StrutCube constructCube(Cube cube, ModelProperties properties, GroupBoneDefinition bone);

	/**
	 * Builtin method to construct the quad list from the various vertices and related data, to make it easier
	 * <p>
	 * Vertices have already been mirrored here if {@code mirror} is true
	 */
	default StrutQuad[] buildQuads(UVUnion uvUnion, VertexSet vertices, Cube cube, float textureWidth, float textureHeight, boolean mirror) {
		StrutQuad[] quads = new StrutQuad[6];

		quads[0] = buildQuad(vertices, cube, uvUnion, textureWidth, textureHeight, mirror, Direction.WEST);
		quads[1] = buildQuad(vertices, cube, uvUnion, textureWidth, textureHeight, mirror, Direction.EAST);
		quads[2] = buildQuad(vertices, cube, uvUnion, textureWidth, textureHeight, mirror, Direction.NORTH);
		quads[3] = buildQuad(vertices, cube, uvUnion, textureWidth, textureHeight, mirror, Direction.SOUTH);
		quads[4] = buildQuad(vertices, cube, uvUnion, textureWidth, textureHeight, mirror, Direction.UP);
		quads[5] = buildQuad(vertices, cube, uvUnion, textureWidth, textureHeight, mirror, Direction.DOWN);

		return quads;
	}

	/**
	 * Build an individual quad
	 */
	default StrutQuad buildQuad(VertexSet vertices, Cube cube, UVUnion uvUnion, float textureWidth, float textureHeight, boolean mirror, Direction direction) {
		if (!uvUnion.isBoxUV()) {
			FaceUV faceUV = uvUnion.faceUV().fromDirection(direction);

			if (faceUV == null)
				return null;

			return StrutQuad.build(vertices.verticesForQuad(direction, false, mirror || cube.mirror() == Boolean.TRUE), faceUV.uv(), faceUV.uvSize(),
					faceUV.uvRotation(), textureWidth, textureHeight, mirror, direction);
		}

		double[] uv = cube.uv().boxUVCoords();
		double[] uvSize = cube.size();
		Vec3d uvSizeVec = new Vec3d(Math.floor(uvSize[0]), Math.floor(uvSize[1]), Math.floor(uvSize[2]));
		double[][] uvData = switch(direction) {
			case WEST -> new double[][] {
					new double[] {uv[0] + uvSizeVec.z + uvSizeVec.x, uv[1] + uvSizeVec.z},
					new double[] {uvSizeVec.z, uvSizeVec.y}
			};
			case EAST -> new double[][] {
					new double[] { uv[0], uv[1] + uvSizeVec.z },
					new double[] { uvSizeVec.z, uvSizeVec.y }
			};
			case NORTH -> new double[][] {
					new double[] {uv[0] + uvSizeVec.z, uv[1] + uvSizeVec.z},
					new double[] {uvSizeVec.x, uvSizeVec.y}
			};
			case SOUTH -> new double[][] {
					new double[] {uv[0] + uvSizeVec.z + uvSizeVec.x + uvSizeVec.z, uv[1] + uvSizeVec.z},
					new double[] {uvSizeVec.x, uvSizeVec.y }
			};
			case UP -> new double[][] {
					new double[] {uv[0] + uvSizeVec.z, uv[1]},
					new double[] {uvSizeVec.x, uvSizeVec.z}
			};
			case DOWN -> new double[][] {
					new double[] {uv[0] + uvSizeVec.z + uvSizeVec.x, uv[1] + uvSizeVec.z},
					new double[] {uvSizeVec.x, -uvSizeVec.z}
			};
		};

		return StrutQuad.build(vertices.verticesForQuad(direction, true, mirror || cube.mirror() == Boolean.TRUE), uvData[0], uvData[1], FaceUV.Rotation.NONE, textureWidth, textureHeight, mirror, direction);
	}

	static BakedModelFactory getForNamespace(String namespace) {
		return FACTORIES.getOrDefault(namespace, DEFAULT_FACTORY);
	}

	/**
	 * Register a custom {@link BakedModelFactory} to handle loading models in a custom way
	 * <p>
	 * <b><u>MUST be called during mod construct</u></b>
	 * <p>
	 * It is recommended you don't call this directly, and instead call it via {@link StrutUtil#addCustomBakedModelFactory}
	 *
	 * @param namespace The namespace (modid) to register the factory for
	 * @param factory The factory responsible for model loading under the given namespace
	 */
	static void register(String namespace, BakedModelFactory factory) {
		FACTORIES.put(namespace, factory);
	}

	final class Builtin implements BakedModelFactory {
		@Override
		public BakedStrutModel constructStrutModel(GeometryTree geometryTree) {
			List<GroupBoneDefinition> bones = new ObjectArrayList<>();

			for (BoneStructure boneStructure : geometryTree.topLevelBones().values()) {
				bones.add(constructBone(boneStructure, geometryTree.properties(), null));
			}

			return new BakedStrutModel(bones, geometryTree.properties());
		}

		@Override
		public GroupBoneDefinition constructBone(BoneStructure boneStructure, ModelProperties properties, GroupBoneDefinition parent) {
			Bone bone = boneStructure.self();
			GroupBoneDefinition newBone = new GroupBoneDefinition(parent, bone.name(), bone.mirror(), bone.inflate(), bone.neverRender(), bone.reset());
			Vec3d rotation = RenderUtil.arrayToVec(bone.rotation());
			Vec3d pivot = RenderUtil.arrayToVec(bone.pivot());

			newBone.bakeInitialRotation((float)Math.toRadians(-rotation.x), (float)Math.toRadians(-rotation.y), (float)Math.toRadians(rotation.z));
			newBone.bakePivot((float)-pivot.x, (float)pivot.y, (float)pivot.z);

			for (Cube cube : bone.cubes()) {
				newBone.getCubes().add(constructCube(cube, properties, newBone));
			}

			for (BoneStructure child : boneStructure.children().values()) {
				newBone.getChildBones().add(constructBone(child, properties, newBone));
			}

			return newBone;
		}

		@Override
		public StrutCube constructCube(Cube cube, ModelProperties properties, GroupBoneDefinition bone) {
			boolean mirror = cube.mirror() == Boolean.TRUE;
			double inflate = cube.inflate() != null ? cube.inflate() / 16f : (bone.getInflate() == null ? 0 : bone.getInflate() / 16f);
			Vec3d size = RenderUtil.arrayToVec(cube.size());
			Vec3d origin = RenderUtil.arrayToVec(cube.origin());
			Vec3d rotation = RenderUtil.arrayToVec(cube.rotation());
			Vec3d pivot = RenderUtil.arrayToVec(cube.pivot());
			origin = new Vec3d(-(origin.x + size.x) / 16d, origin.y / 16d, origin.z / 16d);
			Vec3d vertexSize = size.multiply(1 / 16d, 1 / 16d, 1 / 16d);

			pivot = pivot.multiply(-1, 1, 1);
			rotation = new Vec3d(Math.toRadians(-rotation.x), Math.toRadians(-rotation.y), Math.toRadians(rotation.z));
			StrutQuad[] quads = buildQuads(cube.uv(), new VertexSet(origin, vertexSize, inflate), cube, (float)properties.textureWidth(), (float)properties.textureHeight(), mirror);

			return new StrutCube(quads, pivot, rotation, size, inflate, mirror);
		}
	}

	/**
	 * Holder class to make it easier to store and refer to vertices for a given cube
	 */
	record VertexSet(StrutVertex bottomLeftBack, StrutVertex bottomRightBack, StrutVertex topLeftBack, StrutVertex topRightBack,
							StrutVertex topLeftFront, StrutVertex topRightFront, StrutVertex bottomLeftFront, StrutVertex bottomRightFront) {
		public VertexSet(Vec3d origin, Vec3d vertexSize, double inflation) {
			this(
					new StrutVertex(origin.x - inflation, origin.y - inflation, origin.z - inflation),
					new StrutVertex(origin.x - inflation, origin.y - inflation, origin.z + vertexSize.z + inflation),
					new StrutVertex(origin.x - inflation, origin.y + vertexSize.y + inflation, origin.z - inflation),
					new StrutVertex(origin.x - inflation, origin.y + vertexSize.y + inflation, origin.z + vertexSize.z + inflation),
					new StrutVertex(origin.x + vertexSize.x + inflation, origin.y + vertexSize.y + inflation, origin.z - inflation),
					new StrutVertex(origin.x + vertexSize.x + inflation, origin.y + vertexSize.y + inflation, origin.z + vertexSize.z + inflation),
					new StrutVertex(origin.x + vertexSize.x + inflation, origin.y - inflation, origin.z - inflation),
					new StrutVertex(origin.x + vertexSize.x + inflation, origin.y - inflation, origin.z + vertexSize.z + inflation));
		}

		/**
		 * Returns the normal vertex array for a west-facing quad
		 */
		public StrutVertex[] quadWest() {
			return new StrutVertex[] {this.topRightBack, this.topLeftBack, this.bottomLeftBack, this.bottomRightBack};
		}

		/**
		 * Returns the normal vertex array for an east-facing quad
		 */
		public StrutVertex[] quadEast() {
			return new StrutVertex[] {this.topLeftFront, this.topRightFront, this.bottomRightFront, this.bottomLeftFront};
		}

		/**
		 * Returns the normal vertex array for a north-facing quad
		 */
		public StrutVertex[] quadNorth() {
			return new StrutVertex[] {this.topLeftBack, this.topLeftFront, this.bottomLeftFront, this.bottomLeftBack};
		}

		/**
		 * Returns the normal vertex array for a south-facing quad
		 */
		public StrutVertex[] quadSouth() {
			return new StrutVertex[] {this.topRightFront, this.topRightBack, this.bottomRightBack, this.bottomRightFront};
		}

		/**
		 * Returns the normal vertex array for a top-facing quad
		 */
		public StrutVertex[] quadUp() {
			return new StrutVertex[] {this.topRightBack, this.topRightFront, this.topLeftFront, this.topLeftBack};
		}

		/**
		 * Returns the normal vertex array for a bottom-facing quad
		 */
		public StrutVertex[] quadDown() {
			return new StrutVertex[] {this.bottomLeftBack, this.bottomLeftFront, this.bottomRightFront, this.bottomRightBack};
		}

		/**
		 * Return the vertex array relevant to the quad being built, taking into account mirroring and quad type
		 */
		public StrutVertex[] verticesForQuad(Direction direction, boolean boxUv, boolean mirror) {
			return switch (direction) {
				case WEST -> mirror ? quadEast() : quadWest();
				case EAST -> mirror ? quadWest() : quadEast();
				case NORTH -> quadNorth();
				case SOUTH -> quadSouth();
				case UP -> mirror && !boxUv ? quadDown() : quadUp();
				case DOWN -> mirror && !boxUv ? quadUp() : quadDown();
			};
		}
	}
}
