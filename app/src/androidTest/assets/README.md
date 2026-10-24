# Synthetic listening-mode fixture

`listening-mode-fixture.mp4` contains 30 seconds of black 160×90 video (10 fps) and
silent mono audio. Both signals were generated locally; no downloaded or protected
media is included. The test pauses/seeks at 7 seconds and loops the file during setup.

Generation command (FFmpeg, H.264 Baseline + AAC for API 23 compatibility):

```sh
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i color=c=black:s=160x90:r=10 \
  -f lavfi -i anullsrc=r=44100:cl=mono -t 30 \
  -c:v libx264 -profile:v baseline -pix_fmt yuv420p \
  -c:a aac -b:a 16k -movflags +faststart listening-mode-fixture.mp4
```
# Offline video fixtures

`listening-mode-fixture.webm` is the same 30-second black/silent generated fixture
encoded as VP8/Vorbis, 160×90 at 10 fps. Pass `offlineVideoFixture=webm` for listening
and orientation tests when the emulator's H.264 decoder is unavailable. All frame,
position, listening-mode and navigation assertions remain enabled; this does not
establish H.264 coverage. Generated locally with FFmpeg lavfi `color` and `anullsrc`,
`-c:v libvpx -threads 1 -b:v 80k -c:a libvorbis -b:a 32k`; CC0-1.0.

`offline-generated-av.mp4` and `offline-generated-audio.m4a` were generated locally
on 2026-10-21 using FFmpeg lavfi `testsrc2` and `sine`, with no downloaded material.
The authors dedicate these generated fixtures to the public domain (CC0-1.0).
The MP4 contains 3 seconds of H.264 320x180 at 12 fps and AAC 48 kHz audio; the M4A
contains 3 seconds of AAC 48 kHz audio only. `ffprobe` verified both stream layouts.

`offline-generated-av.webm` uses the same generated sources and CC0 dedication,
with VP8 320x180 at 12 fps and Vorbis 48 kHz audio for codec-independent routing
checks. Pass instrumentation argument `offlineVideoFixture=webm` to select it;
the default remains H.264/AAC MP4. Keep results for both codecs separate.
