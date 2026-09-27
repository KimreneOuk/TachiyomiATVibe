## Summary

Describe the change and its user-visible impact.

## Related issue

Link an issue or write “None”.

## Testing

List the exact commands and test suites you ran. If tests were not run, say why. Some tests are tagged `quarantined-flaky`; they are excluded from the usual run unless enabled with `-PincludeQuarantinedTests`. State whether you included them.

## Screenshots (if UI)

Add before/after screenshots for user interface changes, or write “Not applicable”.

## Translation-concurrency impact

State whether this change touches translation leases, generation fencing, or result publication. Identify affected files or explain why this section is not applicable.

Reviewer checklist:

- [ ] Lease acquisition, ownership, renewal, and release behavior is accounted for where changed.
- [ ] Generation fencing prevents stale work from publishing where changed.
- [ ] Result publication and lifecycle checks are covered by tests or explained above.
- [ ] No translation-concurrency paths changed, or the affected paths are identified above.

## Checklist

- [ ] The summary and related issue are filled in.
- [ ] I reviewed the diff and tested the change, or explained what I could not test.
- [ ] User-facing text and documentation are updated where needed.
- [ ] I removed credentials and other secrets from the diff and attachments.
