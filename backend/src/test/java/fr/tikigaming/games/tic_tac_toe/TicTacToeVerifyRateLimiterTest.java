package fr.tikigaming.games.tic_tac_toe;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Limite le nombre de vérifications par minute et par client (OWASP API4). Horloge injectée : on avance le temps
 * sans attendre une vraie minute.
 */
class TicTacToeVerifyRateLimiterTest {

    /** Horloge réglable à la main pour les tests. */
    static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-11-08T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final ManualClock clock = new ManualClock();

    @Test
    void acceptsUpToTheLimitThenRefusesTheSameClient() {
        // Arrange
        TicTacToeVerifyRateLimiter limiter = new TicTacToeVerifyRateLimiter(2, clock);

        // Act + Assert
        assertThat(limiter.tryAcquire("203.0.113.7")).isTrue();
        assertThat(limiter.tryAcquire("203.0.113.7")).isTrue();
        assertThat(limiter.tryAcquire("203.0.113.7")).isFalse();
    }

    @Test
    void theLimitResetsAfterAMinute() {
        // Arrange
        TicTacToeVerifyRateLimiter limiter = new TicTacToeVerifyRateLimiter(1, clock);
        limiter.tryAcquire("203.0.113.7");

        // Act
        clock.advance(Duration.ofMinutes(1));

        // Assert
        assertThat(limiter.tryAcquire("203.0.113.7")).isTrue();
    }

    @Test
    void anotherClientIsNotAffected() {
        // Arrange
        TicTacToeVerifyRateLimiter limiter = new TicTacToeVerifyRateLimiter(1, clock);
        limiter.tryAcquire("203.0.113.7");

        // Act + Assert
        assertThat(limiter.tryAcquire("198.51.100.9")).isTrue();
        assertThat(limiter.tryAcquire("203.0.113.7")).isFalse();
    }
}
