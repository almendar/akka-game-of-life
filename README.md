# Pekko Game of Life

A fault-tolerant, distributed implementation of Conway's Game of Life built with **Scala 3**, **Apache Pekko Typed**, and **Pekko Cluster Sharding**.

## Architecture

- **Cluster Sharding (`CellActor`)**: Every cell `(x, y)` on the board is a sharded entity (`entityId = "x,y"`) distributed across cluster nodes with the `backend` role.
- **Epoch Coordination (`BoardCoordinator`)**: Runs on `frontend` nodes, monitors cluster backend membership, advances the simulation on a fixed tick timer, and optionally injects simulated crashes (`DoCrash`) to demonstrate supervision and deterministic history replay.
- **Neighbour State Gathering (`NextStateCellGathererActor`)**: Short-lived typed child actor spawned per cell per epoch to gather neighbouring states with retries and apply Conway's Game of Life transition rules.
- **Board Rendering (`LoggerActor`)**: Aggregates per-epoch cell reports and writes coordinate-ordered board snapshots to `info.log` while operational cluster logs stream to stdout.
- **Serialization & Resilience**: All cluster messages use **Jackson CBOR** (`allow-java-serialization = off`), and **Split Brain Resolver** handles node failures so orphaned cell entities automatically rebalance onto surviving backends.

## Prerequisites

- **JDK 17+** (tested with OpenJDK 21)
- **sbt 1.12+**

## Running the Simulation

### Interactive Web UI (Multi-Backend Cluster Manager)

Launch the embedded Web UI, which starts the frontend cluster seed node and spawns isolated child JVM backend processes you can add, gracefully stop (`SIGTERM`), or hard-kill (`SIGKILL`) right from the browser:

```bash
sbt "runMain gameoflife.RunUI"
```

Then open [http://localhost:8080](http://localhost:8080).

Optional CLI flags (or environment variables `UI_PORT`, `PEKKO_PORT`, `INITIAL_BACKENDS`):

```bash
sbt "runMain gameoflife.RunUI --ui-port 8080 --cluster-port 2551 --backends 2"
```

In the UI you can:
- **Start / Stop / Kill multiple backend JVMs** and watch Cluster Sharding color-code cells by owning backend and animate shard rebalancing live.
- **Play, Pause, Resume, Single-Step**, and adjust tick speed on the fly.
- **Reset the board** across all running backends with custom dimensions, seeds, or pattern presets (`Random`, `Glider`, `Blinker`, `Beacon`, `Pulsar`, `R-pentomino`, `Gosper Glider Gun`).
- **Click any cell** to inject `DoCrash` (or toggle automatic crash chaos) and watch deterministic history recovery.
- **Scrub through buffered epochs** (`LIVE` vs `REPLAY`) and inspect live cluster events and per-backend JVM logs.

### Single-Node (Standalone)

Run a single-JVM cluster node carrying both `frontend` and `backend` roles:

```bash
sbt "runMain gameoflife.RunStandalone"
```

Watch the board evolve in another terminal:

```bash
tail -f info.log
```

### Multi-Node Cluster (CLI)

1. Start the seed/frontend node on port `2551`:
   ```bash
   sbt "runMain gameoflife.RunFrontend 2551"
   ```
2. Start one or more backend nodes (in separate terminals):
   ```bash
   sbt "runMain gameoflife.RunBackend"
   ```
3. Watch the simulation output:
   ```bash
   tail -f info.log
   ```

You can stop any backend node with `Ctrl+C`. Split Brain Resolver will down the lost node, Cluster Sharding will relocate its cell entities onto surviving backend nodes, and the simulation will automatically recover and continue.

*(If you prefer `sbt-revolver` hot reloads, launch `sbt` and use `reStart` after selecting the main class.)*

## Configuration

Settings live in `src/main/resources/application.conf` under `game-of-life`:

- `board.size.x`, `board.size.y` — Grid dimensions (default `6x6`)
- `board.seed` — Deterministic seed for initial cell states across cluster nodes
- `simulation.wait-for-backends`, `simulation.start-delay`, `simulation.tick`, `simulation.max-crashes`
- `errors.delay`, `errors.every` — Simulated cell crash injection cadence

Network bindings can also be overridden via `PEKKO_HOSTNAME` and `PEKKO_PORT`.

## Development & Testing

```bash
# Check code formatting
sbt scalafmtCheckAll

# Run unit, actor, and CBOR serialization tests
sbt test
```
