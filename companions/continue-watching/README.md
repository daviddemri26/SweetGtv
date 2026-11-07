# Reprendre les séries sur le Chromecast

Petit complément Android TV à CleanYourTV. Son icône **Reprendre** ouvre les épisodes que les huit applications choisies publient dans Android TV : Netflix, Prime Video, YouTube, Apple TV, Peacock, Spotify, Disney+ et Stremio. Il ne remplace pas l’accueil, ne modifie pas les historiques et n’intercepte aucune touche.

## Ce qui peut apparaître

- Les programmes `WatchNext` de type reprise, épisode suivant ou nouvel épisode, publiés comme accessibles par les applications.
- Les programmes du canal Stremio **Continue Watching**, lorsqu’une position de lecture est disponible.
- Le titre, l’épisode, l’application et la progression. Le carrousel de l’accueil affiche les affiches publiques partagées par ces programmes ; une icône locale de l’application remplace les images indisponibles. La liste autonome garde sa présentation initiale.
- Les programmes datant de plus de 180 jours ou les reprises déjà terminées à 99 % sont écartés. Les lignes Stremio sans date restent accessibles.

La reprise directe utilise uniquement un lien résolu dans l’application ayant publié le programme. Un lien absent, refusé ou périmé ouvre cette application et indique que la reprise directe est indisponible. L’application peut encore afficher son sélecteur de profil.

**Toutes les plateformes ne partagent pas leurs séries avec les applications tierces.** Sur un autre Chromecast 4K au même build Android, le dépôt officiel rapporte zéro ligne WatchNext accessible. Une liste vide ne veut donc pas dire que les historiques ont été effacés. Dans ce cas, l’écran l’explique et propose d’ouvrir les applications, en commençant par Stremio.

## Permissions

- `android.permission.READ_TV_LISTINGS` : lire les programmes que les applications rendent accessibles dans le fournisseur TV Android. La permission est demandée par le bouton **Autoriser**, ou peut être accordée par ADB pendant l’installation autorisée.
- `android.permission.INTERNET` : télécharger uniquement les affiches publiées dans les programmes, à la demande du carrousel. Aucun cookie, identifiant de compte, en-tête d’authentification ou télémétrie. Le launcher ne possède toujours pas cette permission.
- Aucun service de fond, aucune interrogation périodique et aucune permission d’accessibilité.
- Les lectures se font uniquement à l’ouverture de l’écran, avec **Actualiser**, ou quand l’accueil demande les reprises et les affiches.
- Sortir de l’écran ou démarrer une nouvelle actualisation annule les requêtes en cours avec `CancellationSignal` ; les parcours de résultats vérifient aussi l’annulation.
- Le journal `ContinueWatching` contient seulement les nombres de lignes trouvées et un indicateur d’échec de lecture. Aucun titre, identifiant de programme ou lien de reprise n’est enregistré.

## Compilation

Projet autonome, Gradle 8.9, AGP 8.5.2, JDK 17, Android SDK 34. Depuis la racine du dépôt :

```sh
./launcher/gradlew -p companions/continue-watching --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

L’APK est produit dans `app/build/outputs/apk/release/app-release.apk`. Son identifiant est `com.david.tvcontinue` et il est signé avec la clé de développement locale, comme l’accueil installé. Conserver cette clé pour les mises à jour.

## Installation et vérification

À effectuer par le responsable de la session, une fois ADB reconnecté et l’état sauvegardé. Installer l’APK sans désinstaller aucune application, puis ouvrir **Reprendre** et autoriser l’accès aux programmes TV. Il conserve son filtre Leanback et le module-card CleanYourTV ; le carrousel personnalisé utilise le fournisseur local décrit ci-dessous.

Vérifier sur le Chromecast réel la navigation aux flèches, l’ouverture d’un épisode, le retour à l’accueil et la lecture Cast. Une sonde de comptage apparaît dans `adb logcat -s ContinueWatching` après ouverture.

Pour retirer sa présence de l’accueil, utiliser la sélection des applications de CleanYourTV. Pour le désactiver complètement sans effacer de données : `pm disable-user --user 0 com.david.tvcontinue`.

## Contrat du carrousel personnalisé

- Requête en lecture seule : `content://com.david.tvcontinue.home/entries`. Aucun réseau pendant `query` ; chaque requête relit les programmes et la progression, au maximum 60 résultats. Une panne sans résultat lève une erreur au lieu de remplacer l’état par une fausse liste vide.
- Colonnes : `_id` (SHA-256 hex opaque stable de l’application et de son lien), `title` (512 caractères maximum), `subtitle` (nom de l’application + S/E, 512 caractères maximum), `owner` (package Android), `progress` (0–1000, ou −1 si inconnu/épisode suivant), `art_uri`.
- `art_uri` : `content://com.david.tvcontinue.home/art/KEY/VERSION`. KEY et VERSION contiennent exactement 64 caractères hexadécimaux minuscules ; VERSION inclut `format-v2` et change lorsque l’URL d’affiche publiée change, ce qui invalide les anciennes vignettes carrées.
- `query`, `openFile` et `getType` vérifient l’UID propre ou le package `com.gathin.tvhome` avec la même signature (`PackageManager.checkSignatures`). Aucun accès shell/ADB aux titres par ce fournisseur. L’identité Binder n’est effacée qu’après cette vérification, pour lire les programmes avec la permission du complément.
- Aucun filtre SQL ni chemin arbitraire ; seules les colonnes annoncées sont acceptées. Insertion, modification, suppression et ouverture en écriture sont rejetées.
- Ouverture : composant explicite `com.david.tvcontinue/.OpenEntryActivity`, unique argument utilisé `entry_key`. Aucun lien externe envoyé par l’appelant n’est accepté. Une clé doit correspondre à un vrai programme lu par le complément ; le snapshot mémoire expire après une minute et les mêmes contrôles de résolution d’application que la liste autonome sont appliqués.
- `CancellationSignal` traverse les trois lectures du fournisseur TV, le chargement des affiches et leurs boucles de lecture. Sortir de l’activité d’ouverture annule sa résolution.

## Limites des affiches

- HTTPS public ou `content://` du fournisseur appartenant à l’application source uniquement. Les adresses privées, locales, de boucle locale et les principaux réseaux réservés sont refusés. Chaque redirection est vérifiée à nouveau, deux redirections maximum ; pas de HTTP, fichier local, proxy, cookie ou authentification.
- TLS et HTTP utilisent `HttpsURLConnection` avec les vérifications de certificat et d’hôte Android par défaut. Vérification DNS préalable, délai DNS 1,5 s, connexion/lecture 2,5 s, budget de lecture global 10 s ; annulation par déconnexion. Un blocage de lecture peut dépasser ce budget de son délai de lecture courant.
- Source de 4 Mio maximum, 16 millions de pixels maximum ; décodage sous-échantillonné, recadrage central 16:9 en 480 × 270, sans étirement, PNG de 512 Kio maximum.
- Deux générations d’images au maximum en parallèle. Aucun chargement automatique en arrière-plan ; les demandes de l’accueil déclenchent les lectures.
- Cache privé dans `cache/home-art`, plafond dur 8 Mio y compris fichier temporaire. Affiches valides 24 h, icône de repli 60 s. Aucun titre ni URL dans les noms de fichiers ; l’ancienneté détermine l’éviction.
- Le chargement des images `content://` est limité à 5 s après ouverture du descripteur ; l’annulation ferme aussi ce descripteur.
- Aucun compte, donnée d’application, progression de lecture, service Home ou paramètre système n’est modifié.

Interface du complément et du raccourci d’économiseur en anglais ; les titres des programmes restent ceux des applications. Les icônes de repli gardent leurs proportions, centrées dans le cadre 16:9.

## Rangée YouTube distincte

Le carrousel YouTube utilise uniquement les fiches `preview_program` publiées sous le package exact `com.google.android.youtube.tv`. Il ne présente ni historique, ni dernière vidéo regardée, ni reprise ou minutage déduit. Le lecteur de reprises et `/entries` conservent leur comportement existant.

- Endpoint : `content://com.david.tvcontinue.home/youtube_entries`, avec les mêmes colonnes que `/entries`. `progress` vaut toujours `-1`.
- Parcours limité à 5 000 lignes du fournisseur, au plus 60 fiches affichées. Ordre déterministe par identifiant de canal puis identifiant de ligne ; doublons de lien exact éliminés avant la limite d’affichage. Cet ordre n’est pas un ordre de visionnage.
- Chaque fiche doit avoir un titre non vide après nettoyage, un lien de longueur bornée et une activité résolue, exportée, activée et appartenant à YouTube. Les mêmes contrôles sont refaits lors de l’ouverture. Les chaînes affichées sont bornées ; aucun titre, identifiant ou lien n’est journalisé.
- Affiches : `content://com.david.tvcontinue.home/youtube_art/KEY/VERSION`. La validation HTTPS public/fournisseur appartenant à YouTube et les limites du cache existant restent appliquées ; une affiche refusée ou indisponible utilise l’icône de YouTube.
- Ouverture : le composant existant `OpenEntryActivity`, `entry_key=KEY` et `shelf=youtube`. Sans `shelf`, la résolution utilise les reprises existantes ; une valeur autre que `continue` ou `youtube` est refusée.
- Les deux rangées ont des snapshots distincts et des clés séparées par source. Le fournisseur conserve sa vérification de signature. Aucune permission, dépendance, connexion de compte, tâche de fond ou modification d’historique supplémentaire.

Version locale 1.1, `versionCode` conservé à 1 pour permettre le retour à l’APK sauvegardé par une installation de remplacement normale.

## Métadonnées des vidéos YouTube

La rangée lit les colonnes publiques author, release_date, duration_millis, interaction_type/count, live et short_description du fournisseur TV. Le contrat historique conserve ses six colonnes ; six champs youtube_* optionnels enrichissent uniquement /youtube_entries. Une projection minimale conserve les vidéos si un ancien fournisseur refuse les colonnes supplémentaires.

Si `author` ou `release_date` sont vides, la chaîne et l’ancienneté peuvent provenir de `short_description`. L’ancienneté conserve alors le libellé publié par YouTube. Les descriptions de films et de mixes sont exclues de ce traitement. Seul le champ `live=1` active le badge LIVE.

L’accueil affiche la chaîne et l’ancienneté/vues sur deux petites lignes tronquées, avec la durée ou LIVE sur la miniature. Les informations absentes restent masquées.
