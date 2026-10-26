package com.sandymandy.strut.renderer.hook;

import com.sandymandy.strut.renderer.hook.StrutShaderHooks.Point;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrutShaderHooksTest {
	private static final Identifier A = Identifier.of("test", "a");
	private static final Identifier B = Identifier.of("test", "b");

	@AfterEach
	void clean() {
		StrutShaderHooks.unregister(A);
		StrutShaderHooks.unregister(B);
	}

	private static String shader(String field) throws Exception {
		Field source = Class.forName("com.sandymandy.strut.renderer.gpu.GpuRenderBackend").getDeclaredField(field);

		source.setAccessible(true);

		return (String) source.get(null);
	}

	@Test
	void aMarkerLineIsReplacedByWhatIsRegisteredThere() {
		String source = "void main() {\n    //@hook vertex_pose\n    int x;\n}\n";
		String composed = StrutShaderHooks.composeWith(source, Point.VERTEX_POSE, A, "posedPosition.y += 1.0;");

		assertTrue(composed.contains("posedPosition.y += 1.0;"));
		assertTrue(composed.contains("// hook test:a"), "the id is in the text, for an error message to point at");
		assertFalse(composed.contains("//@hook"), composed);
		assertTrue(composed.indexOf("posedPosition") < composed.indexOf("int x;"), "in the place of the marker");
	}

	@Test
	void everyPointNothingIsRegisteredAtIsLeftEmpty() {
		StringBuilder source = new StringBuilder();

		for (Point point : Point.values())
			source.append(point.marker()).append('\n');

		String composed = StrutShaderHooks.composeWith(source.toString(), Point.FRAGMENT_FINAL, A, "color.a = 1.0;");

		assertEquals(1, composed.lines().filter(l -> l.contains("color.a")).count());
		assertFalse(composed.contains("//@hook"));
	}

	@Test
	void hooksAtOnePointRunInTheOrderTheyWereRegistered() {
		StrutShaderHooks.register(A, Point.FRAGMENT_FINAL, "first();");
		StrutShaderHooks.register(B, Point.FRAGMENT_FINAL, "second();");

		String composed = StrutShaderHooks.compose("//@hook fragment_final\n");

		assertTrue(composed.indexOf("first();") < composed.indexOf("second();"), composed);
	}

	@Test
	void registeringAgainReplacesAndUnregisteringTakesItOut() {
		StrutShaderHooks.register(A, Point.FRAGMENT_FINAL, "one();");
		StrutShaderHooks.register(A, Point.FRAGMENT_FINAL, "two();");

		String composed = StrutShaderHooks.compose("//@hook fragment_final\n");

		assertFalse(composed.contains("one();"));
		assertTrue(composed.contains("two();"));

		StrutShaderHooks.unregister(A);

		assertEquals("\n", StrutShaderHooks.compose("//@hook fragment_final\n"));
		assertFalse(StrutShaderHooks.any());
	}

	@Test
	void theVersionChangesWithTheHooksSoTheShaderIsBuiltAgain() {
		int before = StrutShaderHooks.version();

		StrutShaderHooks.register(A, Point.VERTEX_DECLS, "float f() { return 1.0; }");

		assertNotEquals(before, StrutShaderHooks.version());

		int added = StrutShaderHooks.version();

		StrutShaderHooks.unregister(B);

		assertEquals(added, StrutShaderHooks.version(), "taking out what was never there changes nothing");
	}

	@Test
	void aShaderThatDidNotCompileIsNotTriedAgainUntilTheHooksChange() {
		StrutShaderHooks.register(A, Point.VERTEX_POSE, "broken");
		StrutShaderHooks.markFailed(StrutShaderHooks.version());

		assertTrue(StrutShaderHooks.failed());

		StrutShaderHooks.register(B, Point.VERTEX_POSE, "fixed();");

		assertFalse(StrutShaderHooks.failed());
	}

	@Test
	void theWithoutHooksFormLeavesNoMarkerBehind() {
		StrutShaderHooks.register(A, Point.FRAGMENT_FINAL, "color.a = 0.0;");

		assertEquals("a\n\nb\n", StrutShaderHooks.compose("a\n//@hook fragment_final\nb\n", false));
	}

	@Test
	void everyPointHasItsMarkerInTheShaderItBelongsTo() throws Exception {
		String vertex = shader("VERTEX_SOURCE");
		String fragment = shader("FRAGMENT_SOURCE");

		for (Point point : Point.values()) {
			boolean inVertex = point.name().startsWith("VERTEX");
			String owner = inVertex ? vertex : fragment;
			String other = inVertex ? fragment : vertex;

			assertEquals(1, owner.split(Pattern.quote(point.marker()), -1).length - 1, point + " is in its own shader once");
			assertFalse(other.contains(point.marker()), point + " is not in the other");
		}
	}

	@Test
	void paramsAreSetBySlotAndOutOfRangeIsRefused() {
		StrutShaderParams.set(2, 1f, 2f, 3f, 4f);

		float[] values = StrutShaderParams.snapshot();

		assertEquals(StrutShaderParams.COUNT * 4, values.length);
		assertEquals(3f, values[2 * 4 + 2]);
		assertThrows(IllegalArgumentException.class, () -> StrutShaderParams.set(StrutShaderParams.COUNT, 0f, 0f, 0f, 0f));

		StrutShaderParams.set(2, 0f, 0f, 0f, 0f);
	}
}
