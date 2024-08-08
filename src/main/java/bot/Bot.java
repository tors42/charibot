package bot;

import chariot.model.*;
import chariot.model.Enums.*;
import chariot.model.Event.*;
import chariot.model.GameStateEvent.*;
import chariot.util.Board;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.logging.Level;

record Bot(ClientAndAccount clientAndAccount, Map<String,String> games, Rules rules) {

    static final Logger LOGGER = Logger.getLogger("bot");

    public static void main(String[] args) {
        while (true) {
            try {
                if (ClientAndAccount.initialize().map(Bot::new) instanceof Some(var bot)) bot.run();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, e, () -> e.getMessage());
            } finally {
                var duration = Duration.ofSeconds(60);
                LOGGER.info(() -> "Retrying in %d seconds...".formatted(duration.toSeconds()));
                sleep(duration);
            }
        }
    }

    Bot(ClientAndAccount clientAndProfile) {
        this(clientAndProfile, new ConcurrentHashMap<>(), Rules.defaultRules());
    }

    void run() {
        try (var scope = new StructuredTaskScope<>()) {
            try {
                // Connect the BOT to Lichess
                Many<Event> events = clientAndAccount.client().bot().connect();

                // Check for network problems
                if (events instanceof Fail<?>) {
                    LOGGER.warning(() -> "Failed to connect: %s".formatted(events));
                    return;
                }

                // Listen for game start events and incoming challenges
                events.stream().forEach(event -> { switch(event) {
                    case ChallengeCreatedEvent created -> scope.fork(toCallable(() -> handleChallenge(created)));
                    case GameStartEvent(var game, _)   -> scope.fork(toCallable(() -> handleGame(game)));
                    case GameStopEvent _,
                         GameStartEvent _,
                         ChallengeCanceledEvent _,
                         ChallengeDeclinedEvent _ -> LOGGER.fine(() -> "%s".formatted(event));
                }});
            } finally {
                scope.shutdown();
                try { scope.join(); } catch (InterruptedException ie) {}
            }
        }
    }

    static Callable<Void> toCallable(Runnable runnable) { return () -> { runnable.run(); return null; }; }
    static void sleep(Duration duration) { try { Thread.sleep(duration); } catch (InterruptedException _) {} }

    void handleChallenge(ChallengeCreatedEvent event) {
        // Decline if unwanted challenges
        if (rules.decliner(event, games, clientAndAccount.client(), LOGGER) instanceof Some(Runnable decliner)) {
            decliner.run();
            return;
        }

        // Accept the challenge
        if (clientAndAccount.client().challenges().acceptChallenge(event.id()) instanceof Fail<?> f) {
            LOGGER.warning(() -> "Failed (%s) to accept %s!".formatted(f, event));
            clientAndAccount.client().challenges().declineChallenge(event.id(), d -> d.generic());
            return;
        }

        // Greet opponent
        String opponent = event.challenge().players().challengerOpt().map(p -> p.user().name()).orElse("Opponent");
        LOGGER.info(() -> "Accepted %s\n%s".formatted(event, opponent));
        sleep(Duration.ofSeconds(1));
        String greeting = event.isRematch()
            ? "Again!"
            : """
            Hi %s!
            I wish you a good game!
            """.formatted(opponent);
        clientAndAccount.client().bot().chat(event.id(), greeting);
    }

    void handleGame(GameInfo game) {
        var client = clientAndAccount.client();
        var account = clientAndAccount.account();
        games.put(game.opponent().id(), game.gameId());

        try {
            String fenAtGameStart = game.fen();

            Function<Color, String> nameByColor = color ->
                color == game.color() ? account.name() : game.opponent().name();

            Consumer<String> processMoves = moves -> {
                Board board = moves.isBlank()
                    ? Board.fromFEN(fenAtGameStart)
                    : Board.fromFEN(fenAtGameStart).play(moves);

                if (game.color() == Color.white
                        ? board.blackToMove()
                        : board.whiteToMove()) return;

                List<Board.Move> validMoves = new ArrayList<>(board.validMoves());

                Collections.shuffle(validMoves, new Random());
                One<?> result = validMoves.stream()
                    .map(m -> m.uci())
                    .findFirst()
                    .map(uci -> {
                        //var updatedBoard = board.play(uci);
                        //boolean draw = updatedBoard.gameState() == GameState.draw_by_threefold_repetition
                        //    || updatedBoard.gameState() == GameState.draw_by_fifty_move_rule;
                        //return client.bot().handleDrawOffer(game.gameId(), true);
                        return client.bot().move(game.gameId(), uci);
                    }).orElse(One.fail(-1, Err.from("no move")));

                if (result instanceof Fail<?> fail) {
                    LOGGER.warning(() -> "Play failed: %s - resigning".formatted(fail));
                    client.bot().resign(game.gameId());
                }
            };

            LOGGER.fine(() -> "Connecting to game: %s".formatted(game));

            final AtomicInteger movesPlayedSinceStart = new AtomicInteger();

            client.bot().connectToGame(game.gameId()).stream()
                .forEach(event -> { switch(event) {
                    case Full full -> {
                        LOGGER.info(() -> "FULL: %s".formatted(full));
                        movesPlayedSinceStart.set(full.state().moveList().size());
                        processMoves.accept("");
                    }

                    case State state -> {
                        List<String> moveList = state.moveList();
                        moveList = moveList.subList(movesPlayedSinceStart.get(), moveList.size());
                        int moves = moveList.size();
                        if (moves > 0) {
                            Board board = Board.fromFEN(fenAtGameStart);
                            if (moves > 1) board = board.play(String.join(" ", moveList.subList(0, moves-1)));
                            String lastMove = moveList.getLast();

                            String infoBeforeMove = "%s (%s) played (%s - %s)".formatted(
                                    lastMove,
                                    board.toSAN(lastMove),
                                    nameByColor.apply(Color.white) + (board.whiteToMove() ? "*" : ""),
                                    nameByColor.apply(Color.black) + (board.blackToMove() ? "*" : ""));

                            board = board.play(lastMove);

                            String infoAfterMove = "%s %s %s".formatted(
                                    board.toFEN(),
                                    board.gameState(),
                                    state.status());

                            LOGGER.info("%s\n%s".formatted(infoBeforeMove, infoAfterMove));
                        }

                        if (state.status().ordinal() > Status.started.ordinal()) {
                            client.bot().chat(game.gameId(), "Thanks for the game!");
                            LOGGER.info(() -> state.winner() instanceof Some(var winner)
                                    ? "Winner: %s".formatted(nameByColor.apply(winner))
                                    : "No winner: %s".formatted(state.status()));
                            break;
                        }

                        if (state.drawOffer() instanceof Some(var color)
                            && color != game.color()) {
                            client.bot().handleDrawOffer(game.gameId(), true);
                            break;
                        }

                        processMoves.accept(String.join(" ", moveList));
                    }

                    case OpponentGone gone                  -> LOGGER.info(() -> "Gone: %s".formatted(gone));
                    case Chat(var name, var text, var room) -> LOGGER.info(() -> "Chat: [%s][%s]: %s".formatted(name, room, text));
                }});
            LOGGER.fine(() -> "GameEvent handler for %s finished".formatted(game.gameId()));
        } finally {
            games.remove(game.opponent().id());
        }
    }
}
