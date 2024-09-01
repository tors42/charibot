package bot;

import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.logging.Logger;

import static chariot.model.Enums.DeclineReason;
import chariot.model.Event.ChallengeCreatedEvent;
import chariot.ClientAuth;
import chariot.model.*;

record Rules(List<Rule> rules) {

    record Rule(BiPredicate<ChallengeCreatedEvent, Map<String, String>> cond, String msg, DeclineReason reason) {}

    static Rules defaultRules() {
        return new Rules(List.of(
            new Rule((e,_) -> ! e.challenge().players().challengerOpt().isPresent(), "No challenger", DeclineReason.generic),
            new Rule((e,_) -> e.challenge().gameType().rated(), "Rated", DeclineReason.casual),
            new Rule((e,_) -> e.challenge().gameType().variant() != Variant.Basic.standard
                && ! (e.challenge().gameType().variant() instanceof Variant.Chess960)
                && ! (e.challenge().gameType().variant() instanceof Variant.FromPosition), "Variant", DeclineReason.standard),
            new Rule((_, games) -> games.size() > 8, "Too many games", DeclineReason.later),
            new Rule((e, games) -> games.containsKey(e.challenge().players().challengerOpt().map(p -> p.user().id()).orElse("")), "Existing game", DeclineReason.later)
            ));
    }

    Opt<Runnable> decliner(ChallengeCreatedEvent event, Map<String, String> games, ClientAuth client, Logger LOGGER) {
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
