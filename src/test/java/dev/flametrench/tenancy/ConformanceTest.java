// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flametrench.ids.Id;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Flametrench v0.1 conformance suite — Java / JUnit 5 harness for the
 * tenancy capability.
 *
 * <p>Implements the state-machine fixture format defined in
 * spec/conformance/fixture.schema.json. Each test:
 *
 * <ol>
 *   <li>Pre-allocates fresh usr_ IDs for declared named users.</li>
 *   <li>Creates a fresh InMemoryTenancyStore.</li>
 *   <li>Walks the steps list. Each step's input has {name} references
 *       resolved against the variable map. Captures may extract values
 *       for later substitution; expected error stops the test.</li>
 * </ol>
 *
 * <p>Pseudo-ops {@code assert_subject_relations} and {@code assert_equal}
 * handle final-state checks via JUnit assertions.
 *
 * <p>Mirrors the canonical Python reference implementation in
 * tenancy-python with snake_case → camelCase adaptation.
 */
class ConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern VAR_PATTERN = Pattern.compile("^\\{([a-z_][a-z0-9_]*)\\}$");

    private static final Map<String, Class<? extends RuntimeException>> ERROR_CLASSES = Map.ofEntries(
            Map.entry("SoleOwnerError", SoleOwnerError.class),
            Map.entry("RoleHierarchyError", RoleHierarchyError.class),
            Map.entry("ForbiddenError", ForbiddenError.class),
            Map.entry("DuplicateMembershipError", DuplicateMembershipError.class),
            Map.entry("InvitationNotPendingError", InvitationNotPendingError.class),
            Map.entry("InvitationExpiredError", InvitationExpiredError.class),
            Map.entry("PreconditionError", PreconditionError.class),
            Map.entry("AlreadyTerminalError", AlreadyTerminalError.class),
            Map.entry("NotFoundError", NotFoundError.class),
            Map.entry("IdentifierBindingRequiredError", IdentifierBindingRequiredError.class),
            Map.entry("IdentifierMismatchError", IdentifierMismatchError.class),
            Map.entry("OrgSlugConflictError", OrgSlugConflictError.class)
    );

    private static JsonNode loadFixture(String relativePath) throws IOException {
        String resource = "/conformance/fixtures/" + relativePath;
        try (InputStream in = ConformanceTest.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Fixture not found: " + resource);
            return MAPPER.readTree(in);
        }
    }

    /** Recursively substitute {var} references in a value tree. */
    private static Object resolveVars(Object value, Map<String, Object> variables) {
        if (value instanceof String s) {
            Matcher m = VAR_PATTERN.matcher(s);
            if (m.matches()) {
                String name = m.group(1);
                if (!variables.containsKey(name)) {
                    throw new IllegalStateException("Unknown variable in fixture: {" + name + "}");
                }
                return variables.get(name);
            }
            return s;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(resolveVars(item, variables));
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put((String) e.getKey(), resolveVars(e.getValue(), variables));
            }
            return out;
        }
        return value;
    }

    /** Convert a JsonNode tree to plain Java values (Map/List/String/Number/Boolean). */
    @SuppressWarnings("unchecked")
    private static Object jsonToPlain(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isInt() || node.isLong()) return node.asLong();
        if (node.isDouble() || node.isFloat()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonNode child : node) out.add(jsonToPlain(child));
            return out;
        }
        if (node.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                out.put(e.getKey(), jsonToPlain(e.getValue()));
            }
            return out;
        }
        throw new IllegalStateException("Unhandled JSON node type: " + node.getNodeType());
    }

    /** snake_case → camelCase. */
    private static String toCamel(String segment) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    /**
     * Walk a dotted path into a value. Tries snake_case as-is first,
     * then camelCase, so fixture paths like "owner_membership.id"
     * resolve against the Java field "ownerMembership".
     */
    private static Object walkPath(Object obj, String dottedPath) {
        Object current = obj;
        for (String segment : dottedPath.split("\\.")) {
            String camel = toCamel(segment);
            if (current instanceof Map<?, ?> map) {
                if (map.containsKey(segment)) current = map.get(segment);
                else if (map.containsKey(camel)) current = map.get(camel);
                else throw new IllegalStateException(
                        "Cannot resolve path segment '" + segment + "' in map");
            } else if (current != null) {
                // Try record accessor methods (Java records expose fields as methods).
                Object val = invokeAccessor(current, segment);
                if (val == null) val = invokeAccessor(current, camel);
                if (val == null) {
                    throw new IllegalStateException(
                            "Cannot resolve path segment '" + segment + "' on "
                                    + current.getClass().getSimpleName());
                }
                current = val;
            } else {
                throw new IllegalStateException("Cannot walk into null at '" + segment + "'");
            }
        }
        return current;
    }

    private static Object invokeAccessor(Object target, String fieldName) {
        try {
            return target.getClass().getMethod(fieldName).invoke(target);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<PreTuple> buildPreTuples(Object raw) {
        if (raw == null) return List.of();
        List<Map<String, Object>> values = (List<Map<String, Object>>) raw;
        List<PreTuple> out = new ArrayList<>(values.size());
        for (Map<String, Object> v : values) {
            out.add(new PreTuple(
                    (String) v.get("relation"),
                    (String) v.get("object_type"),
                    (String) v.get("object_id")
            ));
        }
        return out;
    }

    private static Role role(Object value) {
        return Role.valueOf(((String) value).toUpperCase());
    }

    @SuppressWarnings("unchecked")
    private static Object invokeOp(
            InMemoryTenancyStore store, String op, Map<String, Object> args
    ) {
        return switch (op) {
            case "create_org" -> store.createOrg(
                    (String) args.get("creator"),
                    args.containsKey("name") ? (String) args.get("name") : null,
                    args.containsKey("slug") ? (String) args.get("slug") : null
            );

            case "update_org" -> store.updateOrg(
                    (String) args.get("org_id"),
                    args.containsKey("name") ? (String) args.get("name") : InMemoryTenancyStore.UNSET,
                    args.containsKey("slug") ? (String) args.get("slug") : InMemoryTenancyStore.UNSET
            );

            case "assert_org_fields" -> {
                Organization org = store.getOrg((String) args.get("org_id"));
                if (args.containsKey("expected_name")) {
                    assertEquals(args.get("expected_name"), org.name());
                }
                if (args.containsKey("expected_slug")) {
                    assertEquals(args.get("expected_slug"), org.slug());
                }
                yield null;
            }
            case "add_member" -> store.addMember(
                    (String) args.get("org_id"),
                    (String) args.get("usr_id"),
                    role(args.get("role")),
                    (String) args.get("invited_by")
            );
            case "change_role" -> store.changeRole(
                    (String) args.get("mem_id"),
                    role(args.get("new_role"))
            );
            case "suspend_membership" -> store.suspendMembership((String) args.get("mem_id"));
            case "reinstate_membership" -> store.reinstateMembership((String) args.get("mem_id"));
            case "self_leave" -> store.selfLeave(
                    (String) args.get("mem_id"),
                    (String) args.get("transfer_to")
            );
            case "admin_remove" -> store.adminRemove(
                    (String) args.get("mem_id"),
                    (String) args.get("admin_usr_id")
            );
            case "transfer_ownership" -> store.transferOwnership(
                    (String) args.get("org_id"),
                    (String) args.get("from_mem_id"),
                    (String) args.get("to_mem_id")
            );
            case "create_invitation" -> {
                long ttl = args.containsKey("ttl_seconds")
                        ? ((Number) args.get("ttl_seconds")).longValue()
                        : 86400L;
                yield store.createInvitation(
                        (String) args.get("org_id"),
                        (String) args.get("identifier"),
                        role(args.get("role")),
                        (String) args.get("invited_by"),
                        Instant.now().plus(ttl, ChronoUnit.SECONDS),
                        buildPreTuples(args.get("pre_tuples"))
                );
            }
            case "accept_invitation" -> store.acceptInvitation(
                    (String) args.get("inv_id"),
                    (String) args.get("as_usr_id"),
                    (String) args.get("accepting_identifier")
            );
            case "decline_invitation" -> store.declineInvitation(
                    (String) args.get("inv_id"),
                    (String) args.get("as_usr_id")
            );
            case "revoke_invitation" -> store.revokeInvitation(
                    (String) args.get("inv_id"),
                    (String) args.get("admin_usr_id")
            );
            case "suspend_org" -> store.suspendOrg((String) args.get("org_id"));
            case "reinstate_org" -> store.reinstateOrg((String) args.get("org_id"));
            case "revoke_org" -> store.revokeOrg((String) args.get("org_id"));

            // Harness-only assertion pseudo-ops.
            case "assert_subject_relations" -> {
                List<Tuple> tuples = store.listTuplesForSubject(
                        (String) args.get("subject_type"),
                        (String) args.get("subject_id")
                );
                List<String> actual = new ArrayList<>();
                for (Tuple t : tuples) actual.add(t.relation());
                Collections.sort(actual);
                List<String> expected = new ArrayList<>((List<String>) args.get("relations"));
                Collections.sort(expected);
                assertEquals(expected, actual,
                        "assert_subject_relations: expected " + expected + ", got " + actual);
                yield null;
            }
            case "assert_equal" -> {
                assertEquals(args.get("expected"), args.get("actual"));
                yield null;
            }
            case "assert_invitation_status" -> {
                Invitation inv = store.getInvitation((String) args.get("inv_id"));
                assertEquals(args.get("expected_status"), inv.status().getValue());
                yield null;
            }
            default -> throw new IllegalStateException("Unknown fixture op: " + op);
        };
    }

    @SuppressWarnings("unchecked")
    private static void runTest(JsonNode test) {
        InMemoryTenancyStore store = new InMemoryTenancyStore();
        Map<String, Object> variables = new HashMap<>();

        if (test.has("users")) {
            for (JsonNode userName : test.get("users")) {
                variables.put(userName.asText(), Id.generate("usr"));
            }
        }

        for (JsonNode step : test.get("steps")) {
            String op = step.get("op").asText();
            Map<String, Object> resolvedInput = (Map<String, Object>) resolveVars(
                    jsonToPlain(step.get("input")), variables);

            JsonNode expected = step.get("expected");
            if (expected != null && expected.has("error")) {
                String errorName = expected.get("error").asText();
                Class<? extends RuntimeException> ctor = ERROR_CLASSES.get(errorName);
                if (ctor == null) throw new IllegalStateException("Unknown spec error: " + errorName);
                assertThrows(ctor, () -> invokeOp(store, op, resolvedInput));
                return;
            }

            Object result = invokeOp(store, op, resolvedInput);

            JsonNode captures = step.get("captures");
            if (captures != null) {
                Iterator<Map.Entry<String, JsonNode>> it = captures.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    variables.put(e.getKey(), walkPath(result, e.getValue().asText()));
                }
            }
        }

        // "Completed without error" sentinel.
        assertTrue(true);
    }

    private List<DynamicTest> conformanceTests(String fixturePath) throws IOException {
        JsonNode fixture = loadFixture(fixturePath);
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode t : fixture.get("tests")) {
            String id = t.get("id").asText();
            String desc = t.get("description").asText();
            tests.add(DynamicTest.dynamicTest("[" + id + "] " + desc, () -> runTest(t)));
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> selfLeave() throws IOException {
        return conformanceTests("tenancy/self-leave.json");
    }

    @TestFactory
    List<DynamicTest> changeRole() throws IOException {
        return conformanceTests("tenancy/change-role.json");
    }

    @TestFactory
    List<DynamicTest> transferOwnership() throws IOException {
        return conformanceTests("tenancy/transfer-ownership.json");
    }

    @TestFactory
    List<DynamicTest> adminRemove() throws IOException {
        return conformanceTests("tenancy/admin-remove.json");
    }

    @TestFactory
    List<DynamicTest> acceptInvitation() throws IOException {
        return conformanceTests("tenancy/invitation-accept.json");
    }

    @TestFactory
    List<DynamicTest> acceptInvitationBinding() throws IOException {
        return conformanceTests("tenancy/invitation-accept-binding.json");
    }

    @TestFactory
    List<DynamicTest> orgNameSlug() throws IOException {
        return conformanceTests("tenancy/org-name-slug.json");
    }
}
