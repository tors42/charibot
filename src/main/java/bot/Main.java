package bot;

import chariot.*;
import chariot.model.*;
import chariot.model.Enums.*;
import chariot.model.Event.*;
import chariot.model.Event.GameEvent;
import chariot.model.GameEvent.*;
import chariot.util.Board;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

class Main {

    public static void main(String[] args) {

        var token = System.getenv("BOT_TOKEN"); // scope bot:play

        if (token == null) {
            System.out.println("Missing BOT_TOKEN environment variable");
            return;
        }

        var client = Client.auth(token);

        var profile = client.account().profile();
        if (! (profile instanceof Entry<User> one)) {
            System.out.println("Failed to lookup account " + profile);
            return;
        }

        // Prepare queues for ongoing games and incoming challenges.
        var gamesToStart = new ArrayBlockingQueue<GameEvent>(64);
        var challenges   = new ArrayBlockingQueue<ChallengeEvent>(64);;
        var ongoingGames = new ArrayBlockingQueue<GameEvent>(64);


        // Listen for game start events and incoming challenges,
        // and just put them in the queues ("Producer")
        var executor = Executors.newCachedThreadPool();
        executor.submit(() -> {
            while(true) {
                try {
                    // Connect the BOT to Lichess
                    var connect = client.bot().connect();

                    // Check for failure
                    if (connect instanceof Fail<?> f) {
                        System.out.println("Failed to connect " + f);
                        TimeUnit.SECONDS.sleep(15);
                        continue;
                    }

                    connect.stream().forEach(event -> { switch(event.type()) {
                        case challenge -> challenges.add((ChallengeEvent) event);
                        case gameStart -> gamesToStart.add((GameEvent) event);
                        default -> {}
                    }});

                } catch(Exception e) {
                    System.out.println("EventStream: %s\n%s".formatted(e.getMessage(), Instant.now()));
                }
            }
        });


        // Accept or Decline incoming challenges ("Consumer")
        executor.submit(() -> {
            while(true) {
                try {
                    var challenge = challenges.take();
                    Challenge c = challenge.challenge();

                    System.out.println("Challenge from " + c.challenger());

                    if (c.rated()) {
                        System.out.println("Declinining challenge because rated,\n%s".formatted(challenge));
                        client.challenges().declineChallenge(challenge.id(), d -> d.casual());
                        continue;
                    }
                    if (!c.variant().key().equals(GameVariant.standard)) {
                        System.out.println("Declinining challenge because not standard,\n%s".formatted(challenge));
                        client.challenges().declineChallenge(challenge.id(), d -> d.standard());
                        continue;
                    }

                    boolean alreadyPlayingSameOpponent = ongoingGames.stream()
                        .anyMatch(g -> g.game().opponent() instanceof GameEvent.Opponent.User u
                                && u.id().equals(c.challenger().id()));

                    if (alreadyPlayingSameOpponent) {
                        System.out.println("Declinining challenge because already playing same opponent,\n%s".formatted(challenge));
                        client.challenges().declineChallenge(challenge.id(), d -> d.later());
                        continue;
                    }

                    if (ongoingGames.size() >= 7) {
                        System.out.println("Declinining challenge because ongoing games,\n%s".formatted(challenge));
                        client.challenges().declineChallenge(challenge.id(), d -> d.later());
                        continue;
                    }

                    System.out.println("Accepting challenge from %s,\n%s".formatted(c.challenger().name(),  challenge));
                    client.challenges().acceptChallenge(challenge.id());
                    client.bot().chat(challenge.id(), """
                            Hello!
                            I haven't existed for long,
                            so I might contain some bugs...
                            Let us hope things will go smooth.
                            I wish you a good game!
                            """);
                } catch (Exception e) {
                    System.out.println("ChallengeAcceptor: %s\n%s".formatted(e.getMessage(), Instant.now()));
                }
            }
        });

        // Create handler for started games ("Consumer")
        executor.submit(() -> {
            while(true) {
                try {
                    var game = gamesToStart.take();

                    String opponent = "<Opponent>";
                    if (game.game().opponent() instanceof GameEvent.Opponent.User u) {
                        opponent = u.username();
                    } else if (game.game().opponent() instanceof GameEvent.Opponent.AI ai) {
                        opponent = ai.username() + " - " + ai.ai();
                    }
                    if (ongoingGames.offer(game)) {
                        System.out.println("Successfully added game %s against %s in queue".formatted(game.id(), opponent));
                    } else {
                        System.out.println("Failed to add game %s against %s in queue".formatted(game.id(), opponent));
                    }
                    var white = game.game().color() == Color.white
                        ? one.entry().username() : opponent;
                    var black = game.game().color() == Color.black
                        ? one.entry().username() : opponent;

                    executor.submit(() -> {
                        System.out.println("Game:\n" + game);

                        Consumer<String> processMoves = moveList -> {
                            var board = moveList.isBlank()
                                ? Board.fromStandardPosition()
                                : Board.fromStandardPosition().play(moveList);
                            if (game.game().color() == Color.white
                                ? board.blackToMove()
                                : board.whiteToMove()) return;

                            var moves = new ArrayList<>(board.validMoves());
                            Collections.shuffle(moves, new Random());
                            var result = moves.stream().map(m -> m.uci())
                                .findFirst().map(uci -> client.bot().move(game.id(), uci))
                                .orElse(One.fail(-1, Err.from("no move")));

                            if (result instanceof Fail<?> f) {
                                System.out.println("Play failed: %s - resigning".formatted(f));
                                client.bot().resign(game.id());
                            }
                        };

                        client.bot().connectToGame(game.id()).stream()
                            .forEach(event -> { switch(event.type()) {
                                case gameFull -> {
                                    Full full = (Full) event;
                                    System.out.println("Full:\n"+full);
                                    processMoves.accept(full.state().moves());
                                }
                                case gameState -> {
                                    State state = (State) event;
                                    var moveList = state.moveList();
                                    if (! moveList.isEmpty()) {
                                        var board = Board.fromStandardPosition();
                                        if (moveList.size()-1 > 0) board = board.play(String.join(" ", moveList.subList(0, moveList.size()-1)));
                                        String lastMove = moveList.get(moveList.size()-1);
                                        System.out.println("%s (%s) played by %s".formatted(
                                                    lastMove, board.toSAN(lastMove),
                                                    board.whiteToMove() ? (white + " (w)") : (black + " (b)")));
                                        board = board.play(lastMove);
                                        System.out.println("%s %s %s".formatted(board.toFEN(), board.gameState(), state.status()));
                                    }

                                    if (state.status().ordinal() > Game.Status.started.ordinal()) {
                                        client.bot().chat(game.id(), "Thanks for the game!");
                                        if (state.winner() == null || state.winner().isBlank()) {
                                            System.out.println("No winner");
                                        } else {
                                            System.out.println("Winner: %s".formatted(state.winner()));
                                        }
                                        if (ongoingGames.remove(game)) {
                                            System.out.println("Successfully removed ongoing game %s".formatted(game.id()));
                                        } else {
                                            System.out.println("Failed to remove game %s".formatted(game.id()));
                                        }
                                    } else {
                                        processMoves.accept(state.moves());
                                    }
                                }

                                case opponentGone -> System.out.println("Gone:\n"+event);
                                case chatLine -> System.out.println("Chat:\n"+event);
                            }});
                        System.out.println("GameEvent handler for %s finished".formatted(game.id()));
                    });
                } catch (Exception e) {
                    System.out.println("GameAcceptor: %s\n%s".formatted(e.getMessage(), Instant.now()));
                }
            }
        });
    }
}

