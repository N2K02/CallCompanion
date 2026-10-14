# Signing key

`keys.tar.enc` holds this app's private signing key (plus the old public test key, needed
for the key-rotation proof). It is encrypted with AES-256 (OpenSSL, PBKDF2, 600,000 rounds).

The passphrase is **not** in the repo: it is the `SIGNING_PASSPHRASE` secret in
Settings → Secrets and variables → Actions. The build unlocks the key only while signing.

Keep a copy of the passphrase in a password manager. Without it, no new version can be
built that installs over the current app.
