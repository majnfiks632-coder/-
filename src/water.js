import * as THREE from "three";

/**
 * Top water surface — viewed from below most of the time, so the
 * underside is just as important as the top. Uses a custom shader that
 * combines moving sine waves for displacement with a fresnel-tinted
 * color blend (deep blue under, sky-tinted on top).
 */
export function buildWaterSurface(bounds) {
  const w = bounds.maxX - bounds.minX;
  const d = bounds.maxZ - bounds.minZ;
  const geo = new THREE.PlaneGeometry(w, d, 96, 60);
  geo.rotateX(-Math.PI / 2);

  const uniforms = {
    uTime: { value: 0 },
    uDeepColor: { value: new THREE.Color(0x0b3756) },
    uShallowColor: { value: new THREE.Color(0x66c7ff) },
    uFoamColor: { value: new THREE.Color(0xeaf6ff) },
    uOpacity: { value: 0.55 },
  };

  const mat = new THREE.ShaderMaterial({
    uniforms,
    transparent: true,
    side: THREE.DoubleSide,
    depthWrite: false,
    vertexShader: /* glsl */ `
      uniform float uTime;
      varying vec3 vWorldPos;
      varying vec3 vNormal;
      varying float vWaveHeight;

      // Cheap stacked sines — converges to a believable wavy surface.
      float wave(vec2 p) {
        float h = 0.0;
        h += sin(p.x * 0.45 + uTime * 0.9) * 0.18;
        h += sin(p.y * 0.6  - uTime * 1.1) * 0.14;
        h += sin((p.x + p.y) * 0.35 + uTime * 0.6) * 0.10;
        h += sin((p.x - p.y) * 0.7  - uTime * 1.4) * 0.06;
        return h;
      }

      void main() {
        vec3 p = position;
        float h = wave(p.xz);
        p.y += h;
        vWaveHeight = h;

        // Approximate normal by sampling neighbours.
        float eps = 0.4;
        float hx = wave(p.xz + vec2(eps, 0.0));
        float hz = wave(p.xz + vec2(0.0, eps));
        vec3 n = normalize(vec3(h - hx, eps, h - hz));
        vNormal = n;

        vec4 worldPos = modelMatrix * vec4(p, 1.0);
        vWorldPos = worldPos.xyz;
        gl_Position = projectionMatrix * viewMatrix * worldPos;
      }
    `,
    fragmentShader: /* glsl */ `
      uniform vec3 uDeepColor;
      uniform vec3 uShallowColor;
      uniform vec3 uFoamColor;
      uniform float uOpacity;
      varying vec3 vWorldPos;
      varying vec3 vNormal;
      varying float vWaveHeight;

      void main() {
        vec3 viewDir = normalize(cameraPosition - vWorldPos);
        float fres = pow(1.0 - max(dot(vNormal, viewDir), 0.0), 2.2);

        vec3 col = mix(uDeepColor, uShallowColor, fres);
        // Foamy highlights at wave crests.
        float foam = smoothstep(0.18, 0.32, vWaveHeight);
        col = mix(col, uFoamColor, foam * 0.55);

        gl_FragColor = vec4(col, uOpacity + fres * 0.25);
        #include <colorspace_fragment>
      }
    `,
  });

  const mesh = new THREE.Mesh(geo, mat);
  mesh.position.set(
    (bounds.minX + bounds.maxX) / 2,
    bounds.maxY,
    (bounds.minZ + bounds.maxZ) / 2
  );
  mesh.renderOrder = 2;
  mesh.name = "waterSurface";

  return {
    mesh,
    update(t) {
      uniforms.uTime.value = t;
    },
    setMood({ shallow, deep, foam }) {
      if (shallow) uniforms.uShallowColor.value.set(shallow);
      if (deep) uniforms.uDeepColor.value.set(deep);
      if (foam) uniforms.uFoamColor.value.set(foam);
    },
  };
}
