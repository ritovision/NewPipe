# AI Control production Android upgrade — 2026-11-09 (Eastern)

**Outcome: installed successfully as an in-place upgrade.** The Cloudflare AI gateway was already deployed separately; see [production gateway deployment](ai-passkeys-production-gateway-deployment-20261109.md).

## Source and build responsibilities

- Source branch: `feature/ai-passkeys-20261109`; source commit `06992e64ee8dc1a5ddb661515e2242193e3815a4`.
- **@NotCodex-2 performed the successful release build** with JDK 21, Android SDK 37, Gradle release variant, package suffix `.ritoprod`, versionCode override `1016`, and version name suffix `-ritoprod.passkeys20261109`. Initial offline Gradle attempt failed only because `com.android.tools.lint:lint-gradle:32.4.1` was not cached; the online `--no-configuration-cache` build completed successfully (98 tasks, 4m57s).
- Source `app/build/outputs/apk/release/app-release-unsigned.apk`: 12,007,670 bytes; SHA-256 `237b918fa4397e237bbecbd9fc1d2ef8d76633e1748646baefe78cf6eb8145ff`. Android `aapt2 dump badging` verified exact package `org.schabi.newpipe.ritoprod`, versionCode `1016`, versionName `0.29.1-ritoprod.passkeys20261109`.
- The initial redundant primary release compilation was stopped after its purpose was superseded by a secure binary transfer. **No primary build output was installed.**
- Binary transfer used **Tailscale Taildrop** from secondary Windows host `alex-computer` to primary `desktop-7kso456`. The received unsigned APK had the exact same SHA-256. Earlier direct TCP transfer probe failed due to network access and its temporary script was removed, without modifying firewall rules.

## Production signature and local retention

- The *installed pre-upgrade APK* (`1015`, `0.29.1-ritoprod.cloud20261022`) was read from the phone without modifying it; certificate `CN=RitoVision NewPipe Production`, SHA-256 `a8706c87a71e030a42fdade9f424fb541336fb2b1c54f1875e7294e94a4f8a74`.
- Primary's protected production PKCS#12 signing key `~/.local/share/ritovision/newpipe-release-signing/ritovision-newpipe-prod-20261022.p12` has the **same certificate fingerprint**, verified using public certificate material only. Its mode-600 password file was used via `apksigner --ks-pass file:<file>` without displaying the secret.
- Signed release APK in primary owner-only state: `~/.local/state/newpipe-passkeys-20261109/production-preflight-20261109/newpipe-ritoprod-passkeys-v1016-signed.apk`; 12,118,717 bytes; SHA-256 `1cd34c59455f0c7446a8bf5d142acba302195cd9420d58805ce1155f0b4d6dce`. `apksigner verify --verbose --print-certs` passed v2 and v3 signatures and verified exact production certificate.
- Original version 1015 APK retained as `~/.local/state/newpipe-passkeys-20261109/production-preflight-20261109/installed-ritoprod-before-passkeys-v1015.apk`; SHA-256 `1bb069304cf1f9cceb3c515a7a7d596027a17c325e6fbcb8a4ca9362b92b8c2b`. **An APK is not a backup of internal app data.** Do not blindly downgrade it with `adb install -d`; database and auth state might become incompatible.

## Device deployment and data-preservation boundary

- Explicit Pixel target: `100.68.130.31:5555` through Windows ADB server `5042`, device `Pixel 7`.
- Installer used **`adb install -r --no-streaming`** of the exact signed v1016 APK. Result **`Success`**.
- Before: `org.schabi.newpipe.ritoprod` versionCode `1015`, Android UID `10470`. After: versionCode `1016`, **same Android UID `10470`**. No `adb uninstall`, `pm clear`, backup import, database reset or re-pairing was performed.
- Separate upstream `org.schabi.newpipe` remains installed, with its same package path; it was not replaced.
- Source delta from the Nov 9 `custom/dev` baseline covers only AI Control Kotlin and its strings (no Android Room/schema or Cloud & Sync code changes). The live app data is preserved by the in-place upgrade; **internal catalog and cloud account contents have not been independently opened or audited after installation**. Do not infer item counts from unchanged UID alone.
- **Post-upgrade launch smoke passed on the real Pixel 7 using isolated scrcpy virtual display 13**: `am start --display 13 -n org.schabi.newpipe.ritoprod/org.schabi.newpipe.MainActivity` launched successfully, process PID remained alive after a delay, and Android reported the app's `MainActivity` top-resumed on that display. The owned virtual display exited cleanly and its recording was removed. This is **not** an item-by-item Library/Cloud & Sync data-content audit.
- Private temporary Windows unsigned/signed transport copies can be deleted once authenticated local APK retention is verified; keep both protected APK artifacts.

## Release status and remaining acceptance

- Gateway: **live production**, version `b0367d23-7723-42e7-bfd2-988dc1e693ee`, at 100%, configured passkeys, browser pairing, 90-day sliding OAuth.
- Android: **live production v1016 installed with correct signer**.
- Still required: one-time **production-hostname** passkey enrollment with the actual owner on Pixel 7, followed by sign-out/sign-in and relevant ChatGPT authorization/reconnect checks. The staging passkey is tied to a different relying-party hostname and cannot authorize production.
- The user explicitly instructed **not to call the existing NewPipe ChatGPT plugin** during deployment. This step used only ADB/Cloudflare/NotCodex workstation actions; no plugin invocation after that instruction.
