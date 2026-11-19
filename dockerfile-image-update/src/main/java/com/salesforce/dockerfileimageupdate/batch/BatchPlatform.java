/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

/**
 * The platform a batch run operates against: the set of repository/image pairs that currently
 * exist, plus the ability to update one of them.
 *
 * <p>The interface is deliberately narrow so that a hosted platform, an in-memory test double,
 * and any future adapter are interchangeable. Implementations must make
 * {@link #applyUpdate(BatchTarget)} idempotent: applying the same update twice must be safe and
 * must not create a second merge request. That guarantee is what makes running two batch
 * instances concurrently at worst duplicated work.</p>
 */
public interface BatchPlatform {
    /**
     * Discover the current targets. The order is not significant; the batch engine imposes its
     * own fair order. The same logical repository/image pair must not be returned twice; if it
     * is, the engine de-duplicates by canonical names.
     *
     * @return all known targets
     * @throws PlatformException if discovery fails
     */
    Iterable<BatchTarget> discoverTargets() throws PlatformException;

    /**
     * Apply the desired update. Must be idempotent.
     *
     * @param target target to update
     * @return the outcome of the write
     * @throws PlatformException if the platform reports a failure
     */
    UpdateOutcome applyUpdate(BatchTarget target) throws PlatformException;

    /**
     * Decide whether the target actually needs a write, without writing anything. This runs
     * before the quota is consulted so that repositories that are already current never consume
     * (or appear to need) quota.
     *
     * <p>The default implementation trusts {@link BatchTarget#isUpToDate()}; implementations
     * that can cheaply verify the live state should override it.</p>
     *
     * @param target target to inspect
     * @return whether a write would change the repository
     * @throws PlatformException if the check itself fails
     */
    default boolean needsUpdate(BatchTarget target) throws PlatformException {
        return !target.isUpToDate();
    }

    /**
     * Result of a write.
     */
    enum UpdateOutcome {
        /** The repository was changed and a merge request was opened. Counts against the quota. */
        UPDATED,
        /** The repository already had the desired state; nothing was written. Does not count. */
        ALREADY_UP_TO_DATE
    }

    /**
     * A platform failure, surfaced as a {@code FAILED} row rather than aborting the run.
     */
    class PlatformException extends Exception {
        public PlatformException(String message) {
            super(message);
        }

        public PlatformException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
