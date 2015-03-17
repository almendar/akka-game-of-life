# akka-game-of-life

To run the application:

1. Start at least two console windows with sbt.
2. Enter `re-start 2551` command in one window.
3. Enter `re-start` in every other windows.
3. Start `RunFrontend` where 2551 argument was passed.
4. Everywhere else start `RunBackend`
4. Watch the simulation with the log file `tail -f info.log`

Any of the backends can be closed with `ctrl+c`. Application should survive that and move dead actors to other nodes and regenerate.
