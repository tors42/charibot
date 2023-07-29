# charibot

Application which can connect to Lichess using a Lichess BOT account, and
start accepting challenges (non-rated, standard + Chess 960).

The application only plays random moves, so it is definitely possible to win
against it.

When the application is running, your Lichess BOT account will show up as
online at https://lichess.org/player/bots.

Example of a Lichess BOT account which uses this application:
https://lichess.org/@/charibot

# Releases

TODO, there are no pre-built releases yet - so you would need to build the
application yourself for now (see Build section)

# Run

You will need a Lichess (BOT) account. If you don't already have a Lichess BOT
account, you can create an account at https://lichess.org/signup

There are two ways you can make the application control your Lichess BOT
account

 1. Using a pre-created token
 2. Using OAuth2 PKCE flow

## Pre-created token

To use a pre-created token, begin by using a Web Browser to login with your
Lichess BOT account (or newly created account) and [create the
token](https://lichess.org/account/oauth/token/create?scopes[]=bot:play&description=Prefilled+bot+token).
You can then start the application with `BOT_TOKEN` environment variable set to
the created token:

    $ export BOT_TOKEN=lip_...
    $ <directory>/bin/bot

## OAuth2 PKCE

To use OAuth2 PKCE flow, you just start the application without a token - and
the application will generate a one-time Lichess URL which you can open in an
"incognito" Web Browser window and login with your Lichess BOT account (or
newly created account) and choose to approve the application:

    $ <directory>/bin/bot

_Note, the OAuth2 PKCE flow will currently only work if you run the application
on your local computer - so if you intend to run the bot deployed on a remote
server, you would need to use the `BOT_TOKEN` alternative (or update this
application to make use of a publicly reachable `redirect_uri` instead of the
default `localhost`... (Example of an application which does that can be found
[here](https://github.com/tors42/challengeaiexample/)))_

# Build

To build, one needs [Maven](https://maven.apache.org) and [Java 21 (Early
Access Builds)](https://jdk.java.net/21)

    $ mvn clean verify

The bot application will be packaged in `target/charibot-0.0.1-SNAPSHOT.zip`
(and unpackaged in `target/maven-jlink/default/`)

Note, the built bot application is a self-contained runtime so it is possible
to run it without having Java installed.

Note 2, the bot application is simple so it is possible to run it without
building it - but then one needs Java 21 (or later) on the machine where
running it, and also one would need to manually download the
[chariot](https://repo1.maven.org/maven2/io/github/tors42/chariot/0.0.71/chariot-0.0.71.jar)
library, and then run the application like this:

    java --source 21 --enable-preview --class-path chariot-0.0.71.jar src/main/java/bot/Main.java

