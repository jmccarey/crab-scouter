# Crab Scouter

A RuneLite plugin that crowdsources Gemstone Crab data across worlds. Shows player counts, crab health, and location in real-time.

<img width="293" height="236" alt="image" src="https://github.com/user-attachments/assets/dce527ed-6835-4c6b-9af5-f1b915602b0c" />


## How It Works

1. When you enter a crab area the plugin connects to the scouting server
2. The server assigns one player per world as the "reporter" who sends updates
3. All players receive broadcasts of world state changes
4. The sidebar panel shows all active worlds with an active reporter

## Backend

The backend is a Cloudflare Worker with Durable Objects. See the `crab-scouter-worker/` repo for the server code.

## Privacy

This plugin shares your IP address and current world with a third-party server. No personal information or account data is transmitted.
