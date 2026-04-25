// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

/**
 * The six built-in relations registered in Flametrench v0.1.
 *
 * <p>Applications MAY register custom relation names (matching
 * {@code ^[a-z_]{2,32}$}) for their own domain objects, but membership
 * roles MUST be drawn from this enum so cross-SDK tenancy semantics
 * stay byte-identical.
 */
public enum Role {
    OWNER("owner"),
    ADMIN("admin"),
    MEMBER("member"),
    GUEST("guest"),
    VIEWER("viewer"),
    EDITOR("editor");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * The admin-hierarchy ranking used by the {@code adminRemove}
     * precondition. Higher rank removes lower or equal rank.
     * Viewer/editor are object-scoped and do not participate; they
     * return null.
     */
    public Integer adminRank() {
        return switch (this) {
            case OWNER -> 4;
            case ADMIN -> 3;
            case MEMBER -> 2;
            case GUEST -> 1;
            default -> null;
        };
    }
}
