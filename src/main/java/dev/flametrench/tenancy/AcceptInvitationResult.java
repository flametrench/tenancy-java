// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

import java.util.List;

public record AcceptInvitationResult(
        Invitation invitation,
        Membership membership,
        List<Tuple> materializedTuples
) {
}
