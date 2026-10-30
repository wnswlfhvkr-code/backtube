# Title language and hold speed design

Source baseline: `b966504dbcaa1b4a51a04080312ada227d9c8e95` (1041).

## Intent and constraints

- With the default content language selected, requests follow the app's visible language. An explicitly selected content language remains authoritative. The channel, search, and related list paths continue to use one extractor localization. No per-item network request or machine translation is added.
- A long press on the MAIN video surface temporarily plays at 2.0x while playback is active. Release, cancel, scroll, pause, completion, player teardown, or a new engine ends the hold. The prior playback parameters are restored only to the same engine. Popup resizing, double-tap seek, normal taps, and volume/brightness swipes retain priority.
- A small `2배속` indicator is visible only during the hold. Temporary speed never updates saved playback preferences.
- In the inline video surface, a one-finger upward swipe enters the existing fullscreen view. In fullscreen, a two-finger downward swipe returns to the inline view. The detector requires a deliberate vertical distance and rejects page scrolling, horizontal swipes, pinch, and small motion. The existing fullscreen toggle keeps the current playback engine, position, and play state.

## Approach

Content: resolve the default content locale from the app locale, preserving explicit choices. Read the saved app-language preference before its first-activity migration, and synchronize the extractor on activity resume or content-settings exit. Invalidate cached metadata only when the effective extractor localization changes. This addresses the confirmed app/system locale split; it does not claim that every YouTube list renderer returns the original title.

Speed: keep hold state in a small controller, use the ExoPlayer's in-memory playback parameters, and let the MAIN gesture/UI lifecycle start and end it. Avoid the existing `Player.setPlaybackSpeed`, which writes preferences. Tests cover controller state and locale resolution, followed by build and available Android gesture checks.

Fullscreen swipes: use a small gesture state machine with one transition per touch sequence. While an inline touch is undecided, the player briefly retains the touch; a downward or horizontal intent hands it back to the detail page. A second pointer cancels temporary speed and the existing tap detector. The two-finger candidate starts only before a one-finger scroll and rejects pinch or sideways motion. Popup gestures remain separate.

## Limits

The user reported app and content language settings both at defaults, with Korean UI. If the device's OS locale is also Korean, the default-locale adjustment makes no difference for this case. The list extractor reads the response item's `title`; the detail extractor prefers `playerResponse.videoDetails.title`, documented by NewPipeExtractor v0.26.5. Raw YouTube responses are unavailable; a public comparison request from this environment returned proxy HTTP 403. The user's exact title mismatch remains unverified and may require provider-response diagnosis. No app release or main merge is part of this change.
