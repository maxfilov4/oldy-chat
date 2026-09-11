# Separate media VPS access

Target: `2.56.174.123:22`. The existing chat server and data stay in place.
This workflow only checks SSH, capacity, occupied ports and public YouTube HTTPS reachability.
It does not install services, modify files on the VPS, copy keys or data from the chat server,
call the sticker API, or enable a relay. Video playback and provider eligibility remain unverified.

In the NEW VPS provider web console, obtain its PUBLIC host key:

```sh
awk '{print "2.56.174.123 " $1 " " $2}' /etc/ssh/ssh_host_ed25519_key.pub
```

Add repository Actions Secrets at
https://github.com/maxfilov4/oldy-chat/settings/secrets/actions :

- `AEZA_SSH_KNOWN_HOSTS`: the exact output above, obtained through the trusted VPS console.
- `AEZA_SSH_PRIVATE_KEY`: the dedicated SSH private key already created for this VPS.
  Use the complete private-key file, not its `.pub` companion. Never add it to source code or chat.
- `AEZA_SSH_USER`: existing authorized SSH login; defaults to `root` if omitted.
- `AEZA_SSH_KEY_PASSPHRASE`: only if the private key has a passphrase.

The matching public user key must already be in the selected server user's `authorized_keys`.
If it is not, add that public key using the provider console; no server reinstall is needed.
Do not replace existing authorized keys.

The workflow fails with SETUP_REQUIRED while secrets are absent. After adding them, rerun
the failed workflow. It can also be dispatched once registered on the default branch.
The fixed host key is required; strict host verification is never disabled. Keys exist only
in a private temporary runner directory and are removed when the check finishes.

The runner uses the SSH permissions of the supplied account. This first diagnostic is read-only;
granting a root key would also authorize future workflows to administer that VPS. Use a separate,
revocable key and keep repository write access restricted to trusted maintainers.
