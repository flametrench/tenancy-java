// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

/**
 * The supplied org slug is already in use by another active org.
 *
 * <p>Per ADR 0011, slugs are globally unique within a deployment when
 * set. Revoked orgs free their slug; null slugs are not unique-constrained.
 */
public class OrgSlugConflictError extends TenancyError {
    private final String slug;

    public OrgSlugConflictError(String slug) {
        super("Org slug '" + slug + "' is already in use", "conflict.org_slug");
        this.slug = slug;
    }

    public String getSlug() {
        return slug;
    }
}
