# Destination-host validation guard

`run-guard.sh` sets the prescribed Windows JDK/SDK environment and calls
`windows-run-guard.py`. The helper claims an empty immutable evidence directory
before writing logs. Pass the exact task and command after `--`; it adds a tagged
single-use Gradle daemon, a tagged test-worker init script and in-process Kotlin
compilation so compilation cannot escape into an untagged reusable compiler.
The prescribed `--max-workers=2` and `-Dkotlin.daemon.jvmargs=-Xmx3g` still belong
in the supplied Gradle command. No clean or duplicate standalone compile is added.

The setup/compilation deadline defaults to 1,200 seconds. Test execution has a
separate 300-second deadline, armed by an init-script `doFirst` marker for the
exact task. On breach, the helper captures bounded thread dumps and terminates
only Java processes carrying this run's UUID, task and checkout digest, after
revalidating unchanged creation identity. It never uses tree-wide `taskkill`.
The direct wrapper child has a bounded exit grace. Failed process verification
is preserved rather than broadening the kill criteria.

Each attempt retains invocation/exit JSON, console output, obtainable thread
dumps, termination decisions and fresh test XML. Prior shared XML is moved into
`prior-results` before launch, after checking its resolved checkout path. A
timeout reports guard exit 124 separately from the actual child exit.

Cheap self-test (no Gradle):

```bash
bash docs/architecture-cleanup/validation/run-guard.sh \
  --repo . --evidence C:/path/to/new-attempt \
  --task :app:testStandardDebugUnitTest --setup-seconds 8 --selftest
```

The first self-test exposed environment-only JVM tags being invisible to process
inspection. It failed honestly and remains retained locally. The corrected test
used command-line tags: target PID 25516 was identity-checked before and after a
successful thread dump, terminated with exit 0, and a different-run sibling
survived. Guard result was 124 and actual child result 1; the self-test wrapper
returned 0 because that was the expected breach behavior. This demonstrates the
controlled safeguard route, not a passing S5 test or production exit guarantee.
Independent helper review accepted these mechanics. No bitmap lifetime is bounded
by this host watchdog.

The first real app attempt (`s5-red-01`) exposed Gradle removing custom `-D`
tags from daemon startup arguments. Manual identity capture detected this before
tests. The coordinator verified the daemon's tagged-client parent and unchanged
creation identities before/after `jstack`, then stopped only daemon PID 28332.
Gradle exited 1 after 79 seconds; no regression test ran.

The revised daemon marker uses an exact `-XX:ErrorFile` argument containing the
run UUID, task digest and checkout digest, paired with the exact GradleDaemon
main-class token. Client/test workers retain the three original `-D` tags.
An isolated Gradle probe established real marker visibility and owned setup-time
termination before app validation resumed.

Probe `guard-daemon-probe-01` established actual daemon marker visibility and
setup-time termination: daemon PID 26736, thread dump exit 0, verified kill exit 0,
guard exit 124, actual child exit 1, no monitor error, 29.7 seconds. The 25-second
setup deadline expired during Groovy init loading before `GUARD_PROBE_ENTERED`;
sleep-task execution is unproven. The updated original-tag self-test also passed:
owned target stopped, different-run sibling survived, 11.38 seconds. Independent
review accepted this narrower setup-time proof in the
[corrected-source review](../artifacts/phase-3-implementation/tickets/owner-cutover/review/corrected-r2/index.md).
Red07 captured a real Test-worker command line (PID 24860) with all three exact
invocation tags, alongside daemon PID 19788 and client PID 29600. The test-start
marker armed the separate deadline. `live-owned-identities.json` and
`identity-observer-result.json` retain this evidence; worker visibility is now
established. The three tests failed JVM class verification, so this guard proof
does not establish pipeline behavior.
