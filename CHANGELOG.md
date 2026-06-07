# Changelog

All notable changes to `dev.flametrench:tenancy` are recorded here.
Spec-level changes live in [`spec/CHANGELOG.md`](https://github.com/flametrench/spec/blob/main/CHANGELOG.md).

## [v0.4.0] — 2026-06-07

### Added
- `InMemoryTenancyStore.listOrgs(cursor, limit, query, status)` — cursor-paginated cross-org enumeration per [ADR 0025](https://github.com/flametrench/spec/blob/main/decisions/0025-list-orgs.md). Returns `Page<Organization>` ordered by `id` ASC; supports optional `status` filter (`active`/`suspended`/`revoked`) and case-insensitive `query` substring over `name` or `slug`.
- `PostgresTenancyStore.listOrgs(cursor, limit, query, status)` — Postgres-backed equivalent using `id > cursor` keyset pagination and `ILIKE` for the `query` filter.
- Conformance fixture `tenancy/list-orgs.json` vendored (spec@da8ae1a, 8 tests). This is the 5th and final SDK implementation; the conformance fixture flips to `runnable_today: true` upon merge.

## [v0.3.0] — 2026-06-06

### Changed
- Cohort version bump to `0.3.0` for consistency with `ids`, `identity`, and `authz` v0.3 family (no tenancy-specific behavior changes in this cohort cut).
- CI: corrected `ids-java` checkout ref from stale `v0.1.0` tag → `v0.3.0`, matching the `ids` dependency declared in `pom.xml`. Fixes the "sibling resolves from Maven Central" principle-7 violation.

## [v0.2.0] — 2026-04-30

### Released
- v0.2 stable cutoff. No functional changes from `v0.2.0-rc.5` — same source, version bumped to drop the `-rc` suffix at the spec v0.2.0 freeze. The `ids` dependency was bumped from `0.1.0` to `0.2.0` to track the family. Includes the +3 createInvitation savepoint regression tests added at the cut. Maven Central publication is gated on Sonatype Central Portal credential regeneration; until that unblocks, the `0.2.0` jar is built and validated locally (`mvn -P release verify -Dgpg.skip=true`).

## [v0.2.0-rc.5] — 2026-04-27

### Fixed
- `PostgresTenancyStore.acceptInvitation` (when materializing pre-tuples) and `listTuplesForObject` now accept wire-format `object_id` values with app-defined prefixes (e.g. `proj_<32hex>`, `file_<32hex>`) in addition to bare 32-hex and canonical hyphenated UUIDs. Previously, an invitation carrying pre-tuples with wire-format prefixed IDs threw `IllegalArgumentException` at acceptance time when `UUID.fromString` rejected the value. Closes [`spec#8`](https://github.com/flametrench/spec/issues/8).

## [v0.2.0-rc.4] — 2026-04-27

### Added
- `dev.flametrench.tenancy.PostgresTenancyStore` — a Postgres-backed tenancy store. Mirrors `InMemoryTenancyStore` byte-for-byte at the SDK boundary; the difference is durability and concurrency.
  - Schema: `spec/reference/postgres.sql` (the `org`, `mem`, `inv`, `tup` tables, plus the v0.2 `org.name`/`org.slug` ADR 0011 columns).
  - Connection: accepts a `javax.sql.DataSource`. `org.postgresql:postgresql:42.7.4` is declared `<optional>true</optional>` — adopters using only the in-memory store don't transitively pull in the JDBC driver.
  - `jackson-databind` was promoted from `test` to compile scope to support `inv.pre_tuples` JSONB serialization at runtime.
  - Multi-statement ops (`createOrg` + owner membership + tuple, `changeRole` revoke-and-re-add, `acceptInvitation` with pre-tuples, `transferOwnership`) run inside a transaction.
  - Coverage: 25 integration tests, gated on `TENANCY_POSTGRES_URL`.

## [v0.2.0-rc.3] — 2026-04-26

ADR 0011 org metadata (`name` + `slug`). See [`spec/CHANGELOG.md`](https://github.com/flametrench/spec/blob/main/CHANGELOG.md).

## [v0.2.0-rc.1] — 2026-04-25

Initial v0.2 release-candidate.

For pre-rc history, see git tags.
