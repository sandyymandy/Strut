package com.sandymandy.strut.renderer.hook;

import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a mod changes what the GPU path draws: pieces of GLSL, put into the shader Strut draws every model with at named places. A hook is text that runs in the middle of
 * the shader's own {@code main} (or declares what a later hook uses), with the shader's own variables in reach, so it can move vertices, change the colour a model is
 * lit by, replace the toon look or add an effect, without a copy of the whole shader
 * <p>
 * The places, in the order they run, and what is in scope there:
 * <ul>
 *     <li>{@link Point#VERTEX_DECLS}: top level of the vertex shader, for functions and constants the other vertex hooks use</li>
 *     <li>{@link Point#VERTEX_POSE}: after the vertex is posed and its normal is known, before it is projected. {@code posedPosition} (a {@code vec4}, relative to the
 *     camera, not yet rotated by it) and {@code worldNormal} may be changed; {@code instData} (this instance's data, {@code instData.custom[0..3]} the floats set with
 *     {@link StrutRenderHooks#SUBMIT}), {@code gl_InstanceID}, {@code BoneIndex} and {@code StrutParams[0..7]} are read</li>
 *     <li>{@link Point#VERTEX_COLOR}: after the vertex is lit. {@code vertexColor} and {@code lightTint} may be changed, and {@code strutVarying0} and
 *     {@code strutVarying1} (a {@code vec4} each, they reach the fragment shader interpolated) set</li>
 *     <li>{@link Point#FRAGMENT_DECLS}: top level of the fragment shader</li>
 *     <li>{@link Point#FRAGMENT_TOON}: first thing in {@code toonColor(vec3 albedo, float shade, float rim)}; {@code return} a colour to replace the toon look, or change
 *     {@code albedo}, {@code shade} and {@code rim} to adjust it</li>
 *     <li>{@link Point#FRAGMENT_ALBEDO}: after the texture is read ({@code color}, the model's own colour before any light), may be changed; {@code texCoord},
 *     {@code fragPos}, {@code strutCustom} ({@code vec4}, the instance's custom floats), {@code strutVarying0}, {@code strutVarying1} and {@code StrutParams} are read</li>
 *     <li>{@link Point#FRAGMENT_FINAL}: after the light, before the fog; {@code color} is what would be drawn</li>
 * </ul>
 * A hook that does not compile is left out and logged with the ids registered; the model is then drawn as if none was registered. Hooks apply to the GPU path's own
 * shader; under a shader pack the pack draws the model and they are not used
 * <p>
 * Register at startup (the shader is built the first time a model is drawn, and again if hooks change later), from the client's side only
 */
public final class StrutShaderHooks {
	/** The named places a hook can go */
	public enum Point {
		VERTEX_DECLS("vertex_decls"),
		VERTEX_POSE("vertex_pose"),
		VERTEX_COLOR("vertex_color"),
		FRAGMENT_DECLS("fragment_decls"),
		FRAGMENT_TOON("fragment_toon"),
		FRAGMENT_ALBEDO("fragment_albedo"),
		FRAGMENT_FINAL("fragment_final");

		private final String marker;

		Point(String marker) {
			this.marker = marker;
		}

		/** The text of the line, a comment, that stands for this place in the shader source */
		public String marker() {
			return "//@hook " + this.marker;
		}
	}

	private record Snippet(Identifier id, String glsl) {}

	private static final Map<Point, Map<Identifier, Snippet>> HOOKS = new EnumMap<>(Point.class);
	private static int version;
	private static int failedVersion = -1;

	private StrutShaderHooks() {}

	/**
	 * Puts {@code glsl} at {@code point}, under {@code id}; registering the same id at the same point again replaces it. Hooks at one point run in the order they were
	 * registered
	 */
	public static synchronized void register(Identifier id, Point point, String glsl) {
		HOOKS.computeIfAbsent(point, p -> new LinkedHashMap<>()).put(id, new Snippet(id, glsl));
		version++;
	}

	/** Takes everything registered under {@code id}, at every point, out again */
	public static synchronized void unregister(Identifier id) {
		boolean changed = false;

		for (Map<Identifier, Snippet> snippets : HOOKS.values())
			changed |= snippets.remove(id) != null;

		if (changed)
			version++;
	}

	/** Changes every time a hook is added or taken out: the shader is rebuilt when it no longer matches */
	public static synchronized int version() {
		return version;
	}

	/** Whether any hook is registered */
	public static synchronized boolean any() {
		return HOOKS.values().stream().anyMatch(m -> !m.isEmpty());
	}

	/** The ids registered, for the message when the shader with them does not compile */
	public static synchronized List<Identifier> ids() {
		List<Identifier> out = new ArrayList<>();

		for (Map<Identifier, Snippet> snippets : HOOKS.values()) {
			for (Identifier id : snippets.keySet()) {
				if (!out.contains(id))
					out.add(id);
			}
		}

		return out;
	}

	/** Called by the renderer when the shader built at {@code version} did not compile: it is not tried again until the hooks change */
	public static synchronized void markFailed(int version) {
		failedVersion = version;
	}

	/** Whether the shader built at the current hooks is known not to compile */
	public static synchronized boolean failed() {
		return failedVersion == version;
	}

	/** {@code source} with each marker line replaced by what is registered there, or by nothing at all when {@code withHooks} is false */
	public static synchronized String compose(String source, boolean withHooks) {
		return withHooks ? compose(source) : compose(source, Map.of());
	}

	/** {@code source} with each hook point's marker line replaced by what is registered there (nothing, if nothing is) */
	public static synchronized String compose(String source) {
		Map<Point, List<Snippet>> snapshot = new EnumMap<>(Point.class);

		for (Map.Entry<Point, Map<Identifier, Snippet>> entry : HOOKS.entrySet())
			snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue().values()));

		return compose(source, snapshot);
	}

	private static String compose(String source, Map<Point, List<Snippet>> snippets) {
		String out = source;

		for (Point point : Point.values()) {
			StringBuilder text = new StringBuilder();

			for (Snippet snippet : snippets.getOrDefault(point, List.of()))
				text.append("// hook ").append(snippet.id()).append('\n').append(snippet.glsl()).append('\n');

			out = Pattern.compile("^[ \\t]*" + Pattern.quote(point.marker()) + "[ \\t]*$", Pattern.MULTILINE).matcher(out).replaceAll(Matcher.quoteReplacement(text.toString()));
		}

		return out;
	}

	/** {@code source} composed with {@code glsl} at {@code point} only, for a test of what a hook looks like in place */
	public static String composeWith(String source, Point point, Identifier id, String glsl) {
		return compose(source, Map.of(point, List.of(new Snippet(id, glsl))));
	}
}
