# Ownership and security boundaries

Owner: maxfilov4. This repository must remain private. Do not invite contributors or install GitHub applications without the owner's explicit authorization. CODEOWNERS documents ownership; it is not an enforced branch protection rule by itself.

The Android build workflow accepts only runs initiated by maxfilov4 from this same repository. Third-party pull request code must never run with a signing key, server credentials or deployment permissions. Workflow tokens have contents-read permission. APKs contain neither the server's root password nor the APK signing private key.

Current beta signing uses a private Actions cache. This is temporary build infrastructure, not a durable production keystore. Before public distribution, provision an owner-controlled, backed-up release signing key in protected signing infrastructure, and configure branch/environment protections from the owner's GitHub settings. The connector used in this session cannot configure repository administration or secrets. No such protection or production key provisioning is claimed here.

No application administrator can decrypt message content merely by possessing the server account database. The prototype pins contacts' public keys internally, but first use still depends on the directory server (TOFU). The 0.2 UI hides fingerprint fields as requested by the owner, so this beta has no user-facing out-of-band key verification flow. Account passwords alone are not enough to recover private message keys. New devices need an encrypted user backup.

Closed source and APK signatures do not make software unbreakable. APKs can be inspected and modified; Android rejects replacing an already installed app with a differently signed build. This does not stop a person installing a counterfeit app separately. Only distribute verified original builds through owner-controlled channels.

This beta's encryption protocol has not been independently audited and lacks forward secrecy. Before wider release, use an audited ratcheting protocol, review authentication/recovery, perform penetration testing and review the server's operational security. Do not market this beta as suitable for highly sensitive conversations.

The owner supplied a root password in conversation. It is not stored in this repository. Change it through the server console before deployment and use SSH keys for future administration. Keep MFA enabled on GitHub and hosting accounts. Source ownership and release control do not imply a right or technical ability to read users' private messages.
