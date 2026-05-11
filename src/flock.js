import * as THREE from "three";
import { Fish, SPECIES_NAMES } from "./fish.js";

/**
 * Manages a flock of fish using a classic Reynolds boids model
 * (separation / alignment / cohesion) plus boundary avoidance and
 * optional food-seeking. O(N^2) is fine here — N stays well under 100.
 */
export class Flock {
  constructor(scene, bounds, { count = 26 } = {}) {
    this.scene = scene;
    this.bounds = bounds;
    this.fish = [];
    this.foods = []; // list of { position: Vector3, ... }

    for (let i = 0; i < count; i++) {
      this._spawnOne();
    }
  }

  get count() {
    return this.fish.length;
  }

  _spawnOne() {
    const species =
      SPECIES_NAMES[Math.floor(Math.random() * SPECIES_NAMES.length)];
    // Boost overall sizing so fish read clearly at sensible orbit
    // distances. Tiny neons stay smaller; big-bodied angels stay larger.
    const baseScale =
      species === "neon" ? 1.0 : species === "angel" ? 1.7 : 1.5;
    const f = new Fish({ species, scale: baseScale });
    f.position.set(
      THREE.MathUtils.randFloat(this.bounds.minX + 2, this.bounds.maxX - 2),
      THREE.MathUtils.randFloat(this.bounds.minY + 2, this.bounds.maxY - 2),
      THREE.MathUtils.randFloat(this.bounds.minZ + 2, this.bounds.maxZ - 2)
    );
    this.scene.add(f.group);
    this.fish.push(f);
    return f;
  }

  addFish(n = 1) {
    for (let i = 0; i < n; i++) this._spawnOne();
  }

  setLighting(ambient, light) {
    for (const f of this.fish) f.setLighting(ambient, light);
  }

  setFoods(foods) {
    this.foods = foods;
  }

  update(dt, time) {
    const fish = this.fish;
    const bounds = this.bounds;

    // Scratch vectors so we don't churn the GC.
    const sep = new THREE.Vector3();
    const ali = new THREE.Vector3();
    const coh = new THREE.Vector3();
    const seek = new THREE.Vector3();
    const wall = new THREE.Vector3();
    const desired = new THREE.Vector3();
    const offset = new THREE.Vector3();

    for (let i = 0; i < fish.length; i++) {
      const f = fish[i];
      sep.set(0, 0, 0);
      ali.set(0, 0, 0);
      coh.set(0, 0, 0);
      let sepCount = 0;
      let aliCount = 0;
      let cohCount = 0;

      const perception = 6.0;
      const personal = 1.6 + f.size * 0.6;

      for (let j = 0; j < fish.length; j++) {
        if (i === j) continue;
        const o = fish[j];
        const d = f.position.distanceTo(o.position);
        if (d < personal && d > 1e-4) {
          offset.subVectors(f.position, o.position).divideScalar(d * d);
          sep.add(offset);
          sepCount++;
        }
        if (d < perception) {
          ali.add(o.velocity);
          coh.add(o.position);
          aliCount++;
          cohCount++;
        }
      }

      if (sepCount > 0) sep.divideScalar(sepCount);
      if (aliCount > 0) ali.divideScalar(aliCount);
      if (cohCount > 0) {
        coh.divideScalar(cohCount).sub(f.position);
      }

      // Steer = desired - velocity (normalized to maxSpeed).
      sep.setLength(sep.length() > 0 ? f.maxSpeed : 0).sub(f.velocity);
      ali.setLength(ali.length() > 0 ? f.maxSpeed : 0).sub(f.velocity);
      coh.setLength(coh.length() > 0 ? f.maxSpeed : 0).sub(f.velocity);

      // Food seeking.
      seek.set(0, 0, 0);
      if (this.foods.length > 0) {
        // Find nearest food.
        let nearest = null;
        let nd = Infinity;
        for (const fd of this.foods) {
          const d = f.position.distanceTo(fd.position);
          if (d < nd) {
            nd = d;
            nearest = fd;
          }
        }
        if (nearest && nd < 18) {
          seek
            .subVectors(nearest.position, f.position)
            .setLength(f.maxSpeed)
            .sub(f.velocity);
        }
      }

      // Wall avoidance — soft repulsion from every wall when close.
      wall.set(0, 0, 0);
      const margin = 3.0;
      const push = (axis, lo, hi) => {
        const v = f.position[axis];
        if (v < bounds[lo] + margin) {
          wall[axis] += (bounds[lo] + margin - v) * 1.2;
        } else if (v > bounds[hi] - margin) {
          wall[axis] -= (v - (bounds[hi] - margin)) * 1.2;
        }
      };
      push("x", "minX", "maxX");
      push("y", "minY", "maxY");
      push("z", "minZ", "maxZ");

      // Weighted sum into acceleration.
      desired
        .set(0, 0, 0)
        .addScaledVector(sep, 1.6)
        .addScaledVector(ali, 0.85)
        .addScaledVector(coh, 0.7)
        .addScaledVector(wall, 2.0)
        .addScaledVector(seek, 2.2);

      // Mild downforce-free vertical damping so fish don't all rocket up.
      desired.y *= 0.7;

      // Clamp the steering force.
      const maxForce = 2.6;
      if (desired.length() > maxForce) desired.setLength(maxForce);

      f.acceleration.add(desired);
    }

    // Integrate motion + animate.
    for (const f of fish) {
      f.update(dt, time);
      // Hard clamp inside bounds as a final safety net.
      f.position.x = THREE.MathUtils.clamp(
        f.position.x,
        bounds.minX + 0.5,
        bounds.maxX - 0.5
      );
      f.position.y = THREE.MathUtils.clamp(
        f.position.y,
        bounds.minY + 0.5,
        bounds.maxY - 0.5
      );
      f.position.z = THREE.MathUtils.clamp(
        f.position.z,
        bounds.minZ + 0.5,
        bounds.maxZ - 0.5
      );
    }
  }
}
