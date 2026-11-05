# sPDR for OsmAnd

An Android companion app for OsmAnd that estimates your position underground, where there is no
GPS. It counts steps (smartphone pedestrian dead reckoning) and snaps the walk onto the galleries
and roads of a map graph with a particle filter. You can correct the position by hand, and each
correction also improves the calibration for what follows. The estimate is pushed into OsmAnd as
OsmAnd's own location, so the usual position arrow moves over your IGC atlas raster, track
recording works, and so on.

Design follows [`underground_pdr_spec.md`](underground_pdr_spec.md) (phases 1a + 1c, phone only).

> **Safety.** This can be wrong by tens of meters or pick the wrong branch. Never rely on it alone:
> carry the paper atlas, use landmarks, and keep an independent way back.

## How it plugs into OsmAnd

OsmAnd doesn't load third-party code. This app talks to it through OsmAnd's public AIDL API
(`net.osmand.aidl.OsmandAidlServiceV2`, the `osmand-api/` module vendored from the OsmAnd repo).

| Feature | AIDL call |
|---|---|
| sPDR position = OsmAnd's location (blue arrow, bearing, accuracy circle); OsmAnd ignores its own GPS for 30 s after each push | `setLocation` |
| Layer **sPDR reset points**: junctions, dead ends, named landmarks, and a mark every 10 m along nearby galleries | `addMapLayer` / `updateMapLayer` / `removeMapPoint` |
| Tap one of those points in OsmAnd → **I'm here** (±3 m) / **I'm near here** (±10 m) | `addContextMenuButtons` + callback |
| Layer **sPDR hypotheses**: alternative branches (orange). Tap → **That's me** | same |
| Widget showing confidence % and ±m | `addMapWidget` / `updateMapWidget` |
| Graph shown as a GPX track (Graph… › Show graph in OsmAnd) | `importGpx` |

### One-time setup in OsmAnd (required)

Recent OsmAnd versions register a new connected app as **disabled**, and they put `setLocation`
in a permission group that is not granted by default. After the first start of sPDR:

1. OsmAnd › Menu › Settings (or Plugins) › **Connected apps** › enable **sPDR for OsmAnd**.
2. In its permissions, allow **Map** (layers, buttons, widget), **System** (`setLocation`) and
   **Tracks edit** (to show the graph as GPX).

If something is refused, the status line in sPDR says which group is missing. Also enable the
**sPDR** widget from OsmAnd's widget settings if it doesn't appear.

## Link with web apps (Mega Maps)

A web app can't listen for connections, so sPDR runs a small HTTP server on `127.0.0.1:47823` while
its process is alive (`core/link/LinkServer.kt`). The Mega Maps PWA polls it for the position and
whether tracking runs, and can send a position (`fixPosition`, snapped to the nearest gallery within
100 m), start and stop tracking, or send its enabled lines as `POST /v1/graph`. Those are kept as
one source, `mega-maps.geojson`, replaced at each send and merged with your other sources. Only the origins listed in `LinkHandler` get an answer.

Android lets only a visible app start a foreground service, so `POST /v1/start` usually answers
`open-app`: Mega Maps then opens sPDR through `intent://open#Intent;scheme=spdr;…`. Keep tracking on
(or sPDR in front) for the link to stay up: Android may freeze an idle background app.

## The graph ("roads available around")

The filter needs lines to snap to. A companion app can't read OsmAnd's `.obf` files, so sPDR keeps
its own offline copy, built from any combination of:

- **OSM via Overpass** (Graph… › *OSM around me* / *OSM around a lat, lon*): downloads ways within
  a radius (default 1500 m) while you still have network. *Underground only* keeps walkable ways
  that are tunnels or have `layer<0`, `level<0`, `location=underground`, plus corridors. *All
  walkable* is for testing above ground.
- **Your own drawings** (Graph… › *Import GeoJSON / GPX / KML*): lines become galleries. Named
  points (GPX waypoints, KML/GeoJSON points with a `name`) become named reset nodes on the nearest
  gallery within 20 m. For example, draw the galleries over the IGC atlas in QGIS, export
  GeoJSON, and import it together with `../catacombes-salles.gpx`.

Hand-drawn lines are welded automatically: vertices within 0.5 m merge, a dangling end within 3 m
of another line becomes a T junction, and crossing lines become an X junction. All inputs are kept
in the app's storage and rebuilt together, so imported galleries also connect to OSM ways.

The OSM data around Denfert-Rochereau has metro and station corridors but **no catacomb
galleries**. For the quarries you have to import your own lines.

## In the field

1. **Before going down:** load the graph, then **▶ Start**. Do the step length **Calibrate** once:
   walk a known distance (≥ 30 m, measured on the map) and enter it.
2. **Set the start position:** **GPS fix** at the entrance, or long-press the map in sPDR, or tap a
   point in OsmAnd and choose **I'm here**. At start the compass sets the initial heading if it
   seems usable. Otherwise the heading is learned from the first galleries walked.
3. **Mounting:** phone rigidly on the chest, screen facing out, same way every time — most
   accurate. If you need to carry it in your hand, pick **Hand-held, any orientation** in
   Settings: the walking direction is then read from the gait instead of from the phone's axes,
   so the phone can be turned, read or pocketed. Costs some accuracy. Each mounting keeps its own
   step-length calibration, so calibrate once per mounting you use. The default scale of 1.0 is
   an average adult, so it works before you calibrate — just less well.
4. **Corrections whenever you're sure where you are** (junction, named room, shaft):
   - in OsmAnd: tap the reset point → **I'm here** or **I'm near here**;
   - in sPDR: long-press the map → ±3 / ±10 / ±30 m;
   - **−5 m / +5 m**: you're behind or ahead along the gallery;
   - **U-turn**: the estimate goes the wrong way;
   - **Not here**: drop the current best branch in favor of the alternatives; or tap an orange
     hypothesis → **That's me**.

   A reset is a *measurement*, not a teleport. The particles whose step scale, heading offset and
   gyro bias explained the walk survive it, so each correction improves the following ones. Only
   confirm places you physically recognise, never where you meant to go.
5. **Volume up** in sPDR logs a MARK. **■ Stop** ends the session. The learned step scale becomes
   the prior for the next session, but only if at least one reset happened.

## What it does inside

- `core/pdr`: step detection on the low-passed accelerometer norm, the step length and heading
  front ends below, and relative barometric height.
- `core/pdr/StepDetector`: step length from **cadence as well as amplitude**. The spec's Weinberg
  model, `K·(a_max − a_min)^¼`, uses amplitude alone, which holds up poorly across walking speeds
  and badly when the phone swings in a hand, because then the amplitude says as much about the
  phone as about the stride. People also lengthen their step as they quicken it, and the step
  interval is already measured, so the two are blended, each normalised so a nominal step (1.9 Hz,
  3 m/s², 0.70 m) gives a nominal length:
  `L = scale · (w·L₀·f/f₀ + (1−w)·L₀·(amp/amp₀)^¼)`, `w = 0.5` (`frequencyWeight`). Blending also
  contains the failure modes: a missed step halves the apparent cadence but not the amplitude, a
  phone shifting in its pouch changes the amplitude but not the cadence, so either way only half
  the estimate moves (measured: a missed step costs 24% of the length instead of 50%). `scale` is
  the per-user, per-mounting number the known-distance walk fits — **1.0 means an average adult,
  so it is usable uncalibrated**, and the old `weinbergK` values are deliberately not carried
  over, since they scaled a different model. Recalibrate.
- `core/pdr/TiltCompass`: the phone's **absolute** heading from the accelerometer and the
  magnetometer, in any orientation. The accelerometer measures gravity, which gives roll and
  pitch; the magnetometer's projection on the horizontal plane gives magnetic north. (The
  accelerometer alone cannot: gravity is vertical, so it says nothing about rotation around the
  vertical.) `TYPE_GRAVITY` is used for the down direction when the device has it, since while
  walking the raw accelerometer sees body accelerations on top of gravity; otherwise a
  one-second low pass, which averages over a step. The declination at the map origin is taken out,
  because the map frame is true-north ENU. Every reading is gated on field strength (20–75 µT),
  on the field not being parallel to gravity, and on both being steady over a couple of
  seconds — underground, iron is the rule — and on the magnetometer not reporting UNRELIABLE.
- `core/pdr/WalkingPca`: the walking direction relative to the world, from the horizontal
  accelerations of the last ~2 steps. At each step the body swings fore and aft much harder than
  sideways, so the principal axis of the horizontal accelerations is the walking axis, whatever
  the phone is doing. This is what makes a hand-held phone usable. The axis is 180°-ambiguous;
  the phase relation with the vertical resolves it, since at heel strike the ground pushes the
  body up and backwards at the same instant, so forward and vertical are anti-correlated
  (Kourogi & Kurata; Khedr & El-Sheimy). Weak evidence falls back on continuity, and a reversal
  must show in two consecutive windows, so real U-turns are caught but single-step noise is not.
  Good to roughly 10–30° per step, hence feeding a filter rather than being used directly. If a
  recording shows the opposite phase relation, flip
  `WalkingPca.forwardAntiCorrelatesWithVertical` and check it against `pca_axis` / `pca_corr`.
- `core/pdr/HeadingTracker`: hands the filter two things. `psi`, the walking heading in the game
  rotation vector's frame (gyro + accelerometer, no magnetometer) — smooth, slow-drifting, and the
  only thing that still works surrounded by iron, but with an arbitrary azimuth origin; and
  `offset`, a weak absolute measurement of that origin from the tilt compass, which the filter
  treats as a measurement of its per-particle `θ`. On a device with no game rotation vector the
  compass frame is used for both and the offset is zero by construction. Android's own
  `TYPE_ROTATION_VECTOR` is still logged alongside for comparison, as the spec asks.

  The heading is averaged over a **stride**, not a step. Walking rocks the body side to side at
  the stride frequency, so a left step and a right step are yawed a few degrees in opposite
  directions; averaging a pair cancels that, a single step cannot. Which foot it was comes from
  the sign of the sway — the mean sideways acceleration over the step, which alternates because
  you sway towards whichever leg is carrying you — so a pair is only averaged when the two steps
  really are opposite feet, recent, and not separated by a missed step. Two steps running on the
  same foot mean one was probably dropped, which is logged. Tested on a ±8° sway: single steps are
  off by 5.1°, the stride average by under 1°. (Successive strides overlap, so consecutive
  measurements share a step and are not quite independent; removing a systematic few degrees is
  worth more than that costs. `σ_ψ` has not been retuned to match — that wants real recordings.)
- `core/filter/ParticleFilter`: about 1000 particles, each holding (edge, s, direction) plus
  its own step scale `k`, heading offset `θ` and gyro bias. At each step: move along the edge,
  choose branches at junctions by heading, weight by heading vs gallery direction (robust
  likelihood), with optional weak compass and barometer (barometer only when the graph has
  heights). Resampling, roughening, U-turns, dead ends, and automatic re-spreading with a widening
  radius when lost. The position is the best cluster, not an average across branches.
- `core/graph`: graph, spatial index, builder, and JSON in the spec's map format plus an `origin`.
- `core/io/GeoParsers`: GeoJSON, GPX, KML and Overpass JSON.
- Android: `SpdrService` (foreground service with wake lock: sensors keep running with the screen
  off), `Engine`, `OsmAndBridge`, `MainActivity` and `MapCanvasView` (graph, particle cloud,
  hypotheses).

All tunables are in `FilterConfig` (defaults from spec §9), plus the gating thresholds in
`TiltCompass` and the window and quality floors in `WalkingPca`. Particle count, compass,
barometer and mounting are also in Settings. The status line shows the compass state (field
strength and dip, or why it is not trusted) and, hand-held, whether the gait gave a direction.

### Direction faced

Two different things, reported separately, because conflating them was a bug:

- **Phone heading** — where the phone points, from the tilt compass, updated with every
  magnetometer sample. The heading of the top edge (device +y), or of the back of the phone
  (−z) when the top is near vertical. **This is the quantity an ordinary compass app shows, so
  this is the one to hold next to one.** Pale needle on the dial, and the dial's label.
- **Travel heading** — where the walker is going: the measured walking heading plus the offset
  `θ` the filter has learned from the galleries. It is read from the gait, so it can only change
  once per step, and it goes blank after 4 s without a step, because standing still you have no
  direction of travel and showing the last one you had is a lie. Thick blue needle.

The status line gives both as bearings, plus `θ`'s spread and how far the compass-only value sits
from the fused one — **the two disagreeing is the useful part**, it is the magnetic error the map
constraint is absorbing. The dial works with no graph loaded, so it is also just a compass. On the
map, a thin orange ray from the position is the measured travel direction before the map snaps it
to a gallery; the blue arrow is the gallery direction.

The screen is refreshed by a 5 Hz poll while the activity is in front, not only when a step
arrives — otherwise the compass froze exactly when you would use it, standing still and turning.
Nothing polls with the screen off; the service's own notification is throttled to 5 s.

### A walker cannot teleport

The filter's belief must be free to put weight anywhere — that is how it recovers from a wrong
branch — but the position *reported* has to stay somewhere the person could have walked to. So a
cluster further away than the distance walked since the last report (times `reachMargin`, plus
`reachFloor`) is only reported once it outweighs the reachable one by `1/jumpPenalty`. When it is
being held off, `Estimate.holding` is set and the UI says **RE-LOCALISING**.

Measured over 6 seeded runs while deliberately lost (720 reports): the 99th-percentile jump falls
from **47 m to 8 m** and jumps over 10 m from 14 to 8. The single worst jump barely improves
(97 → 85 m) and that is deliberate — once the old position has no weight left the filter has to
relocalise, and crawling the dot there at walking pace would be a worse lie than moving it. What
this buys is that jumps become rare and deliberate rather than routine. A confirmed fix
("I'm here") is the one jump that is always honoured.

### Multi-step alignment (off by default)

The displacement over several steps is far less noisy than over one and must line up with the
straight line actually covered on the graph. That sounds like a strong extra constraint, and
`useChord` implements it — non-overlapping 10 m windows, used only where the walk was straight,
plus a penalty for a particle that barely moved while we covered several metres.

It is **off by default because it was measured and it does not help**: a product of per-step
heading likelihoods along a path already *is* that constraint, and with independent per-step
errors it is a sufficient statistic for it, so an explicit chord term re-uses the same
measurements with a tighter sigma and over-tightens. Mean error 3.1 → 3.5 m and worst 5 → 10 m on
a dense grid; neutral on an irregular network and on a shallow Y at 8° and 20°; better only at a
Y of 12° (22/24 → 24/24 correct branch, 3.3 → 1.9 m). Kept because the stall penalty is genuinely
new information — path shape rather than direction — and because real heading errors are
correlated in ways the simulator does not model. Turn it on against a recording, not blind.

### Logs (for offline work, spec phase 1b)

Each session writes
`Android/data/org.spdr.companion/files/sessions/<date>/`: `session.json` (device, sensors,
calibration, wall-clock ↔ elapsedRealtime), `steps.csv`, `events.csv` (INIT, DECLINATION, RESET
with the error before the fix, NUDGE, UTURN, SELECT, MARK, …), `estimates.csv`, and optionally raw
sensor CSVs (Settings › Log raw sensors), and `gps.csv` (Settings › Log GPS).

Everything lands in `/sdcard/Android/data/org.spdr.companion/files/sessions/<date>/`, which most
file managers cannot reach on Android 11+; use `adb pull`. Writes are flushed every 3 s.

### Measuring the error against GPS

Settings › **Log GPS next to the prediction**, then do a validation walk above ground. Each fix is
written to `gps.csv` **beside the position predicted at that same instant** — `error_m` between
them, the confidence and spread at the time, and `distance_m` dead-reckoned so far, so error as a
share of distance walked (the number §11 is judged on) needs no interpolation afterwards. Fixes use
`elapsedRealtimeNanos`, the same clock as the sensor timestamps, so the file lines up with the
others. The live error also shows in the status line while you walk.

The fixes are **never fed back into the filter** — that would make the comparison circular. The
"GPS fix" button is the separate, deliberate way to use GPS as a position fix. Off by default:
underground there is no GPS and it only costs battery.

`steps.csv` carries, per step: amplitude, `length` and `raw_length` (before the per-user scale),
interval, `psi` (what the filter got, stride-averaged) and `step_psi` (the single step it came
from), `stride`, `foot`, `sway` and `missed` from the stride pairing, the compass `offset`,
`offset_platform` (the same from Android's fused rotation vector, for comparison), barometric
height, and the gait analysis — `pca_axis` (the ambiguous principal axis), `pca_elongation`,
`pca_corr` (against that axis) and `pca_samples`. Those last four are what to check the 180°
convention against on a walk whose direction you know; `psi` against `step_psi` shows what the
stride averaging bought.

## Build

```sh
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # JVM tests: filter on simulated walks, graph builder, parsers
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs the Android SDK (platform 34, build-tools 34) and JDK 17. `gradle.properties` points Gradle
at `/usr/lib/jvm/java-17-openjdk-amd64`, because the JDK 21 on this machine has no `jlink`.

Tests cover the following (simulated, so optimistic compared with real walking):
- the tilt compass in five phone orientations, from the accelerometer alone as well as from the
  gravity sensor, with declination, and rejecting an implausible, a vertical and a swinging field;
- the gait direction at five headings, the forward/backward resolution, standing still, a U-turn,
  and the whole heading front end from raw samples through to what the filter is handed;
- stride averaging cancelling a ±8° body sway (5.1° per step down to under 1°), and the two feet
  being told apart by the sway alternating;
- the step length model: cadence and amplitude each moving it the right way, the clamps, and
  neither feature alone being able to swing it more than half way;
- the reported position not teleporting while the filter re-localises, and a confirmed fix still
  being honoured;
- the multi-step chord constraint still working when switched on;
- a corridor with an unknown heading offset;
- turns on a grid;
- an irregular network with 25° heading noise, 8% missed steps and uncompensated gyro drift (mean
  error < 10 m, and the drift is learned as gyro bias);
- step scale learned from resets (1.15 recovered to ±0.035, and reset errors drop from about 11 m
  to under 1 m);
- manual corrections;
- determinism;
- T/X/landmark welding;
- every input format.

## Known limitations

- Not yet tested on a real phone or a real walk underground. Thresholds in the step detector and
  the noise levels will need tuning on recordings (that is what the session logs are for).
- Along a long straight gallery, the map can't correct along-track error. Only the step
  calibration and resets help (expect a few % of the distance).
- A confidently wrong "I'm here" can mislead it for a long time on grid-like networks. Fix it
  with another reset.
- Crawling, climbing, wading and swimming break step detection. Treat those sections as gaps
  between two resets.
- Galleries passing over each other: imported crossing lines get joined. Draw multi-level parts
  as separate lines that don't cross, or split them into different files and import only the
  level you're on.
- The OsmAnd point icons, the widget icon names and the context-menu layout depend on the OsmAnd
  version.

## License

`osmand-api/` is copied unmodified from OsmAnd (GPLv3, commit in `osmand-api/UPSTREAM_COMMIT`).
The app links it, so treat the whole app as GPLv3.
