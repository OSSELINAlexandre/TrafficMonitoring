> Traduction française de la description de la PR #2 (branche `cursor/wifi-underlay-bonus-5fd4`), « Prefer validated Wi-Fi and record the session underlay ».

## Résumé

Le Wi-Fi est un bonus ajouté au noyau de surveillance V1 existant. Les données mobiles / la 5G restent la cible principale.

- Une session enregistre le réseau sous-jacent choisi au démarrage (`WIFI` ou `CELLULAR`) dans `monitoring_sessions`, avec une migration Room 1→2. Si le réseau préféré change ensuite, la valeur enregistrée est mise à jour. Il n'y a pas d'essai de changement de réseau en cours de session et le VPN n'est pas recréé.
- Le rattachement des sockets, `setUnderlyingNetworks` et le DNS système suivent les mêmes priorités que le prototype : un Wi-Fi validé l'emporte lorsqu'il est présent ; sinon, le réseau mobile est utilisé.
- Résultats et Surveillance affichent « Session en Wi‑Fi » ou « Session en 5G / mobile » d'après ce type enregistré.
- L'attribution aux applications, les destinations et la classification locale ne changent pas et fonctionnent quel que soit le réseau sous-jacent.

`:app:assembleDebug` et `:app:testDebugUnitTest` ont réussi.
