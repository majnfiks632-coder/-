# Atlantis — 3D Aquarium

An interactive 3D aquarium that runs entirely in the browser, no build
step required. Pure HTML/CSS + a few ES modules talking to Three.js
from a CDN via an import map.

## Live preview

Open `index.html` in any modern browser, or serve the directory:

```sh
python3 -m http.server 8765
# then open http://localhost:8765/
```

## What's in the tank

- A glass tank, dark wooden stand and silicone-black rim — the
  classic aquarium silhouette.
- A sandy floor with dune displacement.
- A schooling flock of procedurally-generated fish across **five
  species** (clownfish, blue tang, yellow tang, angelfish, neon
  tetra). Each fish is a deformed tube with a vertex-shader wiggle
  driving the side-to-side swimming motion, plus dorsal / tail /
  pectoral fins.
- Reynolds-style **boids flocking** with separation, alignment,
  cohesion, wall avoidance, and food seeking.
- An animated **water surface** (custom GLSL shader) with stacked
  sine-wave displacement, fresnel-tinted depth, and foam crests.
- Animated **caustics** projected on the sand (a tiny Voronoi-ish
  shader).
- Volumetric **god rays** shining down through the surface.
- Swaying **seaweed**, coral clusters, scattered rocks, a treasure
  chest with gold coins, and starfish.
- Rising **bubbles** from multiple emitters (single Points object,
  GPU-driven).
- **Bloom** post-processing pass for highlights.

## Controls

- **Drag** to orbit, **scroll** to zoom.
- **Click** anywhere on the tank surface to drop food where you
  clicked — fish flock to it.
- HUD buttons: **Day / Night**, **Auto-rotate**, **Feed**, **More
  fish**, **Bloom** toggle.

## File layout

```
index.html             # entry point + import map + HUD overlay
styles.css             # HUD styling
src/
  main.js              # bootstraps everything
  scene.js             # renderer, camera, post-processing
  environment.js       # lights, sand floor, rocks, glass tank
  water.js             # water-surface shader
  caustics.js          # caustics-on-sand shader
  decorations.js       # seaweed, coral, chest, starfish
  fish.js              # procedural fish geometry + wiggle shader
  flock.js             # boids manager
  bubbles.js           # rising-bubble particle system
  food.js              # feed pellets that fish chase
  godrays.js           # volumetric light shafts
  ui.js                # HUD bindings + FPS tracker
```

Built with [Three.js](https://threejs.org/) (r160).
