// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

import dev.flametrench.ids.Id;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reference in-memory TenancyStore. Behaviorally spec-conformant for
 * every transition: revoke-and-re-add with {@code replaces} chain on
 * role changes, atomic accept-with-pre-tuples, sole-owner protection,
 * and a shadow tuple set kept in lockstep with mem.status so the
 * mem_/tup_ duality cannot drift.
 */
public class InMemoryTenancyStore {

    private final Map<String, Organization> orgs = new LinkedHashMap<>();
    private final Map<String, Membership> memberships = new LinkedHashMap<>();
    private final Map<String, Invitation> invitations = new LinkedHashMap<>();
    private final Set<String> tupleKeys = new HashSet<>();
    private final Clock clock;

    public InMemoryTenancyStore() {
        this(Clock.systemUTC());
    }

    public InMemoryTenancyStore(Clock clock) {
        this.clock = clock;
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static String tupleKey(Tuple t) {
        return t.subjectType() + "|" + t.subjectId() + "|" + t.relation()
                + "|" + t.objectType() + "|" + t.objectId();
    }

    private static Tuple membershipTuple(Membership m) {
        return new Tuple("usr", m.usrId(), m.role().getValue(), "org", m.orgId());
    }

    private void insertTuple(Tuple t) {
        tupleKeys.add(tupleKey(t));
    }

    private void deleteTuple(Tuple t) {
        tupleKeys.remove(tupleKey(t));
    }

    private int countActiveOwners(String orgId) {
        int n = 0;
        for (Membership m : memberships.values()) {
            if (m.orgId().equals(orgId) && m.status() == Status.ACTIVE && m.role() == Role.OWNER) {
                n++;
            }
        }
        return n;
    }

    private Membership findActiveMembership(String usrId, String orgId) {
        for (Membership m : memberships.values()) {
            if (m.usrId().equals(usrId) && m.orgId().equals(orgId) && m.status() == Status.ACTIVE) {
                return m;
            }
        }
        return null;
    }

    private Organization requireOrg(String orgId) {
        Organization org = orgs.get(orgId);
        if (org == null) throw new NotFoundError("Organization " + orgId + " not found");
        return org;
    }

    private Membership requireMembership(String memId) {
        Membership m = memberships.get(memId);
        if (m == null) throw new NotFoundError("Membership " + memId + " not found");
        return m;
    }

    // ─── Organizations ───

    /**
     * Sentinel for {@link #updateOrg} partial-update semantics. Pass
     * this constant to skip a field (its value is preserved); pass
     * {@code null} to clear the field.
     */
    public static final String UNSET = "__flametrench_unset__";

    /** v0.1-compatible 1-arg createOrg. Defaults name and slug to null. */
    public CreateOrgResult createOrg(String creator) {
        return createOrg(creator, null, null);
    }

    /** v0.2 (ADR 0011) createOrg accepting optional name and slug. */
    public CreateOrgResult createOrg(String creator, String name, String slug) {
        if (slug != null) {
            validateSlug(slug);
            enforceSlugUnique(slug, null);
        }
        Instant now = now();
        Organization org = new Organization(
                Id.generate("org"), Status.ACTIVE, now, now, name, slug
        );
        Membership ownerMembership = new Membership(
                Id.generate("mem"), creator, org.id(), Role.OWNER, Status.ACTIVE,
                null, null, null, now, now
        );
        orgs.put(org.id(), org);
        memberships.put(ownerMembership.id(), ownerMembership);
        insertTuple(membershipTuple(ownerMembership));
        return new CreateOrgResult(org, ownerMembership);
    }

    public Organization getOrg(String orgId) {
        return requireOrg(orgId);
    }

    /**
     * ADR 0011 partial update.
     *
     * <p>Pass {@link #UNSET} to skip a field; pass {@code null} to
     * clear it. Slug uniqueness violations raise
     * {@link OrgSlugConflictError}; updating a revoked org raises
     * {@link AlreadyTerminalError}.
     */
    public Organization updateOrg(String orgId, String name, String slug) {
        Organization org = requireOrg(orgId);
        if (org.status() == Status.REVOKED) {
            throw new AlreadyTerminalError("Org " + orgId + " is revoked; cannot update");
        }
        String newName = UNSET.equals(name) ? org.name() : name;
        String newSlug = UNSET.equals(slug) ? org.slug() : slug;
        if (!UNSET.equals(slug) && newSlug != null) {
            validateSlug(newSlug);
            enforceSlugUnique(newSlug, orgId);
        }
        Organization updated = org.withMetadata(newName, newSlug, now());
        orgs.put(orgId, updated);
        return updated;
    }

    private void enforceSlugUnique(String slug, String excludeOrgId) {
        for (Organization existing : orgs.values()) {
            if (existing.id().equals(excludeOrgId)) continue;
            if (slug.equals(existing.slug()) && existing.status() != Status.REVOKED) {
                throw new OrgSlugConflictError(slug);
            }
        }
    }

    private static final java.util.regex.Pattern SLUG_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    private static void validateSlug(String slug) {
        if (!SLUG_PATTERN.matcher(slug).matches()) {
            throw new PreconditionError(
                    "Slug '" + slug + "' does not match the spec pattern "
                            + "(DNS-label-style: 1-63 lowercase ASCII chars or digits or hyphens, "
                            + "no leading/trailing hyphen)",
                    "org_slug_format"
            );
        }
    }

    private Organization transitionOrg(String orgId, Status to) {
        Organization org = requireOrg(orgId);
        if (org.status() == to) {
            throw new AlreadyTerminalError("Org " + orgId + " is already " + to.getValue());
        }
        if (org.status() == Status.REVOKED) {
            throw new AlreadyTerminalError("Org " + orgId + " is revoked; cannot transition");
        }
        Organization updated = org.withStatus(to, now());
        orgs.put(orgId, updated);
        return updated;
    }

    public Organization suspendOrg(String orgId) {
        return transitionOrg(orgId, Status.SUSPENDED);
    }

    public Organization reinstateOrg(String orgId) {
        Organization org = requireOrg(orgId);
        if (org.status() != Status.SUSPENDED) {
            throw new PreconditionError(
                    "Org " + orgId + " is " + org.status().getValue()
                            + "; only suspended orgs can be reinstated",
                    "invalid_transition"
            );
        }
        return transitionOrg(orgId, Status.ACTIVE);
    }

    public Organization revokeOrg(String orgId) {
        Organization org = requireOrg(orgId);
        if (org.status() == Status.REVOKED) {
            throw new AlreadyTerminalError("Org " + orgId + " is already revoked");
        }
        Instant now = now();
        for (Map.Entry<String, Membership> e : new ArrayList<>(memberships.entrySet())) {
            Membership m = e.getValue();
            if (m.orgId().equals(orgId) && m.status() == Status.ACTIVE) {
                deleteTuple(membershipTuple(m));
                memberships.put(e.getKey(), m.withStatus(Status.REVOKED, now));
            }
        }
        Organization updated = org.withStatus(Status.REVOKED, now);
        orgs.put(orgId, updated);
        return updated;
    }

    // ─── Memberships ───

    public Membership addMember(String orgId, String usrId, Role role, String invitedBy) {
        Organization org = requireOrg(orgId);
        if (org.status() != Status.ACTIVE) {
            throw new PreconditionError(
                    "Cannot add member to " + org.status().getValue() + " org",
                    "org_not_active"
            );
        }
        if (findActiveMembership(usrId, orgId) != null) {
            throw new DuplicateMembershipError(
                    "User " + usrId + " already has an active membership in " + orgId
            );
        }
        Instant now = now();
        Membership mem = new Membership(
                Id.generate("mem"), usrId, orgId, role, Status.ACTIVE,
                null, invitedBy, null, now, now
        );
        memberships.put(mem.id(), mem);
        insertTuple(membershipTuple(mem));
        return mem;
    }

    public Membership addMember(String orgId, String usrId, Role role) {
        return addMember(orgId, usrId, role, null);
    }

    public Membership getMembership(String memId) {
        return requireMembership(memId);
    }

    public Membership changeRole(String memId, Role newRole) {
        Membership old = requireMembership(memId);
        if (old.status() != Status.ACTIVE) {
            throw new PreconditionError(
                    "Membership " + memId + " is " + old.status().getValue()
                            + "; only active memberships can change role",
                    "mem_not_active"
            );
        }
        if (old.role() == Role.OWNER && newRole != Role.OWNER
                && countActiveOwners(old.orgId()) == 1) {
            throw new SoleOwnerError(
                    "Cannot change role of the sole active owner; transfer ownership first"
            );
        }
        Instant now = now();
        Membership revoked = old.withStatus(Status.REVOKED, now);
        memberships.put(old.id(), revoked);
        deleteTuple(membershipTuple(old));

        Membership fresh = new Membership(
                Id.generate("mem"), old.usrId(), old.orgId(), newRole, Status.ACTIVE,
                old.id(), old.invitedBy(), null, now, now
        );
        memberships.put(fresh.id(), fresh);
        insertTuple(membershipTuple(fresh));
        return fresh;
    }

    public Membership suspendMembership(String memId) {
        Membership mem = requireMembership(memId);
        if (mem.status() != Status.ACTIVE) {
            throw new PreconditionError(
                    "Membership " + memId + " is " + mem.status().getValue()
                            + "; only active memberships can be suspended",
                    "mem_not_active"
            );
        }
        if (mem.role() == Role.OWNER && countActiveOwners(mem.orgId()) == 1) {
            throw new SoleOwnerError(
                    "Cannot suspend the sole active owner; transfer ownership first"
            );
        }
        Instant now = now();
        Membership updated = mem.withStatus(Status.SUSPENDED, now);
        memberships.put(memId, updated);
        deleteTuple(membershipTuple(mem));
        return updated;
    }

    public Membership reinstateMembership(String memId) {
        Membership mem = requireMembership(memId);
        if (mem.status() != Status.SUSPENDED) {
            throw new PreconditionError(
                    "Membership " + memId + " is " + mem.status().getValue()
                            + "; only suspended memberships can be reinstated",
                    "invalid_transition"
            );
        }
        if (findActiveMembership(mem.usrId(), mem.orgId()) != null) {
            throw new DuplicateMembershipError(
                    "User " + mem.usrId() + " has a separate active membership in "
                            + mem.orgId() + "; cannot reinstate"
            );
        }
        Instant now = now();
        Membership updated = mem.withStatus(Status.ACTIVE, now);
        memberships.put(memId, updated);
        insertTuple(membershipTuple(updated));
        return updated;
    }

    public Membership selfLeave(String memId, String transferTo) {
        Membership mem = requireMembership(memId);
        if (mem.status() != Status.ACTIVE) {
            throw new PreconditionError(
                    "Membership " + memId + " is " + mem.status().getValue()
                            + "; only active memberships can self-leave",
                    "mem_not_active"
            );
        }
        if (mem.role() == Role.OWNER && countActiveOwners(mem.orgId()) == 1) {
            if (transferTo == null) {
                throw new SoleOwnerError(
                        "Cannot self-leave as sole active owner; pass transferTo to atomically transfer ownership"
                );
            }
            Membership target = findActiveMembership(transferTo, mem.orgId());
            if (target == null) {
                throw new NotFoundError(
                        "transferTo user " + transferTo + " has no active membership in " + mem.orgId()
                );
            }
            // Promote target first so the donor is no longer the sole active owner.
            changeRole(target.id(), Role.OWNER);
        }
        Instant now = now();
        Membership revoked = mem.withStatusAndRemovedBy(Status.REVOKED, null, now);
        memberships.put(mem.id(), revoked);
        deleteTuple(membershipTuple(mem));
        return revoked;
    }

    public Membership selfLeave(String memId) {
        return selfLeave(memId, null);
    }

    public Membership adminRemove(String memId, String adminUsrId) {
        Membership target = requireMembership(memId);
        if (target.status() != Status.ACTIVE) {
            throw new PreconditionError(
                    "Target membership " + memId + " is " + target.status().getValue(),
                    "mem_not_active"
            );
        }
        Membership admin = findActiveMembership(adminUsrId, target.orgId());
        if (admin == null) {
            throw new ForbiddenError(
                    "User " + adminUsrId + " has no active membership in " + target.orgId()
            );
        }
        if (admin.role() != Role.OWNER && admin.role() != Role.ADMIN) {
            throw new ForbiddenError(
                    "Role " + admin.role().getValue() + " is not permitted to remove members"
            );
        }
        if (target.role() == Role.OWNER) {
            throw new RoleHierarchyError(
                    "Owner removal requires transferOwnership, not adminRemove"
            );
        }
        Integer adminRank = admin.role().adminRank();
        Integer targetRank = target.role().adminRank();
        if (adminRank == null || targetRank == null) {
            throw new PreconditionError(
                    "adminRemove operates only on owner/admin/member/guest roles",
                    "scope_mismatch"
            );
        }
        if (adminRank < targetRank) {
            throw new RoleHierarchyError(
                    "Role " + admin.role().getValue() + " cannot remove role " + target.role().getValue()
            );
        }
        Instant now = now();
        Membership revoked = target.withStatusAndRemovedBy(Status.REVOKED, admin.usrId(), now);
        memberships.put(target.id(), revoked);
        deleteTuple(membershipTuple(target));
        return revoked;
    }

    public TransferOwnershipResult transferOwnership(String orgId, String fromMemId, String toMemId) {
        Membership from = requireMembership(fromMemId);
        Membership to = requireMembership(toMemId);
        if (from.status() != Status.ACTIVE) {
            throw new PreconditionError(
                    "From membership " + fromMemId + " is " + from.status().getValue(),
                    "from_not_active"
            );
        }
        if (to.status() != Status.ACTIVE) {
            throw new PreconditionError(
                    "To membership " + toMemId + " is " + to.status().getValue(),
                    "to_not_active"
            );
        }
        if (!from.orgId().equals(orgId) || !to.orgId().equals(orgId)) {
            throw new PreconditionError(
                    "Both memberships must belong to " + orgId,
                    "org_mismatch"
            );
        }
        if (from.role() != Role.OWNER) {
            throw new PreconditionError(
                    "From membership must hold the owner role",
                    "from_not_owner"
            );
        }
        if (from.usrId().equals(to.usrId())) {
            throw new PreconditionError("Cannot transfer ownership to self", "self_transfer");
        }
        // Promote target first so the donor is no longer the sole active owner.
        Membership toFresh = changeRole(to.id(), Role.OWNER);
        Membership fromFresh = changeRole(from.id(), Role.MEMBER);
        return new TransferOwnershipResult(fromFresh, toFresh);
    }

    // ─── Invitations ───

    public Invitation createInvitation(
            String orgId,
            String identifier,
            Role role,
            String invitedBy,
            Instant expiresAt,
            List<PreTuple> preTuples
    ) {
        Organization org = requireOrg(orgId);
        if (org.status() != Status.ACTIVE) {
            throw new PreconditionError(
                    "Cannot create invitation for " + org.status().getValue() + " org",
                    "org_not_active"
            );
        }
        Instant now = now();
        if (!expiresAt.isAfter(now)) {
            throw new PreconditionError("expiresAt must be in the future", "past_expiration");
        }
        Invitation inv = new Invitation(
                Id.generate("inv"),
                orgId,
                identifier,
                role,
                InvitationStatus.PENDING,
                preTuples == null ? List.of() : List.copyOf(preTuples),
                invitedBy,
                null,
                now,
                expiresAt,
                null,
                null
        );
        invitations.put(inv.id(), inv);
        return inv;
    }

    public Invitation createInvitation(
            String orgId, String identifier, Role role, String invitedBy, Instant expiresAt
    ) {
        return createInvitation(orgId, identifier, role, invitedBy, expiresAt, List.of());
    }

    public Invitation getInvitation(String invId) {
        Invitation inv = invitations.get(invId);
        if (inv == null) throw new NotFoundError("Invitation " + invId + " not found");
        return inv;
    }

    public AcceptInvitationResult acceptInvitation(
            String invId,
            String asUsrId,
            String acceptingIdentifier
    ) {
        Invitation inv = getInvitation(invId);
        if (inv.status() != InvitationStatus.PENDING) {
            throw new InvitationNotPendingError(
                    "Invitation " + invId + " is " + inv.status().getValue() + ", not pending"
            );
        }
        Instant now = now();
        if (now.isAfter(inv.expiresAt())) {
            throw new InvitationExpiredError(
                    "Invitation " + invId + " expired at " + inv.expiresAt()
            );
        }
        // ADR 0009: existing-user accept MUST supply a matching identifier.
        if (asUsrId != null) {
            if (acceptingIdentifier == null) {
                throw new IdentifierBindingRequiredError();
            }
            if (!acceptingIdentifier.equals(inv.identifier())) {
                throw new IdentifierMismatchError(acceptingIdentifier, inv.identifier());
            }
        }
        String usrId = asUsrId != null ? asUsrId : Id.generate("usr");
        if (findActiveMembership(usrId, inv.orgId()) != null) {
            throw new DuplicateMembershipError(
                    "User " + usrId + " already has an active membership in " + inv.orgId()
            );
        }
        Membership membership = new Membership(
                Id.generate("mem"), usrId, inv.orgId(), inv.role(), Status.ACTIVE,
                null, inv.invitedBy(), null, now, now
        );
        memberships.put(membership.id(), membership);
        insertTuple(membershipTuple(membership));

        List<Tuple> materialized = new ArrayList<>();
        for (PreTuple pt : inv.preTuples()) {
            Tuple t = new Tuple("usr", usrId, pt.relation(), pt.objectType(), pt.objectId());
            insertTuple(t);
            materialized.add(t);
        }

        Invitation updatedInv = inv.transitionTerminal(
                InvitationStatus.ACCEPTED, now, usrId, usrId
        );
        invitations.put(inv.id(), updatedInv);

        return new AcceptInvitationResult(updatedInv, membership, materialized);
    }

    public AcceptInvitationResult acceptInvitation(String invId) {
        return acceptInvitation(invId, null, null);
    }

    /**
     * Backwards-compatible 2-arg overload retained for the mint-new-user
     * flow only. Callers passing a non-null {@code asUsrId} via this
     * overload will hit {@link IdentifierBindingRequiredError} per
     * ADR 0009 — use the 3-arg form with the matching identifier.
     */
    public AcceptInvitationResult acceptInvitation(String invId, String asUsrId) {
        return acceptInvitation(invId, asUsrId, null);
    }

    public Invitation declineInvitation(String invId, String asUsrId) {
        Invitation inv = getInvitation(invId);
        if (inv.status() != InvitationStatus.PENDING) {
            throw new InvitationNotPendingError(
                    "Invitation " + invId + " is " + inv.status().getValue() + ", not pending"
            );
        }
        Invitation updated = inv.transitionTerminal(
                InvitationStatus.DECLINED, now(), asUsrId, null
        );
        invitations.put(inv.id(), updated);
        return updated;
    }

    public Invitation revokeInvitation(String invId, String adminUsrId) {
        Invitation inv = getInvitation(invId);
        if (inv.status() != InvitationStatus.PENDING) {
            throw new InvitationNotPendingError(
                    "Invitation " + invId + " is " + inv.status().getValue() + ", not pending"
            );
        }
        Invitation updated = inv.transitionTerminal(
                InvitationStatus.REVOKED, now(), adminUsrId, null
        );
        invitations.put(inv.id(), updated);
        return updated;
    }

    // ─── Tuple accessors ───

    public List<Tuple> listTuplesForSubject(String subjectType, String subjectId) {
        String prefix = subjectType + "|" + subjectId + "|";
        List<Tuple> results = new ArrayList<>();
        for (String key : tupleKeys) {
            if (!key.startsWith(prefix)) continue;
            String[] parts = key.split("\\|");
            if (parts.length != 5) continue;
            results.add(new Tuple(parts[0], parts[1], parts[2], parts[3], parts[4]));
        }
        return results;
    }

    public List<Tuple> listTuplesForObject(String objectType, String objectId, String relation) {
        List<Tuple> results = new ArrayList<>();
        for (String key : tupleKeys) {
            String[] parts = key.split("\\|");
            if (parts.length != 5) continue;
            if (!parts[3].equals(objectType) || !parts[4].equals(objectId)) continue;
            if (relation != null && !parts[2].equals(relation)) continue;
            results.add(new Tuple(parts[0], parts[1], parts[2], parts[3], parts[4]));
        }
        return results;
    }
}
