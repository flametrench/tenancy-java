// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

public record TransferOwnershipResult(
        Membership fromMembership,
        Membership toMembership
) {
}
