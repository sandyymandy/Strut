package com.sandymandy.strut.renderer.gpu;

/**
 * The GLSL that works out a flesh vertex's offset and shading change from its region's cage (see {@code CageDeform}), shared by the vertex shader and the pose
 * pass of a shader pack so the two cannot differ. It reads the group's deform buffer as {@code DeformWords} with the offsets starting at {@code DeformOffsetBase},
 * which both declare, and the table of what never changes ({@code CageBindings}) on its own binding
 * <p>
 * The same arithmetic as {@code FleshInstance#displace}, which {@code CageDeformTest} holds it to
 */
public final class CageGlsl {
	/** The binding point of the table of what never changes, in both shaders */
	public static final int BINDING = 12;

	/** Declarations and the function; goes after the deform buffer and its uniforms are declared */
	public static final String SOURCE = """

			// Flesh (CageDeform): the table of what never changes for each welded particle, and the function that works a particle's offset and the change of its
			// normal out from the cage's numbers in the group's deform buffer
			layout(std430, binding = 12) readonly buffer CageBindingBuffer {
			    int CageWords[];
			};

			float cageDeformFloat(int at) {
			    return intBitsToFloat(DeformWords[at]);
			}

			float cageBindingFloat(int at) {
			    return intBitsToFloat(CageWords[at]);
			}

			vec3 cageDeformVec(int at) {
			    return vec3(cageDeformFloat(at), cageDeformFloat(at + 1), cageDeformFloat(at + 2));
			}

			// slot is where the mesh's record starts (its header, then one block of 1024 words for each region); particle is the welded particle of the corner
			void cageDeform(int slot, int particle, out vec3 offset, out vec3 normalChange) {
			    offset = vec3(0.0);
			    normalChange = vec3(0.0);

			    int bind = DeformWords[DeformOffsetBase + slot * 4] + particle * 20;
			    int region = CageWords[bind];

			    if (region < 0) {
			        return;
			    }

			    int count = CageWords[bind + 1];
			    int block = DeformOffsetBase + (slot + 1 + region * 256) * 4;
			    int nodes = block + 48;
			    int normals = block + 537;
			    float surface = cageBindingFloat(bind + 14);
			    float soft = cageBindingFloat(bind + 15);
			    vec3 q = vec3(cageBindingFloat(bind + 16), cageBindingFloat(bind + 17), cageBindingFloat(bind + 18));
			    vec3 blended = vec3(0.0);
			    vec3 turned = vec3(0.0);

			    for (int j = 0; j < 6; j++) {
			        if (j >= count) {
			            break;
			        }

			        int node = CageWords[bind + 2 + j];
			        float w = cageBindingFloat(bind + 8 + j);

			        blended += w * cageDeformVec(nodes + node * 3);
			        turned += w * cageDeformVec(normals + node * 3);
			    }

			    vec3 d = blended * surface + cageDeformVec(nodes + 162 * 3) * (1.0 - surface);
			    int flags = int(cageDeformFloat(block + 9) + 0.5);
			    vec3 swing = cageDeformVec(block + 10);
			    vec3 pivot = cageDeformVec(block + 13);
			    vec3 middle = cageDeformVec(block + 16);

			    if ((flags & 1) != 0) {
			        // turned about the pivot rather than slid: the swing the nodes already carry is taken back out
			        vec3 r = q - pivot;
			        vec3 turnedR = vec3(dot(cageDeformVec(block + 19), r), dot(cageDeformVec(block + 22), r), dot(cageDeformVec(block + 25), r));
			        float held = min(1.0, 2.0 * soft);

			        d += (turnedR - r) * held - swing * soft;
			    }

			    if ((flags & 2) != 0) {
			        vec3 r = q - middle;

			        d += vec3(dot(cageDeformVec(block + 28), r), dot(cageDeformVec(block + 31), r), dot(cageDeformVec(block + 34), r)) * soft;
			    }

			    if ((flags & 8) != 0) {
			        // a top holds it in: it may not swell out past where it is modelled by more than a little
			        vec3 r = q - middle;
			        float length = sqrt(dot(r, r));

			        if (length > 1e-9) {
			            float outward = dot(d, r) / length;
			            float room = cageDeformFloat(block + 46);

			            if (outward > room) {
			                d -= r * ((outward - room) / length);
			            }
			        }
			    }

			    if ((flags & 4) != 0) {
			        // flattened against the other of the pair with a soft knee, and what is flattened pushed out along the plane at the rim
			        vec3 p = cageDeformVec(block + 37);
			        vec3 n = cageDeformVec(block + 40);
			        float allow = cageDeformFloat(block + 43);
			        float edge = cageDeformFloat(block + 44);
			        float past = dot(q + d - p, n) - allow;

			        if (past > 0.0) {
			            float kept = past < 2.0 * edge ? past - past * past / (4.0 * edge) : edge;
			            float flattened = past - kept;

			            d -= n * flattened;

			            vec3 o = q + d - p;

			            o -= dot(o, n) * n;

			            float radial = sqrt(dot(o, o));

			            if (radial > 1e-9) {
			                d += o * (cageDeformFloat(block + 45) * flattened / radial);
			            }
			        }
			    }

			    d *= cageDeformFloat(block + 47);

			    // into the mesh's own space: the rows of the matrix
			    vec3 row0 = cageDeformVec(block);
			    vec3 row1 = cageDeformVec(block + 3);
			    vec3 row2 = cageDeformVec(block + 6);

			    offset = vec3(dot(row0, d), dot(row1, d), dot(row2, d));
			    normalChange = vec3(dot(row0, turned), dot(row1, turned), dot(row2, turned));
			}
			""";

	private CageGlsl() {}
}
