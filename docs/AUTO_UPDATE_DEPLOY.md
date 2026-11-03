# 152 Signed Updates — Cloudflare Workers Static Assets

Endpoint: `https://152-updates.torclassic01.workers.dev/latest.json`
Minecraft: 1.21.4. Java: 21. Release name: `152-<version>.jar`.

## Signature rules

The updater **requires** `signature` in `latest.json`; unsigned, invalid, or missing-public-key updates fail closed. The trusted Ed25519 public key is packaged within the JAR at:

`src/main/resources/assets/untitled/update-public-key.txt`

The file initially contains instructions rather than a key. **Replace its contents with the Base64 DER/X.509 public-key line from local keygen before building the mod**.

Manifest signed bytes use exact UTF-8 with LF (not JSON serialization):

```text
152-update-manifest-v1\n
<version>\n
<minecraft>\n
<download>\n
<sha256-lowercase>\n
<size-as-base10-integer>\n
```

(`\\n` above denotes a single newline after each line, including the last.)

This binds the version, target Minecraft version, pinned HTTPS download URL, SHA-256, and byte size to the signature. A compromised Cloudflare server cannot produce a new valid signature without the private key. This does **not** protect against a compromised developer signing machine or replay of an already valid signed release.

## One-time preparation on the Windows development PC

1. Run `py -m pip install cryptography`.
2. From the cloned `untitled` repo, run `py tools/sign_update.py keygen`.
3. Choose a strong password; keep the generated encrypted private PEM and password safe. The private PEM is saved in `%USERPROFILE%\.152-signing\private.pem` (outside this repository).
4. Open `%USERPROFILE%\.152-signing\public.txt`, then copy the **single Base64 line** into `src/main/resources/assets/untitled/update-public-key.txt`. This public key *may* be committed; the private key must *never* be committed or uploaded.
5. **Keep a secure offline backup of your private key**. Losing it prevents signing new updates trusted by clients already installed. Key rotation requires an explicit trusted update strategy.

Do not share private keys or passwords in chat, GitHub, Cloudflare, or screenshots.

## First signed release (1.5.8)

The source repo uses `mod_version=1.5.8`. The previously deployed 1.5.7 is unsigned-capable and can accept a manifest with extra signature field.

1. Replace the public-key resource with your actual key, then commit **only the public key** to GitHub.
2. Run `.\gradlew.bat clean build`. Confirm `build/libs/152-1.5.8.jar`.
3. Copy `152-1.5.8.jar` into `C:\Users\os\Desktop\152-updates\public\downloads\`.
4. From the repository root, run:

```powershell
py -m pip install cryptography
py tools/sign_update.py sign --jar "C:\Users\os\Desktop\152-updates\public\downloads\152-1.5.8.jar" --version 1.5.8 --out "C:\Users\os\Desktop\152-updates\public\latest.json"
```

5. Inspect `latest.json` (ensure `version` and `signature` exist). Run `npx.cmd wrangler deploy` **from the `152-updates` folder**.
6. Verify the remote `/latest.json` and JAR download. On existing 1.5.7, check for update and approve; after relaunch confirm 1.5.8.
7. To test signature verification end-to-end from 1.5.8, build and sign 1.5.9 the same way and update using the 1.5.8 client.

## Security testing (on a test profile, not production)

- Remove `signature` from the remote manifest: updater must **refuse to offer download**.
- Change a signed field or one signature byte: updater must reject.
- Restore the valid signed manifest: updater should offer the newer version.
- Corrupt the JAR while leaving the manifest unchanged: SHA-256 check must block installation.
- Keep an older signed manifest: rollback protection is limited to version comparison with the installed mod. An attacker could replay a previously signed higher version if one exists.

## Player experience and notes

Open `/152 -> Auto Update -> Check for Updates`, then click `Download` only for a verified newer version. The download is checked for size, SHA-256, Fabric mod ID/version and client entrypoint. After Minecraft exits, a separate helper Java process handles the Windows JAR swap; the user must restart Minecraft. The client has been tested on Lunar Client with its versioned Fabric mod directory; backup cleanup only applies to backups created by the current updater.

The public key currently bundled in this repository is a **placeholder**, so after checking out this commit, update checks will fail until the real public key has been installed and the mod rebuilt. Do not disable signature verification as a workaround.
