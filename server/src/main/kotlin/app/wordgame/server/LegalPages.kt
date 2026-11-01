package app.wordgame.server

/*
 * Public pages required by Google Play: the privacy policy and how to delete one's account. They describe what the
 * server and the app actually do; keep them in step with the code (and with the Play Console "Data safety" form).
 */

private fun page(title: String, body: String) = """<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>WordWok — $title</title>
<style>body{font-family:system-ui,sans-serif;max-width:42em;margin:2em auto;padding:0 1em;line-height:1.55}
h2{margin-top:1.6em}td,th{padding:.3em .6em;border-bottom:1px solid #ddd;text-align:left;vertical-align:top}
table{border-collapse:collapse}</style></head>
<body>
$body
</body></html>"""

private fun contact(email: String?) =
    email?.let { """<a href="mailto:$it">$it</a>""" } ?: "l'adresse de contact indiquée sur la fiche Google Play de l'application"

fun privacyPage(contactEmail: String?) = page(
    "Politique de confidentialité",
    """<h1>Politique de confidentialité de WordWok</h1>
<p>Dernière mise à jour : 1<sup>er</sup> novembre 2026 (connexion avec Google).</p>
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
<tr><td>Le jeton de notification de ton téléphone</td><td>T'avertir quand c'est ton tour ou qu'on te défie.</td></tr>
</table>
<p>WordWok ne demande ni nom, ni adresse e-mail, ni numéro de téléphone, ni âge, et n'utilise pas ta position ni tes contacts.
Les journaux techniques du serveur ne notent que son démarrage et ses erreurs, sans adresse IP ; ils sont effacés au bout
de 30 jours.</p>

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
)

fun accountDeletionPage(contactEmail: String?): String {
    val byEmail = contactEmail?.let {
        """<h2>Sans l'application</h2>
<p>Écris à <a href="mailto:$it?subject=Suppression%20de%20compte%20WordWok">$it</a> en indiquant ton pseudo et son numéro
(par exemple <b>greg#4821</b>, affiché dans le menu <i>Mon compte</i>). Le compte est supprimé sous 30 jours.</p>"""
    }.orEmpty()
    return page(
        "Supprimer son compte",
        """<h1>Supprimer son compte WordWok</h1>
<h2>Dans l'application</h2>
<p>Sur l'écran des parties, ouvre le menu <i>Mon compte</i>, puis touche <b>Supprimer mon compte</b> et confirme.</p>
$byEmail
<h2>Ce qui est supprimé</h2>
<ul>
<li>ton pseudo et son numéro, l'accès au compte et son lien avec ton compte Google ;</li>
<li>les jetons de notification de tes téléphones ;</li>
<li>tes parties contre des robots, et tes demandes d'indices ;</li>
<li>les parties solo enregistrées sur le téléphone.</li>
</ul>
<p>Les sauvegardes quotidiennes du serveur sont détruites au bout de 14 jours.</p>
<h2>Ce qui est conservé</h2>
<p>Les parties jouées contre d'autres joueurs restent visibles pour eux, sous le nom « Joueur supprimé », sans lien avec toi.
Tu abandonnes les parties encore en cours.</p>
<p>Voir aussi la <a href="/confidentialite">politique de confidentialité</a>.</p>""",
    )
}
