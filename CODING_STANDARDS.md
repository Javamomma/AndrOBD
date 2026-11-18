# Review rules (fold fork)

Apply these to every diff. They are judgement calls that tests can't make. The mechanical rules
are tests that run in `./gradlew build`:
- `FoldSafetyTest`: every activity survives fold/unfold, and MainActivity is rebuilt only
  through `recreateWhenSafe()`.
- `EcuDataItemTest`: one event per reading.

How the fork works is in [FOLD.md](FOLD.md).

1. **The link outlives the screen.** `MainActivity.onDestroy()` stops the adapter link and can
   switch Bluetooth off. Any new path that finishes or rebuilds MainActivity while the mode is
   ONLINE or DEMO waits for OFFLINE (`recreateWhenSafe()`, or the deferred recreate in
   `setMode`). That covers `finish()`, a `CLEAR_TASK` launch, a theme or locale switch, and a
   config change missing from `configChanges`.
2. **Callbacks run under the PV lock.** `ProcessVar` methods are `synchronized`, and PV change
   listeners run on the comm thread inside them. Code reached from a listener reads what it
   needs from the PV first, then takes its own lock. Holding your own lock while calling into a
   `ProcessVar` deadlocks against the comm thread; the 2026-11-16 review found one in
   `LiveTelemetry.register`.
3. **One event per reading.** `EcuDataItem` fires `PV_MODIFIED` on every reading, including
   repeats of the same value. Listeners stay cheap per event, because charts, the recorder and
   plugins such as MQTT all receive every reading. Graphs and recording keep the repeats, since
   steady values (parked at 0 km/h, warm coolant) are real data.
4. **One link, one session.** A recorded session holds samples from a single link. Changes to
   `LiveTelemetry.setLink`/`setLiveService` or `SessionRecorder` keep the session ending on a
   link or mode change, on leaving live data, and after the 60 s gap. Database writes stay on
   the recorder's worker thread.
5. **The main thread stays free.** Compose code reads ring-buffer snapshots
   (`LiveTelemetry.series`) and runs `SessionRecorder` queries and exports inside
   `withContext(Dispatchers.IO)`.
