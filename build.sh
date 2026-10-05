#!/bin/sh
set -eu
cd "$(dirname "$0")"
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
exec ./gradlew build qaJar "$@"
