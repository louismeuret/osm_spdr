# Underground PDR Navigator — Specification (Phase 1: phone only)

Goal: estimate a pedestrian's position underground (no GPS) over hundreds of meters to a few km, using **only an Android phone's sensors** in phase 1, constrained by a **known map of galleries** and corrected by **manual position resets** that also improve future predictions.

A foot-mounted IMU (ZUPT) will be added in phase 2 — the architecture must allow adding new motion sources without rewriting the filter.

---

## 1. Overall approach

| Building block | Method |
|---|---|
| Motion measurement | Step-based Pedestrian Dead Reckoning (PDR): step detection + step length (Weinberg) + heading change |
| Position representation | Position **on a graph** of galleries (edge id + distance along edge), not free 2D |
| Estimation | **Particle filter** on the graph, each particle also carrying sensor error parameters |
| Corrections | Map constraints, heading vs gallery direction, barometric level, manual resets ("I am at node X") |
| Learning | Resets re-weight particles → error parameters (step scale, heading offset, gyro bias) converge and persist |
| Offline refinement | Smoothing via particle ancestry (phase 1), factor graph with GTSAM (later) |

Development order: **log first, develop the filter offline in Python on real recordings, then port to the phone.**

---

## 2. Repository structure

```
/android/      Kotlin app: sensor logger + UI for resets/markers (phase 1a), on-device filter (phase 1c)
/python/
  pdr/
    io.py            load logs
    preprocess.py    resampling, filtering, step detection, step length, heading
    graph.py         map graph loading, geometry helpers
    particle_filter.py
    smoother.py      ancestry-based smoothing
    simulate.py      synthetic walks on a graph for tests
    viz.py           matplotlib plots: map, particles, estimate, resets
  scripts/
    replay.py        run the filter on a recorded session, output trajectory + plots
  tests/
/maps/         map graphs (JSON)
/data/         recorded sessions
```

---

## 3. Android logger app (phase 1a)

### Sensors to record (all at the fastest rate available)
- `TYPE_ACCELEROMETER`
- `TYPE_GYROSCOPE` and `TYPE_GYROSCOPE_UNCALIBRATED` (raw, so we can estimate bias ourselves)
- `TYPE_MAGNETIC_FIELD` (may be useless underground, still record it)
- `TYPE_PRESSURE`
- `TYPE_GAME_ROTATION_VECTOR` (orientation without magnetometer)
- `TYPE_ROTATION_VECTOR` (orientation with magnetometer, for comparison)
- `TYPE_STEP_DETECTOR` (to compare with our own detector)

### Implementation requirements
- `SENSOR_DELAY_FASTEST`; declare `HIGH_SAMPLING_RATE_SENSORS` permission (Android 12+, needed above 200 Hz).
- Log in a **foreground service** with a partial wake lock, so recording continues with the screen off.
- Timestamps: use `SensorEvent.timestamp` (elapsedRealtimeNanos base). Log a header with wall-clock ↔ elapsedRealtime correspondence.
- Output: one CSV per sensor (`timestamp_ns,x,y,z[,w]`), plus `events.csv`, plus `session.json` (device model, sensor names/vendors/ranges/resolutions, app version, map id, mounting notes).
- Buffered writes; flush at least every few seconds; never lose data if the app is killed.

### Events (`events.csv`: `timestamp_ns,type,payload`)
- `RESET` — payload: node id (user confirms they are physically at that node).
- `MARK` — generic marker (e.g. "start of flooded section", "crawling").
- `SYNC` — sync event (e.g. 3 stomps), for later fusion with the foot unit.
- `STATIC_START` / `STATIC_END` — user is standing still (for gyro bias calibration).

### UI constraints
- Phone will be in a waterproof pouch, possibly with gloves: **large buttons**, and map the **volume buttons** to MARK / quick actions.
- Reset screen: list of nearby nodes (from the current estimate when the filter runs on-device; otherwise full searchable list).
- Mounting: phone **rigidly fixed to the chest/harness**, screen facing out, same orientation every session. Not in a pocket, not in hand.

---

## 4. Map format (`/maps/*.json`)

Local metric frame (ENU, meters), origin at a chosen reference point.

```json
{
  "id": "network_01",
  "nodes": [
    {"id": "A", "x": 0.0, "y": 0.0, "z": 0.0, "name": "Entrance", "landmark": true}
  ],
  "edges": [
    {"id": "e1", "from": "A", "to": "B",
     "polyline": [[0,0,0],[12.5,3.0,-0.5],[30.0,3.2,-1.0]],
     "flags": ["flooded"]}
  ]
}
```

- Edges are bidirectional; geometry is a polyline (first point = `from` node, last = `to` node). Edge length is computed from the polyline.
- `graph.py` must provide: point and tangent direction at distance `s` along an edge, edge length, edges incident to a node with their outgoing direction, nearest edge/position to an (x, y).
- Optional: a tool to build the graph from a drawn map (GeoJSON LineStrings → nodes at endpoints/intersections).

---

## 5. Preprocessing (Python `preprocess.py`)

### 5.1 Common time base
Resample all sensors to a common time base (e.g. 100 Hz, linear interpolation; quaternion slerp for rotation vectors).

### 5.2 Step detection
On the accelerometer norm `|a|`:
1. Remove gravity (subtract 9.81 or a slow moving average), low-pass filter (Butterworth, ~3 Hz).
2. Peak detection with: adaptive threshold (fraction of recent peak amplitude), minimum interval between steps 0.3 s, maximum 2.0 s.
3. Each step: time, `a_max`, `a_min` within the step window.
4. Compare against `TYPE_STEP_DETECTOR`; log disagreements (useful to evaluate difficult terrain).

### 5.3 Step length (Weinberg)
`L = K · (a_max − a_min)^(1/4)`
`K` is a per-user initial calibration (walk a known distance), and the filter refines it via a per-particle scale factor `k` (true step = `k · L`).

### 5.4 Heading
- Yaw from `TYPE_GAME_ROTATION_VECTOR` (relative heading, no magnetometer).
- Also compute our own yaw by integrating gyroscope (uncalibrated) around the gravity axis, so the filter can model gyro bias explicitly. Use one or the other via config.
- Per step: heading `ψ_meas` (mean yaw over the step).
- Static periods (no steps, low gyro variance for > 2 s): estimate gyro bias (zero angular rate update), and feed it to the filter.

### 5.5 Barometric height
`Δz ≈ −Δp / (ρ_air · g)` (≈ 8.3 m/hPa near sea level), low-pass filtered (~0.2 Hz). Only relative changes are used; an unknown offset is part of the filter state or reset at each RESET.

The phone gives no access to IMU temperature; temperature compensation is deferred to phase 2 (foot unit).

---

## 6. Particle filter on the graph (`particle_filter.py`)

### 6.1 Particle state
| Variable | Meaning | Initial distribution |
|---|---|---|
| `edge` | current edge id | from start node / reset |
| `s` | distance along edge from `from` node (m) | idem |
| `dir` | +1 (towards `to`) or −1 | both, equally weighted |
| `k` | step length scale factor | N(1.0, 0.1²) |
| `θ` | heading offset between phone yaw and map frame (rad) | from initial alignment, or uniform if unknown |
| `b_g` | residual gyro bias around vertical (rad/s) | N(0, (0.2°/s)²) |
| `z0` | barometric height offset (m) | set at start/reset |
| `w` | weight | 1/N |

N = 500–2000 (configurable). Implement vectorized with NumPy.

### 6.2 Prediction (at each detected step)
1. `θ ← θ − b_g · Δt + N(0, σ_θ²)` (heading drift).
2. `b_g ← b_g + N(0, σ_bg²)`, `k ← k + N(0, σ_k²)` (slow random walks).
3. `ds = k · L_meas + N(0, (σ_L · L_meas)²)`.
4. Move the particle `ds` along its edge in direction `dir`.
5. When it passes a node: choose the next edge among incident edges (excluding immediate U-turn, unless dead end). Probability ∝ `exp(−Δα² / 2σ_branch²)`, where Δα is the angle between the particle's heading (`ψ_meas + θ`) and the outgoing direction of each candidate edge. Sample the branch. Carry the remaining distance onto the new edge.
6. Dead end: particle reverses direction (`dir` flips), or its weight is strongly reduced (configurable).

### 6.3 Weight updates
- **Heading vs gallery**: `Δ = wrap(ψ_meas + θ − tangent(edge, s, dir))`, `w ← w · N(Δ; 0, σ_ψ²)` with σ_ψ ≈ 15–25° (body sway). Use a robust likelihood (Gaussian + uniform floor) so a single bad heading doesn't kill everything.
- **U-turns**: if the heading matches the reverse direction much better, allow flipping `dir` (with a small prior probability) instead of killing the particle.
- **Barometer**: `w ← w · N(z_baro + z0 − z(edge, s); 0, σ_z²)`, σ_z ≈ 1–2 m. Can be disabled.
- **Normalize**; if all weights are ~0 → recovery (6.6).

### 6.4 Resampling
Systematic resampling when `N_eff = 1/Σw² < N/2`. After resampling, add small roughening noise to `k`, `θ`, `b_g` to avoid collapse of the parameter diversity.

### 6.5 Manual reset (RESET event) — the learning mechanism
The reset is a **measurement**, not a teleport:
1. For each particle, compute the distance along the graph (or Euclidean, simpler) between its position and the reset node.
2. `w ← w · N(d; 0, σ_reset²)` with σ_reset ≈ 2–5 m (user uncertainty about the exact spot).
3. Resample. The surviving particles are those whose `k`, `θ`, `b_g` explained the trajectory → **these parameters are inherited by all following predictions** (this is the "learning").
4. Snap surviving particles to the node: same node, spread over incident edges by heading compatibility, keep their parameters.
5. Reset `z0` so the barometric height matches the node's `z`.
6. Log the error before the reset (estimated position vs node) → main accuracy metric.

**Only use resets at physically confirmed positions**, never at intended destinations.

### 6.6 Recovery (particle depletion)
If total weight collapses or the estimate is clearly lost: re-spread particles over the graph within a radius R of the last confident estimate (growing with distance walked since then), keeping the **parameter distribution** (`k`, `θ`, `b_g`) of the last good particle set, so learning is not lost.

### 6.7 Output
- Estimate = weighted cluster with the highest total weight (group particles by edge; position = weighted mean of `s` in the best cluster). Avoid a plain weighted mean across distinct branches.
- Confidence = share of weight in the best cluster + spread of `s`.
- Also output current means/stds of `k`, `θ`, `b_g`.

### 6.8 Persistence across sessions
Save the final distribution of `k` (and the user's `K`) at the end of a session, and use it as the prior for the next session.

---

## 7. Offline smoothing (`smoother.py`)

- Store each particle's ancestor index at each resampling step. At the end (or at each reset), take the highest-weight final particles and trace their ancestry back → smoothed trajectory consistent with all resets.
- Later (optional): factor graph with GTSAM — one pose per step, factors for step displacement, heading, map (pose on edge), resets as priors, with `k`, `θ`, `b_g` as shared variables. Optimize the whole session to get the best trajectory and the best calibration.

---

## 8. Simulation and tests (`simulate.py`, `/tests`)

- Simulator: generate a random walk on a map graph with true `k`, `θ`, `b_g`, realistic step length variation, heading noise (body sway ±10–15°), occasional missed/extra steps, pauses, U-turns. Output data in the same format as the real logger (steps + heading + pressure), plus ground truth.
- Tests:
  - filter stays on the correct edge on a simple corridor;
  - correct branch chosen at T and X junctions;
  - after 2–3 resets, estimated `k` converges to the true value (±3%);
  - recovery works after an injected wrong branch;
  - deterministic with fixed random seed.
- Metrics script: error at each reset (before applying it), % of time on the correct edge, final parameter error.

---

## 9. Default parameters (config file, YAML)

| Parameter | Default |
|---|---|
| N particles | 1000 |
| σ_L (relative step length noise) | 0.10 |
| σ_k (random walk per step) | 0.002 |
| σ_θ (heading random walk per step) | 0.5° |
| σ_bg (gyro bias random walk per step) | 0.005°/s |
| σ_ψ (heading vs gallery) | 20° |
| σ_branch | 30° |
| σ_z (barometer) | 1.5 m |
| σ_reset | 3 m |
| Resampling threshold | N_eff < N/2 |

All parameters must be tunable without code changes.

---

## 10. Phases

1. **1a — Logger app** (Android, Kotlin). Validate sampling rates and data integrity on the target phone.
2. **1b — Python offline**: preprocessing, simulator, particle filter, smoother, plots. Test first above ground on a known route (e.g. a building or park with a drawn graph and marked reset points), then underground.
3. **1c — Port to Android**: real-time filter (Kotlin), live display of the position on the map, reset UI with nearby node suggestions.
4. **2 — Foot unit**: waterproof foot-mounted IMU (ICM-42688-P or BMI088, ESP32-S3, microSD, LiPo, resin potting, reed switch) running ZUPT/ZARU with an error-state EKF; its per-step displacement becomes a second motion source in the same particle filter. Add IMU-temperature bias compensation and an MS5837 depth sensor. Time sync via SYNC events.

---

## 11. Known limitations and safety

- Phone-only PDR: expect roughly 3–10% of distance traveled before map/reset corrections; worse when crouching, crawling, climbing, or wading. Swimming produces no steps: treat flooded sections as gaps between two resets.
- Heading is the weak point; the map constraint and resets compensate most of it.
- This system can be wrong by tens of meters or pick the wrong branch. **It must never be the only means of orientation**: always carry a map, use landmarks, and have an independent way back.
