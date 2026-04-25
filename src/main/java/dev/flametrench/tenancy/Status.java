// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

/** Lifecycle status shared by organizations and memberships. */
public enum Status {
    ACTIVE("active"),
    SUSPENDED("suspended"),
    REVOKED("revoked");

    private final String value;

    Status(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
