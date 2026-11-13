"""Short, fixed games used by the tests. Every game here is legal and finished."""

# 2. Qg4?? hangs the queen to the c8 bishop (the d-pawn has left d7).
HANGS_QUEEN = "1. e4 d5 2. Qg4 Bxg4 0-1"
HANGS_QUEEN_PLY = 3

# Fool's mate: 2. g4?? allows Qh4#, the final move.
FOOLS_MATE = "1. f3 e5 2. g4 Qh4# 0-1"

# Blackburne Shilling trap, ends 7...Nf3#.
BLACKBURNE_SHILLING = (
    "1. e4 e5 2. Nf3 Nc6 3. Bc4 Nd4 4. Nxe5 Qg5 5. Nxf7 Qxg2 "
    "6. Rf1 Qxe4+ 7. Be2 Nf3# 0-1"
)

# Sam Loyd's ten-move stalemate: 10. Qe6 leaves Black with no legal move.
LOYD_STALEMATE = (
    "1. e3 a5 2. Qh5 Ra6 3. Qxa5 h5 4. h4 Rah6 5. Qxc7 f6 6. Qxd7+ Kf7 "
    "7. Qxb7 Qd3 8. Qxb8 Qh7 9. Qxc8 Kg6 10. Qe6 1/2-1/2"
)


def knight_shuffle(plies: int, result: str = "1/2-1/2") -> str:
    """A legal game of knights moving out and back, ``plies`` half-moves long."""
    cycle = ["Nf3", "Nf6", "Ng1", "Ng8"]
    moves = [cycle[i % 4] for i in range(plies)]
    parts = []
    for i, san in enumerate(moves):
        if i % 2 == 0:
            parts.append(f"{i // 2 + 1}. {san}")
        else:
            parts.append(san)
    return " ".join(parts) + f" {result}"
