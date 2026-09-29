# Moniteur de confidentialité réseau Android — Spécification de référence V1

## 1. Objectif du projet

Créer une application Android personnelle qui surveille les communications réseau produites par les applications installées sur l'appareil.

L'objectif principal est de répondre à la question :

> Quelles applications communiquent vers l'extérieur, avec quelles destinations, quelle quantité de données est échangée, et quelles destinations sont des services connus de mesure d'audience, de publicité ou de pistage ?

Il s'agit d'une **application de surveillance et d'analyse de la confidentialité**, pas d'un outil d'inspection des paquets.

La V1 NE DOIT PAS déchiffrer le trafic TLS/HTTPS et NE DOIT PAS inspecter le contenu des données échangées par les applications.

L'application est destinée à un seul appareil personnel et n'a pas vocation à être distribuée publiquement.

---

# 2. Environnement cible

Appareil cible :

- Samsung Galaxy A57 5G
- Android 16
- Niveau d'API 36
- ARM64

Technologies :

- Kotlin
- SDK Android natif
- Jetpack Compose
- Room
- SQLite

Exigences de SDK :

- `minSdk = 36`
- `targetSdk = 36`
- `compileSdk = 36`

Aucune compatibilité avec Android 15 ou une version antérieure n'est demandée.

N'ajoutez pas de couches de compatibilité pour les anciennes versions d'Android.

---

# 3. Principe fondamental de confidentialité

L'application elle-même DOIT respecter la confidentialité qu'elle est conçue pour analyser.

Toute la surveillance et toute l'analyse DOIVENT avoir lieu localement, sur l'appareil Android.

L'application NE DOIT PAS transmettre :

- les domaines surveillés ;
- les adresses IP de destination ;
- les informations d'utilisation des applications ;
- les informations sur les applications installées ;
- les sessions de surveillance ;
- les statistiques réseau ;
- les requêtes de classification ;
- les métadonnées recueillies

à un service extérieur.

Aucun SDK de mesure d'audience ni de télémesure ne doit être inclus dans la V1.

Les bases de classification des traqueurs doivent être disponibles localement.

---

# 4. Périmètre de la V1

La V1 est avant tout une **application de surveillance du réseau**.

Elle DOIT permettre :

1. de démarrer une session de surveillance ;
2. d'arrêter une session de surveillance ;
3. de surveiller toutes les applications ;
4. en option, de ne surveiller que certaines applications choisies ;
5. de déterminer quelle application Android est à l'origine d'un flux réseau ;
6. d'observer les communications TCP et UDP ;
7. de prendre en charge IPv4 et IPv6 ;
8. d'identifier les adresses IP distantes ;
9. d'identifier les ports distants ;
10. d'identifier les noms de domaine lorsqu'ils sont raisonnablement observables ;
11. de mesurer les octets envoyés ;
12. de mesurer les octets reçus ;
13. de compter les connexions ;
14. de classer les destinations connues ;
15. de regrouper les résultats ;
16. d'enregistrer localement les sessions de surveillance terminées ;
17. de consulter les sessions précédentes ;
18. de contrôler automatiquement la taille de la base de données et la durée de conservation.

La V1 NE DOIT PAS mettre en place de blocage du trafic.

L'architecture DOIT cependant permettre d'ajouter proprement le blocage du trafic en V2.

---

# 5. Hors du périmètre de la V1 (explicitement)

Ne mettez PAS en place :

- l'interception TLS ;
- le déchiffrement HTTPS ;
- des certificats d'interception (MITM, « homme du milieu ») ;
- l'inspection du contenu des données ;
- l'enregistrement du contenu des paquets ;
- la capture permanente de paquets ;
- l'enregistrement de fichiers PCAP ;
- des règles de pare-feu ;
- le blocage de domaines ;
- le blocage d'applications ;
- le blocage des traqueurs ;
- la synchronisation à distance ;
- les services en ligne (cloud) ;
- les comptes utilisateur ;
- la télémesure ;
- la mesure d'audience à distance ;
- l'analyse chronologique ;
- les graphiques historiques d'événements réseau individuels.

Ne les mettez pas en place, sauf demande explicite ultérieure.

---

# 6. Architecture réseau

Utilisez le `VpnService` d'Android.

Le VPN est un **VPN local de surveillance**.

Il n'y a pas de serveur VPN distant.

Architecture de principe :

```text
Android applications
        |
        v
   Android VpnService
        |
        v
      TUN
        |
        +--------------------+
        |                    |
        v                    v
Monitoring pipeline    Forwarding engine
        |                    |
        v                    v
   Flow analysis           Internet
```

L'application doit continuer à fournir un accès normal à Internet pendant que la surveillance est active.

---

# 7. Moteur d'acheminement

NE réécrivez PAS une pile TCP/IP à partir de zéro.

Utilisez une solution d'acheminement existante et éprouvée, compatible avec :

- Android 16 / API 36 ;
- ARM64 ;
- TCP ;
- UDP ;
- IPv4 ;
- IPv6 ;
- le `VpnService` d'Android.

Avant de choisir une dépendance d'acheminement, vérifiez :

1. la licence ;
2. l'état récent de sa maintenance ;
3. la compatibilité avec Android 16 ;
4. la compatibilité avec ARM64 ;
5. la prise en charge de TCP ;
6. la prise en charge d'UDP ;
7. la prise en charge d'IPv6.

La solution d'acheminement DOIT être isolée derrière une interface d'abstraction :

```text
ForwardingEngine
```

Le reste de l'application ne doit pas dépendre directement de la bibliothèque d'acheminement choisie.

C'est important, car la solution d'acheminement pourra être remplacée plus tard.

Ne choisissez pas et n'intégrez pas une dépendance obscure ou non maintenue simplement parce qu'elle est facile à utiliser.

---

# 8. Attribution à une application

Pour chaque connexion TCP/UDP observée, déterminez l'application Android qui en est à l'origine.

Utilisez les API Android modernes disponibles au niveau 36, notamment, lorsque c'est pertinent :

```text
ConnectivityManager.getConnectionOwnerUid(...)
```

Retrouvez successivement :

```text
network flow
    ->
UID
    ->
package name
    ->
application information
```

Exemple :

```text
TCP connection
    ->
UID 10342
    ->
com.instagram.android
    ->
Instagram
```

L'attribution à une application doit avoir lieu lorsqu'un flux est découvert.

Elle ne devrait PAS avoir à être recalculée pour chaque paquet.

Conservez en mémoire une correspondance entre les flux actifs et l'identité de leur application.

---

# 9. Choix des applications

La surveillance DOIT proposer deux modes.

### Surveillance globale

Surveiller toutes les applications qui passent par le VPN local.

### Surveillance des applications choisies

Permettre à l'utilisateur de choisir une ou plusieurs applications installées.

Utilisez, lorsque c'est pertinent, les mécanismes de filtrage par application du VPN Android, par exemple :

```text
VpnService.Builder.addAllowedApplication(...)
```

Le mode « applications choisies » est particulièrement important, car il permet d'analyser une seule application sans le bruit réseau des autres.

---

# 10. Traitement des paquets

Les paquets peuvent être analysés en mémoire pour les besoins de la surveillance.

Prendre en charge au minimum :

- IPv4 ;
- IPv6 ;
- TCP ;
- UDP ;
- DNS lorsqu'il est observable.

Créez un composant clairement identifié :

```text
PacketParser
```

L'analyseur doit fournir des métadonnées structurées au reste de l'application.

Le contenu brut des paquets NE DOIT PAS être enregistré.

---

# 11. Suivi des flux

Créez, en mémoire, un :

```text
FlowTracker
```

Un flux réseau technique peut être identifié par le groupe de valeurs approprié, comprenant :

- le protocole ;
- l'adresse locale ;
- le port local ;
- l'adresse distante ;
- le port distant.

Suivez les flux actifs de façon suffisante pour calculer :

- l'application ;
- la destination ;
- le protocole ;
- le port distant ;
- les octets envoyés ;
- les octets reçus ;
- le cycle de vie de la connexion.

Les flux techniques existent principalement en mémoire.

Ils ne sont PAS l'unité principale enregistrée durablement.

---

# 12. Identification des domaines

Essayez d'associer les destinations réseau à des noms de domaine lorsque c'est raisonnablement possible.

Mécanisme principal :

- observer les requêtes et réponses DNS visibles par le VPN.

Conservez des correspondances temporaires, par exemple :

```text
graph.example.com
    ->
203.0.113.42
```

L'information de domaine doit indiquer sa provenance.

Exemple d'énumération :

```text
DomainSource

DNS_OBSERVED
TLS_METADATA
UNKNOWN
```

Si le domaine ne peut pas être déterminé de façon fiable :

```text
domain = null
domainSource = UNKNOWN
```

N'inventez jamais un nom de domaine.

La résolution DNS inverse NE DOIT PAS être présentée comme la preuve qu'une application a contacté ce nom d'hôte.

L'application doit gérer correctement le DNS chiffré et les autres situations où le domaine ne peut pas être observé.

Une destination connue uniquement par son adresse IP est valable.

---

# 13. Classification des destinations

L'application doit aider à déterminer si des destinations sont liées à des services sensibles pour la vie privée.

Utilisez des jeux de données locaux reconnus sur les traqueurs.

Sources initiales :

- DuckDuckGo Tracker Radar ;
- les listes de traqueurs et de services de Disconnect.

Le système de classification doit être isolé derrière :

```text
DestinationClassifier
```

Composants possibles de la mise en œuvre :

```text
DestinationClassifier
    |
    +-- TrackerRadarSource
    |
    +-- DisconnectSource
```

Les jeux de données doivent être stockés localement.

N'interrogez pas une API de classification distante.

---

# 14. Catégories de classification

Gardez des catégories visibles simples.

Utilisez des catégories proches de :

```text
FUNCTIONAL
CDN
ANALYTICS
ADVERTISING
TRACKING
CRASH_REPORTING
UNKNOWN
```

En interne, plusieurs classifications peuvent être conservées si nécessaire.

Ne forcez pas chaque destination à entrer dans une catégorie.

Lorsque les éléments sont insuffisants :

```text
UNKNOWN
```

est le bon résultat.

---

# 15. Classification de confidentialité

Indiquez séparément si une destination est connue comme traqueur.

Exemple :

```text
PrivacyClassification

KNOWN_TRACKER
POTENTIAL_TRACKER
UNCLASSIFIED
```

N'assimilez pas automatiquement la mesure d'audience à un comportement malveillant.

N'affirmez pas qu'une entreprise vend des informations sur l'utilisateur en vous fondant uniquement sur la destination réseau.

L'application observe des destinations et des métadonnées, pas le contenu réel des données transmises ni l'usage qui en est fait ensuite.

---

# 16. Justification de la classification

Conservez les éléments qui justifient une classification.

Exemple :

```text
domain:
analytics.example.com

owner:
Example Corp

category:
ANALYTICS

privacyClassification:
KNOWN_TRACKER

sources:
TRACKER_RADAR
DISCONNECT

confidence:
HIGH
```

Système de niveau de confiance suggéré :

```text
HIGH
MEDIUM
UNKNOWN
```

Principe général :

- HIGH : classification solide ou confirmée par plusieurs sources ;
- MEDIUM : classification fournie par une seule source reconnue ;
- UNKNOWN : éléments insuffisants.

Évitez de présenter de simples suppositions comme des faits.

---

# 17. Modèle de données enregistrées

Utilisez Room au-dessus de SQLite.

N'enregistrez pas les paquets bruts.

Les principales notions enregistrées devraient être approximativement :

```text
MonitoringSession
ApplicationSummary
DestinationAggregate
DestinationInfo
```

Le schéma Room normalisé exact peut être choisi pendant la mise en œuvre, à condition de respecter le comportement décrit ci-dessous.

---

# 18. Session de surveillance

Une session correspond à la période entre l'appui sur Démarrer et l'appui sur Arrêter.

Modèle de principe :

```text
MonitoringSession

id
startedAt
endedAt
status
monitoringMode
```

Valeurs d'état possibles :

```text
RUNNING
COMPLETED
FAILED
```

---

# 19. Regroupement des données enregistrées

N'enregistrez PAS chaque connexion séparément.

Regroupez l'activité réseau approximativement par :

```text
Session
+ Application
+ Destination
+ Protocol
+ Remote port
```

Exemple :

Au lieu d'enregistrer :

```text
Instagram
TCP connection #1 -> example.com:443
14 KB sent

Instagram
TCP connection #2 -> example.com:443
8 KB sent
```

enregistrez :

```text
Instagram
example.com
TCP/443

connections = 2
bytesSent = 22 KB
bytesReceived = ...
```

Ce regroupement est essentiel pour la V1.

L'application est conçue pour donner une **vue d'ensemble**, pas une chronologie des paquets ou des événements.

---

# 20. Données regroupées par destination

Enregistrez, dans le principe :

```text
DestinationAggregate

sessionId

applicationUid
packageName

remoteIp
domain
domainSource

protocol
remotePort

connectionCount

bytesSent
bytesReceived

classificationId
```

La mise en œuvre Room finale peut normaliser les champs répétés lorsque c'est pertinent.

---

# 21. Pas de chronologie

L'utilisateur n'a PAS besoin de savoir si :

```text
tracker.example
```

a été contacté à 14 h 32 puis de nouveau à 14 h 47.

Ne construisez pas de chronologie des événements réseau.

N'enregistrez pas l'horodatage de chaque connexion dans le seul but de reconstituer une chronologie.

L'information utile est :

```text
Application X
contacted destination Y

N connections
X bytes sent
Y bytes received
classification Z
```

---

# 22. Limites de stockage

L'application DOIT appliquer des limites de stockage.

Valeurs par défaut :

```text
maximum database/storage usage = 500 MB
maximum retention = 30 days
```

Limites de stockage configurables :

```text
100 MB
250 MB
500 MB
1 GB
2 GB
```

Durée de conservation configurable :

```text
7 days
30 days
90 days
unlimited
```

Lorsqu'une limite de stockage est dépassée :

1. supprimez les sessions terminées les plus anciennes ;
2. supprimez des sessions entières plutôt que des lignes au hasard, lorsque c'est possible ;
3. continuez jusqu'à repasser sous la limite configurée.

Ne supprimez jamais la session de surveillance en cours lors du nettoyage automatique du stockage.

Affichez :

```text
Storage used: X MB / Y MB
```

dans les réglages.

Proposez une action :

```text
Erase history
```

---

# 23. Principes de l'interface

L'interface doit privilégier des informations de confidentialité compréhensibles plutôt que des détails réseau bruts.

Préférez :

```text
Instagram
-> Example Analytics
-> Analytics / Known tracker
-> 84 KB sent
```

à :

```text
UID 10342
TCP
203.0.113.42:443
```

Les détails techniques restent accessibles, mais au second plan.

Utilisez Jetpack Compose.

---

# 24. Écran 1 — Surveillance

Écran principal.

Exemple à l'arrêt :

```text
NETWORK MONITOR

INACTIVE

[ START MONITORING ]

Last session
24 min
18 applications
126 destinations

[ VIEW RESULTS ]
```

Pendant la surveillance :

```text
MONITORING

12 min

Applications: 14
Destinations: 87

[ STOP ]
```

N'affichez pas chaque paquet en temps réel.

---

# 25. Configuration de la surveillance

Avant de démarrer une session, permettez de choisir :

```text
Monitor:

(*) All applications
( ) Selected applications
```

Pour les applications choisies, affichez les applications installées avec :

- l'icône de l'application ;
- le nom de l'application ;
- le nom du paquet lorsque c'est utile ;
- une case à cocher.

Exemple :

```text
[x] Instagram
[x] Firefox
[ ] YouTube
[x] Reddit
```

---

# 26. Écran 2 — Résultats de la session

Vue principale :

```text
Session
45 minutes

Applications: 18
Destinations: 126

Sent: 24 MB
Received: 382 MB
```

Regroupement principal :

```text
BY APPLICATION
```

Exemple :

```text
Instagram
12 destinations
3 known trackers
340 KB sent
8.2 MB received

Firefox
34 destinations
5 known trackers
1.2 MB sent
14 MB received
```

Permettez de filtrer par catégories, par exemple :

```text
All
Analytics
Advertising
Tracking
Unknown
```

Proposez aussi une vue organisée par destination si c'est réalisable.

---

# 27. Écran 3 — Détails d'une application

Exemple :

```text
INSTAGRAM

Destinations: 12
Known trackers: 3

Sent: 340 KB
Received: 8.2 MB
```

Affichez la liste des destinations :

```text
graph.example.com
Example Corp
FUNCTIONAL

Sent: 122 KB
Received: 1.4 MB
```

et :

```text
analytics.example.com
Example Corp

ANALYTICS
KNOWN TRACKER
Confidence: HIGH

Sent: 18 KB
Received: 7 KB
Connections: 17
```

Les destinations inconnues doivent être affichées honnêtement :

```text
203.0.113.42

UNKNOWN DESTINATION

Sent: 4 KB
Received: 12 KB
```

---

# 28. Détails techniques d'une destination

À l'ouverture d'une destination, affichez des informations techniques telles que :

- le domaine ;
- l'IP ;
- la provenance du domaine ;
- le propriétaire lorsqu'il est connu ;
- la catégorie ;
- la classification de confidentialité ;
- la source de la classification ;
- le niveau de confiance ;
- le protocole ;
- le port distant ;
- les octets envoyés ;
- les octets reçus ;
- le nombre de connexions.

Ne surchargez pas l'écran principal avec ces détails.

---

# 29. Écran 4 — Historique

Historique simple des sessions.

Exemple :

```text
Today
14:02
27 min
18 apps

Yesterday
20:41
52 min
24 apps

24 September
09:14
12 min
11 apps
```

Choisir une session ouvre ses résultats.

Permettez la suppression d'une session.

---

# 30. Écran 5 — Réglages

Inclure au minimum :

```text
Storage limit
500 MB

Retention
30 days

Storage used
183 MB / 500 MB

[ ERASE HISTORY ]
```

Des informations supplémentaires pour le développement ou la mise au point peuvent être placées ici si nécessaire.

---

# 31. Statistiques de synthèse

Pour chaque application, calculez au minimum :

- le total des octets envoyés ;
- le total des octets reçus ;
- le nombre de destinations ;
- le nombre de destinations connues comme traqueurs ;
- le nombre de destinations de mesure d'audience ;
- le nombre de destinations publicitaires ;
- le nombre de destinations inconnues.

Calculez aussi la répartition du trafic lorsque c'est utile.

Exemple :

```text
Total sent:
8.3 MB

Sent to Analytics/Tracking destinations:
115 KB
```

NE transformez PAS automatiquement ces chiffres en une note de confidentialité arbitraire.

Le volume de trafic ne suffit pas à déterminer l'effet sur la vie privée.

---

# 32. Préparation de l'architecture de la V2

La V2 pourra ajouter le blocage.

L'architecture doit donc permettre d'insérer plus tard un :

```text
PolicyEngine
```

Dans le principe :

```text
V1

Flow
 |
 v
Classifier
 |
 v
Observer
 |
 v
Forwarding
```

À l'avenir :

```text
V2

Flow
 |
 v
Classifier
 |
 v
PolicyEngine
 |       |
ALLOW   BLOCK
 |
 v
Forwarding
```

NE mettez PAS en place le comportement de `PolicyEngine` dans la V1.

Il suffit de définir des interfaces propres pour pouvoir l'ajouter plus tard.

---

# 33. Organisation suggérée des modules et paquets

Une structure raisonnable est :

```text
app/

ui/
    monitoring/
    results/
    appdetail/
    history/
    settings/

monitoring/
    MonitoringVpnService
    PacketParser
    FlowTracker
    AppResolver
    DomainResolver

forwarding/
    ForwardingEngine
    implementation/

classification/
    DestinationClassifier
    TrackerRadarSource
    DisconnectSource

data/
    database/
    repository/
    model/

privacy/
    RetentionManager
```

Cette structure est indicative : ce n'est pas une obligation si une architecture équivalente plus propre est justifiée.

Ne regroupez pas toutes les fonctions VPN et réseau dans une seule grosse classe `VpnService`.

---

# 34. Principes de performance

Le traitement des paquets peut être très fréquent.

Par conséquent :

- évitez les allocations mémoire inutiles dans les boucles de traitement des paquets ;
- évitez d'écrire dans la base de données à chaque paquet ;
- gardez les compteurs actifs en mémoire ;
- regroupez les mises à jour de la base de données ;
- tenez le travail de l'interface à l'écart du traitement des paquets ;
- n'effectuez pas de recherches coûteuses dans la base des traqueurs à chaque paquet ;
- gardez en cache les classifications des destinations ;
- gardez en cache la résolution des UID des applications ;
- gardez en cache, de façon appropriée, les correspondances DNS.

L'exactitude passe avant l'optimisation prématurée, mais les inefficacités évidentes par paquet doivent être évitées.

---

# 35. Gestion des pannes

Les pannes de la surveillance ne doivent pas corrompre silencieusement les données de session.

Si la surveillance s'arrête de façon inattendue :

```text
session.status = FAILED
```

lorsque c'est possible.

L'interface doit indiquer que la session ne s'est pas terminée normalement.

Les pannes de l'acheminement réseau doivent être signalées clairement pendant le développement.

---

# 36. Tests obligatoires

Écrivez des tests unitaires, lorsque c'est pertinent, pour :

### Analyse des paquets

Vérifier :

- IPv4 ;
- IPv6 ;
- TCP ;
- UDP ;
- l'analyse DNS.

### Suivi des flux

Vérifier :

- que les paquets d'un même flux sont correctement associés ;
- que des flux différents restent distincts ;
- que les compteurs sont exacts.

### Attribution à une application

Vérifier :

```text
network flow -> UID -> package
```

lorsque c'est testable.

### Regroupement

Vérifier que deux connexions ayant :

```text
same session
same application
same destination
same protocol
same remote port
```

sont regroupées.

Vérifier que deux applications différentes contactant la même destination restent séparées.

### Classification

Vérifier des domaines de test connus avec les jeux de données fournis.

Vérifier que les destinations inconnues restent :

```text
UNKNOWN
```

### Conservation

Vérifier :

- que les sessions plus anciennes que la durée de conservation configurée sont supprimées ;
- que les sessions les plus anciennes sont supprimées lorsque les limites de stockage sont dépassées ;
- que la session active n'est pas supprimée.

### Migrations de la base de données

Les modifications du schéma Room doivent utiliser des migrations explicites dès qu'il existe des données de développement enregistrées.

Ne comptez pas sur des migrations destructives pour les mises à jour normales.

---

# 37. Essai d'acceptation fonctionnel

Le scénario suivant DOIT fonctionner avant que la V1 soit considérée comme fonctionnelle :

1. Installer l'application sur le Samsung Galaxy A57.
2. Ouvrir l'application.
3. Choisir la surveillance d'une seule application installée.
4. Démarrer la surveillance.
5. Ouvrir et utiliser l'application choisie pendant plusieurs minutes.
6. Revenir à Network Monitor.
7. Arrêter la surveillance.
8. Ouvrir les résultats de la session.

Résultat attendu :

- l'application surveillée est correctement identifiée ;
- les destinations extérieures sont visibles ;
- les noms de domaine apparaissent lorsqu'ils sont observables ;
- les adresses IP restent disponibles ;
- les volumes envoyés et reçus sont affichés ;
- les destinations sont regroupées ;
- les services connus de mesure d'audience ou de pistage sont classés ;
- les services inconnus restent inconnus ;
- aucun contenu des données n'est enregistré ;
- Internet a continué à fonctionner pendant la surveillance.

---

# 38. Exigences d'acceptation en matière de sécurité et de confidentialité

La V1 doit respecter :

**PRIV-01**

Aucune métadonnée surveillée n'est transmise à un serveur extérieur.

**PRIV-02**

Aucun contenu des données des applications n'est enregistré.

**PRIV-03**

Aucune interception TLS n'est effectuée.

**PRIV-04**

La classification des traqueurs a lieu localement.

**PRIV-05**

Aucun SDK tiers de mesure d'audience ou de télémesure n'est inclus.

**PRIV-06**

L'historique peut être supprimé localement par l'utilisateur.

---

# 39. Stratégie de développement

N'essayez PAS de réaliser toute l'application en une seule passe incontrôlée.

Le développement doit avancer par étapes.

Jalons recommandés :

### Jalon 1 — Squelette du projet

Créer :

- le projet Android ;
- Kotlin ;
- Compose ;
- Room ;
- la structure de l'architecture et des paquets ;
- une navigation de base ;
- des écrans provisoires.

Pas encore de mise en œuvre du VPN.

### Jalon 2 — VpnService de base

Mettre en place :

- la demande d'autorisation VPN ;
- le service de premier plan ;
- la création du TUN ;
- le cycle de vie démarrage/arrêt.

Prouver que le service fonctionne correctement.

### Jalon 3 — Acheminement

Intégrer le moteur d'acheminement choisi.

Condition d'acceptation :

> Internet fonctionne normalement pendant que la surveillance par VPN est active.

Ne pas continuer tant que ce n'est pas fiable.

### Jalon 4 — Analyse des paquets

Mettre en place :

- IPv4 ;
- IPv6 ;
- TCP ;
- UDP.

Fournir des flux structurés.

### Jalon 5 — Attribution à une application

Mettre en place :

```text
Flow -> UID -> application
```

Valider avec des applications connues.

### Jalon 6 — Résolution DNS et domaines

Observer le DNS lorsqu'il est disponible.

Conserver temporairement les correspondances :

```text
domain <-> IP
```

Gérer correctement les domaines inconnus.

### Jalon 7 — Regroupement des flux

Mettre en place le suivi des flux en mémoire et l'enregistrement des résultats de session regroupés.

### Jalon 8 — Enregistrement avec Room

Enregistrer :

- les sessions ;
- les applications ;
- les données regroupées par destination ;
- les classifications.

### Jalon 9 — Classification des destinations

Intégrer localement les jeux de données Tracker Radar et Disconnect.

Ajouter :

- le propriétaire ;
- la catégorie ;
- la classification comme traqueur ;
- les justifications ;
- le niveau de confiance.

### Jalon 10 — Interface des résultats

Mettre en place :

- la vue d'ensemble de la session ;
- la liste des applications ;
- les détails d'une application ;
- les détails d'une destination ;
- les filtres.

### Jalon 11 — Historique et conservation

Mettre en place :

- l'historique des sessions ;
- la suppression ;
- la conservation par défaut de 30 jours ;
- la limite de stockage par défaut de 500 Mo ;
- des réglages configurables.

### Jalon 12 — Consolidation

Exécuter :

- les tests unitaires ;
- les tests d'intégration ;
- les tests de performance ;
- de longues sessions de surveillance ;
- des tests IPv6 ;
- des tests de panne DNS ;
- des tests avec des domaines inconnus.

---

# 40. Consignes de travail pour Codex

Avant de réaliser chaque jalon :

1. examiner le dépôt existant ;
2. expliquer les modifications prévues ;
3. indiquer les fichiers qui seront créés ou modifiés ;
4. indiquer les API et dépendances Android concernées ;
5. signaler les incertitudes avant tout changement important d'architecture.

Ne modifiez pas en silence l'architecture définie par cette spécification.

Si une exigence technique est impossible ou inadaptée sur Android 16, expliquez pourquoi et proposez des solutions de remplacement avant de la modifier.

Pour les dépendances tierces :

- vérifier la maintenance ;
- vérifier la licence ;
- vérifier la compatibilité avec Android 16 / API 36 ;
- éviter les dépendances inutiles.

Préférez les API Android officielles chaque fois que c'est possible.

Ne réalisez pas les fonctions prévues pour la V2, sauf si c'est nécessaire pour garder des interfaces propres.

---

# 41. Première tâche pour Codex

NE commencez PAS par réaliser toute la spécification.

La première tâche est seulement :

> Examinez cette spécification du point de vue de l'ingénierie Android 16. Repérez toute hypothèse technique incorrecte, risquée, insuffisamment précisée ou susceptible de poser problème. Examinez en particulier l'architecture `VpnService` proposée, les exigences d'acheminement, l'attribution à une application avec les API donnant l'UID propriétaire d'une connexion, la prise en charge d'IPv6, l'observation du DNS et la stratégie de regroupement dans Room.
>
> N'écrivez pas encore de code de production.
>
> Pour chaque problème, expliquez :
>
> 1. l'exigence concernée ;
> 2. pourquoi elle peut poser problème ;
> 3. le comportement ou l'API d'Android 16 en jeu ;
> 4. la modification recommandée ;
> 5. si la modification change le comportement du produit ou seulement sa mise en œuvre.
>
> Recommandez aussi des moteurs d'acheminement maintenus et compatibles avec Android 16/ARM64, en précisant leurs licences et leurs avantages et inconvénients.
>
> Ne repensez pas le produit, sauf nécessité technique.
