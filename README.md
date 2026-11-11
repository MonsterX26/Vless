# CipherLink VPN — Android (usage personnel)

CipherLink est un client Android local pour profils VLESS. Le moteur natif sing-box est déclaré comme dépendance Gradle (`libbox` 1.14.0) et le service VPN lui fournit un descripteur TUN créé par Android.

> **État de validation :** le code d'intégration est présent, mais il n'a pas été compilé ni exécuté sur un appareil dans cet environnement. Le premier build GitHub doit donc être considéré comme une validation de compilation, pas comme une preuve que le trafic VPN fonctionne. Le workflow vérifie aussi la présence des bibliothèques natives ARM 32 et 64 bits.

## Fonctions incluses

- Import manuel ou depuis le presse-papiers des URI `vless://`.
- Profils VLESS avec TCP, WebSocket, gRPC, HTTPUpgrade et H2.
- TLS, uTLS fingerprint, ALPN, Reality (`pbk`, `sid`) et flow VLESS.
- Sélection d'un profil, suppression et persistance locale.
- Mesure du temps d'ouverture TCP vers l'endpoint (ce n'est pas un ping ICMP ni une mesure du tunnel).
- `VpnService` Android, notification de premier plan, autorisation système, arrêt du moteur et reprise après redémarrage du service.
- Notification et réinitialisation des connexions lors d’un changement Wi-Fi / données mobiles.
- Dépendance sing-box libbox 1.14.0 résolue depuis JitPack.
- Cibles natives `armeabi-v7a` et `arm64-v8a`.

Les profils sont enregistrés dans les préférences privées de l'application. La sauvegarde Android est désactivée pour éviter d'inclure ces profils dans une sauvegarde cloud standard.

## Construire sur GitHub

1. Crée un dépôt GitHub **privé** pour ton usage personnel.
2. Décompresse l'archive et envoie tout son contenu à la racine du dépôt (y compris `.github`).
3. Ouvre **Actions → Build CipherLink APK → Run workflow**.
4. Le job télécharge la dépendance libbox, compile l'APK Release et vérifie les deux ABI natives.
5. Télécharge l'artefact `cipherlink-personal-apk` contenant l'APK et son SHA-256.

Le build utilise une clé de signature debug. Elle convient aux tests personnels, mais une mise à jour installée par-dessus doit être signée avec la même clé. Ne publie pas cet APK comme une version de production.

## Architecture native

- `MainActivity.kt` : interface, profils, import, sélection, mesure TCP.
- `VlessParser.kt` : validation de l'URI et conversion vers le JSON sing-box.
- `TunnelService.kt` : cycle de vie `VpnService`, notification et gestion des erreurs.
- `CoreBridge.kt` : chargement de libbox, initialisation, proxy de `PlatformInterface`, création du TUN et protection des sockets via `VpnService.protect()`.

Le pont `PlatformInterface` utilise la réflexion afin de limiter le couplage aux noms Java générés par gomobile. Les callbacks d'interface réseau/Wi-Fi/notifications sont volontairement minimaux. Les fonctions avancées qui en dépendent (sélection par application, statistiques détaillées, supervision réseau sophistiquée) ne sont pas annoncées comme disponibles.

## Vérifications à faire sur téléphone

Après le premier build :

1. Installer l'APK sur un appareil ARM (Android 7+ ; Android 9+ recommandé pour les essais initiaux).
2. Importer un profil VLESS dont tu contrôles les identifiants.
3. Accorder l'autorisation VPN Android.
4. Se connecter, puis vérifier une adresse IP publique et l'accès DNS depuis le téléphone.
5. Vérifier IPv4 **et** IPv6, puis couper/reprendre le Wi-Fi et passer en données mobiles.
6. Arrêter le VPN et vérifier que le trafic direct revient.
7. Consulter `adb logcat -s CoreBridge TunnelService` en cas d'échec.

Ne saisis pas de profil contenant des secrets dans un dépôt public, un ticket GitHub ou un log partagé.

## Licence et dépendances

`libbox` est distribué sous GPL-3.0. L'usage personnel n'exige pas de publication de ton dépôt privé. Si tu redistribues le binaire ou le code, examine les obligations GPL applicables et inclus les notices/licences requises. La dépendance et sa version sont référencées dans `app/build.gradle.kts`.

- Bibliothèque Android : https://github.com/singbox-android/libbox
- Moteur sing-box : https://github.com/SagerNet/sing-box
- Documentation Android : https://sing-box.sagernet.org/clients/android/
