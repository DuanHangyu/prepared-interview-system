#!/bin/sh
set -eu

if [ "${1:-}" = "migrate" ] || [ "${1:-}" = "validate" ]; then
  exec java -Dloader.main=system.assessment.defense.infrastructure.deployment.DatabaseMigrationCli \
    -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher "$1"
fi

exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
