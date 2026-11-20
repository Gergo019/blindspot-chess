Feature: Only mistakes BlindSpot can detect are reported
  BlindSpot looks for a kind of mistake only once it has a detector for it. Until then the report
  leaves that kind out completely. Saying "in 0 of your last 20 games" would read as "you never make
  this mistake", although nobody looked.

  Scenario: Allowed knight forks are not reported while there is no knight-fork detector
    Given I have played 20 rapid games that have been analysed
    And allowed knight forks are on record in my 3 newest games
    When I look at my blind-spot report
    Then it does not mention allowed knight forks
    And it says I left a piece hanging in 0 of my last 20 rapid games

  Scenario: Hanging pieces are reported because they have a detector, even when there are none
    Given I have played 20 rapid games that have been analysed
    When I look at my blind-spot report
    Then it says I left a piece hanging in 0 of my last 20 rapid games
    And it does not call it a pattern
