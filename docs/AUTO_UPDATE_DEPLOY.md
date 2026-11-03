# 152 Auto Update Deployment (Cloudflare Workers Static Assets)

## Server
Existing Worker: `https://152-updates.torclassic01.workers.dev`

Static assets layout (`C:\\Users\\os\\Desktop\\152-updates` on the publisher PC):

```text
152-updates/
  wrangler.jsonc
  public/
    health.json
    latest.json
    downloads/
      152-1.5.3.jar
```

`gradle.properties` now uses `archives_base_name=152` and `mod_version=1.5.3`.

## First deployment (bootstrapping)
1. Build in the project directory with `gradlew.bat clean build`.
2. Confirm `build/libs/152-1.5.3.jar` exists and build/verifyModJar succeeded.
3. Install this version **manually once**: the existing 1.5.2 client has no updater.
4. Copy the finished JAR to `public/downloads/152-1.5.3.jar`.
5. In PowerShell in the Cloudflare folder, generate the manifest using the exact new JAR:

```powershell
$version = "1.5.3"
$jar = ".\\public\\downloads\\152-$version.jar"
$size = (Get-Item $jar).Length
if ($size -gt 25MB) { throw "Static Assets 25 MiB limit exceeded" }
$hash = (Get-FileHash $jar -Algorithm SHA256).Hash.ToLowerInvariant()
@{ version=$version; minecraft="1.21.4";
   download="https://152-updates.torclassic01.workers.dev/downloads/152-$version.jar";
   sha256=$hash; size=$size
} | ConvertTo-Json | Set-Content ".\\public\\latest.json" -Encoding utf8
npx.cmd wrangler deploy
```

6. Verify `/latest.json` responds with the new version and the linked JAR can be downloaded.
7. To test an actual update from 1.5.3, release **1.5.4** or newer and repeat steps 1-6.

## Player experience
- On startup, a nonblocking check reads `/latest.json`.
- Open `/152 -> Auto Update` to review version and approve download.
- The mod only accepts HTTPS on the pinned Worker host and the expected `/downloads/152-VERSION.jar` path.
- Download must match size, SHA-256, `fabric.mod.json` id and version.
- On closing Minecraft, a separate Java process waits for shutdown, backs up the existing JAR and replaces it. Relaunch Minecraft manually.
- Backups are left alongside the original JAR as `*.152-backup-<timestamp>`. The separate helper writes `config/152-update/install.log`.

## Security and limitations
- SHA-256 from the same server is an **integrity check**, not independent authentication. Compromise of the Worker could allow an attacker to serve both a malicious JAR and a matching hash. A signing key baked into the client and signed manifests are recommended before wide distribution.
- The install helper supports a regular JAR file whose **immediate parent directory is named `mods`**. Nonstandard launchers, loaded JAR copies, nested mod folders, Windows file locks, and permissions can prevent installation. In that case do a manual install.
- This does not reboot the game automatically.
- This feature has not yet passed a documented real Windows/Lunar Client end-to-end test.
