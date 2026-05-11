import * as THREE from "three";

/**
 * Animated caustics overlay projected on the sandy floor. A second
 * additive plane that fades in/out the dappled light pattern produced
 * by waves overhead. Cheap and impactful.
 */
export function buildCaustics(bounds) {
  const w = bounds.maxX - bounds.minX + 4;
  const d = bounds.maxZ - bounds.minZ + 4;
  const geo = new THREE.PlaneGeometry(w, d, 1, 1);

  const uniforms = {
    uTime: { value: 0 },
    uIntensity: { value: 0.32 },
    uTint: { value: new THREE.Color(0xb6e6ff) },
  };

  const mat = new THREE.ShaderMaterial({
    uniforms,
    transparent: true,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    vertexShader: /* glsl */ `
      varying vec2 vUv;
      void main() {
        vUv = uv;
        gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
      }
    `,
    fragmentShader: /* glsl */ `
      uniform float uTime;
      uniform float uIntensity;
      uniform vec3 uTint;
      varying vec2 vUv;

      // Voronoi-ish caustics: distance to the nearest of several moving
      // points, then sharpened to produce thin bright veins.
      float caustic(vec2 p, float t) {
        float d = 1e9;
        for (int i = 0; i < 5; i++) {
          float fi = float(i);
          vec2 c = vec2(
            0.5 + 0.4 * sin(t * 0.6 + fi * 1.3),
            0.5 + 0.4 * cos(t * 0.5 + fi * 2.1)
          );
          d = min(d, length(p - c));
        }
        return d;
      }

      void main() {
        // Tile so the pattern repeats nicely across the floor.
        vec2 p = fract(vUv * 2.5 + vec2(uTime * 0.03, uTime * 0.04));
        float a = caustic(p, uTime);
        float b = caustic(p + 0.13, uTime * 1.1 + 1.0);

        // Two layers offset slightly produce thin interference veins.
        float pat = pow(1.0 - smoothstep(0.0, 0.32, abs(a - b)), 3.0);

        // Falloff so the corners stay dim.
        vec2 c = vUv - 0.5;
        float vignette = smoothstep(0.72, 0.0, length(c));

        vec3 col = uTint * pat * uIntensity * vignette;
        gl_FragColor = vec4(col, pat * uIntensity * vignette);
      }
    `,
  });

  const mesh = new THREE.Mesh(geo, mat);
  mesh.rotation.x = -Math.PI / 2;
  mesh.position.set(
    (bounds.minX + bounds.maxX) / 2,
    0.04,
    (bounds.minZ + bounds.maxZ) / 2
  );
  mesh.renderOrder = 1;
  mesh.name = "caustics";

  return {
    mesh,
    uniforms,
    update(t) {
      uniforms.uTime.value = t;
    },
  };
}
