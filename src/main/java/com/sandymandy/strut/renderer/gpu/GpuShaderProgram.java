package com.sandymandy.strut.renderer.gpu;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;

/**
 * A minimal GLSL vertex+fragment program, compiled and linked directly through LWJGL rather than
 * through Minecraft's own {@code ShaderProgram}, because
 * that class is built around vanilla's JSON shader-definition format and a fixed
 * uniform set (view/projection/fog/light direction and so on), none of which this engine's
 * instanced, SSBO-driven draw needs or wants to route through
 * <p>
 * Deliberately the smallest thing that can compile a shader and report a real error if it
 * doesn't - {@link #compileStage} and {@code #link} both read the GL info log and fail loudly
 * rather than silently drawing nothing, which is the failure mode a broken shader would otherwise
 * produce (an empty program object binds and draws without error, it just outputs nothing)
 */
public final class GpuShaderProgram implements AutoCloseable {
	private final int program;

	private GpuShaderProgram(int program) {
		this.program = program;
	}

	public static GpuShaderProgram compile(String name, String vertexSource, String fragmentSource) {
		int vertexShader = compileStage(name, GL20.GL_VERTEX_SHADER, "vertex", vertexSource);
		int fragmentShader = compileStage(name, GL20.GL_FRAGMENT_SHADER, "fragment", fragmentSource);
		int program = GL20.glCreateProgram();

		GL20.glAttachShader(program, vertexShader);
		GL20.glAttachShader(program, fragmentShader);
		GL20.glLinkProgram(program);

		if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL20.GL_FALSE) {
			String log = GL20.glGetProgramInfoLog(program);

			GL20.glDeleteProgram(program);
			GL20.glDeleteShader(vertexShader);
			GL20.glDeleteShader(fragmentShader);

			throw new RuntimeException("\"" + name + "\" failed to link as a GPU shader program: " + log);
		}

		// A linked program keeps its own copy of each shader stage's compiled code, so the
		// standalone shader objects are safe to delete immediately after linking
		GL20.glDeleteShader(vertexShader);
		GL20.glDeleteShader(fragmentShader);

		return new GpuShaderProgram(program);
	}

	/**
	 * Compiles and links a single-stage compute program
	 * ({@code IrisPrePass}), the first user of a program that isn't
	 * a vertex+fragment pair. {@link #use}/{@link #uniformLocation}/{@link #close} all work
	 * identically on the result; only compilation itself needed a second entry point, since a
	 * compute shader has one stage instead of two
	 */
	public static GpuShaderProgram compileCompute(String name, String computeSource) {
		int computeShader = compileStage(name, GL43.GL_COMPUTE_SHADER, "compute", computeSource);
		int program = GL20.glCreateProgram();

		GL20.glAttachShader(program, computeShader);
		GL20.glLinkProgram(program);

		if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL20.GL_FALSE) {
			String log = GL20.glGetProgramInfoLog(program);

			GL20.glDeleteProgram(program);
			GL20.glDeleteShader(computeShader);

			throw new RuntimeException("\"" + name + "\" failed to link as a GPU compute program: " + log);
		}

		GL20.glDeleteShader(computeShader);

		return new GpuShaderProgram(program);
	}

	private static int compileStage(String name, int stageType, String stageName, String source) {
		int shader = GL20.glCreateShader(stageType);

		GL20.glShaderSource(shader, source);
		GL20.glCompileShader(shader);

		if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL20.GL_FALSE) {
			String log = GL20.glGetShaderInfoLog(shader);

			GL20.glDeleteShader(shader);

			throw new RuntimeException("\"" + name + "\" failed to compile the " + stageName + " stage of a GPU shader: " + log);
		}

		return shader;
	}

	public void use() {
		GL20.glUseProgram(this.program);
	}

	public static void unbind() {
		GL20.glUseProgram(0);
	}

	public int uniformLocation(String name) {
		return GL20.glGetUniformLocation(this.program, name);
	}

	/**
	 * Uploads {@code matrix} to the uniform at {@code location}, via a stack-allocated scratch
	 * buffer rather than a heap allocation - this runs every frame a GPU-backed model draws, so it
	 * follows the same "no allocation on the render path" rule as the rest of this engine
	 */
	public static void uploadMatrix(int location, Matrix4f matrix) {
		if (location < 0)
			return;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			FloatBuffer buffer = stack.mallocFloat(16);

			matrix.get(buffer);
			GL20.glUniformMatrix4fv(location, false, buffer);
		}
	}

	@Override
	public void close() {
		GL20.glDeleteProgram(this.program);
	}
}
