package bot;

import module chariot;
import module java.base;

record Bot(ClientAndAccount clientAndAccount, Map<String,String> games, Rules rules) {

    static final Logger LOGGER = Logger.getLogger("bot");

    static void main() {
        while (true) {
            try {
                if (ClientAndAccount.initialize().map(Bot::new) instanceof Some(var bot)) bot.run();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, e, e::getMessage);
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
        // Connect the BOT to Lichess
        Many<Event> events = clientAndAccount.client().bot().connect();

        // Check for network problems
        if (events instanceof Fail<?>) {
            LOGGER.warning(() -> "Failed to connect: %s".formatted(events));
            return;
        }

        // Check if we are to challenge someone...
        if (System.getenv("BOT_CHALLENGE_USER") instanceof String challengeUser) {
            sendChallenge(challengeUser, clientAndAccount.client());
        }

        // Check if we should ask to be paired for a game in arena
        if (System.getenv("ARENA_ID") instanceof String arenaId) {
            joinArena(arenaId, clientAndAccount.client());
        }

        // Listen for game start events and incoming challenges
        try (var scope = StructuredTaskScope.open();
             var stream = events.stream();) {
            stream.forEach(event -> { switch(event) {
                case Event.ChallengeCreatedEvent created -> scope.fork(() -> handleChallenge(created));
                case Event.GameStartEvent(var game, _)   -> scope.fork(() -> handleGame(game));
                case Event.GameStopEvent _,
                     Event.GameStartEvent _,
                     Event.ChallengeCanceledEvent _,
                     Event.ChallengeDeclinedEvent _      -> LOGGER.info(event::toString);
            }});
        }
    }

    static void sendChallenge(String user, ClientAuth client) {
        client.challenges().challenge(user, p -> p.clockBlitz5m0s().rated(false));
    }

    static void joinArena(String arenaId, ClientAuth client) {
        switch(client.tournaments().joinArena(arenaId)) {
            case Entry<Void>(_), None()    -> System.out.println("Joined %s".formatted(arenaId));
            case Fail(int status, var err) -> System.out.println("Failed to join %s - %d %s".formatted(arenaId, status, err));
        }
    }

    static void sleep(Duration duration) { try { Thread.sleep(duration); } catch (InterruptedException _) {} }

    void handleChallenge(Event.ChallengeCreatedEvent event) {
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

            Function<Enums.Color, String> nameByColor = color ->
                color == game.color() ? account.name() : game.opponent().name();

            Consumer<String> processMoves = moves -> {
                Board board = moves.isBlank()
                    ? Board.fromFEN(fenAtGameStart)
                    : Board.fromFEN(fenAtGameStart).play(moves);

                if (game.color() == Enums.Color.white
                        ? board.blackToMove()
                        : board.whiteToMove()) return;

                One<?> result = board.validMoves().stream()
                    .skip(new Random().nextInt(board.validMoves().size()))
                    .map(Board.Move::uci)
                    .findFirst()
                    .map(uci -> client.bot().move(game.gameId(), uci))
                    .orElse(One.fail(-1, Err.from("no move")));

                if (result instanceof Fail<?> fail) {
                    LOGGER.warning(() -> "Play failed: %s - resigning".formatted(fail));
                    client.bot().resign(game.gameId());
                }
            };

            LOGGER.fine(() -> "Connecting to game: %s".formatted(game));

            final AtomicInteger movesPlayedSinceStart = new AtomicInteger();

            var connectToGameResult = client.bot().connectToGame(game.gameId());

            if (! (connectToGameResult instanceof Entries<GameStateEvent> entries)) {
                LOGGER.warning(() -> "Failed to connect to game %s%n%s".formatted(game, connectToGameResult));
                client.bot().resign(game.gameId());
                return;
            }

            try (var stream = entries.stream()) {
                stream.forEach(event -> { switch(event) {
                    case GameStateEvent.Full full -> {
                        LOGGER.info(() -> "FULL: %s".formatted(full));
                        movesPlayedSinceStart.set(full.state().moveList().size());
                        processMoves.accept("");
                    }

                    case GameStateEvent.State state -> {
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
                                    nameByColor.apply(Enums.Color.white) + (board.whiteToMove() ? "*" : ""),
                                    nameByColor.apply(Enums.Color.black) + (board.blackToMove() ? "*" : ""));

                            board = board.play(lastMove);

                            String infoAfterMove = "%s %s %s".formatted(
                                    board.toFEN(),
                                    board.gameState(),
                                    state.status());

                            LOGGER.info("%s\n%s".formatted(infoBeforeMove, infoAfterMove));
                        }

                        if (state.status().ordinal() > Enums.Status.started.ordinal()) {
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

                    case GameStateEvent.OpponentGone(_, GameStateEvent.Yes())
                        -> LOGGER.info("Claim Draw: %s".formatted(client.bot().claimDraw(game.gameId())));
                    case GameStateEvent.OpponentGone gone
                        -> LOGGER.info(() -> "Gone: %s".formatted(gone));
                    case GameStateEvent.Chat(var name, var text, var room)
                        -> LOGGER.info(() -> "Chat: [%s][%s]: %s".formatted(name, room, text));
                }});
            }
            LOGGER.fine(() -> "GameEvent handler for %s finished".formatted(game.gameId()));
        } finally {
            games.remove(game.opponent().id(), game.gameId());
        }

        // Check if we are to challenge someone...
        if (System.getenv("BOT_CHALLENGE_USER") instanceof String challengeUser
            && game.opponent().id().equalsIgnoreCase(challengeUser)) {
            sendChallenge(challengeUser, clientAndAccount.client());
        }
    }
}
