# Configurable offline shelf capacity

> Implement and verify with test-driven development.

**Goal:** Let existing BackTube users change the temporary shelf limit from the current 500 MB default.
**Architecture:** Persist integer decimal MB in the app's private preferences; apply it to the existing process-owned OfflineStore under the store lock. Keep all saves and limits otherwise independent of normal Downloads.

- [x] Cover raising, lowering without deletion, and limits applied to import and download growth.
- [x] Add a positive whole-MB input to Temporary saves → Manage and reflect the saved limit in the capacity summary.
- [ ] Verify capacity errors, preference persistence and real-device upgrade before distributing any signed APK.
