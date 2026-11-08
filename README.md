# My TV

Setup for **Avy's TV**, a TCL Google TV (G10, Android 12, 1.8 GB RAM).

The stock Google TV home screen shows ads and uses a lot of memory (about 110 MB including its services). This repo replaces it with **Avy TV Home**, a small ad-free launcher of about 36 KB with a glass look. **Once it's installed, everything works on the TV with the remote. The PC is only needed to install or update it.**

## Features

- **Glass theme:** a soft colour backdrop with frosted-glass cards. Choose from Aurora, Ocean, Sunset or Forest with the **Theme** tile in the *System* row.
- **Clock** with a greeting and the date.
- **Live weather** from [Open-Meteo](https://open-meteo.com): temperature, feels-like, high/low, humidity, wind and a 3-day forecast. The location is detected automatically, and it refreshes every 30 minutes.
- **Subscription tracker, managed on the TV:**
  - Select **+ Add**, pick the app (Netflix, Prime Video, Apple TV and others) or type a name, then set the date and choose One-time, Monthly or Yearly.
  - Monthly and yearly plans roll their date forward automatically, so you only enter them once.
  - The badge shows the days left: green, amber (14 days or less) or red (3 days or less, or expired).
  - Select a subscription to edit or delete it.
- **Apps in rows of small round icons** (about ten per row): Streaming, Sports & Live, Tools, More apps and System. The header is one slim line (clock, compact weather chip, ⚙, avatar), and subscriptions are a single strip of chips, so nothing covers the apps. Select the weather chip for the full forecast.
- **Reordering apps**, and the order is remembered. There are two ways:
  - **One app:** hold **OK** on it (or press **Menu**), choose **Move left / right**, use ◀ ▶ to slide it (it wiggles while the other apps move aside), then press **OK** to drop it. *Move to start* and *Move to end* are in the same menu.
  - **Several apps:** in the System row, open **⇄ Arrange apps**. For each app, press **OK** to pick it up, ◀ ▶ to move it and **OK** to drop it, then press **Back** when you're done.
- **Animations:** a slowly drifting background, a floating weather icon, staggered slide-in on open, a bouncy focus zoom, pop-in dialogs, theme cross-fade and pulsing badges for subscriptions that are due soon. They pause while another app is open.
- **⚙ Quick settings** (top right): Wi-Fi, Bluetooth, Picture & display, Sound, Inputs (HDMI), Screen saver, Remote buttons, Change theme and All settings.
- **Profile avatar** (top right): select it, choose **Show my Google account**, and press **Allow**. The avatar then shows your account initial and the menu shows your Google email. You can also set a display name. The app targets SDK 25 on purpose: newer targets can't see Google accounts on Google TV without the stock launcher's picker.
- **Remote app buttons** (Netflix, YouTube, Prime Video and others): Google TV normally handles these inside the stock launcher, which is disabled here. Avy TV Home includes a small accessibility service that handles them instead. It only sees button presses, never screen content. To set it up:
  1. Turn it on once in **Settings → Accessibility → Avy TV Home remote buttons**. The shortcut is in ⚙ → Remote buttons. **This TV switches accessibility off whenever the app is updated**, so after an update, switch the helper off and on once.
  2. The Netflix (4062), YouTube (4065) and Prime Video (4099) buttons work out of the box, the Settings button (Google TV sends NOTIFICATION, 83) opens ⚙ Quick settings, an Apps button (284) goes to the home screen, and the Web button (4057) opens the BrowseHere browser. For other buttons, go to **⚙ → Remote buttons → Set up a button**, press the button, then pick the app. **Configuration** on the Accessibility page opens the same screen.

### Remote tips

| Where | Do |
|---|---|
| Top app row | Press **▲** to jump to **+ Add** |
| Date fields | Press **OK** on Day, Month or Year, change it with **▲ ▼**, then press **OK** again |
| Name field | Press **OK** to open the keyboard |

### Why subscriptions aren't detected automatically

Android keeps each app's account data private to that app, and Netflix, Prime Video and Apple TV have no public API for plan or expiry dates. A launcher can't read them. Setting a plan to Monthly or Yearly means you enter it once and the TV keeps the date up to date.

## Layout

| Path | What it is |
|---|---|
| `tv-snapshot/` | The TV's setup before the change: device info, launchers, installed apps |
| `launcher/` | Avy TV Home source (plain Java, no Gradle) and `build.ps1` |
| `scripts/` | Snapshot, install, restore and optional config scripts |
| `config/config.json` | Optional city override. **Git-ignored.** |

## PC scripts

The default TV address is `192.168.1.124:5555`. To use a different one, set `$env:TV_ADDR` first.

```powershell
scripts\snapshot.ps1                 # save current TV setup into tv-snapshot\
launcher\build.ps1                   # build launcher\out\avy-tv-home.apk
scripts\install-launcher.ps1         # install/update it and make it the home screen
scripts\set-config.ps1               # optional: force a weather city from config\config.json
scripts\set-avatar.ps1 photo.jpg     # optional: your picture in the top-right avatar
scripts\restore-stock-launcher.ps1   # go back to the original Google TV home
```

### Build requirements

- JDK 17: `winget install Microsoft.OpenJDK.17`
- From the Android SDK: `platforms;android-31` and `build-tools;35.0.1`. `build.ps1` looks for them in `%LOCALAPPDATA%\Android\sdk-lite`, `%LOCALAPPDATA%\Android\Sdk` or `ANDROID_HOME`.
- Updates must be signed with the same `launcher\signing.keystore`. It's created on the first build, is git-ignored, and should be backed up. If it's lost, uninstall the app before installing a new build. That also clears the subscriptions saved on the TV.

## What gets changed on the TV

`install-launcher.ps1` **disables** two Google packages with `pm disable-user`. Nothing is uninstalled.

- `com.google.android.apps.tv.launcherx`: the stock Google TV home screen, which is where the ads come from.
- `com.google.android.tungsten.setupwraith`: Google TV's setup app. It registers a system "recovery" home screen that would otherwise take over the Home button from any third-party launcher.

`restore-stock-launcher.ps1` re-enables both and fully undoes the change. Run it before a factory reset or a big system update.
