# Warpinator for Android (unofficial)

This is an unofficial reimplementation of Linux Mint's file sharing tool [Warpinator](https://github.com/linuxmint/warpinator) for Android.

## Download
Get the APK from the "Releases" page

Also available on F-Droid and Google Play  
<a href='https://apt.izzysoft.de/fdroid/index/apk/slowscript.warpinator'><img src='https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png' width='170px'/></a>
<a href='https://play.google.com/store/apps/details?id=slowscript.warpinator'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png' width="170px"/></a>

## Building

Build with Android Studio or with this command (you will need to install Android SDK yourself though):

```
export ANDROID_SDK_ROOT=$HOME/Android/Sdk
./gradlew :app:assembleDebug
```

## Translations

Warpinator for Android can be translated just like any other Android application. Just copy `strings.xml` from `/app/src/main/res/values` to `/app/src/main/res/values-xx` where `xx` is the code of the language you are translating to. Then translate everything in the new xml file and submit a [PR](https://github.com/slowscript/warpinator-android/pulls).
