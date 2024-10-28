package bot;

import module chariot;
import module java.base;

record Rules(List<Rule> rules) {

    record Rule(BiPredicate<Event.ChallengeCreatedEvent, Map<String, String>> cond, String msg, Enums.DeclineReason reason) {}

    static Rules defaultRules() {
        return new Rules(List.of(
            new Rule((e,_) -> ! e.challenge().players().challengerOpt().isPresent(), "No challenger", Enums.DeclineReason.generic),
            new Rule((e,_) -> e.challenge().gameType().rated(), "Rated", Enums.DeclineReason.casual),
            new Rule((e,_) -> e.challenge().gameType().variant() != Variant.Basic.standard
                && ! (e.challenge().gameType().variant() instanceof Variant.Chess960)
                && ! (e.challenge().gameType().variant() instanceof Variant.FromPosition), "Variant", Enums.DeclineReason.standard),
            new Rule((_, games) -> games.size() > 8, "Too many games", Enums.DeclineReason.later),
            new Rule((e, games) -> (! (e.rematchOf() instanceof Some))
                && games.containsKey(e.challenge().players().challengerOpt().map(p -> p.user().id()).orElse("")), "Existing game", Enums.DeclineReason.later)
            ));
    }

    Opt<Runnable> decliner(Event.ChallengeCreatedEvent event, Map<String, String> games, ClientAuth client, Logger LOGGER) {
        return rules().stream()
            .filter(r -> r.cond().test(event, games))
            .map(rule -> Opt.<Runnable>of(() -> {
                LOGGER.info(() -> "Declined %s\n%s - %s - DeclineReason/'want instead': %s".formatted(
                            event,
                            rule.msg(),
                            event.challenge().players().challengerOpt().map(p -> p.user().name()).orElse("<none>"),
                            rule.reason()));
                client.challenges().declineChallenge(event.id(), rule.reason());
            }))
            .findFirst()
            .orElse(Opt.empty());
    }
}
