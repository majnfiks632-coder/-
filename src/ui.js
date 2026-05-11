/**
 * Wires the HUD buttons and stats to the running scene. The scene
 * exposes simple callbacks; this module owns the DOM bits so the
 * rest of the codebase stays free of querySelector noise.
 */
export function bindUI(handlers) {
  const buttons = {
    day: document.querySelector('[data-action="toggle-day"]'),
    rotate: document.querySelector('[data-action="toggle-rotate"]'),
    feed: document.querySelector('[data-action="feed"]'),
    addFish: document.querySelector('[data-action="add-fish"]'),
    bloom: document.querySelector('[data-action="toggle-bloom"]'),
  };

  const stats = {
    fps: document.querySelector('[data-v="fps"]'),
    fish: document.querySelector('[data-v="fish"]'),
    mode: document.querySelector('[data-v="mode"]'),
  };

  buttons.day.addEventListener("click", () => {
    const isNight = handlers.toggleDay();
    buttons.day.classList.toggle("is-warm", isNight);
    buttons.day.querySelector(".ico").textContent = isNight ? "☾" : "☀";
    buttons.day.querySelector(".lbl").textContent = isNight ? "Day" : "Night";
    stats.mode.textContent = isNight ? "Night" : "Day";
  });

  buttons.rotate.addEventListener("click", () => {
    const on = handlers.toggleAutoRotate();
    buttons.rotate.classList.toggle("is-on", on);
  });

  buttons.feed.addEventListener("click", () => {
    handlers.feed();
  });

  buttons.addFish.addEventListener("click", () => {
    handlers.addFish();
  });

  buttons.bloom.addEventListener("click", () => {
    const on = handlers.toggleBloom();
    buttons.bloom.classList.toggle("is-on", on);
  });

  // Loader hide.
  const loader = document.getElementById("loader");
  function hideLoader() {
    loader.classList.add("hidden");
  }

  // FPS / stats updater.
  let last = performance.now();
  let frames = 0;
  let acc = 0;
  function tickStats(now, fishCount) {
    frames++;
    acc += now - last;
    last = now;
    if (acc > 500) {
      const fps = Math.round((frames * 1000) / acc);
      stats.fps.textContent = String(fps);
      frames = 0;
      acc = 0;
    }
    if (fishCount != null) stats.fish.textContent = String(fishCount);
  }

  return { hideLoader, tickStats };
}
