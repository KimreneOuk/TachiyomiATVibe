"""Windows validation watchdog; diagnose and stop invocation-owned JVMs only."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid


def powershell(script, timeout=15):
    result = subprocess.run(
        ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
        capture_output=True, text=True, timeout=timeout,
    )
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout.strip()


def processes():
    raw = powershell(
        "@(Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'java*' } | "
        "ForEach-Object { [pscustomobject]@{ pid=$_.ProcessId; "
        "created=$_.CreationDate.ToUniversalTime().ToString('o'); "
        "command=$_.CommandLine; executable=$_.ExecutablePath } }) | ConvertTo-Json -Compress"
    )
    value = json.loads(raw) if raw else []
    return value if isinstance(value, list) else [value]


def matches(process, tags, daemon_marker=None):
    # Exact tokens prevent run-id prefix matches.
    tokens = (process.get("command") or "").replace('"', '').split()
    if all(tag in tokens for tag in tags):
        return True
    return daemon_marker is not None and daemon_marker in tokens and "org.gradle.launcher.daemon.bootstrap.GradleDaemon" in tokens


def terminate_owned(tags, evidence, daemon_marker=None):
    journal = []
    # Stop workers and daemon before their client.
    candidates = sorted(processes(), key=lambda p: 0 if "GradleWorkerMain" in (p.get("command") or "") else 1 if "GradleDaemon" in (p.get("command") or "") else 2)
    for candidate in candidates:
        if not matches(candidate, tags, daemon_marker):
            continue
        pid = candidate["pid"]
        entry = {"candidate": candidate}
        current = next((p for p in processes() if p["pid"] == pid), None)
        if not current or current["created"] != candidate["created"] or not matches(current, tags, daemon_marker):
            entry["disposition"] = "identity changed before diagnostic; skipped"
            journal.append(entry)
            continue
        jstack = Path(current["executable"]).with_name("jstack.exe")
        try:
            with (evidence / f"jstack-{pid}.txt").open("w", encoding="utf-8") as output:
                result = subprocess.run([str(jstack), str(pid)], stdout=output, stderr=subprocess.STDOUT, timeout=10)
                entry["jstack_exit"] = result.returncode
        except (OSError, subprocess.TimeoutExpired) as error:
            entry["jstack_error"] = str(error)
        current = next((p for p in processes() if p["pid"] == pid), None)
        if current and current["created"] == candidate["created"] and matches(current, tags, daemon_marker):
            # No /T: child processes are separately eligible only after verification.
            result = subprocess.run(["taskkill.exe", "/PID", str(pid), "/F"], capture_output=True, text=True, timeout=10)
            entry.update(disposition="verified termination", kill_exit=result.returncode, output=result.stdout + result.stderr)
        else:
            entry["disposition"] = "identity changed after diagnostic; skipped"
        journal.append(entry)
    (evidence / "termination.json").write_text(json.dumps(journal, indent=2), encoding="utf-8")
    return journal


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--task", required=True)
    parser.add_argument("--setup-seconds", type=int, default=1200)
    parser.add_argument("--test-seconds", type=int, default=300)
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    evidence, repo = args.evidence.resolve(), args.repo.resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    if any(evidence.iterdir()):
        parser.error("evidence directory must be new and empty")
    # Claim before writing attempt evidence.
    (evidence / ".launch-claim").mkdir()
    if args.setup_seconds <= 0 or args.test_seconds <= 0:
        parser.error("deadlines must be positive")
    run_id = str(uuid.uuid4())
    project = hashlib.sha256(str(repo).casefold().encode()).hexdigest()[:24]
    tags = [f"-Dtraycer.validation.runId={run_id}", f"-Dtraycer.validation.task={args.task}", f"-Dtraycer.validation.project={project}"]
    task_digest = hashlib.sha256(args.task.encode()).hexdigest()[:16]
    # Gradle removes mutable -D properties from daemon startup arguments.
    daemon_marker = f"-XX:ErrorFile={evidence.as_posix()}/jvm-{run_id}-{task_digest}-{project}-%p.log"
    env = os.environ.copy()
    env["JAVA_OPTS"] = " ".join(filter(None, [env.get("JAVA_OPTS", ""), *tags]))
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    init = evidence / "run-init.gradle"
    init.write_text("allprojects { tasks.withType(Test).configureEach { t -> jvmArgs(" + ", ".join(repr(tag) for tag in tags) + "); doFirst { println('TRAYCER_TEST_START=' + t.path) } } }\n", encoding="utf-8")
    task_name = args.task.rsplit(":", 1)[-1]
    xml_source = (repo / "app/build/test-results" / task_name).resolve()
    expected_parent = (repo / "app/build/test-results").resolve()
    if xml_source.parent != expected_parent or not task_name or task_name in (".", ".."):
        parser.error("invalid test results path")
    if not args.selftest and xml_source.exists():
        shutil.move(str(xml_source), str(evidence / "prior-results"))
    if args.selftest:
        java = Path(os.environ["JAVA_HOME"]) / "bin/java.exe"
        source = evidence / "GuardSleeper.java"
        source.write_text("class GuardSleeper { public static void main(String[] args) throws Exception { Thread.sleep(60000); } }\n", encoding="utf-8")
        command = [str(java), *tags, str(source)]
    elif not command:
        parser.error("missing command after --")
    else:
        # Keep Kotlin compilation inside the tagged single-use daemon.
        command += ["--no-daemon", "-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8 " + " ".join([*tags, daemon_marker]), "-Pkotlin.compiler.execution.strategy=in-process", "-I", str(init)]
    (evidence / "invocation.json").write_text(json.dumps(dict(run_id=run_id, repo=str(repo), task=args.task, command=command, setup_seconds=args.setup_seconds, test_seconds=args.test_seconds, tags=tags, daemon_marker=daemon_marker), indent=2), encoding="utf-8")
    log = evidence / "command.log"
    sibling = None
    if args.selftest:
        sibling_env = env.copy()
        sibling_command = [arg.replace(run_id, str(uuid.uuid4())) if run_id in arg else arg for arg in command]
        sibling = subprocess.Popen(sibling_command, cwd=repo, env=sibling_env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    started = time.monotonic()
    test_started, breach, monitor_error = None, None, None
    with log.open("w", encoding="utf-8") as output:
        child = subprocess.Popen(command, cwd=repo, env=env, stdout=output, stderr=subprocess.STDOUT)
        print(f"guard run={run_id} pid={child.pid} task={args.task}", flush=True)
        try:
            while child.poll() is None:
                now = time.monotonic()
                if test_started is None and f"TRAYCER_TEST_START={args.task}" in log.read_text(encoding="utf-8", errors="replace"):
                    test_started = now
                    print("test execution deadline armed", flush=True)
                if test_started is None and now - started >= args.setup_seconds:
                    breach = "setup/compilation deadline"
                if test_started is not None and now - test_started >= args.test_seconds:
                    breach = "test execution deadline"
                if breach:
                    break
                time.sleep(1)
        except BaseException as error:
            monitor_error = repr(error)
            breach = "monitor failure"
        if breach:
            print(f"guard breach: {breach}; preserving diagnostics", flush=True)
            try:
                termination = terminate_owned(tags, evidence, daemon_marker)
            except Exception as error:
                termination = []
                monitor_error = repr(error)
            try:
                child.wait(timeout=15)
            except subprocess.TimeoutExpired:
                # Direct Popen child is invocation-owned; do not kill its unverified tree.
                try:
                    child.kill()
                    child.wait(timeout=10)
                except (OSError, subprocess.TimeoutExpired) as error:
                    # Preserve result.json even if the owned wrapper cannot exit.
                    monitor_error = "; ".join(filter(None, [monitor_error, f"owned wrapper did not exit: {error!r}"]))
        else:
            termination = []
    actual_exit = child.returncode
    result = dict(actual_exit=actual_exit, guard_exit=124 if breach else actual_exit, breach=breach, monitor_error=monitor_error, elapsed_seconds=round(time.monotonic() - started, 2))
    if args.selftest:
        result["sibling_survived"] = sibling.poll() is None
        result["verified_target_terminated"] = any(item.get("disposition") == "verified termination" and item.get("kill_exit") == 0 for item in termination)
        sibling.terminate()
        sibling.wait(timeout=10)
    elif xml_source.exists():
        shutil.copytree(xml_source, evidence / "xml")
    (evidence / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result), flush=True)
    if args.selftest:
        return 0 if breach and result["sibling_survived"] and result["verified_target_terminated"] and not monitor_error else 1
    return result["guard_exit"]


if __name__ == "__main__":
    sys.exit(main())
