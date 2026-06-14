# Contributing to Idle Fantasy

Thanks for your interest in contributing. Here is how to get involved.

## Reporting bugs

Open a GitHub issue and fill out the bug report template. Include your app version, Android version, device model, and steps to reproduce.

## Suggesting features

Open a GitHub issue with a clear description of the feature and why it fits the game. Feature requests are not guaranteed to be implemented, but all suggestions are read.

## Submitting a pull request

1. Fork the repository and create a branch from `main`
2. Make your changes
3. Build and test locally: `./gradlew assembleRelease`
4. Open a pull request against `main` with a clear description of what changed and why

### Guidelines

- Keep PRs focused. One feature or fix per PR.
- Do not add dependencies without prior discussion.
- If you add or change any user-visible strings, update all locale files under `app/src/main/res/values-*/strings.xml`.
- Do not add Room database migrations. New player state belongs in the existing JSON blob columns (`flags`, `inventory`, etc.) using default values for new fields.
- Match the existing code style. Jetpack Compose, Kotlin coroutines, Hilt DI, Room.

### What is likely to be accepted

- Bug fixes with a clear reproduction case
- Balance adjustments backed by reasoning
- New content (items, quests, enemies) that fits the existing game structure

### What is unlikely to be accepted

- Large architectural changes
- Features that conflict with the idle game design (mechanics that require frequent active management)
- UI overhauls

## License

By contributing, you agree that your contributions will be licensed under the GNU General Public License v3.0.
