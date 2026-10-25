# Moderation + auto translation: build state

Working notes for the content spam shield and the in-app translation layer.
Nothing here is committed.

## What is implemented

### Content spam shield (`app/src/main/java/com/bitchat/android/spam/`)
- `SpamShield.kt` — pure, Android-free scorer. Signals: muted sender, blocked
  phrase, exact repeat, cross-sender repeat, sender burst, channel flood, char
  run, link flood, scam phrase, forwarded chain, weak/absent proof-of-work, low
  reputation. Text is normalised first (zero-width and combining marks stripped,
  whitespace collapsed, newlines kept so `Fwd:` markers survive).
- `SpamShieldPreferences.kt` — persisted settings: enabled, sensitivity,
  auto-mute minutes, flag-visible, require proof-of-work, blocked phrases,
  persisted mutes. All writes use `apply()`; settings survive restart.
- `SpamShieldService.kt` — process-wide gate. Verdicts are memoised by message
  id, hidden messages live in a memory-only ledger, plus reveal / reveal-scope /
  mute / reset.
- Gate placement: `AppStateStore.addPublicMessage` and `addChannelMessage`
  admit through `SpamShieldService.admit`, so mesh, BLE, Wi-Fi Aware, hotspot
  and Nostr/`geo:<hash>` channels are all covered. `IncomingMessageAdmission`
  is the authoritative gate for side effects: a hidden message produces no UI
  delegate, notification, haptics, unread count, or persistence.
- The engine fails open: if the shield throws, the message is admitted. Losing
  real traffic is worse than showing spam.

### Tuning after on-device use
Thresholds: Gentle 45/95, Balanced 30/55, Aggressive 20/40.
- Rate signals are **never decisive**: sender burst needs 6+ lines in 30 s for
  12 points, 10+ for 25; channel flood needs 15+ in 60 s for 15, 25+ for 25.
  A lively channel keeps every message.
- Repeat: first repeat 45, second 55. Two links in one message 30, three 35.
  Scam phrases 30 each (cap 60), forwarded chain 30, char run 36, weak
  proof-of-work in a location channel 22, cross-sender repeat 30+.
- Reputation +2 per spam hit, decays hourly, so one false positive never becomes
  permanent.
- **Auto-mute is second-strike only** (`applyAutoMute`): the first content hide
  counts, a mute lands on the second content-signal hide, and rate/reputation
  hides never mute. An auto-mute never extends an existing mute. Default is
  `Never`; the chip row offers Never/10/30/60 min/3 h.
  Rationale: a first-strike 30-minute mute silenced a dozen chatty participants
  in a live geohash channel, and every renewed mute made that silence permanent.

### Auto translation (`app/src/main/java/com/bitchat/android/translate/`)
- `TranslationEngine.kt` — ML Kit on-device engine (`com.google.mlkit:translate:17.0.3`)
  and a self-hosted LibreTranslate engine; requests route through
  `OkHttpProvider`, so Tor is inherited. Online mode requires https.
- `LanguageProbe.kt` — local script/word detector, no network, no text leaves the
  phone.
- `TranslationPreferences.kt` — enabled, engine mode, target language, display
  mode, private-chat permission, endpoint, **per-conversation target and per-conversation
  source language**, all `.apply()`-persisted.
- `TranslationStore.kt` — in-memory cache keyed by message id (LRU 400).
  - **Handles are kept out of the engine.** Mentions (`@nick`, `@nick#id`) are
    lifted from the text, the sentence is translated, and the handles are
    re-attached in front. ML Kit was mangling handles (`@ntm` returned as a
    vulgar word, a peer id fragment as a translated noun), which corrupted
    nicknames on screen.
  - Requests run **one at a time** (`limitedParallelism(1)`) with a 45 s timeout,
    because Google Play model downloads stall when several language pairs are
    requested at once. A stalled pair fails and releases the queue; failures are
    logged with the language pair, since a failed translation is invisible in the
    UI.
  - Encrypted payloads, non-text rows, blank content, and private chats (unless
    explicitly allowed) never reach an engine. Same-language pairs are skipped.
  - Nothing translated is written to disk.

### UI
- `ModerationBar.kt` — status row under the header: hidden-spam count as a
  review action, translation target as info.
- `SpamReviewSheet.kt` — hidden messages with sender, time, signal labels,
  preview, Reveal / Reveal channel / Mute 30 min / Block phrase.
- `ModerationSettings.kt` — spam section (toggle, sensitivity, auto-mute,
  flag-visible, proof-of-work, blocked phrases, review) and translation section
  (engine, display mode, target, per-channel target, per-channel source language,
  private permission, endpoint). Privacy notes for model downloads and for the
  online endpoint are in the section, not in strings alone.
- `ChatScreen.kt` / `MessageComponents.kt` / `ChatState.kt` /
  `ChatViewModel.kt` / `AboutSheet.kt` / `BitchatApplication.kt` — plumbing,
  display modes (original / under / replace), flag marker, sheet visibility, and
  panic-clear resetting both stores.
- `wear/build.gradle.kts` — `com/bitchat/android/spam/**` added to
  `sharedSourceIncludes`, because `AppStateStore.kt` is shared and imports the
  gate. Wear has no settings UI, so the gate stays inert there (defaults Off).

## On-device validation (debug build, emulator, geohash channel)
- Per-channel source language set to the channel's language: coverage went from
  ~20% of messages to essentially all, including short chat lines. Detection
  defaults to `en` for Latin text with no hint words, which is why short French
  lines were being fed to the model as English.
- Handles render verbatim next to the translation.
- No muted-sender cascade, no flags on ordinary chatter.
- Settings round-trip through SharedPreferences, verified with `run-as`.
- The moderation bar, the review sheet, sensitivity chips, auto-mute chips,
  flag-visible, proof-of-work, blocked-phrase editor, engine/display/target
  chips, the per-channel source row, the private toggle, and the endpoint note
  all render and are tappable.
- No `TranslationStore` failures in a 20 s logcat window while a channel was
  active.

## Tests
- `SpamShieldTest`, `SpamShieldPreferencesTest`, `SpamShieldServiceTest`,
  `LanguageProbeTest`, `TranslationPreferencesTest`, `TranslationStoreTest`.
  71 tests, all passing. Tests use synthetic content only; completion is driven
  by observed state, not sleeps.
- Full `:app:testDebugUnitTest`: 764 tests, 24 failed, 3 skipped. The 24 are the
  known baseline families — `ConversationDatabaseTest` (9, Robolectric native
  SQLite `SQLITE_CANTOPEN` on Windows), `MediaSendingManagerMigrationTest` (6),
  `IncomingMessageAdmissionTest` (6), `ConversationRepositoryTest` (2),
  `NostrDirectMessageHandlerTest` (1). They reproduce on a clean tree
  (`git stash push -u`) and are private-persistence/DB tests; the private
  admission path is untouched by this work. **No spam or translation test fails.**
- `:app:assembleDebug`, `:wear:assembleDebug`, `:wear:testDebugUnitTest`,
  `:app:lintDebug` all pass. Adding the ML Kit dependency required
  `--write-locks --write-verification-metadata sha256` (strict dependency
  locking), which updated `app/gradle.lockfile` and
  `gradle/verification-metadata.xml`.

## Reading hidden spam, and sender-level judgement
- **The review sheet translates.** Hidden messages never reach the chat list, so nothing else asks for
  their translations; the sheet requests them for its own scope, per channel, behind the same consent
  as the chat (private text never sent, nothing persisted). On device a hidden French line read
  "There is no admin here to help you …" under the label "In your language".
- **Restore trust** (review sheet, per sender): mute, reputation, second-strike counts and repeat
  observations go to zero, and the sender's hidden messages are replayed into the timeline. Content
  rules, thresholds, sensitivity and block phrases are untouched, so their next spam is judged by the
  same rules. Verified on device: restoring a test sender dropped their ledger rows and their next
  ordinary line was admitted; a unit test asserts the same scam text is still hidden afterwards.
- **Mark as spammer** (message long-press menu, next to Block): explicit user judgement. Mutes the
  sender for an hour, extends an existing mute (manual mutes are deliberate, auto-mutes never renew),
  and adds reputation. Shown only when the shield is on.
- Five review actions do not fit one row on a phone, so the action row is a `FlowRow` and wraps.
- Debug-only hook `inject_msg` in `app/src/debug/.../TestHookDriver.kt` puts a synthetic message
  through the real admission path (`IncomingMessageAdmission`), so content moderation is observable on
  an emulator with no radios. It broadcasts nothing and touches no peer. Never shipped in release.

## Flood shapes the content rules could not see

A live geohash channel was flooded with one word pasted many times per message, several accounts,
with proof of work on most messages (3b-6b). The content rules admitted it: exact-repeat and
cross-sender repeat compare whole messages, the flood was posted as many messages with the same word
rotating, `CharRun` only catches repeated single characters, and proof-of-work scoring is off unless
the reader opts in. Two shape signals close that gap, both deliberately shape-based so rotating the
word does not escape:

- `RepetitiveContent` - the same token repeated inside one message (`>=4` times), or the same line
  repeated (`>=3` lines). A wall pasted as one message.
- `ShoutFlood` - one shouted token per message (single all-caps word, letters, no spaces). The
  signal counts how many messages in the last minute were one shouted token, so a rotating-word
  flood is caught by its shape. A lone shout is scored `+10`, never decisive: `ok` and ordinary chat
  stay clear. Both are in the content set for the second-strike auto-mute.

Verified on device with injected synthetic flood: eight rotating shouts, first seven admitted with
markers, the eighth hidden (`sender burst` + `shouty flood` + `poor sender history`); a 24-token word
wall hidden with `repeated text`. Thresholds stay `30/55` Balanced, `20/40` Aggressive.

`Block word` in the review sheet: a message with a repeated token offers that token as a one-tap
block, because blocking the whole message text is not what a reader wants for a wall of one word.

## Hidden counts keep counting

The review list is capped (150 entries), so a count taken from that list stops growing during a
flood and reads as if the shield went quiet. `SpamShieldService.hiddenCount` is a cumulative counter
per app instance, shown in the moderation bar, the settings row, and the sheet header.

## Translation is capped so spam cannot flood the queue

Translating hidden spam spends model downloads and a serial engine queue on text a reader will never
read. The review sheet now skips messages that are not language (link dumps, character runs,
repeated walls, shout floods), caps requests at 25 per scope, and shows why a translation did not
happen (`Translating…`, `No translation: <reason>`) instead of silently rendering nothing.
Per-channel source language follows geohash nesting: a language set for a coarse area applies inside
it, and a finer channel can override it. Short lines the probe cannot classify are sent for
translation instead of being assumed English, which is what made the first coverage attempt work on
only a fraction of a French channel.

## Still open
- Mesh Lab validation on physical devices for the gate's transport coverage
  (BLE, Wi-Fi Aware, hotspot) — the emulator has no radios.
- Locale translations for the new strings (English only today).
- Wear settings UI, if the watch should use the shield.
- Baseline Robolectric/SQLite failures: worth a separate investigation.
- Nothing committed. Delete any scratch scripts outside the repo before a commit.
