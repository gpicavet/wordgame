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

`dictionaries/bin/fr-libre.dawg` contient environ 408 000 formes fléchies (437 Ko). Il est généré à partir du
[lexique Grammalecte](https://grammalecte.net/) v7.7, sous licence **MPL 2.0** :

```bash
curl -LO https://www.grammalecte.net/dic/lexique-grammalecte-fr-v7.7.zip && unzip lexique-grammalecte-fr-v7.7.zip -d dictionaries/source
./gradlew :dict-builder:run --args="dictionaries/source/lexique-grammalecte-fr-v7.7.txt dictionaries"
```

Traitements appliqués : accents retirés, majuscules, mots de 2 à 15 lettres. Sont exclus les noms propres, les mots à majuscule initiale, ceux qui contiennent un trait d'union ou une apostrophe, et ceux dont certains caractères ne correspondent à aucune lettre. La liste obtenue est publiée dans `dictionaries/fr-libre.txt.gz`, comme l'exige la MPL. Ce n'est **pas** l'ODS : certains mots propres au jeu de compétition (ZA, KA…) n'y figurent pas.

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
