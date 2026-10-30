# Title language and hold speed implementation plan

**Goal:** Align default content requests with the app language and add temporary 2.0x MAIN video hold playback.

**Architecture:** Resolve the default locale in `Localization`; keep explicit content language intact. Use a tested hold controller and the existing MAIN gesture listener, with an indicator in `player.xml`.

**Tech Stack:** Android Java/Kotlin, Rx/ExoPlayer, JUnit, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-30-title-language-hold-speed-design.md`.

## Tasks

1. Add tests for default app locale and explicit content language; run RED; change `Localization`; run GREEN. Check cache invalidation when content language changes.
2. Add controller tests for start, release, cancel, 2.0x original speed, non-playing and engine replacement; run RED; implement controller; run GREEN.
3. Wire MAIN long press and lifecycle cancellation, add the short indicator, and verify tap/double-tap/swipe/popup behavior with focused Android checks where available.
4. Run the affected unit suite, debug build, lint/checkstyle, inspect the exact diff, and record PASS/FAIL/NOT RUN. Preserve the branch without main merge or release.
5. Add tests for inline upward and fullscreen two-finger downward swipes, plus downward page scroll, horizontal motion, pinch, small movement, pointer lift, cancel, and repeated events. Implement a state machine and connect it to the existing fullscreen toggle without changing popup gestures. Review and verify the final head.

## Review focus

- Explicit English content language must remain English even when the app UI is Korean.
- A 1.25x or 1.5x setting must be restored without writing preferences.
- A long press must not begin while paused, seeking, swiping, or in popup mode.
- A destroyed or replaced player engine must not receive a late speed restore.
- Indicator visibility and saved speed must not survive cancel or UI teardown.
