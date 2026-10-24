# Contributing to Corner Player

Thank you for your interest in contributing to Corner Player, picture in picture for Android TV, playing your own camera or stream over any app! Contributions are welcome, and this page
keeps the process clear and simple.

## Ways to Contribute

- Report bugs or request features using [GitHub Issues](../../issues).
- Improve documentation (README, code comments, the docs folder).
- Submit Pull Requests (PRs) with bug fixes, improvements, or new features.

## Where Things Are

- Playback and the corner window (`app/src/main/java/`), Media3 (ExoPlayer).
- The TV screen with its rows, and the module card for the Clean Your TV launcher.

## Code Style

- Plain Java 17 and the Android SDK, no extra libraries unless they really pay for themselves.
- Match the code around you: its naming, comment style and size.
- Comments say why, not what.
- Keep things simple (KISS principle). The APK stays small.

## Pull Request Guidelines

1. **Fork** the repository.
2. Create a new branch for your feature or bugfix:
   ```bash
   git checkout -b feature/my-new-feature
   ```
3. Commit changes with clear, descriptive messages.
4. Make sure it builds and the tests pass: `./gradlew assembleRelease testReleaseUnitTest lintRelease`.
5. Open a Pull Request and describe your changes.

## Tests

- The JVM tests run with `./gradlew testReleaseUnitTest`. Add tests for logic that doesn't need a TV.
- Test on a real TV when you can: a camera or stream in each corner and size, Stop, and a video app underneath. Say what you tested in the PR, and what you couldn't.
- Screenshots in issues and PRs: hide IP addresses, network names, device names and accounts.

## Community

We value respectful collaboration. By contributing, you agree to follow our [Code of Conduct](CODE_OF_CONDUCT.md).

Thank you for helping make this project welcoming!
