# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

DNCity is a NeoForge (Minecraft 1.21.1) mod written in Kotlin, using Kotlin for Forge. The
current feature under active development is voice chat (item, tuning UI, networking, and the
domain model for modes/bands/frequencies), replacing a previously-removed Simple Voice Chat
integration with two tiers: close-range (Opus, full-duplex, no PTT) and radio (codec2, PTT,
half-duplex, frequency/range-gated — see "Architecture: the radio system"). Two other mods
(TACZ, First Aid) are vendored as composite-build submodules the project depends on; TACZ (a gun
mod) plays its weapon sounds through DNCity's own bundled FMOD Studio banks
(`src/main/resources/fmod/*.bank`) rather than TACZ's normal vanilla-sound-engine OGG files — see
"Composite builds / submodules" below. Two native modules exist under `engine/`: `engine/audio`
(miniaudio capture/playback + the codec2 JNI bridge) and `engine/fmod` (FMOD Studio Panama FFI
bindings) are both wired into the main build.

## Build & run commands

Use the Gradle wrapper (`./gradlew` on bash, `gradlew.bat` on cmd/PowerShell — PowerShell can
also invoke `./gradlew`).

- `./gradlew build` — compile and build the mod jar.
- `./gradlew runClient` — launch a dev client (`run/` game directory).
- `./gradlew runClient2` — launch a second dev client with a distinct username/UUID and its
  own game directory (`run-client2/`), for testing multiplayer-only features (radio,
  proximity chat) by connecting two clients to the same world/LAN game at once.
- `./gradlew runServer` — launch a dev dedicated server (`--nogui`).
- `./gradlew runGameTestServer` — launches `GameTestServer` and runs all registered gametests,
  then exits (namespace filtered via `neoforge.enabledGameTestNamespaces` = `dncity`).
- `./gradlew runData` — data generation; outputs to `src/generated/resources/`, with
  `src/main/resources/` as the existing-data source.
- `generateModMetadata` runs automatically on IDE sync and expands `${...}` placeholders in
  `src/main/templates/META-INF/neoforge.mods.toml` (mod id/version/authors/description, MC and
  Neo version ranges) into `build/generated/sources/modMetadata`. Edit the template, not a
  generated copy.

There is no separate lint/test task beyond `runGameTestServer`; this is a Minecraft mod, so
most verification is done by running a dev client.

## Toolchain

The whole repo (root project, TACZ submodule, First Aid submodule, `engine/audio`,
`engine/fmod`) is pinned to **Java 21** — what Mojang ships to end users for Minecraft 1.21.1,
so it's what players actually have. Don't lower `sourceCompatibility`/`targetCompatibility` in
any of these modules independently; keep them unified.

This was **Java 25** until 2026-08-26: `engine/fmod`'s bindings used to be jextract-generated
`java.lang.foreign` (Panama FFI) code, which needed `SymbolLookup.findOrThrow()` (added in JDK
24) — and `java.lang.foreign` itself only stabilized out of preview in JDK 22 — so the whole
module required JDK 24+ just to launch. TACZ loads `engine/fmod`'s classes directly at runtime
(`FmodWeaponSoundManager`), so that requirement cascaded to the whole repo
(`UnsupportedClassVersionError` on an older JVM otherwise). `engine/fmod` now uses hand-written
JNI instead (see its own entry below), which has no such floor — don't reintroduce a
jextract/FFM-based binding without solving that the same way, or this section (and the Java 21
pin across every module) needs revisiting.

## Composite builds / submodules

`mods/TACZ-1.21.1` and `mods/First-Aid-New` are git submodules included via
`includeBuild(...)` + `dependencySubstitution` in `settings.gradle.kts`, so their own project
graph resolves using *this* project's repositories (see the `flatDir`/jitpack/maven blocks in
`build.gradle.kts` that exist specifically to satisfy TACZ's dependencies). Their versions in
`gradle.properties` (`mods_tacz_version`, `mods_firstaid_version`) are for readability only —
the included build's own version always wins during substitution.

`engine/fmod` and `engine/audio` are both **standalone Gradle projects** (their own
`settings.gradle.kts`), each wired into the root build via `includeBuild` + `dependencySubstitution`
in `settings.gradle.kts` (same pattern TACZ/First Aid use). `engine/audio` is a direct dependency
of the root project (`implementation`/`jarJar` on `engine:audio:1.0`, see `build.gradle.kts`'s
comments for why both). `engine/fmod` is consumed the same way, but by **TACZ**
(`mods/TACZ-1.21.1/build.gradle.kts` depends on `engine:fmod:1.0`), not the root project directly
— TACZ's `com.tacz.guns.client.sound.fmod` package uses it to play weapon sounds from DNCity's
bundled banks (`FmodBankLocator` locates them via `ModList`/`IModFile.findResource`, since TACZ
and DNCity are separate mods/jars — see "Architecture: TACZ weapon sound (FMOD)" below). Both
modules can still be built/used standalone with `./gradlew -p engine/<fmod|audio> <task>` (they
reuse the root project's Gradle wrapper distribution; neither has its own `gradlew`).

- `engine/fmod` is a **hand-written JNI bridge** (`src/main/cpp/jni_fmod.cpp`, over a minimal,
  hand-declared subset of the FMOD Studio C API in `fmod_min.h`) plus a small Java wrapper
  (`src/main/java/com/iwei20/fmod/studio/FMODSystem.java`) — `System`
  (create/initialize/update/loadBankFile/getEvent/setListenerAttributes/loadPlugin), `Bank`
  (loadSampleData/unload), `EventDescription` (getPath/getId/getParameterNames/createInstance),
  `EventInstance` (start/stop/set3DAttributes/setParameterByName/getPlaybackState/release) — and
  `FModLoad` extracts/loads the bundled native FMOD libraries
  (`src/main/resources/<os>/<arch>/`) from the jar, including this module's own JNI glue DLL
  last. Building a bank-backed `FMODSystem` needs `System.InitFlags.AllowMissingPlugins` if the
  bank references a DSP/spatializer plugin (e.g. Resonance Audio) this build doesn't ship —
  otherwise the *whole* bank fails to load with `FMOD_ERR_DSP_NOTFOUND`; confirmed by hand that
  the flag alone is sufficient (no need to `System.loadPlugin` the actual plugin `.dll`).
  **`fmod_min.h`'s declared API surface is intentionally minimal** — only what `FMODSystem`'s
  public API needs, which is itself only what TACZ's `com.tacz.guns.client.sound.fmod` package
  actually calls, not a general-purpose FMOD binding; extending it means hand-declaring more
  functions/structs the same way (see `fmod_min.h`'s file comment for how the current set's
  correctness was verified without the real FMOD headers on hand: cross-checked byte-for-byte
  against this module's old jextract-generated bindings before they were deleted). Builds with
  plain MSVC on Windows (`CMakeLists.txt`, default generator — no clang/MinGW toolchain dance
  needed here, unlike `engine/audio`'s codec2 work, since there's no C99 `_Complex`-style
  constraint) linked directly against the vendored `fmod_vc.lib`/`fmodstudio_vc.lib` import
  libraries. `FModLoad` extracts this module's own DLL to a plain `File.createTempFile` location
  in the OS temp root, deliberately *not* into the same subdirectory as `fmod.dll`/`fmodstudio.dll`
  (unlike those, which must share a directory for Windows to resolve fmodstudio's dependency on
  fmod by name) — on this dev machine, an unsigned freshly-built DLL extracted into a *subdirectory*
  of the temp root got silently blocked by the machine's Application Control policy
  (AppLocker/WDAC), while a temp-root-level file (matching `engine/audio`'s `NativeLibrary`
  extraction pattern) did not.
  <br><br>**Why hand-written JNI, not jextract-generated `java.lang.foreign` bindings** (this
  module's approach until 2026-08-26 — the deleted bindings lived under
  `src/generated/java`/`com.iwei20.fmod.gen.*`, regenerated on Windows because upstream's
  checked-in bindings hardcode `long` as 8 bytes (LP64), which breaks on Windows where `long` is
  4 bytes (LLP64)): that approach needed JDK 24+ (see the "Toolchain" section above) —
  incompatible with players' actual Java 21. `FMODSystem`'s public API was kept identical on
  purpose, so switching needed zero changes in TACZ's `com.tacz.guns.client.sound.fmod` package.
- `engine/audio` is a JNI bridge over two things, both hand-written JNI (not jextract-generated
  FFI like `engine/fmod`) sharing one native library (`dncity_audio.{dll,so,dylib}`, built from
  `engine/audio/src/main/cpp` via `engine/audio/CMakeLists.txt`):
  - [miniaudio](https://miniaud.io) (vendored single-header at `engine/audio/vendor/miniaudio.h`,
    public domain/MIT-0 — see `vendor/README.md`) for local mic capture/speaker playback. Fixed
    mono/16-bit/48kHz PCM moved through lock-free ring buffers (`ma_pcm_rb`, see
    `jni_audio.cpp`), so the realtime audio device thread never attaches to the JVM. A native
    noise gate/VAD runs on captured samples before they hit the ring buffer (attack/release
    envelope follower with hysteresis) — see `NativeAudio.java`'s
    `setNoiseGateThresholdDb`/`isVoiceActive`/`getInputLevelDb`.
  - [codec2](https://github.com/drowe67/codec2) (vendored as a git submodule at
    `engine/audio/vendor/codec2`, LGPL-2.1) for the radio-voice tier's actual codec (MODE_2400/
    MODE_1200 — see `Codec2.java` and the mod's `voice/Codec2Codec.kt`). Handle-based JNI
    (`jni_codec2.cpp`): one `create`/`destroy` pair per stream, `encode`/`decode` per frame.
  - Both classes' `System.load` goes through the shared `NativeLibrary.java` (package-private):
    the built library is a classpath resource under `natives/<os>-<arch>/` (staged by
    `build.gradle.kts`'s `buildNativeAudio` task), which plain `System.loadLibrary` can't see
    (it only searches `java.library.path`) — `NativeLibrary.load()` extracts it to a temp file
    and `System.load`s the absolute path instead, idempotently so either class's static
    initializer can trigger it first.
  - **Build toolchain: clang targeting `x86_64-w64-mingw32` (MinGW-w64), not MSVC** — codec2's
    sources use real C99 `_Complex` arithmetic (`fsk.c`, `fmfsk.c`, `ofdm.c`, ...), which clang
    cannot provide against the MSVC ABI (MSVC's own CRT has never supported `_Complex`, and
    clang's MSVC-target `<complex.h>` is a no-op there — confirmed by hand: `complex float`
    fails to parse at all under `--target=x86_64-pc-windows-msvc`). mingw's libc genuinely
    supports it, so the whole module (not just codec2) builds against mingw instead; see
    `CMakeLists.txt`'s and `build.gradle.kts`'s comments for the exact flags. Linking uses only
    `clang.exe`/LLVM's own `ld.lld.exe` plus a MinGW-w64 sysroot's headers/import libraries
    (read-only, never executed) — no MinGW-w64-provided *binary* is invoked, which matters on
    this dev machine specifically because its Application Control policy (AppLocker/WDAC) blocks
    the only installed MinGW-w64 toolchain binary (`x86_64-w64-mingw32-g++`, via Cygwin) from
    running at all. `jni_audio.cpp`'s cross-thread noise-gate fields use `__atomic_load`/
    `__atomic_store` compiler builtins on plain fields rather than `std::atomic`, since this
    dev machine's MinGW-w64 sysroot has no libstdc++ headers installed (C runtime only, no
    `<atomic>`) — the builtins need no header. `./gradlew -p engine/audio build` (or the root
    build, which depends on it) has been verified working end-to-end on this dev machine,
    including a smoke test that loads the built DLL, calls `NativeAudio.init()`, and round-trips
    a codec2 encode/decode.

Large binary audio assets (`*.bank`) are tracked via Git LFS (see `.gitattributes`).

## Architecture: the radio system

Package root: `io.github.jwyoon1220.dncity`.

- **`item/RadioItem.kt`** + **`item/component/ModDataComponents.kt`** — the radio item and its
  persistent data component (power state, per-slot list, active slot index). `item/ModItems.kt`
  registers the item variants (`ALL_RADIOS`, added to the tools/utilities creative tab in
  `Dncity.kt`).
- **`radio/RadioSlot.kt`**, **`radio/RadioMode.kt`**, **`radio/RadioBand.kt`** — a radio's tuned
  state (frequency in kHz, mode, enabled flag) and the domain model for modes (AM/USB/FM, each
  with cutoff frequencies and a base noise level — for a future audio backend to consume) and
  bands (ITU designations ELF–THF, each with a kHz range, an optional max range in blocks —
  `null` means unlimited/ground-wave — and a tuning tolerance). `RadioBand.fromFrequencyKhz`
  maps a frequency to its band. `radio/RadioActions.kt` holds the server-side mutations
  (`tune`/`setActive`/`setPowered`) shared by the `/radio` command and the network payload
  handlers.
- **`network/RadioPayloads.kt`** + **`network/RadioNetworking.kt`** — client/server payloads and
  registration for radio state changes.
- **`command/RadioCommand.kt`** — `/radio`-style command registration.
- **`client/RadioScreen.kt`** — the client-side GUI opened when using a radio item
  (`RadioItem.screenOpener`, wired in `Dncity.onClientSetup`).
- **Voice** (`voice/` package) — two tiers sharing one client-side mixer/playback pump
  (`VoiceClientLoop.kt`, `VoiceAudioMixer.kt`), both riding on `engine/audio`'s `NativeAudio` for
  local mic capture/speaker playback:
  - **Close-range**: Opus (`OpusCodec.kt`, via the `opus-jni-rust` prebuilt JNI binding), no PTT,
    auto-sent whenever `NativeAudio`'s native VAD gates open. Server fan-out by distance only
    (`VoiceRelay.kt`); client applies distance-based gain (`CloseRangeVoice.kt`,
    `ClientVoiceReceiver.kt`).
  - **Radio**: PTT-gated (`client/ModKeyMappings.kt`'s `RADIO_PTT`, unbound by default),
    half-duplex with close-range (`VoiceClientLoop`'s capture routing). The codec is chosen by
    `RadioMode` (AM/USB/FM on the tuned slot), not by band: AM/FM use Opus (same `OpusCodec` as
    close-range — the "radio" sound for these two comes entirely from receive-side DSP, not a
    lossy transmit codec), USB uses codec2 (`Codec2Codec.kt`, wrapping `engine/audio`'s `Codec2`
    JNI class — the actual low-bitrate digital-voice codec). `RadioTransmitter.kt` branches
    accordingly; for codec2 it also buffers mic audio across its mode-dependent frame size
    (`Resampler.kt`'s `AntiAliasDownsampler`/`upsample` handle the 48kHz↔8kHz conversion codec2
    needs — the downsampler is a real anti-aliasing filter, not a box-average, since that was
    audibly adding its own coloring on top of codec2's inherent low-bitrate character). Server
    fan-out (`RadioRelay.kt`) is authoritative on both the transmitter's held-radio state and
    every listener's — frequency must match within `RadioBand.tuningToleranceKhz` and, for
    line-of-sight bands, distance must be within `RadioBand.maxRangeBlocks` (VHF-and-up; tuned
    for a ~7.5km²/7500-block-square map — see `RadioBand.kt`'s doc comment). The relay payload
    carries the transmitter's position directly rather than leaving the client to resolve it via
    `Level.getEntity` — that only works within Minecraft's own (much shorter) entity-tracking
    radius, which silently capped every band's real range below what `maxRangeBlocks`/`null`
    said. Client-side (`ClientRadioReceiver.kt`, `RadioDsp.kt`, `RadioVoice.kt`) applies per-mode
    bandpass filtering and mode-flavored static (AM crackle, FM hiss with a capture-effect noise
    curve, USB warble) before handing decoded audio to the shared mixer.

`Dncity.kt` is the `@Mod` entry point: registers the `ModBlocks`/`ModItems`/
`ModDataComponents` deferred registries on the mod bus, hooks command registration and payload
handlers, and dispatches client- vs. server-only setup via `runForDist`.

## Architecture: TACZ weapon sound (FMOD)

TACZ (`mods/TACZ-1.21.1`) plays its own gun sounds — shoot, reload, bolt, draw, put-away —
through FMOD Studio against DNCity's bundled banks (`src/main/resources/fmod/*.bank`) instead of
its normal vanilla-sound-engine OGG files, entirely in TACZ's own code
(`com.tacz.guns.client.sound.fmod` package), using `engine/fmod`'s `FMODSystem` wrapper:

- **`FmodWeaponSoundManager`** — owns the client-side `FMODSystem` (best-effort init: any
  failure falls back to silence, not vanilla sounds, for the actions it covers), loads
  Master/Master.strings/Weapons banks via `FmodBankLocator` (which locates DNCity's bundled
  resources through `ModList`/`IModFile.findResource`, since TACZ and DNCity are separate
  mods/jars), updates the FMOD listener from the client camera and calls `System::update` once
  per client tick, and exposes `playShoot`/`playReloadTactical`/`playReloadEmpty`/`playBolt`/
  `playDraw`/`playPutAway`/`stopLast`. `playShoot` also sets the `IsOutside` local parameter
  (present on Fire events, confirmed by dumping event parameters) from
  `entity.level().canSeeSky(entity.blockPosition())` when the event defines it, so gunshots
  actually sound different indoors vs. outdoors per the bank's own authored acoustics.
- **`WeaponFmodEvents`** — TACZ-gun-id → bank-event-path maps, one per action
  (`GUN_SHOOT_EVENTS`, `GUN_RELOAD_TACTICAL_EVENTS`, `GUN_RELOAD_EMPTY_EVENTS`, plus
  category-keyed `DRAW_EVENTS`/`HOLSTER_EVENTS`). Every weapon folder in the bank names its
  events inconsistently (compare M4A1's `ReloadNormalMagIn` to MP5's `Reload Start` to AK47's
  `AK102MagIn`), so these maps were built by hand from a dump of every event path in the bank,
  not derived programmatically — extending them for a new gun means dumping that gun's mapped
  weapon folder's events the same way (see `WeaponFmodEvents`'s class doc and
  [[project-tacz-fmod-weapon-sound]] memory for the exact method) rather than guessing a naming
  pattern. Guns with no gun-specific reload event in their folder (verified empty, not just
  unmapped — G36/M24-family/M249-family/SR16-family/M32A1/Uzi) fall back to a single generic
  `Shared/Rifles Shared/Mag In` placeholder shared by all such guns.
- **`FmodBankLocator`** — copies a resource from another loaded mod's `IModFile` out to a real
  temp-directory file, since FMOD's native `loadBankFile` needs an actual filesystem path.

`SoundPlayManager` (TACZ's general sound dispatcher) calls straight into
`FmodWeaponSoundManager` for these actions — no vanilla `SoundEvent` playback happens for them at
all, so there's no double-audio concern to manage when changing which bank event an action maps
to.

## Architecture: `/music` (server-sent playback, no resourcepack)

Package `io.github.jwyoon1220.dncity.music` + `network/MusicPayloads.kt` +
`command/MusicCommand.kt`. `/music play <name>` looks for `<name>.{mid,midi,ogg,flac,mp3,opus}`
in `config/dncity/music/` (in that order) and broadcasts it to every online player as chunked
`MusicAssetChunkPayload`s (`MusicAssetSender`/`MusicClientReceiver`, 250KB/packet — soundfonts and
tracks can be large); `/music stop` broadcasts `MusicStopPayload`. No playlists, no per-player
targeting (explicitly out of scope).

Two independent, format-specific client-side players, chosen by extension:
- **`.mid`/`.midi`** — `MidiPlayer`, `javax.sound.midi`'s public API only (`Synthesizer`/
  `Sequencer`), loaded with a soundfont the server pushes on login (`MusicServerEvents`, cached
  from `config/dncity/soundfont.sf2`) — plays on Java Sound's own default output device, not
  mixed into this mod's `VoiceAudioMixer`.
- **`.ogg`/`.flac`/`.mp3`/`.opus`** — `AudioPlayer`, via `engine/fmod`'s `FMODCoreSystem` (Core
  `FMOD::System`, direct `createSound`/`play` file playback — distinct from the Studio-event API
  `FMODSystem` wraps for TACZ weapon sounds above). A **second, independent** FMOD system
  instance from TACZ's own Studio system — deliberately not shared, so this feature doesn't
  depend on TACZ being installed. `AudioPlayer.tick()` (pumping `FMODCoreSystem.update()`) is
  wired into the same per-client-tick hook as `VoiceClientLoop.tick()` in `Dncity.kt`.

`engine:fmod` is consumed directly by the root project (via `includeBuild("engine/fmod")` in
`settings.gradle.kts` plus `implementation`/`jarJar` in `build.gradle.kts`), not only indirectly
through TACZ's own jarJar as before — needed since `AudioPlayer` must work even without TACZ.

## Editing the mod metadata template

`mod_authors`, `mod_description`, and version/range values live in `gradle.properties`, not in
`src/main/templates/META-INF/neoforge.mods.toml` directly — the template only has `${...}`
placeholders expanded by the `generateModMetadata` task.
