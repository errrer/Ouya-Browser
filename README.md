# Remote Engine

See the [main project README](../README.md) for setup, Android client installation, controller mappings, and security notes.

Quick start:

```sh
cp .env.example .env
# Edit .env and set OUYA_TOKEN to a unique value (8+ chars).
docker compose up -d --build
```

The API is intended for one OUYA Browser client on a trusted LAN.
