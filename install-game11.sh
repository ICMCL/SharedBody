#!/usr/bin/env bash
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
PROJECT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
GAME=/srv/mc-manager/games/11
PLUGIN="$PROJECT/dist/SharedBody-Paper-26.3.jar"
README="$PROJECT/README.md"
CHANGELOG="$PROJECT/CHANGELOG.md"

if [[ $EUID -ne 0 ]]; then
  exec sudo -- bash "$PROJECT/install-game11.sh"
fi

[[ -f "$PLUGIN" && -f "$README" && -f "$CHANGELOG" ]] || { echo "Missing built plugin, README or CHANGELOG." >&2; exit 1; }
[[ -d "$GAME" && ! -L "$GAME" && "$(realpath -- "$GAME")" == "$GAME" ]] \
  || { echo "Game 11 directory is missing or redirects elsewhere." >&2; exit 1; }
[[ ! -L "$GAME/plugins" && ! -L "$GAME/plugins/SharedBody" ]] \
  || { echo "Refusing symlinked plugin directories." >&2; exit 1; }

if [[ -d "$GAME/plugins" ]]; then
  for jar in "$GAME/plugins/"*.jar; do
    [[ -f "$jar" ]] || continue
    [[ "$(basename -- "$jar")" == SharedBody-Paper-26.3.jar ]] && continue
    entries="$(unzip -Z1 "$jar")"
    if grep -qx plugin.yml <<< "$entries"; then
      descriptor="$(unzip -p "$jar" plugin.yml)"
      if grep -q '^main: dev\.sharedbody\.paper\.SharedBodyPlugin' <<< "$descriptor"; then
        echo "Another SharedBody JAR already exists: $jar; remove the duplicate before installing." >&2
        exit 1
      fi
    fi
  done
fi

DEST="$GAME/plugins/SharedBody-Paper-26.3.jar"
DOC="$GAME/plugins/SharedBody/README.md"
HISTORY="$GAME/plugins/SharedBody/CHANGELOG.md"
[[ ! -L "$DEST" && ! -L "$DOC" && ! -L "$HISTORY" ]] \
  || { echo "Refusing symlinked destination files." >&2; exit 1; }
install -d -o mcmanager-worker -g mcmanager -m 2750 "$GAME/plugins" "$GAME/plugins/SharedBody"
if [[ -f "$DEST" ]] && ! cmp -s "$PLUGIN" "$DEST"; then
  BACKUP="$GAME/plugins/SharedBody/previous-$(date -u +%Y%m%dT%H%M%SZ).jar.disabled"
  install -o mcmanager-worker -g mcmanager -m 0640 "$DEST" "$BACKUP"
  echo "Previous plugin preserved at $BACKUP"
fi
install -o mcmanager-worker -g mcmanager -m 0640 "$PLUGIN" "$DEST"
install -o mcmanager-worker -g mcmanager -m 0640 "$README" "$DOC"
install -o mcmanager-worker -g mcmanager -m 0640 "$CHANGELOG" "$HISTORY"
cmp -- "$PLUGIN" "$DEST"
cmp -- "$README" "$DOC"
cmp -- "$CHANGELOG" "$HISTORY"
sha256sum -- "$DEST"
echo "Installed plugin, README and CHANGELOG into Game 11."
echo "No game was restarted. Start Game 11 from the manager to load the plugin."
echo "Every participant still needs Fabric API and SharedBody-Client-26.3.jar on their client."
