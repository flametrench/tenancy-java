// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

import dev.flametrench.ids.Id;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InMemoryTenancyStore. Mirrors the most load-bearing
 * PHP/Node/Python test cases: sole-owner protection, the mem_/tup_
 * duality, role hierarchy on adminRemove, atomic invitation acceptance
 * with pre-tuple materialization, and ownership transfer.
 */
class InMemoryTenancyStoreTest {

    private InMemoryTenancyStore store;
    private String alice;
    private String bob;
    private String carol;

    @BeforeEach
    void setUp() {
        store = new InMemoryTenancyStore();
        alice = Id.generate("usr");
        bob = Id.generate("usr");
        carol = Id.generate("usr");
    }

    private Instant future() {
        return Instant.now().plus(1, ChronoUnit.HOURS);
    }

    @Test
    void createOrgEstablishesOwnerMembershipAndOwnerTuple() {
        CreateOrgResult result = store.createOrg(alice);
        assertEquals(Status.ACTIVE, result.org().status());
        assertEquals(Role.OWNER, result.ownerMembership().role());
        assertEquals(alice, result.ownerMembership().usrId());

        // mem_/tup_ duality
        List<Tuple> tuples = store.listTuplesForSubject("usr", alice);
        assertEquals(1, tuples.size());
        assertEquals("owner", tuples.get(0).relation());
    }

    @Test
    void revokeOrgCascadesMembershipsAndTuples() {
        CreateOrgResult result = store.createOrg(alice);
        store.addMember(result.org().id(), bob, Role.MEMBER);
        store.revokeOrg(result.org().id());

        assertTrue(store.listTuplesForSubject("usr", alice).isEmpty());
        assertTrue(store.listTuplesForSubject("usr", bob).isEmpty());
        assertEquals(Status.REVOKED, store.getOrg(result.org().id()).status());
    }

    @Test
    void addMemberRejectsDuplicateActiveMembership() {
        CreateOrgResult result = store.createOrg(alice);
        store.addMember(result.org().id(), bob, Role.MEMBER);
        assertThrows(DuplicateMembershipError.class,
                () -> store.addMember(result.org().id(), bob, Role.ADMIN));
    }

    @Test
    void changeRoleBlocksSoleOwnerDemotion() {
        CreateOrgResult result = store.createOrg(alice);
        assertThrows(SoleOwnerError.class,
                () -> store.changeRole(result.ownerMembership().id(), Role.MEMBER));
    }

    @Test
    void suspendBlocksSoleOwner() {
        CreateOrgResult result = store.createOrg(alice);
        assertThrows(SoleOwnerError.class,
                () -> store.suspendMembership(result.ownerMembership().id()));
    }

    @Test
    void selfLeaveRequiresTransferForSoleOwner() {
        CreateOrgResult result = store.createOrg(alice);
        assertThrows(SoleOwnerError.class,
                () -> store.selfLeave(result.ownerMembership().id()));
    }

    @Test
    void selfLeaveWithTransferPromotesTargetThenRevokes() {
        CreateOrgResult result = store.createOrg(alice);
        store.addMember(result.org().id(), bob, Role.MEMBER);
        Membership revoked = store.selfLeave(result.ownerMembership().id(), bob);
        assertEquals(Status.REVOKED, revoked.status());
        // Bob now has an active owner membership
        List<Tuple> bobTuples = store.listTuplesForSubject("usr", bob);
        assertTrue(bobTuples.stream().anyMatch(t -> t.relation().equals("owner")));
    }

    @Test
    void changeRoleRevokesOldInsertsNewWithReplacesChain() {
        CreateOrgResult result = store.createOrg(alice);
        Membership bobMem = store.addMember(result.org().id(), bob, Role.MEMBER);
        Membership newMem = store.changeRole(bobMem.id(), Role.ADMIN);

        Membership old = store.getMembership(bobMem.id());
        assertEquals(Status.REVOKED, old.status());
        assertEquals(Status.ACTIVE, newMem.status());
        assertEquals(bobMem.id(), newMem.replaces());
        assertEquals(Role.ADMIN, newMem.role());

        // Tuple swap: old member tuple gone, new admin tuple exists.
        List<Tuple> bobTuples = store.listTuplesForSubject("usr", bob);
        assertEquals(1, bobTuples.size());
        assertEquals("admin", bobTuples.get(0).relation());
    }

    @Test
    void adminCanRemoveMember() {
        CreateOrgResult result = store.createOrg(alice);
        store.addMember(result.org().id(), bob, Role.ADMIN);
        Membership target = store.addMember(result.org().id(), carol, Role.MEMBER);
        Membership revoked = store.adminRemove(target.id(), bob);
        assertEquals(Status.REVOKED, revoked.status());
        assertEquals(bob, revoked.removedBy());
    }

    @Test
    void adminCanRemovePeerAdmin() {
        // Per spec: "higher rank removes lower OR EQUAL rank" — admins
        // can remove peer admins. Owner stays the only undeposable role.
        CreateOrgResult result = store.createOrg(alice);
        store.addMember(result.org().id(), bob, Role.ADMIN);
        Membership peer = store.addMember(result.org().id(), carol, Role.ADMIN);
        Membership revoked = store.adminRemove(peer.id(), bob);
        assertEquals(Status.REVOKED, revoked.status());
    }

    @Test
    void adminCannotRemoveOwner() {
        CreateOrgResult result = store.createOrg(alice);
        store.addMember(result.org().id(), bob, Role.ADMIN);
        assertThrows(RoleHierarchyError.class,
                () -> store.adminRemove(result.ownerMembership().id(), bob));
    }

    @Test
    void nonAdminMemberCannotAdminRemove() {
        CreateOrgResult result = store.createOrg(alice);
        store.addMember(result.org().id(), bob, Role.MEMBER);
        Membership target = store.addMember(result.org().id(), carol, Role.MEMBER);
        assertThrows(ForbiddenError.class,
                () -> store.adminRemove(target.id(), bob));
    }

    @Test
    void transferOwnershipSwapsAtomically() {
        CreateOrgResult result = store.createOrg(alice);
        Membership bobMem = store.addMember(result.org().id(), bob, Role.MEMBER);
        TransferOwnershipResult out = store.transferOwnership(
                result.org().id(), result.ownerMembership().id(), bobMem.id()
        );
        assertEquals(Role.OWNER, out.toMembership().role());
        assertEquals(bob, out.toMembership().usrId());
        assertEquals(Role.MEMBER, out.fromMembership().role());
        assertEquals(alice, out.fromMembership().usrId());

        List<Tuple> aliceTuples = store.listTuplesForSubject("usr", alice);
        List<Tuple> bobTuples = store.listTuplesForSubject("usr", bob);
        assertTrue(aliceTuples.stream().anyMatch(t -> t.relation().equals("member")));
        assertTrue(bobTuples.stream().anyMatch(t -> t.relation().equals("owner")));
    }

    @Test
    void transferOwnershipRejectsSelfTransfer() {
        CreateOrgResult result = store.createOrg(alice);
        assertThrows(PreconditionError.class,
                () -> store.transferOwnership(
                        result.org().id(),
                        result.ownerMembership().id(),
                        result.ownerMembership().id()
                ));
    }

    @Test
    void acceptInvitationAtomicallyCreatesMemAndMaterializesPretuples() {
        CreateOrgResult result = store.createOrg(alice);
        String projectId = Id.generate("org").substring(4);
        Invitation inv = store.createInvitation(
                result.org().id(),
                "newbie@example.com",
                Role.MEMBER,
                alice,
                future(),
                List.of(new PreTuple("editor", "proj", projectId))
        );
        AcceptInvitationResult out = store.acceptInvitation(inv.id());
        assertEquals(InvitationStatus.ACCEPTED, out.invitation().status());
        assertEquals(Role.MEMBER, out.membership().role());

        // Both the membership tuple AND the pre-tuple were materialized.
        List<Tuple> newUserTuples = store.listTuplesForSubject("usr", out.membership().usrId());
        assertEquals(2, newUserTuples.size());
    }

    @Test
    void declineTerminalThenRedeclineRaises() {
        CreateOrgResult result = store.createOrg(alice);
        Invitation inv = store.createInvitation(
                result.org().id(), "x@example.com", Role.MEMBER, alice, future()
        );
        store.declineInvitation(inv.id(), null);
        assertThrows(InvitationNotPendingError.class,
                () -> store.declineInvitation(inv.id(), null));
    }

    @Test
    void revokeOrgTwiceRaises() {
        CreateOrgResult result = store.createOrg(alice);
        store.revokeOrg(result.org().id());
        assertThrows(AlreadyTerminalError.class,
                () -> store.revokeOrg(result.org().id()));
    }

    @Test
    void acceptInvitationRequiresAcceptingIdentifierWhenAsUsrIdProvided() {
        // ADR 0009: existing-user accept without acceptingIdentifier fails closed.
        CreateOrgResult result = store.createOrg(alice);
        Invitation inv = store.createInvitation(
                result.org().id(), "bob@example.com", Role.MEMBER, alice, future()
        );
        assertThrows(IdentifierBindingRequiredError.class,
                () -> store.acceptInvitation(inv.id(), bob));
    }

    @Test
    void acceptInvitationRejectsMismatchedAcceptingIdentifier() {
        // ADR 0009: this is the privilege-escalation primitive closer.
        CreateOrgResult result = store.createOrg(alice);
        Invitation inv = store.createInvitation(
                result.org().id(), "victim@example.org", Role.OWNER, alice, future()
        );
        IdentifierMismatchError err = assertThrows(IdentifierMismatchError.class,
                () -> store.acceptInvitation(inv.id(), bob, "attacker@example.com"));
        assertEquals("attacker@example.com", err.getAcceptingIdentifier());
        assertEquals("victim@example.org", err.getInvitationIdentifier());
    }

    @Test
    void acceptInvitationWithMatchingAcceptingIdentifierSucceeds() {
        CreateOrgResult result = store.createOrg(alice);
        Invitation inv = store.createInvitation(
                result.org().id(), "bob@example.com", Role.MEMBER, alice, future()
        );
        AcceptInvitationResult out = store.acceptInvitation(inv.id(), bob, "bob@example.com");
        assertEquals(bob, out.membership().usrId());
        assertEquals(InvitationStatus.ACCEPTED, out.invitation().status());
    }
}
