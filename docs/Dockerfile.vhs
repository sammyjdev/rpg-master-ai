FROM ghcr.io/charmbracelet/vhs:latest

# Add curl so rpgm script can make HTTP requests inside the recording
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
