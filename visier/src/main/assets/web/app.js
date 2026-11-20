{
'use strict';

/* ================= Grundlagen ================= */
const $ = s => document.querySelector(s);
const NATIVE = window.Native || null;
const R = 6371008.8, D2R = Math.PI / 180, R2D = 180 / Math.PI;
const REFF = R * 7 / 6;                 // Erdradius inkl. Lichtbrechung
const PITCH_ERR = 0.3;                  // typischer Fehler des Neigungswinkels in Grad (ruhig gehalten)
const BUILD_M = 3000, TALL_M = 15000;   // Suchweite für Gebäude bzw. hohe Bauwerke
const COLORS = ['#FF5B3A', '#3FC1FF', '#F5D547', '#B48CFF', '#4FE08A', '#FF7FBF'];
const DIRS = ['N','NNO','NO','ONO','O','OSO','SO','SSO','S','SSW','SW','WSW','W','WNW','NW','NNW'];

const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} }
};
const DEFAULTS = { layer: 'sat', lineKm: 100, errDeg: 3, fov: 66, offset: 0, eye: 1.6, onboarded: false };
const S = Object.assign({}, DEFAULTS, store.get('vs.settings', {}));
let targets = store.get('vs.targets', []);
const saveS = () => store.set('vs.settings', S);
const saveT = () => store.set('vs.targets', targets);

const nf = (v, d = 0) => Number(v).toLocaleString('de-DE', { minimumFractionDigits: d, maximumFractionDigits: d });
const norm = a => ((a % 360) + 360) % 360;
const sdiff = a => { a = norm(a); return a > 180 ? a - 360 : a; };
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const dirName = b => DIRS[Math.round(norm(b) / 22.5) % 16];
const fmtDist = m => m < 1000 ? nf(Math.round(m / (m < 100 ? 1 : 5)) * (m < 100 ? 1 : 5)) + ' m' : nf(m / 1000, m < 10000 ? 2 : 1) + ' km';
const fmtCoord = p => p.lat.toFixed(5) + ', ' + p.lon.toFixed(5);
const fmtTime = t => new Date(t).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
const fmtSigned = (v, d = 1) => { const r = Math.round(v * 10 ** d) / 10 ** d; return (r > 0 ? '+' : r < 0 ? '−' : '±') + nf(Math.abs(r), d) + '°'; };
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
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
  (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject()).then(() => toast('Kopiert'), () => toast(text, 5000));
}
function shareText(text) {
  if (NATIVE) { try { NATIVE.share(text); return; } catch (e) {} }
  copyText(text);
}
async function fetchJSON(url, opt = {}, ms = 20000) {
  const ctl = new AbortController();
  const tm = setTimeout(() => ctl.abort(), ms);
  try {
    const r = await fetch(url, Object.assign({}, opt, { signal: ctl.signal }));
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return await r.json();
  } finally { clearTimeout(tm); }
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
function plane(ref) {
  const k = Math.cos(ref.lat * D2R);
  return {
    f: p => ({ x: (p.lon - ref.lon) * D2R * R * k, y: (p.lat - ref.lat) * D2R * R }),
    i: q => ({ lat: ref.lat + q.y / R * R2D, lon: ref.lon + q.x / (R * k) * R2D })
  };
}
const unit = b => ({ x: Math.sin(b * D2R), y: Math.cos(b * D2R) });
function pointInRing(ring, p) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const a = ring[i], b = ring[j];
    if ((a.y > p.y) !== (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside;
  }
  return inside;
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
  if (first && !targets.some(t => t.on)) map.setView(ll(pos), Math.max(map.getZoom(), 16));
  if (follow) map.panTo(ll(pos), { animate: true });
  updateGpsChip();
}
window.onNativePermissions = () => { startGeo(); if (Cam.open && !Cam.stream) startStream(); };

/* ================= Kompass ================= */
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
  O.cam = O.cam ? mix(O.cam, cam, 0.2) : cam;
  O.top = O.top ? mix(O.top, top, 0.2) : top;
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

/* ================= Zielerkennung ================= */
/* Gebäude, Türme und Gelände entlang der Blickrichtung laden und per Sichtstrahl prüfen,
   welches Objekt von hier aus im Fadenkreuz zu sehen ist. */
const BUILDING_DE = {
  house: 'Wohnhaus', detached: 'Einfamilienhaus', semidetached_house: 'Doppelhaushälfte', terrace: 'Reihenhaus',
  apartments: 'Mehrfamilienhaus', residential: 'Wohngebäude', bungalow: 'Bungalow', farm: 'Bauernhaus',
  farm_auxiliary: 'Landwirtschaftliches Gebäude', barn: 'Scheune', stable: 'Stall', cowshed: 'Stall', sty: 'Stall',
  garage: 'Garage', garages: 'Garagen', carport: 'Carport', shed: 'Schuppen', hut: 'Hütte', cabin: 'Hütte', roof: 'Überdachung',
  industrial: 'Industriegebäude', manufacture: 'Fabrik', warehouse: 'Lagerhalle', commercial: 'Geschäftsgebäude',
  retail: 'Geschäft', supermarket: 'Supermarkt', kiosk: 'Kiosk', office: 'Bürogebäude', school: 'Schule',
  kindergarten: 'Kindergarten', university: 'Universität', college: 'Hochschule', hospital: 'Krankenhaus',
  church: 'Kirche', cathedral: 'Dom', chapel: 'Kapelle', mosque: 'Moschee', synagogue: 'Synagoge', temple: 'Tempel',
  religious: 'Religiöses Gebäude', public: 'Öffentliches Gebäude', civic: 'Öffentliches Gebäude', government: 'Behörde',
  train_station: 'Bahnhof', transportation: 'Verkehrsgebäude', hotel: 'Hotel', sports_hall: 'Sporthalle',
  sports_centre: 'Sportzentrum', stadium: 'Stadion', grandstand: 'Tribüne', greenhouse: 'Gewächshaus',
  service: 'Technikgebäude', transformer_tower: 'Trafoturm', silo: 'Silo', storage_tank: 'Tank', parking: 'Parkhaus',
  fire_station: 'Feuerwache', construction: 'Baustelle', ruins: 'Ruine', castle: 'Burg', tower: 'Turm',
  bunker: 'Bunker', boathouse: 'Bootshaus', gatehouse: 'Torhaus', pavilion: 'Pavillon', toilets: 'Toilettenhaus',
  bridge: 'Brücke', yes: 'Gebäude'
};
const MANMADE_DE = {
  tower: 'Turm', mast: 'Mast', chimney: 'Schornstein', water_tower: 'Wasserturm', silo: 'Silo',
  communications_tower: 'Fernmeldeturm', lighthouse: 'Leuchtturm', storage_tank: 'Tank'
};
const TOWERTYPE_DE = { communication: 'Funkturm', observation: 'Aussichtsturm', bell_tower: 'Glockenturm', cooling: 'Kühlturm', lighting: 'Lichtmast', defensive: 'Wehrturm', watchtower: 'Wachturm' };
const HEIGHT_DEF = {
  house: 8, detached: 8, semidetached_house: 8, terrace: 9, bungalow: 4.5, apartments: 15, residential: 10, farm: 9,
  farm_auxiliary: 7, barn: 9, stable: 6, cowshed: 7, garage: 3, garages: 3, carport: 3, shed: 3, hut: 4, cabin: 4, roof: 4,
  industrial: 10, manufacture: 12, warehouse: 10, commercial: 12, retail: 7, supermarket: 7, kiosk: 3, office: 16,
  school: 12, kindergarten: 6, university: 16, college: 14, hospital: 20, church: 28, cathedral: 50, chapel: 10,
  mosque: 18, synagogue: 15, temple: 12, public: 12, civic: 12, government: 14, train_station: 12, hotel: 18,
  sports_hall: 10, stadium: 25, greenhouse: 4, service: 4, transformer_tower: 9, silo: 22, storage_tank: 12,
  parking: 12, fire_station: 9, ruins: 6, castle: 20, tower: 25, bunker: 8, yes: 8,
  m_tower: 30, m_mast: 60, m_chimney: 60, m_water_tower: 35, m_silo: 25, m_communications_tower: 150, m_lighthouse: 30,
  m_storage_tank: 12, turbine: 160
};
const num = v => { const m = String(v == null ? '' : v).replace(',', '.').match(/-?\d+(\.\d+)?/); return m ? parseFloat(m[0]) : NaN; };

function describe(tags) {
  const name = tags.name || tags['name:de'] || tags.official_name || '';
  if (tags.power === 'generator') return { kind: 'turbine', type: 'Windrad', name, hkey: 'turbine' };
  if (tags.man_made && MANMADE_DE[tags.man_made]) {
    const t = tags.man_made === 'tower' && TOWERTYPE_DE[tags['tower:type']] ? TOWERTYPE_DE[tags['tower:type']] : MANMADE_DE[tags.man_made];
    return { kind: 'tower', type: t, name, hkey: 'm_' + tags.man_made };
  }
  const b = tags.building || 'yes';
  let type = BUILDING_DE[b] || 'Gebäude';
  if (tags.amenity === 'place_of_worship' && type === 'Gebäude') type = 'Gotteshaus';
  if (tags.shop && (b === 'yes' || b === 'retail' || b === 'commercial')) type = 'Geschäft';
  return { kind: 'building', type, name, hkey: HEIGHT_DEF[b] != null ? b : 'yes' };
}
function heightOf(tags, info) {
  let h = num(tags.height);
  if (h > 0) return { H: h, known: true };
  const lv = num(tags['building:levels']), rl = num(tags['roof:levels']);
  if (lv > 0) return { H: lv * 3 + (rl > 0 ? rl * 2.5 : 2.5), known: true };
  return { H: HEIGHT_DEF[info.hkey] || 8, known: false };
}
function addressOf(tags) {
  const st = tags['addr:street'] || tags['addr:place'];
  if (!st) return '';
  return st + (tags['addr:housenumber'] ? ' ' + tags['addr:housenumber'] : '') + (tags['addr:city'] ? ', ' + tags['addr:city'] : '');
}

const OVERPASS = [
  'https://overpass-api.de/api/interpreter',
  'https://overpass.private.coffee/api/interpreter',
  'https://maps.mail.ru/osm/tools/overpass/api/interpreter'
];
async function overpass(q) {
  let last;
  for (const ep of OVERPASS) {
    try {
      return await fetchJSON(ep, { method: 'POST', body: 'data=' + encodeURIComponent(q), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } }, 35000);
    } catch (e) { last = e; }
  }
  throw last || new Error('overpass');
}
function wedge(o, brg, half, len, n = 12) {
  const pts = [o];
  for (let k = 0; k <= n; k++) pts.push(dest(o, brg - half + 2 * half * k / n, len));
  return pts;
}
const polyStr = pts => pts.map(p => p.lat.toFixed(6) + ' ' + p.lon.toFixed(6)).join(' ');

async function loadObjects(shot) {
  const half = Math.min(12, S.errDeg * 2 + 0.8);
  const pB = polyStr(wedge(shot, shot.brg, half, BUILD_M));
  const pT = polyStr(wedge(shot, shot.brg, half, TALL_M));
  const q = `[out:json][timeout:30];(
way["building"](poly:"${pB}");
relation["building"]["type"="multipolygon"](poly:"${pB}");
nwr["man_made"~"^(tower|mast|chimney|water_tower|silo|communications_tower|lighthouse)$"](poly:"${pT}");
node["power"="generator"]["generator:source"="wind"](poly:"${pT}");
way["building"~"^(church|cathedral|chapel|mosque|castle|tower)$"](poly:"${pT}");
);out tags geom qt;`;
  const js = await overpass(q);
  return parseObjects(js, shot);
}
function parseObjects(js, shot) {
  const P = plane(shot);
  const out = [], seen = new Set();
  for (const el of (js && js.elements) || []) {
    const key = el.type[0] + el.id;
    if (seen.has(key)) continue;
    seen.add(key);
    const tags = el.tags || {};
    let rings = [], pt = null, ll0 = null;
    if (el.type === 'way' && el.geometry) { rings = [el.geometry.map(P.f)]; ll0 = [el.geometry]; }
    else if (el.type === 'relation' && el.members) {
      const outer = el.members.filter(m => m.type === 'way' && m.geometry && m.role !== 'inner');
      rings = outer.map(m => m.geometry.map(P.f)); ll0 = outer.map(m => m.geometry);
    } else if (el.type === 'node' && el.lat != null) pt = P.f(el);
    if (!rings.length && !pt) continue;
    if (!pt && rings.some(r => r.length > 2 && pointInRing(r, { x: 0, y: 0 }))) continue;
    const info = describe(tags);
    const h = heightOf(tags, info);
    const o = Object.assign({ key, tags, rings, pt, latlngs: ll0, H: h.H, hKnown: h.known, r: pt ? 5 : 0, addr: addressOf(tags) }, info);
    const vs = pt ? [pt] : rings.flat();
    let amin = Infinity, amax = -Infinity, dmin = Infinity;
    for (const v of vs) {
      const a = sdiff(Math.atan2(v.x, v.y) * R2D - shot.brg);
      if (a < amin) amin = a; if (a > amax) amax = a;
      dmin = Math.min(dmin, Math.hypot(v.x, v.y));
    }
    if (pt) { const pad = Math.atan2(o.r, Math.max(1, dmin)) * R2D; amin -= pad; amax += pad; }
    if (amax - amin > 180) { amin = -180; amax = 180; }
    Object.assign(o, { amin, amax, dmin });
    out.push(o);
  }
  return out;
}
function rayHit(o, u) {
  if (o.pt) {
    const b = o.pt.x * u.x + o.pt.y * u.y;
    if (b <= 0) return null;
    const c2 = o.pt.x * o.pt.x + o.pt.y * o.pt.y - b * b;
    if (c2 > o.r * o.r) return null;
    const h = Math.sqrt(o.r * o.r - c2);
    return { a: Math.max(0, b - h), b: b + h };
  }
  const ts = [];
  for (const ring of o.rings) for (let i = 0; i < ring.length - 1; i++) {
    const p = ring[i], q = ring[i + 1], ex = q.x - p.x, ey = q.y - p.y;
    const den = u.x * ey - u.y * ex;
    if (Math.abs(den) < 1e-12) continue;
    const t = (p.x * ey - p.y * ex) / den, s = (p.x * u.y - p.y * u.x) / den;
    if (t > 0 && s >= 0 && s <= 1) ts.push(t);
  }
  if (!ts.length) return null;
  ts.sort((a, b) => a - b);
  return { a: ts[0], b: ts.length > 1 ? ts[1] : ts[0] + 4 };
}

async function loadProfile(shot) {
  const far = Math.max(4000, Math.min(S.lineKm * 1000, 80000));
  const ds = [];
  for (let i = 0; i < 50; i++) ds.push(BUILD_M * (i / 49) ** 1.6);
  for (let i = 1; i <= 50; i++) ds.push(BUILD_M + (far - BUILD_M) * (i / 50) ** 1.3);
  const pts = ds.map(d => dest(shot, shot.brg, d));
  const url = 'https://api.open-meteo.com/v1/elevation?latitude=' + pts.map(p => p.lat.toFixed(5)).join(',') + '&longitude=' + pts.map(p => p.lon.toFixed(5)).join(',');
  const js = await fetchJSON(url, {}, 20000);
  const e = js.elevation;
  if (!Array.isArray(e) || e.length !== ds.length) throw new Error('profile');
  return { d: ds, g: e.map(v => v - e[0]), g0: e[0] };
}
function gAt(prof, d) {
  if (!prof) return 0;
  const D = prof.d, G = prof.g;
  if (d <= 0) return 0;
  if (d >= D[D.length - 1]) return G[G.length - 1];
  let lo = 0, hi = D.length - 1;
  while (hi - lo > 1) { const m = (lo + hi) >> 1; if (D[m] <= d) lo = m; else hi = m; }
  return G[lo] + (G[hi] - G[lo]) * (d - D[lo]) / (D[hi] - D[lo] || 1);
}
function lateral(shot, pt) {
  const q = plane(shot).f(pt), u = unit(shot.brg);
  return q.x * u.y - q.y * u.x;      // > 0: rechts der Linie
}
const sideTxt = c => c.side == null || Math.abs(c.side) < 3 ? 'auf der Linie' : fmtDist(Math.abs(c.side)) + (c.side > 0 ? ' rechts' : ' links');
const rayZ = (d, e) => S.eye + d * Math.tan(e * D2R) + d * d / (2 * REFF);   // Strahlhöhe über dem (gekrümmten) Boden
/* Wo trifft der Sichtstrahl das Gelände? slack > 0 = nur eindeutige Treffer */
function terrainCross(prof, e, slack = 0) {
  if (!prof) return null;
  let prev = null;
  for (let i = 0; i < prof.d.length; i++) {
    const d = prof.d[i];
    if (d < 25) continue;
    const s = slack ? slack * (4 + d * Math.tan(PITCH_ERR * D2R)) : 0;
    const f = prof.g[i] - s - rayZ(d, e);
    if (f >= 0) {
      if (!prev) return d;
      return prev.d + (d - prev.d) * (-prev.f) / (f - prev.f || 1);
    }
    prev = { d, f };
  }
  return null;
}
const erf = x => {
  const t = 1 / (1 + 0.3275911 * Math.abs(x));
  const y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
  return x >= 0 ? y : -y;
};
const Phi = x => 0.5 * (1 + erf(x / Math.SQRT2));

/* Fächer aus Sichtstrahlen um die Peilrichtung. Für jedes Objekt wird geprüft, welcher Teil
   davon von hier aus sichtbar ist (Verdeckung durch Näheres und durch Gelände), und wie gut
   gemessene Richtung UND Höhenwinkel dazu passen. Das passendste Objekt gewinnt. */
const angOf = (z, d) => Math.atan((z - S.eye - d * d / (2 * REFF)) / d) * R2D;
function rankTargets(shot, objs, prof) {
  const P = plane(shot);
  const sAz = Math.max(0.5, S.errDeg * 0.6), step = 0.1;
  const K = Math.ceil(2.5 * sAz / step);
  const minD = Math.max(12, Math.min(shot.acc || 10, 40));
  const e = shot.elev;
  let sky = -90, skyD = null;
  const cum = [];
  if (prof) prof.d.forEach((d, i) => {
    if (d >= minD) { const an = angOf(prof.g[i], d); if (an > sky) { sky = an; skyD = d; } }
    cum.push(sky);
  });
  const terrUpTo = d => {
    if (!prof || d <= prof.d[0]) return -90;
    let lo = 0, hi = prof.d.length - 1;
    if (d >= prof.d[hi]) return cum[hi];
    while (hi - lo > 1) { const m = (lo + hi) >> 1; if (prof.d[m] <= d) lo = m; else hi = m; }
    return cum[lo];
  };
  /* Pro Objekt: Anteil der Mess-Unsicherheit (Richtung × Höhe), der auf seinen sichtbaren Umriss fällt */
  const acc = new Map();
  const consider = (key, m, fit, data) => {
    let c = acc.get(key);
    if (!c) { c = Object.assign({ m: 0, best: -1, fit: 0 }, data); acc.set(key, c); }
    c.m += m;
    if (fit > c.fit) c.fit = fit;
    if (m > c.best) { c.best = m; Object.assign(c, data); }
  };
  const band = (lo, hi, sEl) => Phi((hi - e) / sEl) - Phi((lo - e) / sEl);
  const fitOf = (lo, hi, sEl, wa) => { const de = e < lo ? lo - e : e > hi ? e - hi : 0; return Math.exp(-0.5 * (wa + (de / sEl) ** 2)); };
  for (let k = -K; k <= K; k++) {
    const a = k * step, u = unit(shot.brg + a), wa = (a / sAz) ** 2, w = Math.exp(-0.5 * wa);
    const hits = [];
    for (const o of objs) {
      if (a < o.amin - 0.02 || a > o.amax + 0.02) continue;
      const h = rayHit(o, u);
      if (!h || h.a < minD) continue;
      if (h.a > (o.kind === 'building' && !/^(Kirche|Dom|Kapelle|Moschee|Burg|Turm)$/.test(o.type) ? BUILD_M : TALL_M)) continue;
      hits.push({ o, h });
    }
    hits.sort((x, y) => x.h.a - y.h.a);
    let occ = -90;
    for (const { o, h } of hits) {
      const d = h.a, g = gAt(prof, d);
      const top = angOf(g + o.H, d), bot = angOf(g, d);
      const lo = Math.max(bot, occ, terrUpTo(d));
      if (top > lo) {
        const hu = Math.atan((o.hKnown ? 0.08 : 0.22) * o.H / d) * R2D;
        const sEl = Math.hypot(PITCH_ERR, hu);
        const m = w * band(lo, top, sEl);
        if (m > 1e-6) consider(o.key, m, fitOf(lo, top, sEl, wa), { kind: o.kind, o, ray: a, h });
      }
      if (top > occ) occ = top;
      if (occ > e + 4 * PITCH_ERR) break;
    }
    if (prof && sky > occ) {
      const sEl = Math.hypot(PITCH_ERR, 0.3);
      const m = 0.7 * w * band(occ, sky, sEl);
      if (m > 1e-6) consider('terrain', m, fitOf(occ, sky, sEl, wa), { kind: 'terrain', ray: a });
    }
  }
  const tBest = terrainCross(prof, e, 0);
  const list = [...acc.entries()].map(([key, c]) => {
    const p = c.m;
    if (c.kind === 'terrain') {
      const dT = tBest || skyD;
      const dHi = terrainCross(prof, e + PITCH_ERR);
      const vague = !tBest || !dHi || dHi > tBest * 1.8;
      return {
        key, kind: 'terrain', fit: c.fit, p: vague ? p * 0.7 : p, pt: dest(shot, shot.brg, dT), d: dT, side: 0, name: '',
        type: vague ? 'Gelände am Horizont' : 'Gelände', H: null, range: [tBest, dHi], addr: '',
        alt: Math.round(prof.g0 + gAt(prof, dT))
      };
    }
    const o = c.o;
    let pt;
    if (o.pt) pt = P.i(o.pt);
    else {
      const u = unit(shot.brg + c.ray);
      const t = c.h.a + Math.min(Math.max(0, (c.h.b - c.h.a) / 2), 12);
      pt = P.i({ x: u.x * t, y: u.y * t });
    }
    return {
      key, kind: o.kind, fit: c.fit, p, pt, d: dist(shot, pt), side: lateral(shot, pt), name: o.name, type: o.type, H: o.H, hKnown: o.hKnown,
      addr: o.addr, poly: o.latlngs ? o.latlngs.map(r => r.map(q => [q.lat, q.lon])) : null
    };
  }).sort((a, b) => b.p - a.p);
  const top = list.length ? list[0].p : 0;
  const keep = list.filter(c => c.p >= Math.max(0.01, top * 0.08)).slice(0, 6);
  const sum = keep.reduce((a, c) => a + c.p, 0) || 1;
  keep.forEach(c => { c.p = c.p / sum; });
  return keep;
}

/* ================= Karte ================= */
const LAYERS = {
  sat: { name: 'Satellit', sub: 'Luftbild mit Orten und Straßen', labels: true,
    url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
    attr: 'Luftbild © Esri, Maxar, Earthstar Geographics', native: 19 },
  satplain: { name: 'Satellit pur', sub: 'Nur Luftbild, ohne Beschriftung',
    url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
    attr: 'Luftbild © Esri, Maxar, Earthstar Geographics', native: 19 },
  osm: { name: 'Straßenkarte', sub: 'OpenStreetMap, mit Hausnummern',
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
const view = store.get('vs.view', null);
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
  baseLayer = L.tileLayer(d.url, { attribution: d.attr, maxNativeZoom: d.native, maxZoom: 21, subdomains: d.subs || 'abc' }).addTo(map);
  if (d.labels) labelLayers = LABEL_URLS.map(u => L.tileLayer(u, { maxNativeZoom: 19, maxZoom: 21, pane: 'overlayPane', opacity: .95 }).addTo(map));
}
map.createPane('wedges').style.zIndex = 390;
map.createPane('lines').style.zIndex = 410;
map.createPane('shapes').style.zIndex = 420;
map.createPane('marks').style.zIndex = 620;
setLayer(S.layer);

const gLines = L.layerGroup().addTo(map);
const gCand = L.layerGroup().addTo(map);
const gPin = L.layerGroup().addTo(map);
let meMarker = null, meCircle = null, follow = false;

map.on('moveend zoomend', () => {
  store.set('vs.view', { c: [map.getCenter().lat, map.getCenter().lng], z: map.getZoom() });
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
  el.classList.add('has-hdg');
  el.querySelector('.cone').style.transform = `rotate(${norm(vecHeading(v) + corr())}deg)`;
}
setInterval(() => { if (!Cam.open) updateMeHeading(); }, 120);

const TGT_SVG = c => `<svg viewBox="0 0 40 40"><circle cx="20" cy="20" r="12" fill="none" stroke="#fff" stroke-width="3.5"/><circle cx="20" cy="20" r="12" fill="none" stroke="${c}" stroke-width="2"/><path d="M20 1v9M20 30v9M1 20h9M30 20h9" stroke="#fff" stroke-width="2.5" stroke-linecap="round"/><circle cx="20" cy="20" r="3.4" fill="${c}" stroke="#fff" stroke-width="1.5"/></svg>`;
const TICKS = [1, 2, 5, 10, 20, 50, 100, 200, 300, 500];

/* Alle gespeicherten Ziele zeichnen: lange Linie + Punkt auf dem Ziel */
function drawTargets() {
  gLines.clearLayers();
  const len = S.lineKm * 1000;
  for (const t of targets) {
    if (!t.on) continue;
    const active = cur && cur.id === t.id;
    const pts = [];
    const n = Math.max(80, Math.round(S.lineKm / 2));
    for (let k = 0; k <= n; k++) pts.push(ll(dest(t, t.brg, len * k / n)));
    L.polyline(pts, { pane: 'lines', color: '#000', weight: active ? 6 : 5, opacity: .35, interactive: false }).addTo(gLines);
    L.polyline(pts, { pane: 'lines', color: t.color, weight: active ? 2.8 : 2, opacity: active ? 1 : .75 }).on('click', ev => { L.DomEvent.stop(ev); showTarget(t.id); }).addTo(gLines);
    L.polyline(pts, { pane: 'lines', opacity: 0, weight: 24 }).on('click', ev => { L.DomEvent.stop(ev); showTarget(t.id); }).addTo(gLines);
    L.circleMarker(ll(t), { pane: 'marks', radius: 5, color: '#fff', weight: 2.5, fillColor: t.color, fillOpacity: 1, interactive: false }).addTo(gLines);
    TICKS.filter(km => km < S.lineKm).forEach(km => {
      L.marker(ll(dest(t, t.brg, km * 1000)), { icon: L.divIcon({ className: 'tick', html: `<span>${km} km</span>`, iconSize: [0, 0] }), pane: 'lines', interactive: false, keyboard: false }).addTo(gLines);
    });
    if (t.sel && !active) {
      if (t.sel.poly) L.polygon(t.sel.poly, { pane: 'shapes', color: '#fff', weight: 1.5, fillColor: t.color, fillOpacity: .35 }).on('click', ev => { L.DomEvent.stop(ev); showTarget(t.id); }).addTo(gLines);
      L.marker(ll(t.sel.pt), { icon: L.divIcon({ className: 'tgt small', html: TGT_SVG(t.color), iconSize: [0, 0] }), pane: 'marks' }).on('click', ev => { L.DomEvent.stop(ev); showTarget(t.id); }).addTo(gLines);
    }
  }
  drawCandidates();
  const n = targets.length;
  $('#count').hidden = !n; $('#count').textContent = n;
}
/* Kandidaten der aktuellen Analyse: gewähltes Ziel kräftig, Alternativen dezent und antippbar */
function drawCandidates() {
  gCand.clearLayers();
  if (!cur) return;
  const t = targets.find(x => x.id === cur.id);
  if (!t || !t.on) return;
  (cur.cands || []).forEach((c, i) => {
    if (i === cur.sel) return;
    if (c.poly) L.polygon(c.poly, { pane: 'shapes', color: '#fff', weight: 1.2, dashArray: '3 3', fillColor: '#fff', fillOpacity: .1 }).on('click', ev => { L.DomEvent.stop(ev); selectCand(i); }).addTo(gCand);
    else L.circleMarker(ll(c.pt), { pane: 'shapes', radius: 7, color: '#fff', weight: 1.5, dashArray: '3 3', fillOpacity: .1, fillColor: '#fff' }).on('click', ev => { L.DomEvent.stop(ev); selectCand(i); }).addTo(gCand);
  });
  const s = t.sel;
  if (!s) return;
  if (s.poly) L.polygon(s.poly, { pane: 'shapes', color: '#fff', weight: 2, fillColor: t.color, fillOpacity: .45, interactive: false }).addTo(gCand);
  L.marker(ll(s.pt), { icon: L.divIcon({ className: 'tgt', html: TGT_SVG(t.color), iconSize: [0, 0] }), pane: 'marks', interactive: false }).addTo(gCand);
}

function insetsPx() {
  const cs = getComputedStyle(document.documentElement);
  return { st: parseFloat(cs.getPropertyValue('--st')) || 0, sb: parseFloat(cs.getPropertyValue('--sb')) || 0 };
}
function fitTo(b, maxZoom = 18) {
  const { st, sb } = insetsPx();
  map.fitBounds(b, { paddingTopLeft: [36, st + 80], paddingBottomRight: [36, Math.min(innerHeight * .6, 560) + 24], maxZoom });
}
function fitTarget(t) {
  if (!t.sel) return fitTo(L.latLngBounds([ll(t), ll(dest(t, t.brg, Math.min(S.lineKm, 5) * 1000))]));
  const b = L.latLngBounds([ll(t), ll(t.sel.pt)]);
  if (t.sel.poly) t.sel.poly.flat().forEach(p => b.extend(p));
  fitTo(b);
}

/* ================= Sheet ================= */
const sheet = $('#sheet'), scrim = $('#scrim');
let sheetKind = null;
function openSheet(kind, html, modal = true) {
  sheetKind = kind;
  const body = $('#sheetBody');
  const keepScroll = sheet.classList.contains('open') && body.dataset.kind === kind ? body.scrollTop : 0;
  body.dataset.kind = kind;
  body.innerHTML = html;
  body.scrollTop = keepScroll;
  sheet.classList.add('open'); sheet.setAttribute('aria-hidden', 'false');
  sheet.classList.toggle('peek', !modal);
  scrim.hidden = !modal;
  if (modal) requestAnimationFrame(() => scrim.classList.add('show'));
  else scrim.classList.remove('show');
}
function closeSheet() {
  if (!sheetKind) return false;
  sheetKind = null;
  $('#sheetBody').dataset.kind = '';
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
$('#sheetBody').addEventListener('input', e => { const t = e.target; if (t.dataset.set) SETTERS[t.dataset.set](t, false); });
$('#sheetBody').addEventListener('change', e => { const t = e.target; if (t.dataset.set) SETTERS[t.dataset.set](t, true); });
{
  let y0 = null, dy = 0;
  sheet.addEventListener('touchstart', e => {
    if (($('#sheetBody').scrollTop > 0 && !e.target.closest('.grab')) || e.target.closest('input[type=range]')) { y0 = null; return; }
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
  return `<div class="sh-head">${color ? `<span class="swatch" style="background:${color}"></span>` : ''}<div style="flex:1;min-width:0"><h2>${title}</h2>${sub ? `<p>${sub}</p>` : ''}</div><button class="x" data-a="close" aria-label="Schließen">${icon('close')}</button></div>`;
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

/* ================= Analyse-Ablauf ================= */
let cur = null;          // { id, cands, sel, status, step, errors }
let runSeq = 0;
async function analyze(t) {
  const seq = ++runSeq;
  cur = { id: t.id, cands: [], sel: -1, status: 'busy', step: 0, err: [] };
  drawTargets();
  showBusy(t);
  const shot = { lat: t.lat, lon: t.lon, brg: t.brg, elev: t.elev, acc: t.acc };
  let objs = [], prof = null;
  const pObj = loadObjects(shot).then(r => { objs = r; }, () => { cur.err.push('osm'); });
  const pProf = loadProfile(shot).then(r => { prof = r; }, () => { cur.err.push('dem'); });
  await pProf; if (seq !== runSeq) return;
  cur.step = 1; if (sheetKind === 'busy') showBusy(t);
  await pObj; if (seq !== runSeq) return;
  cur.step = 2; if (sheetKind === 'busy') showBusy(t);
  await new Promise(r => setTimeout(r, 30));
  const cands = rankTargets(shot, objs, prof);
  if (seq !== runSeq) return;
  cur.cands = cands;
  cur.status = 'done';
  cur.nObjs = objs.length;
  if (cands.length) applyCand(t, 0, false);
  else { t.sel = null; saveT(); }
  drawTargets();
  fitTarget(t);
  if (sheetKind === 'busy' || sheetKind === 'result') showTarget(t.id);
  if (cands.length) haptic();
}
function applyCand(t, i, save = true) {
  const c = cur.cands[i];
  cur.sel = i;
  t.sel = { pt: c.pt, d: c.d, kind: c.kind, name: c.name, type: c.type, H: c.H, hKnown: c.hKnown, p: c.p, addr: c.addr || '', poly: c.poly, range: c.range || null, alt: c.alt == null ? null : c.alt, fit: c.fit, manual: false };
  if (save) saveT(); else saveT();
  if (!t.sel.addr) reverseGeocode(t);
}
function selectCand(i) {
  const t = cur && targets.find(x => x.id === cur.id);
  if (!t || !cur.cands[i]) return;
  applyCand(t, i);
  haptic();
  drawTargets();
  showTarget(t.id);
}
const geoCache = new Map();
async function reverseGeocode(t) {
  const s = t.sel;
  if (!s) return;
  const key = s.pt.lat.toFixed(5) + ',' + s.pt.lon.toFixed(5);
  if (geoCache.has(key)) { s.addr = geoCache.get(key); saveT(); return; }
  try {
    const js = await fetchJSON(`https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1&accept-language=de&lat=${s.pt.lat}&lon=${s.pt.lon}`, {}, 12000);
    const a = js.address || {};
    const street = a.road || a.pedestrian || a.footway || a.path || a.hamlet || '';
    const place = a.village || a.town || a.city || a.suburb || a.municipality || '';
    let line = street ? street + (a.house_number ? ' ' + a.house_number : '') : '';
    if (place) line += (line ? ', ' : '') + (a.postcode ? a.postcode + ' ' : '') + place;
    if (!line) line = (js.display_name || '').split(', ').slice(0, 3).join(', ');
    geoCache.set(key, line);
    if (t.sel === s) {
      s.addr = line; saveT();
      if ((sheetKind === 'result') && cur && cur.id === t.id) showTarget(t.id);
    }
  } catch (e) {}
}

function showBusy(t) {
  const step = cur ? cur.step : 0;
  const li = (i, txt) => `<li class="${step > i ? 'done' : step === i ? 'on' : ''}">${txt}</li>`;
  openSheet('busy', `
    ${head('Ziel wird gesucht', `${nf(t.brg, 1)}° ${dirName(t.brg)} · Höhe ${fmtSigned(t.elev)}`, t.color)}
    <div class="busy"><i></i></div>
    <ul class="busy-steps">
      ${li(0, 'Geländehöhen entlang der Linie')}
      ${li(1, 'Gebäude und Türme in Blickrichtung')}
      ${li(2, 'Sichtlinie berechnen')}
    </ul>`, false);
}

function confText(p) { return p >= 0.6 ? 'hoch' : p >= 0.35 ? 'mittel' : 'gering'; }
function showTarget(id) {
  const t = targets.find(x => x.id === id);
  if (!t) return;
  const isCur = cur && cur.id === t.id;
  if (isCur && cur.status === 'busy') return showBusy(t);
  const s = t.sel;
  const here = pos ? dist(pos, t) : null;
  if (!s) {
    const offline = isCur && cur.err.length;
    openSheet('result', `
      ${head('Kein Ziel erkannt', `${nf(t.brg, 1)}° ${dirName(t.brg)} · ${fmtTime(t.t)}`, t.color)}
      <p class="note ${offline ? 'warn' : ''}">${offline ? 'Die Karten- und Geländedaten konnten nicht geladen werden. Prüf die Internetverbindung und versuch es nochmal.' : 'In dieser Richtung ist kein Gebäude und kein Gelände auf der Sichtlinie verzeichnet. Setz den Punkt selbst auf die Linie oder visier etwas tiefer an.'}</p>
      ${moverHtml(t, 500)}
      <div class="row2"><button class="btn" data-a="redo" data-id="${t.id}">Erneut suchen</button><button class="btn ghost" data-a="aim">Neu anvisieren</button></div>`, false);
    return;
  }
  const title = s.manual ? 'Eigener Punkt' : esc(s.name || s.type);
  const subParts = [];
  if (!s.manual && s.name) subParts.push(esc(s.type));
  subParts.push(s.addr ? esc(s.addr) : '<span style="opacity:.6">Adresse wird gesucht …</span>');
  let heightTxt = '–';
  if (s.kind === 'terrain' && s.alt != null) heightTxt = nf(s.alt) + ' m ü. NN';
  else if (s.H) heightTxt = (s.hKnown ? '' : 'ca. ') + nf(Math.round(s.H)) + ' m';
  let note = '';
  if (s.kind === 'terrain' && s.range && (!s.range[1] || s.range[1] > s.d * 1.8)) note = `<p class="note warn">Die Sichtlinie streift das Gelände sehr flach. Die Entfernung ist deshalb unsicher – der Punkt kann weiter hinten liegen.</p>`;
  else if (!s.manual && s.fit != null && s.fit < 0.3) note = `<p class="note warn">Nichts auf der Karte liegt genau im Fadenkreuz. Der Punkt ist die beste Annäherung – vielleicht fehlt das Gebäude in den Kartendaten. Du kannst ihn unten selbst verschieben.</p>`;
  else if (!s.manual && s.p < 0.45) note = `<p class="note warn">Mehrere Objekte kommen in Frage. Tipp unten ein anderes an, wenn der Punkt nicht passt.</p>`;
  const cands = isCur && cur.cands.length > 1 ? `<p class="sec-title">Andere mögliche Ziele auf der Linie</p><div class="cands">` + cur.cands.map((c, i) => `
      <button class="cand ${i === cur.sel && !s.manual ? 'sel' : ''}" data-a="cand" data-i="${i}">
        <i class="radio"></i>
        <span><b>${esc(c.name || c.type)}</b><small>${c.name ? esc(c.type) + ' · ' : ''}${fmtDist(c.d)} · ${sideTxt(c)}</small></span>
        <em>${nf(Math.round(c.p * 100))} %</em>
      </button>`).join('') + '</div>' : '';
  openSheet('result', `
    ${head(title, subParts.join(' · '), t.color)}
    <div class="stats">
      <div class="stat"><small>Entfernung</small><b class="num">${fmtDist(here != null && dist(pos, t) > 30 ? dist(pos, s.pt) : s.d)}</b></div>
      <div class="stat"><small>Richtung</small><b class="num">${nf(t.brg)}°<em>${dirName(t.brg)}</em></b></div>
      <div class="stat"><small>Wahrscheinlichkeit</small>${s.manual ? '<b>manuell</b>' : `<div class="conf"><b class="num">${nf(Math.round(s.p * 100))} %</b><span class="meter"><i style="width:${Math.round(s.p * 100)}%"></i></span></div>`}</div>
      <div class="stat"><small>${s.kind === 'terrain' ? 'Geländehöhe' : 'Höhe'}</small><b class="num">${heightTxt}</b></div>
      ${coordStat(s.pt)}
    </div>
    ${note}
    ${placeActions(s.pt)}
    ${cands}
    ${moverHtml(t, distToSlider(s.d))}
    <div class="row2"><button class="btn ghost" data-a="aim">Neu anvisieren</button><button class="btn ghost" data-a="del" data-id="${t.id}">Löschen</button></div>`, false);
}
/* Schieberegler entlang der Linie: logarithmisch von 10 m bis zur Linienlänge */
const sliderToDist = v => 10 * Math.pow(S.lineKm * 100, v / 1000);
const distToSlider = d => Math.round(clamp(Math.log(Math.max(10, d) / 10) / Math.log(S.lineKm * 100) * 1000, 0, 1000));
function moverHtml(t, v) {
  return `<div class="mover">
    <div class="set-head"><b>Punkt auf der Linie verschieben</b><output id="oMove">${fmtDist(sliderToDist(v))}</output></div>
    <input type="range" min="0" max="1000" step="1" value="${v}" data-set="move" data-id="${t.id}">
  </div>`;
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
  if (!targets.length) {
    openSheet('list', `${head('Ziele')}
      <div class="empty-state"><b>Noch nichts anvisiert</b>Tipp auf „Anvisieren“, nimm das Gebäude ins Fadenkreuz und bestätige mit OK. Visier wie mit einem Gewehr: genau auf die Stelle, die du meinst – auch die Höhe zählt.</div>
      <button class="btn" data-a="aim">Jetzt anvisieren</button>`);
    return;
  }
  const rows = targets.slice().reverse().map(t => {
    const s = t.sel;
    const title = s ? (s.manual ? 'Eigener Punkt' : esc(s.name || s.type)) : 'Kein Ziel erkannt';
    const sub = [fmtTime(t.t), nf(t.brg) + '° ' + dirName(t.brg), s ? fmtDist(s.d) : '', s && s.addr ? esc(s.addr) : ''].filter(Boolean).join(' · ');
    return `<li class="row ${t.on ? '' : 'off'}">
      <span class="swatch" style="background:${t.color}"></span>
      <button class="main" data-a="focus" data-id="${t.id}"><b>${title}</b><small>${sub}</small></button>
      <button class="mini" data-a="toggle" data-id="${t.id}" aria-label="${t.on ? 'Ausblenden' : 'Einblenden'}">${icon(t.on ? 'eye' : 'eyeoff')}</button>
      <button class="mini" data-a="del" data-id="${t.id}" aria-label="Löschen">${icon('trash')}</button>
    </li>`;
  }).join('');
  openSheet('list', `${head('Ziele', `${targets.length} gespeichert`)}<ul class="rows">${rows}</ul><button class="btn ghost" data-a="clearAll">Alle löschen</button>`);
}

function showLayers() {
  openSheet('layers', `${head('Kartenansicht')}` + Object.entries(LAYERS).map(([k, d]) => `
    <button class="opt ${S.layer === k ? 'sel' : ''}" data-a="layer" data-k="${k}">
      <span class="prev ${k}"></span><span><b>${d.name}</b><small>${d.sub}</small></span><i class="radio"></i>
    </button>`).join(''));
}

function showSettings() {
  const ver = (() => { try { return NATIVE ? NATIVE.version() : ''; } catch (e) { return ''; } })();
  openSheet('settings', `${head('Optionen')}
    <div class="set">
      <div class="set-head"><b>Länge der Linie</b><output id="oLine">${nf(S.lineKm)} km</output></div>
      <p>So weit wird die Blicklinie auf der Karte gezogen.</p>
      <input type="range" min="5" max="500" step="5" value="${S.lineKm}" data-set="lineKm">
    </div>
    <div class="set">
      <div class="set-head"><b>Kompass-Unsicherheit</b><output id="oErr">±${nf(S.errDeg, 1)}°</output></div>
      <p>Wie breit links und rechts der Linie nach dem Ziel gesucht wird. Nach dem Kalibrieren reichen oft ±2°.</p>
      <input type="range" min="1" max="8" step="0.5" value="${S.errDeg}" data-set="errDeg">
    </div>
    <div class="set">
      <div class="set-head"><b>Augenhöhe</b><output id="oEye">${nf(S.eye, 1)} m</output></div>
      <p>Wie hoch du das Handy hältst – über dem Boden, auf dem du stehst. Auf einem Balkon entsprechend mehr.</p>
      <input type="range" min="0.5" max="40" step="0.1" value="${S.eye}" data-set="eye">
    </div>
    <div class="set">
      <div class="set-head"><b>Kompass-Korrektur</b><output id="oOff">${fmtSigned(S.offset)}</output></div>
      <p>Die Missweisung für deinen Ort wird automatisch eingerechnet (${pos ? fmtSigned(decl) : 'wartet auf GPS'}). Für beste Treffer einmal an einem bekannten Punkt kalibrieren.</p>
      <div class="two"><button class="btn" data-a="calib">Kalibrieren</button><button class="btn ghost" data-a="resetOff">Zurücksetzen</button></div>
    </div>
    <div class="set">
      <div class="set-head"><b>Kamera-Blickwinkel</b><output id="oFov">${nf(S.fov)}°</output></div>
      <p>Nur wichtig, wenn du im Kamerabild neben das Fadenkreuz tippst. Die meisten Hauptkameras haben 63–70°.</p>
      <input type="range" min="45" max="90" step="1" value="${S.fov}" data-set="fov">
    </div>
    <button class="btn ghost" data-a="intro">So funktioniert Visier</button>
    <p class="src">Daten: Gebäude und Türme © OpenStreetMap-Mitwirkende (ODbL) · Geländehöhen Copernicus DEM über Open-Meteo · Adressen Nominatim · Luftbilder Esri</p>
    <p class="ver">Visier ${esc(ver)}</p>`);
}
const SETTERS = {
  lineKm(t, done) { S.lineKm = Number(t.value); $('#oLine').textContent = nf(S.lineKm) + ' km'; saveS(); if (done) drawTargets(); },
  errDeg(t) { S.errDeg = Number(t.value); $('#oErr').textContent = '±' + nf(S.errDeg, 1) + '°'; saveS(); },
  eye(t) { S.eye = Number(t.value); $('#oEye').textContent = nf(S.eye, 1) + ' m'; saveS(); },
  fov(t) { S.fov = Number(t.value); $('#oFov').textContent = nf(S.fov) + '°'; saveS(); },
  move(el, done) {
    const t = targets.find(x => x.id === Number(el.dataset.id));
    if (!t) return;
    const d = sliderToDist(Number(el.value));
    $('#oMove').textContent = fmtDist(d);
    const pt = dest(t, t.brg, d);
    t.sel = { pt, d, kind: 'manual', name: '', type: 'Eigener Punkt', H: null, p: 1, addr: '', poly: null, manual: true };
    if (cur && cur.id === t.id) cur.sel = -1;
    drawTargets();
    if (done) { saveT(); reverseGeocode(t); showTarget(t.id); haptic(); }
  }
};

function showIntro() {
  openSheet('intro', `${head('So funktioniert Visier')}
    <ol class="steps">
      <li><b>Anvisieren</b>Tipp auf „Anvisieren“, halte das Handy aufrecht und nimm das Ziel genau ins Fadenkreuz – nicht nur die Richtung, auch die Höhe zählt. Zoomen hilft. Dann OK.</li>
      <li><b>Ziel wird erkannt</b>Visier prüft die Gebäude, Türme und Geländehöhen entlang deiner Blickrichtung und berechnet, was du von deinem Standort aus wirklich siehst. Darauf landet der Punkt.</li>
      <li><b>Prüfen und wechseln</b>Liegt der Punkt daneben, tipp ein anderes Gebäude aus der Liste oder auf der Karte an – oder schieb den Punkt selbst auf der Linie.</li>
    </ol>
    <p class="note">Für beste Treffer: einmal den Kompass kalibrieren (Optionen) und Abstand zu Autos und Metall halten.</p>
    <button class="btn" data-a="introDone">Los geht's</button>`);
}

const ACTIONS = {
  close: () => closeSheet(),
  aim: () => { closeSheet(); openCam('aim'); },
  cand: t => selectCand(Number(t.dataset.i)),
  redo: (t, id) => { const x = targets.find(y => y.id === id); if (x) analyze(x); },
  toggle: (t, id) => {
    const x = targets.find(y => y.id === id); if (!x) return;
    x.on = !x.on; saveT(); drawTargets();
    sheetKind === 'list' ? showList() : closeSheet();
  },
  del: (t, id) => {
    const i = targets.findIndex(x => x.id === id); if (i < 0) return;
    const [removed] = targets.splice(i, 1); saveT();
    if (cur && cur.id === id) cur = null;
    drawTargets();
    sheetKind === 'list' ? showList() : closeSheet();
    undoToast('Ziel gelöscht', () => { targets.splice(i, 0, removed); saveT(); drawTargets(); });
  },
  clearAll: () => {
    const old = targets.slice();
    targets = []; cur = null; saveT(); drawTargets(); closeSheet();
    undoToast('Alle Ziele gelöscht', () => { targets = old; saveT(); drawTargets(); });
  },
  focus: (t, id) => {
    const x = targets.find(y => y.id === id); if (!x) return;
    if (!x.on) { x.on = true; saveT(); }
    if (!cur || cur.id !== x.id) cur = { id: x.id, cands: [], sel: -1, status: 'stored', err: [] };
    drawTargets(); fitTarget(x); showTarget(id);
  },
  layer: t => { setLayer(t.dataset.k); showLayers(); setTimeout(closeSheet, 180); },
  gmaps: t => { const p = pFrom(t); openExternal(`https://www.google.com/maps/search/?api=1&query=${p.lat},${p.lon}`); },
  route: t => { const p = pFrom(t); openExternal(`https://www.google.com/maps/dir/?api=1&destination=${p.lat},${p.lon}`); },
  share: t => { const p = pFrom(t); shareText(`${fmtCoord(p)}\nhttps://www.google.com/maps/search/?api=1&query=${p.lat},${p.lon}`); },
  copy: t => copyText(fmtCoord(pFrom(t))),
  calib: () => startCalib(),
  calibHere: t => { closeSheet(); beginCalibAt(pFrom(t)); },
  resetOff: () => { S.offset = 0; saveS(); showSettings(); toast('Kompass-Korrektur zurückgesetzt'); },
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
  if (dist(pos, p) < 300) { hideBanner(); return toast('Zu nah – wähle einen Punkt weiter weg'); }
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
let lastLong = 0;
map.on('click', e => {
  if (Date.now() - lastLong < 800) return;
  const p = { lat: e.latlng.lat, lon: e.latlng.lng };
  if (calibPicking) return beginCalibAt(p);
  if (!resEl.hidden) { resEl.hidden = true; qEl.blur(); return; }
  if (sheetKind && sheetKind !== 'busy') closeSheet();
});
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
    const t = e.touches[0], r = el.getBoundingClientRect();
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
  if (follow) map.setView(ll(pos), Math.max(map.getZoom(), 17));
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
    const list = await fetchJSON(`https://nominatim.openstreetmap.org/search?format=jsonv2&limit=7&accept-language=de&q=${encodeURIComponent(q)}&viewbox=${center.lng - 2},${center.lat + 1.5},${center.lng + 2},${center.lat - 1.5}`, {}, 12000);
    if (seq !== searchSeq) return;
    if (!list.length) { resEl.innerHTML = '<li class="empty">Nichts gefunden</li>'; return; }
    resEl.innerHTML = list.map((x, i) => {
      const parts = x.display_name.split(', ');
      return `<li data-i="${i}"><b>${esc(parts[0])}</b><small>${esc(parts.slice(1, 4).join(', '))}</small></li>`;
    }).join('');
    resEl.onclick = e => {
      const li = e.target.closest('li[data-i]'); if (!li) return;
      const x = list[Number(li.dataset.i)], parts = x.display_name.split(', ');
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
  else map.setView(ll(p), 16);
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
    const stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 }, height: { ideal: 1080 } } });
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
  Cam.zoom = z; zoomEl.value = z;
  $('#zoomVal').textContent = nf(z, 1) + '×';
  if (Cam.hwZoom && Cam.track) { Cam.zoomPending = z; if (!Cam.zoomBusy) applyHwZoom(); }
  else video.style.transform = z > 1 ? `scale(${z})` : '';
  placeTapMark();
}
async function applyHwZoom() {
  Cam.zoomBusy = true;
  while (Cam.zoomPending !== null && Cam.track) {
    const z = Cam.zoomPending; Cam.zoomPending = null;
    try { await Cam.track.applyConstraints({ advanced: [{ zoom: z }] }); }
    catch (e) { Cam.hwZoom = null; video.style.transform = z > 1 ? `scale(${z})` : ''; }
  }
  Cam.zoomBusy = false;
}
zoomEl.addEventListener('input', () => setZoom(Number(zoomEl.value)));
{
  let pinch = null, tapStart = null;
  const isUi = t => t.closest('button,input,.zoom,.cam-error');
  camEl.addEventListener('touchstart', e => {
    if (isUi(e.target)) return;
    if (e.touches.length === 2) {
      const [a, b] = e.touches;
      pinch = { d: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY), z: Cam.zoom };
      tapStart = null;
    } else if (e.touches.length === 1) tapStart = { x: e.touches[0].clientX, y: e.touches[0].clientY, t: Date.now() };
  }, { passive: true });
  camEl.addEventListener('touchmove', e => {
    if (pinch && e.touches.length === 2) {
      const [a, b] = e.touches;
      setZoom(pinch.z * Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY) / pinch.d);
    } else if (tapStart && e.touches.length === 1) {
      const t = e.touches[0];
      if (Math.hypot(t.clientX - tapStart.x, t.clientY - tapStart.y) > 12) tapStart = null;
    }
  }, { passive: true });
  camEl.addEventListener('touchend', e => {
    if (e.touches.length < 2) pinch = null;
    if (tapStart && e.touches.length === 0 && Date.now() - tapStart.t < 450 && !isUi(e.target)) setTap(tapStart.x, tapStart.y);
    tapStart = null;
  });
  $('#stage').addEventListener('click', e => { if (!('ontouchstart' in window)) setTap(e.clientX, e.clientY); });
}
function setTap(x, y) {
  const r = camEl.getBoundingClientRect();
  Cam.tap = { fx: (x - r.left) / r.width - 0.5, fy: (y - r.top) / r.height - 0.5 };
  if (Math.abs(Cam.tap.fx) < 0.025 && Math.abs(Cam.tap.fy) < 0.02) Cam.tap = null;
  placeTapMark(); haptic();
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
function tapAngles() {
  if (!Cam.tap) return { dh: 0, de: 0 };
  const W = camEl.clientWidth, H = camEl.clientHeight;
  const vw = video.videoWidth || 1080, vh = video.videoHeight || 1920;
  const cover = Math.max(W / vw, H / vh);
  const css = Cam.hwZoom ? 1 : Cam.zoom, hw = Cam.hwZoom ? Cam.zoom : 1;
  const f = (Math.max(vw, vh) / 2) / Math.tan(S.fov / 2 * D2R) * hw;
  return { dh: Math.atan(Cam.tap.fx * W / (cover * css) / f) * R2D, de: -Math.atan(Cam.tap.fy * H / (cover * css) / f) * R2D };
}
const tape = $('#tape'), tctx = tape.getContext('2d');
function drawTape(h) {
  const dpr = devicePixelRatio || 1, W = tape.clientWidth, H = tape.clientHeight;
  if (tape.width !== Math.round(W * dpr) || tape.height !== Math.round(H * dpr)) { tape.width = Math.round(W * dpr); tape.height = Math.round(H * dpr); }
  tctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  tctx.clearRect(0, 0, W, H);
  if (h == null) return;
  const span = 100, ppd = W / span;
  tctx.lineCap = 'round'; tctx.textAlign = 'center';
  tctx.shadowColor = 'rgba(0,0,0,.7)'; tctx.shadowBlur = 4;
  for (let d = Math.floor(h - span / 2); d <= Math.ceil(h + span / 2); d++) {
    if (d % 5) continue;
    const x = W / 2 + sdiff(d - h) * ppd, n = norm(d), major = n % 15 === 0;
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
  tctx.shadowBlur = 0; tctx.fillStyle = '#FF5B3A';
  tctx.beginPath(); tctx.moveTo(W / 2 - 7, 0); tctx.lineTo(W / 2 + 7, 0); tctx.lineTo(W / 2, 10); tctx.closePath(); tctx.fill();
}
function loop() {
  if (!Cam.open) return;
  Cam.raf = requestAnimationFrame(loop);
  const alive = oriAlive();
  let hint = '', warn = false;
  if (alive) {
    const h = norm(vecHeading(O.cam) + corr()), el = vecElev(O.cam), off = tapAngles();
    drawTape(h);
    $('#hdg').textContent = nf(norm(h + off.dh)) + '°';
    $('#hdgSub').textContent = `${dirName(h + off.dh)} · Höhe ${fmtSigned(el + off.de)}`;
    if (vecHoriz(O.cam) < 0.25) { hint = 'Handy nicht so steil nach oben halten'; warn = true; }
    else if (jitterDeg() > 4) { hint = 'Ruhig halten …'; warn = true; }
    else if (Cam.mode === 'calib' && calibTarget) hint = 'Kalibrieren: Fadenkreuz genau auf den gewählten Punkt richten, dann OK';
    else hint = Cam.tap ? 'Markierung gesetzt. OK übernimmt genau diese Stelle.' : 'Ziel genau ins Fadenkreuz nehmen oder antippen';
  } else {
    drawTape(null);
    $('#hdg').textContent = '–––°';
    if (!O.seen && performance.now() > 4000) { $('#hdgSub').textContent = 'Kein Kompass gefunden'; hint = 'Dieses Gerät meldet keinen Kompass.'; warn = true; }
    else if (O.seen && !O.abs) { $('#hdgSub').textContent = 'Kompass ohne Nordbezug'; hint = 'Der Kompass liefert keine Nordrichtung.'; warn = true; }
    else $('#hdgSub').textContent = 'Kompass startet';
  }
  const ch = $('#camHint');
  ch.textContent = hint; ch.classList.toggle('warn', warn);
  $('#shutter').disabled = !alive;
}
function updateGpsChip() {
  const c = $('#gpsChip');
  if (pos && Date.now() - pos.t < 30000) { c.textContent = 'GPS ±' + nf(Math.round(pos.acc)) + ' m'; c.classList.toggle('bad', pos.acc > 30); }
  else { c.textContent = geoError || 'GPS sucht …'; c.classList.add('bad'); }
}
setInterval(updateGpsChip, 3000);

$('#shutter').addEventListener('click', capture);
function capture() {
  if (!oriAlive()) return toast('Kein Kompass-Signal');
  if (!pos) return toast('Warte auf GPS-Standort …');
  const v = meanCam(700), off = tapAngles();
  const raw = norm(vecHeading(v) + decl + off.dh);
  const elev = vecElev(v) + off.de;
  haptic();
  if (Cam.mode === 'calib' && calibTarget) {
    const newOff = sdiff(bearingTo(pos, calibTarget) - raw);
    if (Math.abs(newOff) > 30) toast('Abweichung über 30° – stimmt der Punkt? Kalibrierung verworfen.', 4000);
    else { S.offset = Math.round(newOff * 10) / 10; saveS(); toast(`Kompass korrigiert: ${fmtSigned(S.offset)}`); }
    closeCam();
    return;
  }
  const used = new Set(targets.slice(-COLORS.length).map(t => t.color));
  const color = COLORS.find(c => !used.has(c)) || COLORS[targets.length % COLORS.length];
  const t = {
    id: Date.now(), t: Date.now(), lat: pos.lat, lon: pos.lon, acc: Math.round(pos.acc),
    brg: Math.round(norm(raw + S.offset) * 10) / 10, elev: Math.round(elev * 100) / 100, on: true, color, sel: null
  };
  targets.push(t);
  if (targets.length > 40) targets.splice(0, targets.length - 40);
  saveT();
  closeCam();
  fitTo(L.latLngBounds([ll(t), ll(dest(t, t.brg, 600))]));
  analyze(t);
}

/* ================= Lebenszyklus ================= */
window.handleBack = () => {
  if (Cam.open) { closeCam(); return true; }
  if (!resEl.hidden) { resEl.hidden = true; qEl.blur(); return true; }
  if (calibPicking) { calibPicking = false; hideBanner(); return true; }
  if (closeSheet()) return true;
  return false;
};
window.onAppPause = () => { if (Cam.open && Cam.stream) { Cam.stream.getTracks().forEach(t => t.stop()); Cam.stream = null; Cam.track = null; } };
window.onAppResume = () => { if (Cam.open && !Cam.stream) startStream(); if (!pos || Date.now() - pos.t > 60000) startGeo(); };
document.addEventListener('visibilitychange', () => document.hidden ? window.onAppPause() : window.onAppResume());

/* Testzugang für die Rechenlogik */
window.__visier = { rankTargets, parseObjects, S };

drawTargets();
startGeo();
const lastOn = targets.filter(t => t.on).slice(-1)[0];
if (lastOn && lastOn.sel) { cur = { id: lastOn.id, cands: [], sel: -1, status: 'stored', err: [] }; drawTargets(); fitTarget(lastOn); }
if (!S.onboarded) setTimeout(showIntro, 400);
}
