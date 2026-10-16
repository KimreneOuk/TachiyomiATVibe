#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
export ANDROID_HOME='C:\Users\User\AppData\Local\Android\Sdk'
exec python "$(dirname "$0")/windows-run-guard.py" "$@"
