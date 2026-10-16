#!/bin/bash
# S1 outer run guard v3 (scratch validation helper — NOT production code).
#
# Usage:
#   s1-guarded-run.sh <evidence-dir> <test-cap-seconds> <task-path> -- <gradle command...>
#   s1-guarded-run.sh <evidence-dir> <selftest-cap-seconds> <task-path> --selftest <java> <scratch-classdir>
#
# v3 (per parent review of v2):
#   1. RUN-UNIQUE identity: the guard generates a UUID run id and injects
#      -Ptraycer.validation.runId=<uuid> plus the s1-runid-init.gradle init
#      script into the wrapped Gradle command; the init script tags every Test
#      task's worker JVM with -Dtraycer.validation.runId and
#      -Dtraycer.validation.task=<exact task path> on the worker COMMAND LINE.
#      A candidate worker must carry the EXACT run-id tag AND exact task tag.
#   2. All PowerShell output is CRLF-normalized (tr -d '\r') before
#      comparisons; creation identity is the FULL-PRECISION raw Win32
#      CreationDate string (no epoch rounding).
#   3. Candidates require Name LIKE 'java*' — the guard's own PowerShell
#      command line (which contains the filter literals) can never match.
#   4. Per candidate: revalidate -> jstack (bounded by GNU timeout when
#      available) -> REVALIDATE AGAIN -> terminate. Anything not VERIFIED at
#      either check is skipped.
#   5. After a breach the wrapper wait is BOUNDED (S1_GRACE_SECS, default 60;
#      selftest forces a short grace). If the wrapper is still alive after
#      grace, ONLY the owned wrapper (this script's own child, MSYS kill) is
#      stopped. The wait is never indefinite.
#   6. Worker termination uses taskkill (Win32 PID from Win32_Process) ONLY.
#      The previous `kill -9 $WPID` fallback is REMOVED (MSYS PID != Win32 PID).
#   7. --selftest exercises the REAL breach route: a tagged sleeper child (all
#      real worker markers, but a plain java process) plus a NON-matching
#      sibling (different run id); breach must terminate the tagged child,
#      leave the sibling running (verified by the caller), stop the owned
#      fake wrapper within grace, and exit 124 with S1_GUARD_BREACH=1.

set -u

EV="$1"
CAP="$2"
TASKPATH="$3"
shift 3
SELFTEST=0
if [ "${1:-}" = "--selftest" ]; then SELFTEST=1; shift; fi
if [ "${1:-}" = "--" ]; then shift; fi

LOG="$EV/guarded-run.log"
DUMP="$EV/worker-dump.txt"
INIT="$EV/s1-runid-init.gradle"
GRACE="${S1_GRACE_SECS:-60}"

RUNID=$(powershell -NoProfile -Command "[guid]::NewGuid().ToString()" | tr -d '\r\n')

# CRLF-normalized PowerShell capture.
psout() {
  powershell -NoProfile -Command "$1" 2>/dev/null | tr -d '\r'
}

# Candidates: java* process name + exact run-id tag + exact task tag + repo
# path + GradleWorkerMain marker, all on the process command line.
# NOTE: single pipeline line — PowerShell 5.1 cannot continue a pipeline with
# a leading '|' on the next line (v3 first run failed silently this way).
# CreationDate is emitted with FULL precision (ToString('o')) on BOTH sides —
# the v3.1 run proved a seconds-precision locale string fails revalidation
# against the process's sub-second creation time (false PID_REUSE, skip-kill).
cand_ps() {
  psout "Get-CimInstance Win32_Process | Where-Object { \$_.Name -like 'java*' -and \$_.CommandLine -like '*traycer.validation.runId=$RUNID*' -and \$_.CommandLine -like '*traycer.validation.task=$TASKPATH*' -and \$_.CommandLine -like '*GradleWorkerMain*' -and \$_.CommandLine -like '*tachiyomiATVIBE-inpainting-modes-redesign*' } | ForEach-Object { Write-Output (\"\$(\$_.ProcessId)|\$(\$_.CreationDate.ToString('o'))\") }"
}

# Revalidate one PID: alive + full-precision creation unchanged + identity
# tags still present. Verdicts: VERIFIED | GONE | IDENTITY_LOST | PID_REUSE.
revalidate_ps() {
  WPID="$1"
  WCREATED="$2"
  psout "
    \$p = Get-CimInstance Win32_Process -Filter \"ProcessId=$WPID\" -ErrorAction SilentlyContinue
    if (\$null -eq \$p) { Write-Output 'GONE'; exit }
    if (\$p.Name -notlike 'java*') { Write-Output 'IDENTITY_LOST'; exit }
    if (\$p.CommandLine -notlike '*traycer.validation.runId=$RUNID*') { Write-Output 'IDENTITY_LOST'; exit }
    if (\$p.CommandLine -notlike '*traycer.validation.task=$TASKPATH*') { Write-Output 'IDENTITY_LOST'; exit }
    if (\$p.CommandLine -notlike '*GradleWorkerMain*') { Write-Output 'IDENTITY_LOST'; exit }
    if (\$p.CommandLine -notlike '*tachiyomiATVIBE-inpainting-modes-redesign*') { Write-Output 'IDENTITY_LOST'; exit }
    if (\$p.CreationDate.ToString('o') -ne '$WCREATED') { Write-Output 'PID_REUSE'; exit }
    Write-Output 'VERIFIED'"
}

terminate_breach() {
  : > "$DUMP"
  echo "=== S1 guard breach $(date -u '+%Y-%m-%dT%H:%M:%SZ'): test execution exceeded ${CAP}s ===" >> "$DUMP"
  echo "run id: $RUNID; task: $TASKPATH" >> "$DUMP"
  echo "--- candidates (run-id + task + project filtered at breach time) ---" >> "$DUMP"
  cand_ps > "$DUMP.candidates"
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    WPID="${line%%|*}"
    WCREATED="${line#*|}"
    echo "candidate PID=$WPID created=$WCREATED" >> "$DUMP"
    V1=$(revalidate_ps "$WPID" "$WCREATED")
    echo "revalidate#1 PID=$WPID -> $V1" >> "$DUMP"
    [ "$V1" != "VERIFIED" ] && continue
    # Derive jstack from the worker's OWN java executable (same JDK bin) —
    # jstack is usually not on PATH; PATH lookup alone is not sufficient.
    JSTACK=""
    WORKERJAVA=$(psout "(Get-CimInstance Win32_Process -Filter \"ProcessId=$WPID\").ExecutablePath")
    if [ -n "$WORKERJAVA" ]; then
      CANDIDATE_JSTACK="$(dirname "$WORKERJAVA")/jstack.exe"
      [ -f "$CANDIDATE_JSTACK" ] && JSTACK="$CANDIDATE_JSTACK"
    fi
    if [ -n "$JSTACK" ] && command -v timeout >/dev/null 2>&1; then
      timeout -k 2 10 "$JSTACK" "$WPID" >> "$DUMP" 2>&1 || echo "jstack failed/timed out for $WPID" >> "$DUMP"
    else
      echo "thread dump unavailable for $WPID (jstack not found next to worker java: '$WORKERJAVA')" >> "$DUMP"
    fi
    V2=$(revalidate_ps "$WPID" "$WCREATED")
    echo "revalidate#2 PID=$WPID -> $V2" >> "$DUMP"
    [ "$V2" != "VERIFIED" ] && continue
    echo "GUARD: terminating VERIFIED worker PID=$WPID (taskkill, Win32 identity)" >> "$DUMP"
    taskkill //PID "$WPID" //T //F >> "$DUMP" 2>&1 || echo "taskkill failed for $WPID" >> "$DUMP"
  done < "$DUMP.candidates"

  GRACESTART=$(date +%s)
  while kill -0 "$GPID" 2>/dev/null; do
    NOW=$(date +%s)
    if [ $((NOW - GRACESTART)) -ge "$GRACE" ]; then
      echo "GUARD: wrapper still alive after ${GRACE}s grace; stopping OWNED wrapper pid=$GPID (MSYS kill of own child)" >> "$DUMP"
      kill -9 "$GPID" 2>/dev/null
      wait "$GPID" 2>/dev/null
      break
    fi
    sleep 1
  done
  wait "$GPID" 2>/dev/null
  if [ "$SELFTEST" = "1" ]; then
    if kill -0 "$SIBLING" 2>/dev/null; then
      echo "SELFTEST: sibling SURVIVED the breach (correct: different run id)"
      kill -9 "$SIBLING" 2>/dev/null
      wait "$SIBLING" 2>/dev/null
    else
      echo "SELFTEST: ERROR — sibling did not survive the breach (identity filter over-matched)"
    fi
  fi
  echo "S1_GUARD_BREACH=1"
  echo "GUARD: cap breached (FAILING exit 124 regardless of wrapped exit); diagnostics: $DUMP"
  echo "GUARD: last log lines:"
  tail -n 15 "$LOG" 2>/dev/null
  exit 124
}

if [ "$SELFTEST" = "1" ]; then
  # Args after --selftest: <java> <scratch-classdir>
  JAVA="$1"
  CLSDIR="$2"
  export S1_GRACE_SECS=5
  GRACE=5
  "$JAVA" -Dtraycer.validation.runId="$RUNID" \
          -Dtraycer.validation.task="$TASKPATH" \
          -Dtraycer.validation.project="tachiyomiATVIBE-inpainting-modes-redesign" \
          -Dtraycer.validation.mockworker=GradleWorkerMain \
          -cp "$CLSDIR" S1SelftestSleep >/dev/null 2>&1 &
  TAGGED=$!
  "$JAVA" -Dtraycer.validation.runId="other-run-should-not-match" \
          -Dtraycer.validation.task="$TASKPATH" \
          -Dtraycer.validation.project="tachiyomiATVIBE-inpainting-modes-redesign" \
          -Dtraycer.validation.mockworker=GradleWorkerMain \
          -cp "$CLSDIR" S1SelftestSleep >/dev/null 2>&1 &
  SIBLING=$!
  echo "SELFTEST: tagged child MSYS-pid=$TAGGED runId=$RUNID; sibling MSYS-pid=$SIBLING (different run id)"
  sleep 1
  : > "$LOG"
  echo "> Task $TASKPATH" > "$LOG"
  sleep 300 &
  GPID=$!
  echo "SELFTEST: fake wrapper MSYS-pid=$GPID (sleep 300); cap=${CAP}s grace=${GRACE}s"
else
  if [ $# -eq 0 ]; then
    echo "GUARD: no command given" >&2
    exit 64
  fi
  : > "$LOG"
  echo "GUARD: run id=$RUNID; task=$TASKPATH"
  # Run id reaches the init script via THREE channels from ONE GUID (env, -D,
  # -P): the -P channel alone proved invisible to startParameter inside the
  # daemon (final std attempt 4 failed in 1s; std-tests/guarded-run.attempt4-initfail.log).
  export TRAYCER_VALIDATION_RUNID="$RUNID"
  "$@" "-Dtraycer.validation.runId=$RUNID" "-Ptraycer.validation.runId=$RUNID" -I "$INIT" > "$LOG" 2>&1 &
  GPID=$!
  echo "GUARD: command pid=$GPID started $(date -u '+%Y-%m-%dT%H:%M:%SZ'); test cap=${CAP}s (armed on test task start)"
fi

RUNSTART=$(date +%s)
TASKSTART=""

while kill -0 "$GPID" 2>/dev/null; do
  NOW=$(date +%s)
  if [ -z "$TASKSTART" ] && grep -qF "> Task $TASKPATH" "$LOG" 2>/dev/null; then
    TASKSTART=$NOW
    echo "GUARD: test task started $(date -u '+%Y-%m-%dT%H:%M:%SZ'); cap ${CAP}s armed"
  fi
  if [ -n "$TASKSTART" ] && [ $((NOW - TASKSTART)) -ge "$CAP" ]; then
    terminate_breach
  fi
  sleep 2
done

wait "$GPID"
CODE=$?
echo "S1_GUARD_BREACH=0"
echo "GUARD: command completed within cap; exit CODE=$CODE"
exit "$CODE"
