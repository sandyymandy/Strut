/* Strut documentation: navigation, theme toggle, page filter, copy buttons. No dependencies. */
(function () {
	"use strict";

	var NAV = [
		["Start", [
			["index.html", "Overview", "welcome install setup dependency"],
			["getting-started.html", "Getting started", "first entity quick start tutorial"]
		]],
		["Guides", [
			["entities.html", "Entities", "StrutEntity StrutClient.entity renderer model"],
			["items-and-blocks.html", "Items, armor and blocks", "StrutItem armor block entity BlockEntity"],
			["players.html", "Players with a Strut body", "player body morph disguise StrutPlayers PlayerBody replace render"],
			["armour.html", "Armour", "armour armor slot set wearer profile look trim animal horse atlas StrutArmour ArmourWearer model attachment"],
			["scenes.html", "Scenes", "scene cutscene paired participant role step sync group strip StrutScenes"],
			["models.html", "Models and files", "strutmodel strutanim blockbench folders convention builder"],
			["animations.html", "Playing animations", "play seek setRate stop server authority controller"],
			["events.html", "Events", "marker sound particle typed binding StrutEvents"],
			["bones.html", "Bone overrides and attachments", "override hide tint attach item block"],
			["asset-sync.html", "Asset sync", "data pack resource pack download server assets"],
			["physics.html", "Physics", "jiggle spring chain hair cloth flesh jelly ragdoll collider strut_physics blockbench quality"]
		]],
		["Reference", [
			["rendering.html", "Rendering and performance", "gpu iris shaders shadow lod f9"],
			["render-hooks.html", "Changing the render pipeline", "shader hook glsl light colour toon custom event provider"],
			["debugging.html", "Commands and debugging", "strut command trace f9 log"],
			["api.html", "API reference", "javadoc classes packages"]
		]],
		["Moving to Strut", [
			["migrating.html", "Migrating from GeckoLib", "geo rename gecko upgrade"],
			["about.html", "About and credits", "license mit geckolib credits"]
		]]
	];

	var root = document.documentElement;
	var here = location.pathname.split("/").pop() || "index.html";

	function store(key, value) {
		try {
			if (value === undefined) return localStorage.getItem(key);
			localStorage.setItem(key, value);
		} catch (e) {
			return null;
		}
	}

	var saved = store("strut-doc-theme");
	if (saved === "light" || saved === "dark") root.setAttribute("data-theme", saved);

	function isDark() {
		var t = root.getAttribute("data-theme");
		if (t) return t === "dark";
		return !!(window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches);
	}

	function build() {
		var layout = document.querySelector(".layout");
		if (!layout) return;

		var top = document.createElement("div");
		top.className = "topbar";
		top.innerHTML = '<button id="menu-toggle" type="button" aria-label="Menu">Menu</button>' +
			'<a class="brand" href="index.html"><img class="logo" src="assets/icon.png" alt="" width="28" height="28"><span class="name">Strut</span><span class="sub"> docs</span></a><span class="spacer"></span>' +
			'<button id="theme-toggle" type="button" aria-label="Toggle light or dark theme"></button>';
		document.body.insertBefore(top, document.body.firstChild);

		var side = document.createElement("nav");
		side.className = "sidebar";
		side.setAttribute("aria-label", "Documentation");

		var search = document.createElement("input");
		search.type = "search";
		search.placeholder = "Filter pages...";
		search.setAttribute("aria-label", "Filter pages");
		side.appendChild(search);

		var links = [];

		NAV.forEach(function (group) {
			var h = document.createElement("h4");
			h.textContent = group[0];
			side.appendChild(h);

			group[1].forEach(function (entry) {
				var a = document.createElement("a");
				a.href = entry[0];
				a.textContent = entry[1];
				a.dataset.keywords = (entry[1] + " " + entry[2]).toLowerCase();
				if (entry[0] === here) a.className = "current";
				side.appendChild(a);
				links.push(a);
			});
		});

		layout.insertBefore(side, layout.firstChild);

		search.addEventListener("input", function () {
			var q = search.value.trim().toLowerCase();
			links.forEach(function (a) { a.classList.toggle("hidden", !!q && a.dataset.keywords.indexOf(q) < 0); });
		});

		var theme = document.getElementById("theme-toggle");
		function label() { theme.textContent = isDark() ? "Light" : "Dark"; }
		label();
		theme.addEventListener("click", function () {
			var next = isDark() ? "light" : "dark";
			root.setAttribute("data-theme", next);
			store("strut-doc-theme", next);
			label();
		});

		document.getElementById("menu-toggle").addEventListener("click", function () { document.body.classList.toggle("menu-open"); });
		side.addEventListener("click", function (e) { if (e.target.tagName === "A") document.body.classList.remove("menu-open"); });
	}

	function anchors() {
		document.querySelectorAll("main h2[id], main h3[id]").forEach(function (h) {
			var a = document.createElement("a");
			a.className = "anchor";
			a.href = "#" + h.id;
			a.textContent = "#";
			a.setAttribute("aria-label", "Link to this section");
			h.appendChild(a);
		});
	}

	function copyButtons() {
		document.querySelectorAll("main pre").forEach(function (pre) {
			var b = document.createElement("button");
			b.type = "button";
			b.className = "copy";
			b.textContent = "Copy";
			b.addEventListener("click", function () {
				var code = pre.querySelector("code");
				var text = (code || pre).textContent;
				if (navigator.clipboard) navigator.clipboard.writeText(text);
				b.textContent = "Copied";
				setTimeout(function () { b.textContent = "Copy"; }, 1200);
			});
			pre.appendChild(b);
		});
	}

	document.addEventListener("DOMContentLoaded", function () { build(); anchors(); copyButtons(); });
})();
