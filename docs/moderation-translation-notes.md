# Content moderation and in-app translation

Working notes for the fork's spam shield and translation layer. Design, what came from
upstream, verification, and what is deliberately deferred.

## One gate, two cooperating engines

Every public and channel message passes one admission point before it can reach a timeline,
notify, haptic, badge, persist, or be delegated to UI. `IncomingMessageAdmission` is that point;
`AppStateStore.addPublicMessage` / `addChannelMessage` repeat the check so relay and Nostr
(geohash) paths are covered even when they land directly in state.

Order inside the gate:

1. **Upstream `SpamFilter`** (merged, release toggle in About): per-sender token budget,
   characters per minute, links per minute, similarity against a sender's own history,
   similarity across senders, long character runs. Its verdicts (`MESSAGE_RATE`,
   `CHARACTER_RATE`, `LINK_RATE`, `REPEATED_MESSAGE`, `REPEATED_ACROSS_USERS`,
   `REPEATED_CHARACTERS`) are recorded into the fork ledger so they are counted, reviewable,
   translatable, and actionable. Upstream ships no ledger, so a silent hide is invisible.
2. **Fork content rules** (`SpamShield`): scam phrases, forward-chain markers, user-supplied
   blocked phrases, shout flood (a single shouted token pasted repeatedly, counted per minute),
   long character runs, proof-of-work, sender reputation with decay, muted senders.
   Verdict type is `ShieldVerdict` (renamed to avoid the `SpamVerdict` collision from upstream).

Duplicated rules the upstream engine does better were removed from `SpamShield`: exact-similarity
repeat, cross-sender similarity, sender burst. Labels stayed the same, now fed by upstream.
Scoring is additive with per-mode thresholds (Gentle 45/95, Balanced 30/55, Aggressive 20/40),
and rate signals alone are never decisive: a single shouted word flags, it does not hide.
Auto-mute is second-strike, content-only, never extends itself, default off.

One switch governs both engines: the upstream Spam Filter row is the master, and the fork content rules score only when it is on. The fork section has no toggle of its own, so the settings sheet shows one switch, then the content rules under it. `SpamShieldPreferences.setEnabled` mirrors the upstream flag, and `policy()` reads it.

The rate, repeat, link and character-run rules are back in `SpamShield`, because Nostr geohash messages enter through `GeohashMessageHandler` into `AppStateStore.addChannelMessage` and never pass through `IncomingMessageAdmission`, where the upstream filter runs. Deleting the duplicated rules left the location channels with no rate or repeat coverage, which is what a geochat flood needs. Mesh traffic is scored by both gates, in order, and verdicts are memoized by message id so a relayed message is charged once. The long-press Mark as spammer and Restore to default trust rows follow `SpamFilterPreferences.enabled` together with `SpamFilterPreferences`, not the shield flag that settings no longer writes.

**Upstream hides are recorded in the fork ledger from both gates.** IncomingMessageAdmission records
filter verdicts as labels, and GeohashMessageHandler records its own, because relay messages never
reach IncomingMessageAdmission. Recording is wrapped in runCatching: a bookkeeping failure must not
admit a hidden message, since the admission call sits inside a try block whose catch admits public
traffic. Verdicts are memoized by message id, so recording does not charge a sender rate budget twice.
Live check: 35 geohash hide log lines in 70 seconds, with the bar count rising from 118 to 155 in the
same window and no translation failures.

**Auto-mute Never is selectable.** The chip row highlighted the index from indexOf(autoMute) with a
floor of 1, which forced Never (index 0) to display as 10 min. The floor is now 0. Tapping Never on
the emulator writes auto_mute_minutes = 0 to spam_shield_v1.xml and the chip stays highlighted.

**Purging visible spam, and restoring it.** Muting only stops future messages, so the first posts
of a spammer stayed on screen. AppStateStore gains `removeMessagesFromSender`, which clears every
visible message of a stable sender key across channel and public timelines, and `replayMessages`,
which puts them back. `SpamShieldService` keeps in-memory copies per sender, capped at 60 messages
and 100 senders, never persisted, purges when a mute begins, and replays them on restore trust. The
second-strike rule is now `contentStrike`, shared by the shield and by verdicts recorded from the
upstream rate filter, so auto-mute and purge are not tied to which engine made a hide. Unit tests
cover a rate-filter hide reaching the strike rule, the purge, the restore, and that purge is keyed
to the sender so innocent chatter in the same channel survives. On the emulator, injected synthetic
messages in a live flooded geochat were hidden by the upstream filter before they could render
(`admitted: false` for every injection), so the bar counted them (`5 spam messages hidden`) and the
review sheet listed them with `repeat` labels and Restore trust, but a purge of visible rows cannot
be shown in that channel: nothing from the injected sender ever reaches the timeline. The debug
bypass pref `spam_filter_debug_bypass` is the route to demonstrate it, since it lets injected
messages render first.

## Surfaces the fork adds

- **Count + review sheet.** Hidden messages live in memory only, newest first: sender, time,
  signals, text. `hiddenCount` is a cumulative per-run counter, not the ledger size, so a
  flood that continues stays visible past the ledger cap.
- **Actions:** Reveal, Reveal channel, Restore trust, Mark as spammer, Mute, Block word
  (the repeated token), Block phrase. Restore trust clears mute, reputation, strikes and the
  sender's observations, then replays their hidden messages; it is not a whitelist — a scam
  text from the same sender is hidden again by the same rules.
- **Translations for hidden text**, same consent and engine as the chat, capped per scope.
- **Long-press menu**: Mark as spammer / Restore to default trust, next to Block.
- **Mention handles lifted out of translated text** (`@nick`, `@nick#id` never reach a model,
  restored verbatim).
- **Per-channel target and source language.** Source per channel (Auto / explicit). Short chat
  lines defeat word-based detection, so explicit source is the reliable setting for known
  language channels. Geohash nesting: a coarse setting applies inside, a finer channel overrides.
- **Display modes** below / replace, plus status lines (`Translating…`, `No translation: reason`).

## Translation engine

- **On-device ML Kit** (default): text processed on the phone. Google Play downloads language
  packs (~30 MB per pair) over Wi-Fi; the download reveals the language pair, never the text.
- **Self-hosted LibreTranslate**: text goes to an endpoint the user configures, https only,
  routed through Tor when Tor mode is on. Nothing is sent until the endpoint is set.
- Requests are serialised (one at a time) with a 45 s timeout, batched to the newest messages,
  capped per scope. Private text is never sent unless explicitly allowed; encrypted, media,
  system, blank and non-language shapes are never sent. Cache is keyed by message id + target
  language, LRU, never persisted.
- Language probe is local: Unicode script ranges, Slavic letter marks, Latin frequency hints.
  Unclassifiable text is sent for translation rather than assumed to be English.

## Privacy boundaries

- Scoring is on-device. Nothing about messages, senders, channels, scores, reputation, mutes,
  or block phrases is transmitted. Persisted state is settings, block phrases and the mute list
  in the app's private preferences.
- Translations are not stored. On-device mode reveals the language pair to Google Play. Online
  mode reveals message text to the user's own endpoint.
- The shield **fails open**: if scoring throws, the message is admitted.
- `app/src/debug/.../TestHookDriver.kt` has an `inject_msg` hook that pushes synthetic messages
  through the real admission path. Debug source set only, broadcasts nothing, never in release.

## Deferred to the next upstream release tag

Upstream `main` is post-release development code, and these parts are unfinished:
- spam filter tuning UI, debug bypass, tuning knobs (debug-only),
- PoW requirement as a gate,
- locale strings for the new UI (English only today),
- Wear settings UI (the shield is inert on the watch),
- baseline test failures: `ConversationDatabaseTest` (Robolectric native SQLite,
  `SQLITE_CANTOPEN` on Windows), `MediaSendingManagerMigrationTest`,
  `IncomingMessageAdmissionTest`, `ConversationRepositoryTest`,
  `NostrDirectMessageHandlerTest`. These reproduce on a clean tree; no spam/translation
  test fails in any run.
- Mesh Lab on two physical devices for BLE, Wi-Fi Aware and hotspot transports. Emulators have
  no radios, so only Nostr/relay-sourced channels, UI, settings and persistence are exercised.

## Verification

- Emulator (debug build, geohash channels, no radios): settings round-trip through
  SharedPreferences; moderation bar, review sheet, settings sections, long-press actions render
  and are tappable; French channels translate with explicit source; hidden messages translate in
  the sheet; injected floods (rotating shouted tokens, word walls, French scam sentences) are
  hidden and counted; restored senders post normally again.
- Test suites: spam + translation suites green; full `:app:testDebugUnitTest` shows only the
  baseline families listed above.
- `:app:assembleDebug`, `:wear:assembleDebug`, `:wear:testDebugUnitTest`, `:app:lintDebug` pass.
- Sources for this feature are ASCII-only: Cyrillic, accents, bullets and ellipses are written as
  `\u` escapes, because encoding passes destroyed literal characters (which silently disabled
  Cyrillic scam-phrase matching and Slavic detection). `strings.xml` typography uses entities.

## Commits

Fork `main`: `ae11b9d9` feature, `e8e441b1` word walls + shout flood + cumulative count +
capped hidden translation, `15a1abbd` string repair + ASCII-safe literals, `a3b9acee` neutral
test tokens, `1930f0a3` ignore Gradle daemon file, `fe559413` merge of 13 upstream commits,
`b01720ef` `ShieldVerdict` rename, `9e0faf7e` upstream verdicts recorded into the fork ledger.
