# Ownership and security boundaries

Owner: maxfilov4. This repository must remain private. Do not invite contributors or install GitHub applications without the owner's explicit authorization. CODEOWNERS documents ownership; it is not an enforced branch protection rule by itself.

The Android build workflow accepts only runs initiated by maxfilov4 from this same repository. Third-party pull request code must never run with a signing key, server credentials or deployment permissions. Workflow tokens have contents-read permission. APKs contain neither the server's root password nor the APK signing private key.

Current beta signing uses a private Actions cache. This is temporary build infrastructure, not a durable production keystore. Before public distribution, provision an owner-controlled, backed-up release signing key in protected signing infrastructure, and configure branch/environment protections from the owner's GitHub settings. The connector used in this session cannot configure repository administration or secrets. No such protection or production key provisioning is claimed here.

No application administrator can decrypt message content merely by possessing the server account database. The prototype pins contacts' public keys internally, but first use still depends on the directory server (TOFU). The UI hides fingerprint fields as requested by the owner, so this beta has no user-facing out-of-band key verification flow. Private message keys can now be restored from a server-side backup encrypted with the account password (PBKDF2-SHA256/AES-GCM). A stolen server database therefore permits offline password guessing against that encrypted backup; long unique passwords matter. Existing accounts need to enable this backup once. The server sees only ciphertext for message history, but sees routing metadata, profiles, membership and the ordinary MP4 bytes uploaded by the application owner. Those MP4 uploads are not end-to-end encrypted.

Closed source and APK signatures do not make software unbreakable. APKs can be inspected and modified; Android rejects replacing an already installed app with a differently signed build. This does not stop a person installing a counterfeit app separately. Only distribute verified original builds through owner-controlled channels.

This beta's encryption protocol has not been independently audited and lacks forward secrecy. Before wider release, use an audited ratcheting protocol, review authentication/recovery, perform penetration testing and review the server's operational security. Do not market this beta as suitable for highly sensitive conversations.

The owner supplied a root password in conversation. It is not stored in this repository. Change it through the server console before deployment and use SSH keys for future administration. Keep MFA enabled on GitHub and hosting accounts. Source ownership and release control do not imply a right or technical ability to read users' private messages.

## 0.3 access controls and release integrity

The API checks account tokens and ECDSA envelope signatures, global unique handles, current room membership, channel publishing and pin permissions, owner bans and personal block boundaries. Video uploads are additionally bound to an existing owner nickname plus its public signing-key hash in a root-owned server file. A modified client cannot grant itself this server role. Streaming requires an authenticated current channel member. Files use generated identifiers; upload offsets, total size (2 GiB maximum), chunks and range requests are bounded. Partial uploads never become playable until completed.

HTTPS keeps normal network sniffers from reading credentials. Android enforces hostname verification plus the bundled exact server certificate pin. The server certificate and private key are retained by upgrades; expiry/rotation needs a planned signed client release. A phone owner can inspect the endpoint and, on a compromised/rooted device, extract that device's session or decrypted content; obfuscation cannot provide a guarantee against this. Sessions are per-user, stored only as hashes on the server, expire and can be revoked at logout. APKs contain no universal login or administration key.

Optional updates validate package identity, increasing version, byte size, SHA-256 and the same Android signing certificate before invoking the OS installer. Release archives preserve accounts/TLS configuration and snapshot SQLite before additive migrations. Previous APKs are supported for existing protocol features; new comments and controls require 0.3. The first move from 0.2 is manual because 0.2 did not contain an update checker.

Password login transmits the password only inside pinned TLS. The API server is trusted during login; the password-encrypted backup is not a zero-knowledge recovery protocol and does not protect against an actively malicious authentication server that captures that password.
