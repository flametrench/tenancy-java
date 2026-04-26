// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

/**
 * The supplied {@code acceptingIdentifier} does not match
 * {@code invitation.identifier}.
 *
 * <p>Per ADR 0009, this byte-equality check is the SDK's contribution
 * to closing the privilege-escalation primitive in spec#5: an attacker
 * substituting a foreign {@code usr_id} will fail to also produce a
 * matching identifier sourced from the authenticated session.
 */
public class IdentifierMismatchError extends PreconditionError {
    private final String acceptingIdentifier;
    private final String invitationIdentifier;

    public IdentifierMismatchError(String acceptingIdentifier, String invitationIdentifier) {
        super(
                "acceptingIdentifier '" + acceptingIdentifier
                        + "' does not match invitation.identifier '"
                        + invitationIdentifier + "'",
                "identifier_mismatch"
        );
        this.acceptingIdentifier = acceptingIdentifier;
        this.invitationIdentifier = invitationIdentifier;
    }

    public String getAcceptingIdentifier() {
        return acceptingIdentifier;
    }

    public String getInvitationIdentifier() {
        return invitationIdentifier;
    }
}
