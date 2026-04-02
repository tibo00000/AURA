# Documentation Style Guide

## Objectif
Definir des regles strictes pour que tout agent ou contributeur puisse mettre a jour la documentation AURA sans introduire de bruit, de temporalite inutile ou d'ambiguite.

## Principes fondamentaux
- La documentation decrit un etat de reference, pas un journal de changements.
- La documentation doit etre lisible par un humain et exploitable par une machine.
- Une information importante ne doit exister qu'a un seul endroit canonique.
- Chaque document doit rester stable dans sa responsabilite et dans sa structure.

## Regles de redaction
- Ecrire de facon normative, precise et intemporelle.
- Privilegier des phrases declaratives courtes et non ambigues.
- Utiliser un vocabulaire stable pour les memes concepts dans toute la documentation.
- Distinguer clairement les faits, les decisions et les hypotheses.
- Eviter toute formulation dependante du moment ou de la personne qui ecrit.

## Formulations interdites
- marqueurs temporels comme `nouveau`, `ancien`, `actuellement` quand ils ne sont pas dates et justifies
- etiquettes comme `[NOUVEAU]`, `[TODO]`, `[WIP]`, `[TEMP]`
- commentaires de commit ou de conversation injectes dans les docs
- formulations floues comme `etc.`, `a voir`, `plus tard` sans section dediee
- references implicites comme `comme vu plus haut` si un lien ou un nom de section peut etre donne

## Formulations autorisees
- une evolution future peut etre documentee si elle est clairement placee dans une section `Evolutions prevues`, `Hors perimetre v1` ou `Point d'extension`
- une hypothese peut etre documentee si elle est explicitement etiquetee comme telle
- une limitation peut etre documentee si elle est precise et actionnable

## Structure des documents
- Un fichier couvre un seul sujet principal.
- Un titre unique en tete de fichier.
- Des sections stables avec des noms explicites.
- Pas de sections decoratives ou narratives sans valeur documentaire.
- Si un document decrit un comportement, il doit contenir au minimum son objectif, ses regles et ses limites.

## Regles de maintenance
- Lorsqu'une information change, mettre a jour le document canonique au lieu d'ajouter une note temporaire.
- Si un document change de role, preferer creer un nouveau document cible et retirer l'ancien proprement.
- Toute creation, suppression ou deplacement de fichier impose une mise a jour de `docs/README.md`, `llms.txt` et `llms-full.txt`.
- Aucun fichier ne doit etre reference dans `llms.txt` s'il n'existe pas.

## Regles de coherence
- Le meme terme doit designer le meme concept partout.
- Les noms d'ecrans, d'entites, de tables et d'artefacts doivent rester coherents entre produit, domaine, Android, serveur et MCP.
- Une regle definie dans un document de domaine ne doit pas etre contredite dans un document d'ecran ou d'API.

## Regles specifiques aux user flows
- Chaque user flow doit documenter un objectif utilisateur clair.
- Chaque user flow doit lister les preconditions.
- Chaque user flow doit decrire le chemin ecran par ecran.
- Chaque user flow doit nommer explicitement les boutons ou actions.
- Chaque user flow doit indiquer les etats d'erreur et le resultat attendu.
- Les user flows ne doivent pas melanger comportement reel et idee non decidee.
- Les verbes d'action doivent etre explicites. Eviter des formulations ambigues comme `ouvrir un titre` si l'action reelle est `lancer la lecture`, `ouvrir le menu contextuel`, `ouvrir la page artiste` ou `ouvrir la page album`.

## Regles specifiques aux schemas et contrats
- Les schemas de donnees doivent etre complets, sans champs implicites critiques.
- Les champs doivent porter des noms stables.
- Les contrats d'API, de base de donnees et de domaine doivent utiliser la meme terminologie quand ils designent le meme objet.
- Si un detail n'est pas decide, il doit etre signale comme hypothese ou decision a prendre, pas masque par une formulation vague.

## Checklist avant validation
- Le document est-il intemporel ?
- Le document evite-t-il les marqueurs de changement temporaires ?
- Le sujet canonique est-il unique et clair ?
- Les termes utilises sont-ils coherents avec le reste du depot ?
- Le fichier doit-il etre ajoute ou mis a jour dans `llms.txt` et `llms-full.txt` ?
