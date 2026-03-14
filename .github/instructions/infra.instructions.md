---
applyTo: 'docker-compose*.yml'
---

# Infrastructure & Docker Conventions

## Docker Compose Service Conventions

- Service name: `rpg-{service}` (e.g., `rpg-qdrant`, `rpg-postgres`, `rpg-ollama`)
- Pin image versions — never `:latest` except Ollama (model weights are version-controlled separately)
- Named volumes for all persistent data — never bind mounts for databases
- Expose only ports required for local development

## Increment 1 Services (canonical)

```yaml
services:
  qdrant:
    image: qdrant/qdrant:v1.12.5
    container_name: rpg-qdrant
    ports: ['6333:6333', '6334:6334'] # 6333=HTTP dashboard, 6334=gRPC client
    volumes: ['qdrant_data:/qdrant/storage']

  postgres:
    image: postgres:16-alpine
    container_name: rpg-postgres
    environment:
      POSTGRES_DB: rpgmaster
      POSTGRES_USER: rpgmaster
      POSTGRES_PASSWORD: rpgmaster
    ports: ['5432:5432']
    volumes: ['postgres_data:/var/lib/postgresql/data']

  ollama:
    image: ollama/ollama:latest
    container_name: rpg-ollama
    ports: ['11434:11434']
    volumes: ['ollama_data:/root/.ollama']
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: all
              capabilities: [gpu] # remove if no GPU
```

## Anti-Patterns

- `:latest` tag on Qdrant or PostgreSQL — breaks reproducibility across machines
- No named volumes — data lost on `docker compose down`
- Hardcoding credentials in `environment:` for non-local environments — use `.env` files or Docker secrets
- No `container_name` — harder to reference in logs and `docker exec`
