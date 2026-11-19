/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

import java.util.Locale;
import java.util.Objects;

/**
 * A normalized repository name in {@code owner/name} form.
 *
 * <p>Hosted platforms such as GitHub treat repository names case-insensitively. Both components
 * are therefore lower-cased, so {@code Acme/My-App} and {@code acme/my-app} are the same
 * repository, and an ignore rule written in any casing applies to both.</p>
 */
public final class RepositoryName implements Comparable<RepositoryName> {
    private final String owner;
    private final String name;
    private final String canonical;

    private RepositoryName(String owner, String name) {
        this.owner = owner;
        this.name = name;
        this.canonical = owner + "/" + name;
    }

    /**
     * @param raw repository name in {@code owner/name} form
     * @return normalized repository name
     * @throws IllegalArgumentException if the value is not a two-segment repository name
     */
    public static RepositoryName parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Repository name must not be null");
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Repository name must not be empty");
        }
        String[] parts = value.split("/");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException(
                    "Repository name must be in owner/name form: " + raw);
        }
        return new RepositoryName(parts[0].toLowerCase(Locale.ROOT), parts[1].toLowerCase(Locale.ROOT));
    }

    public String canonical() {
        return canonical;
    }

    public String owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    @Override
    public int compareTo(RepositoryName other) {
        return canonical.compareTo(other.canonical);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RepositoryName)) {
            return false;
        }
        return canonical.equals(((RepositoryName) o).canonical);
    }

    @Override
    public int hashCode() {
        return Objects.hash(canonical);
    }

    @Override
    public String toString() {
        return canonical;
    }
}
