---
name: testing-aquarium
description: End-to-end test the Atlantis 3D aquarium (static Three.js app). Use when verifying any change to fish, water, decor, HUD buttons, or click-to-feed.
---

# Testing Atlantis 3D Aquarium

The app is a single static page: `index.html` + `styles.css` + `src/*.js`. **No build step, no npm install** — Three.js is loaded via an import map from unpkg.com. Just serve the repo root over HTTP and open it in Chrome.

## Run it locally

```bash
cd /path/to/aquarium-repo
python3 -m http.server 8765
```

Then open http://localhost:8765/ in Chrome. The page is also deployed to https://aquarium-repo-hspumnic.devinapps.com — re-deploy with `deploy(command="frontend", dir="/path/to/aquarium-repo")`.

## What the HUD does (`index.html` + `src/ui.js` + `src/main.js`)

- **Night/Day** — toggles `mode` between `day` and `night` palettes (`src/main.js:91-95`). Switches fog/bg/sun/hemi/god-ray opacity/water shallow+deep/bloom strength all at once. The button **label flips to the next action** (so in day mode it reads "Night"; in night mode it reads "Day"). Verify both the stats panel `Mode` field AND the visible scene tint.
- **Auto-rotate** — toggles `controls.autoRotate` (`src/main.js:96-99`). Wait ≥3 s after clicking and verify camera azimuth drifts on its own.
- **Feed** — drops 10 pellets in front of the camera (`src/main.js:100-109`). Useful as a fallback if click-to-feed is hard to reach in your view.
- **More fish** — calls `flock.addFish(6)` (`src/main.js:110-112`). The stats `Fish` counter MUST increase by exactly 6 per click. This is a strong adversarial assertion — a broken implementation could update the label without spawning, or spawn fewer/more.
- **Bloom** — toggles `bloomEnabled` (`src/main.js:113-116`). Effect is intentionally subtle in day mode (strength=0.45). Most visible on the gold coins in the chest, the caustics, or the god rays.

## Click-to-feed (`src/main.js:122-152`)

- Click handler discriminates click vs drag by **distance < 5 px AND time < 350 ms** between pointerdown and pointerup.
- Raycast against an invisible plane at `y = bounds.maxY` (the water surface).
- Only drops food if the hit is inside the tank bounds.
- Each click spawns 8 pellets clustered around the hit point.

**Pitfalls:**
- If you click while OrbitControls is mid-drag (damping still settling), it may swallow the click. Wait ~0.5 s after any orbit before clicking to feed.
- The `computer` tool's `scroll` action sends inverted deltas in some Chrome builds — "scroll_direction: down" may zoom **in** instead of out. If you over-zoom, just reload the page.
- The `left_click` action is fine for clean clicks (sub-5px movement, sub-350ms duration) — it will register as a click, not a drag.

## Tank bounds and fish state

- Tank interior: `x∈[-22,22], y∈[0.4,13.5], z∈[-13,13]` (`src/environment.js`).
- Default fish count: 28 (`src/main.js:28`).
- 5 species: clownfish, blue tang, yellow tang, angelfish, neon tetra (`src/fish.js`).
- Boids: separation 1.6×, alignment 0.85×, cohesion 0.7×, wall-avoid 2.0×, food-seek 2.2× (`src/flock.js`).

## Known environment caveat — low FPS on the Devin VM

The VM has no GPU, so Chrome falls back to **SwiftShader** (software WebGL). FPS reads as 1–2 instead of 60. This is **not an app bug** — on any real machine the same code runs at 60 FPS. For testing, assert that FPS is **numeric and updating** (not stuck at `–` or `0`) rather than a specific number.

A quick way to confirm SwiftShader is being used: open DevTools → Console and run `navigator.gpu` (undefined means software fallback).

## Adversarial test recipe (in order)

1. **Render loop alive** — wait for loader to clear; verify FPS is numeric and fish move between two screenshots taken ~1 s apart.
2. **Drag-orbit** — drag from one canvas point to a point 300+ px away; verify perspective clearly shifts.
3. **Day→Night** — click Night; verify both `Mode` flips AND background visibly darkens. (A broken impl could update one without the other.)
4. **More fish ×1** — verify counter increases by **exactly 6**.
5. **Click-to-feed** — clean click on the canvas at an in-tank screen point; verify pellets appear AND fish converge within ~2 s. This is the killer feature.
6. **Bloom toggle** — verify render keeps producing frames (no crash).
7. **Auto-rotate** — click and wait 3+ s without input; verify camera drifts.

Keep the recording continuous. Use `annotate_recording` with `test_start` + `assertion` (pass/fail) markers for each step.

## Devin Secrets Needed

None. The app is fully client-side and the preview is publicly hosted.
