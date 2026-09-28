# LEO Control Android v0.8.0 — builder

Questo repository di build usa il sorgente Android di `streetpea/chiaki-ng` come motore Remote Play e applica un overlay LEO Control.

## Obiettivo
- LEO Control diventa il launcher Android.
- Hub PC / Hisense / Fire TV resta accessibile dalla stessa app.
- PS5 usa il motore Remote Play Android interno.
- Prima registrazione PS5: una sola volta.
- Accessi successivi: PS5 -> joypad direttamente.
- Il PC non partecipa alla sessione PS5.
- Modalità controller: stream 360p/30 fps, bitrate 2 Mbps, video coperto dall'interfaccia; Remote Play resta attivo sotto per trasportare i comandi.

## Build
La GitHub Action clona il commit chiaki-ng fissato in `UPSTREAM_COMMIT.txt`, applica l'overlay LEO Control e produce un APK debug come artifact.

## Licenza
L'integrazione contiene/adatta componenti chiaki-ng coperti da AGPL-3.0 con eccezione OpenSSL. La distribuzione deve rispettare i termini applicabili.
