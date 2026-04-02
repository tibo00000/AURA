# Player Screen Layout

## Objectif
Visualiser une proposition de composition du `Player` afin de verifier que toutes les informations critiques tiennent sur un ecran mobile sans surcharger l'interface.

## Hypothese de layout
- la zone haute est reservee a l'identite de la piste
- la zone centrale porte les controles critiques
- la zone basse affiche le contexte source et une `priority queue` compacte
- la `priority queue` est visible en apercu sur l'ecran principal, puis extensible par scroll dans la partie basse

## Schema vertical

```mermaid
flowchart TD
    A["Top Bar\nBack | Optional More"] --> B["Artwork Hero\nLarge square cover"]
    B --> C["Track Meta\nTitle\nArtist\nAlbum or source meta"]
    C --> D["Progress Block\nElapsed | Slider | Duration"]
    D --> E["Transport Controls\nShuffle | Previous | Play/Pause | Next | Repeat"]
    E --> F["Secondary Actions\nLike | Add to playlist | Optional more"]
    F --> G["Source Context Card\nDepuis: Playlist X / Album Y / Lecture directe"]
    G --> H["Priority Queue Header\nQueue (N) | Edit hint"]
    H --> I["Queue Preview List\n1 to 3 visible rows\nDrag handle | Remove"]
    I --> J["Bottom Safe Area"]
```

## Coupe mobile approximative

```text
+--------------------------------------------------+
| Back                                  Optional   |
|                                                  |
|                [ Large Artwork ]                 |
|                                                  |
|               Track Title                        |
|               Artist Name                        |
|               Album or Context                   |
|                                                  |
|  01:12      ----------------------      03:45    |
|                                                  |
| Shuffle   Prev      Play/Pause     Next  Repeat  |
|                                                  |
|        Like        Add to playlist      ...      |
|                                                  |
|  Depuis: Playlist Chill Nuit                      |
|                                                  |
|  Queue (3)                                       |
|  [#1] Song A                     drag     remove |
|  [#2] Song B                     drag     remove |
|  [#3] Song C                     drag     remove |
|                                                  |
+--------------------------------------------------+
```

## Regle de densite
- l'ecran principal ne doit montrer que 1 a 3 lignes de queue simultanement
- si la queue depasse cette taille, le reste se parcourt par scroll vertical dans la partie basse
- la cover reste prioritaire visuellement sur les grands telephones
- sur petits ecrans, la cover peut etre reduite avant de compresser la zone controles

## Variante compacte recommandee
- si la hauteur disponible est trop faible, reduire la taille de la cover
- conserver toujours visibles :
  - titre de piste
  - slider
  - `Previous`
  - `Play/Pause`
  - `Next`
  - carte de contexte source
  - au moins un apercu de la queue

## Point de vigilance
- si la queue devient trop longue, il faudra choisir entre :
  - un scroll integre dans la partie basse
  - une bottom sheet de queue plus detaillee
- pour v1, la solution la plus simple reste une partie basse scrollable limitee a la queue
