# Spur

Deine eigene Kamera-App mit eigenem Server.
Ein Tipp, und das Foto ist gespeichert: mit Ort, Uhrzeit, Höhe und Blickrichtung. Offline landet es erst mal auf dem Handy und wird hochgeladen, sobald du wieder Internet hast. Alles liegt auf deinem Raspberry Pi, nicht bei irgendeiner Firma.

```
spur/
├── android/   → die App (Kotlin, Jetpack Compose)
├── server/    → der Server für den Raspberry Pi (Python, ohne Zusatzpakete)
└── .github/   → baut die APK automatisch auf GitHub
```

---

## Was die App kann

**Kamera**
- Tippen = Foto, Halten = Video (wie bei Snapchat). Wird **sofort** gespeichert, ohne Nachfrage.
- Live-Anzeige oben: GPS-Genauigkeit (grün/gelb/rot), Koordinaten, Kompass-Richtung.
- Blitz (aus/auto/an, bei Video Taschenlampe), Front-/Rückkamera, Zoom mit zwei Fingern, Tippen zum Scharfstellen, Raster.
- Lautstärketaste als Auslöser, Vibration, Schnellkachel in den Schnelleinstellungen, App-Verknüpfung (Icon lange drücken).

**Bei jeder Aufnahme gespeichert**
Datum, Uhrzeit (sekundengenau), Zeitzone, Breite/Länge, Genauigkeit in Metern, Höhe, Blickrichtung der Kamera (Kompass), Tempo, Adresse, Stadt, Land, Auflösung, Dateigröße, Videolänge, Gerät, Kamera.
Die GPS-Daten werden außerdem direkt in die Bilddatei (EXIF) geschrieben.
Kommt das GPS erst kurz nach dem Foto, trägt Spur den Standort automatisch nach (bis 3 Minuten).

**Offline und Upload**
- Ohne Internet bleibt alles auf dem Handy. Sobald WLAN oder mobile Daten da sind, lädt Spur automatisch hoch, auch im Hintergrund.
- Der Upload läuft in Stücken: Bricht die Verbindung ab, macht er genau an der Stelle weiter. Jede Datei wird per Prüfsumme kontrolliert.
- Drei Speicher-Modi: **Handy + Server**, **Nur Server** (nach dem Upload vom Handy löschen) oder **Nur Handy**.
- Nur über WLAN hochladen, Kopie in die Handy-Galerie, „Speicher freigeben“ mit einem Tipp.

**Karte**
- Alle Aufnahmen (auch Snapchat-Memories) auf der Karte. Tausende Punkte werden zu Gruppen zusammengefasst.
- Zeitfilter (Heute, 7 Tage, 30 Tage, Jahr). Bei „Heute“ wird deine Tagesroute als Linie gezeichnet.
- Kartenstile: Dunkel, Hell, Standard, Satellit. Dein Standort, „Alles zeigen“.

**Galerie**
- Nach Monaten sortiert. Filter: Favoriten, Videos, Spur, Snapchat, Ohne Ort, Nicht gesichert, Auf dem Handy.
- Suche nach Straße, Stadt, Land, Monat, Wochentag, Notiz oder Tag.
- Mehrfachauswahl (lange drücken): Favorit oder Löschen (nur vom Handy oder überall).

**Detailansicht**
Wischen, Zoomen (zwei Finger oder Doppeltipp), Videos abspielen, alle Metadaten, Mini-Karte, Koordinaten kopieren, Notiz und Tags, teilen, in Google Maps öffnen, vom Server aufs Handy laden, löschen.

**Rückblick**
„Heute vor X Jahren“, Zahlen (Aufnahmen, Orte, Strecke, Tage, Serien, Länder), Aufnahmen der letzten 12 Monate, zu welcher Uhrzeit du fotografierst, Top-Orte, Speicher auf Handy und Server, Würfel für eine zufällige Erinnerung.

**Sicherheit**
App-Sperre per Fingerabdruck, Gesicht oder PIN. Der Server ist mit einem langen Schlüssel geschützt. Kein Tracking, keine Werbung, kein Standort im Hintergrund.

**Server-Weboberfläche**
Im Browser deine Adresse öffnen: Karte und Galerie am PC, Suche, Download, Löschen.

---

## 1. Server auf dem Raspberry Pi

Den Ordner `server` auf den Pi kopieren (z. B. per WinSCP oder `scp -r server pi@192.168.2.110:~/`). Dann auf dem Pi:

```bash
cd ~/server
bash install.sh
```

Das Skript:
- legt **nur** den Ordner `/mnt/server013/spur` an. Deine anderen Daten auf der Platte bleiben unberührt.
- richtet den Server als Dienst ein, der nach jedem Neustart von selbst läuft (Port 8787).
- zeigt am Ende den **App-Schlüssel** an.

Anderer Ordner? `SPUR_DATA=/mnt/server013/meinordner bash install.sh`

Nützliche Befehle:
```bash
sudo systemctl status spur                                         # läuft er?
journalctl -u spur -f                                              # Live-Log
python3 /opt/spur/spur_server.py token --data /mnt/server013/spur  # Schlüssel anzeigen
python3 /opt/spur/spur_server.py token --data /mnt/server013/spur --reset   # neuen Schlüssel erzeugen
```

Im Heimnetz geht es sofort: In der App `http://192.168.2.110:8787` und den Schlüssel eintragen.

---

## 2. Von unterwegs erreichbar machen

### Variante A: eigene Domain über Cloudflare (empfohlen)
Damit funktioniert die App überall, ohne dass du ein VPN einschalten musst. Du brauchst keine Portfreigabe am Router und bekommst automatisch HTTPS.

1. Bei Cloudflare kostenlos anmelden, deine IONOS-Domain hinzufügen und bei IONOS die Nameserver auf die von Cloudflare umstellen.
2. Auf dem Pi `cloudflared` installieren (Anleitung bei Cloudflare: „Zero Trust → Networks → Tunnels → Create tunnel“; dort wird dir der Installationsbefehl für den Pi angezeigt).
3. Im Tunnel einen „Public Hostname“ anlegen, z. B. `fotos.deine-domain.de`, Dienst `HTTP`, URL `localhost:8787`.
4. In der App als Server-Adresse `https://fotos.deine-domain.de` eintragen.

Hinweis: Cloudflare erlaubt pro Anfrage höchstens 100 MB. Spur lädt aber sowieso in 4-MB-Stücken hoch, deshalb gehen auch lange Videos.

### Variante B: WireGuard (VPN)
1. Auf dem Pi `curl -L https://install.pivpn.io | bash` ausführen und WireGuard wählen.
2. Mit `pivpn add` ein Profil anlegen und es per QR-Code (`pivpn -qr`) in die WireGuard-App auf dem Handy übernehmen.
3. Am Router UDP-Port 51820 auf den Pi weiterleiten.
4. In Spur als Adresse die Heimnetz-Adresse eintragen: `http://192.168.2.110:8787`.

Damit erreicht die App den Server nur, wenn WireGuard aktiv ist. Ist es aus, macht das nichts: Die Fotos warten auf dem Handy und werden später hochgeladen.

---

## 3. App bauen

### Mit GitHub (ohne Android Studio)
1. Den ganzen Ordner `spur` in ein GitHub-Repo hochladen.
2. Unter **Actions** läuft „Spur App bauen“ automatisch los (dauert ca. 5–8 Minuten).
3. Unten beim fertigen Lauf **Spur-APK** herunterladen, entpacken und `app-release.apk` auf dem Handy installieren.

### Mit Android Studio
`android`-Ordner öffnen → warten bis Gradle fertig ist → ▶ Run (Handy per USB, USB-Debugging an).

Updates lassen sich einfach drüber installieren, weil jeder Build mit demselben Schlüssel signiert wird. Deine Fotos bleiben dabei erhalten.

---

## 4. Snapchat-Memories importieren

1. Auf **accounts.snapchat.com** → *Meine Daten* → **„Export your Memories“** einschalten → nur Memories anfordern.
2. Wenn die Mail kommt: alle ZIP-Dateien herunterladen (große Exporte kommen in mehreren Teilen) und auf die Platte am Pi kopieren, z. B. nach `/mnt/server013/snapchat-export/`.
3. Auf dem Pi:
   ```bash
   python3 /opt/spur/spur_server.py import-snapchat --data /mnt/server013/spur /mnt/server013/snapchat-export/*.zip
   ```
4. Danach erscheinen alle Memories in der App, mit **Datum und genauem Standort** aus der `memories_history.json`, auf der Karte, in der Galerie (gelber Punkt) und im Rückblick.

Gut zu wissen:
- Der Import kann jederzeit abgebrochen und neu gestartet werden. Schon importierte Memories werden übersprungen.
- Text und Sticker, die du auf ein Snap gemacht hast, werden mit ins Foto übernommen. Mit `--no-overlay` bekommst du das Original ohne.
- Fehlen Dateien im ZIP, lädt der Importer sie über die Links aus der JSON. Diese Links sind aber nur ca. 7 Tage gültig.
- Ortsnamen (Stadt, Straße) ergänzt der Server automatisch im Hintergrund über OpenStreetMap. Bei 5000 Bildern dauert das ein bis zwei Stunden.

---

## 5. Play Store

Die App ist darauf ausgelegt (targetSdk 36, Datenschutzerklärung, kein Hintergrund-Standort, keine Tracker). Diese Schritte brauchst du:

1. **Google-Play-Entwicklerkonto** (einmalig 25 $). Bei neuen privaten Konten verlangt Google vor der Veröffentlichung einen geschlossenen Test mit mindestens 12 Testern über 14 Tage.
2. **Upload-Schlüssel** erstellen (einmal, gut aufbewahren!):
   ```bash
   keytool -genkeypair -v -keystore upload.jks -alias spur -keyalg RSA -keysize 2048 -validity 10000
   ```
   Dann bei GitHub → Settings → Secrets → Actions anlegen:
   `SPUR_KEYSTORE_BASE64` (Inhalt von `base64 -w0 upload.jks`), `SPUR_KEYSTORE_PASSWORD`, `SPUR_KEY_ALIAS` (= spur), `SPUR_KEY_PASSWORD`.
   Ab dann baut GitHub ein signiertes **Spur-PlayStore-AAB**. Das lädst du in der Play Console hoch.
3. **Datenschutzerklärung**: `https://fotos.deine-domain.de/datenschutz` (liefert dein Server mit). Trag vorher noch deine Kontakt-Mail ein (`server/web/datenschutz.html`).
4. **Datensicherheit** in der Play Console: Standort (genau), Fotos/Videos, *nicht* geteilt, *nicht* für Werbung, Übertragung verschlüsselt (bei HTTPS), Nutzer kann löschen.
5. Screenshots, Symbol (512×512) und Beschreibung hochladen, Altersfreigabe-Fragebogen ausfüllen.
6. Vor dem Hochladen in `android/app/build.gradle.kts` bei jedem Update `versionCode` um 1 erhöhen.

Hinweis: Wenn du die App veröffentlichst, braucht jeder Nutzer seinen eigenen Spur-Server.
Die Karte nutzt die kostenlosen OpenStreetMap-Kacheln. Für den privaten Gebrauch passt das. Bei einer öffentlichen App mit vielen Nutzern verlangt OpenStreetMap einen eigenen Kachel-Anbieter (z. B. einen kostenlosen CARTO- oder MapTiler-Schlüssel). Ohne Server funktioniert die App im Modus „Nur Handy“.

---

## Technik in Kürze

| | |
|---|---|
| App | Kotlin, Jetpack Compose, CameraX, Room, WorkManager, OkHttp, Coil, osmdroid (OpenStreetMap), Biometric |
| Server | Python 3 (nur Standardbibliothek), SQLite, optional Pillow + ffmpeg für Vorschaubilder |
| Upload | fortsetzbar in 4-MB-Stücken, SHA-256-Prüfung, Wiederholung bei Abbruch |
| Abgleich | Server → App per Änderungs-Zeitstempel (auch Löschungen, Notizen, Favoriten) |
| Löschen | Server-Papierkorb, nach 30 Tagen endgültig weg |
| Minimum | Android 8.0 |
