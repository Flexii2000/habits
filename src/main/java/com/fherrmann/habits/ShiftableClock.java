package com.fherrmann.habits;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Die Uhr des Dienstes: die Systemzeit, lokal fuer Demo-Daten verschiebbar.
 *
 * <p>Verschoben wird nur ueber {@code /cohabit/api/dev/clock}, und den gibt es nur mit
 * {@code cohabit.dev.enabled=true} (run-backend.sh) - auf dem Server steht der
 * Versatz fest auf null.
 */
public class ShiftableClock extends Clock {

    private final Clock base;
    private volatile Duration offset = Duration.ZERO;

    public ShiftableClock(Clock base) {
        this.base = base;
    }

    public Duration offset() {
        return offset;
    }

    public void setOffset(Duration offset) {
        this.offset = offset == null ? Duration.ZERO : offset;
    }

    @Override
    public ZoneId getZone() {
        return base.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        ShiftableClock outer = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId z) {
                return outer.withZone(z);
            }

            @Override
            public Instant instant() {
                return outer.instant();
            }
        };
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset);
    }
}
