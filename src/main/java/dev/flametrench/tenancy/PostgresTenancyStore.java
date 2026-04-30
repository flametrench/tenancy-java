// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flametrench.ids.Id;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * PostgresTenancyStore — Postgres-backed implementation of the tenancy
 * surface. Mirrors {@link InMemoryTenancyStore} byte-for-byte at the
 * SDK boundary; the difference is durability and concurrency.
 *
 * <p>Every operation that touches more than one row runs inside a
 * {@code BEGIN}/{@code COMMIT} block so the spec's atomicity guarantees
 * (membership + tuple together, accept-with-pre-tuples, transferOwnership)
 * are backed by a real database transaction.
 */
public class PostgresTenancyStore {

    public static final String UNSET = InMemoryTenancyStore.UNSET;

    private static final String UNIQUE_VIOLATION = "23505";

    private static final Pattern SLUG_PATTERN =
            Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    private static final Map<String, Integer> ADMIN_RANK = Map.of(
            "owner", 4, "admin", 3, "member", 2, "guest", 1
    );

    private static final String ORG_COLS = "id, status, name, slug, created_at, updated_at";
    private static final String MEM_COLS =
            "id, usr_id, org_id, role, status, replaces, invited_by, removed_by, created_at, updated_at";
    private static final String INV_COLS =
            "id, org_id, identifier, role, status, pre_tuples, invited_by, invited_user_id, "
            + "created_at, expires_at, terminal_at, terminal_by";
    private static final String TUP_COLS =
            "id, subject_type, subject_id, relation, object_type, object_id, created_at, created_by";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final DataSource dataSource;
    private final Connection callerConnection;
    private final Clock clock;

    public PostgresTenancyStore(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public PostgresTenancyStore(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.callerConnection = null;
        this.clock = clock;
    }

    /**
     * ADR 0013 caller-owned-connection constructor. The adopter manages
     * the Connection's lifecycle and outer transaction; this store
     * cooperates by using {@code SAVEPOINT}/{@code RELEASE} for its
     * internal atomicity boundaries instead of opening its own
     * transaction.
     */
    public PostgresTenancyStore(Connection callerConnection) {
        this(callerConnection, Clock.systemUTC());
    }

    public PostgresTenancyStore(Connection callerConnection, Clock clock) {
        this.dataSource = null;
        this.callerConnection = callerConnection;
        this.clock = clock;
    }

    private boolean isCallerOwned() {
        return callerConnection != null;
    }

    private Connection acquireConnection() throws SQLException {
        if (callerConnection == null) return dataSource.getConnection();
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] { Connection.class },
            (proxy, method, args) -> {
                if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                    return null;
                }
                try {
                    return method.invoke(callerConnection, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        );
    }

    private static String makeSavepointName() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        String method = stack.length > 3 ? stack[3].getMethodName() : "tx";
        String safe = method.replaceAll("[^A-Za-z0-9_]", "");
        if (safe.isEmpty()) safe = "tx";
        byte[] r = new byte[4];
        SECURE_RANDOM.nextBytes(r);
        StringBuilder hex = new StringBuilder(8);
        for (byte b : r) hex.append(String.format("%02x", b & 0xff));
        return "ft_" + safe + "_" + hex;
    }

    private Instant now() {
        return clock.instant();
    }

    private static UUID wireToUuid(String wireId) {
        return UUID.fromString(Id.decode(wireId).uuid());
    }

    private static final java.util.regex.Pattern OBJECT_ID_WIRE =
            java.util.regex.Pattern.compile("^[a-z]{2,6}_[0-9a-f]{32}$");

    /**
     * Decode an {@code object_id} to a Postgres-bindable UUID. See
     * authz {@code PostgresTupleStore#objectIdToUuid} for rationale
     * (spec#8) — wire-format prefixed IDs with non-registered prefixes
     * (e.g. {@code proj_<hex>}) are decoded via {@link Id#decodeAny}.
     */
    private static UUID objectIdToUuid(String objectId) {
        if (OBJECT_ID_WIRE.matcher(objectId).matches()) {
            return UUID.fromString(Id.decodeAny(objectId).uuid());
        }
        if (objectId.length() == 32) {
            String s = objectId;
            return UUID.fromString(
                    s.substring(0, 8) + "-" + s.substring(8, 12)
                  + "-" + s.substring(12, 16) + "-" + s.substring(16, 20)
                  + "-" + s.substring(20)
            );
        }
        return UUID.fromString(objectId);
    }

    /** Map a Postgres {@code role} value back to the {@link Role} enum. */
    private static Role _RoleFromString(String value) {
        for (Role r : Role.values()) {
            if (r.getValue().equals(value)) return r;
        }
        throw new IllegalArgumentException("Unknown role: " + value);
    }

    private static void validateSlug(String slug) {
        if (!SLUG_PATTERN.matcher(slug).matches()) {
            throw new PreconditionError(
                    "Slug '" + slug + "' does not match the spec pattern (DNS-label-style)",
                    "org_slug_format"
            );
        }
    }

    private static boolean isUniqueViolation(SQLException e) {
        return UNIQUE_VIOLATION.equals(e.getSQLState());
    }

    private static boolean isUniqueViolationOn(SQLException e, String constraint) {
        return UNIQUE_VIOLATION.equals(e.getSQLState())
                && e.getMessage() != null
                && e.getMessage().contains(constraint);
    }

    @FunctionalInterface
    private interface TxFn<T> {
        T apply(Connection conn) throws SQLException;
    }

    private <T> T tx(TxFn<T> fn) {
        if (isCallerOwned()) {
            Connection conn = callerConnection;
            try {
                Savepoint sp = conn.setSavepoint(makeSavepointName());
                try {
                    T result = fn.apply(conn);
                    conn.releaseSavepoint(sp);
                    return result;
                } catch (SQLException | RuntimeException e) {
                    try {
                        conn.rollback(sp);
                        conn.releaseSavepoint(sp);
                    } catch (SQLException ignored) {
                    }
                    if (e instanceof SQLException) throw new RuntimeException(e);
                    throw (RuntimeException) e;
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
        try (Connection conn = acquireConnection()) {
            boolean prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                T result = fn.apply(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                if (e instanceof SQLException) throw new RuntimeException(e);
                throw (RuntimeException) e;
            } finally {
                conn.setAutoCommit(prevAuto);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Shield $fn with a savepoint when the store is caller-owned (inside an
     * adopter's outer transaction); pass through to a fresh connection when
     * standalone. Used by single-statement writes that don't need their own
     * BEGIN/COMMIT but must not contaminate an outer transaction on a
     * constraint violation. See ADR 0013.
     */
    private <T> T nested(TxFn<T> fn) {
        if (!isCallerOwned()) {
            try (Connection conn = acquireConnection()) {
                return fn.apply(conn);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
        Connection conn = callerConnection;
        try {
            Savepoint sp = conn.setSavepoint(makeSavepointName());
            try {
                T result = fn.apply(conn);
                conn.releaseSavepoint(sp);
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    conn.rollback(sp);
                    conn.releaseSavepoint(sp);
                } catch (SQLException ignored) {
                }
                if (e instanceof SQLException) throw new RuntimeException(e);
                throw (RuntimeException) e;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ─── Row mappers ───

    private static Organization rowToOrg(ResultSet rs) throws SQLException {
        String orgUuid = rs.getString("id");
        return new Organization(
                Id.encode("org", orgUuid),
                Status.valueOf(rs.getString("status").toUpperCase()),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("name"),
                rs.getString("slug")
        );
    }

    private static Membership rowToMem(ResultSet rs) throws SQLException {
        String replaces = rs.getString("replaces");
        String invitedBy = rs.getString("invited_by");
        String removedBy = rs.getString("removed_by");
        return new Membership(
                Id.encode("mem", rs.getString("id")),
                Id.encode("usr", rs.getString("usr_id")),
                Id.encode("org", rs.getString("org_id")),
                _RoleFromString(rs.getString("role")),
                Status.valueOf(rs.getString("status").toUpperCase()),
                replaces != null ? Id.encode("mem", replaces) : null,
                invitedBy != null ? Id.encode("usr", invitedBy) : null,
                removedBy != null ? Id.encode("usr", removedBy) : null,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static Invitation rowToInv(ResultSet rs) throws SQLException {
        String invitedUserId = rs.getString("invited_user_id");
        String terminalBy = rs.getString("terminal_by");
        Timestamp terminalAt = rs.getTimestamp("terminal_at");
        List<PreTuple> preTuples = parsePreTuples(rs.getString("pre_tuples"));
        return new Invitation(
                Id.encode("inv", rs.getString("id")),
                Id.encode("org", rs.getString("org_id")),
                rs.getString("identifier"),
                _RoleFromString(rs.getString("role")),
                InvitationStatus.valueOf(rs.getString("status").toUpperCase()),
                preTuples,
                Id.encode("usr", rs.getString("invited_by")),
                invitedUserId != null ? Id.encode("usr", invitedUserId) : null,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                terminalAt != null ? terminalAt.toInstant() : null,
                terminalBy != null ? Id.encode("usr", terminalBy) : null
        );
    }

    private static Tuple rowToTup(ResultSet rs) throws SQLException {
        return new Tuple(
                rs.getString("subject_type"),
                Id.encode("usr", rs.getString("subject_id")),
                rs.getString("relation"),
                rs.getString("object_type"),
                rs.getString("object_id")
        );
    }

    private static List<PreTuple> parsePreTuples(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> raw = MAPPER.readValue(
                    json, new TypeReference<List<Map<String, Object>>>() {}
            );
            List<PreTuple> out = new ArrayList<>(raw.size());
            for (Map<String, Object> entry : raw) {
                String relation = stringOrEmpty(entry.get("relation"));
                String objectType = stringOrEmpty(entry.getOrDefault("object_type", entry.get("objectType")));
                String objectId = stringOrEmpty(entry.getOrDefault("object_id", entry.get("objectId")));
                out.add(new PreTuple(relation, objectType, objectId));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String stringOrEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String preTuplesToJson(List<PreTuple> preTuples) {
        try {
            List<Map<String, String>> raw = new ArrayList<>(preTuples.size());
            for (PreTuple pt : preTuples) {
                Map<String, String> entry = new HashMap<>(3);
                entry.put("relation", pt.relation());
                entry.put("object_type", pt.objectType());
                entry.put("object_id", pt.objectId());
                raw.add(entry);
            }
            return MAPPER.writeValueAsString(raw);
        } catch (Exception e) {
            return "[]";
        }
    }

    // ─── Organizations ───

    public CreateOrgResult createOrg(String creator) {
        return createOrg(creator, null, null);
    }

    public CreateOrgResult createOrg(String creator, String name, String slug) {
        if (slug != null) validateSlug(slug);
        Instant now = now();
        UUID orgUuid = UUID.fromString(Id.decode(Id.generate("org")).uuid());
        UUID memUuid = UUID.fromString(Id.decode(Id.generate("mem")).uuid());
        UUID tupUuid = UUID.fromString(Id.decode(Id.generate("tup")).uuid());
        UUID creatorUuid = wireToUuid(creator);
        Timestamp ts = Timestamp.from(now);
        return tx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO org (id, status, name, slug, created_at, updated_at)"
                  + " VALUES (?, 'active', ?, ?, ?, ?) RETURNING " + ORG_COLS)) {
                ps.setObject(1, orgUuid);
                ps.setString(2, name);
                ps.setString(3, slug);
                ps.setTimestamp(4, ts);
                ps.setTimestamp(5, ts);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("INSERT returned no row");
                    Organization org = rowToOrg(rs);
                    insertMembership(conn, memUuid, creatorUuid, orgUuid, "owner", null, null, ts);
                    insertMembershipTuple(conn, tupUuid, creatorUuid, "owner", orgUuid, ts, creatorUuid);
                    Membership ownerMembership = new Membership(
                            Id.encode("mem", memUuid.toString()),
                            creator,
                            org.id(),
                            Role.OWNER,
                            Status.ACTIVE,
                            null, null, null,
                            now, now
                    );
                    return new CreateOrgResult(org, ownerMembership);
                }
            } catch (SQLException e) {
                if (slug != null && isUniqueViolationOn(e, "org_slug_unique")) {
                    throw new OrgSlugConflictError(slug);
                }
                throw new RuntimeException(e);
            }
        });
    }

    private static void insertMembership(
            Connection conn, UUID memUuid, UUID usrUuid, UUID orgUuid,
            String role, UUID replaces, UUID invitedBy, Timestamp ts
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO mem (id, usr_id, org_id, role, status, replaces, invited_by, created_at, updated_at)"
              + " VALUES (?, ?, ?, ?, 'active', ?, ?, ?, ?)")) {
            ps.setObject(1, memUuid);
            ps.setObject(2, usrUuid);
            ps.setObject(3, orgUuid);
            ps.setString(4, role);
            ps.setObject(5, replaces);
            ps.setObject(6, invitedBy);
            ps.setTimestamp(7, ts);
            ps.setTimestamp(8, ts);
            ps.executeUpdate();
        }
    }

    private static void insertMembershipTuple(
            Connection conn, UUID tupUuid, UUID subjectUuid, String relation,
            UUID orgUuid, Timestamp ts, UUID createdBy
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tup (id, subject_type, subject_id, relation, object_type, object_id, created_at, created_by)"
              + " VALUES (?, 'usr', ?, ?, 'org', ?, ?, ?)")) {
            ps.setObject(1, tupUuid);
            ps.setObject(2, subjectUuid);
            ps.setString(3, relation);
            ps.setObject(4, orgUuid);
            ps.setTimestamp(5, ts);
            ps.setObject(6, createdBy);
            ps.executeUpdate();
        }
    }

    public Organization getOrg(String orgId) {
        try (Connection conn = acquireConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + ORG_COLS + " FROM org WHERE id = ?")) {
            ps.setObject(1, wireToUuid(orgId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new NotFoundError("Organization " + orgId + " not found");
                return rowToOrg(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Organization updateOrg(String orgId, String name, String slug) {
        return tx(conn -> {
            UUID orgUuid = wireToUuid(orgId);
            Organization current;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + ORG_COLS + " FROM org WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, orgUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new NotFoundError("Organization " + orgId + " not found");
                    current = rowToOrg(rs);
                }
            }
            if (current.status() == Status.REVOKED) {
                throw new AlreadyTerminalError("Org " + orgId + " is revoked; cannot update");
            }
            String newName = UNSET.equals(name) ? current.name() : name;
            String newSlug = UNSET.equals(slug) ? current.slug() : slug;
            if (!UNSET.equals(slug) && newSlug != null) {
                validateSlug(newSlug);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE org SET name = ?, slug = ?, updated_at = ? WHERE id = ?"
                  + " RETURNING " + ORG_COLS)) {
                ps.setString(1, newName);
                ps.setString(2, newSlug);
                ps.setTimestamp(3, Timestamp.from(now()));
                ps.setObject(4, orgUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("UPDATE returned no row");
                    return rowToOrg(rs);
                }
            } catch (SQLException e) {
                if (newSlug != null && isUniqueViolationOn(e, "org_slug_unique")) {
                    throw new OrgSlugConflictError(newSlug);
                }
                throw new RuntimeException(e);
            }
        });
    }

    private Organization transitionOrg(String orgId, Status to) {
        return tx(conn -> {
            UUID orgUuid = wireToUuid(orgId);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status FROM org WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, orgUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new NotFoundError("Organization " + orgId + " not found");
                    String cur = rs.getString("status");
                    if ("revoked".equals(cur)) {
                        throw new AlreadyTerminalError("Org " + orgId + " is revoked; cannot transition");
                    }
                    if (cur.equals(to.name().toLowerCase())) {
                        throw new AlreadyTerminalError("Org " + orgId + " is already " + cur);
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE org SET status = ?, updated_at = ? WHERE id = ? RETURNING " + ORG_COLS)) {
                ps.setString(1, to.name().toLowerCase());
                ps.setTimestamp(2, Timestamp.from(now()));
                ps.setObject(3, orgUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rowToOrg(rs);
                }
            }
        });
    }

    public Organization suspendOrg(String orgId) {
        return transitionOrg(orgId, Status.SUSPENDED);
    }

    public Organization reinstateOrg(String orgId) {
        return tx(conn -> {
            UUID orgUuid = wireToUuid(orgId);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status FROM org WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, orgUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new NotFoundError("Organization " + orgId + " not found");
                    String cur = rs.getString("status");
                    if (!"suspended".equals(cur)) {
                        throw new PreconditionError(
                                "Org " + orgId + " is " + cur + "; only suspended orgs can be reinstated",
                                "invalid_transition"
                        );
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE org SET status = 'active', updated_at = ? WHERE id = ? RETURNING " + ORG_COLS)) {
                ps.setTimestamp(1, Timestamp.from(now()));
                ps.setObject(2, orgUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rowToOrg(rs);
                }
            }
        });
    }

    public Organization revokeOrg(String orgId) {
        return tx(conn -> {
            UUID orgUuid = wireToUuid(orgId);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status FROM org WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, orgUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new NotFoundError("Organization " + orgId + " not found");
                    if ("revoked".equals(rs.getString("status"))) {
                        throw new AlreadyTerminalError("Org " + orgId + " is already revoked");
                    }
                }
            }
            Timestamp ts = Timestamp.from(now());
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM tup WHERE object_type = 'org' AND object_id = ?")) {
                ps.setObject(1, orgUuid);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE mem SET status = 'revoked', updated_at = ? WHERE org_id = ? AND status = 'active'")) {
                ps.setTimestamp(1, ts);
                ps.setObject(2, orgUuid);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE org SET status = 'revoked', updated_at = ? WHERE id = ? RETURNING " + ORG_COLS)) {
                ps.setTimestamp(1, ts);
                ps.setObject(2, orgUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rowToOrg(rs);
                }
            }
        });
    }

    // ─── Memberships ───

    public Membership addMember(String orgId, String usrId, Role role, String invitedBy) {
        return tx(conn -> {
            UUID orgUuid = wireToUuid(orgId);
            UUID usrUuid = wireToUuid(usrId);
            UUID invitedByUuid = invitedBy != null ? wireToUuid(invitedBy) : null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status FROM org WHERE id = ?")) {
                ps.setObject(1, orgUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new NotFoundError("Organization " + orgId + " not found");
                    if (!"active".equals(rs.getString("status"))) {
                        throw new PreconditionError(
                                "Cannot add member to " + rs.getString("status") + " org",
                                "org_not_active"
                        );
                    }
                }
            }
            UUID memUuid = UUID.fromString(Id.decode(Id.generate("mem")).uuid());
            UUID tupUuid = UUID.fromString(Id.decode(Id.generate("tup")).uuid());
            Timestamp ts = Timestamp.from(now());
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO mem (id, usr_id, org_id, role, status, invited_by, created_at, updated_at)"
                  + " VALUES (?, ?, ?, ?, 'active', ?, ?, ?) RETURNING " + MEM_COLS)) {
                ps.setObject(1, memUuid);
                ps.setObject(2, usrUuid);
                ps.setObject(3, orgUuid);
                ps.setString(4, role.getValue());
                ps.setObject(5, invitedByUuid);
                ps.setTimestamp(6, ts);
                ps.setTimestamp(7, ts);
                Membership inserted;
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("INSERT returned no row");
                    inserted = rowToMem(rs);
                }
                insertMembershipTuple(conn, tupUuid, usrUuid, role.getValue(), orgUuid, ts, invitedByUuid);
                return inserted;
            } catch (SQLException e) {
                if (isUniqueViolation(e)) {
                    throw new DuplicateMembershipError(
                            "User " + usrId + " already has an active membership in " + orgId
                    );
                }
                throw new RuntimeException(e);
            }
        });
    }

    public Membership addMember(String orgId, String usrId, Role role) {
        return addMember(orgId, usrId, role, null);
    }

    public Membership getMembership(String memId) {
        try (Connection conn = acquireConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + MEM_COLS + " FROM mem WHERE id = ?")) {
            ps.setObject(1, wireToUuid(memId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new NotFoundError("Membership " + memId + " not found");
                return rowToMem(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Page<Membership> listMembers(String orgId, String cursor, int limit, Status status) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + MEM_COLS + " FROM mem WHERE org_id = ?"
        );
        if (status != null) sql.append(" AND status = ?");
        if (cursor != null) sql.append(" AND id > ?");
        sql.append(" ORDER BY id LIMIT ?");
        try (Connection conn = acquireConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setObject(idx++, wireToUuid(orgId));
            if (status != null) ps.setString(idx++, status.name().toLowerCase());
            if (cursor != null) ps.setObject(idx++, wireToUuid(cursor));
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Membership> rows = new ArrayList<>();
                while (rs.next()) rows.add(rowToMem(rs));
                String nextCursor = rows.size() == limit && !rows.isEmpty()
                        ? rows.get(rows.size() - 1).id() : null;
                return new Page<>(List.copyOf(rows), nextCursor);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static class MemRowDirect {
        UUID id;
        UUID usrId;
        UUID orgId;
        String role;
        String status;
        UUID replaces;
        UUID invitedBy;
        UUID removedBy;
    }

    private static MemRowDirect lockMem(Connection conn, String memId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, usr_id, org_id, role, status, replaces, invited_by, removed_by"
              + " FROM mem WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, wireToUuid(memId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new NotFoundError("Membership " + memId + " not found");
                MemRowDirect r = new MemRowDirect();
                r.id = (UUID) rs.getObject("id");
                r.usrId = (UUID) rs.getObject("usr_id");
                r.orgId = (UUID) rs.getObject("org_id");
                r.role = rs.getString("role");
                r.status = rs.getString("status");
                r.replaces = (UUID) rs.getObject("replaces");
                r.invitedBy = (UUID) rs.getObject("invited_by");
                r.removedBy = (UUID) rs.getObject("removed_by");
                return r;
            }
        }
    }

    private static int countOwners(Connection conn, UUID orgUuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM mem WHERE org_id = ? AND role = 'owner' AND status = 'active'")) {
            ps.setObject(1, orgUuid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private Membership rotateMembership(
            Connection conn, MemRowDirect old, Role newRole, UUID removedBy
    ) throws SQLException {
        Timestamp ts = Timestamp.from(now());
        UUID newMem = UUID.fromString(Id.decode(Id.generate("mem")).uuid());
        UUID newTup = UUID.fromString(Id.decode(Id.generate("tup")).uuid());
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE mem SET status = 'revoked', updated_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, ts);
            ps.setObject(2, old.id);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM tup WHERE subject_type = 'usr' AND subject_id = ? AND relation = ?"
              + " AND object_type = 'org' AND object_id = ?")) {
            ps.setObject(1, old.usrId);
            ps.setString(2, old.role);
            ps.setObject(3, old.orgId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO mem (id, usr_id, org_id, role, status, replaces, invited_by, removed_by, created_at, updated_at)"
              + " VALUES (?, ?, ?, ?, 'active', ?, ?, ?, ?, ?) RETURNING " + MEM_COLS)) {
            ps.setObject(1, newMem);
            ps.setObject(2, old.usrId);
            ps.setObject(3, old.orgId);
            ps.setString(4, newRole.getValue());
            ps.setObject(5, old.id);
            ps.setObject(6, old.invitedBy);
            ps.setObject(7, removedBy);
            ps.setTimestamp(8, ts);
            ps.setTimestamp(9, ts);
            Membership newMembership;
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                newMembership = rowToMem(rs);
            }
            try (PreparedStatement ts2 = conn.prepareStatement(
                    "INSERT INTO tup (id, subject_type, subject_id, relation, object_type, object_id, created_at)"
                  + " VALUES (?, 'usr', ?, ?, 'org', ?, ?)")) {
                ts2.setObject(1, newTup);
                ts2.setObject(2, old.usrId);
                ts2.setString(3, newRole.getValue());
                ts2.setObject(4, old.orgId);
                ts2.setTimestamp(5, ts);
                ts2.executeUpdate();
            }
            return newMembership;
        }
    }

    public Membership changeRole(String memId, Role newRole) {
        return tx(conn -> {
            MemRowDirect old = lockMem(conn, memId);
            if (!"active".equals(old.status)) {
                throw new PreconditionError(
                        "Membership " + memId + " is " + old.status + "; only active memberships can change role",
                        "mem_not_active"
                );
            }
            if ("owner".equals(old.role) && newRole != Role.OWNER && countOwners(conn, old.orgId) == 1) {
                throw new SoleOwnerError(
                        "Cannot change role of the sole active owner; transfer ownership first"
                );
            }
            return rotateMembership(conn, old, newRole, null);
        });
    }

    public Membership suspendMembership(String memId) {
        return tx(conn -> {
            MemRowDirect mem = lockMem(conn, memId);
            if (!"active".equals(mem.status)) {
                throw new PreconditionError(
                        "Membership " + memId + " is " + mem.status + "; only active memberships can be suspended",
                        "mem_not_active"
                );
            }
            if ("owner".equals(mem.role) && countOwners(conn, mem.orgId) == 1) {
                throw new SoleOwnerError(
                        "Cannot suspend the sole active owner; transfer ownership first"
                );
            }
            Timestamp ts = Timestamp.from(now());
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM tup WHERE subject_type = 'usr' AND subject_id = ? AND relation = ?"
                  + " AND object_type = 'org' AND object_id = ?")) {
                ps.setObject(1, mem.usrId);
                ps.setString(2, mem.role);
                ps.setObject(3, mem.orgId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE mem SET status = 'suspended', updated_at = ? WHERE id = ? RETURNING " + MEM_COLS)) {
                ps.setTimestamp(1, ts);
                ps.setObject(2, mem.id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rowToMem(rs);
                }
            }
        });
    }

    public Membership reinstateMembership(String memId) {
        return tx(conn -> {
            MemRowDirect mem = lockMem(conn, memId);
            if (!"suspended".equals(mem.status)) {
                throw new PreconditionError(
                        "Membership " + memId + " is " + mem.status + "; only suspended memberships can be reinstated",
                        "invalid_transition"
                );
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM mem WHERE usr_id = ? AND org_id = ? AND status = 'active'")) {
                ps.setObject(1, mem.usrId);
                ps.setObject(2, mem.orgId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        throw new DuplicateMembershipError(
                                "User has a separate active membership in this org; cannot reinstate"
                        );
                    }
                }
            }
            Timestamp ts = Timestamp.from(now());
            UUID newTup = UUID.fromString(Id.decode(Id.generate("tup")).uuid());
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE mem SET status = 'active', updated_at = ? WHERE id = ? RETURNING " + MEM_COLS)) {
                ps.setTimestamp(1, ts);
                ps.setObject(2, mem.id);
                Membership reinstated;
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    reinstated = rowToMem(rs);
                }
                try (PreparedStatement ts2 = conn.prepareStatement(
                        "INSERT INTO tup (id, subject_type, subject_id, relation, object_type, object_id, created_at)"
                      + " VALUES (?, 'usr', ?, ?, 'org', ?, ?)")) {
                    ts2.setObject(1, newTup);
                    ts2.setObject(2, mem.usrId);
                    ts2.setString(3, mem.role);
                    ts2.setObject(4, mem.orgId);
                    ts2.setTimestamp(5, ts);
                    ts2.executeUpdate();
                }
                return reinstated;
            }
        });
    }

    public Membership selfLeave(String memId, String transferTo) {
        return tx(conn -> {
            MemRowDirect mem = lockMem(conn, memId);
            if (!"active".equals(mem.status)) {
                throw new PreconditionError(
                        "Membership " + memId + " is " + mem.status + "; only active memberships can self-leave",
                        "mem_not_active"
                );
            }
            if ("owner".equals(mem.role) && countOwners(conn, mem.orgId) == 1) {
                if (transferTo == null) {
                    throw new SoleOwnerError(
                            "Cannot self-leave as sole active owner; pass transferTo to atomically transfer ownership"
                    );
                }
                MemRowDirect target;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, usr_id, org_id, role, status, replaces, invited_by, removed_by"
                      + " FROM mem WHERE usr_id = ? AND org_id = ? AND status = 'active' FOR UPDATE")) {
                    ps.setObject(1, wireToUuid(transferTo));
                    ps.setObject(2, mem.orgId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new NotFoundError(
                                    "transferTo user " + transferTo + " has no active membership in org"
                            );
                        }
                        target = new MemRowDirect();
                        target.id = (UUID) rs.getObject("id");
                        target.usrId = (UUID) rs.getObject("usr_id");
                        target.orgId = (UUID) rs.getObject("org_id");
                        target.role = rs.getString("role");
                        target.status = rs.getString("status");
                        target.invitedBy = (UUID) rs.getObject("invited_by");
                    }
                }
                rotateMembership(conn, target, Role.OWNER, null);
            }
            Timestamp ts = Timestamp.from(now());
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM tup WHERE subject_type = 'usr' AND subject_id = ? AND relation = ?"
                  + " AND object_type = 'org' AND object_id = ?")) {
                ps.setObject(1, mem.usrId);
                ps.setString(2, mem.role);
                ps.setObject(3, mem.orgId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE mem SET status = 'revoked', removed_by = NULL, updated_at = ? WHERE id = ?"
                  + " RETURNING " + MEM_COLS)) {
                ps.setTimestamp(1, ts);
                ps.setObject(2, mem.id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rowToMem(rs);
                }
            }
        });
    }

    public Membership selfLeave(String memId) {
        return selfLeave(memId, null);
    }

    public Membership adminRemove(String memId, String adminUsrId) {
        return tx(conn -> {
            MemRowDirect target = lockMem(conn, memId);
            if (!"active".equals(target.status)) {
                throw new PreconditionError(
                        "Target membership " + memId + " is " + target.status,
                        "mem_not_active"
                );
            }
            UUID adminUuid;
            String adminRole;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT usr_id, role FROM mem WHERE usr_id = ? AND org_id = ? AND status = 'active'")) {
                ps.setObject(1, wireToUuid(adminUsrId));
                ps.setObject(2, target.orgId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new ForbiddenError(
                                "User " + adminUsrId + " has no active membership in target org"
                        );
                    }
                    adminUuid = (UUID) rs.getObject("usr_id");
                    adminRole = rs.getString("role");
                }
            }
            if (!"owner".equals(adminRole) && !"admin".equals(adminRole)) {
                throw new ForbiddenError(
                        "Role " + adminRole + " is not permitted to remove members"
                );
            }
            if ("owner".equals(target.role)) {
                throw new RoleHierarchyError(
                        "Owner removal requires transferOwnership, not adminRemove"
                );
            }
            Integer adminRank = ADMIN_RANK.get(adminRole);
            Integer targetRank = ADMIN_RANK.get(target.role);
            if (adminRank == null || targetRank == null) {
                throw new PreconditionError(
                        "adminRemove operates only on owner/admin/member/guest roles",
                        "scope_mismatch"
                );
            }
            if (adminRank < targetRank) {
                throw new RoleHierarchyError(
                        "Role " + adminRole + " cannot remove role " + target.role
                );
            }
            Timestamp ts = Timestamp.from(now());
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM tup WHERE subject_type = 'usr' AND subject_id = ? AND relation = ?"
                  + " AND object_type = 'org' AND object_id = ?")) {
                ps.setObject(1, target.usrId);
                ps.setString(2, target.role);
                ps.setObject(3, target.orgId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE mem SET status = 'revoked', removed_by = ?, updated_at = ? WHERE id = ?"
                  + " RETURNING " + MEM_COLS)) {
                ps.setObject(1, adminUuid);
                ps.setTimestamp(2, ts);
                ps.setObject(3, target.id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rowToMem(rs);
                }
            }
        });
    }

    public TransferOwnershipResult transferOwnership(String orgId, String fromMemId, String toMemId) {
        return tx(conn -> {
            UUID orgUuid = wireToUuid(orgId);
            MemRowDirect from = lockMem(conn, fromMemId);
            MemRowDirect to = lockMem(conn, toMemId);
            if (!"active".equals(from.status)) {
                throw new PreconditionError("From membership is " + from.status, "from_not_active");
            }
            if (!"active".equals(to.status)) {
                throw new PreconditionError("To membership is " + to.status, "to_not_active");
            }
            if (!from.orgId.equals(orgUuid) || !to.orgId.equals(orgUuid)) {
                throw new PreconditionError(
                        "Both memberships must belong to " + orgId, "org_mismatch"
                );
            }
            if (!"owner".equals(from.role)) {
                throw new PreconditionError("From membership must hold the owner role", "from_not_owner");
            }
            if (from.usrId.equals(to.usrId)) {
                throw new PreconditionError("Cannot transfer ownership to self", "self_transfer");
            }
            Membership toMembership = rotateMembership(conn, to, Role.OWNER, null);
            Membership fromMembership = rotateMembership(conn, from, Role.MEMBER, null);
            return new TransferOwnershipResult(fromMembership, toMembership);
        });
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
        Organization org = getOrg(orgId);
        if (org.status() != Status.ACTIVE) {
            throw new PreconditionError(
                    "Cannot create invitation for " + org.status().name().toLowerCase() + " org",
                    "org_not_active"
            );
        }
        Instant now = now();
        if (!expiresAt.isAfter(now)) {
            throw new PreconditionError("expiresAt must be in the future", "past_expiration");
        }
        UUID invUuid = UUID.fromString(Id.decode(Id.generate("inv")).uuid());
        String preJson = preTuplesToJson(preTuples != null ? preTuples : List.of());
        return nested(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO inv (id, org_id, identifier, role, status, pre_tuples, invited_by, created_at, expires_at)"
                  + " VALUES (?, ?, ?, ?, 'pending', ?::jsonb, ?, ?, ?) RETURNING " + INV_COLS)) {
                ps.setObject(1, invUuid);
                ps.setObject(2, wireToUuid(orgId));
                ps.setString(3, identifier);
                ps.setString(4, role.getValue());
                ps.setString(5, preJson);
                ps.setObject(6, wireToUuid(invitedBy));
                ps.setTimestamp(7, Timestamp.from(now));
                ps.setTimestamp(8, Timestamp.from(expiresAt));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rowToInv(rs);
                }
            }
        });
    }

    public Invitation createInvitation(
            String orgId, String identifier, Role role, String invitedBy, Instant expiresAt
    ) {
        return createInvitation(orgId, identifier, role, invitedBy, expiresAt, List.of());
    }

    public Invitation getInvitation(String invId) {
        try (Connection conn = acquireConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + INV_COLS + " FROM inv WHERE id = ?")) {
            ps.setObject(1, wireToUuid(invId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new NotFoundError("Invitation " + invId + " not found");
                return rowToInv(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public AcceptInvitationResult acceptInvitation(
            String invId, String asUsrId, String acceptingIdentifier
    ) {
        return tx(conn -> {
            UUID invUuid = wireToUuid(invId);
            Invitation inv;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + INV_COLS + " FROM inv WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, invUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new NotFoundError("Invitation " + invId + " not found");
                    inv = rowToInv(rs);
                }
            }
            if (inv.status() != InvitationStatus.PENDING) {
                throw new InvitationNotPendingError(
                        "Invitation " + invId + " is " + inv.status().name().toLowerCase() + ", not pending"
                );
            }
            Instant now = now();
            if (now.isAfter(inv.expiresAt())) {
                throw new InvitationExpiredError(
                        "Invitation " + invId + " expired at " + inv.expiresAt()
                );
            }
            if (asUsrId != null) {
                if (acceptingIdentifier == null) throw new IdentifierBindingRequiredError();
                if (!acceptingIdentifier.equals(inv.identifier())) {
                    throw new IdentifierMismatchError(acceptingIdentifier, inv.identifier());
                }
            }
            UUID usrUuid = asUsrId != null
                    ? wireToUuid(asUsrId)
                    : UUID.fromString(Id.decode(Id.generate("usr")).uuid());
            UUID orgUuid = wireToUuid(inv.orgId());
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM mem WHERE usr_id = ? AND org_id = ? AND status = 'active'")) {
                ps.setObject(1, usrUuid);
                ps.setObject(2, orgUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        throw new DuplicateMembershipError(
                                "User already has an active membership in this org"
                        );
                    }
                }
            }
            UUID memUuid = UUID.fromString(Id.decode(Id.generate("mem")).uuid());
            UUID tupUuid = UUID.fromString(Id.decode(Id.generate("tup")).uuid());
            UUID invitedByUuid = wireToUuid(inv.invitedBy());
            Timestamp ts = Timestamp.from(now);
            Membership membership;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO mem (id, usr_id, org_id, role, status, invited_by, created_at, updated_at)"
                  + " VALUES (?, ?, ?, ?, 'active', ?, ?, ?) RETURNING " + MEM_COLS)) {
                ps.setObject(1, memUuid);
                ps.setObject(2, usrUuid);
                ps.setObject(3, orgUuid);
                ps.setString(4, inv.role().getValue());
                ps.setObject(5, invitedByUuid);
                ps.setTimestamp(6, ts);
                ps.setTimestamp(7, ts);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    membership = rowToMem(rs);
                }
            }
            insertMembershipTuple(conn, tupUuid, usrUuid, inv.role().getValue(), orgUuid, ts, null);
            List<Tuple> materialized = new ArrayList<>();
            for (PreTuple pt : inv.preTuples()) {
                UUID ptTup = UUID.fromString(Id.decode(Id.generate("tup")).uuid());
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO tup (id, subject_type, subject_id, relation, object_type, object_id, created_at)"
                      + " VALUES (?, 'usr', ?, ?, ?, ?, ?)")) {
                    ps.setObject(1, ptTup);
                    ps.setObject(2, usrUuid);
                    ps.setString(3, pt.relation());
                    ps.setString(4, pt.objectType());
                    ps.setObject(5, objectIdToUuid(pt.objectId()));
                    ps.setTimestamp(6, ts);
                    ps.executeUpdate();
                }
                materialized.add(new Tuple(
                        "usr",
                        Id.encode("usr", usrUuid.toString()),
                        pt.relation(),
                        pt.objectType(),
                        pt.objectId()
                ));
            }
            Invitation updated;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE inv SET status = 'accepted', terminal_at = ?, terminal_by = ?, invited_user_id = ?"
                  + " WHERE id = ? RETURNING " + INV_COLS)) {
                ps.setTimestamp(1, ts);
                ps.setObject(2, usrUuid);
                ps.setObject(3, usrUuid);
                ps.setObject(4, invUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    updated = rowToInv(rs);
                }
            }
            return new AcceptInvitationResult(updated, membership, List.copyOf(materialized));
        });
    }

    public AcceptInvitationResult acceptInvitation(String invId) {
        return acceptInvitation(invId, null, null);
    }

    public AcceptInvitationResult acceptInvitation(String invId, String asUsrId) {
        // Mint-new-user form is only valid when asUsrId is null; otherwise
        // ADR 0009 demands accepting_identifier.
        return acceptInvitation(invId, asUsrId, null);
    }

    public Invitation declineInvitation(String invId, String asUsrId) {
        return tx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, status FROM inv WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, wireToUuid(invId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new NotFoundError("Invitation " + invId + " not found");
                    if (!"pending".equals(rs.getString("status"))) {
                        throw new InvitationNotPendingError(
                                "Invitation " + invId + " is " + rs.getString("status")
                        );
                    }
                }
            }
            UUID by = asUsrId != null ? wireToUuid(asUsrId) : null;
            Timestamp ts = Timestamp.from(now());
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE inv SET status = 'declined', terminal_at = ?, terminal_by = ?"
                  + " WHERE id = ? RETURNING " + INV_COLS)) {
                ps.setTimestamp(1, ts);
                ps.setObject(2, by);
                ps.setObject(3, wireToUuid(invId));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rowToInv(rs);
                }
            }
        });
    }

    public Invitation revokeInvitation(String invId, String adminUsrId) {
        return tx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, status FROM inv WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, wireToUuid(invId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new NotFoundError("Invitation " + invId + " not found");
                    if (!"pending".equals(rs.getString("status"))) {
                        throw new InvitationNotPendingError(
                                "Invitation " + invId + " is " + rs.getString("status")
                        );
                    }
                }
            }
            Timestamp ts = Timestamp.from(now());
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE inv SET status = 'revoked', terminal_at = ?, terminal_by = ?"
                  + " WHERE id = ? RETURNING " + INV_COLS)) {
                ps.setTimestamp(1, ts);
                ps.setObject(2, wireToUuid(adminUsrId));
                ps.setObject(3, wireToUuid(invId));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rowToInv(rs);
                }
            }
        });
    }

    // ─── Tuple accessors ───

    public List<Tuple> listTuplesForSubject(String subjectType, String subjectId) {
        try (Connection conn = acquireConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + TUP_COLS + " FROM tup WHERE subject_type = ? AND subject_id = ?")) {
            ps.setString(1, subjectType);
            ps.setObject(2, wireToUuid(subjectId));
            try (ResultSet rs = ps.executeQuery()) {
                List<Tuple> rows = new ArrayList<>();
                while (rs.next()) rows.add(rowToTup(rs));
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Tuple> listTuplesForObject(String objectType, String objectId, String relation) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + TUP_COLS + " FROM tup WHERE object_type = ? AND object_id = ?"
        );
        if (relation != null) sql.append(" AND relation = ?");
        try (Connection conn = acquireConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, objectType);
            ps.setObject(2, objectIdToUuid(objectId));
            if (relation != null) ps.setString(3, relation);
            try (ResultSet rs = ps.executeQuery()) {
                List<Tuple> rows = new ArrayList<>();
                while (rs.next()) rows.add(rowToTup(rs));
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
