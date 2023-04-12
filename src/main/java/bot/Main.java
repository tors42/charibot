package bot;


import chariot.*;
import chariot.model.*;
import chariot.model.Enums.*;
import chariot.model.Event.*;
import chariot.model.Event.GameEvent;
import chariot.model.Game.Status;
import chariot.model.GameEvent.*;
import chariot.util.Board;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
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
        var games      = new ArrayBlockingQueue<GameEvent>(64);
        var challenges = new ArrayBlockingQueue<ChallengeEvent>(64);;


        // Listen for game start events and incoming challenges,
        // and just put them in the queues ("Producer")
        var thread1 = new Thread(() -> {
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

                    connect.stream().forEach(
                            event -> { switch(event.type()) {
                                case challenge -> challenges.add((ChallengeEvent) event);
                                case gameStart -> games.add((GameEvent) event);
                                default -> {}
                            }});
                } catch(Exception e) {
                    System.out.println(e.getMessage());
                    System.out.println(Instant.now());
                }
            }
        }, "Lichess Event Stream");


        // Accept or Decline incoming challenges ("Consumer")
        var thread2 = new Thread(() -> {
            while(!Thread.interrupted()) {
                try {
                    var challenge = challenges.take();
                    Challenge c = challenge.challenge();

                    if (c.rated()) {
                        System.out.println("Declinining challenge because rated,\n" + challenge);
                        client.challenges().declineChallenge(challenge.id(), d -> d.rated());
                        continue;
                    }
                    if (!c.variant().key().equals(GameVariant.standard)) {
                        System.out.println("Declinining challenge because not standard,\n" + challenge);
                        client.challenges().declineChallenge(challenge.id(), d -> d.standard());
                        continue;
                    }
                    if (games.size() >= 5) {
                        System.out.println("Declinining challenge because ongoing games,\n" + challenge);
                        client.challenges().declineChallenge(challenge.id(), d -> d.later());
                        continue;
                    }

                    System.out.println("Accepting challenge,\n" + challenge);
                    client.challenges().acceptChallenge(challenge.id());
                } catch (InterruptedException ie) {}
            }
        }, "Challenge Acceptor");

        // Create handler for started games ("Consumer")
        var thread3 = new Thread(() -> {
            while(!Thread.interrupted()) {
                try {
                    var game = games.take();
                    var fen = game.game().fen();
                    new Thread(() -> {
                        var ourColor = new AtomicReference<>(game.game().color());

                        Consumer<Board> processBoard = board -> {
                            boolean ourTurn = ourColor.get() == Color.white
                                ? board.whiteToMove()
                                : board.blackToMove();

                            if (ourTurn) {
                                // Hmm... Need to look up promotion moves...

                                var moves = new ArrayList<>(board.validMoves());
                                Collections.shuffle(moves, new Random());

                                boolean success = false;
                                for (var move : moves) {
                                    String uci = move.uci();

                                    System.out.println("Playing [%s] (%s)".formatted(uci, board.toSAN(uci)));
                                    var result = client.bot().move(game.id(), uci);
                                    if (result instanceof Fail<?> f) {
                                        System.out.println("Play failed");
                                    } else {
                                        success = true;
                                        break;
                                    }
                                }

                                if (! success) {
                                    System.out.println("No moved worked.. Trying resign");
                                    client.bot().resign(game.id());
                                }
                            }
                        };

                        client.bot().connectToGame(game.id()).stream()
                            .forEach(event -> { switch(event.type()) {
                                case gameFull -> {
                                    Full full = (Full) event;
                                    System.out.println("Full:\n"+full);
                                    ourColor.set(full.white().id().equals(one.entry().id())
                                        ? Color.white : Color.black);

                                    Board board = Board.fromFEN(fen);
                                    var moves = Arrays.stream(full.state().moves().split(" "))
                                        .filter(m -> ! m.isEmpty())
                                        .toList();

                                    if (moves.isEmpty()) {
                                        client.bot().chat(game.id(), """
                                                Hello!
                                                I haven't existed for long,
                                                so I might contain some bugs...
                                                Let us hope things will go smooth.
                                                I wish you a good game!
                                                """);
                                    }

                                    for (var move : moves) {
                                        board = board.play(move);
                                    }
                                    processBoard.accept(board);

                                }
                                case gameState -> {
                                    State state = (State) event;
                                    Board board = Board.fromFEN(fen);
                                    var moves = Arrays.stream(state.moves().split(" "))
                                        .filter(m -> ! m.isEmpty())
                                        .toList();
                                    for (var move : moves) {
                                        board = board.play(move);
                                    }
                                    System.out.println("fen: " + board.toFEN());
                                    if (state.status().ordinal() > Status.started.ordinal()) {
                                        client.bot().chat(game.id(), """
                                                Thanks for the game!
                                                """);
                                        return;
                                    }
                                    processBoard.accept(board);
                                }

                                case opponentGone -> System.out.println("Gone:\n"+event);
                                case chatLine -> System.out.println("Chat:\n"+event);
                            }});

                    }, "Game-" + game.id()).start();
                } catch (InterruptedException ie) {}
            }
        }, "Game Acceptor");

        thread1.start();
        thread2.start();
        thread3.start();
        try {
            thread1.join();
            thread2.join();
            thread3.join();
        } catch(InterruptedException ie) {
            ie.printStackTrace();
        }
    }

}

