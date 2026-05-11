import * as THREE from "three";

/**
 * Procedurally-generated fish.
 *
 * Geometry is a deformed cylinder along +X with extra fin meshes. A
 * per-vertex attribute `aBodyT` (0 at head, 1 at tail) drives a vertex
 * shader that sweeps a traveling sine wave along the body, producing
 * the side-to-side swimming wiggle. Each fish gets its own material
 * (so it can carry its own phase/speed uniforms), but geometry is
 * shared across instances of the same species.
 */

const speciesCache = new Map();

const SPECIES = {
  clown: {
    length: 1.6,
    girth: 0.45,
    body: 0xff7a2e,
    belly: 0xffd9a8,
    stripe: 0xfaf3e8,
    fin: 0x1b1410,
    stripes: [0.55, 0.75], // x positions of vertical white bands
    finScale: 1.0,
  },
  bluetang: {
    length: 1.9,
    girth: 0.55,
    body: 0x2e7dff,
    belly: 0x82c8ff,
    stripe: 0x0d2240,
    fin: 0xffe24a,
    stripes: [],
    finScale: 1.05,
  },
  yellowtang: {
    length: 1.7,
    girth: 0.5,
    body: 0xffd23f,
    belly: 0xffe98a,
    stripe: 0xffb84a,
    fin: 0xffd23f,
    stripes: [],
    finScale: 1.1,
  },
  angel: {
    length: 1.5,
    girth: 0.65,
    body: 0xf4f1e5,
    belly: 0xfffaee,
    stripe: 0x222831,
    fin: 0xffd070,
    stripes: [0.42, 0.62],
    finScale: 1.3,
  },
  neon: {
    length: 0.7,
    girth: 0.18,
    body: 0x4ed0ff,
    belly: 0xeaf6ff,
    stripe: 0xff3b6b,
    fin: 0xeaf6ff,
    stripes: [0.5],
    finScale: 0.7,
  },
};

export const SPECIES_NAMES = Object.keys(SPECIES);

function buildBodyGeometry(spec) {
  // Tube along +X. Length is 1 in object space, then scaled by
  // spec.length when instanced. Radius is shaped by radiusAt(t) and
  // squashed so the body is taller-than-wide like a real fish.
  const segX = 28;
  const segR = 14;
  const positions = [];
  const normals = [];
  const uvs = [];
  const indices = [];
  const bodyT = [];
  const colors = [];

  const bodyCol = new THREE.Color(spec.body);
  const bellyCol = new THREE.Color(spec.belly);
  const stripeCol = new THREE.Color(spec.stripe);

  // Side-profile of a generic fish: rounded snout, widest just behind
  // the head, smooth taper to a narrow caudal peduncle. The constants
  // here were tuned by eye so the silhouette reads as "fish" without
  // looking like an egg.
  function radiusAt(t) {
    const bulge = Math.pow(Math.sin(Math.PI * Math.min(t * 1.25, 1)), 1.1);
    const taper = Math.pow(1.0 - t * 0.95, 2.0);
    return Math.min(bulge, taper) * (0.18 * spec.girth + 0.05);
  }

  // Build rings.
  for (let i = 0; i <= segX; i++) {
    const t = i / segX;
    const x = THREE.MathUtils.lerp(0.5, -0.5, t); // head at +x, tail at -x
    const r = radiusAt(t);
    for (let j = 0; j <= segR; j++) {
      const a = (j / segR) * Math.PI * 2;
      const cy = Math.cos(a);
      const cz = Math.sin(a);
      // Squash: taller than wide (fish are laterally compressed).
      const y = cy * r * 1.6;
      const z = cz * r * 0.75;
      positions.push(x, y, z);
      normals.push(0, cy, cz); // recomputed later
      uvs.push(t, j / segR);
      bodyT.push(t);

      // Vertex colors: belly lighter on bottom, stripes at specified x.
      let col = bodyCol.clone();
      col.lerp(bellyCol, THREE.MathUtils.clamp(-cy * 0.7 + 0.45, 0, 1));
      for (const sx of spec.stripes) {
        const dx = Math.abs(t - sx);
        const w = 0.05;
        if (dx < w) {
          col.lerp(stripeCol, 1.0 - dx / w);
        }
      }
      colors.push(col.r, col.g, col.b);
    }
  }
  for (let i = 0; i < segX; i++) {
    for (let j = 0; j < segR; j++) {
      const a = i * (segR + 1) + j;
      const b = a + (segR + 1);
      indices.push(a, b, a + 1, b, b + 1, a + 1);
    }
  }

  const geo = new THREE.BufferGeometry();
  geo.setAttribute("position", new THREE.Float32BufferAttribute(positions, 3));
  geo.setAttribute("normal", new THREE.Float32BufferAttribute(normals, 3));
  geo.setAttribute("uv", new THREE.Float32BufferAttribute(uvs, 2));
  geo.setAttribute("aBodyT", new THREE.Float32BufferAttribute(bodyT, 1));
  geo.setAttribute("color", new THREE.Float32BufferAttribute(colors, 3));
  geo.setIndex(indices);
  geo.computeVertexNormals();
  return geo;
}

function buildFinGeometry(spec, kind) {
  // All fins are flat triangle fans, sized relative to spec.girth so
  // they stay proportional to the body across species.
  const g = 0.18 * spec.girth + 0.05; // matches radiusAt() scale
  const finS = spec.finScale ?? 1.0;
  let positions, indices, bodyT;

  if (kind === "tail") {
    // Crescent tail at the -x end. Height ~ 1.6x body radius.
    const h = g * 2.6 * finS;
    positions = [
      -0.5, 0, 0,
      -0.92, h, 0,
      -0.82, 0, 0,
      -0.92, -h, 0,
    ];
    indices = [0, 1, 2, 0, 2, 3];
    bodyT = [1.0, 1.0, 1.0, 1.0];
  } else if (kind === "dorsal") {
    // Thin triangular dorsal fin riding on top of the back.
    const h = g * 3.2 * finS;
    const base = g * 1.6;
    positions = [
      0.05, base, 0,
      -0.2, base, 0,
      -0.05, base + h, 0,
    ];
    indices = [0, 1, 2];
    bodyT = [0.5, 0.65, 0.55];
  } else if (kind === "pectoral") {
    // Small angled pectoral fin on the side.
    const s = g * 1.4 * finS;
    positions = [
      0.18, -g * 0.4, g * 1.0,
      0.0, -g * 1.4, g * 1.6 + s,
      -0.05, g * 0.4, g * 1.2 + s * 0.4,
    ];
    indices = [0, 1, 2];
    bodyT = [0.35, 0.4, 0.35];
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute("position", new THREE.Float32BufferAttribute(positions, 3));
  geo.setAttribute("aBodyT", new THREE.Float32BufferAttribute(bodyT, 1));
  geo.setIndex(indices);
  geo.computeVertexNormals();
  return geo;
}

function makeBodyMaterial() {
  return new THREE.ShaderMaterial({
    uniforms: {
      uTime: { value: 0 },
      uPhase: { value: 0 },
      uSpeed: { value: 6 },
      uWave: { value: 0.18 },
      uAmbient: { value: new THREE.Color(0x6ab4d8) },
      uLight: { value: new THREE.Color(0xffffff) },
      uLightDir: { value: new THREE.Vector3(0.3, 0.9, 0.3).normalize() },
    },
    vertexShader: /* glsl */ `
      attribute float aBodyT;
      attribute vec3 color;
      varying vec3 vColor;
      varying vec3 vNormalW;

      uniform float uTime;
      uniform float uPhase;
      uniform float uSpeed;
      uniform float uWave;

      void main() {
        vec3 p = position;
        // Wiggle in object-space: along +z, amplitude rising toward tail.
        float t = aBodyT;
        float amp = smoothstep(0.0, 1.0, t);
        float w = sin(uTime * uSpeed + uPhase - t * 4.0);
        p.z += w * uWave * amp;

        vColor = color;
        vec4 worldPos = modelMatrix * vec4(p, 1.0);
        vNormalW = normalize(mat3(modelMatrix) * normal);
        gl_Position = projectionMatrix * viewMatrix * worldPos;
      }
    `,
    fragmentShader: /* glsl */ `
      varying vec3 vColor;
      varying vec3 vNormalW;
      uniform vec3 uAmbient;
      uniform vec3 uLight;
      uniform vec3 uLightDir;

      void main() {
        float ndl = clamp(dot(normalize(vNormalW), normalize(uLightDir)), 0.0, 1.0);
        float fres = pow(1.0 - max(dot(normalize(vNormalW), vec3(0.0, 0.0, 1.0)), 0.0), 2.0);
        vec3 lit = vColor * (uAmbient + uLight * ndl);
        lit += fres * 0.15;
        gl_FragColor = vec4(lit, 1.0);
        #include <colorspace_fragment>
      }
    `,
  });
}

function makeFinMaterial(color) {
  return new THREE.ShaderMaterial({
    uniforms: {
      uTime: { value: 0 },
      uPhase: { value: 0 },
      uSpeed: { value: 6 },
      uWave: { value: 0.22 },
      uColor: { value: new THREE.Color(color) },
    },
    side: THREE.DoubleSide,
    transparent: true,
    vertexShader: /* glsl */ `
      attribute float aBodyT;
      uniform float uTime;
      uniform float uPhase;
      uniform float uSpeed;
      uniform float uWave;
      varying float vT;
      void main() {
        vec3 p = position;
        float t = aBodyT;
        float w = sin(uTime * uSpeed + uPhase - t * 4.0);
        p.z += w * uWave * smoothstep(0.0, 1.0, t);
        vT = t;
        gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0);
      }
    `,
    fragmentShader: /* glsl */ `
      uniform vec3 uColor;
      varying float vT;
      void main() {
        gl_FragColor = vec4(uColor, 0.92 - vT * 0.15);
        #include <colorspace_fragment>
      }
    `,
  });
}

function getSpeciesGeometry(name) {
  if (speciesCache.has(name)) return speciesCache.get(name);
  const spec = SPECIES[name];
  const data = {
    spec,
    body: buildBodyGeometry(spec),
    tail: buildFinGeometry(spec, "tail"),
    dorsal: buildFinGeometry(spec, "dorsal"),
    pectoralL: buildFinGeometry(spec, "pectoral"),
    pectoralR: (() => {
      const g = buildFinGeometry(spec, "pectoral");
      // Mirror to opposite side via attribute mutation.
      const pos = g.attributes.position;
      for (let i = 0; i < pos.count; i++) {
        pos.setZ(i, -pos.getZ(i));
      }
      pos.needsUpdate = true;
      g.computeVertexNormals();
      return g;
    })(),
  };
  speciesCache.set(name, data);
  return data;
}

export class Fish {
  constructor({ species = "clown", scale = 1.0 } = {}) {
    this.species = species;
    const data = getSpeciesGeometry(species);
    const spec = data.spec;

    this.group = new THREE.Group();
    this.group.name = `fish-${species}`;

    // Body
    this.bodyMat = makeBodyMaterial();
    const body = new THREE.Mesh(data.body, this.bodyMat);
    body.castShadow = true;
    body.receiveShadow = false;
    this.group.add(body);

    // Fins (each gets its own material with its own wiggle uniforms,
    // but they're synced to the body's phase/speed via shared values).
    this.finMats = [];
    const finCol = spec.fin;
    const fins = [data.tail, data.dorsal, data.pectoralL, data.pectoralR];
    for (const finGeo of fins) {
      const m = makeFinMaterial(finCol);
      this.finMats.push(m);
      const mesh = new THREE.Mesh(finGeo, m);
      mesh.castShadow = true;
      this.group.add(mesh);
    }

    // Apply species size.
    const s = spec.length * scale;
    this.group.scale.setScalar(s);

    // Per-fish wiggle params.
    this.phase = Math.random() * Math.PI * 2;
    this.swimSpeed = THREE.MathUtils.randFloat(5.0, 7.5);
    this.maxSpeed = THREE.MathUtils.randFloat(2.4, 3.4) * (1 / scale);
    this.size = s; // approximate "radius" for separation
    this._setUniform("uPhase", this.phase);
    this._setUniform("uSpeed", this.swimSpeed);

    // Motion state (boids).
    this.position = new THREE.Vector3();
    this.velocity = new THREE.Vector3(
      THREE.MathUtils.randFloatSpread(2),
      THREE.MathUtils.randFloatSpread(0.4),
      THREE.MathUtils.randFloatSpread(2)
    ).setLength(this.maxSpeed * 0.6);
    this.acceleration = new THREE.Vector3();

    this.target = null; // optional Vector3 to seek (food)

    // Reusable scratch for orientation math.
    this._headAxis = new THREE.Vector3(1, 0, 0);
    this._targetQ = new THREE.Quaternion();
  }

  _setUniform(name, value) {
    if (this.bodyMat.uniforms[name]) this.bodyMat.uniforms[name].value = value;
    for (const m of this.finMats) {
      if (m.uniforms[name]) m.uniforms[name].value = value;
    }
  }

  setLighting(ambient, light) {
    this.bodyMat.uniforms.uAmbient.value.copy(ambient);
    this.bodyMat.uniforms.uLight.value.copy(light);
  }

  update(dt, time) {
    // Apply boid acceleration.
    this.velocity.addScaledVector(this.acceleration, dt);
    // Clamp to species max speed.
    if (this.velocity.length() > this.maxSpeed) {
      this.velocity.setLength(this.maxSpeed);
    }
    // Tiny minimum so fish don't freeze.
    if (this.velocity.length() < 0.4) {
      this.velocity.setLength(0.4);
    }
    this.position.addScaledVector(this.velocity, dt);
    this.acceleration.set(0, 0, 0);

    // Orient the model's +X (head) along the velocity. Slerp toward
    // the desired quaternion for smooth turning rather than snapping.
    const dir = this.velocity.clone().normalize();
    this._targetQ.setFromUnitVectors(this._headAxis, dir);
    const turnT = Math.min(1, dt * 4.5);
    this.group.quaternion.slerp(this._targetQ, turnT);

    this.group.position.copy(this.position);

    // Wiggle speed scales gently with swim speed for natural look.
    const sp = THREE.MathUtils.mapLinear(
      this.velocity.length(),
      0.2,
      this.maxSpeed,
      4.5,
      9.5
    );
    this._setUniform("uSpeed", sp);
    this._setUniform("uTime", time);
  }
}
