# Set up Avy TV Home on your own TV

This guide replaces the ad-filled Google TV home screen with **Avy TV Home**. The new home screen is ad-free and fast, with a glass theme, weather, a subscription tracker, app search and round app icons. Everything can be undone (see the last section).

**Works on:** Google TV and Android TV sets running Android 9 or newer. It was built and tested on a **TCL Google TV with Android 12**. Other brands work too, but their remote's app buttons may send different codes (see step 7).

**You need:**
- a **Windows PC** on the **same Wi-Fi** as the TV
- about 30 minutes

---

## 1. Turn on developer mode and network debugging on the TV

1. On the TV, go to **Settings → System → About**.
2. Select **Android TV OS build** 7 times, until it says *"You are now a developer"*.
3. Go back to **Settings → System → Developer options** and turn on:
   - **USB debugging**
   - **Wireless debugging** or **Network debugging**, if your TV has it
4. Note the TV's **IP address** from **Settings → Network & Internet → (your Wi-Fi)**, for example `192.168.1.124`.

## 2. Install the tools on the PC

Open **PowerShell** and run each line:

```powershell
winget install Git.Git
winget install Microsoft.OpenJDK.17
```

Then download **Android platform-tools** (adb) from Google, <https://developer.android.com/tools/releases/platform-tools>, and unzip it to `Downloads\platform-tools-latest-windows\`.

Build parts of the Android SDK, about 115 MB from Google's official server:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\sdk-lite"; New-Item -ItemType Directory -Force "$sdk\dl" | Out-Null
foreach ($f in 'platform-31_r01.zip','build-tools_r35.0.1_windows.zip') {
  Invoke-WebRequest "https://dl.google.com/android/repository/$f" -OutFile "$sdk\dl\$f" -UseBasicParsing }
Expand-Archive "$sdk\dl\platform-31_r01.zip" "$sdk\platforms" -Force
Expand-Archive "$sdk\dl\build-tools_r35.0.1_windows.zip" "$sdk\build-tools" -Force
```

## 3. Get this project

```powershell
git clone https://github.com/chowdhuryavy/My_tv.git
cd My_tv
```

## 4. Connect to the TV

```powershell
$env:TV_ADDR = "192.168.1.124:5555"     # your TV's IP address from step 1
& "$env:USERPROFILE\Downloads\platform-tools-latest-windows\platform-tools\adb.exe" connect $env:TV_ADDR
```

The TV shows **"Allow USB debugging?"**. Tick **Always allow from this computer** and press **Allow**.

## 5. Save a snapshot of the TV (recommended)

```powershell
scripts\snapshot.ps1
```

This saves the TV's current setup into `tv-snapshot\`, so you have a record of how it was before.

## 6. Build and install Avy TV Home

```powershell
launcher\build.ps1
scripts\install-launcher.ps1
```

The install script:
- installs the app
- makes it the home screen
- **disables**, but does not delete, the Google TV home screen (`launcherx`) and Google's setup app (`setupwraith`), whose "recovery" screen would otherwise take over the Home button

Press **Home** on the remote. You should see Avy TV Home.

> The first build creates `launcher\signing.keystore`. **Keep a backup of it.** Future updates must be signed with the same key, or you'll have to uninstall first and lose the subscriptions saved on the TV.

## 7. Personalise it on the TV

Everything below is done with the remote:

- **Name and photo:** select the **avatar** (top right) → **Set your name**. Android doesn't give other apps your Google photo, so either run `scripts\set-avatar.ps1 C:\path\photo.jpg` on the PC once, or follow *Google photo sync* in the README to have it update automatically.
- **Weather:** the location is detected automatically. To set a city, put `{ "city": "Dhaka" }` in `config\config.json` and run `scripts\set-config.ps1`.
- **Subscriptions:** select **+ Add**, pick the app, set the date, and choose **Monthly** or **Yearly** so it rolls forward by itself.
- **Themes:** use the **Theme** tile in the System row, or **⚙ → Change theme**. **Auto** changes with the time of day.
- **Reorder apps:** hold **OK** on an app → **Move left / right**, or use **⇄ Arrange apps** in the System row.
- **Remote app buttons:** Netflix, YouTube, Prime Video, Web and Settings work while the home screen is showing. **On other brands the codes may differ:** go to **⚙ → Remote buttons → Set up a button**, press the button, and pick the app.

## 8. Things to know

- **Accounts & Profiles** in Settings is drawn by Google's launcher, so it's blank while that launcher is off. When you need it, run `scripts\google-accounts.ps1`: it switches Google's launcher on, you use the page, then you press Enter to switch back.
- **Remote buttons inside other apps:** by default, app buttons work from the home screen, which keeps held buttons (volume, arrows) repeating normally. `scripts\enable-buttons.ps1` makes them work everywhere, but on some TVs held buttons then stop repeating.
- **Updates:** pull the latest code, then run `launcher\build.ps1` and `scripts\install-launcher.ps1` again.

## Undo everything

```powershell
scripts\restore-stock-launcher.ps1 -UninstallCustom
```

This turns the Google TV home screen and setup app back on and removes Avy TV Home. Then, in **Developer options**, you can turn debugging off again.
