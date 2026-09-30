# backtube

유튜브 공유 링크를 받아 백그라운드에서 오디오를 듣는 개인용 Android 앱입니다. NewPipe 0.29.1을 기반으로 하며 기존 추출기와 재생 엔진을 사용합니다.

- 화면을 꺼도 백그라운드 오디오 재생
- 추천곡 연속 재생, 추천 영상·채널 제외, 마지막 대기열과 위치 복원
- 이전 곡·처음부터 분리, 반복, 자동 이어듣기, 취침 타이머
- **데이터 절약 · 오디오 전용**: 영상 대체 소스와 일반 라이브 영상 매니페스트를 사용하지 않음. 오디오 전용 소스가 없으면 안내 후 건너뜀
- **절약 / 균형 / 고음질**: 제공된 오디오 중 낮은 비트레이트 / 128 kbit/s에 가까운 값 / 높은 비트레이트 선택
- 절약 모드의 요금제 네트워크: 낮은 오디오 품질과 작은 썸네일. 이미지 끄기 설정은 유지

재생 화면의 ⋮ 메뉴에서 데이터 절약과 오디오 품질을 바꿉니다. 설정 → 영상 및 오디오에서도 변경할 수 있습니다. 품질 변경은 현재 위치와 일시정지 상태를 유지합니다. 네트워크가 요금제 상태로 바뀌면 현재 오디오를 즉시 낮은 품질로 다시 선택하며, 해제되면 선호 품질로 복원합니다. 썸네일은 다음 이미지 요청부터 적용됩니다. 영상 보기를 직접 선택하면 영상이 재생됩니다.

## 빌드

JDK 21, Android SDK가 필요합니다. `ANDROID_HOME`에 SDK 경로를 지정한 뒤 저장소 루트에서 실행합니다.

```powershell
.\gradlew.bat :app:assembleRelease '-DpackageSuffix=.personal' '-DversionNameSuffix=-backtube.7' '-DversionCodeOverride=1021' '-DskipFormatKtlint' '-Pandroid.overridePathCheck=true' --console=plain
```

결과는 `app/build/outputs/apk/release/app-release-unsigned.apk`입니다. 배포에는 개인 서명키로 서명해야 합니다. 기존 개인용 설치와 같은 `org.schabi.newpipe.personal` 패키지를 유지했으며 같은 키로 서명하면 앱 삭제 없이 업데이트할 수 있습니다. 개인 키와 암호는 저장소에 포함하지 않습니다.

생성 WAV·합성 메타데이터를 이용해 재생 정책과 회귀 동작을 검증합니다. 실제 YouTube 네트워크 재생, 갤럭시 화면 꺼짐, 데이터 절감량은 별도 실측 대상입니다. 별도 캐시 엔진은 추가하지 않고 기존 미디어 캐시를 사용합니다.

## 기반과 라이선스

[TeamNewPipe/NewPipe](https://github.com/TeamNewPipe/NewPipe) 기반. GPL-3.0-or-later를 따르며 원본 소스와 저작권 표시를 유지합니다. 아래는 업스트림 프로젝트 안내입니다.

---
<p align="center"><a href="https://newpipe.net"><img src="assets/new_pipe_icon_5.png" width="150"></a></p> 
<h2 align="center"><b>NewPipe</b></h2>
<h4 align="center">A libre lightweight streaming front-end for Android.</h4>

<p align="center"><a href="https://f-droid.org/packages/org.schabi.newpipe/"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on-en.svg" alt="Get it on F-Droid" width=206/></a></p>

<p align="center">
<a href="https://github.com/TeamNewPipe/NewPipe/releases" alt="GitHub NewPipe releases"><img src="https://img.shields.io/github/release/TeamNewPipe/NewPipe.svg" ></a>
<a href="https://github.com/TeamNewPipe/NewPipe-nightly/releases" alt="GitHub NewPipe nightly releases"><img src="https://img.shields.io/github/release/TeamNewPipe/NewPipe-nightly.svg?labelColor=purple&label=dev%20nightly"></a>
<a href="https://www.gnu.org/licenses/gpl-3.0" alt="License: GPLv3"><img src="https://img.shields.io/badge/License-GPL%20v3-blue.svg"></a>
<a href="https://github.com/TeamNewPipe/NewPipe/actions" alt="Build Status"><img src="https://github.com/TeamNewPipe/NewPipe/actions/workflows/ci.yml/badge.svg?branch=dev&event=push"></a>
<a href="https://hosted.weblate.org/engage/newpipe/" alt="Translation Status"><img src="https://hosted.weblate.org/widgets/newpipe/-/svg-badge.svg"></a>
</p>

<p align="center">
<a href="https://web.libera.chat/#newpipe" alt="IRC channel: #newpipe"><img src="https://img.shields.io/badge/IRC%20chat-%23newpipe-brightgreen.svg"></a>
<a href="https://matrix.to/#/#newpipe:matrix.newpipe-ev.de" alt="Matrix channel: #newpipe"><img src="https://img.shields.io/badge/Matrix%20chat-%23newpipe-blue"></a>
</p>

<hr>
<p align="center"><a href="#screenshots">Screenshots</a> &bull; <a href="#supported-services">Supported Services</a> &bull; <a href="#description">Description</a> &bull; <a href="#features">Features</a> &bull; <a href="#installation-and-updates">Installation and updates</a> &bull; <a href="#contribution">Contribution</a> &bull; <a href="#donate">Donate</a> &bull; <a href="#license">License</a></p>
<p align="center"><a href="https://newpipe.net">Website</a> &bull; <a href="https://newpipe.net/blog/">Blog</a> &bull; <a href="https://newpipe.net/FAQ/">FAQ</a> &bull; <a href="https://newpipe.net/press/">Press</a></p>
<hr>

*Read this document in other languages: [Deutsch](doc/README.de.md), [English](README.md), [Español](doc/README.es.md), [Français](doc/README.fr.md), [हिन्दी](doc/README.hi.md), [Italiano](doc/README.it.md), [한국어](doc/README.ko.md), [Português Brasil](doc/README.pt_BR.md), [Polski](doc/README.pl.md), [ਪੰਜਾਬੀ ](doc/README.pa.md), [日本語](doc/README.ja.md), [Română](doc/README.ro.md), [Soomaali](doc/README.so.md), [Türkçe](doc/README.tr.md), [正體中文](doc/README.zh_TW.md), [অসমীয়া](doc/README.asm.md), [Српски](doc/README.sr.md), [العربية](README.ar.md)* 

> [!warning]
> <b>THIS APP IS IN BETA, SO YOU MAY ENCOUNTER BUGS. IF YOU DO, OPEN AN ISSUE IN OUR GITHUB REPOSITORY BY FILLING OUT THE ISSUE TEMPLATE.</b>
> 
> <b>PUTTING NEWPIPE, OR ANY FORK OF IT, INTO THE GOOGLE PLAY STORE VIOLATES THEIR TERMS AND CONDITIONS.</b>

## Screenshots

[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/00.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/00.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/01.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/02.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/03.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/04.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/04.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/05.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/05.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/06.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/06.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/07.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/07.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/08.png" width=160>](fastlane/metadata/android/en-US/images/phoneScreenshots/08.png)
<br/><br/>
[<img src="fastlane/metadata/android/en-US/images/tenInchScreenshots/09.png" width=405>](fastlane/metadata/android/en-US/images/tenInchScreenshots/09.png)
[<img src="fastlane/metadata/android/en-US/images/tenInchScreenshots/10.png" width=405>](fastlane/metadata/android/en-US/images/tenInchScreenshots/10.png)

### Supported Services

NewPipe currently supports these services:

<!-- We link to the service websites separately to avoid people accidentally opening a website they didn't want to. -->
* YouTube ([website](https://www.youtube.com/)) and YouTube Music ([website](https://music.youtube.com/)) ([wiki](https://en.wikipedia.org/wiki/YouTube))
* PeerTube ([website](https://joinpeertube.org/)) and all its instances (open the website to know what that means!) ([wiki](https://en.wikipedia.org/wiki/PeerTube))
* Bandcamp ([website](https://bandcamp.com/)) ([wiki](https://en.wikipedia.org/wiki/Bandcamp))
* SoundCloud ([website](https://soundcloud.com/)) ([wiki](https://en.wikipedia.org/wiki/SoundCloud))
* media.ccc.de ([website](https://media.ccc.de/)) ([wiki](https://en.wikipedia.org/wiki/Chaos_Computer_Club))

As you can see, NewPipe supports multiple video and audio services. Though it started off with YouTube, other people have added more services over the years, making NewPipe more and more versatile!

Partially due to circumstance, and partially due to its popularity, YouTube is the best supported out of these services. If you use or are familiar with any of these other services, please help us improve support for them! We're looking for maintainers for SoundCloud and PeerTube.

If you intend to add a new service, please get in touch with us first! Our [docs](https://teamnewpipe.github.io/documentation/) provide more information on how a new service can be added to the app and to the [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor).

## Description

NewPipe works by fetching the required data from the official API (e.g. PeerTube) of the service you're using. If the official API is restricted (e.g. YouTube) for our purposes, or is proprietary, the app parses the website or uses an internal API instead. This means that you don't need an account on any service to use NewPipe.

Also, since they are free and open source software, neither the app nor the Extractor use any proprietary libraries or frameworks, such as Google Play Services. This means you can use NewPipe on devices or custom ROMs that do not have Google apps installed.

### Features

* Watch videos at resolutions up to 4K
* Listen to audio in the background, only loading the audio stream to save data
* Popup mode (floating player, aka Picture-in-Picture)
* Watch live streams
* Show/hide subtitles/closed captions
* Search videos and audios (on YouTube, you can specify the content language as well)
* Enqueue videos (and optionally save them as local playlists)
* Show/hide general information about videos (such as description and tags)
* Show/hide next/related videos
* Show/hide comments
* Search videos, audios, channels, playlists and albums
* Browse videos and audios within a channel
* Subscribe to channels (yes, without logging into any account!)
* Get notifications about new videos from channels you're subscribed to
* Create and edit channel groups (for easier browsing and management)
* Browse video feeds generated from your channel groups
* View and search your watch history
* Search and watch playlists (these are remote playlists, which means they're fetched from the service you're browsing)
* Create and edit local playlists (these are created and saved within the app, and have nothing to do with any service)
* Download videos/audios/subtitles (closed captions)
* Open in Kodi
* Watch/Block age-restricted material

<!-- Hidden span to keep old links compatible. You should remove this span if you're translating the README into another language.-->
<span id="updates"></span>

## Installation and updates
You can install NewPipe using one of the following methods:
 1. Add our custom repo to F-Droid and install it from there. The instructions are here: https://newpipe.net/FAQ/tutorials/install-add-fdroid-repo/
 2. Download the APK from [GitHub Releases](https://github.com/TeamNewPipe/NewPipe/releases), [compare the signing key](#apk-info) and install it.
 3. Update via F-Droid. This is the slowest method of getting updates, as F-Droid must recognize changes, build the APK itself, sign it, and then push the update to users.
 4. Build a debug APK yourself. This is the fastest way to get new features on your device, but is much more complicated, so we recommend using one of the other methods.
 5. If you're interested in a specific feature or bugfix provided in a Pull Request in this repo, you can also download its APK from within the PR. Read the PR description for instructions. The great thing about PR-specific APKs is that they're installed side-by-side the official app, so you don't have to worry about losing your data or messing anything up.

We recommend method 1 for most users. APKs installed using method 1 or 2 are compatible with each other (meaning that if you installed NewPipe using either method 1 or 2, you can also update NewPipe using the other), but not with those installed using method 3. This is due to the same signing key (ours) being used for 1 and 2, but a different signing key (F-Droid's) being used for 3. Building a debug APK using method 4 excludes a key entirely. Signing keys help ensure that a user isn't tricked into installing a malicious update to an app. When using method 5, each APK is signed with a different random key supplied by GitHub Actions, so you cannot even update it. You will have to backup and restore the app data each time you wish to use a new APK.

In the meanwhile, if you want to switch sources for some reason (e.g. NewPipe's core functionality breaks and F-Droid doesn't have the latest update yet), we recommend following this procedure:
1. Back up your data via Settings > Backup and Restore > Export Database so you keep your history, subscriptions, and playlists
2. Uninstall NewPipe
3. Download the APK from the new source and install it
4. Import the data from step 1 via Settings > Backup and Restore > Import Database

> [!Note]
> When you're importing a database into the official app, always make sure that it is the one you exported _from_ the official app. If you import a database exported from an APK other than the official app, it may break things. Such an action is unsupported, and you should only do so when you're absolutely certain you know what you're doing.

### APK Info

This is the SHA fingerprint of NewPipe's signing key to verify downloaded APKs which are signed by us. The fingerprint is also available on [NewPipe's website](https://newpipe.net#download). This is relevant for method 2.
```
CB:84:06:9B:D6:81:16:BA:FA:E5:EE:4E:E5:B0:8A:56:7A:A6:D8:98:40:4E:7C:B1:2F:9E:75:6D:F5:CF:5C:AB
```

## Contribution
Whether you have ideas, translations, design changes, code cleaning, or even major code changes, help is always welcome. The app gets better and better with each contribution, no matter how big or small! If you'd like to get involved, check our [contribution notes](.github/CONTRIBUTING.md).

<a href="https://hosted.weblate.org/engage/newpipe/">
<img src="https://hosted.weblate.org/widgets/newpipe/-/287x66-grey.png" alt="Translation status" />
</a>

## Donate
If you like NewPipe, you're welcome to send a donation. We prefer Liberapay, as it is both open-source and non-profit. For further info on donating to NewPipe, please visit our [website](https://newpipe.net/donate).

<table>
  <tr>
    <td><a href="https://liberapay.com/TeamNewPipe/"><img src="https://upload.wikimedia.org/wikipedia/commons/2/27/Liberapay_logo_v2_white-on-yellow.svg" alt="Liberapay" width="80px" ></a></td>
    <td><a href="https://liberapay.com/TeamNewPipe/"><img src="assets/liberapay_qr_code.png" alt="Visit NewPipe at liberapay.com" width="100px"></a></td>
    <td><a href="https://liberapay.com/TeamNewPipe/donate"><img src="assets/liberapay_donate_button.svg" alt="Donate via Liberapay" height="35px"></a></td>
  </tr>
</table>

## Privacy Policy

The NewPipe project aims to provide a private, anonymous experience for using web-based media services. Therefore, the app does not collect any data without your consent. NewPipe's privacy policy explains in detail what data is sent and stored when you send a crash report, or leave a comment in our blog. You can find the document [here](https://newpipe.net/legal/privacy/).

## License
[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](https://www.gnu.org/licenses/gpl-3.0.en.html)  

NewPipe is Free Software: You can use, study, share, and improve it at will. Specifically you can redistribute and/or modify it under the terms of the [GNU General Public License](https://www.gnu.org/licenses/gpl.html) as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
