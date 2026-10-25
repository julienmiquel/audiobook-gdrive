#!/usr/bin/env bash
# Génère les mp3 de démonstration (voix de synthèse macOS, textes originaux) servis par le
# Drive simulé du mode démo : app/src/main/assets/demo/.
#   civilisations_01..10.mp3 : « CIVILISATIONS - Laurent BINET » (ID3 : titre, auteur, voix, pochette)
#   chapitre_1..3.mp3        : pistes génériques des autres livres (sans voix ID3)
#   meditation.mp3           : collection _meditations
#   cover_*.jpg              : pochettes de démonstration (images de dossier Drive)
# Prérequis : macOS (say), ffmpeg avec libmp3lame.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/demo"
ART="$ROOT/tools/demo-art"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUT"

VOICE_BOOK="Audrey (Premium)"
VOICE_GENERIC="Thomas"

# mp3 mono 22,05 kHz à débit constant 32 kbit/s : léger, et durée estimable par le débit.
encode() { # $1 aiff, $2 mp3, puis paires -metadata
  local in="$1" out="$2"; shift 2
  ffmpeg -hide_banner -loglevel error -y -i "$in" -ac 1 -ar 22050 -codec:a libmp3lame -b:a 32k \
    -id3v2_version 3 "$@" "$out"
}

speak() { # $1 voix, $2 débit (mots/min), $3 texte, $4 aiff
  say -v "$1" -r "$2" -o "$4" "$3"
}

TITLES=(
  "Partie un, chapitre un : saga de Freydis. Ceci est un enregistrement de démonstration de l'application Audiothèque, lu par une voix de synthèse : le livre original n'est pas inclus. Dans Audiothèque, un livre est un dossier Google Drive, et son statut est le dossier qui le contient. Ce livre est dans le dossier En écoute : toutes ses pistes ont été téléchargées pour une écoute hors ligne."
  "Partie un, chapitre deux : saga de Freydis. La position d'écoute est enregistrée toutes les dix secondes, et à chaque pause. Quand vous reprenez la lecture, l'application recule automatiquement de cinq secondes, pour retrouver le fil de l'histoire."
  "Partie deux, chapitre un : journal de Christophe Colomb. Les chapitres sont déduits du nom des pistes. Le fichier zéro zéro trois, P deux, zéro un, se lit ainsi : piste trois, partie deux, chapitre un. La liste des chapitres est groupée par partie."
  "Partie deux, chapitre deux : journal de Christophe Colomb. Les boutons moins trente et plus trente secondes permettent de revenir en arrière ou d'avancer. Le pas du saut se règle à dix, quinze ou trente secondes, dans les réglages."
  "Partie trois, chapitre un : chroniques d'Atahualpa. La vitesse de lecture va de zéro virgule huit à deux fois, par pas d'un dixième. Elle est mémorisée pour chaque livre, et le temps restant est calculé à la vitesse en cours."
  "Partie trois, chapitre deux : chroniques d'Atahualpa. La minuterie de sommeil arrête la lecture après quinze, trente, quarante-cinq ou soixante minutes, ou à la fin du chapitre. Elle se prolonge de dix minutes d'un simple geste."
  "Partie trois, chapitre trois : chroniques d'Atahualpa. Un signet garde un passage, avec une note courte. Vous le retrouvez dans le lecteur, et dans la fiche du livre."
  "Partie quatre, chapitre un : les aventures de Cervantès. Mettre de côté range le livre dans le dossier À écouter plus tard. La position est conservée : le livre reprendra exactement là où il s'était arrêté."
  "Partie quatre, chapitre deux : les aventures de Cervantès. Chaque geste dans l'application déplace le dossier du livre dans Google Drive, aussitôt, ou dès le retour du réseau. Un bandeau Annuler reste affiché dix secondes."
  "Partie quatre, chapitre trois : les aventures de Cervantès. Vous arrivez à la fin du livre. L'application va vous proposer de le marquer comme écouté, et de le ranger dans le dossier Écouté. Rien n'est déplacé sans votre confirmation. Fin de la démonstration."
)
NAMES=(
  "Saga de Freydis (1)" "Saga de Freydis (2)"
  "Journal de Christophe Colomb (1)" "Journal de Christophe Colomb (2)"
  "Chroniques d'Atahualpa (1)" "Chroniques d'Atahualpa (2)" "Chroniques d'Atahualpa (3)"
  "Les aventures de Cervantès (1)" "Les aventures de Cervantès (2)" "Les aventures de Cervantès (3)"
)

for i in "${!TITLES[@]}"; do
  n=$((i + 1)); nn=$(printf "%02d" "$n")
  text="${TITLES[$i]}"
  [[ $n -eq 1 ]] && text="Civilisations, de Laurent Binet. $text"
  speak "$VOICE_BOOK" 185 "$text" "$TMP/civ_$nn.aiff"
  if [[ $n -eq 1 && -f "$ART/cover_civilisations.jpg" ]]; then
    # Pochette intégrée aux tags de la première piste (lue par l'app : tags ID3, APIC).
    ffmpeg -hide_banner -loglevel error -y -i "$TMP/civ_$nn.aiff" -i "$ART/cover_civilisations.jpg" \
      -map 0:a -map 1:v -ac 1 -ar 22050 -codec:a libmp3lame -b:a 32k -codec:v copy \
      -id3v2_version 3 -metadata:s:v title="Album cover" -metadata:s:v comment="Cover (front)" \
      -metadata title="${NAMES[$i]}" -metadata artist="Laurent Binet" -metadata album="Civilisations" \
      -metadata track="$n/10" -metadata NARRATOR="Audrey (voix de synthèse)" \
      "$OUT/civilisations_$nn.mp3"
  else
    encode "$TMP/civ_$nn.aiff" "$OUT/civilisations_$nn.mp3" \
      -metadata title="${NAMES[$i]}" -metadata artist="Laurent Binet" -metadata album="Civilisations" \
      -metadata track="$n/10" -metadata NARRATOR="Audrey (voix de synthèse)"
  fi
done

GENERIC=(
  "Chapitre un. Ceci est une piste de démonstration de l'application Audiothèque, lue par une voix de synthèse. Le livre original n'est pas inclus."
  "Chapitre deux. Les livres du dossier Nouveaux ne descendent sur le téléphone qu'en métadonnées : titre, auteur, durée et pochette. Commencez un livre pour le télécharger en entier."
  "Chapitre trois. Fin de cette piste de démonstration."
)
for i in "${!GENERIC[@]}"; do
  n=$((i + 1))
  speak "$VOICE_GENERIC" 190 "${GENERIC[$i]}" "$TMP/gen_$n.aiff"
  encode "$TMP/gen_$n.aiff" "$OUT/chapitre_$n.mp3" -metadata title="Chapitre $n" -metadata album="Démonstration"
done

speak "$VOICE_BOOK" 150 "Installez-vous confortablement. [[slnc 1200]] Inspirez lentement par le nez. [[slnc 2500]] Puis expirez, doucement. [[slnc 2500]] Laissez vos épaules se relâcher. [[slnc 2000]] Fin de la méditation de démonstration." "$TMP/med.aiff"
encode "$TMP/med.aiff" "$OUT/meditation.mp3" -metadata title="Méditation" -metadata album="Méditations"

# Pochettes posées dans les dossiers Drive simulés (image du dossier).
for f in "$ART"/cover_*.jpg; do
  [[ -e "$f" ]] || continue
  [[ "$(basename "$f")" == "cover_civilisations.jpg" ]] && continue
  cp "$f" "$OUT/"
done

ls -la "$OUT"
du -sh "$OUT"
