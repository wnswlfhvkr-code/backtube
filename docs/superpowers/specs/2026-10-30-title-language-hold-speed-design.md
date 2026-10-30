# Title language and hold speed design

Source baseline: `b966504dbcaa1b4a51a04080312ada227d9c8e95` (1041).

## Intent and constraints

- With the default content language selected, requests follow the app's visible language. An explicitly selected content language remains authoritative. The channel, search, and related list paths continue to use one extractor localization. No per-item network request or machine translation is added.
- A long press on the MAIN video surface temporarily plays at 2.0x while playback is active. Release, cancel, scroll, pause, completion, player teardown, or a new engine ends the hold. The prior playback parameters are restored only to the same engine. Popup resizing, double-tap seek, normal taps, and volume/brightness swipes retain priority.
- A small `2배속` indicator is visible only during the hold. Temporary speed never updates saved playback preferences.

## Approach

Content: resolve the default content locale from the app locale, preserving explicit choices. Read the saved app-language preference before its first-activity migration, and synchronize the extractor on activity resume or content-settings exit. Invalidate cached metadata only when the effective extractor localization changes. This addresses the confirmed app/system locale split; it does not claim that every YouTube list renderer returns the original title.

Speed: keep hold state in a small controller, use the ExoPlayer's in-memory playback parameters, and let the MAIN gesture/UI lifecycle start and end it. Avoid the existing `Player.setPlaybackSpeed`, which writes preferences. Tests cover controller state and locale resolution, followed by build and available Android gesture checks.

## Limits

The user's actual content language preference and raw YouTube responses are unavailable; a public comparison request from this environment returned proxy HTTP 403. If English remains with explicit Korean selection after a fresh request, the extractor's provider response needs separate diagnosis. No app release or main merge is part of this change.
