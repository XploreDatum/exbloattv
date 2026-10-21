# FAQ

### Does it need root?

No. It uses the TV's built-in debugging service, the same one developers use. Nothing is rooted or flashed.

### Does it run on the TV?

No. The app runs on your Android phone and talks to the TV over Wi-Fi. The only thing it may install on the TV is FLauncher, if you leave **New home screen (FLauncher)** on.

### Is there an iPhone or web version?

No. iPhones and web browsers can't open the kind of network connection the TV's debugging service needs.

### Why isn't it on the Play Store?

It is shared as an APK on the [releases page](https://github.com/XploreDatum/exbloattv/releases/latest). See the [README](../README.md#download-and-install) for how to install it and check the download.

### Will it delete my apps?

Not unless you ask. Switching an app off keeps it on the TV with all its data, and **Undo everything** brings it back. Deleting only happens for apps *you* installed, and only if you turn on **Also delete switched-off apps I installed** on the Review sheet. Apps that came with the TV are never deleted.

### Will my remote, voice search or casting stop working?

The remote and keyboard services are on a never-touch list and aren't even shown. Voice search and casting are in **Features** and start switched **on**; each one says what stops working if you switch it off.

### Why are ads & tracking apps locked?

They show ads or track what you watch, and switching them off costs you nothing. If you really want to decide yourself, tap **Advanced**, then **Let me choose ads & tracking apps**. They still start switched off.

### An app I use is in "Other". Is that bad?

No. **Other** is for apps you installed that the app doesn't recognise yet. They stay on unless you switch them off. You can help by [adding them to the catalog](catalog.md).

### What does "Stop video apps running in the background" do?

It stops the streaming and live TV apps you keep from running when you're not using them, which frees memory. They still work normally when you open them. Music apps are never affected, so they keep playing.

### What's FLauncher?

[FLauncher](https://gitlab.com/flauncher/flauncher) is an open-source home screen for Android TV by Étienne Fesser (GPL-3.0-or-later): a simple grid of your apps, without ads or suggested content. ExBloat TV downloads version 0.18.0 from FLauncher's own release page and checks it against a fixed checksum before installing it. It is not made by this project.

### Can I keep my TV's own home screen?

Yes. Turn off **New home screen (FLauncher)** on **Keep what you watch**. Everything else still works. On Fire TV the home screen is always left as it is.

### Why does the TV restart?

To check the changes stick, including the new home screen. After the restart the app checks that every app you switched off is still off and that the home screen is right. It only restarts if something was switched off or the home screen changed.

### How long does it take?

About five minutes from opening the app, plus a couple of minutes for the restart and a one-minute wait afterwards before the app measures what was freed.

### Can I use my phone while it runs?

Yes. You can lock the phone or switch apps. The cleanup runs in the background with a notification. Leave the TV remote alone, though.

### How do I undo it?

Open the app, tap the TV under **Your TVs**, and tap **Undo everything**. See [Safety](safety.md#what-undo-restores) for exactly what comes back.

### Can someone else in my home undo what I did?

Yes. The list of changes is stored on the TV, so any phone with the app can see it and undo it after pressing OK on the TV's **"Allow USB debugging?"** once.

### What if two of us try at the same time?

Only one phone can work on a TV at a time. The second phone is told another phone is working on it. See [Troubleshooting](troubleshooting.md#another-phone-is-working-on-this-tv).

### What if the power goes out in the middle?

Every change is recorded on the TV before it is made, so nothing is lost track of. The next time the app connects it finishes or reverses any half-done home screen change, and **Undo everything** can reverse the rest. See [Safety](safety.md#interruptions-and-power-cuts).

### Should I leave debugging on afterwards?

The app needs it on to clean up again or undo. You can turn off **USB debugging** in **Developer options** whenever you like and turn it back on when you want to use the app again. The record of changes on the TV is kept either way. Only devices you approved on the TV ("Allow USB debugging?") can connect.

### What data does the app collect?

None. There are no analytics, no accounts and no crash reporting. It only talks to the TV on your home network and, for the new home screen, to GitLab to download FLauncher. Its files (including its connection key) are kept out of phone backups. See the [README](../README.md#privacy).

### My TV only has "Wireless debugging" with a pairing code.

That isn't supported yet. Look for **USB debugging** (or **Network debugging**) in **Developer options** instead.

### Does it work on Fire TV?

The setup steps include Fire TV and the cleanup runs, but the home screen is left as it is. It has not been tested on a real Fire TV yet. Please [tell us how it goes](https://github.com/XploreDatum/exbloattv/issues/new?template=tv_report.yml).

### Which version do I have, and where are the licences?

Tap the **(i)** button at the top of **Your TVs** to open **About**. It shows the version, links to the source code and to **Report a problem**, and lists the open-source projects the app uses under **Open-source licences**.

### Which TVs has it been tested on?

See [Supported TVs](../README.md#supported-tvs) in the README for an honest list.
