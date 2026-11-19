/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

import java.time.Duration;
import java.time.Instant;

/**
 * The update quota for one run.
 *
 * <p>Quota is counted per <em>repository</em>: a repository that references several images and
 * is updated in several places consumes a single slot. Two independent limits can apply:</p>
 * <ul>
 *     <li>a per-run cap: the number of repositories this invocation may update,</li>
 *     <li>a rolling fixed-window cap: the number of repositories permitted in a window such as
 *         one hour, accumulated across runs through the state file.</li>
 * </ul>
 *
 * <p>Only successful updates count. Ignored, already-current and failed targets never consume
 * quota, so a repository that keeps failing cannot starve the rest of the batch.</p>
 *
 * <p>Two instances that start at the same time each read the same state and therefore each
 * compute the same remaining window budget. That is acceptable: it can cause extra updates but,
 * because updates are idempotent, not duplicate merge requests. No distributed lock is used.</p>
 */
public final class Quota {
    private final Integer maxRepositoriesPerRun;
    private final Duration window;
    private final int windowLimit;
    private final BatchState state;
    private final TimeSource clock;
    private final java.util.Set<String> repositoriesWrittenThisRun = new java.util.LinkedHashSet<>();

    /**
     * @param maxRepositoriesPerRun per-run cap, {@code null} for unlimited
     * @param window rolling window length, {@code null} to disable the window
     * @param windowLimit repositories permitted per window
     * @param state persisted state holding the window counters
     * @param clock injected clock
     */
    public Quota(Integer maxRepositoriesPerRun, Duration window, int windowLimit,
                 BatchState state, TimeSource clock) {
        this.maxRepositoriesPerRun = maxRepositoriesPerRun;
        this.window = window;
        this.windowLimit = windowLimit;
        this.state = state;
        this.clock = clock;
        state.rollWindowIfNeeded(clock.now(), window);
    }

    /**
     * Whether a write to {@code repository} may be performed right now. Quota is counted per
     * repository: once a repository has been updated in this run, further images in the same
     * repository do not consume another slot.
     *
     * @param repository canonical repository name, or {@code null} for a non-repository-aware check
     * @return whether the write may proceed
     */
    public boolean allowsAnotherWriteIn(String repository) {
        state.rollWindowIfNeeded(clock.now(), window);
        boolean alreadyCounted = repository != null
                && (repositoriesWrittenThisRun.contains(repository)
                    || state.repositoriesInWindow().contains(repository));
        if (maxRepositoriesPerRun != null && !alreadyCounted
                && repositoriesWrittenThisRun.size() >= maxRepositoriesPerRun) {
            return false;
        }
        return !windowEnabled() || alreadyCounted || state.usedInWindow() < windowLimit;
    }

    /**
     * Account for a successful write to {@code repository}. Must be called exactly once per
     * successful write. The first write to a repository in a run consumes a slot; later writes
     * to the same repository do not.
     *
     * @param repository canonical repository name
     */
    public void recordWrite(String repository) {
        Instant now = clock.now();
        state.rollWindowIfNeeded(now, window);
        if (repositoriesWrittenThisRun.add(repository) && windowEnabled()) {
            state.recordWindowUse(repository, now);
        }
    }

    /**
     * @return description of the quota state, used in {@code quota=} on deferred rows
     */
    public String status() {
        if (maxRepositoriesPerRun != null && repositoriesWrittenThisRun.size() >= maxRepositoriesPerRun) {
            return "per-run limit " + maxRepositoriesPerRun + " exhausted";
        }
        if (windowEnabled()) {
            return "window limit " + state.usedInWindow() + "/" + windowLimit
                    + " per " + window + " exhausted";
        }
        return "unlimited";
    }

    /**
     * @return short description of the configured limits, used in the report header
     */
    public String limitDescription() {
        if (maxRepositoriesPerRun == null && !windowEnabled()) {
            return "unlimited";
        }
        StringBuilder builder = new StringBuilder();
        if (maxRepositoriesPerRun != null) {
            builder.append("perRun=").append(maxRepositoriesPerRun);
        }
        if (windowEnabled()) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append("window=").append(windowLimit).append(" per ").append(window);
        }
        return builder.toString();
    }

    private boolean windowEnabled() {
        return window != null && !window.isZero() && !window.isNegative();
    }
}
