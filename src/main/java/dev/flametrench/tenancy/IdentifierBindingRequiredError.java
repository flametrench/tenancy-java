// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

/**
 * `acceptInvitation` was called with `asUsrId` but no `acceptingIdentifier`.
 *
 * <p>Per ADR 0009, the SDK fails closed: callers MUST supply
 * {@code acceptingIdentifier} whenever they assert an existing
 * {@code asUsrId}. The mint-new-user path ({@code asUsrId == null})
 * does not need this parameter.
 */
public class IdentifierBindingRequiredError extends PreconditionError {
    public IdentifierBindingRequiredError() {
        super(
                "acceptInvitation requires acceptingIdentifier when asUsrId is provided",
                "identifier_binding_required"
        );
    }

    public IdentifierBindingRequiredError(String message) {
        super(message, "identifier_binding_required");
    }
}
