# Wordgame (nom de travail)

Jeu de lettres en tour par tour, à deux joueurs. Le MVP comprend l'application Android, le serveur qui détient l'état des parties et arbitre les coups, et un dictionnaire français libre.

## Modules

| Module | Rôle |
|---|---|
| `engine` | Kotlin Multiplatform (JVM pour l'instant) : règles, plateau, calcul du score, DAWG. Le même code tourne dans l'application (aperçu du score) et sur le serveur (arbitrage). |
| `protocol` | Objets échangés entre l'application et le serveur (kotlinx.serialization). |
| `dict-builder` | Génère le DAWG à partir du lexique Grammalecte. |
| `server` | Ktor (moteur CIO) avec SQLite, sur une seule connexion et un seul thread. |
| `app` | Android avec Jetpack Compose. Inclus uniquement si un SDK Android est présent (`ANDROID_HOME` ou `local.properties`). |

## Commandes

```bash
./gradlew :engine:jvmTest :server:test          # tests
./gradlew :server:run                           # serveur local sur :8080 (données dans ./data)
./gradlew :server:buildFatJar                   # server/build/libs/wordgame-server.jar
./gradlew :app:installDebug                     # app sur l'émulateur (pointe vers 10.0.2.2:8080)
./gradlew :app:installDebug -PapiUrl=http://192.168.1.20:8080   # vrai téléphone sur le même réseau
```

## Dictionnaire

`dictionaries/bin/fr-libre.dawg` contient environ 407 000 formes fléchies (435 Ko). Il est généré à partir du
[lexique Grammalecte](https://grammalecte.net/) v7.7, sous licence **MPL 2.0** :

```bash
curl -LO https://www.grammalecte.net/dic/lexique-grammalecte-fr-v7.7.zip && unzip lexique-grammalecte-fr-v7.7.zip -d dictionaries/source
./gradlew :dict-builder:run --args="dictionaries/source/lexique-grammalecte-fr-v7.7.txt dictionaries"
```

Traitements appliqués : accents retirés, majuscules, mots de 2 à 15 lettres. Sont exclus les noms propres, les mots à majuscule initiale, ceux qui contiennent un trait d'union ou une apostrophe, et ceux dont certains caractères ne correspondent à aucune lettre. Sont aussi exclus les symboles et abréviations (km, kg, cf…), les affixes, les formes fautives et les mots qui n'existent qu'à l'intérieur d'une locution (le « ad » de « ad hoc »).

Compléments du **Wiktionnaire** (CC BY-SA 4.0) : `dictionaries/fr-wiktionnaire.txt.gz`, produit par `tools/wiktionnaire.py` à partir de l'extrait de kaikki.org (commande en tête du script). Ne sont gardés que les mots de 5 lettres ou plus, issus de noms, adjectifs, verbes, adverbes ou interjections traduits dans au moins une autre langue (trois pour les verbes), sans gentilés, sigles, abréviations ni noms propres, avec leurs formes fléchies. Cette liste est publiée sous la même licence CC BY-SA.

Dictionnaire additionnel privé : `dictionaries/private/fr-2lettres.txt` (non versionné), chargé en plus du dictionnaire libre par le serveur (`WORDGAME_EXTRA_DICT`) et intégré à l'application quand le fichier existe à la compilation.

Corrections manuelles : `dictionaries/additions.txt` et `dictionaries/removals.txt`, un mot par ligne. N'y ajouter que des mots attestés dans une source libre (Wiktionnaire…), jamais une liste recopiée de l'ODS. Il faut relancer le générateur après chaque modification. La liste obtenue est publiée dans `dictionaries/fr-libre.txt.gz`, comme l'exige la MPL. Ce n'est **pas** l'ODS : certains mots propres au jeu de compétition (ZA, KA…) n'y figurent pas.

### Anglais

`dictionaries/bin/en-enable.dawg` contient les 168 551 mots de la liste **ENABLE** (domaine public), de 2 à 15 lettres. `dictionaries/bin/en-courant.dawg`, le vocabulaire courant des robots, garde les mots d'ENABLE présents dans les listes **SCOWL** de taille 35 au plus, en orthographe anglaise et américaine (licence permissive de Kevin Atkinson) :

```bash
curl -L -o dictionaries/source/enable1.txt https://raw.githubusercontent.com/dolph/dictionary/master/enable1.txt
curl -L https://downloads.sourceforge.net/wordlist/scowl-2020.12.07.tar.gz | tar xz -C dictionaries/source
./gradlew :dict-builder:run --args="en dictionaries/source/enable1.txt dictionaries/source/scowl-2020.12.07/final dictionaries"
```

### Motifs de refus

Quand un mot est refusé, l'application en donne la raison : nom propre, abréviation ou sigle, mot composé (trait d'union, apostrophe), orthographe fautive, préfixe ou suffixe, mot de locution, ou mot absent du dictionnaire officiel. Sinon, elle indique « n'existe pas ». Ces motifs ne changent rien aux mots acceptés. Le générateur les écrit dans `dictionaries/bin/fr-refus.txt.gz` et `en-refus.txt.gz`, intégrés à l'application :

- **en français**, il s'agit des entrées Grammalecte écartées par les filtres ci-dessus ;
- **en anglais**, des noms propres, abréviations et contractions des listes SCOWL, ainsi que des mots SCOWL de taille 70 au plus qui manquent à ENABLE.

La langue est choisie à la création de la partie (`language` : `fr` ou `en`) ; le serveur refuse l'anglais si `WORDGAME_EN_DICT` est absent. En anglais, IroBot et BelzéBot utilisent les poids appris sur des parties anglaises (`models/value-en-v1.txt`, `trainer --language=en train …`).

## Règles

Deux jeux de règles existent dans `engine/.../RuleSet.kt` :

- `fr-classic`, utilisé par défaut pour les nouvelles parties : plateau standard (centre en mot compte double), 102 jetons avec les valeurs habituelles, bonus de 50 points quand on pose ses 7 lettres ;
- `fr-v1` : plateau et valeurs propres au projet, avec un bonus de 40 ;
- `en-classic`, pour les parties en anglais : même plateau que `fr-classic`, les 100 jetons anglais habituels, bonus de 50.

Chaque partie garde le jeu de règles avec lequel elle a été créée. **Avant une publication sur les stores**, il faudra décider lequel garder par défaut, pour limiter le risque juridique.

## API (`/api/v1`, jeton Bearer)

| Méthode | Chemin | |
|---|---|---|
| POST | `/auth/register` | `{nickname}` → `{userId, nickname, token, tag}` |
| GET | `/me`, `/games`, `/games/{id}` | La vue d'une partie ne contient que le chevalet du joueur qui la demande. |
| DELETE | `/me` | Supprime le compte (voir plus bas). |
| POST | `/games` | `{friends, bots, language}` |
| POST / DELETE | `/matchmaking` | Associe le joueur au plus ancien joueur en attente, sinon le met en file. |
| POST | `/games/{id}/moves` | `{expectedMoveNumber, action:{type: play\|exchange\|pass\|resign, …}}` |

Plusieurs joueurs peuvent avoir le même pseudo : un numéro à 4 chiffres les distingue (`greg#4821`, affiché dans le menu *Mon compte* de l'app). Pour inviter un ami, le pseudo seul suffit tant qu'il est unique ; sinon il faut son numéro. Dans une partie, deux joueurs homonymes apparaissent avec leur numéro.

Langue : le serveur répond en français ou en anglais selon l'en-tête `Accept-Language` (messages d'erreur, nom « Joueur supprimé » / « Deleted player ») ; sans en-tête, en français. Chaque téléphone enregistre sa langue avec son jeton de notification (`devices.lang`), et les notifications sont écrites dans la langue du dernier téléphone du joueur. Les textes sont à côté du code, en paires `tr("…", "…")` (`Messages.kt`).

Politique de confidentialité (exigée par Google Play) : page publique `/confidentialite`, en anglais `/privacy` (`server/.../LegalPages.kt`), liée depuis *Mon compte*. Elle décrit ce que font le serveur et l'app : à tenir à jour avec le code et le formulaire « Sécurité des données » de la console Play.

Suppression du compte (exigée par Google Play) : depuis l'app (*Mon compte*), ou sur la page publique `/supprimer-compte` (en anglais `/delete-account`) en se connectant avec Google, pour les comptes associés à Google. Les parties contre des robots seulement sont effacées ; dans les autres, le joueur abandonne et apparaît comme « Joueur supprimé ». Ses téléphones, sa file d'attente et ses indices sont oubliés.

Le serveur valide tout : tour du joueur, lettres présentes sur son chevalet, géométrie, mots, score recalculé côté serveur. Le sac est mélangé avec `SecureRandom` et n'est jamais envoyé. `expectedMoveNumber` évite qu'un coup soit joué deux fois. Un joueur qui ne joue pas pendant 72 h perd la partie (vérification toutes les 5 minutes).

Limites (`Limits` dans `Application.kt`) : 20 inscriptions ou connexions Google par heure et par adresse IP (derrière Caddy, la dernière adresse de `X-Forwarded-For`), 120 appels par minute et par compte, dont 30 créations de partie ou coups. Au-delà : 429 `TOO_MANY_REQUESTS`.

Version minimale : l'app envoie son `versionCode` dans l'en-tête `X-App-Version`. Si une mise à jour change l'API d'une façon que les anciennes versions ne suivent pas, monter `WORDGAME_MIN_APP_VERSION` (`deploy/wordgame.service`) : les versions plus anciennes reçoivent 426 `UPGRADE_REQUIRED` et l'app affiche « Mise à jour nécessaire » avec un lien vers Google Play. Les requêtes sans en-tête (page de suppression, outils) ne sont pas vérifiées.

## Modération

Les pseudos injurieux ou réservés (staff, robots, WordWok) sont refusés à l'inscription (`Nicknames.kt`, écritures déguisées comprises : « c0nnard », « Gros_Con »). En touchant le nom d'un autre joueur dans une partie, on peut le **bloquer** (plus de défis ni d'appariement entre les deux, la partie en cours continue ; débloquer dans *Mon compte*) ou le **signaler** (pseudo, triche, autre). Les signalements arrivent dans le journal et la table `reports` :

```bash
ssh wordgame 'journalctl -u wordgame --since "-7 days" | grep Report'
ssh wordgame 'sqlite3 /var/lib/wordgame/wordgame.db "SELECT id, datetime(created_at/1000, \"unixepoch\"), reported_nickname, reason, details FROM reports ORDER BY id DESC LIMIT 20"'
# Renommer un pseudo injurieux (le joueur garde son compte et ses parties) :
ssh wordgame 'sqlite3 /var/lib/wordgame/wordgame.db "UPDATE users SET nickname = \"Joueur\" || substr(id, 1, 6) WHERE nickname = \"…\" AND tag = …"'
```

## Déploiement (Scaleway Stardust, 1 vCPU / 1 Go)

```bash
./gradlew :server:buildFatJar
ssh root@HOST mkdir -p /tmp/wordgame
scp server/build/libs/wordgame-server.jar dictionaries/bin/fr-libre.dawg deploy/* root@HOST:/tmp/wordgame/
ssh root@HOST bash /tmp/wordgame/install.sh
```

Le script installe Java 17, SQLite, Caddy (HTTPS automatique), 1 Go de swap, un service systemd et une sauvegarde chaque nuit (copie vers Scaleway Object Storage si un remote rclone `scw` est configuré). Pense à mettre ton domaine dans `deploy/Caddyfile`.

Mesure locale avec les options de la JVM du service : environ **125 Mo de RAM** avec 200 comptes, 30 parties et 300 requêtes.

## Connexion avec Google

Facultative : un joueur associe son compte à son compte Google (*Mon compte* → *Sauvegarder avec Google*) pour le retrouver sur un autre téléphone (*Se connecter avec Google* à l'accueil). Le serveur vérifie le jeton d'identité Google et ne garde que l'identifiant du compte Google (`users.google_sub`) ; chaque téléphone a son propre jeton (`sessions`).

Réglages, dans le projet Google Cloud du projet Firebase :

1. Écran de consentement OAuth (nom de l'app, adresse de contact, lien vers `/confidentialite`).
2. Un identifiant client OAuth **Web** : son id va dans `WORDGAME_GOOGLE_CLIENT_ID` (serveur, `deploy/wordgame.service`) et dans `googleClientId` (app : `-PgoogleClientId=…` ou `gradle.properties`). Sans lui, les boutons Google sont cachés et le serveur répond `GOOGLE_DISABLED`.
3. Un identifiant client OAuth **Android** par clé de signature (paquet `app.wordgame.android`, empreinte SHA-1) : la clé debug (`keytool -list -v -keystore ~/.android/debug.keystore -storepass android`), la clé d'importation (`keytool -list -v -keystore secrets/upload-keystore.jks`) et la clé de signature de Google Play (console Play, *Intégrité de l'application*, après le premier envoi). Une empreinte manquante est la cause habituelle d'un échec de connexion.

## Publication (Google Play)

La version publiée est signée avec la clé d'importation, rangée dans `secrets/` (non versionné). Pour la créer, une seule fois :

```bash
mkdir -p secrets
keytool -genkeypair -v -keystore secrets/upload-keystore.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
cat > secrets/keystore.properties <<'PROPS'
storeFile=secrets/upload-keystore.jks
storePassword=…
keyAlias=upload
keyPassword=…
PROPS
```

Garder la clé et ses mots de passe en lieu sûr (gestionnaire de mots de passe, copie hors du Mac) : Google Play signe l'app avec sa propre clé (Play App Signing) et peut réinitialiser une clé d'importation perdue, mais la démarche est lente. Puis, en augmentant `versionCode` à chaque envoi :

```bash
./gradlew :app:bundleRelease   # app/build/outputs/bundle/release/app-release.aab, à envoyer dans la console Play
```

Une version signée ne s'installe pas par-dessus une version debug (signatures différentes) : il faut désinstaller, ce qui perd le jeton du compte.

## Publicité (AdMob, désactivée par défaut)

Par défaut, l'app ne contient aucun SDK publicitaire (ni permission d'identifiant publicitaire, ni rien à déclarer). `-Pads=true` compile `app/src/ads` au lieu de `app/src/noads` : AdMob et le formulaire de consentement de Google (UMP). La seule publicité est une **vidéo récompensée choisie par le joueur** : 3 tours avec indices gratuits par jour (contre les robots), puis une courte vidéo par tour (`HintQuota`). Sans vidéo disponible ou sans consentement, les indices s'affichent quand même. Un bouton *Confidentialité des publicités* apparaît dans *Mon compte* là où le consentement est demandé (UE, Royaume-Uni…).

```bash
./gradlew :app:installDebug -Pads=true    # identifiants de test de Google : vidéos de démonstration
./gradlew :app:bundleRelease -Pads=true -PadmobAppId=ca-app-pub-…~… -PadmobRewardedId=ca-app-pub-…/…
```

Avant d'activer en production :

1. Compte AdMob : créer l'application (paquet `app.wordgame.android`) et un bloc d'annonces *Avec récompense* ; renseigner les infos fiscales et de paiement.
2. AdMob, *Confidentialité et messages* : créer le message de consentement RGPD (et États-Unis si besoin). Sans lui, le formulaire ne s'affiche pas.
3. `app-ads.txt` : servir la ligne donnée par AdMob à la racine du site déclaré dans la fiche Play.
4. Politique de confidentialité (`LegalPages.kt`, les deux langues) : AdMob/Google comme destinataire, identifiant publicitaire, consentement et comment le retirer.
5. Console Play : *Contient des annonces* = oui ; *Identifiant publicitaire* = oui (publicité) ; *Sécurité des données* : identifiants de l'appareil, données d'utilisation et diagnostics partagés avec Google à des fins publicitaires ; public cible 13 ans et plus. Retirer « No ads / Aucune publicité » de `store/listing.md`.
6. Ne jamais toucher ses propres annonces réelles : tester avec les identifiants de test (par défaut) ou en déclarant son téléphone comme appareil de test dans AdMob.

`play-services-ads` est en 24.9 : les versions 25.x sont compilées avec Kotlin 2.3, plus récent que celui du projet (2.1).

## Pas encore dans le MVP

iOS (et la récupération du compte avec Apple). Sans compte Google associé, `GameService.releaseAccount` permet de reprendre un compte à la main. Zoom sur le plateau. Rappel avant la perte d'une partie au bout de 72 h. Copie des sauvegardes hors du serveur.
