# Local maven mirror

This directory is a plain maven repository that `settings.gradle.kts` checks before
Maven Central and JitPack. It mirrors small third-party artifacts whose JitPack
builds are unreliable, so CI and fresh contributor checkouts do not depend on
JitPack availability.

## Contents

| Coordinates | License | Upstream | Reason |
| --- | --- | --- | --- |
| `com.github.arkon.FlexibleAdapter:flexible-adapter:c8013533` | Apache-2.0 | https://github.com/arkon/FlexibleAdapter | JitPack rebuild of this commit intermittently fails with HTTP 500, breaking CI dependency resolution |

The mirrored `.aar`/`.pom` are byte-for-byte copies of the artifacts JitPack
serves for the pinned commit. Do not add anything here without recording its
license and upstream in this table.
