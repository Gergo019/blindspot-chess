package io.github.gergo019.blindspot.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PatternRuleTest {

    @Test
    void threeDistinctGamesInTheLastTwentyMakeAPattern() {
        assertThat(PatternRule.WINDOW).isEqualTo(20);
        assertThat(PatternRule.THRESHOLD).isEqualTo(3);
        assertThat(PatternRule.isPattern(2)).isFalse();
        assertThat(PatternRule.isPattern(3)).isTrue();
        assertThat(PatternRule.isPattern(0)).isFalse();
    }
}
