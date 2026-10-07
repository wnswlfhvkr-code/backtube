# Backtube prior-update integration and real audio focus verification

Status: pre-integration real-focus tests compile; Android runtime pending.

## Report provenance

The user described quiet audio and no automatic restart after another video app
exited on the existing installation. They explicitly had not tried the 19b4522
test APK. Existing synthetic-callback results at that head do not prove real
cross-app focus exchange or a physical-device resolution.

## Platform contract checked 2026-10-07

[Android audio focus](https://developer.android.com/media/optimize/audio-focus)
distinguishes transient interruption (retain intent; resume/unduck after GAIN)
from permanent LOSS (explicit user Play is required). API23 uses the legacy
request/abandon API. API26 adds system ducking; Backtube requests
`setWillPauseWhenDucked(true)` and handles its callback itself. API31+ can also
fade/mute at the system layer. For target35+, focus requests require a top app
or foreground service. App gain and system output volume are separate; testing
ExoPlayer gain is not acoustic loudness measurement.

[Android17 background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio)
requires visible activity or non-short foreground service for background audio
interactions even below target37. Target37 additionally requires while-in-use
service capability (apart from the documented alarm exception). Backtube targets
35 and compiles against37: compile SDK does not opt it into target37 rules.
The new restrictions cover focus, playback and volume APIs. API23/35 results
cannot establish API37 behavior. No API37 runtime result is claimed here.

The app uses a media-playback foreground service. Default background mode keeps
its notification during pause; alternative minimize-none/video behavior can stop
foreground status. This is source inspection, not proof of every lifecycle path.

## Intended evidence

Use the existing generated-WAV PlayerService fixture and a separate test APK
Activity owning a MediaPlayer/focus request. Assert distinct UIDs and log real
grant/loss/gain events. Test transient return, app duck restoration, permanent
loss without an unsolicited restart, manual Play at normal gain, explicit pause
and timer expiry. Do not download remote media or change user device settings.

PR4's original source, passing run and delivered APK remain unchanged while this
work proceeds on `integration/approved-updates`.

## Pre-integration local validation

Added seven `RealAudioFocusHandoffTest` cases, a framework-only
`AudioFocusOwnerActivity` in the instrumentation APK manifest, and generated WAV
support. The test asserts that the owner's UID differs from the instrumented
Backtube UID. Both real focus requests and OS callbacks are logged. The seventh
case explicitly retains an app duck of 0.2 through user pause/abandon, then checks
that manual Play restores 1.0 without an intervening GAIN callback.

`assembleDebug assembleDebugAndroidTest runCheckstyle` passed; after the seventh
case was added, `assembleDebugAndroidTest runCheckstyle` passed again.
`testDebugUnitTest`: 233 tests, zero failures/errors/skips (37 XML reports).
Diff whitespace and shell syntax checks passed. These are local compilation and
JVM results; the seven new tests still require actual Android execution.
