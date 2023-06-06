# Work In Progress

Testing Lichess BOT functionality,
with a rudimentary bot application which makes random moves.

# Build

    # Java 17

    mvn clean verify

# Run

You need
  - a Lichess BOT account
  - a token with scope bot:play ( https://lichess.org/account/oauth/token/create?scopes[]=bot:play )


```
export BOT_TOKEN=lip_...

java -jar target/charibot-0.0.1-SNAPSHOT-jar-with-dependencies.jar
```

When the program is running, the bot should show up as online at
https://lichess.org/player/bots and be ready to be challenged to casual
standard games.

