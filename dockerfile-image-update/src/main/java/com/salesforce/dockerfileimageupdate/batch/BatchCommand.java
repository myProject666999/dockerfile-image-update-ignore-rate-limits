/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Entry point for a batch run: load and validate the ignore manifest <em>before</em> touching the
 * platform, then run the engine.
 *
 * <p>If the manifest is missing or invalid the run is refused outright: {@code discoverTargets}
 * is never called and no writes happen. Treating a broken manifest as "no ignore rules" would
 * silently update repositories that were meant to be protected, so it is never done.</p>
 */
public final class BatchCommand {
    private BatchCommand() {
    }

    /**
     * Run configuration.
     */
    public static final class Config {
        private final Integer maxRepositoriesPerRun;
        private final Duration window;
        private final int windowLimit;
        private final int expiryWarningDays;
        private final Path stateFile;
        private final String runnerName;
        private final boolean preview;

        /**
         * @param maxRepositoriesPerRun per-run cap, {@code null} for unlimited
         * @param window rolling window length, {@code null} to disable
         * @param windowLimit writes permitted per window
         * @param expiryWarningDays days before expiry at which a rule is reported as expiring
         * @param stateFile state file path inside the run directory
         * @param runnerName name shown in the report header
         */
        public Config(Integer maxRepositoriesPerRun, Duration window, int windowLimit,
                      int expiryWarningDays, Path stateFile, String runnerName) {
            this(maxRepositoriesPerRun, window, windowLimit, expiryWarningDays, stateFile,
                    runnerName, false);
        }

        /**
         * @param maxRepositoriesPerRun per-run cap, {@code null} for unlimited
         * @param window rolling window length, {@code null} to disable
         * @param windowLimit writes permitted per window
         * @param expiryWarningDays days before expiry at which a rule is reported as expiring
         * @param stateFile state file path inside the run directory
         * @param runnerName name shown in the report header
         * @param preview when true, report what would happen without writing or saving state
         */
        public Config(Integer maxRepositoriesPerRun, Duration window, int windowLimit,
                      int expiryWarningDays, Path stateFile, String runnerName, boolean preview) {
            this.maxRepositoriesPerRun = maxRepositoriesPerRun;
            this.window = window;
            this.windowLimit = windowLimit;
            this.expiryWarningDays = expiryWarningDays;
            this.stateFile = stateFile;
            this.runnerName = runnerName;
            this.preview = preview;
        }

        /**
         * @param stateFile state file path
         * @return default configuration with no quotas and real writes
         */
        public static Config unlimited(Path stateFile) {
            return new Config(null, null, 0, 14, stateFile, "batch");
        }
    }

    /**
     * Load the manifest and run.
     *
     * @param manifestPath manifest file path; when {@code null} an empty manifest is used
     * @param config run configuration
     * @param platform platform to operate on
     * @param clock injected clock
     * @return run outcome
     */
    public static Outcome run(Path manifestPath, Config config, BatchPlatform platform,
                              TimeSource clock) {
        IgnoreManifest manifest;
        try {
            manifest = loadManifest(manifestPath);
        } catch (IgnoreManifest.ManifestException | IOException e) {
            return Outcome.refused(ExitCodes.INVALID_MANIFEST,
                    "Refusing to run: ignore manifest " + manifestPath + " is invalid: "
                            + e.getMessage() + ". No repositories were modified.");
        }

        StateStore stateStore = new StateStore(config.stateFile);
        StateStore.LoadResult probe = stateStore.load();
        Quota quota = new Quota(config.maxRepositoriesPerRun, config.window, config.windowLimit,
                probe.state(), clock);
        BatchRunner runner = new BatchRunner(platform, manifest, quota, stateStore, clock,
                config.expiryWarningDays, config.runnerName, probe, config.preview);
        try {
            BatchRunner.RunResult result = runner.run();
            return Outcome.completed(result);
        } catch (BatchPlatform.PlatformException e) {
            return Outcome.refused(ExitCodes.RUN_FAILED,
                    "Batch run failed while reading the platform: " + e.getMessage());
        }
    }

    /**
     * Load a manifest from disk. A {@code null} path means "ignore nothing".
     *
     * @param manifestPath manifest path, may be {@code null}
     * @return validated manifest
     * @throws IOException if the file cannot be read
     * @throws IgnoreManifest.ManifestException if the manifest is invalid
     */
    public static IgnoreManifest loadManifest(Path manifestPath)
            throws IOException, IgnoreManifest.ManifestException {
        if (manifestPath == null) {
            return IgnoreManifest.empty();
        }
        if (!Files.exists(manifestPath)) {
            throw new IgnoreManifest.ManifestException("file does not exist");
        }
        String text = Files.readString(manifestPath, StandardCharsets.UTF_8);
        return IgnoreManifest.parse(text);
    }

    /**
     * Result of a batch command.
     */
    public static final class Outcome {
        private final int exitCode;
        private final String message;
        private final BatchRunner.RunResult result;

        private Outcome(int exitCode, String message, BatchRunner.RunResult result) {
            this.exitCode = exitCode;
            this.message = message;
            this.result = result;
        }

        static Outcome refused(int exitCode, String message) {
            return new Outcome(exitCode, message, null);
        }

        static Outcome completed(BatchRunner.RunResult result) {
            return new Outcome(result.exitCode(), null, result);
        }

        public int exitCode() {
            return exitCode;
        }

        public Optional<String> message() {
            return Optional.ofNullable(message);
        }

        public Optional<BatchRunner.RunResult> result() {
            return Optional.ofNullable(result);
        }

        /**
         * @return the rendered report, or the refusal message when the run was refused
         */
        public String reportText() {
            return result != null ? result.report().render() : message;
        }
    }
}
