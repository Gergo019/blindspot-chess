Feature: When a repeated mistake counts as a pattern
  BlindSpot calls a mistake a pattern only with enough evidence: it must have happened in at least
  3 different games among my last 20 analysed rapid games. A game counts once, however often I made
  the mistake in it. The report always says how many games it looked at, also when there is no
  pattern yet, so I can judge the evidence myself.

  Scenario Outline: Three different games make a pattern, two do not
    Given I have played 20 rapid games that have been analysed
    And I left a piece hanging in my <games> newest games
    When I look at my blind-spot report
    Then it says I left a piece hanging in <games> of my last 20 rapid games
    And it <verdict> it a pattern

    Examples:
      | games | verdict       |
      | 2     | does not call |
      | 3     | calls         |

  Scenario: Two mistakes in the same game count once
    Given I have played 20 rapid games that have been analysed
    And I left a piece hanging twice in my newest game
    And I left a piece hanging in my second newest game
    When I look at my blind-spot report
    Then it says I left a piece hanging in 2 of my last 20 rapid games
    And it does not call it a pattern

  Scenario: Only the last 20 analysed rapid games count
    Given I have played 20 rapid games that have been analysed
    And I have also played an older rapid game that has been analysed
    And I left a piece hanging in my 2 newest games
    And I left a piece hanging in that older game
    When I look at my blind-spot report
    Then it says I left a piece hanging in 2 of my last 20 rapid games
    And it does not call it a pattern

  Scenario: Blitz games and games that are not analysed do not count
    Given I have played 20 rapid games that have been analysed
    And I left a piece hanging in my 2 newest games
    And I left a piece hanging in a blitz game
    And I left a piece hanging in a rapid game that has not been analysed yet
    And I left a piece hanging in a rapid game whose analysis failed
    When I look at my blind-spot report
    Then it says I left a piece hanging in 2 of my last 20 rapid games
    And it does not call it a pattern

  Scenario Outline: The report always says how many games it looked at
    Given I have played <played> rapid games that have been analysed
    And I left a piece hanging in my <games> newest games
    When I look at my blind-spot report
    Then it says I left a piece hanging in <games> of my last <looked at> rapid games
    And it <verdict> it a pattern

    Examples:
      | played | games | looked at | verdict       |
      | 7      | 3     | 7         | calls         |
      | 4      | 1     | 4         | does not call |
      | 25     | 3     | 20        | calls         |
      | 0      | 0     | 0         | does not call |
