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
import java.util.function.Consumer;
import java.util.logging.*;
import java.util.logging.Level;

class Main {

    private static Logger LOGGER = Logger.getLogger("bot");

    // Prepare queues for ongoing games and incoming challenges.
    BlockingQueue<GameInfo>              gamesToStart = new ArrayBlockingQueue<>(64);
    BlockingQueue<GameInfo>              ongoingGames = new ArrayBlockingQueue<>(64);
    BlockingQueue<ChallengeCreatedEvent> challenges   = new ArrayBlockingQueue<>(64);

    ExecutorService executor = Executors.newCachedThreadPool();

    public static void main(String[] args) {
        new Main().run();
    }

    void run() {

        if (! (ClientAndProfile.initialize() instanceof ClientAndProfile(var client, var profile))) return;

        executor.submit(() -> {
            while(true) {
                try {
                    // Connect the BOT to Lichess
                    var connectResult = client.bot().connect();

                    // Check for failure
                    if (! (connectResult instanceof Entries(var events))) {
                        LOGGER.warning(() -> "Failed to connect: %s".formatted(connectResult));
                        TimeUnit.SECONDS.sleep(60);
                        continue;
                    }

                    // Listen for game start events and incoming challenges,
                    // and just put them in the queues ("Producer")
                    events.forEach(event -> { switch(event) {
                            case ChallengeCreatedEvent created -> challenges.add(created);
                            case GameStartEvent(var game, _) when
                                ongoingGames.stream()
                                .map(GameInfo::gameId)
                                .noneMatch(game.gameId()::equals) -> gamesToStart.add(game);
                            case GameStopEvent _,
                                 GameStartEvent _,
                                 ChallengeCanceledEvent _,
                                 ChallengeDeclinedEvent _ -> LOGGER.fine(() -> "%s".formatted(event));
                        }});

                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, e, () -> "EventStream: %s\n%s".formatted(e.getMessage(), Instant.now()));
                }
            }
        });


        // Accept or Decline incoming challenges ("Consumer")
        executor.submit(() -> {
            while(true) {
                try {
                    var challenge = challenges.take();
                    ChallengeInfo c = challenge.challenge();

                    var challenger = c.players().challengerOpt().orElseThrow();

                    if (c.rules().contains("noAbort")) {
                        LOGGER.info(() -> "Declining \"noAbort\" challenge from %s: %s".formatted(challenger.user().name(), challenge));
                        client.challenges().declineChallenge(challenge.id(), d -> d.generic());
                        continue;
                    }

                    if (c.gameType().rated()) {
                        LOGGER.info(() -> "Declining rated challenge from %s: %s".formatted(challenger.user().name(), challenge));
                        client.challenges().declineChallenge(challenge.id(), d -> d.casual());
                        continue;
                    }

                    if (c.gameType().variant() != VariantType.Variant.standard
                        && ! (c.gameType().variant() instanceof VariantType.Chess960)
                        && ! (c.gameType().variant() instanceof VariantType.FromPosition)) {
                        LOGGER.info(() -> "Declining non-standard challenge from %s: %s".formatted(challenger.user().name(), challenge));
                        client.challenges().declineChallenge(challenge.id(), d -> d.standard());
                        continue;
                    }

                    boolean alreadyPlayingSameOpponent = ongoingGames.stream()
                            .anyMatch(game -> challenger.user().id().equals(game.opponent().id()));
                    if (alreadyPlayingSameOpponent) {
                        LOGGER.info(() -> "Declining challenge from %s - already playing: %s".formatted(challenger.user().name(), challenge));
                        client.challenges().declineChallenge(challenge.id(), d -> d.later());
                        continue;
                    }

                    if (ongoingGames.size() >= 14) {
                        LOGGER.info(() -> "Declining too many games from %s: %s".formatted(challenger.user().name(), challenge));
                        client.challenges().declineChallenge(challenge.id(), d -> d.later());
                        continue;
                    }

                    var acceptResult = client.challenges().acceptChallenge(challenge.id());

                    if (acceptResult instanceof Fail<?> f) {
                        LOGGER.warning(() -> "Failed (%s) to accept %s!".formatted(f, challenge));
                        client.challenges().declineChallenge(challenge.id(), d -> d.generic());
                        continue;
                    }

                    LOGGER.info(() -> "Accepted from %s: %s".formatted(challenger.user().name(), challenge));

                    var greeting = challenge.isRematch()
                        ? "Again!"
                        : """
                          Hi %s!
                          I wish you a good game!
                          """.formatted(challenger.user().name());
                     executor.submit(() -> {
                         try { Thread.sleep(Duration.ofSeconds(1));} catch (InterruptedException _) {}
                         client.bot().chat(challenge.id(), greeting);
                     });
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, e, () -> "ChallengeAcceptor: %s\n%s".formatted(e.getMessage(), Instant.now()));
                }
            }
        });

        // Create handler for started games ("Consumer")
        executor.submit(() -> {
            while(true) {
                try {
                    var game = gamesToStart.take();
                    ongoingGames.offer(game);

                    String fenAtGameStart = game.fen();
                    String opponent = game.opponent().name();

                    var white = game.color() == Color.white ? profile.name() : opponent;
                    var black = game.color() == Color.black ? profile.name() : opponent;

                    executor.submit(() -> { try {

                        Consumer<String> processMoves = moves -> {
                            var board = moves.isBlank()
                                ? Board.fromFEN(fenAtGameStart)
                                : Board.fromFEN(fenAtGameStart).play(moves);

                            if (game.color() == Color.white
                                ? board.blackToMove()
                                : board.whiteToMove()) return;

                            var validMoves = new ArrayList<>(board.validMoves());

                            Collections.shuffle(validMoves, new Random());
                            var result = validMoves.stream().map(m -> m.uci())
                                .findFirst().map(uci -> {
                                    //var updatedBoard = board.play(uci);
                                    //boolean draw = updatedBoard.gameState() == GameState.draw_by_threefold_repetition
                                    //    || updatedBoard.gameState() == GameState.draw_by_fifty_move_rule;
                                    //return client.bot().handleDrawOffer(game.gameId(), true);
                                    return client.bot().move(game.gameId(), uci);
                                })
                            .orElse(One.fail(-1, Err.from("no move")));

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
                                    var moveList = state.moveList();
                                    moveList = moveList.subList(movesPlayedSinceStart.get(), moveList.size());
                                    int moves = moveList.size();
                                    if (moves > 0) {
                                        var board = Board.fromFEN(fenAtGameStart);
                                        if (moves > 1) board = board.play(String.join(" ", moveList.subList(0, moves-1)));

                                        String lastMove = moveList.getLast();
                                        var playedMove = "%s (%s) played by %s".formatted(lastMove, board.toSAN(lastMove),
                                                board.whiteToMove() ? (white + " (w)") : (black + " (b)"));

                                        board = board.play(lastMove);

                                        var currentState = "%s %s %s".formatted(board.toFEN(), board.gameState(), state.status());

                                        LOGGER.info("%s\n%s".formatted(playedMove, currentState));
                                    }

                                    if (state.status().ordinal() > Status.started.ordinal()) {
                                        client.bot().chat(game.gameId(), "Thanks for the game!");
                                        if (state.winner() instanceof Some(var winner)) {
                                            LOGGER.info(() -> "Winner: %s".formatted(winner == Color.white ? white : black));
                                        } else {
                                            LOGGER.info(() -> "No winner: %s".formatted(state.status()));
                                        }
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

                        ongoingGames.remove(game);

                    } catch (Exception e) {
                        ongoingGames.remove(game);
                    }});
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, e, () -> "GameAcceptor: %s\n%s".formatted(e.getMessage(), Instant.now()));
                }
            }
        });
    }
}
