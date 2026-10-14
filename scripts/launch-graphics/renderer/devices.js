// Device frames: pure geometry (used by layouts.js and tests) plus the DOM builder (used by scene.js).
// Frame materials are neutral greys; they are the only hard-coded colors in the renderer.

/** Geometry as fractions of the outer frame width. `dir` is the store-screenshots folder. */
export const DEVICES = {
  iphone: { name: "iPhone", phone: true, dir: "ios-6.9", ar: 2868 / 1320, rim: 0.011, bz: 0.023, R: 0.165, deco: "island" },
  android: { name: "Android phone", phone: true, dir: "android-phone", ar: 2424 / 1080, rim: 0.01, bz: 0.023, R: 0.12, deco: "hole" },
  ipad: { name: "iPad", phone: false, dir: "ipad-13", ar: 2752 / 2064, rim: 0.007, bz: 0.04, R: 0.058, deco: "cam" },
  tablet: { name: "Android tablet", phone: false, dir: "android-tab10", ar: 2560 / 1600, rim: 0.007, bz: 0.044, R: 0.05, deco: "cam" },
};
export const DEVICE_IDS = Object.keys(DEVICES);

/** Screen width as a fraction of the frame width. */
export const kOf = (id) => 1 - 2 * (DEVICES[id].rim + DEVICES[id].bz);

/** URL of a screenshot served by server.mjs. */
export const shotUrl = (id, mode, shot) => `/shots/${DEVICES[id].dir}/${mode}/${shot}.png`;

/** Build the frame element for a device. Call only in a browser. */
export function createDeviceElement(id) {
  const g = DEVICES[id];
  const w = document.createElement("div");
  w.className = "devw";
  w.dataset.dev = id;
  const deco = g.deco === "island" ? '<i class="island"></i>' : g.deco === "hole" ? '<i class="hole"></i>' : "";
  w.innerHTML =
    `<div class="dev"><div class="bez">${g.deco === "cam" ? '<i class="cam"></i>' : ""}` +
    `<div class="scr"><img alt="" draggable="false">${deco}</div></div></div><i class="selring ui"></i>`;
  return w;
}

/** Apply a laid-out device (from layouts.js) to its element. Returns the <img> for load tracking. */
export function applyDevice(el, id, G, src) {
  const g = DEVICES[id];
  el.classList.toggle("hidden", !G.visible);
  Object.assign(el.style, {
    width: `${G.w}px`,
    height: `${G.h}px`,
    transform: `translate(${G.x}px, ${G.y}px) rotate(${G.tilt}deg)`,
    zIndex: String(1 + (G.z || 0)),
  });
  const px = (v) => `${v}px`;
  el.style.setProperty("--R", px(g.R * G.w));
  el.style.setProperty("--R2", px((g.R - g.rim) * G.w));
  el.style.setProperty("--R3", px(Math.max(4, (g.R - g.rim - g.bz) * G.w)));
  el.style.setProperty("--rim", px(g.rim * G.w));
  el.style.setProperty("--bz", px(g.bz * G.w));
  el.style.setProperty("--zoom", String(G.focus));
  el.style.setProperty("--fy", `${G.focusAt}%`);
  const img = el.querySelector("img");
  if (img.getAttribute("src") !== src) img.setAttribute("src", src);
  return img;
}
