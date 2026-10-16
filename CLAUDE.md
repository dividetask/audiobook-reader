# CLAUDE.md — Project Guidelines for Audiobook Reader

## Project Overview

Open-source Android audiobook player with Audible integration. Downloads,
converts (AAX → DRM-free), and plays audiobooks. Built with Kotlin, Jetpack
Compose, Media3/ExoPlayer, Hilt, Room, and FFmpegKit.

- **Source** (`app/src/main/java/com/dividetask/audiobookreader/`) — Kotlin / Jetpack Compose.
- **Build** — Gradle (Kotlin DSL), `./gradlew :app:assembleDebug`.

## Critical Rules

### Do not loop on errors — stop and explain

- If an approach fails twice, stop immediately. Do not retry the same strategy.
- Explain what went wrong, what was attempted, and suggest alternatives — let me
  decide how to proceed.
- Do not silently retry file operations, compilations, or code execution hoping
  for a different result.
- If a tool call or code execution returns an error, analyze the error before
  taking any further action.
- When stuck on a problem, present the situation clearly rather than burning
  through tokens on repeated attempts.

### Ask questions often

- Ask questions often, especially when there is ambiguity or you believe I have
  made a mistake.

### Never use question prompts

- Never use the `AskUserQuestion` tool or any other interactive
  question/multiple-choice prompt. Write the question(s) as plain prose in the
  chat.

### Stop after asking questions

- Whenever you have a question, or multiple questions, stop immediately after
  asking them. End the turn — no further tool calls or implementation until I
  reply.

### Number your questions

- When asking more than one question in a single message, number them (`1.`,
  `2.`, …) so I can answer "1: …".
- Do **not** number anything else. Lists, sections, summaries, and recaps use
  letters (`A.`, `B.`, …) when order matters, or bullets when it doesn't.
- When a single question offers multiple options, label the options with
  letters (`A`, `B`, …), not numbers.

### When a referenced thing is missing, stop and ask

- If I reference a branch, file, function, document, or anything else that you
  can't find, do not silently proceed against the closest match — tell me what
  you looked for and where, and ask.

### Don't silently change the design

- If a stated rule conflicts with another rule, or can't produce what's being
  asked for, say so and ask — never quietly modify, extend, or "fix" my rule to
  make a deliverable work.

### Keep answers short

- Short and concise. No fluff. No flattery.

## Android build versioning

The build automation (`build_branches.sh`) builds pushed `claude/*` branches
and commits each new APK as `versions/vMAJOR.MINOR.apk`, using the highest
version already on the branch with the minor bumped by one. Several commits
can be pushed before the next build, so the app's `versionName` has three
parts, e.g. `v0.037 a (e687)`:

- **Number** (`v0.037`) — set by hand. The predicted APK filename: the highest
  `vMAJOR.MINOR.apk` in the versions folder on the current branch, with the
  minor + 1. Keep the same digit width as the existing files.
- **Letter** (`a`) — set by hand. `a` for the first commit after a new APK
  lands. Each further commit pushed before the next APK lands moves to `b`,
  `c`, …. When a new APK lands, the number goes up and the letter resets to
  `a`.
- **Commit** (`(e687)`) — added by Gradle at build time: the first 4
  characters of the commit the APK was built from. Never write it by hand.

Rules:

- `versionCode` is generated at build time from a timestamp
  (`(System.currentTimeMillis() / 60000).toInt()` — minutes since epoch). This
  ensures every build is always higher than the last, regardless of branch, so
  APKs from different `claude/*` branches can be installed over each other
  without uninstalling first. Never set `versionCode` by hand.
- Bump the number/letter on every commit that changes the app, and put the
  hand-set part at the end of the commit message, e.g.
  `Fix playback error handling (v0.037 b)`.
- Enable `buildConfig = true` so the app can show `BuildConfig.VERSION_NAME`.

## After pushing — read the build result

There is no Android SDK in the agent's environment, so the build automation
is the only compile check. After every push:

- Schedule a check-in (5 min if active branch, 15 min if quiet).
- At each check-in, read `build-logs/build.log` and verify the commit SHA
  matches your push.
- **Built** → confirm new APK in `versions/`. Done.
- **Failed** → read the build log, fix the error, push, and check again.
- **No build commit after ~25 minutes** → the script may not be running.
  Stop and tell me.
- Never force-push a `claude/*` branch: the build commits and APKs live on it.
- Always pull before pushing.
- Don't stack new work on top of a build that failed.

## Environment

- No Android SDK available in agent environment.
- Build verification via `build_branches.sh` on user's machine.
- Signing: shared keystore committed to repo (`app/audiobookreader.keystore`).

## File Structure

```
.
├── CLAUDE.md
├── README.md
├── app/
│   ├── build.gradle.kts
│   ├── audiobookreader.keystore
│   └── src/main/java/com/dividetask/audiobookreader/
│       ├── AudiobookApp.kt / MainActivity.kt
│       ├── audible/          # Audible API, auth, download, conversion
│       ├── data/             # Room database, entities, repository
│       ├── debug/            # AppDebugLog
│       ├── di/               # Hilt modules
│       ├── navigation/       # Compose Navigation
│       ├── player/           # PlaybackService, PlaybackController
│       └── ui/               # Screens (main, player, settings, audible)
├── build.gradle.kts
├── settings.gradle.kts
├── versions/                 # Built APKs (committed by build script)
└── build-logs/               # Build output (committed by build script)
```
