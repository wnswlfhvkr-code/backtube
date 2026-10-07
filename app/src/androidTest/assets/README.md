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
