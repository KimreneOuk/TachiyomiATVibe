# Implementation Guide

## Pre-Implementation Verification

**Validate feasibility:** Is the approach sound? Dependencies available? Aligns with architecture?

**Review existing tests:** Find test gaps. Identify what needs new coverage.

---

## Task Classification & Breakdown

**Small task** (implement directly):
- Single focused change
- 1-2 files affected
- Clear path
- Low risk

**Large task** (use subagents + review):
- Multiple interdependent changes
- Many files/modules affected
- High complexity
- Needs parallel work

---

## Implementation Workflow

### Small Tasks

1. Implement directly
2. Test immediately
3. Review for consistency and quality
4. Document if needed

### Large Tasks

1. Break into independent subtasks
2. Use subagents for complex work (treat as junior devs)
3. Review all output: correctness, style, error handling, performance
4. Polish: fix mistakes, optimize, handle edge cases
5. Test end-to-end

---

## Quality Assurance

**Write tests:**
- Design smallest test that proves intended behavior
- Write test before or alongside implementation
- Test edge cases and error conditions

**Code review checklist:**
- [ ] Follows existing architectural patterns
- [ ] No unnecessary complexity
- [ ] Performance considered
- [ ] Memory impact acceptable (6 GB RAM constraint)
- [ ] Error handling present
- [ ] Documentation/KDoc updated
- [ ] No unrelated changes

**Testing validation:** Report what was tested, what wasn't, and why.

---

## Subagent Workflow

**Before:** Clear task description, context, constraints, quality expectations

**During:** Allow autonomy; expect mistakes; trust base structure

**After:** Review all changes → Verify logic → Polish → Test end-to-end

---

## Key Principles

- **Verify feasibility** – Don't implement plans that won't work
- **Break it down** – Decompose into clear, small tasks
- **Use subagents wisely** – Only for truly large/complex work
- **Review everything** – Subagent output needs careful review
- **Test thoroughly** – Automated tests verify behavior
- **Polish before shipping** – Final refinements matter

---
