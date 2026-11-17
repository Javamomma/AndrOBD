# AndrOBD Fold (personal fork)

Fork of [fr3ts0n/AndrOBD](https://github.com/fr3ts0n/AndrOBD) tuned for the Galaxy Z Fold.
Everything else in AndrOBD works as upstream; this fork adds:

- **Fold/unfold keeps the connection.** `MainActivity` handles size changes itself
  (`configChanges` incl. `smallestScreenSize|screenLayout|density`). Upstream, the activity was
  rebuilt on fold/unfold, and `onDestroy()` stops the adapter link (and can switch Bluetooth off).
- **Day/night switches wait for the link to drop.** `uiMode` is handled too; a theme change
  (system schedule at sunset, or the app toggle) re-themes the classic screen only once no
  adapter / demo link is up, because re-theming rebuilds the screen.
- **Split view** (`fold/FoldDashboardActivity`, Jetpack Compose): on the inner screen (≥ 600 dp)
  the sensor list sits on the left, up to 4 live graphs on the right (30 s / 2 m / 10 m / all,
  min/avg/max, press-and-hold to read a value). On the cover screen it is a list; tap a sensor
  for its graph. Opens by itself when live data runs on the big screen or when you unfold, or
  from the menu (**Split view**).
- **Saved on the phone.** Live data (OBD service 1) is recorded automatically to SQLite in app
  storage (`fold_sessions.db`). A session ends when the link drops, you leave live data, or no
  data arrives for 60 s. **Saved sessions** (menu, or the clock icon in the split view) lists
  them; open one to replay its graphs, export it as CSV (share icon), or delete it.
  Status flags / code lists (text values) are not graphed or recorded.
- **Library change:** `EcuDataItem` now fires `PV_MODIFIED` on every reading, not only when the
  value changes, so steady values (parked at 0 km/h, warm coolant) are graphed and recorded.
  Side effect: AndrOBD's own charts and plugins (MQTT) also get every reading.

## Build & install

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :androbd:assembleFold          # optimised, ~4 MB: androbd/build/outputs/apk/fold/
~/cos/bin/fold-adb install androbd/build/outputs/apk/fold/androbd-fold.apk
```

The `fold` and `debug` builds install as `com.fr3ts0n.ecu.gui.androbd.fold` ("AndrOBD Fold"),
next to the official app, both signed with this Mac's debug key (updates keep the saved data;
uninstalling deletes it).

Test shortcuts (fold/debug builds only):

```sh
fold-adb shell am start -n com.fr3ts0n.ecu.gui.androbd.fold/com.fr3ts0n.ecu.gui.androbd.MainActivity --ez fold_demo true      # demo data in the split view
fold-adb shell am start -n com.fr3ts0n.ecu.gui.androbd.fold/com.fr3ts0n.ecu.gui.androbd.MainActivity --es fold_open sessions  # or replay_latest
```

Demo gotcha: select live data only after the demo ECU handshake has run; selecting it first
leaves the PID list empty (upstream demo behaviour).
