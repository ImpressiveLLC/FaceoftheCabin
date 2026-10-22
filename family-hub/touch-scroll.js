// Shared with cabin-ui/src/touch-scroll.js. Native touch panning remains enabled.
(() => {
  let timer;
  function emphasize() {
    document.documentElement.classList.add('scroll-active');
    clearTimeout(timer);
    timer = setTimeout(() => document.documentElement.classList.remove('scroll-active'), 3000);
  }
  document.addEventListener('pointerdown', emphasize, { passive: true });
  document.addEventListener('touchstart', emphasize, { passive: true });
  document.addEventListener('keydown', emphasize);
  document.addEventListener('scroll', emphasize, { passive: true, capture: true });
})();
