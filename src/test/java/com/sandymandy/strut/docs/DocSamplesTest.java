package com.sandymandy.strut.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The documentation site cannot rot
 * <ul>
 * <li>every code sample on a page is character-for-character the compiled region in {@link DocSamples}</li>
 * <li>every region is used somewhere, and every page's sample names a region that exists</li>
 * <li>every local link and anchor resolves, ids are unique, and every page is in the navigation</li>
 * </ul>
 * If this fails after changing {@code DocSamples.java}, run {@code java tools/DocsSync.java} and commit the pages it rewrote
 */
class DocSamplesTest {
	private static final Path DOC = Path.of("doc");
	private static final Path SAMPLES = Path.of("src/test/java/com/sandymandy/strut/docs/DocSamples.java");
	private static final Pattern SAMPLE = Pattern.compile("<pre class=\"sample\" data-sample=\"([a-z0-9-]+)\"><code>(.*?)</code></pre>", Pattern.DOTALL);

	private static List<Path> pages() throws IOException {
		try (Stream<Path> files = Files.list(DOC)) {
			return files.filter(p -> p.toString().endsWith(".html")).sorted().toList();
		}
	}

	private static String read(Path path) throws IOException {
		// a checkout that turns line ends into CRLF must not make a sample differ from its compiled region
		return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
	}

	private static String unescape(String html) {
		return html.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
	}

	// The same extraction tools/DocsSync.java performs, kept independent so the test does not trust the tool
	private static Map<String, String> regions() throws IOException {
		List<String> lines = Files.readAllLines(SAMPLES, StandardCharsets.UTF_8);
		Map<String, String> found = new HashMap<>();

		for (int i = 0; i < lines.size(); i++) {
			Matcher begin = Pattern.compile("//\\s*docs:begin\\s+([a-z0-9-]+)").matcher(lines.get(i));

			if (!begin.find())
				continue;

			String name = begin.group(1);
			List<String> body = new ArrayList<>();
			int j = i + 1;

			while (j < lines.size() && !lines.get(j).matches("\\s*//\\s*docs:end\\s+" + Pattern.quote(name) + "\\s*")) {
				body.add(lines.get(j));
				j++;
			}

			assertTrue(j < lines.size(), "region '" + name + "' is never closed");
			assertNull(found.put(name, dedent(body)), "region '" + name + "' is defined twice");
			i = j;
		}

		return found;
	}

	private static String dedent(List<String> lines) {
		int indent = Integer.MAX_VALUE;

		for (String line : lines) {
			if (line.isBlank())
				continue;

			int n = 0;

			while (n < line.length() && (line.charAt(n) == '\t' || line.charAt(n) == ' '))
				n++;

			indent = Math.min(indent, n);
		}

		StringBuilder out = new StringBuilder();

		for (String line : lines)
			out.append(line.isBlank() ? "" : line.substring(indent).stripTrailing()).append('\n');

		return out.toString().strip();
	}

	@Test
	@DisplayName("the documentation folder has the pages the navigation lists")
	void pagesExist() throws IOException {
		assertFalse(pages().isEmpty(), "doc/ has no pages");
		assertTrue(Files.exists(DOC.resolve("assets/style.css")));
		assertTrue(Files.exists(DOC.resolve("assets/docs.js")));
	}

	@Test
	@DisplayName("every code sample on a page equals its compiled region exactly")
	void samplesMatchTheCompiledSource() throws IOException {
		Map<String, String> regions = regions();
		List<String> problems = new ArrayList<>();
		Set<String> used = new HashSet<>();
		int blocks = 0;

		for (Path page : pages()) {
			Matcher matcher = SAMPLE.matcher(read(page));

			while (matcher.find()) {
				blocks++;

				String name = matcher.group(1);
				String expected = regions.get(name);

				used.add(name);

				if (expected == null)
					problems.add(page.getFileName() + ": no region named '" + name + "'");
				else if (!unescape(matcher.group(2)).strip().equals(expected))
					problems.add(page.getFileName() + ": sample '" + name + "' differs from DocSamples.java (run java tools/DocsSync.java)");
			}
		}

		for (String name : regions.keySet()) {
			if (!used.contains(name))
				problems.add("region '" + name + "' is not used by any page");
		}

		assertTrue(blocks > 0, "no samples found in any page");
		assertEquals(List.of(), problems);
	}

	@Test
	@DisplayName("every page has a title, one h1, the shared assets, and unique ids")
	void pageStructure() throws IOException {
		for (Path page : pages()) {
			String html = read(page);
			String name = page.getFileName().toString();

			assertTrue(html.startsWith("<!doctype html>"), name + ": doctype");
			assertTrue(html.contains("<title>") && html.contains("Strut"), name + ": title");
			assertEquals(1, count(html, "<h1"), name + ": exactly one h1");
			assertTrue(html.contains("assets/style.css") && html.contains("assets/docs.js"), name + ": shared assets");
			assertTrue(html.contains("<meta name=\"viewport\""), name + ": viewport (phones)");
			assertTrue(html.contains("<div class=\"layout\">") && html.contains("<main>"), name + ": layout");

			Set<String> ids = new HashSet<>();
			Matcher id = Pattern.compile("\\sid=\"([^\"]+)\"").matcher(html);

			while (id.find())
				assertTrue(ids.add(id.group(1)), name + ": duplicate id '" + id.group(1) + "'");
		}
	}

	@Test
	@DisplayName("every local link and anchor resolves")
	void linksResolve() throws IOException {
		List<String> problems = new ArrayList<>();
		Map<String, String> content = new HashMap<>();

		for (Path page : pages())
			content.put(page.getFileName().toString(), read(page));

		Pattern link = Pattern.compile("(?:href|src)=\"([^\"]+)\"");

		for (Map.Entry<String, String> entry : content.entrySet()) {
			Matcher matcher = link.matcher(entry.getValue());

			while (matcher.find()) {
				String target = matcher.group(1);

				if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:") || target.startsWith("api/"))
					continue;

				String file = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
				String fragment = target.contains("#") ? target.substring(target.indexOf('#') + 1) : null;

				if (file.isEmpty())
					file = entry.getKey();

				if (!Files.exists(DOC.resolve(file))) {
					problems.add(entry.getKey() + ": missing target " + target);

					continue;
				}

				if (fragment != null && file.endsWith(".html")) {
					String html = content.containsKey(file) ? content.get(file) : read(DOC.resolve(file));

					if (!html.contains("id=\"" + fragment + "\""))
						problems.add(entry.getKey() + ": no anchor '" + fragment + "' in " + file);
				}
			}
		}

		assertEquals(List.of(), problems);
	}

	@Test
	@DisplayName("every page appears in the navigation and every navigation entry is a real page")
	void navigationMatchesPages() throws IOException {
		String js = read(DOC.resolve("assets/docs.js"));
		Set<String> navigated = new HashSet<>();
		Matcher entry = Pattern.compile("\\[\"([a-z0-9-]+\\.html)\",").matcher(js);

		while (entry.find())
			navigated.add(entry.group(1));

		Set<String> pages = new HashSet<>();

		for (Path page : pages())
			pages.add(page.getFileName().toString());

		assertEquals(pages, navigated);
	}

	private static int count(String haystack, String needle) {
		int n = 0;

		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1))
			n++;

		return n;
	}
}
