# Playback User Flows

## Play
- Precondition : une piste cible existe.
- Effet : la piste devient courante et le moteur prepare sa source audio.
- Regle : si la lecture part d'une playlist ou d'un album, un `playback context` est cree ou rehydrate.
- Regle : si la lecture part d'une piste isolee, l'application cree un contexte minimal de type `single_track` ou `search_result`.

## Pause
- Precondition : une lecture est active.
- Effet : la position courante est preservee et le mini-player reste visible.
- Regle : `Pause` ne vide ni le `playback context`, ni la `priority queue`, ni l'historique.

## Next
- Regle : si la `priority queue` contient des pistes en attente, la premiere est lue.
- Regle : sinon, la lecture avance dans le `playback context`.
- Regle : si rien n'est disponible, le player termine en etat `Idle` ou applique `repeat`.
- Postcondition : l'historique est mis a jour avec la piste quittee selon la politique de tracking definie par le moteur.

## Previous
- Regle : si la piste courante a depasse le seuil de redemarrage, `Previous` redemarre cette piste.
- Regle : sinon, le player revient a l'element precedent reel de l'historique, y compris s'il venait de la `priority queue`.
- Regle : si aucun historique n'existe, le player reste sur la piste courante redemarree.

## Remove From Queue
- Regle : l'utilisateur peut supprimer une piste en attente dans la `priority queue`.
- Regle : la piste courante ne peut pas etre supprimee depuis la vue queue.
- Regle : la suppression ne modifie pas le `playback context`.
- Postcondition : l'ordre des autres pistes en attente est compacte et preserve.

## Reorder Queue
- Regle : l'utilisateur peut glisser-deposer les pistes en attente.
- Regle : le nouvel ordre s'applique uniquement aux prochaines lectures issues de la `priority queue`.
- Regle : le reorder n'a aucun effet sur l'ordre canonique du `playback context`.

## End Of Track
- Regle : le player sauvegarde la progression, met a jour l'historique et choisit la prochaine piste selon la priorite canonique.
- Regle : la fin naturelle d'une piste et l'action `Next` partagent la meme logique de resolution de la prochaine piste.

## Error Handling
- Regle : si la source audio echoue, le player expose un etat erreur actionnable.
- Regle : l'utilisateur peut reessayer, passer au titre suivant ou retirer le titre si l'erreur vient de la queue.
- Regle : une erreur sur une piste de la `priority queue` ne doit pas corrompre le `playback context`.
