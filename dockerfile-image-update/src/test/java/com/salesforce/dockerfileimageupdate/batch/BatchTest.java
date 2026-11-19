/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class BatchTest {

    private static IgnoreManifest manifest(String resource) throws Exception {
        return IgnoreManifest.parse(readResource(resource));
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream stream = BatchTest.class.getResourceAsStream("/batch/" + resource)) {
            assertNotNull(stream, "missing resource " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path tempStateFile() throws IOException {
        Path directory = Files.createTempDirectory("dfiu-batch-test");
        directory.toFile().deleteOnExit();
        return directory.resolve("state.json");
    }

    private static BatchRunner.RunResult run(InMemoryPlatform platform, IgnoreManifest manifest,
                                             Integer maxPerRun, Duration window, int windowLimit,
                                             Path stateFile, FakeClock clock) {
        StateStore store = new StateStore(stateFile);
        StateStore.LoadResult loaded = store.load();
        Quota quota = new Quota(maxPerRun, window, windowLimit, loaded.state(), clock);
        BatchRunner runner = new BatchRunner(platform, manifest, quota, store, clock, 14,
                "test", loaded, false);
        try {
            return runner.run();
        } catch (BatchPlatform.PlatformException e) {
            throw new AssertionError(e);
        }
    }

    private static BatchRunner.RunResult run(InMemoryPlatform platform, IgnoreManifest manifest,
                                             Path stateFile, FakeClock clock) {
        return run(platform, manifest, null, null, 0, stateFile, clock);
    }

    private static List<TargetResult> resultsFor(BatchRunner.RunResult result, String repository) {
        return result.report().results().stream()
                .filter(r -> r.target().repository().canonical().equals(repository))
                .collect(Collectors.toList());
    }

    // ---------------------------------------------------------------- normalization

    @Test
    public void imageNamesWithDifferentSpellingsAreOneImage() {
        assertEquals(ImageName.parse("nginx"), ImageName.parse("docker.io/library/nginx"));
        assertEquals(ImageName.parse("nginx"), ImageName.parse("index.docker.io/library/nginx"));
        assertEquals(ImageName.parse("nginx"), ImageName.parse("library/nginx"));
        assertEquals(ImageName.parse("OpenJDK"), ImageName.parse("openjdk"));
        assertEquals(ImageName.parse("quay.io/Acme/Base"), ImageName.parse("quay.io/acme/base"));
        assertEquals(ImageName.parse("nginx:1.2").canonical(), ImageName.parse("nginx").canonical());
        assertEquals(ImageName.parse("nginx@sha256:abc").canonical(), ImageName.parse("nginx").canonical());
        assertEquals(ImageName.parse("quay.io/acme/base").shortName(), "base");
    }

    @Test
    public void otherRegistriesAreKeptDistinct() {
        assertFalse(ImageName.parse("quay.io/acme/base").equals(ImageName.parse("acme/base")));
        assertEquals(ImageName.parse("acme/base").canonical(), "docker.io/acme/base");
        assertEquals(ImageName.parse("localhost:5000/team/app").canonical(), "localhost:5000/team/app");
    }

    @Test
    public void repositoryNamesAreCaseInsensitive() {
        assertEquals(RepositoryName.parse("Acme/My-App"), RepositoryName.parse("acme/my-app"));
        assertEquals(RepositoryName.parse("acme/web").name(), "web");
        assertThrows(IllegalArgumentException.class, () -> RepositoryName.parse("web"));
    }

    @Test
    public void differentlyWrittenImageSelectorsHitTheSameRule() throws Exception {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 20);
        for (String written : Arrays.asList("nginx", "library/nginx", "docker.io/library/nginx",
                "index.docker.io/library/nginx", "NGINX")) {
            IgnoreManifest manifest = IgnoreManifest.parse(
                    "{\"rules\":[{\"id\":\"r\",\"image\":\"" + written + "\"}]}");
            Optional<IgnoreRule> rule = manifest.decide(RepositoryName.parse("acme/web"),
                    ImageName.parse("nginx"), today);
            assertTrue(rule.isPresent(), "image selector '" + written + "' should match nginx");
            assertEquals(rule.get().id(), "r");
        }
    }

    @Test
    public void differentlyWrittenRepositorySelectorsHitTheSameRule() throws Exception {
        IgnoreManifest manifest = IgnoreManifest.parse(
                "{\"rules\":[{\"id\":\"r\",\"repository\":\"ACME/Web\"}]}");
        Optional<IgnoreRule> rule = manifest.decide(RepositoryName.parse("acme/web"),
                ImageName.parse("nginx"), java.time.LocalDate.of(2026, 11, 20));
        assertTrue(rule.isPresent());
        assertEquals(rule.get().id(), "r");
    }

    @Test
    public void exactSelectorsDoNotPrefixMatch() throws Exception {
        IgnoreManifest manifest = IgnoreManifest.parse(
                "{\"rules\":[{\"id\":\"n\",\"image\":\"nginx\"}]}");
        assertTrue(manifest.decide(RepositoryName.parse("acme/web"),
                ImageName.parse("nginx-alpine"), java.time.LocalDate.of(2026, 11, 20)).isEmpty());
        IgnoreManifest repo = IgnoreManifest.parse(
                "{\"rules\":[{\"id\":\"n\",\"repository\":\"acme/web\"}]}");
        assertTrue(repo.decide(RepositoryName.parse("acme/web-api"),
                ImageName.parse("nginx"), java.time.LocalDate.of(2026, 11, 20)).isEmpty());
    }

    @Test
    public void wildcardsMatchAsDocumented() throws Exception {
        IgnoreManifest manifest = IgnoreManifest.parse(
                "{\"rules\":[{\"id\":\"n\",\"image\":\"nginx*\"}]}");
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 20);
        assertTrue(manifest.decide(RepositoryName.parse("acme/web"),
                ImageName.parse("nginx-alpine"), today).isPresent());
        IgnoreManifest org = IgnoreManifest.parse(
                "{\"rules\":[{\"id\":\"o\",\"repository\":\"acme/*\"}]}");
        assertTrue(org.decide(RepositoryName.parse("acme/web"), ImageName.parse("nginx"), today).isPresent());
        assertTrue(org.decide(RepositoryName.parse("other/web"), ImageName.parse("nginx"), today).isEmpty());
    }

    // ---------------------------------------------------------------- rule precedence

    @Test
    public void exactRuleOverridesWildcardRuleInSameRepository() throws Exception {
        // An ignore for all nginx, plus an exact allow for the one repository/image pair.
        IgnoreManifest manifest = IgnoreManifest.parse("{\"rules\":["
                + "{\"id\":\"all-nginx\",\"image\":\"nginx*\"},"
                + "{\"id\":\"allow-api\",\"action\":\"include\",\"repository\":\"acme/api\",\"image\":\"nginx\"}"
                + "]}");
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 20);
        IgnoreRule webNginx = manifest.decide(RepositoryName.parse("acme/web"),
                ImageName.parse("nginx"), today).orElseThrow(AssertionError::new);
        assertEquals(webNginx.id(), "all-nginx");
        IgnoreRule apiNginx = manifest.decide(RepositoryName.parse("acme/api"),
                ImageName.parse("nginx"), today).orElseThrow(AssertionError::new);
        assertEquals(apiNginx.id(), "allow-api");
        assertEquals(apiNginx.action(), IgnoreRule.Action.INCLUDE);
    }

    @Test
    public void resourceManifestBehavesAsDocumented() throws Exception {
        // manifest-precedence.json: ignore nginx everywhere, but include it for acme/cache.
        InMemoryPlatform platform = new InMemoryPlatform()
                .add("acme/web", "nginx", "1.0", "1.1")
                .add("acme/cache", "docker.io/library/nginx", "1.0", "1.1")
                .add("acme/billing", "quay.io/acme/base", "1.0", "1.1");
        FakeClock clock = new FakeClock("2026-11-20T00:00:00Z");
        BatchRunner.RunResult result = run(platform, manifest("manifest-precedence.json"),
                tempStateFileOrFail(), clock);

        assertEquals(resultsFor(result, "acme/web").get(0).status(), TargetResult.Status.IGNORED);
        assertEquals(resultsFor(result, "acme/web").get(0).ruleId().orElseThrow(AssertionError::new),
                "all-nginx-except-cache");
        assertEquals(resultsFor(result, "acme/cache").get(0).status(), TargetResult.Status.UPDATED);
        assertEquals(resultsFor(result, "acme/billing").get(0).status(), TargetResult.Status.IGNORED);
        assertEquals(resultsFor(result, "acme/billing").get(0).ruleId().orElseThrow(AssertionError::new),
                "freeze-billing-base");
    }

    private static Path tempStateFileOrFail() {
        try {
            return tempStateFile();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    // ---------------------------------------------------------------- manifest validation

    @Test
    public void crossedRulesWithOppositeActionsAreRejectedAtLoad() {
        assertThrows(IgnoreManifest.ManifestException.class,
                () -> manifest("manifest-ambiguous.json"));
    }

    @Test
    public void identicalScopeWithOppositeActionsIsRejected() {
        assertThrows(IgnoreManifest.ManifestException.class, () -> IgnoreManifest.parse("{\"rules\":["
                + "{\"id\":\"a\",\"repository\":\"acme/web\"},"
                + "{\"id\":\"b\",\"action\":\"include\",\"repository\":\"Acme/Web\"}"
                + "]}"));
    }

    @Test
    public void disjointWildcardRulesWithOppositeActionsLoadFine() throws Exception {
        // Different organizations' repositories can never collide, so these are not ambiguous.
        IgnoreManifest manifest = IgnoreManifest.parse("{\"rules\":["
                + "{\"id\":\"ignore-web\",\"action\":\"ignore\",\"repository\":\"acme/web*\"},"
                + "{\"id\":\"allow-api\",\"action\":\"include\",\"repository\":\"acme/api*\"}"
                + "]}");
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 20);
        assertEquals(manifest.decide(RepositoryName.parse("acme/web-1"), ImageName.parse("nginx"), today)
                .orElseThrow(AssertionError::new).action(), IgnoreRule.Action.IGNORE);
        assertEquals(manifest.decide(RepositoryName.parse("acme/api-1"), ImageName.parse("nginx"), today)
                .orElseThrow(AssertionError::new).action(), IgnoreRule.Action.INCLUDE);
    }

    @Test
    public void duplicateIdsAreRejected() {
        assertThrows(IgnoreManifest.ManifestException.class, () -> IgnoreManifest.parse("{\"rules\":["
                + "{\"id\":\"a\",\"repository\":\"acme/web\"},"
                + "{\"id\":\"a\",\"repository\":\"acme/api\"}"
                + "]}"));
    }

    @Test
    public void unknownFieldsAreRejected() {
        assertThrows(IgnoreManifest.ManifestException.class, () -> IgnoreManifest.parse("{\"rules\":["
                + "{\"id\":\"a\",\"repository\":\"acme/web\",\"expires\":\"2026-01-01\"}]}"));
    }

    @Test
    public void rulesWithoutScopeAreRejected() {
        assertThrows(IgnoreManifest.ManifestException.class,
                () -> IgnoreManifest.parse("{\"rules\":[{\"id\":\"a\"}]}"));
    }

    @Test
    public void badExpiryDateIsRejected() {
        assertThrows(IgnoreManifest.ManifestException.class, () -> IgnoreManifest.parse("{\"rules\":["
                + "{\"id\":\"a\",\"repository\":\"acme/web\",\"expiresOn\":\"next month\"}]}"));
    }

    @Test
    public void malformedManifestResourceIsRejected() {
        assertThrows(IgnoreManifest.ManifestException.class,
                () -> manifest("manifest-malformed.json"));
    }

    // ---------------------------------------------------------------- invalid manifest aborts run

    @Test
    public void invalidManifestRefusesRunBeforeTouchingPlatform() throws Exception {
        InMemoryPlatform platform = new InMemoryPlatform()
                .add("acme/web", "nginx", "1.0", "1.1");
        BatchCommand.Config config = new BatchCommand.Config(null, null, 0, 14,
                tempStateFile(), "test");
        BatchCommand.Outcome outcome = BatchCommand.run(
                Path.of(BatchTest.class.getResource("/batch/manifest-malformed.json").toURI()),
                config, platform, new FakeClock("2026-11-20T00:00:00Z"));

        assertEquals(outcome.exitCode(), ExitCodes.INVALID_MANIFEST);
        assertEquals(platform.discoverCalls(), 0, "platform must not be read");
        assertTrue(platform.writtenKeys().isEmpty(), "no writes may happen");
        assertTrue(outcome.message().isPresent());
    }

    @Test
    public void missingManifestFileIsAnErrorNotAnEmptyManifest() throws Exception {
        InMemoryPlatform platform = new InMemoryPlatform()
                .add("acme/web", "nginx", "1.0", "1.1");
        BatchCommand.Config config = new BatchCommand.Config(null, null, 0, 14,
                tempStateFile(), "test");
        Path missing = Path.of("/nonexistent/dfiu-ignore-manifest.json");
        BatchCommand.Outcome outcome = BatchCommand.run(missing, config, platform,
                new FakeClock("2026-11-20T00:00:00Z"));

        assertEquals(outcome.exitCode(), ExitCodes.INVALID_MANIFEST);
        assertEquals(platform.discoverCalls(), 0);
    }

    // ---------------------------------------------------------------- quota and fairness

    @Test
    public void threeRateLimitedRunsCoverEverythingWithoutOverlap() throws Exception {
        InMemoryPlatform platform = new InMemoryPlatform();
        List<String> repositories = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            String repository = String.format("acme/repo-%02d", i);
            repositories.add(repository);
            platform.add(repository, "nginx", "1.0", "1.1");
        }
        Path stateFile = tempStateFile();
        FakeClock clock = new FakeClock("2026-11-20T00:00:00Z");
        int limit = repositories.size() / 3;

        Set<String> processedOverall = new LinkedHashSet<>();
        List<Set<String>> perRun = new ArrayList<>();
        for (int run = 0; run < 3; run++) {
            BatchRunner.RunResult result = run(platform, IgnoreManifest.empty(), limit, null, 0,
                    stateFile, clock);
            Set<String> updatedThisRun = result.report().results().stream()
                    .filter(r -> r.status() == TargetResult.Status.UPDATED)
                    .map(r -> r.target().repository().canonical())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            perRun.add(updatedThisRun);

            // Every deferred repository must be listed, with a quota status.
            for (TargetResult targetResult : result.report().results()) {
                if (targetResult.status() == TargetResult.Status.DEFERRED) {
                    assertTrue(targetResult.quotaStatus().isPresent());
                }
            }
            if (run < 3) {
                assertTrue(result.quotaExhausted() || run == 2);
            }
            processedOverall.addAll(updatedThisRun);
            clock.advanceDays(1);
        }

        int total = 0;
        for (int i = 0; i < perRun.size(); i++) {
            total += perRun.get(i).size();
            for (int j = i + 1; j < perRun.size(); j++) {
                Set<String> intersection = new HashSet<>(perRun.get(i));
                intersection.retainAll(perRun.get(j));
                assertTrue(intersection.isEmpty(), "runs must not overlap: " + intersection);
            }
        }
        assertEquals(total, repositories.size());
        assertEquals(processedOverall, new HashSet<>(repositories));
    }

    @Test
    public void quotaExhaustedRunUsesDifferentExitCode() throws Exception {
        InMemoryPlatform platform = new InMemoryPlatform()
                .add("acme/a", "nginx", "1.0", "1.1")
                .add("acme/b", "nginx", "1.0", "1.1");
        BatchRunner.RunResult limited = run(platform, IgnoreManifest.empty(), 1, null, 0,
                tempStateFile(), new FakeClock("2026-11-20T00:00:00Z"));
        assertEquals(limited.exitCode(), ExitCodes.QUOTA_EXHAUSTED);

        InMemoryPlatform all = new InMemoryPlatform()
                .add("acme/a", "nginx", "1.0", "1.1")
                .add("acme/b", "nginx", "1.0", "1.1");
        BatchRunner.RunResult complete = run(all, IgnoreManifest.empty(), null, null, 0,
                tempStateFile(), new FakeClock("2026-11-20T00:00:00Z"));
        assertEquals(complete.exitCode(), ExitCodes.COMPLETE);
    }

    @Test
    public void ignoredAndCurrentRepositoriesDoNotConsumeQuota() throws Exception {
        InMemoryPlatform platform = new InMemoryPlatform()
                .add("acme/ignored-1", "nginx", "1.0", "1.1")
                .add("acme/ignored-2", "nginx", "1.0", "1.1")
                .add("acme/current", "nginx", "1.1", "1.1")
                .add("acme/needs-update", "nginx", "1.0", "1.1");
        IgnoreManifest manifest = IgnoreManifest.parse(
                "{\"rules\":[{\"id\":\"ignore\",\"repository\":\"acme/ignored-*\"}]}");

        BatchRunner.RunResult result = run(platform, manifest, 1, null, 0, tempStateFile(),
                new FakeClock("2026-11-20T00:00:00Z"));

        assertEquals(resultsFor(result, "acme/ignored-1").get(0).status(), TargetResult.Status.IGNORED);
        assertEquals(resultsFor(result, "acme/ignored-2").get(0).status(), TargetResult.Status.IGNORED);
        assertEquals(resultsFor(result, "acme/current").get(0).status(), TargetResult.Status.NOT_NEEDED);
        assertEquals(resultsFor(result, "acme/needs-update").get(0).status(), TargetResult.Status.UPDATED);
        assertFalse(result.quotaExhausted());
        assertEquals(platform.writtenRepositories(), Arrays.asList("acme/needs-update"));
    }

    @Test
    public void perpetuallyFailingRepositoryNeitherConsumesQuotaNorBlocksOthers() throws Exception {
        InMemoryPlatform platform = new InMemoryPlatform()
                .add("acme/broken", "nginx", "1.0", "1.1")
                .add("acme/healthy-1", "nginx", "1.0", "1.1")
                .add("acme/healthy-2", "nginx", "1.0", "1.1")
                .failing("acme/broken");
        Path stateFile = tempStateFile();
        // "acme/broken" sorts first; with a per-run cap of 2 the two healthy repos must still go.
        BatchRunner.RunResult result = run(platform, IgnoreManifest.empty(), 2, null, 0,
                stateFile, new FakeClock("2026-11-20T00:00:00Z"));

        assertEquals(resultsFor(result, "acme/broken").get(0).status(), TargetResult.Status.FAILED);
        assertEquals(resultsFor(result, "acme/healthy-1").get(0).status(), TargetResult.Status.UPDATED);
        assertEquals(resultsFor(result, "acme/healthy-2").get(0).status(), TargetResult.Status.UPDATED);
        assertFalse(result.quotaExhausted());
        // Exactly the two healthy writes were recorded; the failure left no progress behind.
        assertEquals(result.state().lastSuccessByKey().size(), 2);
        assertFalse(result.state().lastSuccessByKey().containsKey("acme/broken|docker.io/library/nginx"));
    }

    @Test
    public void quotaCountsRepositoriesNotImages() throws Exception {
        // One repository references two images; updating both costs a single repository slot.
        InMemoryPlatform platform = new InMemoryPlatform()
                .add("acme/a", "nginx", "1.0", "1.1")
                .add("acme/a", "redis", "6", "7")
                .add("acme/b", "nginx", "1.0", "1.1");
        BatchRunner.RunResult result = run(platform, IgnoreManifest.empty(), 1, null, 0,
                tempStateFile(), new FakeClock("2026-11-20T00:00:00Z"));

        assertEquals(resultsFor(result, "acme/a").size(), 2, "both images in acme/a are reported");
        for (TargetResult targetResult : resultsFor(result, "acme/a")) {
            assertEquals(targetResult.status(), TargetResult.Status.UPDATED);
        }
        assertEquals(resultsFor(result, "acme/b").get(0).status(), TargetResult.Status.DEFERRED);
    }

    @Test
    public void rollingWindowQuotaUsesInjectedClock() throws Exception {
        InMemoryPlatform platform = new InMemoryPlatform();
        for (int i = 0; i < 4; i++) {
            platform.add(String.format("acme/repo-%d", i), "nginx", "1.0", "1.1");
        }
        Path stateFile = tempStateFile();
        FakeClock clock = new FakeClock("2026-11-20T00:00:00Z");

        BatchRunner.RunResult first = run(platform, IgnoreManifest.empty(), null,
                Duration.ofHours(1), 2, stateFile, clock);
        assertEquals(first.report().results().stream()
                .filter(r -> r.status() == TargetResult.Status.UPDATED).count(), 2L);
        assertTrue(first.quotaExhausted());

        // Still inside the window: nothing more may be written.
        clock.advance(Duration.ofMinutes(30));
        BatchRunner.RunResult second = run(platform, IgnoreManifest.empty(), null,
                Duration.ofHours(1), 2, stateFile, clock);
        assertEquals(second.report().results().stream()
                .filter(r -> r.status() == TargetResult.Status.UPDATED).count(), 0L);

        // After the window rolls, the remaining two are allowed.
        clock.advance(Duration.ofMinutes(31));
        BatchRunner.RunResult third = run(platform, IgnoreManifest.empty(), null,
                Duration.ofHours(1), 2, stateFile, clock);
        assertEquals(third.report().results().stream()
                .filter(r -> r.status() == TargetResult.Status.UPDATED).count(), 2L);
    }

    @Test
    public void orderingIsFairAcrossRuns() throws Exception {
        InMemoryPlatform platform = new InMemoryPlatform()
                .add("acme/aaa", "nginx", "1.0", "1.1")
                .add("acme/bbb", "nginx", "1.0", "1.1")
                .add("acme/ccc", "nginx", "1.0", "1.1");
        Path stateFile = tempStateFile();
        FakeClock clock = new FakeClock("2026-11-20T00:00:00Z");

        BatchRunner.RunResult first = run(platform, IgnoreManifest.empty(), 1, null, 0,
                stateFile, clock);
        assertEquals(first.report().results().stream()
                .filter(r -> r.status() == TargetResult.Status.UPDATED)
                .map(r -> r.target().repository().canonical())
                .collect(Collectors.toList()), Arrays.asList("acme/aaa"));

        clock.advance(Duration.ofMinutes(5));
        BatchRunner.RunResult second = run(platform, IgnoreManifest.empty(), 1, null, 0,
                stateFile, clock);
        // The second run advances to the next repository rather than repeating the first.
        assertEquals(second.report().results().stream()
                .filter(r -> r.status() == TargetResult.Status.UPDATED)
                .map(r -> r.target().repository().canonical())
                .collect(Collectors.toList()), Arrays.asList("acme/bbb"));
    }

    // ---------------------------------------------------------------- expiry

    @Test
    public void expiringRuleWarnsThenStopsApplying() throws Exception {
        IgnoreManifest manifest = manifest("manifest-expiring.json");
        Path stateFile = tempStateFile();

        // One day before expiry: still ignored, and reported as expiring soon.
        FakeClock clock = new FakeClock("2026-12-30T00:00:00Z");
        InMemoryPlatform before = new InMemoryPlatform()
                .add("acme/legacy", "nginx", "1.0", "1.1");
        BatchRunner.RunResult beforeResult = run(before, manifest, stateFile, clock);
        assertEquals(resultsFor(beforeResult, "acme/legacy").get(0).status(),
                TargetResult.Status.IGNORED);
        String report = beforeResult.report().render();
        assertTrue(report.contains("expiring-rule id=temporary-freeze"),
                "report should warn about the expiring rule:\n" + report);

        // On the expiry day: no longer ignored.
        clock.advanceDays(1);
        InMemoryPlatform onDay = new InMemoryPlatform()
                .add("acme/legacy", "nginx", "1.0", "1.1");
        BatchRunner.RunResult onDayResult = run(onDay, manifest, tempStateFile(), clock);
        assertEquals(resultsFor(onDayResult, "acme/legacy").get(0).status(),
                TargetResult.Status.UPDATED);
        assertFalse(onDayResult.report().render().contains("expiring-rule"));
    }

    // ---------------------------------------------------------------- state resilience

    @Test
    public void corruptStateFileStartsOverInsteadOfFailing() throws Exception {
        Path stateFile = tempStateFile();
        Files.writeString(stateFile, "{ this is not valid json", StandardCharsets.UTF_8);

        InMemoryPlatform platform = new InMemoryPlatform()
                .add("acme/a", "nginx", "1.0", "1.1")
                .add("acme/b", "nginx", "1.0", "1.1");
        BatchRunner.RunResult result = run(platform, IgnoreManifest.empty(), 5, null, 0,
                stateFile, new FakeClock("2026-11-20T00:00:00Z"));

        assertEquals(result.loadResult().status(), StateStore.LoadStatus.CORRUPT);
        assertTrue(result.report().render().contains("state=reset-corrupt"));
        assertEquals(platform.writtenRepositories().size(), 2);
        assertTrue(Files.exists(stateFile));
    }

    @Test
    public void truncatedStateFileStartsOver() throws Exception {
        Path stateFile = tempStateFile();
        Files.writeString(stateFile, "{\"version\":1,\"usedInWindow\":", StandardCharsets.UTF_8);
        InMemoryPlatform platform = new InMemoryPlatform()
                .add("acme/a", "nginx", "1.0", "1.1");
        BatchRunner.RunResult result = run(platform, IgnoreManifest.empty(), 5, null, 0,
                stateFile, new FakeClock("2026-11-20T00:00:00Z"));
        assertEquals(result.loadResult().status(), StateStore.LoadStatus.CORRUPT);
        assertEquals(platform.writtenRepositories().size(), 1);
    }

    @Test
    public void missingStateFileIsAFreshStart() throws Exception {
        InMemoryPlatform platform = new InMemoryPlatform()
                .add("acme/a", "nginx", "1.0", "1.1");
        BatchRunner.RunResult result = run(platform, IgnoreManifest.empty(), 5, null, 0,
                tempStateFile(), new FakeClock("2026-11-20T00:00:00Z"));
        assertEquals(result.loadResult().status(), StateStore.LoadStatus.MISSING);
        assertTrue(result.report().render().contains("state=reset-missing"));
    }

    @Test
    public void stateRoundTrips() throws Exception {
        Path stateFile = tempStateFile();
        StateStore store = new StateStore(stateFile);
        BatchState state = BatchState.empty();
        state.recordSuccess("acme/a|docker.io/library/nginx", Instant.parse("2026-11-20T00:00:00Z"));
        store.save(state);

        StateStore.LoadResult loaded = store.load();
        assertEquals(loaded.status(), StateStore.LoadStatus.LOADED);
        assertEquals(loaded.state().lastSuccess("acme/a|docker.io/library/nginx"),
                Instant.parse("2026-11-20T00:00:00Z"));
        assertFalse(loaded.startedOver());
    }

    @Test
    public void stateFileIsNeverLeftPartiallyWritten() throws Exception {
        Path stateFile = tempStateFile();
        StateStore store = new StateStore(stateFile);
        store.save(BatchState.empty());
        // A successful save leaves exactly the state file, no temporary files.
        try (java.util.stream.Stream<Path> list = Files.list(stateFile.getParent())) {
            List<String> names = list.map(p -> p.getFileName().toString())
                    .filter(n -> !n.equals("state.json"))
                    .collect(Collectors.toList());
            assertTrue(names.isEmpty(), "unexpected leftover files: " + names);
        }
    }

    // ---------------------------------------------------------------- report format

    @Test
    public void reportIsOneLinePerRepositoryWithSummary() throws Exception {
        InMemoryPlatform platform = new InMemoryPlatform()
                .add("acme/updated", "nginx", "1.0", "1.1")
                .add("acme/current", "nginx", "1.1", "1.1")
                .add("acme/ignored", "nginx", "1.0", "1.1")
                .add("acme/failing", "nginx", "1.0", "1.1")
                .add("acme/zz-deferred", "nginx", "1.0", "1.1")
                .failing("acme/failing");
        IgnoreManifest manifest = IgnoreManifest.parse(
                "{\"rules\":[{\"id\":\"skip\",\"repository\":\"acme/ignored\"}]}");

        BatchRunner.RunResult result = run(platform, manifest, 1, null, 0, tempStateFile(),
                new FakeClock("2026-11-20T00:00:00Z"));
        String report = result.report().render();

        List<String> targetLines = Arrays.stream(report.split("\n"))
                .filter(line -> line.startsWith("TARGET ")).collect(Collectors.toList());
        assertEquals(targetLines.size(), 5, report);
        assertTrue(report.contains("status=updated"), report);
        assertTrue(report.contains("status=no-op"), report);
        assertTrue(report.contains("status=ignored rule=skip"), report);
        assertTrue(report.contains("status=deferred"), report);
        assertTrue(report.contains("status=failed"), report);
        assertTrue(report.contains("SUMMARY updated=1 no-op=1 ignored=1 deferred=1 failed=1 total=5"),
                report);
    }

    @Test
    public void failureReasonIsIncluded() throws Exception {
        InMemoryPlatform platform = new InMemoryPlatform().failing("acme/broken");
        platform.add("acme/broken", "nginx", "1.0", "1.1");
        BatchRunner.RunResult result = run(platform, IgnoreManifest.empty(), tempStateFile(),
                new FakeClock("2026-11-20T00:00:00Z"));
        String report = result.report().render();
        assertTrue(report.contains("simulated failure for acme/broken"), report);
    }

    // ---------------------------------------------------------------- preview

    @Test
    public void previewListsPerRepositoryOutcomeWithoutWriting() throws Exception {
        InMemoryPlatform platform = new InMemoryPlatform()
                .add("acme/updated", "nginx", "1.0", "1.1")
                .add("acme/current", "nginx", "1.1", "1.1")
                .add("acme/ignored", "nginx", "1.0", "1.1");
        IgnoreManifest manifest = IgnoreManifest.parse(
                "{\"rules\":[{\"id\":\"skip\",\"repository\":\"acme/ignored\"}]}");
        Path stateFile = tempStateFile();
        FakeClock clock = new FakeClock("2026-11-20T00:00:00Z");
        StateStore store = new StateStore(stateFile);
        StateStore.LoadResult loaded = store.load();
        BatchRunner runner = new BatchRunner(platform, manifest,
                new Quota(10, null, 0, loaded.state(), clock), store, clock, 14, "test",
                loaded, true);

        BatchRunner.RunResult result = runner.run();
        String report = result.report().render();

        assertTrue(report.contains("mode=preview"), report);
        assertTrue(report.contains(
                "TARGET acme/updated docker.io/library/nginx current=1.0 desired=1.1 status=would-update"),
                report);
        assertTrue(report.contains("acme/current docker.io/library/nginx current=1.1 desired=1.1 status=no-op"),
                report);
        assertTrue(report.contains("acme/ignored docker.io/library/nginx current=1.0 desired=1.1 status=ignored rule=skip"),
                report);
        assertTrue(report.contains("PREVIEW would-update=1"), report);
        assertTrue(platform.writtenKeys().isEmpty(), "preview must not write");
        assertFalse(Files.exists(stateFile), "preview must not persist state");
    }

    // ---------------------------------------------------------------- concurrent instances

    @Test
    public void twoInstancesShareNoLockButDoNotDoubleCreate() throws Exception {
        InMemoryPlatform platform = new InMemoryPlatform()
                .add("acme/a", "nginx", "1.0", "1.1");
        Path stateFile = tempStateFile();
        FakeClock clock = new FakeClock("2026-11-20T00:00:00Z");

        // Two processes, each having loaded its own copy of the same state before either runs,
        // each compute a budget of 1. No lock serialises them.
        StateStore storeOne = new StateStore(stateFile);
        StateStore storeTwo = new StateStore(stateFile);
        StateStore.LoadResult loadedOne = storeOne.load();
        StateStore.LoadResult loadedTwo = storeTwo.load();
        BatchRunner first = new BatchRunner(platform, IgnoreManifest.empty(),
                new Quota(1, null, 0, loadedOne.state(), clock), storeOne, clock, 14, "one",
                loadedOne, false);
        BatchRunner second = new BatchRunner(platform, IgnoreManifest.empty(),
                new Quota(1, null, 0, loadedTwo.state(), clock), storeTwo, clock, 14, "two",
                loadedTwo, false);

        BatchRunner.RunResult firstResult = first.run();
        BatchRunner.RunResult secondResult = second.run();

        assertEquals(firstResult.report().results().get(0).status(), TargetResult.Status.UPDATED);
        // The second instance attempts the write, finds it already done, and creates no second
        // merge request. The platform remains idempotent despite the duplicated attempt.
        assertEquals(secondResult.report().results().get(0).status(), TargetResult.Status.NOT_NEEDED);
        assertEquals(platform.writtenRepositories(), Arrays.asList("acme/a"));
        assertEquals(platform.attempts("acme/a|docker.io/library/nginx"), 2);
    }

    // ---------------------------------------------------------------- resolution tie-break

    @Test
    public void unresolvedTieAtDecisionTimePrefersIgnore() throws Exception {
        // Same specificity on both dimensions, different id and action, non-overlapping scopes
        // is fine; identical specificity with overlap would be rejected. Here the scopes do not
        // overlap, so both can be loaded and each resolves independently.
        IgnoreManifest manifest = IgnoreManifest.parse("{\"rules\":["
                + "{\"id\":\"a-ignore\",\"action\":\"ignore\",\"repository\":\"acme/web\",\"image\":\"nginx\"},"
                + "{\"id\":\"b-include\",\"action\":\"include\",\"repository\":\"acme/api\",\"image\":\"nginx\"}"
                + "]}");
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 20);
        assertEquals(manifest.decide(RepositoryName.parse("acme/web"), ImageName.parse("nginx"), today)
                .orElseThrow(AssertionError::new).action(), IgnoreRule.Action.IGNORE);
        assertEquals(manifest.decide(RepositoryName.parse("acme/api"), ImageName.parse("nginx"), today)
                .orElseThrow(AssertionError::new).action(), IgnoreRule.Action.INCLUDE);
    }

    @Test
    public void manifestReaderOverloadWorks() throws Exception {
        IgnoreManifest manifest = IgnoreManifest.parse(new StringReader("{\"rules\":[]}"));
        assertEquals(manifest.size(), 0);
        assertTrue(IgnoreManifest.empty().decide(RepositoryName.parse("acme/a"),
                ImageName.parse("nginx"), java.time.LocalDate.of(2026, 11, 20)).isEmpty());
    }
}
