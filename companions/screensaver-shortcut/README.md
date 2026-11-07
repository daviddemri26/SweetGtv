# Raccourci économiseur

Petite application Android TV locale, affichée sous le nom **Économiseur**. Appuyer sur son icône demande à Android de lancer l’économiseur déjà choisi dans les paramètres. L’activité se ferme aussitôt. CleanYourTV peut placer ce raccourci dans `top_bar_apps`.

Le raccourci utilise d’abord l’activité Android TV `com.android.tv.settings/.device.display.daydream.DaydreamVoiceAction` avec l’action exacte `com.google.android.pano.action.SLEEP`. Malgré ce nom historique, son implémentation AOSP Android 14 appelle `DreamBackend.startDreaming()`, qui demande à DreamManager de lancer l’économiseur sélectionné, puis ferme l’activité. Elle n’appelle pas la mise en veille de PowerManager.

L’activité SystemUI `com.android.systemui/.Somnambulator` reste un second chemin pour les appareils qui la fournissent. Elle est absente du TvSystemUI installé sur ce Chromecast ; la version 1.0 du raccourci ouvrait donc les réglages généraux sans lancer l’économiseur. Si les deux activités directes sont indisponibles, le raccourci ouvre les réglages Google AmbientActivity, puis essaie les réglages Android d’économiseur, avec une indication « Démarrer maintenant ». En dernier recours, il ouvre les paramètres généraux avec une indication, ou affiche un message d’échec.

Le raccourci ne remplace jamais l’économiseur, ne modifie aucun paramètre et ne simule ni bouton d’alimentation ni extinction. Une demande d’ouverture acceptée n’est pas une preuve de démarrage effectif : le test sur l’appareil reste indispensable.

Sources du chemin Android TV : [DaydreamVoiceAction Android 14](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android14-release/Settings/src/com/android/tv/settings/device/display/daydream/DaydreamVoiceAction.java), [DreamBackend Android 14](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/packages/SettingsLib/src/com/android/settingslib/dream/DreamBackend.java), [activité exportée dans le manifeste AOSP](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android14-release/Settings/AndroidManifest.xml).

## Permissions

Aucune permission Android n’est demandée. L’application n’a aucun accès réseau, service, récepteur de démarrage, collecte, publicité ou stockage de données. Elle ne modifie aucun réglage Android, application, Wi-Fi, Bluetooth, HDMI-CEC ou service de télécommande.

## Compilation

Depuis la racine du dépôt, avec JDK 17, Android SDK 34 et Build Tools 34 disponibles :

```sh
./launcher/gradlew -p companions/screensaver-shortcut --no-daemon lintRelease assembleRelease
```

Définir `JAVA_HOME`, `ANDROID_HOME` et éventuellement `GRADLE_USER_HOME` selon le poste. Le projet utilise le wrapper Gradle 8.9 du lanceur.

- Identifiant : `com.daviddemri.tv.screensaver`.
- Activité : `com.daviddemri.tv.screensaver/.MainActivity`.
- APK compilé : `app/build/outputs/apk/release/app-release.apk`.
- Signature locale de développement, constante pour les mises à jour sur ce poste.
- Aucun filtre HOME : cette application ne peut pas remplacer l’accueil Android.

## Vérification sur le Chromecast

Installer uniquement l’APK local compilé après sauvegarde, puis ajouter l’icône à la barre supérieure de l’accueil. Tester depuis la télécommande iPhone : ouvrir l’icône, constater l’économiseur Google existant, puis appuyer sur Retour ou Accueil. Vérifier que le contrôle iPhone et Cast restent fonctionnels. La compilation et l’analyse statique ne remplacent pas ce test réel. Vérifier séparément que `dumpsys dreams` indique le DreamService Google Backdrop actif.

Le retour arrière consiste à retirer la tuile de l’accueil et, si souhaité, désactiver seulement `com.daviddemri.tv.screensaver` ; aucun réglage d’économiseur n’a besoin d’être restauré.
