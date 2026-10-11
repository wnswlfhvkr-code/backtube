# Existing data and signing verification — 2026-10-11

## Verified existing CI delivery

[CI run 38125933187](https://github.com/wnswlfhvkr-code/backtube/actions/runs/38125933187)
succeeded. [APK ZIP](https://github.com/wnswlfhvkr-code/backtube/actions/runs/38125933187/artifacts/11697696580)
expires 2026-10-14 08:52:26 UTC. Product source is
`98511aba53f82e3671b0e6f26a20a0fcefb264e8`; version
`0.29.1-offline-98511ab`, code 1016, package
`org.schabi.newpipe.debug.featclaudeautoreport`. Signature and downloaded APK/ZIP
hashes were independently verified. JVM 338/338, Checkstyle zero errors.

**This same-package CI APK is not a compatible update for the previously
delivered CI APK. Keep the existing installed app and its temporary saves.**

## Existing signing routes

Public signer certificate SHA-256 (these are public certificates, not private keys):

| Route | Certificate SHA-256 |
| --- | --- |
| Previous CI run 38087900234 | `0abc055c0ae166fe058f60568935fb0ae77b3ea043c4db88344eb8de690c6d63` |
| Current CI run 38125933187 | `979dbc6cf4a1769ad1f6c591e218c72de5df9a7ed329aa90f3ff55bf8f99525d` |
| Existing approved local signing route | `4fc26294d54b6c9adb276b0b3aab38592077fe28c9f3c64d93da5d9ff6bb5bbc` |

The approved local debug signing file exists. Existing `continuous` configuration
uses the debug signing configuration. A fresh packaging-only build used that
existing route and its resulting certificate matches the existing local certificate.
It also differs from the previous CI certificate, so it cannot make an update for
that previous CI installation compatible.

The [previous pinned workflow](https://github.com/wnswlfhvkr-code/backtube/blob/a442e4421325e4dc30f73f66f2c569d5f20ed1b2/.github/workflows/pr7-full-app-artifact.yml)
used a standard `ubuntu-24.04` hosted runner and default debug signing. It has no
keystore setup, restore, cache or private-key persistence/upload step. It staged
only APK, Korean guide and public metadata. The project has no configured release
signing route. GitHub documents standard hosted runners as new VMs;
[runner reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners).
Android documents that retaining the signing key is necessary to update an app
outside Play App Signing; [app signing](https://developer.android.com/studio/publish/app-signing).

**Inference limited to the approved workflow/environment:** the original CI
private signing key is unavailable through these routes, so recovering a
compatible update here is blocked. This does not claim to inventory unknown
external backups. An APK/public certificate is not the private signing key.
The actual phone's installed certificate remains unverified.

Presence checking `/root/.android/debug.keystore` was permission-denied. No
content access, retry with expanded permissions, or bypass was attempted. The agent did not inspect key contents
or credential values, or print, copy, issue or export them. Gradle used the existing
signing configuration directly. No signing
configuration or original installation/data was changed.

## Minimal parallel-test candidate

Existing app remains in place. A separate local worktree at the unchanged product
source used the existing `continuous` variant and existing local signer. Packaging
completed exit 0 in 251.329 seconds. No app source or build configuration edit was
needed; branch-derived test identity is the existing build behavior.

- Package: `org.schabi.newpipe.continuous.testofflinelibrary20261011` (distinct from the delivered existing-app package).
- Label: `backtube test/offline-library-20261011`.
- Version: `0.29.1-offline-test-98511ab`, code 1016.
- APK SHA-256: `c796c2d3f3212837f160024ed7b34044165c81a0eadaf1e223c91caa865f2d2e`.
- Signature verified; public certificate matches the existing local signer above.
- Provider authority uses the distinct application ID; no shared UID. Permission
  declarations are unchanged after normalizing the application-specific receiver
  permission. No runtime permission or default-handler change was performed.
- The saved store uses `context.getFilesDir()/offline`; database/preferences belong
  to the new app's own context. No automatic data transfer or cross-app access was
  added. Existing temporary saves remain only in their original app.

Different application IDs identify separate apps
([Android application ID](https://developer.android.com/build/configure-app-module));
Android isolates app resources by app UID
([Application Sandbox](https://source.android.com/docs/security/app-sandbox)).
The candidate is a build/manifest/signature feasibility result. It has not been
installed, and actual coexistence/UI/ExoPlayer execution is **0**. The unchanged
product's 338 tests were not blindly rerun for packaging only.

The candidate is retained locally with public metadata; it is not an original-app
update, a CI publication, automatic migration, or deployment. No existing app was
uninstalled or reset. No access permission was expanded. Main is untouched;
Claude/authentication/connection retries remain stopped.
