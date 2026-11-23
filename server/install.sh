#!/usr/bin/env bash
# Spur Server auf dem Raspberry Pi installieren.
# Aufruf im Ordner "server":   bash install.sh
set -e
DATA=${SPUR_DATA:-/mnt/server013/spur}
USER_NAME=${SUDO_USER:-$(whoami)}

echo "== Spur Server: Installation =="
echo "Daten-Ordner: $DATA   (andere Daten auf der Platte bleiben unberuehrt)"

sudo apt-get update -qq
sudo apt-get install -y -qq python3 python3-pil ffmpeg >/dev/null || echo "Hinweis: Pakete konnten nicht alle installiert werden - Server laeuft trotzdem."

sudo mkdir -p /opt/spur/web "$DATA"
sudo cp spur_server.py /opt/spur/
sudo cp web/* /opt/spur/web/
sudo chown -R "$USER_NAME":"$USER_NAME" "$DATA"

sed "s/^User=.*/User=$USER_NAME/; s#--data [^ ]*#--data $DATA#" spur.service | sudo tee /etc/systemd/system/spur.service >/dev/null
sudo systemctl daemon-reload
sudo systemctl enable --now spur.service
sleep 2
echo
echo "Fertig. Server laeuft auf Port 8787."
echo "Im Heimnetz:   http://$(hostname -I | awk '{print $1}'):8787"
echo -n "App-Schluessel: "
sudo -u "$USER_NAME" python3 /opt/spur/spur_server.py token --data "$DATA"
