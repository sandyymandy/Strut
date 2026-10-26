package com.sandymandy.strut;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A mixin class that is not listed in {@code strut.mixins.json} is never applied, and nothing says so: the code compiles and the feature silently does not
 * happen. Every mixin class in the common and client folders must be in the list, and every name in the list must be a class
 */
class MixinRegistrationTest {
	private static final Path ROOT = Path.of("src/main");

	private static TreeSet<String> listed(String key) throws IOException {
		JsonObject config = JsonParser.parseString(Files.readString(ROOT.resolve("resources/strut.mixins.json"), StandardCharsets.UTF_8)).getAsJsonObject();
		TreeSet<String> names = new TreeSet<>();
		JsonArray array = config.getAsJsonArray(key);

		if (array != null)
			array.forEach(element -> names.add(element.getAsString()));

		return names;
	}

	private static TreeSet<String> classesIn(String folder) throws IOException {
		TreeSet<String> names = new TreeSet<>();

		try (Stream<Path> files = Files.list(ROOT.resolve("java/com/sandymandy/strut/mixin/" + folder))) {
			files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".java"))
					.forEach(name -> names.add(folder + "." + name.substring(0, name.length() - ".java".length())));
		}

		return names;
	}

	@Test
	void everyCommonMixinClassIsListed() throws IOException {
		assertEquals(classesIn("common"), listed("mixins"), "common mixin classes and the \"mixins\" list differ");
	}

	@Test
	void everyClientMixinClassIsListed() throws IOException {
		assertEquals(classesIn("client"), listed("client"), "client mixin classes and the \"client\" list differ");
	}

	@Test
	void theListsHaveNoDuplicates() throws IOException {
		JsonObject config = JsonParser.parseString(Files.readString(ROOT.resolve("resources/strut.mixins.json"), StandardCharsets.UTF_8)).getAsJsonObject();

		for (String key : List.of("mixins", "client")) {
			List<String> all = new ArrayList<>();

			config.getAsJsonArray(key).forEach(element -> all.add(element.getAsString()));
			assertEquals(new TreeSet<>(all).size(), all.size(), key + " lists a mixin twice");
		}
	}
}
