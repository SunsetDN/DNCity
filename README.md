# DNCity

NeoForge (Minecraft 1.21.1) mod written in Kotlin. Voice chat (close-range Opus + PTT radio on
codec2), TACZ weapon sounds through a bundled FMOD Studio bank, server-pushed `/music` playback,
and an experimental native window/JCEF browser overlay. See `CLAUDE.md` for the full architecture
writeup — this file only covers getting a dev environment running.

## Requirements

- **JDK 21** (exactly this major version — see `CLAUDE.md`'s "Toolchain" section for why the repo
  used to require JDK 25 and no longer does). Point `JAVA_HOME` at a JDK 21 install, or let the
  Gradle toolchain resolver (`org.gradle.toolchains.foojay-resolver-convention`) download one.
- **Git LFS** — `*.bank` files (FMOD Studio banks) are tracked via LFS (`.gitattributes`). Install
  it and run `git lfs install` once per machine before cloning/pulling, or the bank files will be
  checked out as tiny pointer text files instead of real audio data.
- **A C/C++ toolchain**, only if you intend to rebuild the native modules under `engine/` (most
  day-to-day Kotlin/Java work on the mod itself does not touch these — the built `.dll`s are
  checked into each module's generated resources dir until you actually change native code):
  - `engine/audio` (miniaudio + codec2) needs **clang targeting `x86_64-w64-mingw32`** (MinGW-w64)
    — codec2's C99 `_Complex` code doesn't build against the MSVC ABI. Needs `cmake` and a
    MinGW-w64 sysroot on `PATH`.
  - `engine/fmod` and `engine/window` build with plain **MSVC** (Visual Studio Build Tools) via
    `cmake` — no special toolchain needed.
  - See each module's `CLAUDE.md` section / `CMakeLists.txt` comments for exact flags if a build
    fails.
- **Windows** is the only platform the native modules currently support (`engine/window` is
  Win32-only; `engine/fmod`/`engine/audio` are Windows-focused too). Linux/macOS are not set up.

## Cloning

This repo uses several git submodules (vendored mod forks + codec2) wired in as Gradle composite
builds (`settings.gradle.kts`). Clone with submodules, or init them after the fact:

```
git clone --recurse-submodules <repo-url>
# or, if already cloned:
git submodule update --init --recursive
```

Submodules in use (see `.gitmodules`): `mods/TACZ-1.21.1`, `mods/First-Aid-New`,
`mods/Automobility`, `mods/SuperbWarfare`, `mods/ModernUI-MC`, `mods/sodium`, `mods/Iris`,
`engine/audio/vendor/codec2`.

## Building & running

Use the Gradle wrapper — `./gradlew` (bash) or `gradlew.bat` (cmd/PowerShell; PowerShell can also
call `./gradlew`). First invocation will pull in NeoForge's toolchain and every submodule's own
dependency graph, so expect a slow first sync.

- `./gradlew build` — compile and build the mod jar.
- `./gradlew runClient` — launch a dev client (game directory `run/`).
- `./gradlew runClient2` — a second dev client with a distinct username/UUID and its own game
  directory (`run-client2/`), for testing multiplayer-only features (radio, proximity voice) by
  connecting two clients to the same world/LAN game at once.
- `./gradlew runServer` — launch a dev dedicated server (`--nogui`).
- `./gradlew runGameTestServer` — runs all registered gametests (namespace `dncity`) then exits.
- `./gradlew runData` — data generation, outputs to `src/generated/resources/`.

There's no separate lint/test task beyond `runGameTestServer` — this is a Minecraft mod, so most
verification is done by actually running a dev client.

Each native module under `engine/` (`audio`, `fmod`, `window`) is also a standalone Gradle
project and can be built on its own: `./gradlew -p engine/<audio|fmod|window> build` (they reuse
the root project's Gradle wrapper distribution — no separate `gradlew` in those directories).

## IDE setup (IntelliJ IDEA)

1. Open the repo root as a Gradle project (`build.gradle.kts`) — don't open a submodule directory
   directly.
2. Let the initial Gradle sync finish; it resolves every `includeBuild(...)` composite build
   declared in `settings.gradle.kts` (TACZ, First Aid, Automobility, SuperbWarfare, ModernUI-MC,
   Sodium, Iris, and the three `engine/*` native modules).
3. `generateModMetadata` runs automatically on sync and expands `${...}` placeholders in
   `src/main/templates/META-INF/neoforge.mods.toml` (from `gradle.properties`) into
   `build/generated/sources/modMetadata` — edit the template/properties, not the generated copy.
4. If IntelliJ shows stale "unresolved reference" errors after adding/changing a composite build
   module (most often after pulling changes that touch `settings.gradle.kts` or add a new
   `engine/*` module), a plain incremental sync sometimes isn't enough:
   - First try the Gradle tool window's **"Reload All Gradle Projects"** button.
   - If that doesn't clear it, **File → Invalidate Caches / Restart → Invalidate and Restart**.
   - If Gradle itself throws something like `Unable to find method
     'DefaultGroovyMethods.collect(...)'`, that's a corrupted/stale Gradle daemon, not a real
     build error — run `./gradlew --stop` to kill it, then re-sync (a fresh daemon starts
     automatically on the next Gradle invocation).

## Configuration cache

`org.gradle.configuration-cache=false` is set repo-wide in `gradle.properties` and must stay off:
`mods/ModernUI-MC`'s Architectury Loom build silently fails to apply its access transformer under
the configuration cache (confirmed by hand — see `gradle.properties`'s comment), and configuration
cache is an invocation-wide setting shared by every included build, so it can't be scoped to just
that one submodule.

## Editing mod metadata

`mod_authors`, `mod_description`, and the Minecraft/NeoForge version ranges live in
`gradle.properties`, not in `src/main/templates/META-INF/neoforge.mods.toml` directly — the
template only has `${...}` placeholders the `generateModMetadata` task expands.
