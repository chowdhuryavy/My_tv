# My TV

Setup for **Avy's TV**, a TCL Google TV (G10, Android 12, 1.8 GB RAM), managed from a Windows PC over adb.

The stock Google TV home screen shows ads and uses a lot of memory (about 110 MB including its services). This repo replaces it with **Avy TV Home**, a small ad-free launcher that shows:

- the time and date
- the current weather for your location, from [Open-Meteo](https://open-meteo.com) with no API key needed. The city is detected from your internet IP unless you set one.
- your paid subscriptions with their expiry dates and days left, coloured green, amber (14 days or less) or red (3 days or less, or expired)
- your apps sorted into rows: **Streaming**, **Sports & Live**, **Tools**, **More apps** and **System**

## Layout

| Path | What it is |
|---|---|
| `tv-snapshot/` | The TV's setup before the change: device info, launchers, installed apps |
| `launcher/` | Avy TV Home source (plain Java, no Gradle) and `build.ps1` |
| `config/config.example.json` | Template for the city and subscriptions |
| `config/config.json` | **Your real values. Git-ignored, never pushed.** |
| `scripts/` | Snapshot, install, config and restore scripts |

## Usage

The default TV address is `192.168.1.124:5555`. To use a different one, set `$env:TV_ADDR` first.

```powershell
scripts\snapshot.ps1                 # save current TV setup into tv-snapshot\
launcher\build.ps1                   # build launcher\out\avy-tv-home.apk
scripts\install-launcher.ps1         # install it, make it Home, turn off the stock launcher
scripts\set-config.ps1               # after editing config\config.json (subscriptions/city)
scripts\restore-stock-launcher.ps1   # go back to the original Google TV home
```

### Subscriptions

Edit `config/config.json`, using dates in `YYYY-MM-DD` format:

```json
{
  "city": "",
  "subscriptions": [
    { "name": "Crunchyroll", "expires": "2026-12-31" }
  ]
}
```

Leave `city` empty to detect it automatically, or set it to a city name such as `"Dhaka"`. Then run `scripts\set-config.ps1`.

### Build requirements

- JDK 17: `winget install Microsoft.OpenJDK.17`
- From the Android SDK: `platforms;android-31` and `build-tools;35.0.1`. `build.ps1` looks for them in `%LOCALAPPDATA%\Android\sdk-lite`, `%LOCALAPPDATA%\Android\Sdk` or `ANDROID_HOME`.

## What gets changed on the TV

`install-launcher.ps1` **disables** two Google packages with `pm disable-user`. Nothing is uninstalled.

- `com.google.android.apps.tv.launcherx`: the stock Google TV home screen, which is where the ads come from.
- `com.google.android.tungsten.setupwraith`: Google TV's setup app. It registers a system "recovery" home screen that would otherwise take over the Home button from any third-party launcher.

`restore-stock-launcher.ps1` re-enables both and fully undoes the change. Run it before a factory reset or a big system update.
