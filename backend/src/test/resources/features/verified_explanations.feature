Feature: Only verified explanations are shown
  An explanation reaches me only if it is a template filled from checked facts and it passed
  verification. Text that failed verification, is not verified yet, or was written by an AI model is
  never shown, even when it exists. Then I see the mistake and its moves without an explanation.

  Background:
    Given I have played 1 rapid game that has been analysed
    And I left a piece hanging in my newest game

  Scenario: A template explanation that passed verification is shown
    Given that mistake has a template explanation "Your rook on b6 was left undefended." whose verification passed
    When I open that mistake
    Then I see the explanation "Your rook on b6 was left undefended."

  Scenario Outline: Any other explanation is not shown
    Given that mistake has a <kind> explanation "<text>" whose verification <verification>
    When I open that mistake
    Then I see no explanation

    Examples:
      | kind          | text                                   | verification     |
      | template      | The rook was safe on b6.               | failed           |
      | template      | Your rook on b6 was left undefended.   | is still pending |
      | facts-only AI | Your rook on b6 had no defender.       | passed           |
      | free AI       | You blundered the exchange on move 18. | passed           |

  Scenario: Only the verified template is shown when there are several explanations
    Given that mistake has a facts-only AI explanation "AI text that passed." whose verification passed
    And that mistake has a template explanation "Template text that failed." whose verification failed
    And that mistake has a template explanation "Your rook on b6 was left undefended." whose verification passed
    When I open that mistake
    Then I see the explanation "Your rook on b6 was left undefended."

  Scenario: Without any explanation the mistake is still shown
    When I open that mistake
    Then I see no explanation
    And I see the moves that prove the mistake
