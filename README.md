# Peilung & Visier

Zwei Android-Apps im gleichen Stil.

## Visier

Gebäude, Turm oder Hügel anvisieren und sofort sehen, wo genau das auf der Karte ist – mit nur einem Blick. Visier lädt Gebäudeumrisse und Türme (OpenStreetMap) sowie Geländehöhen (Copernicus DEM) entlang der Blickrichtung, berechnet, was von deinem Standort aus wirklich sichtbar ist (Verdeckung, Erdkrümmung), und setzt den Punkt auf das Objekt, das Richtung und Höhenwinkel am besten erklärt. Alternativen mit Wahrscheinlichkeit, Adresse, Google Maps, Route, Punkt manuell verschiebbar.

**Download:** [Visier.apk](../../releases/latest/download/Visier.apk)

## Peilung

Android-App: Licht oder Objekt in der Ferne mit der Kamera anpeilen, Richtung als Linie auf der Satellitenkarte sehen, mit einer zweiten Peilung von einem anderen Ort den genauen Punkt bestimmen.

**Download:** [Peilung.apk](../../releases/latest/download/Peilung.apk) – auf dem Handy antippen, installieren, fertig.

## Funktionen

- Kamera mit Fadenkreuz, Kompassband, Zoom (Regler oder zwei Finger) und Antippen des Ziels
- Richtung = Kompass + automatische Missweisung (World Magnetic Model) + eigene Kalibrierung
- Peillinie mit Unsicherheitsfächer und km-Markierungen auf der Karte
- Kreuzpeilung aus beliebig vielen Peilungen, mit Genauigkeitsfläche und geschätzter Höhe des Lichts
- Karten: Satellit (Esri), OpenStreetMap, OpenTopoMap, dunkle Karte
- Ortssuche (OpenStreetMap Nominatim) und Koordinaten-Eingabe
- Lang drücken auf die Karte: Entfernung, Richtung, Google Maps, Route, Teilen
- Kompass-Kalibrierung an einem sichtbaren Punkt (Kirchturm, Funkmast, Windrad)

## Aufbau

- `app/src/main/java/.../MainActivity.java` – nativer Rahmen: Rechte, Kamera/GPS-Freigabe, Missweisung, Teilen, Google-Maps-Absprung
- `app/src/main/assets/web/` – Oberfläche und Logik
- `.github/workflows/build.yml` – baut bei jedem Push die APK und veröffentlicht sie als Release
