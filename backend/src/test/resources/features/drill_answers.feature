Feature: A drill accepts every good-enough move
  A drill shows me the position from my own game where I left a piece hanging, and asks me to find a
  safe move. The engine has judged every legal move of that position beforehand. A move counts as
  correct when it loses less than 0.1 of my winning chances, so the engine's first choice is not the
  only right answer. The verdict is looked up, never guessed, so a wrong answer always comes with the
  line that refutes it. A move that is not legal in the position is refused and does not count.

  There is no drill screen yet: these steps answer through the training service. They move to the
  HTTP API with the lesson screens (ADR 0008).

  Background:
    Given I left a piece hanging with Nxe5 in one of my games
    And a drill was prepared from that mistake, with the engine's verdicts:
      | move | as   | loses  | line after it  |
      | f3d4 | Nxd4 | 0      | c6d4 c2c3      |
      | b1c3 | Nc3  | 0.0999 | d4f3 d1f3      |
      | d2d3 | d3   | 0.1    | d4f3 d1f3      |
      | f3e5 | Nxe5 | 0.6    | d8g5 e5f7 g5g2 |

  Scenario Outline: A move is correct when it loses less than 0.1
    When I answer the drill with <move>
    Then my answer is <result>

    Examples:
      | move | result  |
      | Nxd4 | correct |
      | Nc3  | correct |
      | d3   | wrong   |
      | Nxe5 | wrong   |

  Scenario: Repeating my mistake is wrong, and I see how it is refuted
    When I answer the drill with Nxe5
    Then my answer is wrong
    And I see the line "d8g5 e5f7 g5g2" that refutes it
    And I see that Nxd4 and Nc3 would have been correct, with Nxd4 as the engine's first choice

  Scenario: A move that is not legal in the position is refused
    When I answer the drill with the move "e2e5", which is not legal there
    Then my answer is refused as not a legal move
    And no answer to the drill is on record
    And the drill is still due now

  Scenario: Correct answers bring the position back later and later
    When I answer the drill with Nxd4
    And I answer the drill with Nc3
    Then the drill comes back in 6 days

  Scenario: A wrong answer brings the position back the next day
    Given I have answered the drill correctly twice
    When I answer the drill with Nxe5
    Then the drill comes back in 1 day
