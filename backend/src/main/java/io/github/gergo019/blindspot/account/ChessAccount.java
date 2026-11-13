package io.github.gergo019.blindspot.account;

import java.time.Instant;

import io.github.gergo019.blindspot.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A user's account on a chess platform. Games are imported through it.
 */
@Entity
@Table(name = "chess_accounts")
public class ChessAccount extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChessPlatform platform;

    @Column(name = "platform_username", nullable = false, length = 100)
    private String platformUsername;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    protected ChessAccount() {
        // for JPA
    }

    public ChessAccount(User user, ChessPlatform platform, String platformUsername) {
        this.user = user;
        this.platform = platform;
        this.platformUsername = platformUsername;
    }

    public void markSynced(Instant at) {
        this.lastSyncedAt = at;
    }

    public User getUser() {
        return user;
    }

    public ChessPlatform getPlatform() {
        return platform;
    }

    public String getPlatformUsername() {
        return platformUsername;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }
}
