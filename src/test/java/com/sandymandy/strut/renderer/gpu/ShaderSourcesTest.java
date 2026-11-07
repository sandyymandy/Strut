package com.sandymandy.strut.renderer.gpu;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shaders' sources, compiled by the reference GLSL front end ({@code glslangValidator}, from the Vulkan SDK) when it is on the machine: a typo or a type
 * error in a shader is found here and not by the game, where it would be every model failing to draw. Skipped where the tool is not found
 */
class ShaderSourcesTest {
	private static Path validator() {
		String sdk = System.getenv("VULKAN_SDK");
		List<Path> candidates = new ArrayList<>();

		if (sdk != null)
			candidates.add(Path.of(sdk, "Bin", "glslangValidator.exe"));

		candidates.add(Path.of("C:/VulkanSDK/1.4.350.0/Bin/glslangValidator.exe"));

		for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
			candidates.add(Path.of(dir, "glslangValidator.exe"));
			candidates.add(Path.of(dir, "glslangValidator"));
		}

		for (Path candidate : candidates) {
			if (Files.isExecutable(candidate))
				return candidate;
		}

		return null;
	}

	private static String source(String className, String field) throws Exception {
		Field f = Class.forName(className).getDeclaredField(field);

		f.setAccessible(true);

		return (String)f.get(null);
	}

	private static void compile(Path tool, String name, String stage, String source) throws Exception {
		Path dir = Files.createTempDirectory("strut-glsl");
		Path file = dir.resolve(name + "." + stage);

		Files.writeString(file, source, StandardCharsets.UTF_8);

		Process process = new ProcessBuilder(tool.toString(), file.toString()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

		assertTrue(process.waitFor(60, TimeUnit.SECONDS), name + " did not finish");
		assertTrue(process.exitValue() == 0, name + " does not compile:\n" + output);
	}

	@Test
	void everyShaderCompiles() throws Exception {
		Path tool = validator();

		Assumptions.assumeTrue(tool != null, "glslangValidator is not installed");

		compile(tool, "vertex", "vert", source("com.sandymandy.strut.renderer.gpu.GpuRenderBackend", "VERTEX_SOURCE"));
		compile(tool, "fragment", "frag", source("com.sandymandy.strut.renderer.gpu.GpuRenderBackend", "FRAGMENT_SOURCE"));
		compile(tool, "prepass", "comp", source("com.sandymandy.strut.renderer.iris.IrisPrePass", "COMPUTE_SOURCE"));
		compile(tool, "pack", "comp", source("com.sandymandy.strut.renderer.iris.IrisPrePass", "PACK_COMPUTE_SOURCE"));
	}

	@Test
	void theCageFunctionIsInBothTheVertexAndThePosePass() throws Exception {
		assertTrue(source("com.sandymandy.strut.renderer.gpu.GpuRenderBackend", "VERTEX_SOURCE").contains("void cageDeform("));
		assertTrue(source("com.sandymandy.strut.renderer.iris.IrisPrePass", "COMPUTE_SOURCE").contains("void cageDeform("));
	}
}
