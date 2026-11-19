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
 * A {@link TimeSource} whose value only changes when a test advances it.
 */
public final class FakeClock implements TimeSource {
    private Instant current;

    public FakeClock(Instant start) {
        this.current = start;
    }

    /**
     * @param startIso ISO-8601 instant to start at
     */
    public FakeClock(String startIso) {
        this(Instant.parse(startIso));
    }

    @Override
    public Instant now() {
        return current;
    }

    /**
     * @param duration amount to move forward
     * @return this clock
     */
    public FakeClock advance(Duration duration) {
        current = current.plus(duration);
        return this;
    }

    /**
     * @param days days to move forward, keeping the time of day
     * @return this clock
     */
    public FakeClock advanceDays(long days) {
        return advance(Duration.ofDays(days));
    }

    public void set(Instant instant) {
        this.current = instant;
    }
}
