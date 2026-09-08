# OldЫ Chat · Android beta

Native Android 8+ messenger, Russian interface, nickname/password registration, text and emoji, encrypted local history and password-protected backups. Android foreground service receives messages while the OS allows it to run. No SMS provider or cloud push dependency.

## Status

This is a functional beta prototype, not a production security-audited messenger. See Actions for actual build/test results. No server deployment is claimed by this repository.

## Data and delivery

The server stores accounts, public keys, salted scrypt password hashes and hashed session tokens. It never writes message envelopes to its database. An envelope exists in RAM for up to 18 seconds during the sender's active request and is deleted after acknowledgement or timeout. If the recipient is offline, the encrypted outbox stays on the sender's phone. Both phones must reconnect for delivery. One active phone per account in this beta; there is no multi-device synchronization.

Android history is AES-256-GCM encrypted with an Android Keystore key. Identity private keys are inside that encrypted vault. Messages use fresh AES-256-GCM keys, RSA-3072 OAEP-SHA256 wrapping and ECDSA P-256 signatures. This beta protocol has **no forward secrecy** and has not had independent cryptographic review. Before wider release, replace it with an audited ratcheting protocol and perform a security review. First-contact keys are TOFU: compare displayed fingerprints through a trusted channel. Changed keys are blocked.

HTTPS checks the exact certificate SHA-256 fingerprint entered from the server console and its validity/hostname. Plain HTTP and arbitrary untrusted certificates are not accepted. The operator still sees account names, public keys, IP connections and delivery metadata. No claim of anonymity or guaranteed availability in any country is made.

## First setup

1. Download the signed `OldyChat-beta.apk` and `Install-OldyChat.txt` from the successful Actions artifact.
2. Review the installer in `server/install.sh`. Paste the full contents of `Install-OldyChat.txt` into your Ubuntu 24.04 root console. It embeds the server source, needs no GitHub token, installs Python/cryptography, creates an unprivileged systemd service and self-signed TLS certificate for `5.42.102.11`, opens TCP 8443 if UFW is already enabled, and prints SHA-256.
3. In app settings enter `https://5.42.102.11:8443` and the printed certificate fingerprint. Distribute the same fingerprint to invited testers through a trusted channel.
4. Register using a nickname and a password. Make an encrypted backup in settings and keep its separate password safe. Restoring an existing account on a new phone requires the old private keys from that backup.
5. Allow notifications for the foreground service. Find your friend's nickname and send messages. A clock means pending; two checks mean received and saved by the other phone, not read.

The current mascot is an original animated code illustration of a grandfather typing on a smartphone; it is not the polished raster reference render. Advertising is disabled by default. To prepare a future campaign, copy `server/campaign.example.json` to `/var/lib/oldy-chat/campaign.json` on the server and configure its contents. An enabled campaign displays a dismissible, explicitly labelled ad once per revision when opening the chat list. There is no advertising SDK or payment/advertiser integration. Attachments, groups, calls, read receipts, device synchronization and automatic updates are not implemented.

## Build and checks

No Gradle/Maven dependencies. Requires JDK 17, Android SDK platform 35/build-tools 35.0.0, zip, Python 3 and cryptography.

```sh
python3 -m unittest discover -s tests -v
bash tools/build-apk.sh
python3 tools/package-installer.py
```

The workflow launches an Android 35 emulator and tests AES-GCM local storage, message encryption/signatures and tamper rejection, backup restore/wrong password rejection, real pinned HTTPS relay delivery/acknowledgement and wrong certificate rejection. It also installs the app, verifies its login screen and saves a screenshot. `tools/smoke.sh` needs an Android emulator using `10.0.2.2` for the host.

The beta signing key is generated in the build environment and reused through a private Actions cache. Cache loss changes the signing identity: export a chat backup before uninstall/reinstall. Production distribution needs a durable private signing key and a planned update/recovery process. Never commit `.keys`, root passwords, server private certificates or account data.

## Operations

The server is an initial implementation for a small test group. Service status: `systemctl status oldy-chat`. Restart: `systemctl restart oldy-chat`. Accounts: `/var/lib/oldy-chat/accounts.sqlite3`. TLS certificate expires after one year and requires operator renewal and updating the client pin. User chat backups are independent of server account backups. No unattended maintenance by an assistant is implied.

The console installer is specific to the user's authorized server IP. It has no embedded credentials and does not change root/SSH login. Installation, external reachability and operation on physical Android phones must be checked on the actual server/devices after the automated tests.
