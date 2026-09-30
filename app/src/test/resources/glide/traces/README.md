Put glides exported from the app (Settings > Record glides > Export, a `.jsonl` file) in this folder and run

    ./gradlew testDebugUnitTest --tests '*RecordedGlidesTest*' -i | grep 'GLIDE BENCH'

to score both decoders on real fingers. Each line holds one prompted word, its touch points with times,
and the key positions it was glided on. Nothing else is recorded.
