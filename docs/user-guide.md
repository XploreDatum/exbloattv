# User guide

This guide is for anyone at home who wants to tidy up the TV. You don't need to know anything technical. It takes about five minutes, plus a couple of minutes while the TV restarts.

You need:

- an Android phone (Android 8.0 or newer) on the **same Wi-Fi** as the TV,
- the TV remote,
- the TV switched on.

Button names below are written exactly as the app shows them.

## 1. Install the app

1. On your phone, open the [latest release](https://github.com/XploreDatum/exbloattv/releases/latest) and download `ExBloatTV-1.0.0.apk`.
2. Open the downloaded file. Android says your browser (or file manager) isn't allowed to install apps. Tap **Settings**, allow it, then go back and tap **Install**.
3. Open **ExBloat TV**.

On Android 13 and newer the app asks to send notifications. Allow it if you want to see progress and the result while the phone is locked. The cleanup works either way.

## 2. Find your TV

The first time you open the app it goes straight to **Find your TV**. (Later, tap **Add a TV** on **Your TVs** to get here.) The app looks for TVs on your Wi-Fi and lists them as they turn up.

- **Ready** means the TV already accepts the phone. You'll go straight to approving it on the TV.
- **Setup** means the TV was found but debugging isn't on yet. The app shows you how.

Tap your TV, then **Continue**.

If your TV isn't listed:

- Tap **Look again**.
- Or tap **Enter IP address** and type the TV's address. On the TV, open **Settings**, then **Network & Internet**, and look for the IP address (four numbers with dots, like 192.168.1.50). Then tap **Connect**.

If the phone isn't on Wi-Fi you'll see **Connect to Wi-Fi**. Tap **Open Wi-Fi settings**, join the same Wi-Fi as the TV, come back and tap **I'm on Wi-Fi now**.

On Android 16 and newer the app may show *Allow "Nearby devices" so TVs show their names in this list.* Tapping **Allow** only adds names to the list; finding TVs works without it.

## 3. Turn on debugging on the TV

This is a one-time setup on each TV. Grab the remote.

The app guesses your TV's brand. If it guessed wrong, tap **Your TV** at the top and pick the right one. Tap **Show me with pictures** to see a simple drawing of each step.

Follow the steps for your TV:

**Android TV** (most other boxes)
1. Open **Settings → Device Preferences → About**
2. Press OK on **Build** 7 times
3. Go back to **Developer options** and turn on **USB debugging**

**Google TV** (for example Chromecast with Google TV)
1. Open **Settings → System → About**
2. Press OK on **Android TV OS build** 7 times
3. Go back to **System → Developer options** and turn on **USB debugging**

**Xiaomi Mi Box / Mi TV** and **Hisense**
1. Open **Settings → Device Preferences → About**
2. Press OK on **Build** 7 times
3. Go back to **Device Preferences → Developer options** and turn on **USB debugging**

**TCL** and **Sony**
1. Open **Settings → System → About** (older TVs: **Settings → Device Preferences → About**)
2. Press OK on **Android TV OS build** (older TVs: **Build**) 7 times
3. Go back to **Developer options** and turn on **USB debugging**

**Philips**
1. Open **Settings → All settings → Android settings → Device Preferences → About**
2. Press OK on **Build** 7 times
3. Go back to **Developer options** and turn on **USB debugging**

**Fire TV**
1. Open **Settings → My Fire TV → About**
2. Press OK on your device's name (like **Fire TV Stick**) 7 times
3. Go back to **My Fire TV → Developer options** and turn on the **debugging** switch

On Fire TV the home screen stays as it is. Everything else works.

Two tips the app also shows:

- See **Network debugging** as well? Turn that on too.
- Only see **Wireless debugging** with a pairing code? This app can't use that yet. Look for **USB debugging** instead.

You don't need to press anything on the phone. It checks every two seconds and moves on by itself as soon as the TV is ready.

## 4. Allow the phone on the TV

The screen now says **Allow on the TV**. Look at the TV. It shows a message: **"Allow USB debugging?"**, with this phone's name (it starts with `exbloattv@`).

1. Tick **Always allow** (so the TV doesn't ask again next time).
2. Press **OK**.

The app then says **Reading your TV** and looks at every app on it. This takes a few seconds.

If nobody presses OK within a minute, you'll see **The TV didn't answer**. Make sure the TV is on and tap **Try again**. If the TV refuses the connection, debugging is off, so the app takes you back to the setup steps. You can also tap **Show the setup steps** yourself.

Every phone has to be allowed once, even if another phone in your home was allowed before.

## 5. Keep what you watch

This is the main screen. Every app on the TV is listed in groups. Each app has a switch: **on means keep**, off means it will be switched off.

| Group | Starts | What it is |
|---|---|---|
| **Streaming video** | on | Netflix, Prime Video, YouTube, JioHotstar, Plex, VLC, Kodi and the like. |
| **Music & audio** | on | Spotify, YouTube Music and similar. |
| **Live TV & sports** | on | Live Channels and live TV apps. |
| **Features** | on | Things the TV does for you. Each one says what stops working if you switch it off, for example *Voice search: Mic button on the remote stops working*, *Casting: You can't cast from your phone to the TV*, plus the screensaver, TalkBack and text-to-speech. |
| **Google extras** | off | Google apps most people don't use on a TV: the camera app, printing, feedback, the first-time setup helper and similar. |
| **Manufacturer extras** | off | Apps the TV maker added, such as Xiaomi's Mi Channel, updater and auto-installer, or TCL's ad engine and demo mode. |
| **Store & sideload tools** | on | Downloader, file managers and app stores. |
| **Other** | on | *Apps you installed that this app doesn't know yet.* They stay on unless you switch them off. |
| **Ads & tracking** | always off | Apps that show ads or track what you watch. Shown as **Always switched off**. |

Voice search and casting start **on** because people use them without thinking of them as apps; switching them off would break the mic button and casting from phones.

Apps that are already switched off on the TV show as off. Switch one on and the cleanup switches it back on.

Some apps are never offered at all: the TV needs them to work (settings, the remote, Wi-Fi, the Play Store and so on). Tap **Show system apps** to see them. They're listed read-only: *The TV needs these to work, so they're never changed.*

**Advanced.** Tap **Advanced**, then **Let me choose ads & tracking apps**, if you really want to decide about those yourself. They still start switched off. This is not recommended.

### Make it faster

Below the apps are four switches, all on to begin with:

- **Faster animations**: menus and screens open quicker (animations at half length).
- **Stop video apps running in the background**: frees memory. Only for the streaming and live TV apps you keep; music apps keep playing.
- **Clear app caches**: removes temporary files.
- **New home screen (FLauncher)**: a simple home screen without ads. On Fire TV this says *Not available on Fire TV yet*.

When you're happy, tap **Clean up TV**.

## 6. Review

A sheet called **Ready to clean up** sums up what will happen, for example:

> 6 apps will be switched off (plus the old home screen).
> The home screen will change to FLauncher.
> The TV will restart once at the end.
> Leave the remote alone for a couple of minutes.

There is one more switch here, **off** by default:

**Also delete switched-off apps I installed.** *Frees storage, but deletes their data. You can get them back from the Play Store.*

- **Off (recommended):** every app you switched off is only *switched off*. It stays on the TV with its data and Undo can bring it straight back.
- **On:** apps *you* installed that you switched off are deleted instead. Their data (logins, downloads, settings) is lost and Undo can't bring them back; only the Play Store can. Apps that came with the TV are always just switched off, never deleted.

Tap **Clean up TV** to start. If the sheet says *Your TV already matches your choices. Nothing to change.*, there is nothing to do.

## 7. Cleaning up

The **Cleaning up** screen shows a progress bar and a checklist, for example:

- Save a restore point
- Switch off apps
- Stop video apps running in the background
- Make menus and screens snappier
- Clear temporary files
- Set up the new home screen
- Restart and check everything still works

Leave the remote alone while this runs. You can lock your phone; the cleanup carries on and shows its progress in a notification.

If a step needs attention it says so in plain words, with **Retry** and **Skip** buttons. **Finish anyway** skips what didn't work and finishes the rest. If the TV can't be reached (it went to sleep, or the Wi-Fi dropped), the app tries again by itself a few times and then waits for you to tap **Retry**.

If someone changed the TV from another phone while this one was waiting, you'll see **Check the TV again**. It reads the TV again and takes you back to your choices.

### Stop

Tap **Stop** in the top corner, then **Stop cleaning** to confirm (or **Keep going**). The step that is running finishes first (*Stopping after the current step…*). Nothing after it runs, and the TV is not restarted. *Changes already made stay recorded and can be undone.*

## 8. When the TV restarts

If anything was switched off or the home screen changed, the TV restarts once at the end. That's normal.

- The TV screen goes dark and comes back after a minute or two.
- The app waits for it, then checks that everything you switched off is still off and, if you chose it, that **FLauncher** is now the home screen.
- It then waits a minute (*Letting the TV settle after the restart…*) and measures how much was freed.

If FLauncher doesn't come up as the home screen, the app puts your old home screen back by itself and tells you.

## 9. All clean

You'll see **All clean** (or **Cleaned up, with a few skips**) with:

- **Memory freed** and **Storage freed**. Tap **How these were measured** for the before and after numbers. A dash means the TV didn't answer in time.
- **Switched off**: how many apps.
- **Home screen**: FLauncher or Unchanged.

Only changes the TV confirmed are counted. If you deleted apps, they're listed with a note that the Play Store on the TV can bring them back.

Tap **Done**, or **Undo everything** if you changed your mind.

## 10. Your TVs, Clean up again and Undo

The app's home screen, **Your TVs**, lists every TV this phone has worked with, with **On Wi-Fi** or **Not found** next to each. Tap a TV to open its page:

- **What was changed** lists every change on record, in plain words, with which phone made it and when. Tap **Show details** for the technical version.
- **Clean up again** reads the TV again and starts from the choices you made last time. You can switch apps back on here too.
- **Undo everything** puts the TV back.

### Undo everything

Tap **Undo everything**, read the note, and tap **Undo everything** again (or **Keep my changes**).

> This puts the TV back the way it was before this app changed it: apps switched back on, animations and the old home screen restored. Apps that were deleted can only come back from the Play Store.

Leave the remote alone. If the TV asks **"Allow USB debugging?"**, press OK. You'll see **Undo finished** and how many changes were put back.

- Anything that couldn't be put back is listed under **Couldn't put these back**. It stays on the list, so tapping **Undo everything** later tries again.
- Deleted apps are listed under **Deleted apps**. Tap **Open in Play Store on the TV**, then press **Install** on the TV with the remote.

Undo reverses changes made from **any** phone in your home, not just this one, because the list of changes is kept on the TV.

## 11. More than one phone

- Each phone needs to be allowed on the TV once ("Allow USB debugging?").
- Any phone can see what was changed and undo it, whoever made the change.
- Only one phone can clean or undo a TV at a time. If another is working on it, you'll see *Another phone (name) is working on this TV right now.* Wait for it to finish and try again. If that phone was switched off or lost Wi-Fi in the middle, its hold on the TV runs out by itself after 10 minutes.
- Each phone keeps its own list under **Your TVs**. On a new phone, use **Add a TV** to find the TV; its history is read from the TV.

## 12. If something was interrupted

If the app or phone was closed in the middle of a cleanup, you'll see *Work on (TV) was interrupted* on the home screen and *A cleanup from this phone was interrupted* on the TV's page. Everything it changed is recorded on the TV. Tap **Clean up again** to finish, or **Undo everything**.

## 13. About the app

Tap the **(i)** button at the top of **Your TVs** (or of **Find your TV**, when it is the first screen) to open **About**. It shows the app's version and has:

- **Source code on GitHub**
- **Report a problem**: opens GitHub Issues in your browser
- **Open-source licences**: the projects ExBloat TV is built on

More help: [Troubleshooting](troubleshooting.md) and the [FAQ](faq.md).
