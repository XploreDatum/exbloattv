# Troubleshooting

Start with the section that matches what you see. Messages in *italics* are what the app shows.

- [The app can't find the TV](#the-app-cant-find-the-tv)
- ["Allow USB debugging?" never appears on the TV](#allow-usb-debugging-never-appears-on-the-tv)
- [The TV refuses the connection](#the-tv-refuses-the-connection)
- [The TV's address changed](#the-tvs-address-changed)
- [A VPN is on on the phone](#a-vpn-is-on-on-the-phone)
- [Another phone is working on this TV](#another-phone-is-working-on-this-tv)
- [The FLauncher download fails](#the-flauncher-download-fails)
- [A step says it needs attention](#a-step-says-it-needs-attention)
- [The TV boots to a blank or black home screen](#the-tv-boots-to-a-blank-or-black-home-screen)
- [Older Android versions](#older-android-versions)
- [Still stuck?](#still-stuck)

## The app can't find the TV

*No TVs found. Make sure the TV is on and on the same Wi-Fi as this phone. Guest networks and some routers keep devices apart, so the phone can't see the TV. Try your main Wi-Fi, or type the TV's address.*

- **Same Wi-Fi.** The phone and the TV must be on the same Wi-Fi network. Mobile data can't reach the TV.
- **Guest networks and "client isolation".** Many routers stop devices on a guest network (and sometimes on the main one) from seeing each other. Put both on your main Wi-Fi, or turn off client/AP isolation in the router settings.
- **TV switched on.** A TV in deep standby may not answer. Turn it on with the remote and tap **Look again**.
- **Type the address.** Tap **Enter IP address**. On the TV, open **Settings → Network & Internet** and look for the IP address (for example 192.168.1.50).
- **Large networks.** The app scans at most the 254 addresses around the phone's own address (a /24). If your network is bigger and the TV's address differs in the third number, type the address.
- **TV shows as Setup, not Ready.** The app found the TV but debugging isn't on yet. Tap it and follow the steps.
- **Names missing (Android 16 and newer).** Without the *Nearby devices* permission TVs may show as "Android TV" with no name. Finding them still works.

## "Allow USB debugging?" never appears on the TV

The prompt shows on the TV screen, not the phone, and only while the TV is on.

- Make sure the TV is awake and showing something (the home screen is fine), then tap **Try again** on the phone.
- The prompt closes by itself after a while. If you missed it, tap **Try again**; the app waits up to a minute each time.
- Check **USB debugging** is still on in **Developer options**. On some TVs, also turn on **Network debugging** if you see it.
- If you once pressed **Cancel**/**Deny**, open **Developer options** on the TV, choose **Revoke USB debugging authorizations** (if your TV has it; this is the TV's own wording), and try again.
- The TV shows this phone as `exbloattv@<phone model>`. Each phone has its own key, so every phone is asked once.
- If the app's key on the phone was damaged, the app quietly makes a new one, and the TV asks once more. That is expected.

## The TV refuses the connection

If the TV turns the connection away, debugging is off and the app takes you back to **Turn on debugging**.

- Some TVs turn USB debugging off after a software update or a restart. Turn it on again.
- TVs that only offer **Wireless debugging** with a pairing code (no plain USB/network debugging) aren't supported yet. Look for **USB debugging** instead.
- *Can't reach the TV. Make sure it's on and on the same Wi-Fi.* means the TV didn't answer at all: check it's on and the phone is on the right Wi-Fi.

## The TV's address changed

Routers sometimes give the TV a new address. **Your TVs** then shows the TV as **Not found**.

- Tap **Add a TV** and find it again. The app recognises the TV by its serial number, not its address, and shows it as *(name) · new address*. Its record and history are kept.
- To stop it happening, give the TV a fixed address (a "DHCP reservation") in your router settings.

## A VPN is on on the phone

The app routes TV connections over the phone's Wi-Fi even when a VPN is on. Some VPN apps, especially with *Always-on VPN* and *Block connections without VPN*, refuse that. The app then falls back to the normal route, which may go through the VPN and never reach the TV.

- Pause the VPN while you use ExBloat TV, or
- allow local network access / exclude ExBloat TV in the VPN app's settings, if it has that option.

## Another phone is working on this TV

*Another phone (name) is working on this TV right now.* or *Another phone (name) is changing this TV right now. Try again when it's done.*

Only one phone can clean or undo a TV at a time.

- Wait for the other phone to finish, then try again.
- If the other phone was switched off or left home mid-run, its hold runs out by itself **10 minutes** after its last step. Then try again.
- *This TV was changed from another phone while you were away. Check the TV again before carrying on* means another phone finished something while yours was paused. Tap **Check the TV again** to read the TV again and review your choices.

## The FLauncher download fails

FLauncher is downloaded by the phone over its normal internet connection (Wi-Fi or mobile data) when the cleanup reaches **Set up the new home screen**.

- *Couldn't download the new home screen. Check the phone's internet connection*: check the phone is online, then tap **Retry** on that step. Nothing on the TV was changed by the failed download.
- *The new home screen download didn't match the expected file, so it wasn't used*: the file that arrived was not the expected FLauncher 0.18.0 (for example, a hotel or office network replaced it with a login page). It was deleted and not installed. Try again on another network.
- You can always skip this step, or turn **New home screen (FLauncher)** off next time. Everything else still works.
- If FLauncher is already on the TV, the app uses that copy and downloads nothing.

## A step says it needs attention

Each step shows a plain reason with **Retry** and **Skip**. A few common ones:

| Message | What to do |
|---|---|
| *Lost connection to the TV. Check it's on and on the same Wi-Fi, then tap Retry* | Wake the TV, check Wi-Fi, tap **Retry**. |
| *This app keeps the TV working, so it was left on* | Nothing; this is the safety list doing its job. Tap **Skip**. |
| *Couldn't switch this app off* | Some TVs refuse to switch certain apps off. Tap **Skip**. |
| *The TV didn't restart. Tap Retry.* | The restart didn't happen. Tap **Retry**. |
| *The TV didn't come back after restarting. Check it's on, then tap Retry* | Make sure the TV is on and finished starting, then **Retry**. |
| *These apps switched themselves back on: …* | The TV turned these back on during the restart. Run **Clean up again** later, or leave them. |
| *Nothing changes on the TV until the restore point is saved. Tap Retry.* | The app couldn't write its record on the TV. Tap **Retry**; it won't change anything until that works. |
| *Update ExBloat TV first* | A newer version of the app set this TV up. Install the latest release on this phone. |

**Finish anyway** skips every step that needs attention and finishes the rest.

## The TV boots to a blank or black home screen

This shouldn't happen: the app only switches the old home screen off after FLauncher is confirmed working, and puts it back by itself if FLauncher doesn't come up after the restart. If it does happen, here is how to get out of it.

### With the app

1. Press **Home** on the remote and wait a few seconds.
2. Open ExBloat TV, tap the TV, and tap **Undo everything**. If the TV asks **"Allow USB debugging?"**, press OK.

Whenever the app connects, it checks for a half-finished home screen swap and puts the old home screen back first. Undo then reverses everything else.

### By hand, from a computer

You need the Android platform tools (`adb`) on a computer on the same Wi-Fi. The TV will ask to allow the computer the first time.

```sh
adb connect 192.168.1.50:5555          # use your TV's address

# What did ExBloat TV change? The log lists every change, newest last.
adb shell cat /data/local/tmp/exbloattv/state.json

# Which apps are switched off right now?
adb shell pm list packages -d
```

Switch the stock home screen back on. Use the one your TV has (from the list above, or the `from` value of the `LAUNCHER_SWAP` entry in the log):

| TV | Home screen package |
|---|---|
| Android TV | `com.google.android.tvlauncher` |
| Android TV (older) | `com.google.android.leanbacklauncher` |
| Google TV | `com.google.android.apps.tv.launcherx` or `com.google.android.tv.home` |
| Xiaomi | `com.mitv.tvhome` |
| TCL | `com.tcl.launcher` |
| Sony | `com.sony.dtv.tvx` |
| NVIDIA Shield | `com.nvidia.shieldtech.launcher` |

```sh
adb shell pm enable com.google.android.tvlauncher                  # your TV's home screen
adb shell pm enable com.google.android.tungsten.setupwraith        # the setup wizard, if it was switched off
adb shell input keyevent KEYCODE_HOME
```

Once the old home screen is back, you can remove FLauncher if you like:

```sh
adb shell pm uninstall me.efesser.flauncher
```

Other changes, if you want to reverse them by hand:

```sh
adb shell pm enable <package>                         # an app that was switched off (DISABLE)
adb shell cmd package install-existing <package>      # an app removed for the user (REMOVE_FOR_USER)
adb shell settings put global window_animation_scale 1.0
adb shell settings put global transition_animation_scale 1.0
adb shell settings put global animator_duration_scale 1.0
adb shell cmd appops set <package> RUN_IN_BACKGROUND allow      # use the mode in the entry's "from"
adb shell cmd appops set <package> RUN_ANY_IN_BACKGROUND allow
```

If you fix things by hand, **Undo everything** in the app still works afterwards: it sees what is already back and simply clears it from the list.

## Older Android versions

**Phones on Android 8 to 11.** The app uses its own green colours instead of your wallpaper colours (dynamic colour needs Android 12). **Open Wi-Fi settings** opens the full Wi-Fi settings page rather than the quick panel on Android 8 and 9. Notifications don't need a permission before Android 13.

**TVs on Android 8.** **Stop video apps running in the background** sets one background setting (`RUN_IN_BACKGROUND`) instead of two; the second one (`RUN_ANY_IN_BACKGROUND`) only exists from Android 9.

**TVs on Android 11 and newer** may also offer **Wireless debugging** with a pairing code. The app can't use that yet; turn on **USB debugging** (or **Network debugging**) instead.

## Still stuck?

Open a [bug report](https://github.com/XploreDatum/exbloattv/issues/new?template=bug_report.yml) (or tap **About → Report a problem** in the app) with your phone model, TV model, Android versions and what happened. A screenshot of the TV's page in the app (**What was changed**, with **Show details**) helps a lot.
