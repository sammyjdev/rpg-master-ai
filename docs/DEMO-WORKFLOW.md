# Demo Workflow — RPG Master AI Phase 1

This demonstrates the complete working workflow after fixing the LLM model configuration.

## Prerequisites
- Docker Compose stack running: `docker compose up -d`
- API started: `./rpgm start` (port 8082)
- Ollama running with qwen2.5:7b model

## Commands

### 1. Check System Status
```bash
$ ./rpgm status
🏥 System Health: UP
   API: http://localhost:8082 (UP)
   Qdrant: http://localhost:6333 (UP)
   PostgreSQL: localhost:5432 (UP)
```

### 2. Ingest D&D 5e Rulebook
```bash
$ ./rpgm ingest pdfs/phb.pdf dnd-5e-phb
📚 Ingesting: pdfs/phb.pdf as 'dnd-5e-phb'...
✅ {"documentId":"bc1abe0d-11c8-4fbe-8ad5-707fd9498ab4","status":"success","chunksStored":856,"error":null}
```

### 3. Query the Rulebook
```bash
$ ./rpgm ask "What is the Fireball spell?"
The Fireball spell is a 3rd-level Evocation spell with the following details:

- **Casting Time:** 1 action
- **Range:** 45 feet
- **Components:** V, S, M (a tiny ball of bat guano and sulfur)
- **Duration:** Instantaneous

When cast, a bright bolt of light shoots from your finger in a direction you choose within range. It then explodes with a low rumble, creating a sphere of fire 6 feet in radius centered on that point.

Each creature in the area must make a Dexterity saving throw. A creature takes 8d6 fire damage on a failed save or half as much damage on a successful one. The fire spreads around corners and ignites flammable objects not being worn or carried.

At higher levels, when casting this spell using a 4th-level slot or higher, the damage increases by 1d6 for each level above 3rd.
```

## Technical Details

- **LLM Model**: qwen2.5:7b (via Ollama on localhost:11434)
- **Embeddings**: bge-m3
- **Vector DB**: Qdrant
- **Metadata**: PostgreSQL
- **Java 21**: Virtual threads for I/O operations
- **Spring Boot 3.3.x**: Hexagonal architecture with ports

## Configuration

Local profile uses:
- `application-local.yml` for port mappings and Ollama URLs
- Spring AI auto-configuration for ChatModel
- Flyway for schema management

All infrastructure runs in Docker Compose for reproducibility.
