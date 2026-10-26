# AGENTS.md — paper-scoring-template

Follow the workspace rules in `../AGENTS.md`. Read [README.md](README.md) for project scope, setup, and standard validation commands.

## Documentation ownership

Keep project documentation in the root `AGENTS.md` and `README.md` only. Product content and required license files retain their existing locations. Workspace progress and evidence belong in `../TODO.md`; unresolved issues belong in `../PROBLEMS.md`. These workspace records are machine-local and are not published.

## Development boundaries

Java sources live under `src/main/java/com/jlucraft/scoring/`; plugin settings live in `src/main/resources/`. Activity checks live in `src/activityTest/`. The build uses Java 25 and the Paper API declared in `build.gradle.kts`. Use the installed Gradle toolchain and existing `build/` directory.

Change only the affected subsystem. Use existing dependencies and build directories. Finish each batch before validation; select one validation channel and reuse successful evidence for unchanged inputs. Report skipped checks and device execution limits explicitly.
