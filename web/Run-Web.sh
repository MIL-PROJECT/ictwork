#!/usr/bin/env sh
set -eu
cd -- "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
test -f app/OMNIS.war || { echo 'Missing app/OMNIS.war' >&2; exit 1; }
test -f config/application.properties || { echo 'Configure config/application.properties first. See README.md.' >&2; exit 1; }
java_bin=java
if [ -n "${JAVA_HOME:-}" ]; then java_bin="$JAVA_HOME/bin/java"; fi
port="${OMNIS_WEB_PORT:-8080}"
echo "Starting OMNIS WAR at http://127.0.0.1:$port/signin (default localhost binding). Ctrl+C stops Java."
if [ -f config/qa-config.yml ]; then
  exec "$java_bin" -Duser.timezone=Asia/Seoul -jar app/OMNIS.war --spring.profiles.active=demo "--server.port=$port" --spring.config.additional-location=file:./config/qa-config.yml
else
  exec "$java_bin" -Duser.timezone=Asia/Seoul -jar app/OMNIS.war --spring.profiles.active=demo "--server.port=$port"
fi
