package com.sandymandy.strut.loading.math;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A name written without a prefix, as a Blockbench variable placeholder is, is a variable: the keyframe is not lost
 */
class MolangNamesTest {
	@Test
	@DisplayName("a bare placeholder name compiles as variable.<name>, reads 0 until it is set, and follows the variable once it is")
	void aPlaceholderIsAVariable() {
		MathValue value = MathParser.compileMolang("-4.7757 + SIZE * 15");

		assertEquals(-4.7757, value.get(), 1e-9, "the placeholder is 0 until it is set");

		MathParser.setVariable("variable.size", () -> 2.0);

		assertEquals(-4.7757 + 30.0, value.get(), 1e-9);
		assertEquals(30.0, MathParser.compileMolang("size * 15").get(), 1e-9, "the same variable by any spelling");
		assertEquals(30.0, MathParser.compileMolang("variable.size * 15").get(), 1e-9);
		assertEquals(-2.0, MathParser.compileMolang("-(Size)").get(), 1e-9, "negated, in brackets");
	}

	@Test
	@DisplayName("names that already mean something are left alone: constants, functions, prefixed names, true and false")
	void knownNamesAreNotRewritten() {
		assertEquals(Math.PI * 2, MathParser.compileMolang("PI * 2").get(), 1e-9);
		assertEquals(1.0, MathParser.compileMolang("math.sin(90)").get(), 1e-6);
		assertEquals("math.sin(x.y) + variable.a", MathParser.namesAsVariables("math.sin(x.y) + variable.a"));
		assertEquals("4.5e2 + 1", MathParser.namesAsVariables("4.5e2 + 1"));
		assertEquals("variable.speed*2", MathParser.namesAsVariables("speed*2"));
		assertEquals("true + false", MathParser.namesAsVariables("true + false"));
	}
}
