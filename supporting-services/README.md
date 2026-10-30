# Supporting services

This directory contains independently runnable components that support the Moodify backend:

- `karaoke-media/` is the Node.js mediasoup service for real-time voice transport.
- `recommendation-worker/` contains Python batch, audit, and maintenance tools for recommendations.

The main Spring Boot application remains at the repository root, with Java sources under `src/`.
