# Database boundary review batch 14, 2026-10-25

## Scope and source

- Based on batch 8 commit `fe9be2d56efda24c5c8dac940d9b406d0ac8bf07` and its earlier
  batch 3–7 changes. This batch changes only two DAO SQL files and an Android test.
- Read Library patch `libfile_a5704467789c8191a73a77c441af95c8` and review
  `libfile_fbabca9eb704819199a20419b1bf0100`. Materialized Library ZIP
  `libfile_e9bde68ee21c819189fb5f300d3ba8be`; its SHA-256 matched
  `05d47f23fc845436d3bfaa8c54ea1738abef23215547a65029c71e7f7dec288f`
  and ZIP CRC verification passed. Web substitute-boundary tests were review input,
  not Android test results.
- `SubscriptionEntity` has a unique `(service_id, url)` index. Feed relations have a
  `(stream_id, subscription_id)` primary key, and `StreamHistoryEntity` allows multiple
  access dates per stream. The four proposals match these existing contracts.

## RED to GREEN on an in-memory API 23 Room database

| Boundary | Before | Minimal correction |
| --- | --- | --- |
| Subscription identity | Exact lookup, exact delete, and upsert conflict tests failed with `_`/`%` or case variants. A selected delete returned 3 rows rather than 1. | Change only four service+URL identity queries from `LIKE` to `=`. Keep name search `LIKE` unchanged. |
| Feed list fanout | 501 history rows for one stream consumed `LIMIT 500`, hiding the next stream. | Select distinct projected stream/state rows before applying the limit. |
| Shared feed cleanup | Pruning B's old link also removed A's last link to a shared stream. | Correlate the candidate feed row with the outer subscription. |
| Missing update marker | A new subscription without a `feed_last_updated` row was absent from the count. | Count subscriptions through a left join, matching the existing group/outdated queries. |

All **six new pre-fix tests failed** on the original DAO code. After the changes,
**ten API 23 Android Room tests passed**: eight new tests (including a shared stream
displayed once, an empty count, and 501 distinct streams retaining the 500-row limit
and date order) and two existing `FeedDAOTest` cleanup tests.
The tests use a newly created in-memory database and synthetic URLs, streams and dates;
they never open or modify installed user data. Same URL under a different service,
case variants, literal `_`/`%`, 500-row limit, shared links and normal cleanup are covered.

Final-source `:app:testDebugUnitTest` ran **620 tests with 0 failures/errors/skips**.
`:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:runCheckstyle` passed.
An independent code review found no blocking issue in the four SQL changes; the
reviewer checked existing index/foreign-key contracts and did not rerun tests.

## Limits

- No schema, database version, migration, URL normalization, or permission change.
  The separate conditional migration foreign-key observation was not patched here.
- The feed `DISTINCT` query may use extra SQLite temporary work; no performance
  measurement was made and this is an accuracy fix.
- API 35 remains untested because the cloud AVD previously had system ANRs before app
  tests began. H.264 video playback remains untested because API 23 software decode
  previously raised native `SIGILL`. Actual provider feeds and a physical device were
  not used.
- Batch 9–13 and 15 candidates, backup restore R1, a new APK, main merge, and public
  release were outside this batch.
