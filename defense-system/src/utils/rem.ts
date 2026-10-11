function setRem() {
  const baseSize = 16;
  const scale = document.documentElement.clientWidth / 1920;
  const size = Math.min(Math.max(scale, 0.8), 1.5) * baseSize;
  document.documentElement.style.fontSize = `${
    size > 16 ? 16 : size < 12 ? 12 : size
  }px`;
}

setRem();
window.addEventListener("resize", setRem);
