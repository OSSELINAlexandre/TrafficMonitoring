# Revue d'ingénierie Android 16 de la spécification

Date de la revue : 26 septembre 2026  
Périmètre : point 41 de `SPEC.md` — revue uniquement, sans code de production.

## Conclusion exécutive

Le produit décrit est réalisable sur Android 16 avec `VpnService`, mais la spécification ne doit pas encore être implémentée telle quelle. Cinq corrections sont bloquantes avant le squelette technique :

1. Le TUN doit avoir un seul lecteur. Le moteur de forwarding et le pipeline de mesure ne peuvent pas lire chacun le même descripteur comme deux branches indépendantes.
2. Le moteur doit ouvrir des sockets de sortie protégées du VPN et fournir des événements de flux/compteurs avant de transformer les connexions. Un simple `tun2socks` n'est pas, à lui seul, un accès direct à Internet.
3. `getConnectionOwnerUid()` est une attribution opportuniste, limitée à l'instance VPN active et susceptible de retourner `INVALID_UID`. Le modèle doit accepter une attribution inconnue ou ambiguë.
4. La prise en charge IPv6 nécessite deux routes, les en-têtes d'extension, la fragmentation, l'ICMPv6/PMTU et une politique explicite contre les fuites IPv6.
5. Le domaine ne doit pas faire partie naïvement de la clé d'agrégation. La corrélation DNS est temporelle, plusieurs noms peuvent partager une IP, et les DNS chiffrés rendent souvent le nom inconnu.

La recommandation est de faire, avant le jalon 1, un spike jetable sur le Galaxy A57 avec **Firestack** et un second spike minimal avec **hev-socks5-tunnel + proxy SOCKS5 direct local**. Aucun choix de moteur ne doit être figé avant d'avoir démontré sur l'appareil cible : TCP, UDP/QUIC, IPv4, IPv6, changement Wi-Fi/5G, attribution UID, compteurs bidirectionnels, DNS classique et pages mémoire de 16 Kio.

## Problèmes et modifications recommandées

### 1. Le diagramme TUN à deux branches est techniquement trompeur — bloquant

1. **Exigence concernée** — Sections 6, 7 et 10 : le TUN alimente en parallèle le « Monitoring pipeline » et le « Forwarding engine » ; `PacketParser` analyse les paquets.
2. **Problème** — Un descripteur TUN est un flux, pas un bus de diffusion. Deux lecteurs concurrents se répartiraient les paquets au lieu de recevoir chacun une copie. Dupliquer le descripteur ne duplique pas les données. Si la bibliothèque native prend possession du TUN, le parseur Kotlin ne peut pas aussi le consommer correctement.
3. **Android 16/API** — `VpnService.Builder.establish()` retourne un descripteur où chaque lecture retire un paquet sortant et chaque écriture injecte un paquet entrant. Le descripteur est non bloquant par défaut. La documentation exige aussi sa fermeture à l'arrêt ([VpnService.Builder](https://developer.android.com/reference/android/net/VpnService.Builder#establish())).
4. **Modification recommandée** — Remplacer le diagramme par une chaîne à lecture unique : `TUN -> ForwardingEngine`, puis imposer à `ForwardingEngine` une sortie d'observation (`FlowOpened`, `BytesTransferred`, `DnsMessageObserved`, `FlowClosed`, erreurs) ou un tap de paquets explicite avant/après forwarding. L'interface doit aussi exposer les deux sens du trafic et la convention de comptage.
5. **Impact produit** — Implémentation seulement ; aucun changement de fonction visible.

### 2. « Forwarding engine » est sous-spécifié — bloquant

1. **Exigence concernée** — Sections 6 et 7 : forwarding local sans serveur distant, connectivité Internet normale, moteur existant TCP/UDP/IPv4/IPv6.
2. **Problème** — Beaucoup de projets nommés `tun2socks` convertissent le TUN vers un serveur SOCKS5 ; ils ne fournissent pas eux-mêmes une sortie Internet directe. Un serveur SOCKS distant contredirait l'architecture sans serveur et déplacerait tout le trafic vers un tiers. Un proxy direct local supplémentaire doit ouvrir les vraies sockets Internet.
3. **Android 16/API** — Toute socket de sortie dont la destination est couverte par les routes VPN doit être passée à `VpnService.protect()` avant connexion, sinon elle repart dans le TUN et boucle. Si les sockets sont liées à un `Network`, le VPN doit tenir `setUnderlyingNetworks()` à jour ([VpnService](https://developer.android.com/reference/android/net/VpnService#protect(int)), [réseaux sous-jacents](https://developer.android.com/reference/android/net/VpnService#setUnderlyingNetworks(android.net.Network%5B%5D))).
4. **Modification recommandée** — Définir un contrat obligatoire : forwarding direct local ou proxy direct local embarqué ; callback permettant de protéger chaque FD natif ; aucune destination réseau contrôlée par le projet ; gestion des changements de réseau ; arrêt déterministe ; remontée d'erreurs et de compteurs ; aucun DNS implicite envoyé ailleurs. Distinguer dans l'architecture « pile TUN » et « dialer direct protégé ».
5. **Impact produit** — Implémentation et garantie de confidentialité ; le comportement attendu reste identique.

### 3. L'attribution UID n'est pas garantie — bloquant pour la sémantique des résultats

1. **Exigence concernée** — Section 8 : déterminer l'application responsable de chaque connexion avec `ConnectivityManager.getConnectionOwnerUid()`.
2. **Problème** — L'appel peut retourner `Process.INVALID_UID`; il ne constitue pas une promesse d'attribution à 100 %. L'interrogation doit avoir lieu tant que la connexion originale existe dans le noyau et avant que le moteur ne la remplace par une socket appartenant à l'application VPN. Une interrogation tardive porterait sur le mauvais flux ou ne trouverait plus rien.
3. **Android 16/API** — L'API n'accepte que TCP et UDP. Elle renvoie l'UID seulement si la connexion est trouvée et observable ; elle lève `SecurityException` si l'appelant n'est pas le `VpnService` actif de l'utilisateur ([ConnectivityManager](https://developer.android.com/reference/android/net/ConnectivityManager#getConnectionOwnerUid(int,%20java.net.InetSocketAddress,%20java.net.InetSocketAddress))).
4. **Modification recommandée** — Faire l'appel au premier paquet exploitable du flux original, avec tuple et sens normalisés, puis mettre en cache. Prévoir `AttributionStatus = RESOLVED | SHARED_UID | ISOLATED_UID | SYSTEM | UNKNOWN` et conserver l'UID brut. Réessayer un petit nombre de fois sur `INVALID_UID` sans bloquer le forwarding. L'API doit être testée séparément sur TCP, UDP non connecté, QUIC et IPv4/IPv6 sur l'appareil cible.
5. **Impact produit** — Produit : l'UI doit pouvoir afficher honnêtement « application non déterminée » au lieu de promettre une attribution parfaite.

### 4. UID et package ne sont pas en relation univoque

1. **Exigence concernée** — Section 8 : `UID -> package name -> application information`; section 20 : un seul `packageName` par agrégat.
2. **Problème** — Plusieurs packages peuvent partager un UID ; les processus isolés et certains services système ne se résolvent pas comme une application ordinaire. Choisir arbitrairement le premier package crée de fausses attributions.
3. **Android 16/API** — `PackageManager.getPackagesForUid()` retourne explicitement plusieurs noms pour un UID partagé ([PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager#getPackagesForUid(int))). Les UID sont aussi propres à un utilisateur Android.
4. **Modification recommandée** — Persister `androidUserId`, `uid` et une identité d'attribution séparée. Autoriser zéro, un ou plusieurs packages et figer un snapshot `label/package(s)` par session pour que l'historique survive aux désinstallations/renommages. Ne pas classer un UID partagé sous une seule application sans preuve additionnelle.
5. **Impact produit** — Produit pour les rares UID partagés/isolés ; meilleure exactitude, pas de changement du cas normal.

### 5. La sélection des applications dépend de la visibilité des packages

1. **Exigence concernée** — Sections 9 et 25 : afficher toutes les applications installées et passer les packages choisis à `addAllowedApplication()`.
2. **Problème** — Depuis Android 11, les requêtes du `PackageManager` sont filtrées. Une liste incomplète rendrait impossible la sélection de certaines applications. Un package peut également disparaître entre la sélection et `establish()`.
3. **Android 16/API** — Les méthodes de `PackageManager` renvoient une vue filtrée ; voir [visibilité des packages](https://developer.android.com/training/package-visibility). `addAllowedApplication()` exige un nom canonique installé et peut lever `NameNotFoundException`; dès qu'au moins une application est ajoutée, toutes les autres contournent le VPN ([VpnService.Builder](https://developer.android.com/reference/android/net/VpnService.Builder#addAllowedApplication(java.lang.String))).
4. **Modification recommandée** — Ajouter à la spécification le besoin de visibilité complète des applications (`QUERY_ALL_PACKAGES`, acceptable pour cette installation personnelle mais soumis à politique en cas de diffusion), filtrer proprement les apps sans intérêt, revalider au démarrage et afficher les packages disparus. Tester la sélection d'applications système. Ne jamais mélanger listes autorisées et interdites.
5. **Impact produit** — Implémentation ; message d'erreur visible si une application sélectionnée a été désinstallée.

### 6. Les limites de couverture du VPN doivent être visibles

1. **Exigence concernée** — Sections 4 et 9 : « toutes les applications » en mode global.
2. **Problème** — Android n'autorise qu'un VPN actif à la fois. L'outil ne peut donc pas cohabiter avec un VPN commercial, un autre pare-feu basé sur VPN ou Samsung Secure Wi-Fi. De plus, un VPN du profil personnel ne promet pas la couverture d'un profil professionnel, d'un autre utilisateur ou du Dossier sécurisé Samsung.
3. **Android 16/API** — Android désactive l'interface VPN précédente lorsqu'une autre est créée ; la préparation peut être révoquée à tout moment ([VpnService](https://developer.android.com/reference/android/net/VpnService)). `establish()` peut retourner `null` lors d'une révocation ou d'une course avec une autre application VPN.
4. **Modification recommandée** — Définir « toutes les applications » comme « toutes les applications de l'utilisateur/profil courant routées par cette instance VPN ». Signaler l'incompatibilité avec un autre VPN avant démarrage, traiter `onRevoke()`, et marquer la session `FAILED` ou `INTERRUPTED` avec sa cause.
5. **Impact produit** — Produit : clarification d'une limite de plateforme et messages utilisateur associés.

### 7. La configuration IPv6 doit être explicite et sans fuite silencieuse — bloquant

1. **Exigence concernée** — Sections 4, 7, 10 et 36 : support IPv4 et IPv6.
2. **Problème** — Ajouter seulement une adresse/route IPv4 ne surveille pas IPv6. Appeler `allowFamily(AF_INET6)` sans route IPv6 laisse généralement IPv6 passer sur le réseau sous-jacent, donc hors mesure. Ne pas autoriser la famille la bloque et peut casser la connectivité. Une application ne peut donc pas prétendre surveiller les deux familles sans deux chemins fonctionnels.
3. **Android 16/API** — Ajouter une adresse, route ou DNS d'une famille l'autorise dans le VPN. `allowFamily()` sans route la débloque et la laisse typiquement tomber vers le réseau sous-jacent ([VpnService.Builder.allowFamily](https://developer.android.com/reference/android/net/VpnService.Builder#allowFamily(int))).
4. **Modification recommandée** — Exiger une adresse TUN IPv4 et IPv6, les routes `0.0.0.0/0` et `::/0`, et un moteur dual-stack réellement testé. Ne pas utiliser `allowFamily()` comme substitut. Si une famille ne peut pas être forwardée, refuser de démarrer la session avec une erreur explicite plutôt que mesurer partiellement sans avertissement.
5. **Impact produit** — Produit en cas de panne d'une famille : démarrage refusé plutôt que résultats incomplets.

### 8. IPv6 ne se réduit pas à lire un en-tête fixe puis TCP/UDP

1. **Exigence concernée** — Sections 7, 10 et 36 : parser IPv6/TCP/UDP et conserver Internet fonctionnel.
2. **Problème** — L'en-tête de transport peut être précédé de plusieurs en-têtes d'extension ; les fragments non initiaux n'ont pas les ports. Ignorer ICMPv6, notamment `Packet Too Big`, peut produire des connexions qui établissent TCP puis se figent. Le même problème de fragments existe en IPv4.
3. **Android 16/API/protocole** — IPv6 impose de parcourir la chaîne `Next Header` et définit la fragmentation dans [RFC 8200](https://www.rfc-editor.org/info/rfc8200/). La découverte du MTU dépend des messages ICMPv6 `Packet Too Big`; leur perte crée des trous noirs de connectivité ([RFC 8201](https://www.rfc-editor.org/info/rfc8201/)).
4. **Modification recommandée** — Ajouter aux critères moteur et tests : en-têtes d'extension, fragments IPv4/IPv6, ICMP/ICMPv6 nécessaire au forwarding, PMTU, checksum, paquets malformés et MTU TUN. Le `PacketParser` peut marquer les fragments non attribuables plutôt que tenter une fausse 5-tuple ; le moteur doit néanmoins les traiter correctement.
5. **Impact produit** — Implémentation et fiabilité réseau ; pas de nouvelle fonctionnalité d'analyse.

### 9. Le DNS observé ne prouve pas qu'un flux a utilisé ce nom — bloquant pour la classification

1. **Exigence concernée** — Sections 12, 16 et 20 : associer un domaine observé par DNS à une IP puis persister ce domaine et sa classification.
2. **Problème** — Une IP CDN peut servir de nombreux noms simultanément, plusieurs applications partagent le cache DNS système, les réponses contiennent CNAME/SVCB/HTTPS, et les TTL expirent. Une table globale `IP -> dernier domaine` crée des faux positifs. Une réponse DNS observée n'est qu'un indice temporel, pas une preuve exclusive pour chaque connexion.
3. **Android 16/API** — `VpnService.Builder.addDnsServer()` configure des serveurs mais ne fournit aucun journal DNS ; l'observation vient uniquement des paquets qui traversent le TUN. Sans serveur explicite, Android utilise ceux du réseau par défaut ([VpnService.Builder](https://developer.android.com/reference/android/net/VpnService.Builder#addDnsServer(java.net.InetAddress))).
4. **Modification recommandée** — Maintenir en mémoire des ensembles `IP -> {nom, chaîne CNAME, observedAt, expiresAt, source}` avec TTL, idéalement contextualisés par UID quand la requête est attribuable. À l'ouverture d'un flux, créer une `DomainEvidence` immuable avec zéro, un ou plusieurs candidats. N'afficher un nom principal que si la politique de confiance le permet ; sinon conserver l'IP comme destination et les noms comme indices.
5. **Impact produit** — Produit : quelques destinations resteront IP-only ou « domaine possible » plutôt que recevoir un nom potentiellement faux.

### 10. Les DNS chiffrés et les métadonnées TLS sont insuffisamment cadrés

1. **Exigence concernée** — Section 12 : DNS observable, `TLS_METADATA`; section 5 : aucune inspection de payload.
2. **Problème** — Private DNS utilise DNS-over-TLS et des applications peuvent faire du DoH/DoQ. Le VPN voit alors une connexion chiffrée au résolveur, pas les noms. Lire SNI dans ClientHello est techniquement une inspection de contenu protocolaire, fragile face à la fragmentation, QUIC et ECH, et semble contredire l'exclusion de « payload inspection ».
3. **Android 16/API** — Android prend en charge Private DNS/DoT depuis Android 9 ; en présence d'un VPN, le résolveur privé doit être joignable à l'intérieur et à l'extérieur du VPN ([documentation Android Enterprise](https://source.android.com/docs/security/overview/reports/Google_Android_Enterprise_Security_Whitepaper_2020.pdf), [DevicePolicyManager](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setGlobalPrivateDnsModeSpecifiedHost(android.content.ComponentName,%20java.lang.String))).
4. **Modification recommandée** — Pour V1, limiter `DomainSource` à `DNS_OBSERVED` et `UNKNOWN`, sauf décision produit explicite autorisant l'analyse non déchiffrante du ClientHello. Ne pas désactiver Private DNS, ne pas forcer un DNS externe et ne pas intercepter le DoH. Ajouter un indicateur de couverture (« noms partiellement observables ») et des tests avec Private DNS et DoH.
5. **Impact produit** — Produit : moins de noms visibles, mais promesse de confidentialité cohérente. L'ajout futur de TLS/QUIC metadata nécessiterait une décision de périmètre séparée.

### 11. La définition des octets et des connexions manque

1. **Exigence concernée** — Sections 11, 19, 20 et 31 : octets envoyés/reçus et nombre de connexions.
2. **Problème** — Les chiffres diffèrent selon que l'on compte la longueur du paquet IP, le payload transport, les octets lus/écrits sur les sockets proxy, les retransmissions, ou les en-têtes. Pour UDP, il n'existe pas toujours une « connexion » ; pour QUIC, plusieurs connexions logiques peuvent partager le même 5-tuple.
3. **Android 16/API** — Le TUN expose des paquets IP complets, alors qu'un moteur proxy expose souvent des flux ou datagrammes après terminaison de la pile. Ces deux niveaux ne produisent pas les mêmes métriques.
4. **Modification recommandée** — Définir V1 ainsi : octets = longueur totale des paquets IP originaux vus au TUN, retransmissions comprises ; sens = depuis/vers l'application ; TCP `connectionCount` = nouveaux SYN sans ACK (avec protection contre retransmissions) ; UDP = nouvelle pseudo-session après expiration d'inactivité. Si le moteur ne donne que des compteurs de payload, l'UI doit les nommer différemment. Ajouter `countingMethod` à la version de schéma/session.
5. **Impact produit** — Produit : rend les statistiques comparables et explicables.

### 12. La clé d'agrégation Room proposée n'est pas stable — bloquant pour le schéma

1. **Exigence concernée** — Sections 19 et 20 : clé approximative `session + application + destination + protocole + port` avec `remoteIp` et `domain` dans l'agrégat.
2. **Problème** — Le domaine peut être inconnu à l'ouverture puis découvert, ou plusieurs domaines peuvent pointer vers la même IP. Changer le domaine change la clé et fractionne/double les compteurs. En SQLite, les valeurs `NULL` dans un index unique ne se comportent pas comme une valeur ordinaire unique. Les IP doivent aussi être canonisées, surtout IPv6.
3. **Android 16/API/Room** — Room fournit les entités, index et transactions, mais ne définit pas l'identité métier. Une mise à jour atomique des compteurs n'est pas un simple remplacement d'entité.
4. **Modification recommandée** — Utiliser une clé technique stable, par exemple `sessionId + attributionId + canonicalRemoteIpBytes + protocol + remotePort`, et rattacher les preuves de domaine dans une table séparée plusieurs-à-plusieurs. Si le produit veut agréger par domaine, effectuer une projection de lecture ou une consolidation explicite à la fin de session sans supprimer l'IP. Utiliser une requête SQL atomique `UPDATE counters = counters + delta`, pas lecture-modification-écriture en Kotlin.
5. **Impact produit** — Implémentation ; l'affichage reste groupable par domaine tout en évitant pertes et doubles comptes.

### 13. L'écriture « agrégée » doit aussi survivre à un crash

1. **Exigence concernée** — Sections 18, 19, 34 et 35 : compteurs en mémoire, écritures batchées, session `FAILED` si arrêt inattendu.
2. **Problème** — Tout garder jusqu'au bouton Stop perd la session lors d'un kill, crash ou redémarrage. Écrire chaque paquet surcharge SQLite. La spécification ne fixe ni cadence de checkpoint ni reprise d'une session laissée `RUNNING`.
3. **Android 16/API/Room** — Room utilise normalement WAL sur les appareils modernes ; WAL autorise les lecteurs pendant un writer mais il n'y a toujours qu'un writer à la fois ([Room JournalMode](https://developer.android.com/reference/androidx/room/RoomDatabase.JournalMode), [SQLite WAL](https://www.sqlite.org/wal.html)).
4. **Modification recommandée** — Un seul agrégateur en mémoire, un seul writer Room, flush transactionnel par intervalle et seuil (par exemple quelques secondes ou N flux modifiés), plus flush à Stop. Au lancement, convertir toute ancienne session `RUNNING` en `FAILED`/`INTERRUPTED`. Utiliser des deltas idempotents ou une séquence de checkpoint pour ne pas doubler après retry. Ne jamais faire de Room depuis la boucle paquet.
5. **Impact produit** — Implémentation ; améliore la récupération visible après incident.

### 14. La limite de 500 Mo ne peut pas être déduite de la seule taille logique

1. **Exigence concernée** — Section 22 : stockage utilisé, suppression des sessions les plus anciennes jusqu'au retour sous la limite.
2. **Problème** — Supprimer des lignes ne réduit normalement pas le fichier SQLite : les pages vont sur la freelist. Le WAL et le fichier SHM occupent aussi du disque. Une boucle qui vérifie seulement la taille du fichier principal peut supprimer tout l'historique sans voir la taille baisser.
3. **Android 16/API/SQLite** — Sans auto-vacuum, les pages libérées sont réutilisées mais le fichier ne rétrécit pas. Le mode incremental doit être choisi avant la création des tables et `incremental_vacuum` est nécessaire pour tronquer ([SQLite PRAGMA auto_vacuum](https://sqlite.org/pragma.html#pragma_auto_vacuum)). WAL ajoute un fichier `-wal`, normalement checkpointé vers 1000 pages mais susceptible de croître ([SQLite WAL](https://www.sqlite.org/wal.html)).
4. **Modification recommandée** — Définir deux mesures : usage physique = DB + WAL + SHM + datasets locaux ; usage logique = `(page_count - freelist_count) * page_size` pour la DB. Choisir `auto_vacuum=INCREMENTAL` dès la création, supprimer des sessions entières par cascade dans de courtes transactions, checkpoint puis vacuum incrémental hors chemin critique. Déclencher le nettoyage avec marge avant 100 %, ne jamais supprimer `RUNNING`, et gérer le cas où les datasets fixes dépassent la limite choisie.
5. **Impact produit** — Implémentation ; rend exactes les valeurs et la politique déjà promises.

### 15. Le VPN modifie par défaut la perception « réseau mesuré »

1. **Exigence concernée** — Sections 6 et 37 : Internet doit continuer à fonctionner normalement.
2. **Problème** — Pour une application ciblant API 29+, le VPN est considéré comme metered par défaut. Certaines apps réduisent alors téléchargements ou synchronisation ; la simple activation du moniteur change ce que l'on observe.
3. **Android 16/API** — `VpnService.Builder.setMetered(false)` fait hériter le caractère mesuré du réseau sous-jacent ; sans cela, un VPN ciblant Q ou plus est metered par défaut ([VpnService.Builder.setMetered](https://developer.android.com/reference/android/net/VpnService.Builder#setMetered(boolean))).
4. **Modification recommandée** — Appeler explicitement `setMetered(false)` pour hériter de l'underlay et tester les capacités réseau annoncées sur Wi-Fi et 5G. Documenter toute différence inévitable.
5. **Impact produit** — Implémentation ; évite de perturber le comportement des applications surveillées.

### 16. Cycle de vie, service au premier plan et native ARM64/16 Kio sont absents

1. **Exigence concernée** — Sessions longues sur Android 16, ARM64, démarrage/arrêt fiable.
2. **Problème** — Un `VpnService` doit devenir rapidement un foreground service avec notification persistante. Toute bibliothèque native doit être testée avec pages mémoire 16 Kio ; la compatibilité automatique Android 16 n'est pas un substitut durable à un binaire correctement aligné. Le démarrage depuis l'UI et la révocation doivent être des états métier.
3. **Android 16/API** — Le système arrête un VPN démarré en arrière-plan s'il ne passe pas au premier plan ([VpnService](https://developer.android.com/reference/android/net/VpnService)). Android 14+ impose un type de foreground service ; les VPN actifs font partie des cas `systemExempted` autorisés ([types FGS](https://developer.android.com/about/versions/14/changes/fgs-types-required)). Android 16 propose un mode de compatibilité pour les bibliothèques natives 4 Kio mais recommande toujours l'alignement 16 Kio ([changements Android 16](https://developer.android.com/about/versions/16/behavior-changes-all#16-kb)).
4. **Modification recommandée** — Ajouter au jalon 2 : permission VPN explicite, notification et canal, type/permission FGS validés sur API 36, `onRevoke`, null/exception de `establish`, arrêt depuis notification, redémarrage processus, batterie Samsung, ABI `arm64-v8a`, vérification d'alignement 16 Kio de chaque `.so`. Désactiver l'option always-on dans le manifeste pour V1 sauf si elle est réellement supportée.
5. **Impact produit** — Produit : notification permanente pendant la surveillance et comportement clair à la révocation ; le reste est implémentation.

## Options de moteur de forwarding

L'état de maintenance ci-dessous est vérifié au 26 septembre 2026. « Compatible » signifie que le projet fournit les briques annoncées ; seule une validation sur le Galaxy A57/API 36 peut valider le choix final.

| Option | Licence | Éléments favorables | Risques / travail requis | Avis |
|---|---|---|---|---|
| [Firestack](https://github.com/celzero/firestack) | MPL-2.0 | Bibliothèque Android AAR ; moniteur/pare-feu TCP et UDP ; gVisor/netstack ; projet de Rethink ; instructions actuelles utilisant explicitement platform API 36 et NDK 28 | API déclarée instable et non documentée ; intégration guidée par le code de Rethink ; Go/gomobile, taille et mémoire ; obligations MPL sur les fichiers modifiés ; vérifier callbacks de protection, compteurs, arm64 et 16 Kio | **Premier spike recommandé** : meilleur alignement fonctionnel, sans engagement avant preuve sur appareil |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | MIT | Actif ; C compact ; IPv4/IPv6, TCP/UDP, Android ; artefact `arm64-v8a`; build configuré pour pages flexibles ; version 2.17.1 publiée le 12 août 2026 | C'est un client SOCKS5, pas un dialer Internet direct ; exige un proxy SOCKS5 direct **local** et protégé ; événements d'observation/UID à ajouter autour ; JNI et cycle de vie natif | **Second spike recommandé** si l'on accepte de construire/intégrer le proxy direct local |
| [NetGuard](https://github.com/M66B/NetGuard) | GPL-3.0 | Référence Android éprouvée de VPN local sans root ; IPv4/IPv6, TCP/UDP ; comptage par application/adresse ; développement actif | Application complète, pas bibliothèque stable ; extraction du moteur JNI coûteuse ; licence copyleft forte en cas de redistribution ; architecture historique à adapter ; compatibilité API 36/16 Kio à démontrer | Bonne référence et solution de repli par fork pour usage personnel, pas premier choix comme dépendance |
| [xjasonlyu/tun2socks](https://github.com/xjasonlyu/tun2socks) | MIT | Projet actif, gVisor, TCP/UDP, IPv6, Go ; large communauté | Pas Android-first ni AAR officiel ; nécessite lui aussi un proxy ; intégration gomobile/FD/protect/événements à fabriquer ; empreinte Go ; compatibilité 16 Kio et API 36 non attestée par le projet | Option viable mais moins adaptée que Firestack/hev pour ce projet |

### Options à ne pas choisir comme raccourci

- **WireGuard Android** n'est pas un moteur de forwarding direct local : il suppose un tunnel et un pair distant, donc ne correspond pas au produit.
- **gVisor/netstack brut** fournit une pile utile mais pas une API Android stable clé en main ; l'intégrer directement revient presque à maintenir son propre moteur.
- **BadVPN tun2socks** et forks anciens ne satisfont pas le critère de maintenance.
- Un serveur SOCKS/VPN distant, même gratuit, contredit le principe local et ne doit pas être introduit pour simplifier l'implémentation.

## Contrat minimal proposé pour `ForwardingEngine`

Sans imposer une bibliothèque, l'abstraction doit garantir conceptuellement :

- prise de possession exclusive du descripteur TUN ;
- démarrage/arrêt idempotents et état observable ;
- callback synchrone pour protéger chaque socket de sortie native avant `connect()`/`send()` ;
- IPv4, IPv6, TCP, UDP et ICMP/ICMPv6 nécessaire à la connectivité ;
- notification de flux original avant proxy avec tuple complet et sens ;
- compteurs bidirectionnels dont la couche de mesure est documentée ;
- observation DNS optionnelle sans modification des réponses ;
- erreurs structurées, changement d'underlay et fermeture des flux ;
- aucune télémétrie, aucun résolveur ou serveur distant implicite.

Il est prématuré de figer une signature Kotlin : les callbacks réellement disponibles dans le moteur choisi détermineront la frontière JNI/AAR.

## Décisions à intégrer dans `SPEC.md` avant le code de production

1. Corriger le diagramme pour un propriétaire unique du TUN et un flux d'événements d'observation.
2. Définir « toutes les applications » par utilisateur/profil et accepter les attributions inconnues/partagées.
3. Fixer la convention d'octets et la définition d'une connexion UDP.
4. Remplacer `IP -> domaine` par des preuves DNS temporaires, multiples et expirables.
5. Retirer `TLS_METADATA` de V1 ou autoriser explicitement l'analyse non déchiffrante des métadonnées de handshake.
6. Ajouter ICMP/ICMPv6, fragments, extensions IPv6, PMTU et absence de fuite IPv6 aux critères du moteur.
7. Définir la clé Room stable sur IP canonique et séparer les preuves de domaine.
8. Définir checkpoint, récupération après crash, mesure physique du stockage, WAL et vacuum incrémental.
9. Ajouter service au premier plan, VPN unique, révocation, réseaux sous-jacents, réseau metered, ARM64 et pages 16 Kio aux critères d'acceptation.

## Go/no-go recommandé

**No-go pour le code de production à ce stade**, conformément au point 41.  
**Go pour deux prototypes jetables et mesurés** : Firestack d'abord, hev + proxy direct local ensuite si Firestack ne fournit pas une frontière d'observation assez stable. Le résultat du spike doit être une matrice de tests sur le Galaxy A57, pas une base de code que l'on conserverait automatiquement.
