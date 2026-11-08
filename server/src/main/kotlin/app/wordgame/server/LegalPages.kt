package app.wordgame.server

/*
 * Public pages required by Google Play: the privacy policy and how to delete one's account. They describe what the
 * server and the app actually do; keep them in step with the code (and with the Play Console "Data safety" form).
 */

/** [other]: the link to the same page in the other language. */
private fun page(title: String, body: String, lang: String = "fr", other: String = "") = """<!doctype html>
<html lang="$lang"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>WordWok — $title</title>
<style>body{font-family:system-ui,sans-serif;max-width:42em;margin:2em auto;padding:0 1em;line-height:1.55}
h2{margin-top:1.6em}td,th{padding:.3em .6em;border-bottom:1px solid #ddd;text-align:left;vertical-align:top}
table{border-collapse:collapse}</style></head>
<body>
$other
$body
</body></html>"""

private fun contact(email: String?, lang: Lang = Lang.FR) = email?.let { """<a href="mailto:$it">$it</a>""" }
    ?: lang.t("l'adresse de contact indiquée sur la fiche Google Play de l'application", "the contact address given on the app's Google Play listing")

private const val TO_ENGLISH_PRIVACY = """<p lang="en" style="text-align:right"><a href="/privacy">English</a></p>"""
private const val TO_FRENCH_PRIVACY = """<p lang="fr" style="text-align:right"><a href="/confidentialite">Français</a></p>"""
private const val TO_ENGLISH_DELETION = """<p lang="en" style="text-align:right"><a href="/delete-account">English</a></p>"""
private const val TO_FRENCH_DELETION = """<p lang="fr" style="text-align:right"><a href="/supprimer-compte">Français</a></p>"""

fun privacyPage(contactEmail: String?) = page(
    "Politique de confidentialité",
    """<h1>Politique de confidentialité de WordWok</h1>
<p>Dernière mise à jour : 8 novembre 2026 (version anglaise, langue des notifications).</p>
<p>WordWok est un jeu de lettres développé à titre personnel. Il ne contient ni publicité ni outil de mesure d'audience,
et ne vend ni ne partage aucune donnée. Cette page dit quelles données il utilise, pourquoi, et combien de temps.</p>

<h2>Ce qui est enregistré sur le serveur</h2>
<table>
<tr><th>Donnée</th><th>Pourquoi</th></tr>
<tr><td>Ton pseudo et son numéro (ex. greg#4821)</td><td>Te désigner auprès des autres joueurs, te permettre d'être invité.</td></tr>
<tr><td>Un jeton de connexion par téléphone (seule son empreinte est conservée)</td><td>Reconnaître ton téléphone sans mot de passe.</td></tr>
<tr><td>Si tu sauvegardes ton compte avec Google : l'identifiant de ton compte Google</td><td>Retrouver ton compte sur un autre téléphone. Ni ton adresse e-mail ni ton nom ne sont conservés.</td></tr>
<tr><td>Tes parties en ligne : coups joués, scores, dates</td><td>Faire fonctionner les parties et calculer tes statistiques.</td></tr>
<tr><td>Les tours où tu as demandé des indices</td><td>Tes statistiques.</td></tr>
<tr><td>Le jeton de notification de ton téléphone et sa langue</td><td>T'avertir, dans ta langue, quand c'est ton tour ou qu'on te défie.</td></tr>
<tr><td>Les joueurs que tu bloques</td><td>Ne plus vous proposer de partie ensemble.</td></tr>
<tr><td>Les signalements que tu envoies ou qui te concernent (motif, précisions, partie)</td><td>Modérer les pseudos et les abus.</td></tr>
</table>
<p>WordWok ne demande ni nom, ni adresse e-mail, ni numéro de téléphone, ni âge, et n'utilise pas ta position ni tes contacts.
Les journaux techniques du serveur ne notent que son démarrage, ses erreurs et les signalements, sans adresse IP ; ils sont
effacés au bout de 30 jours. Pour limiter les abus, le serveur compte en mémoire les requêtes par adresse IP pendant au plus
une heure, sans les enregistrer.</p>

<h2>Ce qui reste sur ton téléphone</h2>
<p>Ton jeton de connexion, ton pseudo et tes parties solo contre les robots restent sur ton téléphone et ne sont pas envoyés
au serveur. Ils disparaissent quand tu supprimes ton compte ou l'application.</p>

<h2>Qui d'autre y a accès</h2>
<ul>
<li><b>Les autres joueurs</b> de tes parties voient ton pseudo, tes coups et tes scores.</li>
<li><b>OVHcloud</b> héberge le serveur, au Canada (pays reconnu par l'Union européenne comme assurant une protection adéquate des données).</li>
<li><b>Google</b>, si tu choisis « Se connecter avec Google » : Google confirme ton identité à WordWok, selon ses propres
<a href="https://policies.google.com/privacy">règles de confidentialité</a>.</li>
<li><b>Google (Firebase Cloud Messaging)</b> achemine les notifications : il reçoit le jeton de notification et le texte de
chaque notification (par exemple « greg a joué KIWI (+12) »).</li>
</ul>

<h2>Combien de temps</h2>
<p>Tes données sont gardées tant que ton compte existe. Quand tu le supprimes, elles sont effacées immédiatement du serveur ;
elles peuvent subsister jusqu'à 14 jours dans les sauvegardes quotidiennes, qui sont ensuite détruites. Les parties jouées
contre d'autres joueurs restent visibles pour eux, sous le nom « Joueur supprimé », sans lien avec toi.</p>

<h2>Tes droits</h2>
<p>Tu peux supprimer ton compte à tout moment depuis l'application (menu <i>Mon compte</i>) ou en suivant
<a href="/supprimer-compte">cette page</a>. Pour accéder à tes données, les corriger ou en obtenir une copie, écris à
${contact(contactEmail)}. Tu peux aussi adresser une réclamation à la CNIL (<a href="https://www.cnil.fr">cnil.fr</a>).</p>

<h2>Contact</h2>
<p>Pour toute question sur cette politique : ${contact(contactEmail)}.</p>""",
    other = TO_ENGLISH_PRIVACY,
)

/** [privacyPage] in English, at /privacy. */
fun privacyPageEn(contactEmail: String?) = page(
    "Privacy policy",
    """<h1>WordWok privacy policy</h1>
<p>Last updated: November 8, 2026 (English version).</p>
<p>WordWok is a word game developed as a personal project. It contains no ads and no audience measurement tools, and it
neither sells nor shares any data. This page says which data it uses, why, and for how long.</p>

<h2>What is stored on the server</h2>
<table>
<tr><th>Data</th><th>Why</th></tr>
<tr><td>Your nickname and its number (e.g. greg#4821)</td><td>To show you to other players and let them invite you.</td></tr>
<tr><td>One sign-in token per phone (only its fingerprint is kept)</td><td>To recognize your phone without a password.</td></tr>
<tr><td>If you back up your account with Google: your Google account identifier</td><td>To find your account again on another phone. Neither your email address nor your name is kept.</td></tr>
<tr><td>Your online games: moves, scores, dates</td><td>To run the games and compute your statistics.</td></tr>
<tr><td>The turns where you asked for hints</td><td>Your statistics.</td></tr>
<tr><td>Your phone's notification token and language</td><td>To tell you, in your language, when it is your turn or someone challenges you.</td></tr>
<tr><td>The players you block</td><td>To stop offering you games together.</td></tr>
<tr><td>The reports you send or that concern you (reason, details, game)</td><td>To moderate nicknames and abuse.</td></tr>
</table>
<p>WordWok asks for no name, email address, phone number or age, and does not use your location or your contacts.
The server's technical logs only record its startup, its errors and the reports, without IP addresses; they are deleted
after 30 days. To limit abuse, the server counts requests per IP address in memory for at most an hour, without storing
them.</p>

<h2>What stays on your phone</h2>
<p>Your sign-in token, your nickname and your solo games against the robots stay on your phone and are not sent to the
server. They disappear when you delete your account or the app.</p>

<h2>Who else has access</h2>
<ul>
<li><b>Other players</b> in your games see your nickname, your moves and your scores.</li>
<li><b>OVHcloud</b> hosts the server, in Canada (a country recognized by the European Union as ensuring an adequate level of data protection).</li>
<li><b>Google</b>, if you choose "Sign in with Google": Google confirms your identity to WordWok, under its own
<a href="https://policies.google.com/privacy">privacy policy</a>.</li>
<li><b>Google (Firebase Cloud Messaging)</b> delivers the notifications: it receives the notification token and the text of
each notification (for example "greg played KIWI (+12)").</li>
</ul>

<h2>How long</h2>
<p>Your data is kept as long as your account exists. When you delete it, it is erased from the server immediately; it may
remain for up to 14 days in the daily backups, which are then destroyed. Games played against other players remain
visible to them, under the name "Deleted player", with no link to you.</p>

<h2>Your rights</h2>
<p>You can delete your account at any time from the app (<i>My account</i> menu) or by following
<a href="/delete-account">this page</a>. To access your data, correct it or get a copy, write to
${contact(contactEmail, Lang.EN)}. You may also lodge a complaint with the French data protection authority, the CNIL
(<a href="https://www.cnil.fr">cnil.fr</a>), or with your own country's authority.</p>

<h2>Contact</h2>
<p>For any question about this policy: ${contact(contactEmail, Lang.EN)}.</p>""",
    lang = "en",
    other = TO_FRENCH_PRIVACY,
)

/**
 * How to delete one's account: in the app, or here for players whose account is linked to Google ([googleClientId]): the Google button proves the account is theirs (POST /api/v1/auth/google), then the page
 * deletes it (DELETE /api/v1/me), or signs out if they change their mind.
 */
fun accountDeletionPage(googleClientId: String?): String {
    val byGoogle = googleClientId?.let {
        """<h2>Sans l'application, avec Google</h2>
<p>Si ton compte est sauvegardé avec Google (menu <i>Mon compte</i>), connecte-toi avec le même compte Google :</p>
<script src="https://accounts.google.com/gsi/client" async></script>
<div id="g_id_onload" data-client_id="$it" data-callback="onGoogle" data-auto_prompt="false"></div>
<div class="g_id_signin" data-type="standard" data-text="signin_with" data-locale="fr"></div>
<div id="found" hidden>
<p>Compte trouvé : <b id="name"></b>. Sa suppression est immédiate et définitive.</p>
<p><button id="delete">Supprimer définitivement ce compte</button> <button id="cancel">Annuler</button></p>
</div>
<p id="status" role="status"></p>
<script>
let token = null;
const status = (text) => { document.getElementById("status").textContent = text; };
async function onGoogle(response) {
  status("Recherche du compte…");
  const r = await fetch("/api/v1/auth/google", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ idToken: response.credential }),
  });
  const body = await r.json();
  if (!r.ok) { status(body.message || "Erreur"); return; }
  token = body.token;
  document.getElementById("name").textContent = body.nickname + "#" + body.tag;
  document.getElementById("found").hidden = false;
  status("");
}
document.getElementById("delete").onclick = async () => {
  const r = await fetch("/api/v1/me", { method: "DELETE", headers: { Authorization: "Bearer " + token } });
  document.getElementById("found").hidden = true;
  status(r.ok ? "Ton compte a été supprimé." : "La suppression a échoué, réessaie plus tard.");
  token = null;
};
document.getElementById("cancel").onclick = async () => {
  await fetch("/api/v1/me/session", { method: "DELETE", headers: { Authorization: "Bearer " + token } });
  document.getElementById("found").hidden = true;
  status("Rien n'a été supprimé.");
  token = null;
};
</script>"""
    }.orEmpty()
    return page(
        "Supprimer son compte",
        """<h1>Supprimer son compte WordWok</h1>
<h2>Dans l'application</h2>
<p>Sur l'écran des parties, ouvre le menu <i>Mon compte</i>, puis touche <b>Supprimer mon compte</b> et confirme.</p>
$byGoogle
<h2>Ce qui est supprimé</h2>
<ul>
<li>ton pseudo et son numéro, l'accès au compte et son lien avec ton compte Google ;</li>
<li>les jetons de notification de tes téléphones ;</li>
<li>tes parties contre des robots, et tes demandes d'indices ;</li>
<li>les joueurs que tu as bloqués, et les signalements que tu as envoyés ou qui te concernent ;</li>
<li>depuis l'application, les parties solo enregistrées sur le téléphone (sinon, elles disparaissent en désinstallant l'application).</li>
</ul>
<p>Les sauvegardes quotidiennes du serveur sont détruites au bout de 14 jours.</p>
<h2>Ce qui est conservé</h2>
<p>Les parties jouées contre d'autres joueurs restent visibles pour eux, sous le nom « Joueur supprimé », sans lien avec toi.
Tu abandonnes les parties encore en cours.</p>
<p>Voir aussi la <a href="/confidentialite">politique de confidentialité</a>.</p>""",
        other = TO_ENGLISH_DELETION,
    )
}

/** [accountDeletionPage] in English, at /delete-account. */
fun accountDeletionPageEn(googleClientId: String?): String {
    val byGoogle = googleClientId?.let {
        """<h2>Without the app, with Google</h2>
<p>If your account is backed up with Google (<i>My account</i> menu), sign in with the same Google account:</p>
<script src="https://accounts.google.com/gsi/client" async></script>
<div id="g_id_onload" data-client_id="$it" data-callback="onGoogle" data-auto_prompt="false"></div>
<div class="g_id_signin" data-type="standard" data-text="signin_with" data-locale="en"></div>
<div id="found" hidden>
<p>Account found: <b id="name"></b>. Deleting it is immediate and permanent.</p>
<p><button id="delete">Permanently delete this account</button> <button id="cancel">Cancel</button></p>
</div>
<p id="status" role="status"></p>
<script>
let token = null;
const status = (text) => { document.getElementById("status").textContent = text; };
async function onGoogle(response) {
  status("Looking for the account…");
  const r = await fetch("/api/v1/auth/google", {
    method: "POST", headers: { "Content-Type": "application/json", "Accept-Language": "en" },
    body: JSON.stringify({ idToken: response.credential }),
  });
  const body = await r.json();
  if (!r.ok) { status(body.message || "Error"); return; }
  token = body.token;
  document.getElementById("name").textContent = body.nickname + "#" + body.tag;
  document.getElementById("found").hidden = false;
  status("");
}
document.getElementById("delete").onclick = async () => {
  const r = await fetch("/api/v1/me", { method: "DELETE", headers: { Authorization: "Bearer " + token } });
  document.getElementById("found").hidden = true;
  status(r.ok ? "Your account has been deleted." : "The deletion failed, try again later.");
  token = null;
};
document.getElementById("cancel").onclick = async () => {
  await fetch("/api/v1/me/session", { method: "DELETE", headers: { Authorization: "Bearer " + token } });
  document.getElementById("found").hidden = true;
  status("Nothing was deleted.");
  token = null;
};
</script>"""
    }.orEmpty()
    return page(
        "Delete your account",
        """<h1>Delete your WordWok account</h1>
<h2>In the app</h2>
<p>On the games screen, open the <i>My account</i> menu, then tap <b>Delete my account</b> and confirm.</p>
$byGoogle
<h2>What is deleted</h2>
<ul>
<li>your nickname and its number, access to the account and its link with your Google account;</li>
<li>your phones' notification tokens;</li>
<li>your games against robots, and your hint requests;</li>
<li>the players you blocked, and the reports you sent or that concern you;</li>
<li>from the app, the solo games saved on the phone (otherwise, they disappear when you uninstall the app).</li>
</ul>
<p>The server's daily backups are destroyed after 14 days.</p>
<h2>What is kept</h2>
<p>Games played against other players remain visible to them, under the name "Deleted player", with no link to you.
You resign the games still in progress.</p>
<p>See also the <a href="/privacy">privacy policy</a>.</p>""",
        lang = "en",
        other = TO_FRENCH_DELETION,
    )
}
