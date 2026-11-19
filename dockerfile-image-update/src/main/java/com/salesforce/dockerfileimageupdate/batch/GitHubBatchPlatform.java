/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

import com.salesforce.dockerfileimageupdate.model.GitForkBranch;
import com.salesforce.dockerfileimageupdate.process.ForkableRepoValidator;
import com.salesforce.dockerfileimageupdate.process.GitHubPullRequestSender;
import com.salesforce.dockerfileimageupdate.storage.ImageTagStore;
import com.salesforce.dockerfileimageupdate.storage.ImageTagStoreContent;
import com.salesforce.dockerfileimageupdate.utils.Constants;
import com.salesforce.dockerfileimageupdate.utils.DockerfileGitHubUtil;
import com.salesforce.dockerfileimageupdate.utils.ImageStoreUtil;
import com.salesforce.dockerfileimageupdate.utils.PullRequests;
import net.sourceforge.argparse4j.inf.Namespace;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.PagedSearchIterable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link BatchPlatform} backed by the image tag store and GitHub.
 *
 * <p>Discovery expands each entry in the image tag store into the repositories that reference
 * that image. A write reuses the existing {@link GitHubPullRequestSender} /
 * {@link PullRequests} machinery to open a pull request for one repository.</p>
 *
 * <p>Opening a pull request is already idempotent on GitHub: re-running against an unchanged
 * repository produces either "no commits between" or "a pull request already exists", neither of
 * which creates a second request. This adapter reports {@code ALREADY_UP_TO_DATE} when the
 * repository query finds no content that needs the new tag.</p>
 */
public final class GitHubBatchPlatform implements BatchPlatform {
    private final DockerfileGitHubUtil dockerfileGitHubUtil;
    private final Namespace ns;

    public GitHubBatchPlatform(DockerfileGitHubUtil dockerfileGitHubUtil, Namespace ns) {
        this.dockerfileGitHubUtil = dockerfileGitHubUtil;
        this.ns = ns;
    }

    @Override
    public Iterable<BatchTarget> discoverTargets() throws PlatformException {
        String store = ns.get(Constants.STORE);
        try {
            ImageTagStore imageTagStore =
                    ImageStoreUtil.initializeImageTagStore(dockerfileGitHubUtil, store);
            List<ImageTagStoreContent> contents =
                    imageTagStore.getStoreContent(dockerfileGitHubUtil, store);
            List<BatchTarget> targets = new ArrayList<>();
            Map<String, Boolean> orgsToInclude = new HashMap<>();
            if (ns.get(Constants.GIT_ORG) != null) {
                orgsToInclude.put(ns.get(Constants.GIT_ORG), true);
            }
            for (ImageTagStoreContent content : contents) {
                ImageName image = ImageName.parse(content.getImageName());
                Optional<List<PagedSearchIterable<GHContent>>> found = dockerfileGitHubUtil
                        .findFilesWithImage(content.getImageName(), orgsToInclude,
                                ns.get(Constants.GIT_API_SEARCH_LIMIT),
                                ns.get(Constants.FILE_NAMES_TO_SEARCH));
                if (!found.isPresent()) {
                    continue;
                }
                for (PagedSearchIterable<GHContent> iterable : found.get()) {
                    for (GHContent ghContent : iterable) {
                        RepositoryName repository =
                                RepositoryName.parse(ghContent.getOwner().getFullName());
                        targets.add(new BatchTarget(repository, image,
                                currentTagOf(ghContent, content), content.getTag()));
                    }
                }
            }
            return targets;
        } catch (Exception e) {
            throw new PlatformException("Could not discover targets from store " + store, e);
        }
    }

    private static String currentTagOf(GHContent content, ImageTagStoreContent storeContent) {
        // The search result does not expose the tag cheaply; leave it unknown so that the
        // validator decides whether the repository actually needs a change.
        return null;
    }

    @Override
    public boolean needsUpdate(BatchTarget target) throws PlatformException {
        try {
            Optional<List<PagedSearchIterable<GHContent>>> found = dockerfileGitHubUtil
                    .findFilesWithImage(target.image().canonical(), Collections.emptyMap(),
                            ns.get(Constants.GIT_API_SEARCH_LIMIT),
                            ns.get(Constants.FILE_NAMES_TO_SEARCH));
            if (!found.isPresent()) {
                return false;
            }
            for (PagedSearchIterable<GHContent> iterable : found.get()) {
                for (GHContent content : iterable) {
                    if (target.repository().equals(
                            RepositoryName.parse(content.getOwner().getFullName()))) {
                        // The search returned this repository, so it references the image; the
                        // forkable validator will still skip it if the tag is already current.
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            throw new PlatformException(
                    "Could not inspect " + target.repository().canonical(), e);
        }
    }

    @Override
    public UpdateOutcome applyUpdate(BatchTarget target) throws PlatformException {
        try {
            GitForkBranch gitForkBranch = new GitForkBranch(target.image().canonical(),
                    target.desiredTag().orElse(null), ns.get(Constants.GIT_BRANCH),
                    ns.get(Constants.FILE_NAMES_TO_SEARCH));
            GitHubPullRequestSender sender = new GitHubPullRequestSender(dockerfileGitHubUtil,
                    new ForkableRepoValidator(dockerfileGitHubUtil),
                    ns.get(Constants.GIT_REPO_EXCLUDES));
            Optional<List<PagedSearchIterable<GHContent>>> found = dockerfileGitHubUtil
                    .findFilesWithImage(target.image().canonical(), Collections.emptyMap(),
                            ns.get(Constants.GIT_API_SEARCH_LIMIT),
                            ns.get(Constants.FILE_NAMES_TO_SEARCH));
            if (!found.isPresent()) {
                return UpdateOutcome.ALREADY_UP_TO_DATE;
            }
            boolean anyForked = false;
            for (PagedSearchIterable<GHContent> iterable : found.get()) {
                new PullRequests().prepareToCreate(ns, sender, iterable, gitForkBranch,
                        dockerfileGitHubUtil, null);
                anyForked |= iterable.getTotalCount() > 0;
            }
            return anyForked ? UpdateOutcome.UPDATED : UpdateOutcome.ALREADY_UP_TO_DATE;
        } catch (Exception e) {
            throw new PlatformException(
                    "Could not update " + target.repository().canonical(), e);
        }
    }
}
