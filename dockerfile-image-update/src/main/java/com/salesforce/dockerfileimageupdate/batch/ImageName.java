/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

import java.util.Objects;
import java.util.Optional;

/**
 * A normalized Docker image reference.
 *
 * <p>Docker treats many spellings as the same image. {@code openjdk},
 * {@code docker.io/library/openjdk} and {@code index.docker.io/library/openjdk} all name the
 * same image. Ignore rules are matched against the normalized form so that a rule written one
 * way still protects an image written another way.</p>
 *
 * <p>Normalization applied by {@link #parse(String)}:</p>
 * <ul>
 *     <li>the optional digest ({@code @sha256:...}) is dropped,</li>
 *     <li>the tag, when present, is split off and kept separately,</li>
 *     <li>the registry is lower-cased and {@code index.docker.io} / {@code registry-1.docker.io}
 *         are rewritten to {@code docker.io},</li>
 *     <li>a missing registry becomes {@code docker.io},</li>
 *     <li>a single-segment repository on Docker Hub gains the {@code library/} namespace,</li>
 *     <li>the repository path is lower-cased (Docker repository names are lower-case).</li>
 * </ul>
 *
 * <p>Equality and hashing are based on the canonical name only: tags are metadata used by the
 * batch engine but do not make two references different images.</p>
 */
public final class ImageName implements Comparable<ImageName> {
    public static final String DEFAULT_REGISTRY = "docker.io";
    private static final String DEFAULT_NAMESPACE = "library";

    private final String registry;
    private final String repositoryPath;
    private final String canonical;
    private final String tag;

    private ImageName(String registry, String repositoryPath, String tag) {
        this.registry = registry;
        this.repositoryPath = repositoryPath;
        this.canonical = registry + "/" + repositoryPath;
        this.tag = tag;
    }

    /**
     * Parse and normalize a Docker image reference. Tags are allowed and preserved in
     * {@link #tag()}; they never take part in comparison.
     *
     * @param raw raw image reference, e.g. {@code index.docker.io/library/openjdk:17-jdk}
     * @return normalized image name
     * @throws IllegalArgumentException if the reference is empty or malformed
     */
    public static ImageName parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Image name must not be null");
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Image name must not be empty");
        }

        int digest = value.indexOf('@');
        if (digest >= 0) {
            value = value.substring(0, digest);
        }

        String tag = null;
        int lastSlash = value.lastIndexOf('/');
        int lastColon = value.lastIndexOf(':');
        if (lastColon > lastSlash) {
            tag = value.substring(lastColon + 1);
            value = value.substring(0, lastColon);
            if (tag.isEmpty()) {
                throw new IllegalArgumentException("Image reference has an empty tag: " + raw);
            }
        }

        if (value.isEmpty()) {
            throw new IllegalArgumentException("Image reference has no repository: " + raw);
        }
        if (value.startsWith("/") || value.endsWith("/") || value.contains("//")) {
            throw new IllegalArgumentException("Malformed image reference: " + raw);
        }

        String[] components = value.split("/");
        String registry;
        String path;
        if (components.length > 1 && looksLikeRegistry(components[0])) {
            registry = normalizeRegistry(components[0]);
            path = String.join("/", java.util.Arrays.copyOfRange(components, 1, components.length));
        } else {
            registry = DEFAULT_REGISTRY;
            path = value;
        }

        path = path.toLowerCase(java.util.Locale.ROOT);
        if (DEFAULT_REGISTRY.equals(registry) && !path.contains("/")) {
            path = DEFAULT_NAMESPACE + "/" + path;
        }
        return new ImageName(registry, path, tag);
    }

    private static boolean looksLikeRegistry(String component) {
        return component.contains(".")
                || component.contains(":")
                || "localhost".equalsIgnoreCase(component);
    }

    private static String normalizeRegistry(String registry) {
        String normalized = registry.toLowerCase(java.util.Locale.ROOT);
        if ("index.docker.io".equals(normalized) || "registry-1.docker.io".equals(normalized)) {
            return DEFAULT_REGISTRY;
        }
        return normalized;
    }

    /**
     * @return fully qualified, normalized name ({@code registry/repository-path})
     */
    public String canonical() {
        return canonical;
    }

    /**
     * @return repository path without the registry, e.g. {@code library/openjdk}
     */
    public String repositoryPath() {
        return repositoryPath;
    }

    /**
     * @return last path segment, e.g. {@code openjdk}
     */
    public String shortName() {
        int slash = repositoryPath.lastIndexOf('/');
        return slash < 0 ? repositoryPath : repositoryPath.substring(slash + 1);
    }

    /**
     * @return registry component, e.g. {@code docker.io} or {@code quay.io}
     */
    public String registry() {
        return registry;
    }

    /**
     * @return the tag that was present in the original reference, or empty
     */
    public Optional<String> tag() {
        return Optional.ofNullable(tag);
    }

    @Override
    public int compareTo(ImageName other) {
        return canonical.compareTo(other.canonical);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ImageName)) {
            return false;
        }
        ImageName imageName = (ImageName) o;
        return canonical.equals(imageName.canonical);
    }

    @Override
    public int hashCode() {
        return Objects.hash(canonical);
    }

    @Override
    public String toString() {
        return tag == null ? canonical : canonical + ":" + tag;
    }
}
