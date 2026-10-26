# Strut

**Strut** is a mesh and animation engine for Minecraft: Java Edition mods, for **Fabric** on **Minecraft 1.21.1**. It is a hard fork of
[GeckoLib](https://github.com/bernie-g/geckolib) 4.9.3: it keeps GeckoLib's keyframe animation engine (cubes, bones, Molang, easings, controllers) and rebuilds
everything around it so the server owns what plays, models can be real meshes with skeletons, and a mod needs very little code.

```java
// the whole entity
public class HeroEntity extends PathAwareEntity implements StrutEntity {
	public HeroEntity(EntityType<? extends PathAwareEntity> type, World world) { super(type, world); }
}

// one line, client side
StrutClient.entity(HERO);

// anywhere on the server
ServerAnimationStates.forEntity(entity, false).play("base", "walk", entity.getWorld().getTime());
```

The guide is at **https://sandyymandy.github.io/Strut/**, and is also in [`doc/`](doc/index.html): open `doc/index.html` in a browser, nothing to build. Every Java sample in it is compiled by the build.

## If you know GeckoLib: what is different

| | GeckoLib | Strut |
|---|---|---|
| **Who decides what plays** | Each client's animation handler | The **server**. A controller is server-authoritative by default; the server plays, seeks and changes the rate, and every client derives the same pose from one shared clock. Late joiners, lag and reconnects need no code. Items and block entities stay client-side by default. |
| **Keyframe events** | Client-side handlers that can miss or repeat | **Typed events with an exactly-once guarantee**: gameplay events run once on the server, cosmetic ones (sounds, particles) at most once on clients. A marker is `StrutEvents.onServerMarker`; a custom instruction is a typed event you register once. |
| **Model formats** | `.geo.json`, `.animation.json` | **`.strutmodel` and `.strutanim`**, compact binary formats. The old JSON files can still be loaded when named explicitly, but events read binary clips only. |
| **Geometry** | Cubes | Cubes **and free-form meshes with armatures and skinning** (skinned, deformable models) |
| **Authoring** | Blockbench with the GeckoLib plugin | Blockbench with the **Strut Toolkit** plugin, which reads and writes the binary formats directly (see below) |
| **Rendering** | A walk of the bones on the CPU | An **instanced GPU path** and nothing else. It works under Iris shader packs, including shadows. There is no CPU path, so methods that hooked it (`renderRecursively`, `renderCubesOfBone`, the `Dynamic*Renderer` classes) no longer exist. A per-bone colour or texture is a bone override instead. |
| **Changing a model at runtime** | Override render methods | **Bone overrides** (colour, texture, UV, visibility, per bone, optionally for one frame) and **attachments**, synced from the server |
| **Delivering content** | A resource pack | **Asset sync**: models, textures and sounds in a data pack on the server reach every player on join, with no resource pack to install |
| **Boilerplate** | A cache field, `getTick`, `registerControllers`, a model class, a renderer class, a render provider | A class with no body and one registration line. The cache, the tick and the controllers are provided; model and renderer are found by file convention |
| **Names** | `GeoEntity`, `GeoModel`, `GeckoLibUtil`... | `StrutEntity`, `StrutModel`, `StrutUtil`... Every `Geo*` name is renamed, none is kept as an alias |
| **Loaders and versions** | Several loaders and versions | **Fabric, Minecraft 1.21.1 only** |

Things that stay the same: `AnimationController`, `AnimatableManager`, `RawAnimation`, `PlayState`, `DataTickets`, Molang, the easings and the controller model. The
[migration guide](doc/migrating.html) has the full rename table, the steps, and what to delete from an existing mod.

The one change that can break a ported mod's behaviour is the first row: a GeckoLib handler that read entity state to pick an animation does nothing on a
`StrutEntity`, and that decision moves to server code. The migration guide covers it.

## What else is in it

- **[Players with a body](doc/players.html):** draw a player as a Strut model, with first-person modes, held items on hand bones, armour and a locomotion set, with
  no second entity.
- **[Armour](doc/armour.html):** a model per slot, bound to the wearer by bone name, so one set fits every model that has those bones.
- **[Scenes](doc/scenes.html):** several entities animated together on one clock, for cutscenes and paired animations.
- **[Physics](doc/physics.html):** spring bones, chains, hair, cloth, flesh and jelly, and ragdolls; collision with the world, other models and players; a cursor
  tool to test it in game. Set up in Blockbench, saved with the model.
- **[Shading](doc/rendering.html#shading):** soft, toon, game-default and flat shading, lit by the sun or moon and the glowing blocks near a model, one per model.
- **[Render pipeline hooks](doc/render-hooks.html):** a mod can put its own GLSL into the shader every model is drawn with, add coloured lights (a mob that glows),
  give the shader per-model values and run code around the draw.
- **Debugging:** the F9 overlay (draw counts, timings, shader-pack state, light), `/strut` and `/strutphysics` commands, and a log line that says why a shadow is
  missing. See [Commands and debugging](doc/debugging.html).

## Requirements

- Minecraft 1.21.1, Java 21, Fabric Loader 0.17 or newer, Fabric API 0.100.0+1.21 or newer
- A graphics driver with **OpenGL 4.3** (shader storage buffers): the GPU path is the only renderer. macOS stops at OpenGL 4.1, so it is not supported
- Optional: [Iris](https://www.irisshaders.dev/) for shader packs, detected automatically (tested with 1.8.x)

## Using it

Strut is published on [Modrinth](https://modrinth.com/mod/strut), which also serves it as a Maven repository. In `build.gradle`:

```groovy
repositories {
	exclusiveContent {
		forRepository {
			maven {
				name = "Modrinth"
				url = "https://api.modrinth.com/maven"
			}
		}
		filter {
			includeGroup "maven.modrinth"
		}
	}
}

dependencies {
	modImplementation "maven.modrinth:strut:1.0.0"
}
```

and in `fabric.mod.json`:

```json
"depends": {
	"strut": "*"
}
```

Players install the mod from Modrinth like any other. [Getting started](https://sandyymandy.github.io/Strut/getting-started.html) takes a first entity from nothing in five steps. To build Strut
from source instead, run `./gradlew publishToMavenLocal` and depend on `com.sandymandy.strut:strut-fabric-1.21.1:1.0.0` from `mavenLocal()`.

## The Blockbench plugin

Models and animations are authored with the **Strut Toolkit** plugin for Blockbench 5.1 or newer, kept in its own repository,
[Strut-BlockBench-Plugin](https://github.com/sandyymandy/Strut-BlockBench-Plugin). It exports and imports `.strutmodel`
and `.strutanim`, supports meshes and armatures with skinning, controller-based animation with Molang and event markers on the timeline, armour sets and wearer
profiles, and has a Physics tab. It opens older Bedrock-style `.geo.json` models and animations so an existing model can be moved over and saved in the binary
format.

## Good to know before you start

- **It is early.** The API can still change, and there are no compatibility aliases.
- **Server-owned animation is a design choice, not an option you can mostly ignore.** If your mod is client-only, items and block entities still work the
  GeckoLib way, but an entity's animation is driven from the server.
- **Models made for GeckoLib need converting once**, by opening them in Blockbench with the plugin and saving in the binary formats; asset folders move from
  `geo/` and `animations/` to `strut/models/` and `strut/animations/`.
- **Under a shader pack**, the pack draws the models with its own program, so Strut's shading modes, coloured lights and shader hooks do not apply there.
- **Fabric only.** There is no Forge or NeoForge build.

## Building

`./gradlew build` compiles Strut and runs its tests.

## A note on how it was made

Some of the code in this project was written with the help of AI tools. The design and direction are mine, and the build runs a large test suite, but it is an early project, so expect rough edges and please report anything you run into.

## Credits

Strut is a fork of [GeckoLib](https://github.com/bernie-g/geckolib) by Gecko, Eliot, AzureDoom, DerToaster, Tslat and Witixin, used under the MIT License. The
upstream license is kept verbatim at [`src/main/resources/GECKOLIB_LICENSE`](src/main/resources/GECKOLIB_LICENSE). GeckoLib's
[wiki](https://github.com/bernie-g/geckolib/wiki) describes the upstream project and some inherited behaviour, but its class names differ from Strut's; the
[documentation](doc/index.html) is the reference for Strut.

## License

MIT.
