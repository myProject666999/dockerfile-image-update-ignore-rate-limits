/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link BatchPlatform} for tests.
 *
 * <p>Holds a fixed set of targets, records every write, and lets a test declare that a
 * repository always fails so that quota and failure behaviour can be verified deterministically.
 * Writes are idempotent: once a repository/image pair has been written it reports
 * {@code ALREADY_UP_TO_DATE} on subsequent attempts, which mirrors the real platform.</p>
 */
public final class InMemoryPlatform implements BatchPlatform {
    private final List<BatchTarget> targets = new ArrayList<>();
    private final Set<String> written = new LinkedHashSet<>();
    private final Set<String> alwaysFailing = new LinkedHashSet<>();
    private final Map<String, Integer> writeAttempts = new LinkedHashMap<>();
    private int discoverCalls;

    /**
     * @param repository repository name
     * @param image image reference (tags are allowed and ignored for comparison)
     * @param currentTag tag currently in the repository
     * @param desiredTag tag that should be applied
     * @return this platform
     */
    public InMemoryPlatform add(String repository, String image, String currentTag, String desiredTag) {
        targets.add(new BatchTarget(RepositoryName.parse(repository), ImageName.parse(image),
                currentTag, desiredTag));
        return this;
    }

    /**
     * @param repository repository that should fail on every write
     * @return this platform
     */
    public InMemoryPlatform failing(String repository) {
        alwaysFailing.add(RepositoryName.parse(repository).canonical());
        return this;
    }

    @Override
    public Iterable<BatchTarget> discoverTargets() {
        discoverCalls++;
        return new ArrayList<>(targets);
    }

    @Override
    public boolean needsUpdate(BatchTarget target) {
        // Whether the update is needed is a property of the target alone; a perpetually failing
        // repository still needs the write, it just fails when the write is attempted.
        return !target.isUpToDate();
    }

    @Override
    public UpdateOutcome applyUpdate(BatchTarget target) throws PlatformException {
        String repository = target.repository().canonical();
        writeAttempts.merge(target.key(), 1, Integer::sum);
        if (alwaysFailing.contains(repository)) {
            throw new PlatformException("simulated failure for " + repository);
        }
        if (written.contains(target.key())) {
            return UpdateOutcome.ALREADY_UP_TO_DATE;
        }
        written.add(target.key());
        return UpdateOutcome.UPDATED;
    }

    /**
     * @return repository names that were successfully written, in write order
     */
    public List<String> writtenRepositories() {
        List<String> repositories = new ArrayList<>();
        for (String key : written) {
            repositories.add(key.substring(0, key.indexOf('|')));
        }
        return repositories;
    }

    /**
     * @return keys of all successful writes
     */
    public Set<String> writtenKeys() {
        return new LinkedHashSet<>(written);
    }

    /**
     * @param key target key
     * @return how many times a write was attempted for the target
     */
    public int attempts(String key) {
        return writeAttempts.getOrDefault(key, 0);
    }

    /**
     * @return how many times discovery was invoked
     */
    public int discoverCalls() {
        return discoverCalls;
    }
}
