package bot;

import chariot.*;
import chariot.Client.*;
import chariot.model.*;

import java.net.URI;
import java.util.*;
import java.util.logging.*;
import java.util.prefs.Preferences;

record ClientAndProfile(ClientAuth client, UserAuth profile) {

    private static Logger LOGGER = Logger.getLogger("bot");

    static ClientAndProfile initialize() {

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
            if (client.scopes().contains(Scope.bot_play)) {
                LOGGER.info(() -> "Storing and using provided token (%s)".formatted(prefs));
                client.store(prefs);
                return client;
            }
            LOGGER.info("Provided token is missing bot:play scope");
            throw new RuntimeException("BOT_TOKEN is missing scope bot:play");
        }

        var client = Client.load(prefs);

        if (client instanceof ClientAuth auth
            && auth.scopes().contains(Scope.bot_play)) {
            LOGGER.info(() -> "Using stored token (%s)".formatted(prefs));
            return auth;
        }

        var authResult = Client.auth(
            conf -> conf.api(lichessApi),
            uri -> System.out.println("""

                Visit the following URL and choose to grant access to this application or not:

                %s

                Tip, open URL in "incognito"/private browser to log in with bot account
                to avoid logging out with normal account.
                """.formatted(uri)),
            pkce -> pkce.scope(Scope.bot_play));

        if (! (authResult instanceof AuthOk(var auth))) {
            LOGGER.warning(() -> "OAuth PKCE flow failed: %s".formatted(authResult));
            throw new RuntimeException(authResult.toString());
        }

        LOGGER.info(() -> "OAuth PKCE flow succeeded - storing and using token (%s)".formatted(prefs));
        auth.store(prefs);

        return auth;
    }

    static UserAuth initializeProfile(ClientAuth client) {
        // Check the Lichess account
        var profileResult = client.account().profile();
        if (! (profileResult instanceof Entry(var profile))) {
            LOGGER.warning(() -> "Failed to lookup bot account profile: %s".formatted(profileResult));
            throw new RuntimeException("Failed to lookup bot account profile: %s".formatted(profileResult));
        }

        // Lichess account is a BOT account, we're done - return profile
        if (profile.title() instanceof Some(var title) && "BOT".equals(title)) return profile;

        // It wasn't a BOT account...
        LOGGER.warning(() -> "%s is not a BOT account".formatted(profile.name()));

        // Check if eligible to upgrade to BOT account
        if (profile.accountStats().all() > 0) {
            LOGGER.warning(() -> "Account has played games - won't be possible to upgrade to BOT account");
            throw new RuntimeException("Not a BOT account (and not upgradeable because there are played games)");
        }

        // Ask if user wants to upgrade to BOT account
        if (! (System.console().readLine("Transform account (%s) to BOT account? (Warning, can't be undone) [N/y]: ".formatted(profile.name()))
                instanceof String choice && choice.toLowerCase(Locale.ROOT).equals("y"))) {
            LOGGER.warning(() -> "Did not want to upgrade to BOT account");
            throw new RuntimeException("Did not want to upgrade to BOT account");
        }

        // User wanted to upgrade to BOT account - attempt to do so
        if (client.bot().upgradeToBotAccount() instanceof Fail<?> fail) {
            LOGGER.warning(() -> "Failed to upgrade account to BOT account: %s".formatted(fail));
            throw new RuntimeException("Failed to upgrade account to BOT account: %s".formatted(fail));
        }

        // Tada!
        LOGGER.info(() -> "Upgraded account to BOT account");
        return profile;
    }
}
