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
 * One repository/image pair the batch engine may update.
 */
public final class BatchTarget implements Comparable<BatchTarget> {
    private final RepositoryName repository;
    private final ImageName image;
    private final String currentTag;
    private final String desiredTag;

    /**
     * @param repository repository that references the image
     * @param image image referenced by the repository
     * @param currentTag tag currently referenced, may be {@code null} when unknown
     * @param desiredTag tag that should be referenced
     */
    public BatchTarget(RepositoryName repository, ImageName image, String currentTag, String desiredTag) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.image = Objects.requireNonNull(image, "image");
        this.currentTag = currentTag;
        this.desiredTag = desiredTag;
    }

    public RepositoryName repository() {
        return repository;
    }

    public ImageName image() {
        return image;
    }

    public Optional<String> currentTag() {
        return Optional.ofNullable(currentTag);
    }

    public Optional<String> desiredTag() {
        return Optional.ofNullable(desiredTag);
    }

    /**
     * @return whether the referenced tag already equals the desired tag and no update is needed
     */
    public boolean isUpToDate() {
        return desiredTag == null || desiredTag.equals(currentTag);
    }

    /**
     * @return stable key used for de-duplication and state bookkeeping
     */
    public String key() {
        return repository.canonical() + "|" + image.canonical();
    }

    @Override
    public int compareTo(BatchTarget other) {
        int byRepository = repository.compareTo(other.repository);
        return byRepository != 0 ? byRepository : image.compareTo(other.image);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BatchTarget)) {
            return false;
        }
        BatchTarget that = (BatchTarget) o;
        return key().equals(that.key());
    }

    @Override
    public int hashCode() {
        return Objects.hash(key());
    }

    @Override
    public String toString() {
        return repository.canonical() + " " + image.canonical()
                + (currentTag == null ? "" : ":" + currentTag)
                + " -> " + (desiredTag == null ? "?" : desiredTag);
    }
}
