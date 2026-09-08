# OldЫ Chat · Android beta 0.2

Private messenger project for maxfilov4. Install the signed APK as an update over 0.1; do not uninstall to keep local account keys and history. Install the new APK on both phones. Run the new console installer on the existing server first; it preserves accounts and the existing TLS certificate, adds HTTPS 443 alongside 8443, and starts STUN discovery on UDP 3478.

## Included

- Nickname/password accounts, device-held message history and retry outbox.
- Light, dark and green themes, clean connection status, original illustrated animated mascot, 12 animated vector avatars, uploaded profile/room photos, hundreds of Unicode emoji and 12 animated Oldy Pop reactions.
- Private groups and channels (up to 50 participants), creator-managed membership and avatars. Only the creator publishes to a channel; recipient checks membership and role before displaying a message.
- Direct photo, video and voice-message transfer in personal chats using native WebRTC data channels; 25 MiB maximum per attachment and three-minute voice recording. Formats: JPEG/PNG/WebP, MP4/WebM video, AAC/M4A voice. Receiver taps to download. Both phones must be online. The server never stores or relays media bytes; only encrypted signaling and attachment descriptions pass through the short-lived relay. There is no TURN fallback. Carrier NAT / filtering may prevent a direct path; use another network when this occurs.
- Message notifications with original Oldy Pop chime, sound/vibration/preview switches, foreground delivery, and a user-controlled Android battery exemption. Force-stop, revoked permissions, manufacturer restrictions and network outages can still prevent delivery. Google push services are not configured.
- Encrypted account/text backup. Attachments are deliberately excluded and remain on the original phone.

The production connection certificate is pinned in the app. UI hides endpoint details but app binaries and network endpoints cannot be made secret from a device owner. No administrator credentials are embedded. The source remains private; temporary beta signing identity is kept in the private CI cache. A backed-up owner-controlled release signing key and repository protections are still needed before a public release.

## Storage and transport

Server SQLite stores accounts, password/session hashes, public keys, profile details and room membership. Avatar files are uploaded by users, decoded, resized/re-encoded, and stored on this server. Messages exist in server RAM only while active requests wait for recipient acknowledgement. Offline delivery requires the sender to reconnect; there is no server mailbox or channel history archive. Group history is not backfilled to newly added members. Local encrypted history includes encrypted per-recipient delivery envelopes.

The experimental v1 envelope uses RSA-OAEP/SHA-256, AES-GCM, ECDSA P-256 and first-contact public-key pinning. It is not audited and does not provide forward secrecy. SDP signaling is carried in authenticated envelopes. WebRTC media uses a DTLS data channel; media files are encrypted in authenticated 16 KiB records on each phone. Voice recording temporarily uses app-private cache while Android records, then is encrypted and the temporary file removed.

## Build / validation

GitHub Actions builds using Android SDK 35, JDK 17 and pinned WebRTC SDK `144.7559.12` from Maven Central, then runs server integration tests and Android instrumentation. The native direct-transfer test uses two real local peer connections and authenticated SDP, transfers a multi-chunk file and checks exact bytes and encrypted disk storage. No automated test contacts the production VPS. Actual Russian mobile-carrier connectivity must be tested on the user's phones after installation.

`tools/build-apk.sh` produces `build/OldyChat-beta.apk`. `tools/package-installer.py` creates a self-contained Timeweb console command. Never commit root passwords, TLS private keys, or APK signing private keys.

## Asset provenance and dependencies

`app/src/main/assets/mascot.png`: generated original grandfather on a sofa using the built-in image generation tool. The requested near-exact reference recreation was rejected by the generation service; this is a distinct character. Prompt: original friendly silver-bearded grandfather, navy cap with lime chat patch, bronze glasses, terracotta cardigan, smartphone, green sofa, stylized 3D rendering. Runtime mesh animation adds subtle thumb motion. Vector avatars and Oldy Pop emoji animations are original code; they are not Telegram assets.

WebRTC Android packaging: https://github.com/webrtc-sdk/android (MIT wrapper); WebRTC engine: https://webrtc.googlesource.com/src/ (BSD license and third-party notices). Android platform APIs are provided by the device. The original notification sound is synthesized for this app.

Useful primary references: https://developer.android.com/training/monitoring-device-state/doze-standby ; https://www.rfc-editor.org/rfc/rfc8445.html ; https://github.com/coturn/coturn/wiki/turnserver .
