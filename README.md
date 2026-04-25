# flametrench-tenancy (Java)

Java SDK for the [Flametrench v0.1](https://github.com/flametrench/spec) tenancy specification: organizations, memberships (with the `mem_`/`tup_` duality), and atomic invitation acceptance.

The same behavioral guarantees that gate `@flametrench/tenancy` (Node), `flametrench/tenancy` (PHP), and `flametrench-tenancy` (Python) hold here:

- **Revoke-and-re-add** on role changes, with a `replaces` chain for audit history.
- **Sole-owner protection** on every path that could leave an org without an active owner.
- **Atomic invitation acceptance** — user creation, membership insertion, owner-role tuple, AND pre-tuple expansion all in one transition.
- **Role hierarchy** on `adminRemove` — admins cannot remove owners (only `transferOwnership` can demote owners).
- **mem_/tup_ duality** — every active membership is shadowed by a corresponding `(usr, role, org)` tuple.

```java
import dev.flametrench.ids.Id;
import dev.flametrench.tenancy.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

var store = new InMemoryTenancyStore();
String alice = Id.generate("usr");
CreateOrgResult result = store.createOrg(alice);
// result.org().id(), result.ownerMembership().role() == Role.OWNER

String projectId = Id.generate("org").substring(4);
Invitation inv = store.createInvitation(
    result.org().id(),
    "newbie@example.com",
    Role.MEMBER,
    alice,
    Instant.now().plus(7, ChronoUnit.DAYS),
    List.of(new PreTuple("editor", "proj", projectId))
);

AcceptInvitationResult out = store.acceptInvitation(inv.id());
// out.membership().role() == Role.MEMBER
// out.materializedTuples() includes the pre-declared editor grant
```

## Installation

Maven:

```xml
<dependency>
    <groupId>dev.flametrench</groupId>
    <artifactId>tenancy</artifactId>
    <version>0.1.0</version>
</dependency>
```

Requires Java 17+. Depends on `dev.flametrench:ids`.

## License

Apache-2.0. See [LICENSE](./LICENSE) and [NOTICE](./NOTICE).

Copyright 2026 NDC Digital, LLC.
