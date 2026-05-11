import * as THREE from "three";

/**
 * Drop-and-sink food pellets. Each pellet is a small glowing sphere
 * that sinks slowly. Fish target the nearest pellet via the flock's
 * food-seeking rule; pellets are removed once eaten (close enough to
 * any fish) or once they reach the floor.
 */
export class FoodManager {
  constructor(scene, bounds) {
    this.scene = scene;
    this.bounds = bounds;
    this.items = []; // { position: Vector3, velocity: Vector3, mesh, life }

    this.geo = new THREE.SphereGeometry(0.16, 10, 8);
    this.mat = new THREE.MeshStandardMaterial({
      color: 0xffd070,
      emissive: 0x4a3300,
      emissiveIntensity: 0.7,
      roughness: 0.45,
      metalness: 0.05,
    });
  }

  drop(x, z, count = 8) {
    for (let i = 0; i < count; i++) {
      const mesh = new THREE.Mesh(this.geo, this.mat);
      const pos = new THREE.Vector3(
        THREE.MathUtils.clamp(
          x + (Math.random() - 0.5) * 1.6,
          this.bounds.minX + 1,
          this.bounds.maxX - 1
        ),
        this.bounds.maxY - 0.4,
        THREE.MathUtils.clamp(
          z + (Math.random() - 0.5) * 1.6,
          this.bounds.minZ + 1,
          this.bounds.maxZ - 1
        )
      );
      mesh.position.copy(pos);
      mesh.castShadow = true;
      this.scene.add(mesh);
      this.items.push({
        position: pos,
        velocity: new THREE.Vector3(
          (Math.random() - 0.5) * 0.3,
          -THREE.MathUtils.randFloat(0.4, 0.8),
          (Math.random() - 0.5) * 0.3
        ),
        mesh,
        life: 14, // seconds
      });
    }
  }

  update(dt, fish) {
    for (let i = this.items.length - 1; i >= 0; i--) {
      const f = this.items[i];
      f.life -= dt;
      f.position.addScaledVector(f.velocity, dt);
      // Drift, gentle damping.
      f.velocity.x *= 0.99;
      f.velocity.z *= 0.99;
      // Settle when on the floor.
      if (f.position.y < this.bounds.minY + 0.2) {
        f.position.y = this.bounds.minY + 0.2;
        f.velocity.set(0, 0, 0);
      }
      f.mesh.position.copy(f.position);
      f.mesh.rotation.y += dt * 1.5;

      // Eaten?
      for (const fish_ of fish) {
        if (f.position.distanceTo(fish_.position) < 0.7) {
          f.life = 0;
          break;
        }
      }

      if (f.life <= 0) {
        this.scene.remove(f.mesh);
        this.items.splice(i, 1);
      }
    }
  }
}
