# MasterOEbot

A Java Discord bot implementing `/connect4` with JDA.

## Requirements
- Java 21+
- Maven 3.9+
- A Discord application + bot user

## Configuration
1. Copy the example file:
   ```bash
   cp config.yaml.example config.yaml
   ```
2. Edit `config.yaml` and set your bot token:
   ```yaml
   discord:
     token: "YOUR_REAL_BOT_TOKEN"
   ```

> Never commit `config.yaml` with a real token.

## Discord setup (Developer Portal)
1. Go to **https://discord.com/developers/applications**.
2. Create a new application.
3. Open **Bot** tab and click **Add Bot**.
4. Under **Privileged Gateway Intents**:
   - Not required for this slash-command-only bot.
5. In **Bot Permissions**, ensure your invite grants at least:
   - View Channels
   - Send Messages
   - Use Slash Commands

## OAuth2 install URL
In **OAuth2 > URL Generator** select:
- **Scopes**:
  - `bot`
  - `applications.commands`
- **Bot Permissions**:
  - `View Channels`
  - `Send Messages`
  - `Use Slash Commands`

Open the generated URL and install the bot to your server.

## Build & run
```bash
mvn -q test
mvn -q package
java -jar target/masteroebot-1.0.0.jar
```

## Command usage
1. Start game in a channel and select both players:
   - `/connect4 player1:@UserA player2:@UserB`
2. Players place moves on their turn:
   - `/connect4 move:F7`

Board format:
```text
Aooooooo
Booooooo
Cooooooo
Dooooooo
Eooooooo
Fooooooo
 1234567
```
- Rows are `A` to `F` (top to bottom).
- Columns are `1` to `7`.
- Gravity is enforced, so the move must match the slot where the piece lands.

Piece markers:
- Player 1: `●`
- Player 2: `◍`
