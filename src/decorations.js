import * as THREE from "three";

/**
 * Static scenery inside the tank: seaweed (animated), coral clusters,
 * a treasure chest, and a starfish. Seaweed uses a vertex shader so
 * each blade sways independently at very low CPU cost.
 */
export function buildDecorations(scene, bounds) {
  const updaters = [];

  // ---------- Seaweed ----------
  const seaweedMat = makeSeaweedMaterial(0x2c8a4d);
  const seaweedMat2 = makeSeaweedMaterial(0x5fbf6a);
  const seaweedMat3 = makeSeaweedMaterial(0x356b3a);

  const seaweedSpots = [
    { x: -17, z: -6, n: 14, mat: seaweedMat },
    { x: -14, z: -10, n: 9, mat: seaweedMat2 },
    { x: 16, z: 9, n: 12, mat: seaweedMat },
    { x: 19, z: 4, n: 9, mat: seaweedMat3 },
    { x: -3, z: 11, n: 10, mat: seaweedMat2 },
    { x: 5, z: -11, n: 9, mat: seaweedMat },
    { x: 0, z: 2, n: 7, mat: seaweedMat3 },
    { x: -10, z: 3, n: 8, mat: seaweedMat2 },
  ];

  for (const s of seaweedSpots) {
    for (let i = 0; i < s.n; i++) {
      const blade = makeSeaweedBlade(s.mat);
      const dx = (Math.random() - 0.5) * 3.5;
      const dz = (Math.random() - 0.5) * 3.5;
      blade.position.set(s.x + dx, 0, s.z + dz);
      blade.rotation.y = Math.random() * Math.PI * 2;
      const h = THREE.MathUtils.randFloat(2.6, 6.4);
      blade.scale.set(
        THREE.MathUtils.randFloat(0.7, 1.2),
        h,
        THREE.MathUtils.randFloat(0.7, 1.2)
      );
      blade.userData.phase = Math.random() * Math.PI * 2;
      scene.add(blade);
    }
  }
  updaters.push((t) => {
    seaweedMat.uniforms.uTime.value = t;
    seaweedMat2.uniforms.uTime.value = t;
    seaweedMat3.uniforms.uTime.value = t;
  });

  // ---------- Coral clusters ----------
  buildCorals(scene);

  // ---------- Treasure chest ----------
  scene.add(buildChest(new THREE.Vector3(-2, 0.3, -8)));

  // ---------- Starfish ----------
  scene.add(buildStarfish(new THREE.Vector3(8, 0.05, -2)));
  scene.add(buildStarfish(new THREE.Vector3(-12, 0.05, 1), 0xff7a2e));

  return {
    update(t) {
      for (const u of updaters) u(t);
    },
  };
}

function makeSeaweedMaterial(color) {
  return new THREE.ShaderMaterial({
    uniforms: {
      uTime: { value: 0 },
      uColor: { value: new THREE.Color(color) },
    },
    side: THREE.DoubleSide,
    transparent: true,
    vertexShader: /* glsl */ `
      uniform float uTime;
      varying float vY;
      void main() {
        vec3 p = position;
        // Sway proportional to height — base anchored, top swings most.
        float sway = sin(uTime * 1.4 + p.y * 0.6 + p.x * 0.4) * 0.18;
        p.x += sway * p.y;
        p.z += cos(uTime * 1.1 + p.y * 0.5) * 0.06 * p.y;
        vY = p.y;
        gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0);
      }
    `,
    fragmentShader: /* glsl */ `
      uniform vec3 uColor;
      varying float vY;
      void main() {
        vec3 col = mix(uColor * 0.45, uColor, smoothstep(0.0, 1.0, vY));
        gl_FragColor = vec4(col, 0.95);
        #include <colorspace_fragment>
      }
    `,
  });
}

function makeSeaweedBlade(material) {
  // Slim ribbon: a tall thin plane.
  const geo = new THREE.PlaneGeometry(0.35, 1.0, 1, 16);
  geo.translate(0, 0.5, 0); // base at y=0
  return new THREE.Mesh(geo, material);
}

function buildCorals(scene) {
  const palette = [0xff5b8a, 0xffc83d, 0xff8a45, 0xd24bff, 0x6cdcff];
  const spots = [
    { x: -15, z: -3, count: 10 },
    { x: 13, z: -8, count: 8 },
    { x: 4, z: 9, count: 9 },
    { x: -8, z: 10, count: 6 },
    { x: 17, z: -2, count: 7 },
  ];
  for (const s of spots) {
    const group = new THREE.Group();
    for (let i = 0; i < s.count; i++) {
      const c = palette[i % palette.length];
      const mat = new THREE.MeshStandardMaterial({
        color: c,
        roughness: 0.6,
        metalness: 0.05,
        flatShading: true,
      });
      // Each "branch" is a tapered cylinder; assemble a few into a coral.
      const branch = new THREE.Mesh(
        new THREE.CylinderGeometry(0.06, 0.18, 1.0, 8, 4),
        mat
      );
      branch.position.set(
        (Math.random() - 0.5) * 1.6,
        0.5,
        (Math.random() - 0.5) * 1.6
      );
      const h = THREE.MathUtils.randFloat(0.6, 1.6);
      branch.scale.set(1, h, 1);
      branch.rotation.set(
        (Math.random() - 0.5) * 0.6,
        Math.random() * Math.PI,
        (Math.random() - 0.5) * 0.6
      );
      branch.castShadow = true;
      group.add(branch);

      if (Math.random() < 0.55) {
        const tip = new THREE.Mesh(new THREE.SphereGeometry(0.18, 8, 6), mat);
        tip.position.copy(branch.position);
        tip.position.y += h * 0.9;
        tip.castShadow = true;
        group.add(tip);
      }
    }
    group.position.set(s.x, 0, s.z);
    scene.add(group);
  }
}

function buildChest(position) {
  const group = new THREE.Group();
  group.name = "treasureChest";
  const wood = new THREE.MeshStandardMaterial({
    color: 0x6a3d1c,
    roughness: 0.85,
    metalness: 0.08,
  });
  const brass = new THREE.MeshStandardMaterial({
    color: 0xd9a45a,
    roughness: 0.4,
    metalness: 0.85,
  });
  const gold = new THREE.MeshStandardMaterial({
    color: 0xffd23f,
    roughness: 0.3,
    metalness: 0.95,
    emissive: 0x553a00,
    emissiveIntensity: 0.25,
  });

  const base = new THREE.Mesh(new THREE.BoxGeometry(2.2, 1.1, 1.4), wood);
  base.position.y = 0.55;
  base.castShadow = true;
  base.receiveShadow = true;
  group.add(base);

  // Curved lid via a half-cylinder.
  const lid = new THREE.Mesh(
    new THREE.CylinderGeometry(0.75, 0.75, 2.2, 18, 1, false, 0, Math.PI),
    wood
  );
  lid.rotation.z = Math.PI / 2;
  lid.position.set(0, 1.1, 0);
  // Tilt the lid open a bit.
  lid.rotation.x = -0.4;
  lid.position.z = -0.18;
  lid.position.y = 1.18;
  lid.castShadow = true;
  group.add(lid);

  // Brass bands.
  for (const z of [-0.55, 0.55]) {
    const band = new THREE.Mesh(
      new THREE.TorusGeometry(0.78, 0.06, 8, 24, Math.PI),
      brass
    );
    band.rotation.y = Math.PI / 2;
    band.position.set(0, 1.1, z);
    band.rotation.x = Math.PI / 2;
    group.add(band);
  }

  // Pile of gold coins peeking out.
  for (let i = 0; i < 6; i++) {
    const coin = new THREE.Mesh(
      new THREE.CylinderGeometry(0.22, 0.22, 0.05, 14),
      gold
    );
    coin.position.set(
      (Math.random() - 0.5) * 1.4,
      0.55 + Math.random() * 0.4,
      (Math.random() - 0.5) * 0.8
    );
    coin.rotation.set(
      (Math.random() - 0.5) * 1.2,
      Math.random() * Math.PI,
      (Math.random() - 0.5) * 1.2
    );
    coin.castShadow = true;
    group.add(coin);
  }

  group.position.copy(position);
  group.rotation.y = -0.4;
  return group;
}

function buildStarfish(position, color = 0xff6a3d) {
  const shape = new THREE.Shape();
  const r1 = 1.2;
  const r2 = 0.45;
  const arms = 5;
  for (let i = 0; i < arms * 2; i++) {
    const r = i % 2 === 0 ? r1 : r2;
    const a = (i / (arms * 2)) * Math.PI * 2 - Math.PI / 2;
    const x = Math.cos(a) * r;
    const y = Math.sin(a) * r;
    if (i === 0) shape.moveTo(x, y);
    else shape.lineTo(x, y);
  }
  const geo = new THREE.ExtrudeGeometry(shape, {
    depth: 0.18,
    bevelEnabled: true,
    bevelSegments: 2,
    bevelSize: 0.12,
    bevelThickness: 0.12,
  });
  geo.rotateX(-Math.PI / 2);
  const mat = new THREE.MeshStandardMaterial({
    color,
    roughness: 0.7,
    metalness: 0.05,
    flatShading: true,
  });
  const m = new THREE.Mesh(geo, mat);
  m.position.copy(position);
  m.rotation.y = Math.random() * Math.PI * 2;
  m.castShadow = true;
  return m;
}
