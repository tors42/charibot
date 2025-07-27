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

The latest version of the application is available for download for Windows,
Mac and Linux at https://github.com/tors42/charibot/releases/latest

Unzipping the downloaded archive will create a directory `charibot-<version>`
and the application can be launched from a command-line terminal with
`charibot-<version>/bin/bot`

# Run

You will need a Lichess (BOT) account. If you don't already have a Lichess account,
you can create an account at https://lichess.org/signup

If you use a new account which is not yet a BOT account,
the application will ask you if you want to convert the account to a BOT account.

    $ bin/bot

or

    $ bin/bot.exe

When running the application for the first time, you will be asked to authorize
the application to access your BOT account, which will create and store a token
which the application can use to send moves to Lichess. The token will be
reused when running the application again, so only need to authorize once.

_Note, it is possible to revoke the token at any time via [https://lichess.org/account/security](https://lichess.org/account/security),
and if running the application again you will once again be asked to authorize the application._

_Note, if you already have a Lichess BOT account and an existing [token with bot:play scope](https://lichess.org/account/oauth/token/create?scopes[]=bot:play&description=Prefilled+bot+token),
you can run the application with that token set in an environment variable `BOT_TOKEN`:_

    $ export BOT_TOKEN=lip_...
    $ bin/bot

or

    $ set BOT_TOKEN=lip_...
    $ bin/bot.exe

# Build

This is an application written in Java and is managed as a
[Maven](https://maven.apache.org) project which has been configured to package
the application using `jlink`. 
This means that Java will not be needed on the computer that runs the application.

## GitHub Actions

The workflow which builds the application can be seen in
[build.yml](https://github.com/tors42/charibot/blob/main/.github/workflows/build.yml).

## Build Locally

Needs [JDK 25](https://jdk.java.net/25) and [Maven](https://maven.apache.org)

    $ mvn clean verify

The application will be packaged in `target/charibot-0.0.1-SNAPSHOT-<os>-<arch>.zip`
(and unpackaged in `target/maven-jlink/classifiers/<os>-<arch>/`, so can be run with
`target/maven-jlink/classifiers/<os>-<arch>/bin/bot`)

Note, it is also possible to run the application from source without building
it with Maven. This needs the above JDK, downloading of dependency [chariot-0.1.19.jar](https://repo1.maven.org/maven2/io/github/tors42/chariot/0.1.19/chariot-0.1.19.jar) and providing command-line flags:

    $ java --enable-preview -p chariot-0.1.19.jar --add-modules chariot src/main/java/bot/Bot.java

