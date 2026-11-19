/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Mutable state carried between batch runs: how much of the time-window quota has been used and
 * when each target was last successfully updated.
 *
 * <p>The state exists only to make runs fair and to enforce a rolling quota. It is never
 * required for correctness: if it is lost or unreadable the engine starts from scratch, which
 * can cause a repository to be processed twice but can never cause one to be skipped.</p>
 */
public final class BatchState {
    static final int CURRENT_VERSION = 1;

    private int version;
    private Instant windowStart;
    private int usedInWindow;
    private final java.util.Set<String> repositoriesInWindow;
    private final Map<String, Instant> lastSuccessByKey;

    public BatchState(int version, Instant windowStart, int usedInWindow,
                      java.util.Set<String> repositoriesInWindow,
                      Map<String, Instant> lastSuccessByKey) {
        this.version = version;
        this.windowStart = windowStart;
        this.usedInWindow = usedInWindow;
        this.repositoriesInWindow = new java.util.LinkedHashSet<>(repositoriesInWindow);
        this.lastSuccessByKey = new LinkedHashMap<>(lastSuccessByKey);
    }

    /**
     * @return state for a fresh start
     */
    public static BatchState empty() {
        return new BatchState(CURRENT_VERSION, null, 0, Collections.emptySet(),
                Collections.emptyMap());
    }

    public int version() {
        return version;
    }

    public Instant windowStart() {
        return windowStart;
    }

    public int usedInWindow() {
        return usedInWindow;
    }

    /**
     * @param key target key
     * @return when the target was last successfully updated, if ever
     */
    public Instant lastSuccess(String key) {
        return lastSuccessByKey.get(key);
    }

    /**
     * @return read-only view of last-success timestamps
     */
    public Map<String, Instant> lastSuccessByKey() {
        return Collections.unmodifiableMap(lastSuccessByKey);
    }

    /**
     * Record a successful write.
     *
     * @param key target key
     * @param at instant of success from the injected clock
     */
    public void recordSuccess(String key, Instant at) {
        lastSuccessByKey.put(key, at);
    }

    /**
     * Account for one repository against the window. A repository already accounted for in this
     * window is not counted twice, so a repository that references several images consumes a
     * single slot.
     *
     * @param repository canonical repository name
     * @param at instant of the write
     */
    void recordWindowUse(String repository, Instant at) {
        if (windowStart == null) {
            windowStart = at;
        }
        if (repositoriesInWindow.add(repository)) {
            usedInWindow++;
        }
    }

    /**
     * Reset the window counter if the fixed window has elapsed.
     *
     * @param now current instant
     * @param window window length; zero or negative disables windowing
     */
    void rollWindowIfNeeded(Instant now, java.time.Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            return;
        }
        if (windowStart == null || !now.isBefore(windowStart.plus(window))) {
            windowStart = now;
            usedInWindow = 0;
            repositoriesInWindow.clear();
        }
    }

    /**
     * @return repositories already counted in the current window
     */
    java.util.Set<String> repositoriesInWindow() {
        return java.util.Collections.unmodifiableSet(repositoriesInWindow);
    }

    void setWindowStart(Instant windowStart) {
        this.windowStart = windowStart;
    }

    void setUsedInWindow(int usedInWindow) {
        this.usedInWindow = usedInWindow;
    }

    /**
     * Discard all progress and quota information. Called when a state file is present but
     * unusable so that the run starts from scratch with a full quota.
     */
    void reset() {
        this.windowStart = null;
        this.usedInWindow = 0;
        this.repositoriesInWindow.clear();
        this.lastSuccessByKey.clear();
    }

    /**
     * @return whether this state came from a readable, supported file
     */
    boolean isUsable() {
        return version == CURRENT_VERSION && usedInWindow >= 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BatchState)) {
            return false;
        }
        BatchState that = (BatchState) o;
        return version == that.version
                && usedInWindow == that.usedInWindow
                && Objects.equals(windowStart, that.windowStart)
                && repositoriesInWindow.equals(that.repositoriesInWindow)
                && lastSuccessByKey.equals(that.lastSuccessByKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, windowStart, usedInWindow, repositoriesInWindow,
                lastSuccessByKey);
    }
}
