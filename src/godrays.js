import * as THREE from "three";

/**
 * Volumetric "god ray" beams shining down through the water surface.
 * Implemented as a few overlapping additive-blended cones whose
 * intensity flickers gently over time. Cheap, but reads as classic
 * underwater shafts of light.
 */
export function buildGodRays(bounds) {
  const group = new THREE.Group();
  group.name = "godRays";

  const baseUniforms = {
    uTime: { value: 0 },
    uColor: { value: new THREE.Color(0xfff1c4) },
    uOpacity: { value: 0.32 },
  };

  const mat = new THREE.ShaderMaterial({
    uniforms: baseUniforms,
    transparent: true,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    side: THREE.DoubleSide,
    vertexShader: /* glsl */ `
      varying float vY;
      void main() {
        vY = position.y;
        gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
      }
    `,
    fragmentShader: /* glsl */ `
      uniform float uTime;
      uniform vec3 uColor;
      uniform float uOpacity;
      varying float vY;
      void main() {
        // Top of cone is bright, bottom fades; subtle flicker over time.
        float t = clamp((vY + 7.0) / 14.0, 0.0, 1.0);
        float fade = pow(t, 1.6);
        float flicker = 0.85 + 0.15 * sin(uTime * 2.3 + vY * 0.4);
        gl_FragColor = vec4(uColor * fade * flicker, fade * uOpacity);
      }
    `,
  });

  const placements = [
    { x: -10, z: -2, scale: 1.0, h: 14 },
    { x: 6, z: 4, scale: 1.2, h: 14 },
    { x: -2, z: 8, scale: 0.9, h: 13 },
    { x: 12, z: -6, scale: 1.05, h: 14 },
  ];

  for (const p of placements) {
    const geo = new THREE.ConeGeometry(2.4 * p.scale, p.h, 24, 1, true);
    // Position the cone with its tip at the surface (open base = wide
    // shaft of light reaching down toward the floor).
    geo.translate(0, -p.h / 2, 0);
    geo.translate(0, bounds.maxY, 0);
    const mesh = new THREE.Mesh(geo, mat);
    mesh.position.x = p.x;
    mesh.position.z = p.z;
    group.add(mesh);
  }

  return {
    group,
    uniforms: baseUniforms,
    update(t) {
      baseUniforms.uTime.value = t;
    },
  };
}
