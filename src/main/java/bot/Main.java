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
                        LOGGER.warning(() -> STR."Failed to connect: \{connectResult}");
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
                                 ChallengeDeclinedEvent _ -> LOGGER.fine(() -> STR."\{event}");
                        }});

                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, e, () -> STR."EventStream: \{e.getMessage()}\n\{Instant.now()}");
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
                        LOGGER.info(() -> STR."Declining \"noAbort\" challenge from \{challenger.user().name()}: \{challenge}");
                        client.challenges().declineChallenge(challenge.id(), d -> d.generic());
                        continue;
                    }

                    if (c.gameType().rated()) {
                        LOGGER.info(() -> STR."Declining rated challenge from \{challenger.user().name()}: \{challenge}");
                        client.challenges().declineChallenge(challenge.id(), d -> d.casual());
                        continue;
                    }

                    if (c.gameType().variant() != VariantType.Variant.standard
                        && ! (c.gameType().variant() instanceof VariantType.Chess960)
                        && ! (c.gameType().variant() instanceof VariantType.FromPosition)) {
                        LOGGER.info(() -> STR."Declining non-standard challenge from \{challenger.user().name()}: \{challenge}");
                        client.challenges().declineChallenge(challenge.id(), d -> d.standard());
                        continue;
                    }

                    boolean alreadyPlayingSameOpponent = ongoingGames.stream()
                            .anyMatch(game -> challenger.user().id().equals(game.opponent().id()));
                    if (alreadyPlayingSameOpponent) {
                        LOGGER.info(() -> STR."Declining challenge from \{challenger.user().name()} - already playing: \{challenge}");
                        client.challenges().declineChallenge(challenge.id(), d -> d.later());
                        continue;
                    }

                    if (ongoingGames.size() >= 14) {
                        LOGGER.info(() -> STR."Declining too many games from \{challenger.user().name()}: \{challenge}");
                        client.challenges().declineChallenge(challenge.id(), d -> d.later());
                        continue;
                    }

                    var acceptResult = client.challenges().acceptChallenge(challenge.id());

                    if (acceptResult instanceof Fail<?> f) {
                        LOGGER.warning(() -> STR."Failed (\{f}) to accept \{challenge}!");
                        continue;
                    }

                    LOGGER.info(() -> STR."Accepted from \{challenger.user().name()}: \{challenge}");

                    var greeting = challenge.isRematch()
                        ? "Again!"
                        : STR."""
                          Hi \{challenger.user().name()}!
                          I wish you a good game!
                          """;
                     executor.submit(() -> {
                         try { Thread.sleep(Duration.ofSeconds(1));} catch (InterruptedException _) {}
                         client.bot().chat(challenge.id(), greeting);
                     });
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, e, () -> STR."ChallengeAcceptor: \{e.getMessage()}\n\{Instant.now()}");
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
                                LOGGER.warning(() -> STR."Play failed: \{fail} - resigning");
                                client.bot().resign(game.gameId());
                            }
                        };

                        LOGGER.fine(() -> STR."Connecting to game: \{game}");

                        final AtomicInteger movesPlayedSinceStart = new AtomicInteger();

                        client.bot().connectToGame(game.gameId()).stream()
                            .forEach(event -> { switch(event) {
                                case Full full -> {
                                    LOGGER.info(() -> STR."FULL: \{full}");
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

                                        var playedMove = STR."\{lastMove} (\{board.toSAN(lastMove)}) played by \{
                                                board.whiteToMove() ? (white + " (w)") : (black + " (b)") }";

                                        board = board.play(lastMove);

                                        var currentState = STR."\{board.toFEN()} \{board.gameState()} \{state.status()}";

                                        LOGGER.info(STR."\{playedMove}\n\{currentState}");
                                    }

                                    if (state.status().ordinal() > Status.started.ordinal()) {
                                        client.bot().chat(game.gameId(), "Thanks for the game!");
                                        if (state.winner() instanceof Some(var winner)) {
                                            LOGGER.info(() -> STR."Winner: \{winner == Color.white ? white : black}");
                                        } else {
                                            LOGGER.info(() -> STR."No winner: \{state.status()}");
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

                                case OpponentGone gone                  -> LOGGER.info(() -> STR."Gone: \{gone}");
                                case Chat(var name, var text, var room) -> LOGGER.info(() -> STR."Chat: [\{name}][\{room}]: \{text}");
                            }});

                        LOGGER.fine(() -> STR."GameEvent handler for \{game.gameId()} finished");

                        ongoingGames.remove(game);

                    } catch (Exception e) {
                        ongoingGames.remove(game);
                    }});
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, e, () -> STR."GameAcceptor: \{e.getMessage()}\n\{Instant.now()}");
                }
            }
        });
    }
}
