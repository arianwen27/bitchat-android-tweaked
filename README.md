<img width="256" height="256" alt="icon_128x128@2x" src="https://github.com/user-attachments/assets/90133f83-b4f6-41c6-aab9-25d0859d2a47" />

## bitchat for Android

A decentralized peer-to-peer messaging app with dual transport architecture: local Bluetooth mesh networks for offline communication and internet-based Nostr protocol for global reach. No accounts, no phone numbers, no central servers.

This is the Android implementation of bitchat, fully protocol-compatible with the [iOS version](https://github.com/permissionlesstech/bitchat) for cross-platform mesh communication.

[bitchat.free](http://bitchat.free)

[GitHub Releases](https://github.com/permissionlesstech/bitchat-android/releases)

[<img alt="Get it on Google Play" height="60" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png"/>](https://play.google.com/store/apps/details?id=com.bitchat.droid)

## See it in action

<table>
  <tr>
    <th>Offline mesh conversation</th>
    <th>Geohash globe picker</th>
  </tr>
  <tr>
    <td><img src="docs/screenshots/readme-mesh-chat.png" alt="Active four-peer Bitchat mesh conversation with an image, voice messages, and text messages" width="360"/></td>
    <td><img src="docs/screenshots/readme-geohash-globe.png" alt="Bitchat geohash location picker showing the whole Earth and geohash grid" width="360"/></td>
  </tr>
</table>

## License

This project is released into the public domain. See the [LICENSE](LICENSE.md) file for details.

## Features

- **Dual Transport Architecture**: Bluetooth LE mesh for offline messaging, Nostr relays for internet-based messaging
- **Location-Based Channels**: Geographic chat rooms using geohash coordinates over Nostr relays
- **Intelligent Message Routing**: Automatically chooses the best transport, with queuing and retry when a peer is unreachable
- **End-to-End Encryption**: [Noise Protocol](https://noiseprotocol.org) (XX pattern, X25519 + ChaCha20-Poly1305) for private messages over the mesh
- **Decentralized Mesh Network**: Automatic peer discovery and multi-hop relay over Bluetooth LE (max 7 hops)
- **Wi-Fi Aware Transport**: Higher-bandwidth local mesh on supported devices
- **Channel Chats**: Topic-based group messaging with optional password protection (Argon2id + AES-256-GCM)
- **IRC-Style Commands**: Familiar `/join`, `/msg`, `/who` style interface
- **Tor Support**: Built-in Tor (Arti) for private internet connectivity
- **Emergency Wipe**: Triple-tap to instantly clear all data
- **Cross-Platform**: Binary protocol compatible with bitchat on iOS and macOS
- **Content Spam Shield**: opt-in, on-device content scoring for public and location channels. Repeats, floods, link spam, forwarded chains, scam phrasing and blocked phrases are scored locally; hidden messages never reach the timeline, notifications, history, or storage, and every hide is reviewable and revealable.
- **In-App Auto Translation**: translated text under the original in any channel, on-device via ML Kit by default, with a self-hosted LibreTranslate endpoint as an option. Per-channel target and source language, and translation of hidden spam for review.

## Fork additions

This fork adds two client-side features for global and location channels, where spam is the main problem and proof-of-work is widely ignored. Both are opt-in, off by default, and change no protocol, packet format, or PoW requirement, so mesh and Nostr interoperability is unchanged.

**Content spam shield** (`app/src/main/java/com/bitchat/android/spam/`)
- Scores message **content** on the device: exact repeats, copies relayed by several senders, sender bursts, channel floods, character runs, link floods, scam phrasing, forwarded chains, weak or missing proof-of-work, blocked phrases, and a sender reputation that decays hourly.
- Four sensitivities: Off, Gentle, Balanced (default), Aggressive. Flag-visible mode shows a warning label below the hide threshold; only past the hide threshold is a message suppressed.
- Hidden messages are not stored: they never reach the timeline, unread counts, haptics, notifications, persistence, or delivery bookkeeping. The review sheet lists them for the current app run only, with the reason, and actions Reveal, Reveal channel, Restore trust, Mute 30 min, Block phrase.
- Auto-mute is second-strike and applies only to content signals, never to rate or reputation, and never extends an existing mute. Off by default.
- **Restore trust** returns a sender to default points (mute, reputation, repeat history cleared) and replays their hidden messages. It is not a whitelist: the same rules judge their next message.
- **Mark as spammer** in the message long-press menu mutes a sender for an hour with an explicit, deliberate judgement.
- Private chats, encrypted payloads, media, voice, and system messages are never read, scored, muted, or sent to a model.
- The engine fails open: if scoring throws, the message is admitted, because losing real messages is worse than showing spam.
- On-device only. No message content, sender, fingerprint, score, reputation, blocklist, or mute is transmitted, and no content is written to disk.

**In-app auto translation** (`app/src/main/java/com/bitchat/android/translate/`)
- Translated text below (or replacing) the original in channel, global, location, private and mesh chats. Per-conversation target **and** source language: in a French location channel, set the source to French so short chat lines are not mis-detected as English.
- On-device engine uses ML Kit Translation. Google Play fetches language packs over Wi-Fi (about 30 MB per pair); a model download reveals which language pair is fetched, never the message text.
- Online engine sends message text to a **self-hosted LibreTranslate** endpoint you configure. It is refused unless the URL is `https` and the host is not a clear-text local name, and requests inherit the app Tor routing when Tor is on.
- Translation results are cached in memory keyed by message id, capped at 400 records, and are never written to disk.
- Mentions such as `@nick` and `@nick#id` are lifted out of the text, the sentence is translated, and the handles are re-attached verbatim, so peer ids are never fed to a model.
- Private text is translated only when you explicitly enable it.
- No word list, phrase, sender, geohash, or translation is uploaded for detection. Language detection uses Unicode script ranges plus a small set of high-frequency chat words, and a language label is not location.

Full notes on behaviour, privacy boundaries, verification and open work: [docs/moderation-translation-notes.md](docs/moderation-translation-notes.md).


### Bluetooth Mesh Network (Offline)

- Direct peer-to-peer within Bluetooth range, multi-hop relay through nearby devices
- Noise Protocol sessions with forward secrecy; peer identities derived from static keys
- Compact binary packet format with fragmentation, TTL routing, and deduplication
- Adaptive duty cycling and connection limits for battery efficiency
- Foreground service keeps the mesh alive within Android background execution limits

### Nostr Protocol (Internet)

- Global reach via public relays, geohash-based location channels
- Private messages fall back to Nostr for mutual favorites when the mesh is unavailable
- Ephemeral keys per geohash area

### Android Stack

- Kotlin, Jetpack Compose (Material 3), MVVM
- Coroutines and Flow for all networking and state
- Core components: `MeshForegroundService` (persistent connectivity), `BluetoothMeshService` / `WifiAwareMeshService` (transports), `UnifiedMeshService` (transport selection), `NoiseSessionManager` (encryption sessions), `MessageRouter` (mesh/Nostr routing with outbox retry)

## Building

Requires Android Studio and the Android SDK (API 26+).

```bash
git clone https://github.com/permissionlesstech/bitchat-android.git
cd bitchat-android
./gradlew assembleDebug
```

Install on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app requests Bluetooth, location (required for BLE scanning), and notification permissions at runtime.

Release APKs and the Android App Bundle can be rebuilt byte-for-byte in the
pinned Linux container. Maintainers should follow the
[Android release guide](docs/maintainer-release-guide.md). See
[Reproducible builds](docs/reproducible-builds.md) for the build trust model
and public GitHub/Google Play verification procedures.

## Testing

```bash
# Unit tests
./gradlew test

# Lint
./gradlew lint

# Instrumented tests (requires a device or emulator)
./gradlew connectedAndroidTest
```

Note that BLE mesh behavior is difficult to emulate; protocol and session logic is covered by unit tests, while radio-level behavior needs real devices.
