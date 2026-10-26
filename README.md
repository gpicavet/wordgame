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

La langue est choisie à la création de la partie (`language` : `fr` ou `en`) ; le serveur refuse l'anglais si `WORDGAME_EN_DICT` est absent. En anglais, BelzéBot utilise ses poids appris sur des parties anglaises (`models/value-en-v1.txt`, `trainer --language=en train …`).

## Règles

Deux jeux de règles existent dans `engine/.../RuleSet.kt` :

- `fr-classic`, utilisé par défaut pour les nouvelles parties : plateau standard (centre en mot compte double), 102 jetons avec les valeurs habituelles, bonus de 50 points quand on pose ses 7 lettres ;
- `fr-v1` : plateau et valeurs propres au projet, avec un bonus de 40 ;
- `en-classic`, pour les parties en anglais : même plateau que `fr-classic`, les 100 jetons anglais habituels, bonus de 50.

Chaque partie garde le jeu de règles avec lequel elle a été créée. **Avant une publication sur les stores**, il faudra décider lequel garder par défaut, pour limiter le risque juridique.

## API (`/api/v1`, jeton Bearer)

| Méthode | Chemin | |
|---|---|---|
| POST | `/auth/register` | `{nickname}` → `{userId, nickname, token}` |
| GET | `/me`, `/games`, `/games/{id}` | La vue d'une partie ne contient que le chevalet du joueur qui la demande. |
| POST | `/games` | `{opponentNickname}` |
| POST / DELETE | `/matchmaking` | Associe le joueur au plus ancien joueur en attente, sinon le met en file. |
| POST | `/games/{id}/moves` | `{expectedMoveNumber, action:{type: play\|exchange\|pass\|resign, …}}` |

Le serveur valide tout : tour du joueur, lettres présentes sur son chevalet, géométrie, mots, score recalculé côté serveur. Le sac est mélangé avec `SecureRandom` et n'est jamais envoyé. `expectedMoveNumber` évite qu'un coup soit joué deux fois. Un joueur qui ne joue pas pendant 72 h perd la partie (vérification toutes les 5 minutes).

## Déploiement (Scaleway Stardust, 1 vCPU / 1 Go)

```bash
./gradlew :server:buildFatJar
ssh root@HOST mkdir -p /tmp/wordgame
scp server/build/libs/wordgame-server.jar dictionaries/bin/fr-libre.dawg deploy/* root@HOST:/tmp/wordgame/
ssh root@HOST bash /tmp/wordgame/install.sh
```

Le script installe Java 17, SQLite, Caddy (HTTPS automatique), 1 Go de swap, un service systemd et une sauvegarde chaque nuit (copie vers Scaleway Object Storage si un remote rclone `scw` est configuré). Pense à mettre ton domaine dans `deploy/Caddyfile`.

Mesure locale avec les options de la JVM du service : environ **125 Mo de RAM** avec 200 comptes, 30 parties et 300 requêtes.

## Pas encore dans le MVP

Notifications push (FCM) : l'application interroge le serveur toutes les 10 à 15 s tant qu'elle est ouverte. Récupération du compte (Google ou Apple). Glisser-déposer des lettres et zoom sur le plateau. Bots et solveur. iOS. Limitation du nombre de requêtes. Suppression du compte (obligatoire avant la publication).
