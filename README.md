# Heroku Host APK

Android mini-UserLAnd style installer for Heroku Userbot.

## Download

APK is in the repository root:

```text
HerokuHost-by-ziwupa-debug.apk
```

Latest release download:

```text
https://github.com/ziwupa/heroku-host-apk/releases/latest
```

Features:

- Downloads and extracts Ubuntu Base rootfs.
- Uses bundled UserLAnd support assets (`proot`, `busybox`, loaders).
- Button flow: `INSTALL LINUX`, `INSTALL HEROKU`, `START BOT`.
- Interactive terminal input through the bottom input field.
- Selectable logs and `COPY LOGS` button.
- Wake lock and battery optimization prompt for long installs.

Developer: [@ziwupa](https://t.me/ziwupa)

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/opt/android-sdk /home/user/gradle-8.4/bin/gradle assembleDebug --no-daemon
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Copy APK to repo root after building:

```bash
cp app/build/outputs/apk/debug/app-debug.apk HerokuHost-by-ziwupa-debug.apk
```
