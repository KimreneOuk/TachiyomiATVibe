# Planning & Investigation Guide

## Clarifying Questions (Ask First)

Ask strategic questions to understand what's actually needed—don't assume.

**Problem & Impact:** What's the real problem? Why now? Who benefits? What if it's not done?

**Scope & Boundaries:** What's in/out of scope? Related subsystems? Backward compatibility needed?

**Constraints & Trade-offs:** Performance/memory limits? Timeline? Device-specific concerns? What's non-negotiable?

**Success Criteria:** How do we verify it works? What's failure? What needs testing?

**Risk & Dependencies:** What existing code might break? Test coverage gaps? What could go wrong?

**Architecture & Design:** Fit existing patterns? Multiple valid implementations? New approach needed?

---

## Investigation Checklist

1. **Read Structure** – Understand the module, existing docs, architecture, dependencies
2. **Analyze Connections** – Trace dependencies (what depends on what?)
3. **Understand Data Flow** – How data enters, transforms, and exits
4. **Read Implementations** – Never guess; locate and read actual code
5. **Identify Patterns** – What conventions does this codebase use?

---

## Plan Formulation

**Define the problem** – What's broken or missing? What are constraints?

**Identify scope** – Which files change? Which stay untouched?

**Design approach** – What's the minimal change? Does it align with architecture?

**Plan testing** – What behavior must be verified? What edge cases exist?

---

## Key Principles

- **Ask before investigating** – Clarifying questions prevent rework
- **Never assume** – Read actual code and trace behavior
- **Understand deeply** – Don't proceed until dependencies and data flow are clear
- **Minimize scope** – Change only what's necessary
- **Plan ahead** – Identify risks before coding

---