// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

/**
 * The five-state invitation lifecycle. {@code PENDING} is the only
 * non-terminal state; the other four are terminal and immutable once
 * entered.
 */
public enum InvitationStatus {
    PENDING("pending"),
    ACCEPTED("accepted"),
    DECLINED("declined"),
    REVOKED("revoked"),
    EXPIRED("expired");

    private final String value;

    InvitationStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
