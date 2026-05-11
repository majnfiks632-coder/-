import * as THREE from "three";
import { createSceneCore } from "./scene.js";
import { buildEnvironment } from "./environment.js";
import { buildWaterSurface } from "./water.js";
import { buildCaustics } from "./caustics.js";
import { buildDecorations } from "./decorations.js";
import { Flock } from "./flock.js";
import { buildBubbles } from "./bubbles.js";
import { FoodManager } from "./food.js";
import { buildGodRays } from "./godrays.js";
import { bindUI } from "./ui.js";

const canvas = document.getElementById("scene");
const core = createSceneCore(canvas);
const { scene, camera, controls, composer, bloomPass, renderer } = core;

// ---------- World ----------
const env = buildEnvironment(scene);
const water = buildWaterSurface(env.bounds);
scene.add(water.mesh);
const caustics = buildCaustics(env.bounds);
scene.add(caustics.mesh);
const decor = buildDecorations(scene, env.bounds);
const godRays = buildGodRays(env.bounds);
scene.add(godRays.group);

// ---------- Inhabitants ----------
const flock = new Flock(scene, env.bounds, { count: 28 });
const bubbles = buildBubbles(env.bounds);
scene.add(bubbles.points);
const foodMgr = new FoodManager(scene, env.bounds);

// ---------- Day / Night palette ----------
const PALETTES = {
  day: {
    fog: new THREE.Color(0x06314b),
    bg: new THREE.Color(0x06314b),
    sun: 1.35,
    sunColor: new THREE.Color(0xfff1cc),
    hemi: 0.55,
    rays: 0.26,
    raysColor: new THREE.Color(0xfff1c4),
    causticsIntensity: 0.6,
    fogDensity: 0.012,
    waterShallow: 0x66c7ff,
    waterDeep: 0x0b3756,
    ambient: new THREE.Color(0x6ab4d8),
    light: new THREE.Color(0xfff7e6),
    bloom: 0.45,
  },
  night: {
    fog: new THREE.Color(0x010812),
    bg: new THREE.Color(0x010812),
    sun: 0.18,
    sunColor: new THREE.Color(0x9bb8ff),
    hemi: 0.18,
    rays: 0.1,
    raysColor: new THREE.Color(0x88b5ff),
    causticsIntensity: 0.22,
    fogDensity: 0.022,
    waterShallow: 0x143352,
    waterDeep: 0x020a14,
    ambient: new THREE.Color(0x2a4868),
    light: new THREE.Color(0x88a8d8),
    bloom: 0.85,
  },
};

let mode = "day";
function applyPalette(name, instant = false) {
  const p = PALETTES[name];
  // Direct apply (no tween needed for a tiny demo).
  scene.fog.color.copy(p.fog);
  scene.fog.density = p.fogDensity;
  scene.background.copy(p.bg);
  env.sun.intensity = p.sun;
  env.sun.color.copy(p.sunColor);
  env.hemi.intensity = p.hemi;
  godRays.uniforms.uOpacity.value = p.rays;
  godRays.uniforms.uColor.value.copy(p.raysColor);
  caustics.uniforms.uIntensity.value = p.causticsIntensity;
  water.setMood({ shallow: p.waterShallow, deep: p.waterDeep });
  flock.setLighting(p.ambient, p.light);
  bloomPass.strength = p.bloom;
  void instant;
}
applyPalette("day", true);

// ---------- UI ----------
const ui = bindUI({
  toggleDay() {
    mode = mode === "day" ? "night" : "day";
    applyPalette(mode);
    return mode === "night";
  },
  toggleAutoRotate() {
    controls.autoRotate = !controls.autoRotate;
    return controls.autoRotate;
  },
  feed() {
    // Drop food roughly in front of the camera so the user can see it.
    const dir = new THREE.Vector3();
    camera.getWorldDirection(dir);
    const center = camera.position
      .clone()
      .addScaledVector(dir, 14)
      .clampScalar(-20, 20);
    foodMgr.drop(center.x, center.z, 10);
  },
  addFish() {
    flock.addFish(6);
  },
  toggleBloom() {
    bloomEnabled = !bloomEnabled;
    return bloomEnabled;
  },
});
// Loader fades out as soon as the first frame renders.
let firstFrame = true;

// ---------- Click-to-feed on the canvas ----------
const raycaster = new THREE.Raycaster();
const ndc = new THREE.Vector2();
const surfacePlane = new THREE.Plane(new THREE.Vector3(0, 1, 0), -env.bounds.maxY);

canvas.addEventListener("pointerdown", (ev) => {
  // Only respond to clicks that aren't drags (track with timing/movement).
  canvas._pdX = ev.clientX;
  canvas._pdY = ev.clientY;
  canvas._pdT = performance.now();
});
canvas.addEventListener("pointerup", (ev) => {
  const dx = ev.clientX - (canvas._pdX ?? 0);
  const dy = ev.clientY - (canvas._pdY ?? 0);
  const dt = performance.now() - (canvas._pdT ?? 0);
  if (Math.hypot(dx, dy) > 5 || dt > 350) return;

  ndc.x = (ev.clientX / window.innerWidth) * 2 - 1;
  ndc.y = -(ev.clientY / window.innerHeight) * 2 + 1;
  raycaster.setFromCamera(ndc, camera);
  const hit = new THREE.Vector3();
  if (raycaster.ray.intersectPlane(surfacePlane, hit)) {
    if (
      hit.x > env.bounds.minX &&
      hit.x < env.bounds.maxX &&
      hit.z > env.bounds.minZ &&
      hit.z < env.bounds.maxZ
    ) {
      foodMgr.drop(hit.x, hit.z, 8);
    }
  }
});

// Wire foods into flock so fish know to seek them.
function refreshFlockFoods() {
  flock.setFoods(foodMgr.items);
}

// ---------- Animation loop ----------
let bloomEnabled = true;
const clock = new THREE.Clock();
function tick() {
  const dt = Math.min(clock.getDelta(), 0.05);
  const t = clock.getElapsedTime();

  controls.update();
  water.update(t);
  caustics.update(t);
  decor.update(t);
  godRays.update(t);
  refreshFlockFoods();
  flock.update(dt, t);
  bubbles.update(dt, t);
  foodMgr.update(dt, flock.fish);

  if (bloomEnabled) {
    composer.render();
  } else {
    renderer.render(scene, camera);
  }

  ui.tickStats(performance.now(), flock.count);

  if (firstFrame) {
    firstFrame = false;
    requestAnimationFrame(() => ui.hideLoader());
  }
  requestAnimationFrame(tick);
}
tick();
