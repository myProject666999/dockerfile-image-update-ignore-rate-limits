/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs one batch: discover targets, order them fairly, apply ignore rules, respect the quota,
 * and produce a report.
 *
 * <p>The engine is deliberately free of GitHub-specific code so that it can be exercised with an
 * in-memory platform and a fake clock. A run proceeds in three phases:</p>
 * <ol>
 *     <li><em>discover and classify</em>: every target is classified once, in fair order, as
 *         ignored, already current, or a candidate for a write,</li>
 *     <li><em>apply</em>: candidates are written while the quota allows; once it is exhausted the
 *         remaining candidates are marked deferred,</li>
 *     <li><em>report</em>: a {@link BatchReport} is rendered and the state is saved.</li>
 * </ol>
 */
public final class BatchRunner {
    private final BatchPlatform platform;
    private final IgnoreManifest manifest;
    private final Quota quota;
    private final StateStore stateStore;
    private final TimeSource clock;
    private final int expiryWarningDays;
    private final String runnerName;
    private final StateStore.LoadResult preloaded;
    private final boolean preview;

    /**
     * @param platform platform to read and write
     * @param manifest validated ignore manifest
     * @param quota quota for this run
     * @param stateStore state persistence
     * @param clock injected clock
     * @param expiryWarningDays days before expiry at which a rule is reported as expiring soon
     * @param runnerName name of this runner instance, shown in the report header
     * @param preloaded state already loaded by the caller; when {@code null} the runner loads it
     * @param preview when true, classify and report but never write or persist state
     */
    public BatchRunner(BatchPlatform platform, IgnoreManifest manifest, Quota quota,
                       StateStore stateStore, TimeSource clock, int expiryWarningDays,
                       String runnerName, StateStore.LoadResult preloaded, boolean preview) {
        this.platform = platform;
        this.manifest = manifest;
        this.quota = quota;
        this.stateStore = stateStore;
        this.clock = clock;
        this.expiryWarningDays = expiryWarningDays;
        this.runnerName = runnerName;
        this.preloaded = preloaded;
        this.preview = preview;
    }

    /**
     * Execute the run.
     *
     * @return the run outcome, including the rendered report
     * @throws BatchPlatform.PlatformException if the platform cannot be read
     */
    public RunResult run() throws BatchPlatform.PlatformException {
        Instant started = clock.now();
        LocalDate today = clock.today();

        StateStore.LoadResult loaded = preloaded != null ? preloaded : stateStore.load();
        BatchState state = loaded.state();
        if (loaded.startedOver()) {
            // A missing file is the first run; a damaged one must not leave stale progress or a
            // partially consumed window behind. Either way, start from scratch.
            state.reset();
        }

        List<BatchTarget> targets = deduplicate(platform.discoverTargets());
        List<BatchTarget> ordered = new FairOrder(state.lastSuccessByKey()).sort(targets);

        Map<String, TargetResult> results = new LinkedHashMap<>();
        List<BatchTarget> candidates = new ArrayList<>();

        for (BatchTarget target : ordered) {
            Optional<IgnoreRule> rule = manifest.decide(target.repository(), target.image(), today);
            if (rule.isPresent() && rule.get().action() == IgnoreRule.Action.IGNORE) {
                results.put(target.key(), TargetResult.ignored(target, rule.get()));
            } else if (target.isUpToDate()) {
                results.put(target.key(), TargetResult.notNeeded(target));
            } else {
                candidates.add(target);
            }
        }

        List<BatchTarget> toWrite = new ArrayList<>();
        for (BatchTarget candidate : candidates) {
            try {
                if (platform.needsUpdate(candidate)) {
                    toWrite.add(candidate);
                } else {
                    results.put(candidate.key(), TargetResult.notNeeded(candidate));
                }
            } catch (BatchPlatform.PlatformException | RuntimeException e) {
                // A failed check is a failed target; it must not consume quota or block others.
                results.put(candidate.key(), TargetResult.failed(candidate, messageOf(e)));
            }
        }

        for (BatchTarget target : toWrite) {
            if (!quota.allowsAnotherWriteIn(target.repository().canonical())) {
                results.put(target.key(), TargetResult.deferred(target, quota.status()));
                continue;
            }
            results.put(target.key(), preview ? preview(target) : apply(target, state));
        }

        if (!preview) {
            try {
                stateStore.save(state);
            } catch (IOException e) {
                // State is an optimisation; failing to persist it must not fail the run.
                // Starting over next time can duplicate work but cannot skip a repository.
            }
        }

        List<TargetResult> orderedResults = new ArrayList<>(results.values());
        BatchReport report = new BatchReport(
                runnerName,
                started.toString(),
                quotaDescription(),
                BatchReport.describeState(loaded.status()),
                orderedResults,
                manifest.expiringSoon(today, expiryWarningDays),
                expiryWarningDays,
                preview);
        return new RunResult(report, state, loaded);
    }

    private TargetResult apply(BatchTarget target, BatchState state) {
        try {
            BatchPlatform.UpdateOutcome outcome = platform.applyUpdate(target);
            if (outcome == BatchPlatform.UpdateOutcome.UPDATED) {
                Instant now = clock.now();
                quota.recordWrite(target.repository().canonical());
                state.recordSuccess(target.key(), now);
                return TargetResult.updated(target);
            }
            return TargetResult.notNeeded(target);
        } catch (BatchPlatform.PlatformException | RuntimeException e) {
            // A failed write does not consume quota and does not stop the rest of the batch.
            return TargetResult.failed(target, messageOf(e));
        }
    }

    private TargetResult preview(BatchTarget target) {
        return TargetResult.previewUpdate(target);
    }

    private static String messageOf(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static List<BatchTarget> deduplicate(Iterable<BatchTarget> discovered) {
        Map<String, BatchTarget> byKey = new LinkedHashMap<>();
        for (BatchTarget target : discovered) {
            byKey.putIfAbsent(target.key(), target);
        }
        return new ArrayList<>(byKey.values());
    }

    private String quotaDescription() {
        return quota.limitDescription();
    }

    /**
     * Result of a run.
     */
    public static final class RunResult {
        private final BatchReport report;
        private final BatchState state;
        private final StateStore.LoadResult loadResult;

        RunResult(BatchReport report, BatchState state, StateStore.LoadResult loadResult) {
            this.report = report;
            this.state = state;
            this.loadResult = loadResult;
        }

        public BatchReport report() {
            return report;
        }

        public BatchState state() {
            return state;
        }

        public StateStore.LoadResult loadResult() {
            return loadResult;
        }

        /**
         * @return whether the quota stopped the run before every candidate was considered
         */
        public boolean quotaExhausted() {
            return report.quotaExhausted();
        }

        /**
         * @return process exit code: 0 when everything was processed, 3 when quota-limited
         */
        public int exitCode() {
            return quotaExhausted() ? ExitCodes.QUOTA_EXHAUSTED : ExitCodes.COMPLETE;
        }
    }
}
