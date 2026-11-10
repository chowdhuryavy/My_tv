# My TV

Setup for **Avy's TV**, a TCL Google TV (G10, Android 12, 1.8 GB RAM).

> **Want this on your own TV?** Follow the step-by-step guide in **[SETUP.md](SETUP.md)**.

The stock Google TV home screen shows ads and uses a lot of memory (about 110 MB including its services). This repo replaces it with **Avy TV Home**, a small ad-free launcher of about 36 KB with a glass look. **Once it's installed, everything works on the TV with the remote. The PC is only needed to install or update it.**

## Features

- **Glass theme:** a soft colour backdrop with frosted-glass cards. Themes: **Aurora, Ocean, Sunset, Forest, Midnight** (starfield), **Neon, Rose**, or **Auto** (Rose mornings, Ocean by day, Sunset evenings, Midnight at night). Backgrounds have soft colour mesh, light streaks, film grain and a vignette; each theme has an accent colour for the focus glow and row titles. Change it with the **Theme** tile in the *System* row or ⚙ → Change theme.
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
- **Animations:** a drifting background with floating light particles, a floating weather icon, staggered slide-in on open, a bouncy focus zoom, the current row's title lighting up, a press-squeeze when you open an app, the clock sliding to each new minute, subscription chips popping in, pop-in dialogs and a theme cross-fade. **Moving an app:** it lifts up and the others dim, the gold ring pulses, it hops into each new spot while the others slide aside, and when you drop it there is a bounce and a gold ripple. Everything pauses while another app is open.
- **⚙ Quick settings** (top right): Wi-Fi, Bluetooth, Picture & display, Sound, Inputs (HDMI), Screen saver, Remote buttons, Change theme and All settings.
- **Profile avatar** (top right): select it, choose **Show my Google account**, and press **Allow**. The avatar then shows your account initial and the menu shows your Google email. You can also set a display name. The app targets SDK 25 on purpose: newer targets can't see Google accounts on Google TV without the stock launcher's picker.
- **Remote app buttons** (Netflix, YouTube, Prime Video, Web, Settings): handled by Avy TV Home itself **while the home screen is showing**. Inside another app, press **Home** first. This keeps held buttons (volume, arrows) repeating normally. Change what a button opens in **⚙ → Remote buttons → Set up a button**.
  - Optional: `scriptsnable-buttons.ps1` turns on the accessibility helper so buttons work inside other apps too. On this TV that stops held buttons from repeating, and it has to be switched on again after every update.

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
scripts\google-accounts.ps1          # Google launcher on briefly for Settings > Accounts & Profiles; Enter = back to Avy
scripts\enable-buttons.ps1           # optional: remote-button helper so buttons work inside other apps too
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

## Google photo sync (automatic profile picture)

Android doesn't give your Google profile photo to other apps; only Google's own launcher could show it. Avy TV Home instead signs in with Google's official **TV sign-in** (you approve on your phone). It asks only for your **name, email and photo**, and refreshes the photo automatically every few hours.

One-time setup (about 10 minutes, free):

1. Open <https://console.cloud.google.com> and **create a project**, for example "Avy TV Home".
2. Go to **Google Auth Platform → Get started**. Enter an app name and your email, choose **Audience: External**, and finish.
3. Under **Audience**, select **Publish app**. Without this, Google signs you out after 7 days. Name, email and photo don't need Google's review.
4. Go to **Clients → Create client** and choose **TVs and Limited Input devices**. Copy the **Client ID** and **Client secret**.
5. On the PC, put them in `config/config.json` (git-ignored, never pushed):
   ```json
   { "city": "", "name": "Avy", "google_client_id": "…apps.googleusercontent.com", "google_client_secret": "…" }
   ```
   Then run `scripts\set-config.ps1`.
6. On the TV, select the **avatar → Sign in with Google (photo)**. On your phone, open **google.com/device**, enter the code shown on the TV, and allow access.

**Without a PC:** skip step 5. On the TV, select **avatar → Sign in with Google (photo) → Enter sign-in ID** and paste the Client ID and secret with the Google TV app's keyboard on your phone. They're saved only on that TV.

To stop syncing, use **avatar → Stop Google photo sync**. To remove access completely, go to myaccount.google.com → Security → Third-party connections.

## Install without a PC (GitHub build)

Every push to `main` builds the APK on GitHub and publishes it at
`https://github.com/chowdhuryavy/My_tv/releases/latest/download/TvHome.apk`.

1. On the TV, turn on **Developer options**, then **USB debugging** and **Network debugging**.
2. Install the APK with the **Downloader** app (paste the link above) or from a phone with **Bugjaeger**.
3. Open **Avy TV Home**. If another home screen is still the default, it offers **Make default**. You can also use **⚙ → Make Avy my Home**. On "Allow USB debugging?", tick **Always allow** and press **Allow**.

GitHub builds are signed with a new key each time, so uninstall the old version before installing an update.

## Accounts & Profiles without the PC (v3.6)

Opening **⚙ → All settings**, the **Settings** tile, or **avatar → Accounts & Profiles** in Avy TV Home now switches Google's launcher on automatically, so Accounts & Profiles works. When you leave Settings, it switches the launcher off again and makes Avy your Home screen. To do this, Avy connects to the TV's own debugging service (`LocalAdb`).

- **First time:** the TV asks **"Allow USB debugging?"** for Avy TV Home. Tick **Always allow** and press **Allow**.
- **Trade-off:** once allowed, Avy TV Home holds full debugging access to the TV, the same as a connected PC. It only uses it to switch the launcher. To withdraw it, go to **Developer options → Revoke USB debugging authorisations**.
- The TV's own **Settings button** on the remote opens Settings directly, so use ⚙ in Avy when you need Accounts & Profiles.
