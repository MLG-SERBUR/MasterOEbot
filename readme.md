# MasterOEbot

A Java Discord bot implementing `/connect4` with JDA, plus `!connect4` fallback when slash commands are unavailable.

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

AI replies use configured providers round-robin. Keys can live in `config.yaml` or env vars:
`CEREBRAS_API_KEY`, `GROQ_API_KEY`, `OPENROUTER_API_KEY`, `ARLI_API_KEY`, `ARLIAI_API_KEY`.
Use `groqModels`, `openrouterModels`, and `arliModels` lists to cycle multiple models. Legacy `groqModel` still loads.

## Discord setup (Developer Portal)
1. Go to **https://discord.com/developers/applications**.
2. Create a new application.
3. Open **Bot** tab and click **Add Bot**.
4. Under **Privileged Gateway Intents**:
   - Enable **Message Content Intent** if you want prefix fallback commands like `!connect4`.
   - If Discord denies it, the bot will still start and use slash commands only.
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

## Systemd Service
To install and run the bot in the background automatically:
1. Ensure your `config.yaml` is configured.
2. Run `./install_service.sh`.
3. To view logs: `journalctl --user -u masteroebot -f`.
4. To stop and uninstall: `./uninstall_service.sh`.

## Command usage
1. Start game in a channel and select both players:
   - `/connect4 player1:@UserA player2:@UserB`
   - `!connect4 @UserA @UserB`
   - Use the same user twice to play yourself. Prefix mode also supports `!connect4 @UserA`.
   - Bot users can be selected. This bot auto-plays its own turns.
2. Players place moves on their turn:
   - `/connect4 move:F7 game:1`
   - `!connect4 F7`
   - `!connect4 move F7`
   - `!connect4 1 F7`

Each started game gets a number like `Connect 4 #1`. If multiple active games match you, include the game number with your move.

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
