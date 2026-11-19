{
'use strict';

/* ================= Grundlagen ================= */
const $ = s => document.querySelector(s);
const NATIVE = window.Native || null;
const R = 6371008.8, D2R = Math.PI / 180, R2D = 180 / Math.PI;
const COLORS = ['#FF5B3A', '#3FC1FF', '#F5D547', '#B48CFF', '#4FE08A', '#FF7FBF'];
const DIRS = ['N','NNO','NO','ONO','O','OSO','SO','SSO','S','SSW','SW','WSW','W','WNW','NW','NNW'];

const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} }
};

const DEFAULTS = { layer: 'sat', lineKm: 30, errDeg: 3, fov: 66, offset: 0, onboarded: false };
const S = Object.assign({}, DEFAULTS, store.get('pl.settings', {}));
let bearings = store.get('pl.bearings', []);
const saveS = () => store.set('pl.settings', S);
const saveB = () => store.set('pl.bearings', bearings);

const nf = (v, d = 0) => Number(v).toLocaleString('de-DE', { minimumFractionDigits: d, maximumFractionDigits: d });
const norm = a => ((a % 360) + 360) % 360;
const sdiff = a => { a = norm(a); return a > 180 ? a - 360 : a; };
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const dirName = b => DIRS[Math.round(norm(b) / 22.5) % 16];
const fmtDist = m => m < 1000 ? nf(Math.round(m / 10) * 10) + ' m' : nf(m / 1000, m < 10000 ? 2 : 1) + ' km';
const fmtCoord = p => p.lat.toFixed(5) + ', ' + p.lon.toFixed(5);
const fmtTime = t => new Date(t).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
const fmtSigned = (v, d = 1) => { const r = Math.round(v * 10 ** d) / 10 ** d; return (r > 0 ? '+' : r < 0 ? '−' : '±') + nf(Math.abs(r), d) + '°'; };
const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const ll = p => [p.lat, p.lon];
const icon = id => `<svg class="ic"><use href="#i-${id}"/></svg>`;

function haptic() { try { NATIVE && NATIVE.haptic(); } catch (e) {} }

let toastTimer = 0;
function toast(msg, ms = 2600) {
  const t = $('#toast');
  t.textContent = msg; t.hidden = false;
  t.style.animation = 'none'; void t.offsetWidth; t.style.animation = '';
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, ms);
}

function openExternal(url) {
  if (NATIVE) { try { NATIVE.openExternal(url); return; } catch (e) {} }
  window.open(url, '_blank');
}
function copyText(text) {
  if (NATIVE) { try { NATIVE.copy(text); toast('Kopiert'); return; } catch (e) {} }
  (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject())
    .then(() => toast('Kopiert'), () => toast(text, 5000));
}
function shareText(text) {
  if (NATIVE) { try { NATIVE.share(text); return; } catch (e) {} }
  copyText(text);
}

window.setInsets = (top, bottom) => {
  document.documentElement.style.setProperty('--st', Math.max(0, top) + 'px');
  document.documentElement.style.setProperty('--sb', Math.max(0, bottom) + 'px');
  if (window.map) setTimeout(() => map.invalidateSize(), 50);
};

/* ================= Geodäsie ================= */
function dist(a, b) {
  const p1 = a.lat * D2R, p2 = b.lat * D2R, dp = p2 - p1, dl = (b.lon - a.lon) * D2R;
  const h = Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}
function bearingTo(a, b) {
  const p1 = a.lat * D2R, p2 = b.lat * D2R, dl = (b.lon - a.lon) * D2R;
  return norm(Math.atan2(Math.sin(dl) * Math.cos(p2), Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)) * R2D);
}
function dest(a, brg, d) {
  const p1 = a.lat * D2R, l1 = a.lon * D2R, t = brg * D2R, dr = d / R;
  const p2 = Math.asin(Math.sin(p1) * Math.cos(dr) + Math.cos(p1) * Math.sin(dr) * Math.cos(t));
  const l2 = l1 + Math.atan2(Math.sin(t) * Math.sin(dr) * Math.cos(p1), Math.cos(dr) - Math.sin(p1) * Math.sin(p2));
  return { lat: p2 * R2D, lon: ((l2 * R2D + 540) % 360) - 180 };
}
// Lokale Ebene (Ost/Nord in Metern) – für Strecken bis ~150 km ausreichend genau
function plane(ref) {
  const k = Math.cos(ref.lat * D2R);
  return {
    f: p => ({ x: (p.lon - ref.lon) * D2R * R * k, y: (p.lat - ref.lat) * D2R * R }),
    i: q => ({ lat: ref.lat + q.y / R * R2D, lon: ref.lon + q.x / (R * k) * R2D })
  };
}
const unit = b => ({ x: Math.sin(b * D2R), y: Math.cos(b * D2R) });
function rayCross(a, b) {
  const det = b.u.x * a.u.y - a.u.x * b.u.y;
  if (Math.abs(det) < 1e-9) return null;
  const dx = b.p.x - a.p.x, dy = b.p.y - a.p.y;
  const t = (b.u.x * dy - dx * b.u.y) / det;
  const s = (a.u.x * dy - a.u.y * dx) / det;
  if (t <= 0 || s <= 0) return null;
  return { x: a.p.x + t * a.u.x, y: a.p.y + t * a.u.y };
}

/* Schnittpunkt aller sichtbaren Peilungen (kleinste Quadrate) + Unsicherheitsfläche */
function solveFix() {
  const vis = bearings.filter(b => b.on);
  if (vis.length < 2) return null;
  const P = plane(vis[0]);
  const L = vis.map(b => ({ b, p: P.f(b), u: unit(b.brg) }));

  let best = null, bestSin = -1;
  for (let i = 0; i < L.length; i++) for (let j = i + 1; j < L.length; j++) {
    const s = Math.abs(L[i].u.x * L[j].u.y - L[i].u.y * L[j].u.x);
    const base = Math.hypot(L[i].p.x - L[j].p.x, L[i].p.y - L[j].p.y);
    const score = s * Math.min(1, base / 50);
    if (score > bestSin) { bestSin = score; best = [L[i], L[j]]; }
  }
  const angle = Math.asin(clamp(Math.abs(best[0].u.x * best[1].u.y - best[0].u.y * best[1].u.x), 0, 1)) * R2D;
  const base = Math.hypot(best[0].p.x - best[1].p.x, best[0].p.y - best[1].p.y);
  if (base < 30) return { err: 'same', base };

  let a11 = 0, a12 = 0, a22 = 0, b1 = 0, b2 = 0;
  for (const l of L) {
    const m11 = 1 - l.u.x * l.u.x, m12 = -l.u.x * l.u.y, m22 = 1 - l.u.y * l.u.y;
    a11 += m11; a12 += m12; a22 += m22;
    b1 += m11 * l.p.x + m12 * l.p.y; b2 += m12 * l.p.x + m22 * l.p.y;
  }
  const det = a11 * a22 - a12 * a12;
  if (Math.abs(det) < 1e-6 || angle < 1) return { err: 'parallel', angle };
  const x = { x: (a22 * b1 - a12 * b2) / det, y: (a11 * b2 - a12 * b1) / det };
  for (const l of L) if ((x.x - l.p.x) * l.u.x + (x.y - l.p.y) * l.u.y <= 0) return { err: 'behind' };

  const e = S.errDeg;
  const turn = (l, d) => ({ p: l.p, u: unit(l.b.brg + d) });
  const corners = [[e, e], [e, -e], [-e, -e], [-e, e]].map(([d1, d2]) => rayCross(turn(best[0], d1), turn(best[1], d2)));
  const pt = P.i(x);
  let poly = null, radius = null;
  if (corners.every(Boolean)) {
    poly = corners.map(P.i);
    radius = Math.max(...poly.map(c => dist(pt, c)));
  }
  let rms = 0;
  for (const l of L) {
    const dx = x.x - l.p.x, dy = x.y - l.p.y;
    rms += (dx * l.u.y - dy * l.u.x) ** 2;
  }
  rms = Math.sqrt(rms / L.length);
  return { pt, poly, radius, angle, base, n: vis.length, rms, weak: angle < 10 };
}

/* ================= Standort ================= */
let pos = null, geoWatch = null, geoError = null, decl = 0, declAt = null;

function startGeo() {
  if (!navigator.geolocation) { geoError = 'Kein GPS verfügbar'; return; }
  if (geoWatch !== null) navigator.geolocation.clearWatch(geoWatch);
  geoWatch = navigator.geolocation.watchPosition(onPos, err => {
    geoError = err.code === 1 ? 'Standortzugriff verweigert' : 'GPS sucht …';
    updateGpsChip();
  }, { enableHighAccuracy: true, maximumAge: 1500, timeout: 30000 });
}
function onPos(p) {
  const first = !pos;
  pos = { lat: p.coords.latitude, lon: p.coords.longitude, acc: p.coords.accuracy, alt: p.coords.altitude || 0, t: Date.now() };
  geoError = null;
  if (!declAt || dist(declAt, pos) > 20000) {
    declAt = { lat: pos.lat, lon: pos.lon };
    try { decl = NATIVE ? Number(NATIVE.declination(pos.lat, pos.lon, pos.alt)) || 0 : 0; } catch (e) { decl = 0; }
  }
  drawMe();
  if (first && !bearings.some(b => b.on)) map.setView(ll(pos), Math.max(map.getZoom(), 15));
  if (follow) map.panTo(ll(pos), { animate: true });
  updateGpsChip();
}
window.onNativePermissions = () => { startGeo(); if (Cam.open && !Cam.stream) startStream(); };

/* ================= Kompass ================= */
/* Blickrichtung der Rückkamera aus alpha/beta/gamma (W3C-Rotationsmatrix, Spalte z negiert) */
const O = { cam: null, top: null, last: 0, hist: [], abs: false, seen: false };

function onOrientation(e) {
  if (e.alpha == null) return;
  const absolute = e.type === 'deviceorientationabsolute' || e.absolute === true;
  O.seen = true;
  if (!absolute) return;
  O.abs = true;
  const a = e.alpha * D2R, b = e.beta * D2R, g = (e.gamma || 0) * D2R;
  const cA = Math.cos(a), sA = Math.sin(a), cB = Math.cos(b), sB = Math.sin(b), cG = Math.cos(g), sG = Math.sin(g);
  const cam = [-(cA * sG + sA * sB * cG), cA * sB * cG - sA * sG, -cB * cG];
  const top = [-cB * sA, cA * cB, sB];
  const k = 0.2;
  O.cam = O.cam ? mix(O.cam, cam, k) : cam;
  O.top = O.top ? mix(O.top, top, k) : top;
  const now = performance.now();
  O.last = now;
  O.hist.push({ t: now, v: cam });
  while (O.hist.length && now - O.hist[0].t > 1500) O.hist.shift();
}
function mix(p, q, k) {
  const r = [p[0] + (q[0] - p[0]) * k, p[1] + (q[1] - p[1]) * k, p[2] + (q[2] - p[2]) * k];
  const n = Math.hypot(r[0], r[1], r[2]) || 1;
  return [r[0] / n, r[1] / n, r[2] / n];
}
const corr = () => decl + S.offset;
const oriAlive = () => O.cam && performance.now() - O.last < 1500;
const vecHeading = v => norm(Math.atan2(v[0], v[1]) * R2D);
const vecElev = v => Math.asin(clamp(v[2], -1, 1)) * R2D;
const vecHoriz = v => Math.hypot(v[0], v[1]);

/* Mittelwert der Kamerarichtung über die letzten ms (ruhiger als ein Einzelwert) */
function meanCam(ms) {
  const now = performance.now();
  const s = O.hist.filter(h => now - h.t <= ms);
  if (s.length < 3) return O.cam;
  const m = [0, 0, 0];
  for (const h of s) { m[0] += h.v[0]; m[1] += h.v[1]; m[2] += h.v[2]; }
  const n = Math.hypot(m[0], m[1], m[2]) || 1;
  return [m[0] / n, m[1] / n, m[2] / n];
}
function jitterDeg() {
  const now = performance.now();
  const s = O.hist.filter(h => now - h.t <= 800);
  if (s.length < 5) return 0;
  let c = 0, sn = 0;
  for (const h of s) { const a = Math.atan2(h.v[0], h.v[1]); c += Math.cos(a); sn += Math.sin(a); }
  const Rb = Math.hypot(c, sn) / s.length;
  return Math.sqrt(Math.max(0, -2 * Math.log(Math.max(Rb, 1e-9)))) * R2D;
}

if ('ondeviceorientationabsolute' in window) window.addEventListener('deviceorientationabsolute', onOrientation);
else window.addEventListener('deviceorientation', onOrientation);

/* ================= Karte ================= */
const LAYERS = {
  sat: { name: 'Satellit', sub: 'Luftbild mit Orten und Straßen', labels: true,
    url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
    attr: 'Luftbild © Esri, Maxar, Earthstar Geographics', native: 19 },
  satplain: { name: 'Satellit pur', sub: 'Nur Luftbild, ohne Beschriftung',
    url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
    attr: 'Luftbild © Esri, Maxar, Earthstar Geographics', native: 19 },
  osm: { name: 'Straßenkarte', sub: 'OpenStreetMap',
    url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
    attr: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>-Mitwirkende', native: 19 },
  topo: { name: 'Gelände', sub: 'Höhenlinien, Berge, Wald',
    url: 'https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png', subs: 'abc',
    attr: '© OpenStreetMap, SRTM · <a href="https://opentopomap.org">OpenTopoMap</a> (CC-BY-SA)', native: 17 },
  dark: { name: 'Nacht', sub: 'Dunkle Karte, schont die Augen',
    url: 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', subs: 'abcd',
    attr: '© OpenStreetMap © <a href="https://carto.com/attributions">CARTO</a>', native: 20 }
};
const LABEL_URLS = [
  'https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}',
  'https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}'
];

const view = store.get('pl.view', null);
const map = window.map = L.map('map', {
  zoomControl: false, attributionControl: true, worldCopyJump: true,
  maxZoom: 21, minZoom: 2, tapTolerance: 18, zoomSnap: 0.25, bounceAtZoomLimits: false
}).setView(view ? view.c : [51.16, 10.45], view ? view.z : 6);
map.attributionControl.setPrefix(false);

let baseLayer = null, labelLayers = [];
function setLayer(key) {
  if (!LAYERS[key]) key = 'sat';
  S.layer = key; saveS();
  if (baseLayer) map.removeLayer(baseLayer);
  labelLayers.forEach(l => map.removeLayer(l)); labelLayers = [];
  const d = LAYERS[key];
  baseLayer = L.tileLayer(d.url, { attribution: d.attr, maxNativeZoom: d.native, maxZoom: 21, subdomains: d.subs || 'abc', detectRetina: false, crossOrigin: false }).addTo(map);
  if (d.labels) labelLayers = LABEL_URLS.map(u => L.tileLayer(u, { maxNativeZoom: 19, maxZoom: 21, pane: 'overlayPane', opacity: .95 }).addTo(map));
  document.body.dataset.layer = key;
}
map.createPane('wedges').style.zIndex = 390;
map.createPane('lines').style.zIndex = 410;
map.createPane('marks').style.zIndex = 620;
setLayer(S.layer);

const gBear = L.layerGroup().addTo(map);
const gFix = L.layerGroup().addTo(map);
const gPin = L.layerGroup().addTo(map);
let meMarker = null, meCircle = null, follow = false;

map.on('moveend zoomend', () => {
  store.set('pl.view', { c: [map.getCenter().lat, map.getCenter().lng], z: map.getZoom() });
  map.getContainer().classList.toggle('z-low', map.getZoom() < 10.5);
});
map.getContainer().classList.toggle('z-low', map.getZoom() < 10.5);
map.on('dragstart', () => { if (follow) { follow = false; $('#btnLocate').classList.remove('on'); } });

const CONE = '<svg class="cone" viewBox="0 0 80 80"><defs><radialGradient id="cg" cx="50%" cy="100%" r="100%"><stop offset="0" stop-color="#3D8BFF" stop-opacity=".55"/><stop offset="1" stop-color="#3D8BFF" stop-opacity="0"/></radialGradient></defs><path d="M40 40 L24 4 A40 40 0 0 1 56 4 Z" fill="url(#cg)"/></svg>';
function drawMe() {
  if (!pos) return;
  if (!meMarker) {
    meCircle = L.circle(ll(pos), { radius: pos.acc, pane: 'wedges', color: '#3D8BFF', weight: 1, opacity: .5, fillOpacity: .1, interactive: false }).addTo(map);
    meMarker = L.marker(ll(pos), { icon: L.divIcon({ className: 'me', html: CONE + '<i></i>', iconSize: [0, 0] }), pane: 'marks', interactive: false, keyboard: false }).addTo(map);
  } else {
    meCircle.setLatLng(ll(pos)).setRadius(pos.acc);
    meMarker.setLatLng(ll(pos));
  }
}
function updateMeHeading() {
  if (!meMarker) return;
  const el = meMarker.getElement();
  if (!el) return;
  if (!oriAlive()) { el.classList.remove('has-hdg'); return; }
  const v = vecHoriz(O.cam) > 0.6 ? O.cam : O.top;
  const h = norm(vecHeading(v) + corr());
  el.classList.add('has-hdg');
  el.querySelector('.cone').style.transform = `rotate(${h}deg)`;
}
setInterval(() => { if (!Cam.open) updateMeHeading(); }, 120);

const TICKS = [1, 2, 3, 5, 10, 15, 20, 30, 40, 50, 75, 100, 150];
function drawBearings() {
  gBear.clearLayers();
  const len = S.lineKm * 1000;
  bearings.forEach(b => {
    if (!b.on) return;
    const pts = [];
    for (let k = 0; k <= 80; k++) pts.push(ll(dest(b, b.brg, len * k / 80)));
    const wedge = [ll(b)];
    for (let k = 0; k <= 20; k++) wedge.push(ll(dest(b, b.brg - S.errDeg + 2 * S.errDeg * k / 20, len)));
    L.polygon(wedge, { pane: 'wedges', stroke: false, fillColor: b.color, fillOpacity: .13, interactive: false }).addTo(gBear);
    L.polyline(pts, { pane: 'lines', color: '#000', weight: 6, opacity: .35, interactive: false }).addTo(gBear);
    L.polyline(pts, { pane: 'lines', color: b.color, weight: 2.6 }).on('click', ev => { L.DomEvent.stop(ev); showBearing(b.id); }).addTo(gBear);
    L.polyline(pts, { pane: 'lines', opacity: 0, weight: 24 }).on('click', ev => { L.DomEvent.stop(ev); showBearing(b.id); }).addTo(gBear);
    L.circleMarker(ll(b), { pane: 'marks', radius: 6, color: '#fff', weight: 2.5, fillColor: b.color, fillOpacity: 1 })
      .on('click', ev => { L.DomEvent.stop(ev); showBearing(b.id); }).addTo(gBear);
    TICKS.filter(km => km <= S.lineKm && km < S.lineKm).forEach(km => {
      L.marker(ll(dest(b, b.brg, km * 1000)), { icon: L.divIcon({ className: 'tick', html: `<span>${km} km</span>`, iconSize: [0, 0] }), pane: 'lines', interactive: false, keyboard: false }).addTo(gBear);
    });
  });
  drawFix();
  const n = bearings.length;
  $('#count').hidden = !n; $('#count').textContent = n;
}

let lastFix = null;
const FIX_SVG = '<svg viewBox="0 0 44 44"><circle cx="22" cy="22" r="13" fill="none" stroke="#fff" stroke-width="3"/><circle cx="22" cy="22" r="13" fill="none" stroke="#FF5B3A" stroke-width="1.5"/><path d="M22 2v10M22 32v10M2 22h10M32 22h10" stroke="#fff" stroke-width="2.5" stroke-linecap="round"/><circle cx="22" cy="22" r="3" fill="#FF5B3A" stroke="#fff" stroke-width="1.5"/></svg>';
function drawFix() {
  gFix.clearLayers();
  lastFix = solveFix();
  if (!lastFix || lastFix.err) return;
  if (lastFix.poly) L.polygon(lastFix.poly.map(ll), { pane: 'lines', color: '#fff', weight: 1.5, dashArray: '4 4', fillColor: '#fff', fillOpacity: .12, interactive: false }).addTo(gFix);
  L.marker(ll(lastFix.pt), { icon: L.divIcon({ className: 'fix', html: FIX_SVG, iconSize: [0, 0] }), pane: 'marks' })
    .on('click', ev => { L.DomEvent.stop(ev); showFix(); }).addTo(gFix);
}

function fitAll() {
  const b = L.latLngBounds([]);
  const vis = bearings.filter(x => x.on);
  vis.forEach(x => { b.extend(ll(x)); b.extend(ll(dest(x, x.brg, S.lineKm * 1000))); });
  if (lastFix && lastFix.pt) {
    b.extend(ll(lastFix.pt));
    if (vis.length >= 2) {
      const fb = L.latLngBounds(vis.map(ll)).extend(ll(lastFix.pt));
      return fitTo(fb);
    }
  }
  if (pos) b.extend(ll(pos));
  if (b.isValid()) fitTo(b);
}
function fitTo(b) {
  const st = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--st')) || 0;
  const sb = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--sb')) || 0;
  map.fitBounds(b, { paddingTopLeft: [36, st + 80], paddingBottomRight: [36, sb + Math.min(innerHeight * .5, 360)], maxZoom: 17 });
}

/* ================= Sheet ================= */
const sheet = $('#sheet'), scrim = $('#scrim');
let sheetKind = null;
function openSheet(kind, html, modal = true) {
  sheetKind = kind;
  $('#sheetBody').innerHTML = html;
  $('#sheetBody').scrollTop = 0;
  sheet.classList.add('open'); sheet.setAttribute('aria-hidden', 'false');
  scrim.hidden = !modal;
  if (modal) requestAnimationFrame(() => scrim.classList.add('show'));
  else scrim.classList.remove('show');
}
function closeSheet() {
  if (!sheetKind) return false;
  sheetKind = null;
  sheet.classList.remove('open'); sheet.setAttribute('aria-hidden', 'true');
  scrim.classList.remove('show');
  setTimeout(() => { if (!sheetKind) scrim.hidden = true; }, 200);
  gPin.clearLayers();
  return true;
}
scrim.addEventListener('click', closeSheet);
$('#sheetBody').addEventListener('click', e => {
  const t = e.target.closest('[data-a]');
  if (!t) return;
  const a = t.dataset.a, id = t.dataset.id ? Number(t.dataset.id) : null;
  ACTIONS[a] && ACTIONS[a](t, id);
});
$('#sheetBody').addEventListener('input', e => {
  const t = e.target;
  if (t.dataset.set) SETTERS[t.dataset.set](t);
});

/* Wischen nach unten schließt das Sheet */
{
  let y0 = null, dy = 0;
  sheet.addEventListener('touchstart', e => {
    if ($('#sheetBody').scrollTop > 0 && !e.target.closest('.grab')) { y0 = null; return; }
    if (e.target.closest('input[type=range]')) { y0 = null; return; }
    y0 = e.touches[0].clientY; dy = 0; sheet.style.transition = 'none';
  }, { passive: true });
  sheet.addEventListener('touchmove', e => {
    if (y0 === null) return;
    dy = Math.max(0, e.touches[0].clientY - y0);
    if (dy > 0) sheet.style.transform = `translateY(${dy}px)`;
  }, { passive: true });
  sheet.addEventListener('touchend', () => {
    if (y0 === null) return;
    sheet.style.transition = ''; sheet.style.transform = '';
    if (dy > 90) closeSheet();
    y0 = null;
  });
}

function head(title, sub, color) {
  return `<div class="sh-head">${color ? `<span class="swatch" style="background:${color}"></span>` : ''}<div style="flex:1"><h2>${title}</h2>${sub ? `<p>${sub}</p>` : ''}</div><button class="x" data-a="close" aria-label="Schließen">${icon('close')}</button></div>`;
}
function placeActions(p) {
  return `<div class="actions">
    <button class="act" data-a="gmaps" data-lat="${p.lat}" data-lon="${p.lon}">${icon('map')}Google Maps</button>
    <button class="act" data-a="route" data-lat="${p.lat}" data-lon="${p.lon}">${icon('route')}Route</button>
    <button class="act" data-a="share" data-lat="${p.lat}" data-lon="${p.lon}">${icon('share')}Teilen</button>
  </div>`;
}
function coordStat(p) {
  return `<div class="stat wide coord"><div><small>Koordinaten</small><b class="num">${fmtCoord(p)}</b></div><button class="mini" data-a="copy" data-lat="${p.lat}" data-lon="${p.lon}" aria-label="Kopieren">${icon('copy')}</button></div>`;
}
const pFrom = t => ({ lat: Number(t.dataset.lat), lon: Number(t.dataset.lon) });

/* ---------- Peilung ---------- */
function showBearing(id) {
  const b = bearings.find(x => x.id === id);
  if (!b) return;
  const idx = bearings.indexOf(b) + 1;
  const here = pos ? dist(pos, b) : null;
  const others = bearings.filter(x => x.on && x.id !== b.id).length;
  let note;
  if (!others) note = `<p class="note">Die Lichtquelle liegt irgendwo auf dieser Linie. Für den genauen Punkt geh ein Stück zur Seite – am besten einige hundert Meter quer zur Linie – und peil das Licht noch einmal an.</p>`;
  else if (lastFix && !lastFix.err) note = `<p class="note good">Zusammen mit den anderen Peilungen ergibt sich ein Schnittpunkt.</p>`;
  else note = '';
  openSheet('bearing', `
    ${head(`Peilung ${idx}`, `${fmtTime(b.t)} · Standort ±${nf(b.acc || 0)} m`, b.color)}
    <div class="stats">
      <div class="stat"><small>Richtung</small><b class="num">${nf(b.brg, 1)}°<em>${dirName(b.brg)}</em></b></div>
      <div class="stat"><small>Höhenwinkel</small><b class="num">${fmtSigned(b.elev)}</b></div>
      <div class="stat"><small>Linie</small><b class="num">${nf(S.lineKm)} km</b></div>
      <div class="stat"><small>Von hier</small><b class="num">${here == null ? '–' : fmtDist(here)}</b></div>
    </div>
    ${note}
    <div class="actions">
      <button class="act primary" data-a="aim">${icon('plus')}Neu peilen</button>
      <button class="act" data-a="toggle" data-id="${b.id}">${icon('eyeoff')}Ausblenden</button>
      <button class="act danger" data-a="del" data-id="${b.id}">${icon('trash')}Löschen</button>
    </div>`, false);
}

/* ---------- Ermittelter Punkt ---------- */
function lightHeight(pt) {
  const vis = bearings.filter(b => b.on && typeof b.elev === 'number');
  if (!vis.length) return null;
  const Reff = R * 7 / 6;
  const hs = vis.map(b => { const d = dist(b, pt); return d * Math.tan(b.elev * D2R) + d * d / (2 * Reff); });
  return hs.reduce((a, c) => a + c, 0) / hs.length;
}
function showFix() {
  const f = lastFix;
  if (!f || f.err) return;
  const d = pos ? dist(pos, f.pt) : null;
  const dir = pos ? bearingTo(pos, f.pt) : null;
  const h = lightHeight(f.pt);
  let note = '';
  if (f.weak) note = `<p class="note warn">Die Linien kreuzen sich sehr flach (${nf(f.angle)}°). Der Punkt kann deshalb weit daneben liegen. Peil von einem Ort weiter seitlich nochmal.</p>`;
  else if (f.n > 2 && f.rms > 250) note = `<p class="note warn">Die ${f.n} Peilungen passen nicht ganz zusammen (Abweichung ca. ${fmtDist(f.rms)}). Blende eine ungenaue Peilung aus oder kalibriere den Kompass.</p>`;
  openSheet('fix', `
    ${head('Ermittelter Punkt', `Schnitt aus ${f.n} Peilungen · ${nf(f.angle)}° Kreuzungswinkel`)}
    <div class="stats">
      ${coordStat(f.pt)}
      <div class="stat"><small>Entfernung</small><b class="num">${d == null ? '–' : fmtDist(d)}</b></div>
      <div class="stat"><small>Richtung</small><b class="num">${dir == null ? '–' : nf(dir) + '°'}<em>${dir == null ? '' : dirName(dir)}</em></b></div>
      <div class="stat"><small>Genauigkeit</small><b class="num">${f.radius ? '±' + fmtDist(f.radius) : '–'}</b></div>
      <div class="stat"><small>Höhe des Lichts</small><b class="num">${h == null || Math.abs(h) < 5 ? 'am Boden' : (h > 0 ? 'ca. ' : 'ca. −') + fmtDist(Math.abs(h))}</b></div>
    </div>
    ${note}
    ${placeActions(f.pt)}`, false);
}

/* ---------- Beliebiger Punkt (lange drücken) ---------- */
function showPoint(p, title = 'Punkt auf der Karte', sub = '') {
  gPin.clearLayers();
  L.marker(ll(p), { icon: L.divIcon({ className: 'pin', html: '<i></i>', iconSize: [0, 0] }), pane: 'marks', interactive: false }).addTo(gPin);
  const d = pos ? dist(pos, p) : null, dir = pos ? bearingTo(pos, p) : null;
  openSheet('point', `
    ${head(esc(title), esc(sub))}
    <div class="stats">
      ${coordStat(p)}
      <div class="stat"><small>Entfernung</small><b class="num">${d == null ? '–' : fmtDist(d)}</b></div>
      <div class="stat"><small>Richtung</small><b class="num">${dir == null ? '–' : nf(dir) + '°'}<em>${dir == null ? '' : dirName(dir)}</em></b></div>
    </div>
    ${placeActions(p)}
    <button class="btn ghost" data-a="calibHere" data-lat="${p.lat}" data-lon="${p.lon}">Kompass an diesem Punkt kalibrieren</button>`, false);
}

/* ---------- Liste ---------- */
function showList() {
  if (!bearings.length) {
    openSheet('list', `${head('Peilungen')}
      <div class="empty-state"><b>Noch keine Peilung</b>Tipp auf „Peilen“, richte die Kamera auf das Licht und bestätige mit OK. Die Richtung erscheint dann als Linie auf der Karte.</div>
      <button class="btn" data-a="aim">Jetzt peilen</button>`);
    return;
  }
  const rows = bearings.map((b, i) => `
    <li class="row ${b.on ? '' : 'off'}">
      <span class="swatch" style="background:${b.color}"></span>
      <button class="main" data-a="focus" data-id="${b.id}"><b>${i + 1}. ${nf(b.brg, 1)}° ${dirName(b.brg)}</b><small>${fmtTime(b.t)} · Höhe ${fmtSigned(b.elev)}${pos ? ' · ' + fmtDist(dist(pos, b)) + ' von hier' : ''}</small></button>
      <button class="mini" data-a="toggle" data-id="${b.id}" aria-label="${b.on ? 'Ausblenden' : 'Einblenden'}">${icon(b.on ? 'eye' : 'eyeoff')}</button>
      <button class="mini" data-a="del" data-id="${b.id}" aria-label="Löschen">${icon('trash')}</button>
    </li>`).join('');
  const f = lastFix;
  let fixNote = '';
  if (f && !f.err) fixNote = `<button class="btn" data-a="showFix">Ermittelten Punkt zeigen</button>`;
  else if (f && f.err === 'same') fixNote = `<p class="note">Alle Peilungen stammen vom selben Ort. Für einen Schnittpunkt musst du von einer anderen Stelle aus peilen.</p>`;
  else if (f && f.err === 'parallel') fixNote = `<p class="note warn">Die Linien verlaufen fast parallel. Geh weiter zur Seite und peil erneut.</p>`;
  else if (f && f.err === 'behind') fixNote = `<p class="note warn">Die Linien treffen sich nicht vor dir. Prüf, ob eine Peilung falsch ist, und blende sie aus.</p>`;
  openSheet('list', `${head('Peilungen', `${bearings.length} gespeichert`)}<ul class="rows">${rows}</ul>${fixNote}
    <button class="btn ghost" data-a="clearAll">Alle löschen</button>`);
}

/* ---------- Kartenansicht ---------- */
function showLayers() {
  openSheet('layers', `${head('Kartenansicht')}` + Object.entries(LAYERS).map(([k, d]) => `
    <button class="opt ${S.layer === k ? 'sel' : ''}" data-a="layer" data-k="${k}">
      <span class="prev ${k}"></span><span><b>${d.name}</b><small>${d.sub}</small></span><i class="radio"></i>
    </button>`).join(''));
}

/* ---------- Optionen ---------- */
function showSettings() {
  const ver = (() => { try { return NATIVE ? NATIVE.version() : ''; } catch (e) { return ''; } })();
  openSheet('settings', `${head('Optionen')}
    <div class="set">
      <div class="set-head"><b>Länge der Peillinie</b><output id="oLine">${nf(S.lineKm)} km</output></div>
      <p>So weit wird die Linie auf der Karte gezogen.</p>
      <input type="range" min="2" max="150" step="1" value="${S.lineKm}" data-set="lineKm">
    </div>
    <div class="set">
      <div class="set-head"><b>Unsicherheit</b><output id="oErr">±${nf(S.errDeg, 1)}°</output></div>
      <p>Breite des hellen Fächers um jede Linie. Handy-Kompasse liegen meist 2–5° daneben.</p>
      <input type="range" min="0.5" max="10" step="0.5" value="${S.errDeg}" data-set="errDeg">
    </div>
    <div class="set">
      <div class="set-head"><b>Kompass-Korrektur</b><output id="oOff">${fmtSigned(S.offset)}</output></div>
      <p>Missweisung für deinen Ort wird automatisch eingerechnet (${pos ? fmtSigned(decl) : 'wartet auf GPS'}). Für beste Genauigkeit einmal an einem bekannten Punkt kalibrieren.</p>
      <div class="two"><button class="btn" data-a="calib">Kalibrieren</button><button class="btn ghost" data-a="resetOff">Zurücksetzen</button></div>
    </div>
    <div class="set">
      <div class="set-head"><b>Kamera-Blickwinkel</b><output id="oFov">${nf(S.fov)}°</output></div>
      <p>Nur wichtig, wenn du im Kamerabild neben das Fadenkreuz tippst. Die meisten Hauptkameras haben 63–70°.</p>
      <input type="range" min="45" max="90" step="1" value="${S.fov}" data-set="fov">
    </div>
    <button class="btn ghost" data-a="intro">Kurzanleitung anzeigen</button>
    <p class="ver">Peilung ${esc(ver)}</p>`);
}
const SETTERS = {
  lineKm(t) { S.lineKm = Number(t.value); $('#oLine').textContent = nf(S.lineKm) + ' km'; saveS(); drawBearings(); },
  errDeg(t) { S.errDeg = Number(t.value); $('#oErr').textContent = '±' + nf(S.errDeg, 1) + '°'; saveS(); drawBearings(); },
  fov(t) { S.fov = Number(t.value); $('#oFov').textContent = nf(S.fov) + '°'; saveS(); }
};

function showIntro() {
  openSheet('intro', `${head('So findest du ein Licht')}
    <ol class="steps">
      <li><b>Anpeilen</b>Tipp auf „Peilen“, halte das Handy aufrecht und richte das Fadenkreuz auf das Licht. Du kannst zoomen oder direkt aufs Licht tippen. Dann OK.</li>
      <li><b>Linie ansehen</b>Die Karte zeigt die Richtung als Linie ab deinem Standort. Das Licht liegt irgendwo darauf – in der Satellitenansicht siehst du, was dort ist.</li>
      <li><b>Kreuzpeilung</b>Geh ein Stück zur Seite (je weiter, desto genauer) und peil nochmal. Wo sich die Linien kreuzen, ist der Punkt.</li>
    </ol>
    <p class="note">Halte Abstand zu Autos, Metallgeländern und Lautsprechern – die lenken den Kompass ab. Wenn die Richtung springt, beweg das Handy ein paarmal in Form einer 8.</p>
    <button class="btn" data-a="introDone">Verstanden</button>`);
}

const ACTIONS = {
  close: () => closeSheet(),
  aim: () => { closeSheet(); openCam('aim'); },
  toggle: (t, id) => {
    const b = bearings.find(x => x.id === id); if (!b) return;
    b.on = !b.on; saveB(); drawBearings();
    sheetKind === 'list' ? showList() : closeSheet();
  },
  del: (t, id) => {
    const i = bearings.findIndex(x => x.id === id); if (i < 0) return;
    const [removed] = bearings.splice(i, 1); saveB(); drawBearings();
    sheetKind === 'list' ? showList() : closeSheet();
    undoToast('Peilung gelöscht', () => { bearings.splice(i, 0, removed); saveB(); drawBearings(); });
  },
  clearAll: () => {
    const old = bearings.slice();
    bearings = []; saveB(); drawBearings(); closeSheet();
    undoToast('Alle Peilungen gelöscht', () => { bearings = old; saveB(); drawBearings(); });
  },
  focus: (t, id) => {
    const b = bearings.find(x => x.id === id); if (!b) return;
    if (!b.on) { b.on = true; saveB(); drawBearings(); }
    fitTo(L.latLngBounds([ll(b), ll(dest(b, b.brg, S.lineKm * 1000))]));
    showBearing(id);
  },
  showFix: () => { fitAll(); showFix(); },
  layer: t => { setLayer(t.dataset.k); showLayers(); setTimeout(closeSheet, 180); },
  gmaps: t => { const p = pFrom(t); openExternal(`https://www.google.com/maps/search/?api=1&query=${p.lat},${p.lon}`); },
  route: t => { const p = pFrom(t); openExternal(`https://www.google.com/maps/dir/?api=1&destination=${p.lat},${p.lon}`); },
  share: t => { const p = pFrom(t); shareText(`${fmtCoord(p)}\nhttps://www.google.com/maps/search/?api=1&query=${p.lat},${p.lon}`); },
  copy: t => copyText(fmtCoord(pFrom(t))),
  calib: () => startCalib(),
  calibHere: t => { closeSheet(); beginCalibAt(pFrom(t)); },
  resetOff: () => { S.offset = 0; saveS(); drawBearings(); showSettings(); toast('Kompass-Korrektur zurückgesetzt'); },
  intro: () => showIntro(),
  introDone: () => { S.onboarded = true; saveS(); closeSheet(); }
};

let undoFn = null;
function undoToast(msg, fn) {
  undoFn = fn;
  const t = $('#toast');
  t.innerHTML = `${esc(msg)} <button class="link-btn" id="undoBtn" style="margin-left:10px;padding:4px 2px;color:#C2391D">Rückgängig</button>`;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; undoFn = null; }, 5000);
  $('#undoBtn').onclick = () => { if (undoFn) undoFn(); undoFn = null; t.hidden = true; };
}

/* ================= Kalibrierung ================= */
let calibTarget = null, calibPicking = false;
function startCalib() {
  closeSheet();
  calibPicking = true;
  banner('Tipp auf der Karte auf etwas, das du von hier sehen kannst – Kirchturm, Funkmast, Windrad. Mindestens 500 m entfernt.');
}
function beginCalibAt(p) {
  calibPicking = false;
  if (!pos) { hideBanner(); return toast('Warte auf GPS …'); }
  const d = dist(pos, p);
  if (d < 300) { hideBanner(); return toast('Zu nah – wähle einen Punkt weiter weg'); }
  calibTarget = p;
  hideBanner();
  gPin.clearLayers();
  L.marker(ll(p), { icon: L.divIcon({ className: 'pin', html: '<i></i>', iconSize: [0, 0] }), pane: 'marks', interactive: false }).addTo(gPin);
  openCam('calib');
}
function banner(text) { $('#bannerText').textContent = text; $('#banner').hidden = false; }
function hideBanner() { $('#banner').hidden = true; }
$('#bannerCancel').addEventListener('click', () => { calibPicking = false; hideBanner(); gPin.clearLayers(); });

/* ================= Karten-Interaktion ================= */
map.on('click', e => {
  if (Date.now() - lastLong < 800) return;
  const p = { lat: e.latlng.lat, lon: e.latlng.lng };
  if (calibPicking) return beginCalibAt(p);
  if (!$('#results').hidden) { $('#results').hidden = true; $('#q').blur(); return; }
  if (sheetKind) closeSheet();
});
let lastLong = 0;
function longPressAt(latlng) {
  if (Date.now() - lastLong < 900) return;
  lastLong = Date.now();
  haptic();
  if (calibPicking) return beginCalibAt({ lat: latlng.lat, lon: latlng.lng });
  showPoint({ lat: latlng.lat, lon: latlng.lng });
}
map.on('contextmenu', e => longPressAt(e.latlng));
{
  const el = map.getContainer();
  let lp = null;
  const cancel = () => { if (lp) { clearTimeout(lp.timer); lp = null; } };
  el.addEventListener('touchstart', e => {
    cancel();
    if (e.touches.length !== 1) return;
    const t = e.touches[0];
    const r = el.getBoundingClientRect();
    const pt = L.point(t.clientX - r.left, t.clientY - r.top);
    lp = { x: t.clientX, y: t.clientY, timer: setTimeout(() => { lp = null; longPressAt(map.containerPointToLatLng(pt)); }, 550) };
  }, { passive: true });
  el.addEventListener('touchmove', e => {
    if (!lp) return;
    const t = e.touches[0];
    if (e.touches.length !== 1 || Math.hypot(t.clientX - lp.x, t.clientY - lp.y) > 10) cancel();
  }, { passive: true });
  el.addEventListener('touchend', cancel, { passive: true });
  el.addEventListener('touchcancel', cancel, { passive: true });
  el.addEventListener('contextmenu', e => e.preventDefault());
}

$('#btnAim').addEventListener('click', () => { closeSheet(); openCam('aim'); });
$('#btnList').addEventListener('click', showList);
$('#btnSettings').addEventListener('click', showSettings);
$('#btnLayers').addEventListener('click', showLayers);
$('#btnLocate').addEventListener('click', () => {
  if (!pos) { toast(geoError || 'Standort wird gesucht …'); startGeo(); return; }
  follow = !follow;
  $('#btnLocate').classList.toggle('on', follow);
  if (follow) map.setView(ll(pos), Math.max(map.getZoom(), 16));
});

/* ================= Suche ================= */
let searchTimer = 0, searchSeq = 0;
const qEl = $('#q'), resEl = $('#results');
qEl.addEventListener('input', () => {
  $('#qClear').hidden = !qEl.value;
  clearTimeout(searchTimer);
  if (qEl.value.trim().length < 3) { resEl.hidden = true; return; }
  searchTimer = setTimeout(() => runSearch(qEl.value.trim()), 450);
});
$('#searchForm').addEventListener('submit', e => { e.preventDefault(); clearTimeout(searchTimer); runSearch(qEl.value.trim()); });
$('#qClear').addEventListener('click', () => { qEl.value = ''; $('#qClear').hidden = true; resEl.hidden = true; qEl.focus(); });
function parseCoords(s) {
  const m = s.replace(/°/g, ' ').match(/^\s*(-?\d+(?:[.,]\d+)?)\s*[,; ]\s*(-?\d+(?:[.,]\d+)?)\s*$/);
  if (!m) return null;
  const lat = parseFloat(m[1].replace(',', '.')), lon = parseFloat(m[2].replace(',', '.'));
  if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
  return { lat, lon };
}
async function runSearch(q) {
  if (!q) return;
  const c = parseCoords(q);
  if (c) { goTo(c, 'Koordinaten', ''); return; }
  const seq = ++searchSeq;
  resEl.innerHTML = '<li class="empty">Suche …</li>'; resEl.hidden = false;
  try {
    const center = map.getCenter();
    const url = `https://nominatim.openstreetmap.org/search?format=jsonv2&limit=7&accept-language=de&q=${encodeURIComponent(q)}`
      + `&viewbox=${center.lng - 2},${center.lat + 1.5},${center.lng + 2},${center.lat - 1.5}`;
    const r = await fetch(url, { headers: { 'Accept': 'application/json' } });
    const list = await r.json();
    if (seq !== searchSeq) return;
    if (!list.length) { resEl.innerHTML = '<li class="empty">Nichts gefunden</li>'; return; }
    resEl.innerHTML = list.map((x, i) => {
      const parts = x.display_name.split(', ');
      return `<li data-i="${i}"><b>${esc(parts[0])}</b><small>${esc(parts.slice(1, 4).join(', '))}</small></li>`;
    }).join('');
    resEl.onclick = e => {
      const li = e.target.closest('li[data-i]'); if (!li) return;
      const x = list[Number(li.dataset.i)];
      const parts = x.display_name.split(', ');
      goTo({ lat: Number(x.lat), lon: Number(x.lon) }, parts[0], parts.slice(1, 3).join(', '), x.boundingbox);
    };
  } catch (e) {
    if (seq === searchSeq) resEl.innerHTML = '<li class="empty">Keine Verbindung zur Suche</li>';
  }
}
function goTo(p, title, sub, bbox) {
  resEl.hidden = true; qEl.blur();
  follow = false; $('#btnLocate').classList.remove('on');
  if (bbox) fitTo(L.latLngBounds([[Number(bbox[0]), Number(bbox[2])], [Number(bbox[1]), Number(bbox[3])]]));
  else map.setView(ll(p), 15);
  showPoint(p, title, sub);
}

/* ================= Kamera ================= */
const Cam = { open: false, mode: 'aim', stream: null, track: null, hwZoom: null, zoom: 1, tap: null, raf: 0, zoomPending: null };
const video = $('#video'), camEl = $('#cam'), zoomEl = $('#zoom');

async function openCam(mode) {
  if (Cam.open) return;
  Cam.open = true; Cam.mode = mode; Cam.tap = null; Cam.zoom = 1;
  camEl.hidden = false; camEl.classList.remove('tapped');
  $('#tapMark').hidden = true; $('#tapReset').hidden = true; $('#camError').hidden = true;
  video.style.transform = '';
  try { NATIVE && NATIVE.keepAwake(true); } catch (e) {}
  if (!pos) startGeo();
  await startStream();
  loop();
}
async function startStream() {
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) return camFail('Kamera wird nicht unterstützt.');
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 }, height: { ideal: 1080 } }
    });
    if (!Cam.open) { stream.getTracks().forEach(t => t.stop()); return; }
    Cam.stream = stream;
    Cam.track = stream.getVideoTracks()[0];
    video.srcObject = stream;
    await video.play().catch(() => {});
    let caps = {};
    try { caps = Cam.track.getCapabilities ? Cam.track.getCapabilities() : {}; } catch (e) {}
    if (caps.zoom && caps.zoom.max > caps.zoom.min) {
      Cam.hwZoom = { min: Math.max(1, caps.zoom.min), max: caps.zoom.max, step: caps.zoom.step || 0.1 };
      zoomEl.min = Cam.hwZoom.min; zoomEl.max = Math.min(Cam.hwZoom.max, 30); zoomEl.step = Math.max(0.05, Cam.hwZoom.step);
    } else {
      Cam.hwZoom = null;
      zoomEl.min = 1; zoomEl.max = 8; zoomEl.step = 0.05;
    }
    $('.zoom-min').textContent = nf(Number(zoomEl.min), Number(zoomEl.min) % 1 ? 1 : 0) + '×';
    setZoom(Number(zoomEl.min));
    $('#camError').hidden = true;
  } catch (e) {
    const denied = e && (e.name === 'NotAllowedError' || e.name === 'SecurityError');
    camFail(denied ? 'Die App darf die Kamera nicht benutzen. Erlaube den Zugriff in den App-Einstellungen.' : 'Kamera konnte nicht gestartet werden.');
  }
}
function camFail(msg) {
  $('#camErrorText').textContent = msg;
  $('#camErrorBtn').hidden = !NATIVE;
  $('#camError').hidden = false;
}
$('#camErrorBtn').addEventListener('click', () => { try { NATIVE.openAppSettings(); } catch (e) {} });

function closeCam() {
  if (!Cam.open) return false;
  Cam.open = false;
  cancelAnimationFrame(Cam.raf);
  if (Cam.stream) Cam.stream.getTracks().forEach(t => t.stop());
  Cam.stream = null; Cam.track = null;
  video.srcObject = null;
  camEl.hidden = true;
  try { NATIVE && NATIVE.keepAwake(false); } catch (e) {}
  if (Cam.mode === 'calib') { calibTarget = null; gPin.clearLayers(); }
  return true;
}
$('#camClose').addEventListener('click', closeCam);

function setZoom(z) {
  z = clamp(z, Number(zoomEl.min), Number(zoomEl.max));
  Cam.zoom = z;
  zoomEl.value = z;
  $('#zoomVal').textContent = nf(z, 1) + '×';
  if (Cam.hwZoom && Cam.track) {
    Cam.zoomPending = z;
    if (!Cam.zoomBusy) applyHwZoom();
  } else {
    video.style.transform = z > 1 ? `scale(${z})` : '';
  }
  placeTapMark();
}
async function applyHwZoom() {
  Cam.zoomBusy = true;
  while (Cam.zoomPending !== null && Cam.track) {
    const z = Cam.zoomPending; Cam.zoomPending = null;
    try { await Cam.track.applyConstraints({ advanced: [{ zoom: z }] }); } catch (e) {
      Cam.hwZoom = null; video.style.transform = z > 1 ? `scale(${z})` : '';
    }
  }
  Cam.zoomBusy = false;
}
zoomEl.addEventListener('input', () => setZoom(Number(zoomEl.value)));

/* Antippen und Zwei-Finger-Zoom im Kamerabild */
{
  let pinch = null, tapStart = null;
  const stage = $('#stage');
  const isUi = t => t.closest('button,input,.zoom,.cam-error');
  camEl.addEventListener('touchstart', e => {
    if (isUi(e.target)) return;
    if (e.touches.length === 2) {
      const [a, b] = e.touches;
      pinch = { d: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY), z: Cam.zoom };
      tapStart = null;
    } else if (e.touches.length === 1) {
      tapStart = { x: e.touches[0].clientX, y: e.touches[0].clientY, t: Date.now() };
    }
  }, { passive: true });
  camEl.addEventListener('touchmove', e => {
    if (pinch && e.touches.length === 2) {
      const [a, b] = e.touches;
      const d = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
      setZoom(pinch.z * d / pinch.d);
    } else if (tapStart && e.touches.length === 1) {
      const t = e.touches[0];
      if (Math.hypot(t.clientX - tapStart.x, t.clientY - tapStart.y) > 12) tapStart = null;
    }
  }, { passive: true });
  camEl.addEventListener('touchend', e => {
    if (e.touches.length < 2) pinch = null;
    if (tapStart && e.touches.length === 0 && Date.now() - tapStart.t < 450 && !isUi(e.target)) {
      setTap(tapStart.x, tapStart.y);
    }
    tapStart = null;
  });
  stage.addEventListener('click', e => { if (!('ontouchstart' in window)) setTap(e.clientX, e.clientY); });
}
function setTap(x, y) {
  const r = camEl.getBoundingClientRect();
  Cam.tap = { fx: (x - r.left) / r.width - 0.5, fy: (y - r.top) / r.height - 0.5 };
  if (Math.abs(Cam.tap.fx) < 0.025 && Math.abs(Cam.tap.fy) < 0.02) Cam.tap = null;
  placeTapMark();
  haptic();
}
function placeTapMark() {
  const m = $('#tapMark');
  if (!Cam.tap) { m.hidden = true; camEl.classList.remove('tapped'); $('#tapReset').hidden = true; return; }
  const r = camEl.getBoundingClientRect();
  m.style.left = (r.width * (0.5 + Cam.tap.fx)) + 'px';
  m.style.top = (r.height * (0.5 + Cam.tap.fy)) + 'px';
  m.hidden = false; camEl.classList.add('tapped'); $('#tapReset').hidden = false;
}
$('#tapReset').addEventListener('click', () => { Cam.tap = null; placeTapMark(); });

/* Winkelversatz des angetippten Punkts gegenüber der Bildmitte */
function tapAngles() {
  if (!Cam.tap) return { dh: 0, de: 0 };
  const W = camEl.clientWidth, H = camEl.clientHeight;
  const vw = video.videoWidth || 1080, vh = video.videoHeight || 1920;
  const cover = Math.max(W / vw, H / vh);
  const css = Cam.hwZoom ? 1 : Cam.zoom;
  const hw = Cam.hwZoom ? Cam.zoom : 1;
  const f = (Math.max(vw, vh) / 2) / Math.tan(S.fov / 2 * D2R) * hw;
  const xv = Cam.tap.fx * W / (cover * css);
  const yv = Cam.tap.fy * H / (cover * css);
  return { dh: Math.atan(xv / f) * R2D, de: -Math.atan(yv / f) * R2D };
}

/* Kompassband zeichnen */
const tape = $('#tape'), tctx = tape.getContext('2d');
function drawTape(h) {
  const dpr = devicePixelRatio || 1;
  const W = tape.clientWidth, H = tape.clientHeight;
  if (tape.width !== Math.round(W * dpr) || tape.height !== Math.round(H * dpr)) { tape.width = Math.round(W * dpr); tape.height = Math.round(H * dpr); }
  tctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  tctx.clearRect(0, 0, W, H);
  if (h == null) return;
  const span = 100, ppd = W / span;
  tctx.lineCap = 'round';
  tctx.textAlign = 'center';
  tctx.shadowColor = 'rgba(0,0,0,.7)'; tctx.shadowBlur = 4;
  const start = Math.floor(h - span / 2), end = Math.ceil(h + span / 2);
  for (let d = start; d <= end; d++) {
    if (d % 5) continue;
    const x = W / 2 + sdiff(d - h) * ppd;
    const n = norm(d);
    const major = n % 15 === 0;
    tctx.strokeStyle = major ? 'rgba(255,255,255,.95)' : 'rgba(255,255,255,.55)';
    tctx.lineWidth = major ? 2 : 1.2;
    tctx.beginPath(); tctx.moveTo(x, 4); tctx.lineTo(x, major ? 16 : 11); tctx.stroke();
    if (major) {
      const card = { 0: 'N', 90: 'O', 180: 'S', 270: 'W', 45: 'NO', 135: 'SO', 225: 'SW', 315: 'NW' }[n];
      tctx.fillStyle = n === 0 ? '#FF5B3A' : '#fff';
      tctx.font = card ? '600 15px Roboto,system-ui,sans-serif' : '400 12px Roboto,system-ui,sans-serif';
      tctx.fillText(card || String(n), x, 36);
    }
  }
  tctx.shadowBlur = 0;
  tctx.fillStyle = '#FF5B3A';
  tctx.beginPath(); tctx.moveTo(W / 2 - 7, 0); tctx.lineTo(W / 2 + 7, 0); tctx.lineTo(W / 2, 10); tctx.closePath(); tctx.fill();
}

function loop() {
  if (!Cam.open) return;
  Cam.raf = requestAnimationFrame(loop);
  const alive = oriAlive();
  const shutter = $('#shutter');
  let hint = '', warn = false;
  if (alive) {
    const h = norm(vecHeading(O.cam) + corr());
    const el = vecElev(O.cam);
    const off = tapAngles();
    drawTape(h);
    $('#hdg').textContent = nf(norm(h + off.dh)) + '°';
    $('#hdgSub').textContent = `${dirName(h + off.dh)} · Höhe ${fmtSigned(el + off.de)}`;
    const j = jitterDeg();
    if (vecHoriz(O.cam) < 0.25) { hint = 'Handy nicht so steil nach oben halten'; warn = true; }
    else if (j > 4) { hint = 'Ruhig halten …'; warn = true; }
    else if (Cam.mode === 'calib' && calibTarget) hint = 'Kalibrieren: Fadenkreuz genau auf den gewählten Punkt richten, dann OK';
    else hint = Cam.tap ? 'Markierung gesetzt. OK übernimmt diese Richtung.' : 'Licht ins Fadenkreuz nehmen oder antippen';
  } else {
    drawTape(null);
    $('#hdg').textContent = '–––°';
    if (!O.seen && performance.now() > 4000) { $('#hdgSub').textContent = 'Kein Kompass gefunden'; hint = 'Dieses Gerät meldet keinen Kompass.'; warn = true; }
    else if (O.seen && !O.abs) { $('#hdgSub').textContent = 'Kompass ohne Nordbezug'; hint = 'Der Kompass liefert keine Nordrichtung.'; warn = true; }
    else $('#hdgSub').textContent = 'Kompass startet';
  }
  const ch = $('#camHint');
  ch.textContent = hint; ch.classList.toggle('warn', warn);
  shutter.disabled = !alive;
}
function updateGpsChip() {
  const c = $('#gpsChip');
  if (pos && Date.now() - pos.t < 30000) {
    c.textContent = 'GPS ±' + nf(Math.round(pos.acc)) + ' m';
    c.classList.toggle('bad', pos.acc > 50);
  } else { c.textContent = geoError || 'GPS sucht …'; c.classList.add('bad'); }
}
setInterval(updateGpsChip, 3000);

$('#shutter').addEventListener('click', capture);
function capture() {
  if (!oriAlive()) return toast('Kein Kompass-Signal');
  if (!pos) return toast('Warte auf GPS-Standort …');
  const v = meanCam(700);
  const off = tapAngles();
  const raw = norm(vecHeading(v) + decl + off.dh);
  const elev = vecElev(v) + off.de;
  haptic();

  if (Cam.mode === 'calib' && calibTarget) {
    const truth = bearingTo(pos, calibTarget);
    const newOff = sdiff(truth - raw);
    if (Math.abs(newOff) > 30) {
      toast('Abweichung über 30° – stimmt der Punkt? Kalibrierung verworfen.', 4000);
    } else {
      S.offset = Math.round(newOff * 10) / 10; saveS();
      toast(`Kompass korrigiert: ${fmtSigned(S.offset)}`);
      drawBearings();
    }
    closeCam();
    return;
  }

  const used = new Set(bearings.map(b => b.color));
  const color = COLORS.find(c => !used.has(c)) || COLORS[bearings.length % COLORS.length];
  const b = {
    id: Date.now(), t: Date.now(), lat: pos.lat, lon: pos.lon, acc: Math.round(pos.acc),
    brg: Math.round(norm(raw + S.offset) * 10) / 10, elev: Math.round(elev * 10) / 10, on: true, color
  };
  bearings.push(b); saveB();
  closeCam();
  drawBearings();
  fitAll();
  setTimeout(() => (lastFix && !lastFix.err) ? showFix() : showBearing(b.id), 350);
}

/* ================= Lebenszyklus ================= */
window.handleBack = () => {
  if (Cam.open) { closeCam(); return true; }
  if (!resEl.hidden) { resEl.hidden = true; qEl.blur(); return true; }
  if (calibPicking) { calibPicking = false; hideBanner(); return true; }
  if (closeSheet()) return true;
  return false;
};
window.onAppPause = () => {
  if (Cam.open && Cam.stream) { Cam.stream.getTracks().forEach(t => t.stop()); Cam.stream = null; Cam.track = null; }
};
window.onAppResume = () => {
  if (Cam.open && !Cam.stream) startStream();
  if (!pos || Date.now() - pos.t > 60000) startGeo();
};
document.addEventListener('visibilitychange', () => document.hidden ? window.onAppPause() : window.onAppResume());

drawBearings();
startGeo();
if (bearings.some(b => b.on)) fitAll();
if (!S.onboarded) setTimeout(showIntro, 400);
}
