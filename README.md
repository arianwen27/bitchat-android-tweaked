<img width="256" height="256" alt="icon_128x128@2x" src="https://github.com/user-attachments/assets/90133f83-b4f6-41c6-aab9-25d0859d2a47" />

## bitchat for Android

I got bored, this is 100% vibecoded with a local LLM, I just wanted to see if it could actually do it, it seems it can.

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
- **Content Spam Shield**: on-device content scoring behind the upstream Spam Filter switch for public and location channels. Repeats, floods, link spam, forwarded chains, scam phrasing and blocked phrases are scored locally; hidden messages never reach the timeline, notifications, history, or storage, and every hide is reviewable and revealable.
- **In-App Auto Translation**: translated text under the original in any channel, on-device via ML Kit by default, with a self-hosted LibreTranslate endpoint as an option. Per-channel target and source language, and translation of hidden spam for review.

## Fork additions: content moderation and in-app translation

Two features on top of the upstream client. Neither changes the packet format, transport,
identity, PoW requirement, or any protocol behaviour. There is one spam switch: the upstream Spam Filter row, which the fork rules ride on. Translation is off until you enable it. Both are
local-first.

### Content Spam Shield

Every public and channel message passes one admission point before it can reach a timeline, notify,
badge, persist, or reach the UI. The upstream Spam Filter switch is the master: when it is off, nothing is scored. When it is on, two engines run in order at that point:

1. **Upstream rate and repetition filter** - per-sender token budget, characters per minute, links
   per minute, similarity against a sender's own history, similarity across senders, and long
   character runs.
2. **Fork content rules** - scam phrases, forward-chain markers, your own blocked phrases, shout
   floods (a single shouted word pasted repeatedly, counted per minute), long character runs,
   proof-of-work, and sender reputation that decays hourly.

The fork adds what upstream does not provide:

- **A visible count and a review sheet.** Hidden messages are kept in memory only, newest first,
  with sender, time, the signals that hid them, and the text. The count is per app run, so a
  continuing flood stays visible.
- **Actions**: Reveal, Reveal channel, Restore trust, Mark as spammer, Mute, Block word,
  Block phrase. Restore trust returns a sender to default reputation, clears their mute and
  strikes, and replays their hidden messages. It is not a whitelist: the same rules apply to
  their next message.
- **Translations for hidden text**, so a hidden message in a foreign language can be read.
- **Second-strike auto-mute**, off by default, applied only to content signals. Rate and
  reputation alone never mute anyone, and auto-mutes never extend themselves.
- **Long-press actions** Mark as spammer and Restore to default trust, next to Block.
- **Mention handles** (`@nick`, `@nick#id`) are lifted out of translated text and restored
  verbatim, so handles never reach a translation model.

The shield **fails open**: if scoring throws, the message is admitted. Losing real messages is
worse than showing spam. Scoring happens on the device; nothing about a message, sender, channel,
score, reputation, mute, or block phrase is transmitted.

### In-App Auto Translation

- **On-device** (ML Kit, default): text is processed on the phone. Google Play downloads language
  packs (~30 MB per pair) over Wi-Fi. The download reveals the language pair, never the text.
- **Self-hosted**: text is sent to an endpoint you configure, over https, routed through Tor when
  Tor mode is on. Nothing is sent until you set an endpoint.
- **Per-channel target and source language.** Source can be Auto or explicit. Short chat lines
  defeat word-based detection, so set an explicit source for known-language channels. A coarse
  geohash setting applies inside it; a finer channel overrides.
- **Display** below the original, or replacing it. Translations are cached in memory keyed by
  message id and target language, never persisted.
- **Private chat is not translated** unless you explicitly allow it. Encrypted, media, system,
  blank, and non-language shapes (word walls, shout floods, link dumps) are never sent. Requests
  are serialised with a timeout and capped per batch and per scope.
- **Rows report status** (`Translating...`, `No translation: reason`) so a stalled model download
  is distinguishable from a skipped message.

### Limits

- Emulators cannot exercise BLE, Wi-Fi Aware, or hotspot transports. Mesh transport coverage
  requires two physical devices, per the Mesh Lab runbook.
- PoW is a cost, not a content rule: requiring proof raises a spammer's cost, it does not block
  content. It is used as one signal among several.
- UI strings are English only. The shield is inert on the watch until Wear exposes settings.
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
