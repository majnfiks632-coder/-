import * as THREE from "three";

/**
 * Rising-bubble particle system. Multiple emitters at fixed positions
 * each spawn bubbles that rise, wobble side-to-side, and pop when they
 * reach the surface. Uses a single Points object with a per-vertex
 * size/opacity attribute for efficiency.
 */
export function buildBubbles(bounds, options = {}) {
  const capacity = options.capacity ?? 500;
  const emitters = options.emitters ?? [
    { x: -16, z: -7, rate: 6 },
    { x: 15, z: 9, rate: 4 },
    { x: -2, z: -8, rate: 2 }, // treasure chest
    { x: 4, z: -10, rate: 3 },
  ];

  const positions = new Float32Array(capacity * 3);
  const velocities = new Float32Array(capacity * 3);
  const ages = new Float32Array(capacity);
  const lifetimes = new Float32Array(capacity);
  const sizes = new Float32Array(capacity);
  const alive = new Uint8Array(capacity);

  const geo = new THREE.BufferGeometry();
  geo.setAttribute("position", new THREE.BufferAttribute(positions, 3));
  geo.setAttribute("aSize", new THREE.BufferAttribute(sizes, 1));

  const mat = new THREE.ShaderMaterial({
    uniforms: {
      uPixelRatio: { value: Math.min(window.devicePixelRatio, 2) },
    },
    transparent: true,
    depthWrite: false,
    vertexShader: /* glsl */ `
      attribute float aSize;
      uniform float uPixelRatio;
      varying float vAlpha;
      void main() {
        vec4 mv = modelViewMatrix * vec4(position, 1.0);
        gl_Position = projectionMatrix * mv;
        gl_PointSize = aSize * (300.0 / -mv.z) * uPixelRatio;
        vAlpha = clamp(aSize * 0.7, 0.0, 1.0);
      }
    `,
    fragmentShader: /* glsl */ `
      varying float vAlpha;
      void main() {
        vec2 c = gl_PointCoord - 0.5;
        float r = length(c);
        if (r > 0.5) discard;
        // Soft edge + a subtle highlight to suggest a bubble.
        float core = smoothstep(0.5, 0.42, r);
        float rim  = smoothstep(0.5, 0.46, r) - smoothstep(0.46, 0.34, r);
        float hi   = smoothstep(0.18, 0.07, length(c + vec2(0.12, -0.14)));
        vec3 col = vec3(0.85, 0.95, 1.0);
        float a = (rim * 0.55 + hi * 0.35) * vAlpha + core * 0.05;
        gl_FragColor = vec4(col, a);
      }
    `,
  });

  const points = new THREE.Points(geo, mat);
  points.frustumCulled = false;
  points.name = "bubbles";

  let nextEmit = Array(emitters.length).fill(0);

  function spawn(i, ex, ez) {
    positions[i * 3 + 0] = ex + (Math.random() - 0.5) * 0.6;
    positions[i * 3 + 1] = bounds.minY + 0.3;
    positions[i * 3 + 2] = ez + (Math.random() - 0.5) * 0.6;
    velocities[i * 3 + 0] = (Math.random() - 0.5) * 0.25;
    velocities[i * 3 + 1] = THREE.MathUtils.randFloat(1.8, 3.2);
    velocities[i * 3 + 2] = (Math.random() - 0.5) * 0.25;
    ages[i] = 0;
    lifetimes[i] = (bounds.maxY - bounds.minY) / velocities[i * 3 + 1] + 0.2;
    sizes[i] = THREE.MathUtils.randFloat(0.06, 0.18);
    alive[i] = 1;
  }

  function findSlot() {
    for (let i = 0; i < capacity; i++) {
      if (!alive[i]) return i;
    }
    return -1;
  }

  function update(dt, time) {
    // Emit.
    for (let e = 0; e < emitters.length; e++) {
      nextEmit[e] -= dt;
      while (nextEmit[e] <= 0) {
        const slot = findSlot();
        if (slot === -1) break;
        spawn(slot, emitters[e].x, emitters[e].z);
        nextEmit[e] += 1.0 / emitters[e].rate;
      }
    }

    // Integrate.
    for (let i = 0; i < capacity; i++) {
      if (!alive[i]) {
        sizes[i] = 0;
        continue;
      }
      ages[i] += dt;
      // Wobble side-to-side.
      const wob = Math.sin(time * 3.0 + i * 0.7) * 0.25;
      positions[i * 3 + 0] += (velocities[i * 3 + 0] + wob) * dt;
      positions[i * 3 + 1] += velocities[i * 3 + 1] * dt;
      positions[i * 3 + 2] += velocities[i * 3 + 2] * dt;

      const t = ages[i] / lifetimes[i];
      if (t > 1 || positions[i * 3 + 1] > bounds.maxY - 0.2) {
        alive[i] = 0;
        sizes[i] = 0;
        continue;
      }
      // Bubbles get slightly bigger as pressure drops, then pop.
      const base = sizes[i] || 0.12;
      sizes[i] = base * (1 + t * 0.3);
    }
    geo.attributes.position.needsUpdate = true;
    geo.attributes.aSize.needsUpdate = true;
  }

  return { points, update };
}
