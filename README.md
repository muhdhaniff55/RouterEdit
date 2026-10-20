# RouterEdit

Small Android app (Kotlin + Jetpack Compose + SSHJ) to browse a router over SSH,
edit text config files (e.g. OpenWrt `/etc/config/*`) and save them back.

- Password or private-key login (OpenSSH-format keys; passphrase supported)
- Uses SFTP when available; otherwise falls back to plain SSH exec (`ls`/`cat`),
  which works on Dropbear routers without `sftp-server`
- First save of a file creates a one-time backup `file.bak` next to it
- Host key is pinned on first connect; use "Forget saved host key" after reflashing the router
- "Run" button for one-off commands (e.g. `/etc/init.d/network restart`, `uci show`)
- Files over 256 KB or binary files are refused in the editor

## Build

### Option A: Android Studio
1. Open this folder in Android Studio (JDK 17).
2. If it complains about a missing Gradle wrapper, open the Terminal and run
   `gradle wrapper --gradle-version 8.10.2`, then sync.
3. Build > Build APK(s), or run on your phone with USB debugging.

### Option B: GitHub Actions (no local setup)
1. Create a new GitHub repo and push this folder to `main`.
2. Open the Actions tab > "Build APK" > download the `RouterEdit-debug-apk` artifact.
3. Unzip it and install `app-debug.apk` on your phone (allow installs from unknown sources).

## Notes
- This has not been compiled or tested yet. If the build reports an error, send me the message.
- Restarting the network/wifi from the Run box can drop your SSH session. Save first.
- Keep a way back in (wired port or failsafe mode) before editing network/firewall configs.
