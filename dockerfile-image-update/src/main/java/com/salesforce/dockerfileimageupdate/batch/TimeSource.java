/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Clock abstraction used for rule expiry and quota windows. Injecting the clock keeps tests
 * independent of wall-clock time.
 */
@FunctionalInterface
public interface TimeSource {
    /**
     * @return current instant
     */
    Instant now();

    /**
     * @return current date in UTC, used for rule expiry
     */
    default LocalDate today() {
        return LocalDate.ofInstant(now(), ZoneOffset.UTC);
    }

    /**
     * @return a source backed by the system clock
     */
    static TimeSource system() {
        return Instant::now;
    }

    /**
     * @param clock clock to adapt
     * @return a source backed by the given clock
     */
    static TimeSource of(Clock clock) {
        return clock::instant;
    }
}
