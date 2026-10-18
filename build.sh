#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
LOCAL_MAVEN="$(cd .. && pwd)/env/mvn"
if [[ -f "$LOCAL_MAVEN" ]]; then
  MAVEN=(bash "$LOCAL_MAVEN")
elif command -v mvn >/dev/null 2>&1; then
  MAVEN=(mvn)
else
  printf 'Maven not found. Install Java 25 and Maven 3.9+ or provide %s\n' "$LOCAL_MAVEN" >&2
  exit 1
fi
mkdir -p .cache dist
if [[ ! -f .cache/minecraft-26.3-client.jar ]]; then
  DOWNLOAD="$(mktemp .cache/minecraft-26.3-client.XXXXXX)"
  trap 'rm -f -- "$DOWNLOAD"' EXIT
  curl -fL --retry 2 https://piston-data.mojang.com/v1/objects/e877b6a07acd633fb3bb475002175cec036e7b87/client.jar -o "$DOWNLOAD"
  printf '%s  %s\n' e877b6a07acd633fb3bb475002175cec036e7b87 "$DOWNLOAD" | sha1sum --check
  mv -- "$DOWNLOAD" .cache/minecraft-26.3-client.jar
  trap - EXIT
fi
printf '%s\n' 'e877b6a07acd633fb3bb475002175cec036e7b87  .cache/minecraft-26.3-client.jar' | sha1sum --check
"${MAVEN[@]}" --batch-mode --no-transfer-progress -N org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
  -Dfile="$PWD/.cache/minecraft-26.3-client.jar" -DgroupId=com.mojang -DartifactId=minecraft-client \
  -Dversion=26.3 -Dpackaging=jar -DgeneratePom=true
"${MAVEN[@]}" --batch-mode --no-transfer-progress clean verify "$@"
cp paper/target/sharedbody-paper-1.4.0.jar dist/SharedBody-Paper-26.3.jar
cp client/target/sharedbody-client-1.4.0.jar dist/SharedBody-Client-26.3.jar
printf '\nBuilt: %s/dist/\n' "$PWD"
