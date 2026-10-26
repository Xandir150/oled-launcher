# OLED Home

A minimal Android TV home screen for low-memory devices: pure black background,
a clock, and large 16:9 tiles.

- TV apps show their banner. Other apps show their icon and name.
- The focused tile grows, gets a white border, and the other tiles dim.
- Hold OK (or press MENU) on a tile to hide the app or open its system info.
  Hidden apps collect in the last tile, where you can bring them back.

Built for a 1 GB RAM Android 14 projector stick. It uses plain framework views
(no AndroidX, no Kotlin) and software rendering, which keeps the process at
about 24 MB PSS on screen and 19 MB in the background.

## Build

Needs the Android SDK (build-tools 36, platform android-36) and a JDK.
No Gradle.

```bash
./build.sh
```

The signed APK is `build/oled-home.apk`. The signing key is created once in
`~/.android/oled-launcher.keystore`.

## Install as the home screen

```bash
adb install -r build/oled-home.apk
adb shell cmd package set-home-activity app.oled.launcher/.MainActivity
```

On Android TV the stock launcher declares a higher HOME priority than a
third-party app can, so it has to be disabled first:

```bash
adb shell pm disable-user --user 0 com.google.android.tvlauncher
```

To go back: `adb shell pm enable com.google.android.tvlauncher`.
