import * as THREE from "three";

/**
 * The "world" surrounding the tank: distant gradient sky cube, soft
 * hemisphere/key lighting, sandy floor, scattered rocks, and the glass
 * tank walls (visible from the outside, so the viewer feels they're
 * looking *into* an aquarium rather than swimming inside open ocean).
 *
 * Returns the bounds of the playable water volume so other systems
 * (fish flock, bubbles, food) can clamp themselves to it.
 */
export function buildEnvironment(scene) {
  // ---------- Bounds (interior of the tank) ----------
  const bounds = {
    minX: -22,
    maxX: 22,
    minY: 0.4,
    maxY: 13.5,
    minZ: -13,
    maxZ: 13,
  };

  // (Backdrop is set via scene.background in main.js — no extra geometry
  // needed, keeps the render simple and avoids order-of-draw glitches.)

  // ---------- Lighting ----------
  const hemi = new THREE.HemisphereLight(0xa6e1ff, 0x0a1a26, 0.55);
  scene.add(hemi);

  const sun = new THREE.DirectionalLight(0xfff1cc, 1.35);
  sun.position.set(20, 50, 18);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  sun.shadow.camera.near = 5;
  sun.shadow.camera.far = 120;
  sun.shadow.camera.left = -30;
  sun.shadow.camera.right = 30;
  sun.shadow.camera.top = 30;
  sun.shadow.camera.bottom = -30;
  sun.shadow.bias = -0.0005;
  scene.add(sun);

  // A cool fill from below the tank to suggest soft ambient water bounce.
  const fill = new THREE.DirectionalLight(0x2a7aa8, 0.35);
  fill.position.set(-15, -8, -10);
  scene.add(fill);

  // ---------- Sandy floor inside the tank ----------
  scene.add(buildSandFloor(bounds));

  // ---------- Rocks ----------
  buildRocks(scene, bounds);

  // ---------- Glass tank walls (seen from outside) ----------
  scene.add(buildTank(bounds));

  return { bounds, sun, hemi, fill };
}

function buildSandFloor(bounds) {
  const w = bounds.maxX - bounds.minX + 6;
  const d = bounds.maxZ - bounds.minZ + 6;
  const geo = new THREE.PlaneGeometry(w, d, 96, 64);
  // Displacement: a few overlapping sine waves to mimic dunes/ripples.
  const pos = geo.attributes.position;
  for (let i = 0; i < pos.count; i++) {
    const x = pos.getX(i);
    const y = pos.getY(i);
    const h =
      Math.sin(x * 0.18) * 0.18 +
      Math.cos(y * 0.22 + 1.1) * 0.22 +
      Math.sin((x + y) * 0.07) * 0.32;
    pos.setZ(i, h);
  }
  geo.computeVertexNormals();

  const mat = new THREE.MeshStandardMaterial({
    color: 0xd9b27a,
    roughness: 0.95,
    metalness: 0,
  });
  const floor = new THREE.Mesh(geo, mat);
  floor.rotation.x = -Math.PI / 2;
  floor.position.y = 0;
  floor.receiveShadow = true;
  floor.name = "sandFloor";
  return floor;
}

function buildRocks(scene, bounds) {
  const rockMat = new THREE.MeshStandardMaterial({
    color: 0x6b6660,
    roughness: 0.95,
    metalness: 0.02,
    flatShading: true,
  });
  const darkMat = new THREE.MeshStandardMaterial({
    color: 0x3a3a36,
    roughness: 1.0,
    metalness: 0,
    flatShading: true,
  });

  const positions = [
    { x: -16, z: -7, s: 3.2, mat: rockMat },
    { x: -13, z: -9, s: 1.6, mat: darkMat },
    { x: 15, z: 8, s: 3.6, mat: rockMat },
    { x: 18, z: 5, s: 2.0, mat: darkMat },
    { x: -2, z: 10, s: 2.6, mat: rockMat },
    { x: 4, z: -10, s: 2.2, mat: rockMat },
    { x: -8, z: 6, s: 1.3, mat: darkMat },
    { x: 10, z: -3, s: 1.5, mat: darkMat },
  ];

  for (const p of positions) {
    const geo = new THREE.IcosahedronGeometry(p.s, 1);
    // Jitter vertices for organic shape.
    const pos = geo.attributes.position;
    for (let i = 0; i < pos.count; i++) {
      const v = new THREE.Vector3(pos.getX(i), pos.getY(i), pos.getZ(i));
      const j = 0.18 * p.s;
      v.x += (Math.random() - 0.5) * j;
      v.y += (Math.random() - 0.5) * j;
      v.z += (Math.random() - 0.5) * j;
      pos.setXYZ(i, v.x, v.y, v.z);
    }
    geo.computeVertexNormals();
    const m = new THREE.Mesh(geo, p.mat);
    m.position.set(p.x, p.s * 0.55, p.z);
    m.rotation.y = Math.random() * Math.PI;
    m.castShadow = true;
    m.receiveShadow = true;
    scene.add(m);
  }
}

function buildTank(bounds) {
  const group = new THREE.Group();
  group.name = "tank";

  const cx = (bounds.minX + bounds.maxX) / 2;
  const cz = (bounds.minZ + bounds.maxZ) / 2;
  const w = bounds.maxX - bounds.minX + 0.6;
  const h = bounds.maxY + 0.4;
  const d = bounds.maxZ - bounds.minZ + 0.6;

  // Lightweight "glass": low-opacity tinted surface. Avoids the cost of
  // MeshPhysicalMaterial transmission while still selling the idea of
  // a glass tank when paired with the silicone-black rim and stand.
  const glassMat = new THREE.MeshStandardMaterial({
    color: 0xbfeaff,
    transparent: true,
    opacity: 0.08,
    roughness: 0.05,
    metalness: 0,
    side: THREE.DoubleSide,
    depthWrite: false,
  });

  // Four side panes (no top — the water surface is the "top").
  const panes = [
    { w: w, h, d: 0.12, x: cx, y: h / 2, z: bounds.maxZ + 0.3 },
    { w: w, h, d: 0.12, x: cx, y: h / 2, z: bounds.minZ - 0.3 },
    { w: 0.12, h, d, x: bounds.maxX + 0.3, y: h / 2, z: cz },
    { w: 0.12, h, d, x: bounds.minX - 0.3, y: h / 2, z: cz },
  ];
  for (const p of panes) {
    const geo = new THREE.BoxGeometry(p.w, p.h, p.d);
    const m = new THREE.Mesh(geo, glassMat);
    m.position.set(p.x, p.y, p.z);
    group.add(m);
  }

  // Dark wooden stand under the tank.
  const standMat = new THREE.MeshStandardMaterial({
    color: 0x2a1b10,
    roughness: 0.7,
    metalness: 0.05,
  });
  const standH = 1.0;
  const stand = new THREE.Mesh(
    new THREE.BoxGeometry(w + 1.2, standH, d + 1.2),
    standMat
  );
  stand.position.set(cx, -standH / 2 - 0.05, cz);
  stand.castShadow = false;
  stand.receiveShadow = true;
  group.add(stand);

  // Black silicone rim around top edge for that authentic aquarium look.
  const rimMat = new THREE.MeshStandardMaterial({
    color: 0x111111,
    roughness: 0.8,
  });
  const rimT = 0.18;
  const rims = [
    { w: w + 0.2, h: rimT, d: 0.25, x: cx, y: h, z: bounds.maxZ + 0.3 },
    { w: w + 0.2, h: rimT, d: 0.25, x: cx, y: h, z: bounds.minZ - 0.3 },
    { w: 0.25, h: rimT, d: d + 0.2, x: bounds.maxX + 0.3, y: h, z: cz },
    { w: 0.25, h: rimT, d: d + 0.2, x: bounds.minX - 0.3, y: h, z: cz },
  ];
  for (const r of rims) {
    const m = new THREE.Mesh(new THREE.BoxGeometry(r.w, r.h, r.d), rimMat);
    m.position.set(r.x, r.y, r.z);
    group.add(m);
  }

  return group;
}
