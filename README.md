# akka-game-of-life

To run the application:

1. Start at least two console windows with sbt.
2. Enter `re-start` command in every window.
3. Start one `RunFrontend` and as many as you want `RunBackend`
4. Watch the log file `tail -f info.log`
5. Watch the simulation

Any of the backends can be closed with `ctrl+c`. Application should survive that and move dead actors to other nodes and regenerate.
