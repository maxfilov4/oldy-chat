# OldЫ Chat 0.3 beta

Native Android messenger for the owner’s private `oldy-chat` repository. Install the APK over 0.2; keep the existing application data and signing identity.

## What changed

- Groups and channels render bordered publication cards with author/channel identity, photos, captions, reactions and a pinned-post bar. Personal conversations retain message bubbles. Tap once for reply, copy, save, reaction and permitted pin actions.
- Global case-insensitive `@handle` namespace spans users, groups and channels. Availability is checked while typing and enforced atomically in SQLite. Existing rooms receive a unique address; they remain private until the owner enables public search. Public communities can be found by name or handle and joined. Current beta limit remains 50 members.
- Group members publish as themselves. Channel owners publish as the channel; subscribers react and discuss in a separate per-post thread with a unique `oldy://comments/<room>/<post>` link. Owners alone manage membership, community bans and community pins. Personal block lists do not expel people from shared groups.
- Photo/video/voice attachments now work for groups and channels over authenticated direct WebRTC. Photo posts include a small encrypted thumbnail. Full attachments require a direct connection to the sender, as before; 25 MiB limit, voice up to three minutes.
- YouTube watch, Shorts, youtu.be and live links show a card and thumbnail. Titles use YouTube oEmbed with a fallback. Other HTTP(S) links show a domain card. Links are opened externally; fetching previews may be affected by access to YouTube.
- Neutral light/dark themes, three original line-art wallpapers, profile menu at bottom-right `@nick`, 12 original illustrated avatars, custom photo upload, and an animated original grandfather who walks, squats and writes. Saved messages and per-conversation encrypted drafts are local features.
- Six original notification tones, immediate preview, vibration and system-volume settings. Actual loudness also depends on Android notification volume and Do Not Disturb.
- The established owner account `@oldy` alone can upload MP4 up to 2 GiB in channels it owns. This is enforced by the server against an owner public-key hash configured from the existing database, not by an APK flag. Uploads use resumable 512 KiB chunks. A post combines video cover, text, reactions and comments. The in-app player supports authenticated HTTP ranges, seeking and portrait/landscape without downloading the whole file first. H.264/AAC MP4 is the tested format; MP4 is a container and other codecs depend on device support. Uploading requires the app to stay open; a stopped upload resumes when the same file is selected again.
- New registrations use email, nickname and password. Login accepts email or nickname; legacy nickname accounts still work. Email is private to the account. Email verification is implemented but requires SMTP configuration; an unverified address is explicitly marked. On signup/login, private device keys are backed up using PBKDF2-SHA256 (310,000 iterations) and AES-GCM under the account password. Existing logged-in users enable this once in Settings → Protection and backup. A fresh device can then restore keys and encrypted history with that password. Without this backup, use the existing encrypted local export.

## Optional updates

`GET /updates` advertises a release only when the manifest and APK exist together. Old clients continue to log in and exchange compatible messages. Protocol capability announcements prevent sending new control-event payloads to older clients.

The app checks on launch/foreground and has a manual Updates entry in Settings. A newer version offers Update or Later. It downloads from the pinned HTTPS server, checks exact size, SHA-256, Android package name, increasing versionCode, and the current application's signing certificate. Android asks for installation confirmation. The first update may require the user to allow installs from OldЫ Chat.

`python3 tools/package-release.py` packages the signed APK, matching manifest and server installer into `build/OldyChat-0.3-server.tar.gz`. The owner runs the delivered console bootstrap, which retrieves this exact archive, verifies its SHA-256 and calls the bundled installer. The installer preserves TLS keys, takes a SQLite backup, runs an additive migration and atomically publishes the new APK/manifest. Root credentials and signing keys are not included in deliverables.

To publish future builds, keep the same signing key and package, increase versionCode and versionName, update the release notes/manifest in `package-release.py`, build, then run the new owner console command. No forced upgrade or expiration of the previous version is configured.

## Server configuration

The service optionally reads `/etc/oldy-chat/mail.env` (root-owned, mode 600):

```ini
OLDY_SMTP_HOST=smtp.example.com
OLDY_SMTP_PORT=587
OLDY_SMTP_FROM=Oldy Chat <mail@example.com>
OLDY_SMTP_USER=mail@example.com
OLDY_SMTP_PASSWORD=your-smtp-app-password
```

SMTP requires STARTTLS with certificate validation. Verification codes expire after 10 minutes, have an attempt limit, are stored as hashes and are never logged. No SMTP account has been configured by this update.

## Storage and limitations

Server SQLite stores account public keys, salted password hashes, hashed individual sessions, profiles, handles, membership, bans, encrypted per-user message envelopes, password-encrypted key backups and metadata for channel videos/comments. Encrypted offline messages survive server restarts and remain until recipient acknowledgement; a stored receipt and a delivered receipt are distinct. Each authenticated account can retrieve only its own history. Signatures and sender authentication are checked before storage; clients additionally verify signatures, routing and permissions before displaying content.

Messages, captions, thumbnails, reactions and pins are archived as ciphertext. Ordinary full photo/video/voice attachments up to 25 MiB still use encrypted device storage and direct WebRTC; they are not copied into the server history archive and need the sender online for a first full download. Channel-owner MP4 files use a different path: ordinary MP4 storage on the server, protected by TLS and current channel membership. These large videos are **not end-to-end encrypted**. New community members receive future posts; historic per-recipient ciphertext is not automatically re-keyed for new subscribers. Backups omit ordinary full media. Direct media can fail on restrictive networks because TURN is not configured. The beta encryption protocol is experimental, unaudited and lacks forward secrecy.

The server address cannot be hidden from a device owner or network observer. TLS/certificate pinning protect the connection; per-account tokens and server authorization protect access. There is no shared admin credential in the APK. Never treat address secrecy as a security boundary.

No production VPS change is made by building this repository. The owner runs the supplied console command. Carrier behavior and SMTP delivery require checks in the owner's environment.

## Validation and assets

Build uses Android SDK 35, JDK 17 and WebRTC SDK 144.7559.12. Server integration tests cover namespace races, permissions, private search, joins/bans, blocks, emails, migration preservation, optional update serving, legacy clients, durable encrypted history, signature rejection, key-backup isolation, 2 GiB bounds, owner-only MP4 upload, resume/range authorization and comment permissions. Android instrumentation covers encrypted direct and channel media, reactions, pins, blocked direct messages, routing, saved items/drafts, and UI screenshots. A real H.264/AAC fixture checks the full-screen player, seeking and rotation on Android 35. A near-2 GiB real-world upload and physical Xiaomi background behavior still require validation on the owner’s network/device.

`avatars-v3.webp` and `mascot-v3.webp` were generated with the built-in imagegen tool. They are original characters, not licensed game characters or celebrity likenesses. Generation of a set referencing known characters/actors was rejected; the final set uses original gaming/fantasy/sci-fi archetypes. The avatar prompt requested twelve premium 3D cartoon badges in a 4×3 atlas; the mascot prompt requested an original elderly gamer in a 4×2 atlas with a four-frame walk cycle, idle, squat and two writing poses. Runtime samples the atlas directly; no extracted Telegram artwork is used. Wallpaper artwork is original Android Canvas code. Existing WebRTC license and notice files remain bundled.
