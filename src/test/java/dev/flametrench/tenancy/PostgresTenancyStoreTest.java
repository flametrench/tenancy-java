// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

import dev.flametrench.ids.Id;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "TENANCY_POSTGRES_URL", matches = ".+")
class PostgresTenancyStoreTest {

    private static DataSource dataSource;
    private static String schemaSql;

    private PostgresTenancyStore store;
    private String alice;
    private String bob;
    private String carol;

    @BeforeAll
    static void setupClass() throws IOException {
        String url = System.getenv("TENANCY_POSTGRES_URL");
        URI uri = URI.create(url.replaceFirst("^postgresql:", "http:"));
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[] { uri.getHost() });
        ds.setPortNumbers(new int[] { uri.getPort() == -1 ? 5432 : uri.getPort() });
        String path = uri.getPath();
        ds.setDatabaseName(path != null && path.length() > 1 ? path.substring(1) : "postgres");
        if (uri.getUserInfo() != null) {
            String[] parts = uri.getUserInfo().split(":", 2);
            ds.setUser(parts[0]);
            if (parts.length > 1) ds.setPassword(parts[1]);
        }
        dataSource = ds;
        schemaSql = Files.readString(Path.of("src/test/resources/postgres-schema.sql"));
    }

    @BeforeEach
    void resetSchema() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;");
            st.execute(schemaSql);
        }
        store = new PostgresTenancyStore(dataSource);
        alice = registerUser();
        bob = registerUser();
        carol = registerUser();
    }

    private String registerUser() throws Exception {
        String wire = Id.generate("usr");
        UUID uuid = UUID.fromString(Id.decode(wire).uuid());
        try (Connection conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO usr (id, status) VALUES (?, 'active')")) {
            ps.setObject(1, uuid);
            ps.executeUpdate();
        }
        return wire;
    }

    @Test
    void createOrg_withOwnerMembershipAndTuple() {
        CreateOrgResult result = store.createOrg(alice);
        assertEquals(Status.ACTIVE, result.org().status());
        assertEquals(Role.OWNER, result.ownerMembership().role());
        assertEquals(alice, result.ownerMembership().usrId());
        List<Tuple> tuples = store.listTuplesForSubject("usr", alice);
        assertEquals(1, tuples.size());
        assertEquals("owner", tuples.get(0).relation());
    }

    @Test
    void createOrg_persistsNameAndSlug() {
        CreateOrgResult result = store.createOrg(alice, "Acme", "acme");
        Organization fetched = store.getOrg(result.org().id());
        assertEquals("Acme", fetched.name());
        assertEquals("acme", fetched.slug());
    }

    @Test
    void createOrg_duplicateSlug_raises() {
        store.createOrg(alice, null, "shared");
        assertThrows(OrgSlugConflictError.class, () ->
                store.createOrg(bob, null, "shared"));
    }

    @Test
    void createOrg_malformedSlug_raises() {
        assertThrows(PreconditionError.class, () ->
                store.createOrg(alice, null, "AcmeInc"));
    }

    @Test
    void updateOrg_partialUpdatesNameOnly() {
        CreateOrgResult result = store.createOrg(alice, "Old", "old-slug");
        Organization updated = store.updateOrg(result.org().id(), "New", PostgresTenancyStore.UNSET);
        assertEquals("New", updated.name());
        assertEquals("old-slug", updated.slug());
    }

    @Test
    void updateOrg_explicitNullClearsSlug() {
        CreateOrgResult result = store.createOrg(alice, null, "to-clear");
        Organization updated = store.updateOrg(result.org().id(), PostgresTenancyStore.UNSET, null);
        assertNull(updated.slug());
    }

    @Test
    void updateOrg_revoked_raises() {
        CreateOrgResult result = store.createOrg(alice, "RIP", null);
        store.revokeOrg(result.org().id());
        assertThrows(AlreadyTerminalError.class, () ->
                store.updateOrg(result.org().id(), "Whatever", PostgresTenancyStore.UNSET));
    }

    @Test
    void addMember_createsTuple() {
        CreateOrgResult result = store.createOrg(alice);
        Membership mem = store.addMember(result.org().id(), bob, Role.MEMBER, alice);
        assertEquals(Role.MEMBER, mem.role());
        assertEquals(alice, mem.invitedBy());
        assertEquals(1, store.listTuplesForSubject("usr", bob).size());
    }

    @Test
    void duplicateMember_raises() {
        CreateOrgResult result = store.createOrg(alice);
        store.addMember(result.org().id(), bob, Role.MEMBER);
        assertThrows(DuplicateMembershipError.class, () ->
                store.addMember(result.org().id(), bob, Role.ADMIN));
    }

    @Test
    void changeRole_atomic() {
        CreateOrgResult result = store.createOrg(alice);
        Membership bobMem = store.addMember(result.org().id(), bob, Role.MEMBER);
        Membership newMem = store.changeRole(bobMem.id(), Role.ADMIN);
        assertEquals(bobMem.id(), newMem.replaces());
        assertEquals(Role.ADMIN, newMem.role());
        assertEquals(Status.REVOKED, store.getMembership(bobMem.id()).status());
        List<Tuple> tuples = store.listTuplesForSubject("usr", bob);
        assertEquals(1, tuples.size());
        assertEquals("admin", tuples.get(0).relation());
    }

    @Test
    void changeRole_soleOwner_blocked() {
        CreateOrgResult result = store.createOrg(alice);
        assertThrows(SoleOwnerError.class, () ->
                store.changeRole(result.ownerMembership().id(), Role.MEMBER));
    }

    @Test
    void suspendReinstate_membership() {
        CreateOrgResult result = store.createOrg(alice);
        Membership bobMem = store.addMember(result.org().id(), bob, Role.MEMBER);
        store.suspendMembership(bobMem.id());
        assertTrue(store.listTuplesForSubject("usr", bob).isEmpty());
        store.reinstateMembership(bobMem.id());
        assertEquals(1, store.listTuplesForSubject("usr", bob).size());
    }

    @Test
    void selfLeave_nonOwner_noTransfer() {
        CreateOrgResult result = store.createOrg(alice);
        Membership bobMem = store.addMember(result.org().id(), bob, Role.MEMBER);
        Membership left = store.selfLeave(bobMem.id());
        assertEquals(Status.REVOKED, left.status());
        assertNull(left.removedBy());
    }

    @Test
    void selfLeave_soleOwner_withTransfer() {
        CreateOrgResult result = store.createOrg(alice);
        store.addMember(result.org().id(), bob, Role.MEMBER);
        Membership left = store.selfLeave(result.ownerMembership().id(), bob);
        assertEquals(Status.REVOKED, left.status());
        assertTrue(store.listTuplesForSubject("usr", alice).isEmpty());
        List<Tuple> bobTuples = store.listTuplesForSubject("usr", bob);
        assertEquals(1, bobTuples.size());
        assertEquals("owner", bobTuples.get(0).relation());
    }

    @Test
    void selfLeave_soleOwner_noTransfer_blocked() {
        CreateOrgResult result = store.createOrg(alice);
        assertThrows(SoleOwnerError.class, () ->
                store.selfLeave(result.ownerMembership().id()));
    }

    @Test
    void adminRemove_recordsRemover() {
        CreateOrgResult result = store.createOrg(alice);
        Membership bobMem = store.addMember(result.org().id(), bob, Role.MEMBER);
        Membership removed = store.adminRemove(bobMem.id(), alice);
        assertEquals(alice, removed.removedBy());
    }

    @Test
    void adminRemove_cannotRemoveOwner() {
        CreateOrgResult result = store.createOrg(alice);
        store.addMember(result.org().id(), bob, Role.ADMIN);
        assertThrows(RoleHierarchyError.class, () ->
                store.adminRemove(result.ownerMembership().id(), bob));
    }

    @Test
    void transferOwnership_atomic() {
        CreateOrgResult result = store.createOrg(alice);
        Membership bobMem = store.addMember(result.org().id(), bob, Role.ADMIN);
        TransferOwnershipResult out = store.transferOwnership(
                result.org().id(), result.ownerMembership().id(), bobMem.id()
        );
        assertEquals(Role.MEMBER, out.fromMembership().role());
        assertEquals(Role.OWNER, out.toMembership().role());
        List<String> aliceRels = store.listTuplesForSubject("usr", alice).stream()
                .map(Tuple::relation).toList();
        assertEquals(List.of("member"), aliceRels);
        List<String> bobRels = store.listTuplesForSubject("usr", bob).stream()
                .map(Tuple::relation).toList();
        assertEquals(List.of("owner"), bobRels);
    }

    @Test
    void acceptInvitation_materializesPreTuples() {
        CreateOrgResult result = store.createOrg(alice);
        String projectId = "0190f2a8-1b3c-7abc-8123-456789abcdef";
        Invitation inv = store.createInvitation(
                result.org().id(),
                "carol@example.com",
                Role.GUEST,
                alice,
                Instant.now().plus(1, ChronoUnit.HOURS),
                List.of(new PreTuple("viewer", "proj", projectId))
        );
        AcceptInvitationResult out = store.acceptInvitation(inv.id(), carol, "carol@example.com");
        assertEquals(1, out.materializedTuples().size());
        assertEquals(InvitationStatus.ACCEPTED, out.invitation().status());
        assertEquals(carol, out.invitation().terminalBy());
        List<Tuple> carolTuples = store.listTuplesForSubject("usr", carol);
        assertEquals(2, carolTuples.size());
        Tuple viewer = carolTuples.stream()
                .filter(t -> "viewer".equals(t.relation()))
                .findFirst().orElseThrow();
        assertEquals(projectId, viewer.objectId());
    }

    @Test
    void acceptInvitation_nonPending_rejected() {
        CreateOrgResult result = store.createOrg(alice);
        Invitation inv = store.createInvitation(
                result.org().id(), "x@y", Role.MEMBER, alice,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );
        store.acceptInvitation(inv.id(), bob, "x@y");
        assertThrows(InvitationNotPendingError.class, () ->
                store.acceptInvitation(inv.id(), carol, "x@y"));
    }

    @Test
    void declineInvitation_terminal() {
        CreateOrgResult result = store.createOrg(alice);
        Invitation inv = store.createInvitation(
                result.org().id(), "x@y", Role.MEMBER, alice,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );
        Invitation declined = store.declineInvitation(inv.id(), bob);
        assertEquals(InvitationStatus.DECLINED, declined.status());
        assertEquals(bob, declined.terminalBy());
    }

    @Test
    void revokeInvitation_terminal() {
        CreateOrgResult result = store.createOrg(alice);
        Invitation inv = store.createInvitation(
                result.org().id(), "x@y", Role.MEMBER, alice,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );
        Invitation revoked = store.revokeInvitation(inv.id(), alice);
        assertEquals(InvitationStatus.REVOKED, revoked.status());
        assertEquals(alice, revoked.terminalBy());
    }

    @Test
    void revokeOrg_cascades() {
        CreateOrgResult result = store.createOrg(alice);
        store.addMember(result.org().id(), bob, Role.MEMBER);
        store.revokeOrg(result.org().id());
        assertEquals(Status.REVOKED, store.getOrg(result.org().id()).status());
        assertTrue(store.listTuplesForSubject("usr", alice).isEmpty());
        assertTrue(store.listTuplesForSubject("usr", bob).isEmpty());
    }

    @Test
    void listMembers_paginates() throws Exception {
        CreateOrgResult result = store.createOrg(alice);
        String extra1 = registerUser();
        String extra2 = registerUser();
        for (String u : List.of(bob, carol, extra1, extra2)) {
            store.addMember(result.org().id(), u, Role.MEMBER);
        }
        Page<Membership> page1 = store.listMembers(result.org().id(), null, 2, null);
        assertEquals(2, page1.data().size());
        assertNotNull(page1.nextCursor());
        Page<Membership> page2 = store.listMembers(result.org().id(), page1.nextCursor(), 10, null);
        Set<String> all = new HashSet<>();
        page1.data().forEach(m -> all.add(m.id()));
        page2.data().forEach(m -> all.add(m.id()));
        assertEquals(5, all.size()); // alice + 4 added
    }

    @Test
    void notFoundPaths() {
        assertThrows(NotFoundError.class, () -> store.getOrg(Id.generate("org")));
        assertThrows(NotFoundError.class, () -> store.getMembership(Id.generate("mem")));
        assertThrows(NotFoundError.class, () -> store.getInvitation(Id.generate("inv")));
    }

    // ─── Outer-transaction nesting (ADR 0013) ───

    @Test
    void createOrg_cooperatesWithOuterTransaction() throws java.sql.SQLException {
        try (java.sql.Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            PostgresTenancyStore nested = new PostgresTenancyStore(conn);
            CreateOrgResult r = nested.createOrg(alice, "Outer", "outer");
            conn.commit();
            assertEquals("Outer", store.getOrg(r.org().id()).name());
        }
    }

    @Test
    void outerRollback_undoesInnerCreateOrg() throws java.sql.SQLException {
        String orgId;
        try (java.sql.Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            PostgresTenancyStore nested = new PostgresTenancyStore(conn);
            CreateOrgResult r = nested.createOrg(alice, null, "will-rollback");
            orgId = r.org().id();
            conn.rollback();
        }
        assertThrows(NotFoundError.class, () -> store.getOrg(orgId));
    }

    @Test
    void multipleCallsInOuterCommitOrRollbackTogether() throws java.sql.SQLException {
        String aId, bId;
        try (java.sql.Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            PostgresTenancyStore nested = new PostgresTenancyStore(conn);
            CreateOrgResult a = nested.createOrg(alice, null, "group-a");
            CreateOrgResult b = nested.createOrg(bob, null, "group-b");
            aId = a.org().id();
            bId = b.org().id();
            conn.rollback();
        }
        assertThrows(NotFoundError.class, () -> store.getOrg(aId));
        assertThrows(NotFoundError.class, () -> store.getOrg(bId));
    }
}
