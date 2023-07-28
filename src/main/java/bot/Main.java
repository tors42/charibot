package bot;

import chariot.*;
import chariot.Client.*;
import chariot.model.*;
import chariot.model.Enums.*;
import chariot.model.Event.*;
import chariot.model.GameStateEvent.*;
import chariot.util.Board;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.*;
import java.util.logging.Level;
import java.util.prefs.Preferences;

class Main {

    record ClientAndProfile(ClientAuth client, UserAuth profile) {}

    private static Logger LOGGER = Logger.getLogger("bot");

    public static void main(String[] args) {

        if (! (initializeClientAndProfile() instanceof ClientAndProfile(var client, var profile))) return;

        // Prepare queues for ongoing games and incoming challenges.
        var gamesToStart = new ArrayBlockingQueue<GameStartEvent>(64);
        var challenges   = new ArrayBlockingQueue<ChallengeCreatedEvent>(64);
        var ongoingGames = new ArrayBlockingQueue<GameStartEvent>(64);

        // Listen for game start events and incoming challenges,
        // and just put them in the queues ("Producer")
        var executor = Executors.newCachedThreadPool();
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

                    events.forEach(event -> { switch(event) {
                            case ChallengeCreatedEvent created -> challenges.add(created);
                            case GameStartEvent start when
                                ongoingGames.stream()
                                .map(GameStartEvent::id)
                                .noneMatch(start.id()::equals) -> gamesToStart.add(start);
                            default -> {}
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

                    LOGGER.info(() -> STR."Challenge from \{challenger.user().name()}");

                    if (c.gameType().rated()) {
                        LOGGER.info(() -> STR."Declining rated challenge: \{challenge}");
                        client.challenges().declineChallenge(challenge.id(), d -> d.casual());
                        continue;
                    }
                    if (c.gameType().variant() != VariantType.Variant.standard
                        && ! (c.gameType().variant() instanceof VariantType.Chess960)) {
                        LOGGER.info(() -> STR."Declining non-standard challenge: \{challenge}");
                        client.challenges().declineChallenge(challenge.id(), d -> d.standard());
                        continue;
                    }

                    boolean alreadyPlayingSameOpponent = ongoingGames.stream()
                        .anyMatch(g -> challenger.user().id().equals(g.game().opponent().id()));

                    if (alreadyPlayingSameOpponent) {
                        LOGGER.info(() -> STR."Declining simultaneous challenge: \{challenge}");
                        client.challenges().declineChallenge(challenge.id(), d -> d.later());
                        continue;
                    }

                    if (ongoingGames.size() >= 7) {
                        LOGGER.info(() -> STR."Declining too many games: \{challenge}");
                        client.challenges().declineChallenge(challenge.id(), d -> d.later());
                        continue;
                    }

                    LOGGER.info(() -> STR."Accepting from \{challenger.user().name()}: \{challenge}");

                    client.challenges().acceptChallenge(challenge.id());

                    var greeting = challenge.isRematch()
                        ? "Again!"
                        : STR."""
                          Hi \{challenger.user().name()}!
                          I wish you a good game!
                          """;
                     executor.submit(() -> {
                         try { Thread.sleep(Duration.ofSeconds(1));} catch (InterruptedException ie) {}
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

                    String fenAtGameStart = game.game().fen();

                    String opponent = game.game().opponent().name();

                    if (ongoingGames.offer(game))
                        LOGGER.info(() -> STR."Successfully added game \{game.id()} against \{opponent} in queue");
                    else
                        LOGGER.info(() -> STR."Failed to add game \{game.id()} against \{opponent} in queue");

                    var white = game.game().color() == Color.white ? profile.name() : opponent;
                    var black = game.game().color() == Color.black ? profile.name() : opponent;

                    int startPly = Board.fromFEN(fenAtGameStart) instanceof Board.BoardData(_, var fen, _, _)
                        ? 2 * fen.move() + (fen.whoseTurn() == Board.Side.BLACK ? 1 : 0)
                        : 0;

                    executor.submit(() -> { try {

                        Consumer<String> processMoves = moves -> {
                            var board = Board.fromFEN(fenAtGameStart);
                            if (! moves.isBlank()) board = board.play(moves);

                            if (game.game().color() == Color.white
                                ? board.blackToMove()
                                : board.whiteToMove()) return;

                            var validMoves = new ArrayList<>(board.validMoves());
                            Collections.shuffle(validMoves, new Random());
                            var result = validMoves.stream().map(m -> m.uci())
                                .findFirst().map(uci -> client.bot().move(game.id(), uci))
                                .orElse(One.fail(-1, Err.from("no move")));

                            if (result instanceof Fail<?> fail) {
                                LOGGER.info(() -> STR."Play failed: \{fail} - resigning");
                                client.bot().resign(game.id());
                            }
                        };

                        LOGGER.info(() -> STR."Connecting to game: \{game}");

                        client.bot().connectToGame(game.id()).stream()
                            .forEach(event -> { switch(event) {
                                case Full full -> {
                                    LOGGER.info(() -> STR."\{full}");
                                    processMoves.accept("");
                                }

                                case State state -> {
                                    var moveList = state.moveList();
                                    moveList = moveList.subList(startPly-2, moveList.size());
                                    int moves = moveList.size();
                                    if (moves > 0) {
                                        var board = Board.fromFEN(fenAtGameStart);
                                        if (moves > 1) board = board.play(String.join(" ", moveList.subList(0, moves-1)));

                                        String lastMove = moveList.getLast();
                                        LOGGER.info(STR."\{lastMove} (\{board.toSAN(lastMove)}) played by \{
                                                board.whiteToMove() ? (white + " (w)") : (black + " (b)")
                                                }");
                                        board = board.play(lastMove);

                                        LOGGER.info(STR."\{board.toFEN()} \{board.gameState()} \{state.status()}");
                                    }

                                    if (state.status().ordinal() > Status.started.ordinal()) {
                                        client.bot().chat(game.id(), "Thanks for the game!");
                                        if (state.winner() instanceof Some(var winner)) {
                                            LOGGER.info(() -> STR."Winner: \{winner == Color.white ? white : black}");
                                        } else {
                                            LOGGER.info(() -> STR."No winner: \{state.status()}");
                                        }
                                    } else {
                                        processMoves.accept(String.join(" ", moveList));
                                    }
                                }

                                case OpponentGone gone                  -> LOGGER.info(() -> STR."Gone: \{gone}");
                                case Chat(var name, var text, var room) -> LOGGER.info(() -> STR."Chat: [\{name}][\{room}]: \{text}");
                            }});

                        LOGGER.info(() -> STR."GameEvent handler for \{game.id()} finished");

                        if (ongoingGames.remove(game)) {
                            LOGGER.info(() -> STR."Successfully removed ongoing game \{game.id()}");
                        } else {
                            LOGGER.info(() -> STR."Failed to remove game \{game.id()}");
                        }
                    } catch (Exception e) {
                        if (ongoingGames.remove(game)) {
                            LOGGER.log(Level.WARNING, e, () -> STR."Successfully removed ongoing game \{game.id()} after failure: \{e.getMessage()}");
                        } else {
                            LOGGER.log(Level.WARNING, e, () -> STR."Failed to remove game \{game.id()} after failure: \{e.getMessage()}");
                        }
                    }});
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, e, () -> STR."GameAcceptor: \{e.getMessage()}\n\{Instant.now()}");
                }
            }
        });
    }




    static ClientAndProfile initializeClientAndProfile() {

        var client  = initializeClient();
        var profile = initializeProfile(client);

        return new ClientAndProfile(client, profile);
    }

    /**
     * Initialize client with a OAuth token with scope bot:play,
     * either provided via environment variable BOT_TOKEN (create at https://lichess.org/account/oauth/token/create),
     * or using OAuth PKCE flow and storing granted token locally with Java Preferences API,
     * i.e at first run the user must interactively grant access by navigating with a Web Browser
     * to the Lichess grant page and authorizing with the Bot Account,
     * and consecutive runs the now already stored token will be used automatically.
     */
    static ClientAuth initializeClient() {
        var prefs = Preferences.userRoot().node(System.getProperty("prefs", "charibot"));

        URI lichessApi = URI.create(System.getenv("LICHESS_API") instanceof String api ? api : "https://lichess.org");

        if (System.getenv("BOT_TOKEN") instanceof String token) { // scope bot:play
            var client = Client.auth(conf -> conf.api(lichessApi), token);
            if (client.scopes().contains(Client.Scope.bot_play)) {
                LOGGER.info(() -> STR."Storing and using provided token (\{prefs})");
                client.store(prefs);
                return client;
            }
            LOGGER.info("Provided token is missing bot:play scope");
            throw new RuntimeException("BOT_TOKEN is missing scope bot:play");
        }

        var client = Client.load(prefs);

        if (client instanceof ClientAuth auth
            && auth.scopes().contains(Client.Scope.bot_play)) {
            LOGGER.info(() -> STR."Using stored token (\{prefs})");
            return auth;
        }

        var authResult = Client.auth(
            conf -> conf.api(lichessApi),
            uri -> System.out.println(STR."""

                Visit the following URL and choose to grant access to this application or not:

                \{uri}

                Tip, open URL in "incognito"/private browser to log in with bot account
                to avoid logging out with normal account.
                """),
            pkce -> pkce.scope(Client.Scope.bot_play));

        if (! (authResult instanceof AuthOk(var auth))) {
            LOGGER.warning(() -> STR."OAuth PKCE flow failed: \{authResult}");
            throw new RuntimeException(authResult.toString());
        }

        LOGGER.info(() -> STR."OAuth PKCE flow succeeded - storing and using token (\{prefs})");
        auth.store(prefs);

        return auth;
    }

    static UserAuth initializeProfile(ClientAuth client) {
        var profileResult = client.account().profile();
        if (! (profileResult instanceof Entry(var profile))) {
            LOGGER.warning(() -> STR."Failed to lookup bot account profile: \{profileResult}");
            throw new RuntimeException(STR."Failed to lookup bot account profile: \{profileResult}");
        }

        if (! (profile.title() instanceof Some(var title) && "BOT".equals(title))) {
            LOGGER.warning(() -> STR."\{profile.name()} is not a BOT account");
            if (profile.accountStats().all() > 0) {
                LOGGER.warning(() -> "Account has played games - won't be possible to upgrade to BOT account");
                throw new RuntimeException("Not a BOT account (and not upgradeable because there are played games)");
            } else {
                String choice = System.console().readLine(STR."Transform account (\{profile.name()}) to BOT account? (Warning, can't be undone) [N/y]: ");
                if (choice != null && choice.toLowerCase(Locale.ROOT).equals("y")) {
                    if (client.bot().upgradeToBotAccount() instanceof Fail<?> fail) {
                        LOGGER.warning(() -> STR."Failed to upgrade account to BOT account: \{fail}");
                        throw new RuntimeException(STR."Failed to upgrade account to BOT account: \{fail}");
                    }
                    LOGGER.info(() -> "Upgraded account to BOT account");
                } else {
                    LOGGER.warning(() -> "Did not want to upgrade to BOT account");
                    throw new RuntimeException("Did not want to upgrade to BOT account");
                }
            }
        }
        return profile;
    }

}
