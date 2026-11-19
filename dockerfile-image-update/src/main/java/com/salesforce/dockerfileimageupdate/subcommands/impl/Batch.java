/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.subcommands.impl;

import com.salesforce.dockerfileimageupdate.SubCommand;
import com.salesforce.dockerfileimageupdate.batch.BatchCommand;
import com.salesforce.dockerfileimageupdate.batch.ExitCodes;
import com.salesforce.dockerfileimageupdate.batch.GitHubBatchPlatform;
import com.salesforce.dockerfileimageupdate.batch.TimeSource;
import com.salesforce.dockerfileimageupdate.subcommands.ExecutableWithNamespace;
import com.salesforce.dockerfileimageupdate.utils.Constants;
import com.salesforce.dockerfileimageupdate.utils.DockerfileGitHubUtil;
import net.sourceforge.argparse4j.inf.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * The {@code batch} subcommand: applies the ignore manifest and per-run quota on top of the
 * existing <em>all</em> behaviour.
 *
 * <p>Wiring is intentionally thin. The manifest is loaded first by {@link BatchCommand}; the
 * platform is a {@link GitHubBatchPlatform} that delegates image discovery to the existing search
 * and PR machinery. If the manifest is invalid, nothing is loaded from GitHub.</p>
 */
@SubCommand(help = "updates all repositories' Dockerfiles with ignore rules and a per-run quota",
        requiredParams = {Constants.STORE})
public class Batch implements ExecutableWithNamespace {
    private static final Logger log = LoggerFactory.getLogger(Batch.class);

    @Override
    public void execute(Namespace ns, DockerfileGitHubUtil dockerfileGitHubUtil) throws Exception {
        Path manifestPath = ns.get(Constants.IGNORE_MANIFEST) == null
                ? null : Paths.get(ns.getString(Constants.IGNORE_MANIFEST));

        Integer maxRepositories = parseMaxRepositories(ns);
        Duration window = parseWindow(ns);
        int windowLimit = parseWindowLimit(ns);
        Path stateFile = Paths.get(ns.get(Constants.BATCH_STATE_FILE) == null
                ? ".dfiu-batch-state.json" : ns.getString(Constants.BATCH_STATE_FILE));

        BatchCommand.Config config = new BatchCommand.Config(maxRepositories, window, windowLimit,
                Integer.parseInt(ns.get(Constants.BATCH_EXPIRY_WARNING_DAYS) == null
                        ? "14" : ns.getString(Constants.BATCH_EXPIRY_WARNING_DAYS)),
                stateFile, "github", parsePreview(ns));

        GitHubBatchPlatform platform = new GitHubBatchPlatform(dockerfileGitHubUtil, ns);
        BatchCommand.Outcome outcome = BatchCommand.run(manifestPath, config, platform,
                TimeSource.system());

        System.out.print(outcome.reportText());
        outcome.message().ifPresent(message -> log.error(message));
        if (outcome.exitCode() != ExitCodes.COMPLETE) {
            System.exit(outcome.exitCode());
        }
    }

    private static Integer parseMaxRepositories(Namespace ns) {
        String value = ns.get(Constants.BATCH_MAX_REPOSITORIES);
        return value == null ? null : Integer.parseInt(value);
    }

    private static boolean parsePreview(Namespace ns) {
        return Boolean.TRUE.equals(ns.get(Constants.BATCH_PREVIEW));
    }

    private static int parseWindowLimit(Namespace ns) {
        String value = ns.get(Constants.BATCH_MAX_PER_WINDOW);
        return value == null ? 0 : Integer.parseInt(value.split("-", 2)[0]);
    }

    private static Duration parseWindow(Namespace ns) {
        String value = ns.get(Constants.BATCH_MAX_PER_WINDOW);
        if (value == null) {
            return null;
        }
        String[] parts = value.split("-", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Expected <count>-<ISO-8601 duration> for " + Constants.BATCH_MAX_PER_WINDOW
                            + ", e.g. 20-PT1H");
        }
        try {
            return Duration.parse(parts[1]);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid ISO-8601 duration: " + parts[1], e);
        }
    }
}
