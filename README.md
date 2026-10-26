# Paper Scoring Template

The JLUCraft Paper plugin for reporting game scores and accumulated activity to the federation host.

## Setup and structure

Java sources live under `src/main/java/com/jlucraft/scoring/`; plugin settings live in `src/main/resources/`. Activity checks live in `src/activityTest/`. The build uses Java 25 and the Paper API declared in `build.gradle.kts`. Use the installed Gradle toolchain and existing `build/` directory.

## Validation

Select one validation channel for a batch and reuse successful results for unchanged inputs. Standard commands:

```sh
gradle build
```

Read [AGENTS.md](AGENTS.md) before making changes.
