# CLAUDE.md

This file guides Claude Code when working in this repository.

## Language

- Talk to the user in **Traditional Chinese (繁體中文, Taiwan usage)**.
- Code, identifiers, commit messages and code comments are in **English**.
- User-facing strings live in resources: `values/strings.xml` (English) and `values-zh-rTW/strings.xml` (Traditional Chinese). Never hardcode UI text.

## Project overview

**Wayla Maps** is an Android navigation app with a companion character who calls out every turn in her own voice, built for Taiwan's roads. It works for both **cars** and **scooters**:

- In a car, audio goes through the car speakers (later via Android Auto).
- On a scooter, audio goes through a Bluetooth helmet headset; the phone sits on a handlebar mount.

The voice is generated with **GPT-SoVITS**, not a stock TTS voice. The character's name is the working name "Wayla" until decided otherwise.

What makes it different from general navigation apps is Taiwan-specific behaviour:

- Two-stage left turns (兩段式左轉) and waiting zones (待轉區) for scooters
- Roads closed to scooters (快速道路, some bridges and viaducts) avoided in scooter mode
- Speed cameras, average-speed zones (區間測速) and traffic-enforcement intersections (科技執法)
- Live traffic incidents from TDX (交通部運輸資料流通服務平臺)
- Correct reading of Taiwanese addresses (段、巷、弄、號)

## Tech stack

- **Platform:** Android only. Kotlin, no Flutter or other cross-platform layer.
- **UI:** Jetpack Compose, Material 3
- **Build:** Gradle with Kotlin DSL (`build.gradle.kts`), version catalog in `gradle/libs.versions.toml`
- **Min SDK:** 26
- **Package:** `cc.nekocat.waylamaps`
- **Navigation:** Google Navigation SDK for Android, with built-in voice guidance set to silent; turn events come from the turn-by-turn data feed
- **Audio:** Media3 ExoPlayer
- **Networking:** OkHttp / Retrofit, Kotlin Coroutines and Flow
- **Local data:** Room (speed cameras, voice cache index)
- **Background work:** a foreground service for active navigation
- **Car (later):** Android for Cars App Library (`androidx.car.app`)

Prefer AndroidX and Jetpack libraries. Ask before adding a new third-party dependency.

## Commands

```bash
./gradlew assembleDebug        # build debug APK
./gradlew installDebug         # install on connected device
./gradlew test                 # unit tests
./gradlew connectedAndroidTest # instrumented tests (device needed)
./gradlew lint                 # Android lint
```

Run `./gradlew test` and `./gradlew lint` before saying a change is done.

The system JDK is 11, which is too old for AGP 9. Run Gradle with Android Studio's bundled JBR: `JAVA_HOME=/opt/android-studio/jbr ./gradlew ...`. The SDK is at `~/Android/Sdk` (set in the gitignored `local.properties`).

## Architecture

- Single-activity app, Compose navigation between screens
- MVVM: Composable → ViewModel (StateFlow) → Repository → data sources
- Dependency injection with Hilt
- Keep Android framework code out of domain logic so it can be unit tested

Planned packages under `cc.nekocat.waylamaps`:

| Package | Responsibility |
|---|---|
| `ui` | Compose screens, theme, character view |
| `navigation` | Navigation SDK wrapper, route and turn events |
| `voice` | Mapping events to phrases, playback, cache, GPT-SoVITS client |
| `alerts` | Speed cameras, average-speed zones, enforcement points |
| `traffic` | TDX client and incident handling |
| `data` | Room database, DAOs, repositories |
| `service` | Foreground navigation service |

## Voice pipeline rules

This is the core of the app; keep it reliable.

1. **Fixed phrases** (turn directions, distances, alerts) are pre-generated audio files bundled in the app, so they work offline.
2. **Dynamic phrases** (street names, traffic incidents) are requested from the GPT-SoVITS server and cached on device.
3. If the server is slow or unreachable, **fall back to the system TTS**. Never stay silent on a turn instruction.
4. Request **transient audio focus with ducking** (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) so music lowers during prompts.
5. Play prompts as **media audio**, not the call/SCO channel, so helmet headsets keep good quality.
6. Prompts must be short. Riders hear them through wind noise; one instruction per sentence.
7. Priority when prompts collide: turn instruction > speed camera > traffic incident > character chatter.

## Taiwan domain notes

- Speed camera data comes from Taiwan government open data (政府資料開放平臺). Do not copy data from commercial apps such as 神盾.
- Do not use the system Geofencing API for cameras (too few geofences). Check distance and heading on each location update instead, with a bounding-box query in Room.
- Alert only for cameras ahead in the direction of travel.
- Scooter mode must never route onto roads closed to scooters.

## Secrets

- Never commit API keys. Google Maps Platform and TDX keys go in `local.properties` (gitignored) and are read through the Secrets Gradle Plugin or `BuildConfig`.
- The GPT-SoVITS server URL is configuration, not hardcoded.
- Check `git status` before committing; `local.properties`, `build/`, `.gradle/` and `.idea/` must not be tracked.

## Safety

- The app is a companion, not a co-driver. No feature should require the user to look at or touch the phone while moving.
- Do not add UI that needs text input while navigation is active.
- Speed-related prompts state facts (limit, camera ahead); they never encourage speeding or evading enforcement.

## Voice and character assets

- Only use voices that the author recorded or has permission to use. Do not train or ship voices cloned from real people or existing commercial characters without permission.
- Keep audio assets under `app/src/main/assets/voice/<language>/`.

## Git

- Branch: `main`
- Commit messages: short imperative English, e.g. `Add speed camera importer`
- One logical change per commit