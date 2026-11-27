Feature: A lesson is mastered by solving its check puzzles on the first try
  A lesson gives me puzzles from my own games where I left a piece hanging: practice puzzles first, then a
  mastery check of two puzzles. I master the lesson only when I solve both check puzzles on my first and
  only try; a practice puzzle I can try as often as I like. Progress comes only from solving puzzles, and a
  lesson I have passed stays passed, whatever a later analysis changes.

  If I fail the check, I can retry it on two positions I have not seen in any lesson yet, never on
  positions I have already seen. Until there are two such positions, nothing changes and the lesson tells
  me how many more it needs. A check whose position a re-analysis removed cannot be passed any more; it is
  retried the same way.

  There are no lesson screens yet: these steps go through the lesson service. They move to the HTTP API
  with the lesson endpoints (#90).

  Background:
    Given I left a piece hanging in 5 positions that are ready as puzzles
    And in each of them Nxd4 is a correct answer and Nxe5 is wrong

  Scenario: Five positions fill lesson 1, and lesson 2 needs more
    Then lesson 1 can be started
    And lesson 1 is recommended next
    And lesson 2 needs 5 more positions

  Scenario: Solving both check puzzles on the first try masters the lesson
    Given I have started lesson 1
    Then lesson 1 has 3 practice puzzles and then 2 check puzzles
    When I answer the first check puzzle with Nxd4
    And I answer the second check puzzle with Nxd4
    Then lesson 1 is mastered

  Scenario: A check puzzle is answered only once
    Given I have started lesson 1
    And I have answered the first check puzzle with Nxe5
    When I answer the first check puzzle again, with Nxd4
    Then my second answer is refused, because a check puzzle is answered once
    And only my first answer to it is on record

  Scenario: A failed check without unused positions changes nothing
    Given I have started lesson 1
    When I answer the first check puzzle with Nxe5
    Then my move was wrong, and I see the line "d8g5 e5f7 g5g2" that refutes it
    When I answer the second check puzzle with Nxd4
    Then lesson 1 is still in progress, and no retry is available
    And a retry of lesson 1 needs 2 more positions
    When I start lesson 1 again
    Then lesson 1 still has the same 5 puzzles

  Scenario: A failed check is retried on two positions I have not seen
    Given I have started lesson 1
    And I have failed its check
    When I left a piece hanging in 2 new positions that are ready as puzzles
    Then a retry of lesson 1 is available
    When I start lesson 1 again
    Then lesson 1 has a new check of 2 puzzles on the new positions
    When I answer the first check puzzle with Nxd4
    And I answer the second check puzzle with Nxd4
    Then lesson 1 is mastered

  Scenario: A passed lesson stays passed after a re-analysis
    Given I have started lesson 1
    And I have passed its check
    When a re-analysis removes the findings behind all its puzzles
    Then lesson 1 is still mastered

  Scenario: A check puzzle removed by a re-analysis voids the check
    Given I have started lesson 1
    When a re-analysis removes the finding behind the second check puzzle
    And I answer the first check puzzle with Nxd4
    Then lesson 1 is still in progress, and no retry is available
    And a retry of lesson 1 needs 2 more positions
    When I left a piece hanging in 2 new positions that are ready as puzzles
    And I start lesson 1 again
    Then lesson 1 has a new check of 2 puzzles on the new positions
    When I answer the first check puzzle with Nxd4
    And I answer the second check puzzle with Nxd4
    Then lesson 1 is mastered
