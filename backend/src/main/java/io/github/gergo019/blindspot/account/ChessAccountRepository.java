package io.github.gergo019.blindspot.account;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChessAccountRepository extends JpaRepository<ChessAccount, Long> {

    List<ChessAccount> findByUserId(Long userId);

    Optional<ChessAccount> findByPlatformAndPlatformUsername(ChessPlatform platform, String platformUsername);
}
