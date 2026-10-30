"use strict";

const http = require("node:http");
const crypto = require("node:crypto");
const mediasoup = require("mediasoup");
const jwt = require("jsonwebtoken");
const { WebSocketServer, WebSocket } = require("ws");

const port = Number(process.env.PORT || 3001);
const signalingListenIp = process.env.KARAOKE_MEDIA_SIGNALING_LISTEN_IP || "0.0.0.0";
const secret = process.env.KARAOKE_MEDIA_TICKET_SECRET || "";
const backendUrl = (process.env.KARAOKE_BACKEND_INTERNAL_URL || "http://127.0.0.1:8080").replace(/\/$/, "");
const backendServiceSecret = process.env.KARAOKE_MEDIA_SERVICE_SECRET || "";
const listenIp = process.env.MEDIASOUP_LISTEN_IP || "0.0.0.0";
const announcedIp = process.env.MEDIASOUP_ANNOUNCED_IP || undefined;
const rtcMinPort = Number(process.env.MEDIASOUP_MIN_PORT || 40000);
const rtcMaxPort = Number(process.env.MEDIASOUP_MAX_PORT || 49999);
const maxPeersPerRoom = 6;
const maxRequestsPerPeerWindow = 60;
const peerRequestWindowMs = 10_000;
const ticketClockToleranceSeconds = Number(process.env.KARAOKE_MEDIA_TICKET_CLOCK_TOLERANCE_SECONDS || 120);
const requestCacheTtlMs = 60_000;
const maxCachedRequestsPerPeer = 128;
const unauthenticatedTimeoutMs = Number(process.env.MEDIASOUP_AUTH_TIMEOUT_MS || 5000);
const allowedOrigins = new Set((process.env.MEDIASOUP_ALLOWED_ORIGINS || "")
  .split(",")
  .map((origin) => origin.trim())
  .filter(Boolean));
const replayedTickets = new Map();
const rooms = new Map();
const roomCreations = new Map();
const metrics = { authSuccess: 0, authFailure: 0, rateLimited: 0, requestFailure: 0 };
let worker;

const mediaCodecs = [
  {
    kind: "audio",
    mimeType: "audio/opus",
    clockRate: 48000,
    channels: 2,
    parameters: { useinbandfec: 1, usedtx: 1 },
  },
];

function send(socket, value) {
  if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(value));
}

function respond(socket, id, payload) {
  const response = { id, ok: true, payload };
  send(socket, response);
  return response;
}

function reject(socket, id, code, message = code) {
  const response = { id, ok: false, error: { code, message } };
  send(socket, response);
  return response;
}

function requestFingerprint(message) {
  return crypto.createHash("sha256")
    .update(message.type)
    .update("\n")
    .update(JSON.stringify(message.payload || {}))
    .digest("hex");
}

function pruneRequestCache(peer, now = Date.now()) {
  for (const [id, cached] of peer.requestCache) {
    if (cached.expiresAt <= now) peer.requestCache.delete(id);
  }
  while (peer.requestCache.size > maxCachedRequestsPerPeer) {
    peer.requestCache.delete(peer.requestCache.keys().next().value);
  }
}

function parseIceServers(userId) {
  const raw = process.env.WEBRTC_ICE_SERVERS;
  const servers = raw ? JSON.parse(raw) : [{ urls: ["stun:stun.l.google.com:19302"] }];
  if (!Array.isArray(servers)) throw new Error("WEBRTC_ICE_SERVERS must be a JSON array");
  const turnUrls = process.env.TURN_SERVER_URLS ? JSON.parse(process.env.TURN_SERVER_URLS) : [];
  const turnSecret = process.env.TURN_SHARED_SECRET || "";
  if (turnUrls.length && turnSecret) {
    const expiresAt = Math.floor(Date.now() / 1000) + Number(process.env.TURN_CREDENTIAL_TTL_SECONDS || 600);
    const username = `${expiresAt}:${userId}`;
    const credential = crypto.createHmac("sha1", turnSecret).update(username).digest("base64");
    servers.push({ urls: turnUrls, username, credential });
  }
  return servers;
}

async function getRoom(roomId) {
  let room = rooms.get(roomId);
  if (!room) {
    let pending = roomCreations.get(roomId);
    if (!pending) {
      pending = worker.createRouter({ mediaCodecs }).then(async (router) => {
        const current = rooms.get(roomId);
        if (current) {
          router.close();
          return current;
        }
        const audioObserver = await router.createAudioLevelObserver({ maxEntries: maxPeersPerRoom, threshold: -65, interval: 800 });
        const created = { id: roomId, router, peers: new Map(), audioObserver, speakingUsers: new Set() };
        audioObserver.on("volumes", (volumes) => {
          const active = new Set();
          for (const volume of volumes) {
            const userId = volume.producer.appData.userId;
            if (userId) active.add(String(userId));
          }
          created.speakingUsers = active;
          broadcast(created, null, { type: "voice-speaking", payload: { userIds: [...active] } });
        });
        audioObserver.on("silence", () => {
          created.speakingUsers = new Set();
          broadcast(created, null, { type: "voice-silence", payload: {} });
        });
        rooms.set(roomId, created);
        return created;
      });
      roomCreations.set(roomId, pending);
    }
    try {
      room = await pending;
    } finally {
      if (roomCreations.get(roomId) === pending) roomCreations.delete(roomId);
    }
  }
  return room;
}

function broadcast(room, exceptSocket, value) {
  for (const peer of room.peers.values()) {
    if (peer.socket !== exceptSocket) send(peer.socket, value);
  }
}

function removePeer(peer) {
  if (peer.removed) return;
  peer.removed = true;
  if (peer.leaseTimer) clearInterval(peer.leaseTimer);
  void callBackend("release", {
    sessionId: peer.sessionId,
    roomId: Number(peer.roomId),
    userId: Number(peer.userId),
  }).catch(() => undefined);
  const room = rooms.get(peer.roomId);
  if (!room) return;
  room.peers.delete(peer.userId);
  for (const producer of peer.producers.values()) {
    void room.audioObserver.removeProducer({ producerId: producer.id }).catch(() => undefined);
  }
  for (const transport of peer.transports.values()) transport.close();
  if (room.peers.size === 0) {
    room.router.close();
    rooms.delete(room.id);
  }
}

function authenticateTicket(ticket) {
  if (secret.length < 32 || backendServiceSecret.length < 32) throw new Error("MEDIA_SERVICE_AUTH_NOT_CONFIGURED");
  const claims = jwt.verify(ticket, secret, {
    algorithms: ["HS256"],
    issuer: "moodify-karaoke-backend",
    audience: "moodify-karaoke-media",
    clockTolerance: ticketClockToleranceSeconds,
  });
  if (claims.type !== "karaoke-voice" || !claims.sub || !claims.roomId || !claims.jti || claims.sid !== claims.jti) {
    throw new Error("VOICE_TICKET_INVALID");
  }
  const existingExpiry = replayedTickets.get(claims.jti);
  if (existingExpiry && existingExpiry > Date.now()) throw new Error("VOICE_TICKET_ALREADY_USED");
  const ticketReplayUntilMs = claims.exp * 1000 + ticketClockToleranceSeconds * 1000;
  replayedTickets.set(claims.jti, ticketReplayUntilMs);
  return {
    roomId: String(claims.roomId),
    userId: String(claims.sub),
    sessionId: String(claims.sid),
  };
}

async function callBackend(action, peerIdentity) {
  const response = await fetch(`${backendUrl}/api/internal/karaoke/media-sessions/${action}`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "X-Karaoke-Media-Key": backendServiceSecret,
    },
    body: JSON.stringify(peerIdentity),
    signal: AbortSignal.timeout(4000),
  });
  if (!response.ok) throw new Error(`BACKEND_${action.toUpperCase()}_FAILED_${response.status}`);
  if (response.status === 204) return { valid: true };
  return response.json();
}

function startLeaseRenewal(peer) {
  peer.leaseExpiresAt = Date.now() + 15_000;
  peer.leaseTimer = setInterval(async () => {
    if (peer.leaseRenewing || peer.socket.readyState !== WebSocket.OPEN) return;
    peer.leaseRenewing = true;
    try {
      const result = await callBackend("lease", {
        sessionId: peer.sessionId,
        roomId: Number(peer.roomId),
        userId: Number(peer.userId),
      });
      if (!result.valid) {
        peer.socket.close(1008, "voice membership expired");
        return;
      }
      peer.leaseExpiresAt = Date.now() + 15_000;
    } catch (error) {
      if (Date.now() >= peer.leaseExpiresAt) {
        console.warn("voice lease expired", peer.roomId, peer.userId, error instanceof Error ? error.message : "unknown");
        peer.socket.close(1011, "voice membership check failed");
      }
    } finally {
      peer.leaseRenewing = false;
    }
  }, 5_000);
}

async function createTransport(peer) {
  if (peer.transports.size >= 2) throw new Error("TRANSPORT_LIMIT_REACHED");
  const transport = await peer.room.router.createWebRtcTransport({
    listenInfos: [
      { protocol: "udp", ip: listenIp, ...(announcedIp ? { announcedAddress: announcedIp } : {}) },
      { protocol: "tcp", ip: listenIp, ...(announcedIp ? { announcedAddress: announcedIp } : {}) },
    ],
    initialAvailableOutgoingBitrate: 600000,
    appData: { userId: peer.userId },
  });
  if (peer.removed || peer.socket.readyState !== WebSocket.OPEN) {
    transport.close();
    throw new Error("VOICE_SESSION_CLOSED");
  }
  peer.transports.set(transport.id, transport);
  transport.on("close", () => peer.transports.delete(transport.id));
  transport.on("dtlsstatechange", (state) => {
    if (state === "closed") transport.close();
  });
  return {
    id: transport.id,
    iceParameters: transport.iceParameters,
    iceCandidates: transport.iceCandidates,
    dtlsParameters: transport.dtlsParameters,
    sctpParameters: transport.sctpParameters,
  };
}

function consumerResponse(consumer) {
  return {
    id: consumer.id,
    producerId: consumer.producerId,
    kind: consumer.kind,
    rtpParameters: consumer.rtpParameters,
    type: consumer.type,
    producerPaused: consumer.producerPaused,
  };
}

async function handleMessage(peer, socket, message) {
  const { id, type, payload = {} } = message;
  if (!id || typeof type !== "string") return reject(socket, id, "INVALID_REQUEST");
  try {
    if (type === "get-rtp-capabilities") {
      return respond(socket, id, {
        routerRtpCapabilities: peer.room.router.rtpCapabilities,
        iceServers: parseIceServers(peer.userId),
      });
    }
    if (type === "create-transport") {
      if (payload.direction !== "send" && payload.direction !== "recv") return reject(socket, id, "INVALID_DIRECTION");
      return respond(socket, id, await createTransport(peer));
    }
    if (type === "connect-transport") {
      const transport = peer.transports.get(payload.transportId);
      if (!transport) return reject(socket, id, "TRANSPORT_NOT_FOUND");
      await transport.connect({ dtlsParameters: payload.dtlsParameters });
      return respond(socket, id, {});
    }
    if (type === "produce") {
      if (payload.kind !== "audio") return reject(socket, id, "AUDIO_ONLY");
      const transport = peer.transports.get(payload.transportId);
      if (!transport) return reject(socket, id, "TRANSPORT_NOT_FOUND");
      for (const previousProducer of [...peer.producers.values()]) {
        if (previousProducer.kind !== "audio") continue;
        await peer.room.audioObserver.removeProducer({ producerId: previousProducer.id }).catch(() => undefined);
        previousProducer.close();
        peer.producers.delete(previousProducer.id);
      }
      const producer = await transport.produce({
        kind: "audio",
        rtpParameters: payload.rtpParameters,
        appData: { userId: peer.userId },
      });
      if (peer.removed || socket.readyState !== WebSocket.OPEN) {
        producer.close();
        throw new Error("VOICE_SESSION_CLOSED");
      }
      peer.producers.set(producer.id, producer);
      producer.on("transportclose", () => peer.producers.delete(producer.id));
      try {
        await peer.room.audioObserver.addProducer({ producerId: producer.id });
      } catch (error) {
        peer.producers.delete(producer.id);
        producer.close();
        throw error;
      }
      if (peer.removed || socket.readyState !== WebSocket.OPEN || producer.closed) {
        await peer.room.audioObserver.removeProducer({ producerId: producer.id }).catch(() => undefined);
        producer.close();
        peer.producers.delete(producer.id);
        throw new Error("VOICE_SESSION_CLOSED");
      }
      broadcast(peer.room, socket, {
        type: "new-producer",
        payload: { producerId: producer.id, userId: peer.userId, kind: producer.kind },
      });
      return respond(socket, id, { id: producer.id });
    }
    if (type === "consume") {
      const transport = peer.transports.get(payload.transportId);
      if (!transport) return reject(socket, id, "TRANSPORT_NOT_FOUND");
      if (typeof payload.producerId !== "string" || !payload.producerId.trim()
          || payload.producerId === "undefined" || payload.producerId === "null") {
        return reject(socket, id, "INVALID_PRODUCER_ID");
      }
      const producerExists = [...peer.room.peers.values()]
        .some((roomPeer) => roomPeer.producers.has(payload.producerId));
      if (!producerExists) return reject(socket, id, "PRODUCER_NOT_FOUND");
      const existingConsumer = peer.consumersByProducer.get(payload.producerId);
      if (existingConsumer) return respond(socket, id, consumerResponse(existingConsumer));
      if (peer.consumers.size >= maxPeersPerRoom - 1) return reject(socket, id, "CONSUMER_LIMIT_REACHED");
      if (!peer.room.router.canConsume({ producerId: payload.producerId, rtpCapabilities: payload.rtpCapabilities })) {
        return reject(socket, id, "CANNOT_CONSUME");
      }
      const consumer = await transport.consume({
        producerId: payload.producerId,
        rtpCapabilities: payload.rtpCapabilities,
        paused: true,
      });
      if (peer.removed || socket.readyState !== WebSocket.OPEN) {
        consumer.close();
        throw new Error("VOICE_SESSION_CLOSED");
      }
      peer.consumers.set(consumer.id, consumer);
      peer.consumersByProducer.set(consumer.producerId, consumer);
      consumer.on("transportclose", () => {
        peer.consumers.delete(consumer.id);
        peer.consumersByProducer.delete(consumer.producerId);
      });
      consumer.on("producerclose", () => {
        peer.consumers.delete(consumer.id);
        peer.consumersByProducer.delete(consumer.producerId);
        send(socket, { type: "producer-closed", payload: { producerId: payload.producerId } });
      });
      return respond(socket, id, consumerResponse(consumer));
    }
    if (type === "resume-consumer") {
      const consumer = peer.consumers.get(payload.consumerId);
      if (!consumer) return reject(socket, id, "CONSUMER_NOT_FOUND");
      await consumer.resume();
      return respond(socket, id, {});
    }
    if (type === "close-consumer") {
      const consumer = peer.consumers.get(payload.consumerId);
      if (consumer) {
        consumer.close();
        peer.consumers.delete(consumer.id);
        peer.consumersByProducer.delete(consumer.producerId);
      }
      return respond(socket, id, {});
    }
    if (type === "close-producer") {
      const producer = peer.producers.get(payload.producerId);
      if (producer) {
        await peer.room.audioObserver.removeProducer({ producerId: producer.id }).catch(() => undefined);
        producer.close();
      }
      peer.producers.delete(payload.producerId);
      return respond(socket, id, {});
    }
    if (type === "heartbeat") return respond(socket, id, { serverTime: Date.now() });
    if (type === "leave") {
      respond(socket, id, {});
      socket.close(1000, "left room");
      return;
    }
    return reject(socket, id, "UNSUPPORTED_REQUEST");
  } catch (error) {
    return reject(socket, id, "MEDIA_OPERATION_FAILED", error instanceof Error ? error.message : "Unknown error");
  }
}

function createPeerSocket(socket) {
  let peer;
  let authPending = false;
  const authTimer = setTimeout(() => {
    if (!peer && socket.readyState === WebSocket.OPEN) socket.close(1008, "authentication timeout");
  }, unauthenticatedTimeoutMs);
  authTimer.unref();

  const authenticate = async (message) => {
    if (authPending) {
      socket.close(1008, "authentication already in progress");
      return;
    }
    authPending = true;
    const authStartedAt = Date.now();
    let consumedIdentity;
    let attemptedIdentity;
    try {
      if (typeof message.ticket !== "string" || message.ticket.length > 8192) throw new Error("VOICE_TICKET_INVALID");
      const identity = authenticateTicket(message.ticket);
      attemptedIdentity = identity;
      const consumed = await callBackend("consume", {
        sessionId: identity.sessionId,
        roomId: Number(identity.roomId),
        userId: Number(identity.userId),
        ticket: message.ticket,
      });
      if (!consumed.valid) throw new Error("VOICE_SESSION_NOT_ACTIVE");
      consumedIdentity = identity;
      const room = await getRoom(identity.roomId);
      if (socket.readyState !== WebSocket.OPEN) throw new Error("VOICE_SOCKET_CLOSED");
      if (room.peers.size >= maxPeersPerRoom) throw new Error("ROOM_FULL");
      if (room.peers.has(identity.userId)) throw new Error("USER_ALREADY_CONNECTED");
      peer = {
        ...identity,
        socket,
        room,
        transports: new Map(),
        producers: new Map(),
        consumers: new Map(),
        consumersByProducer: new Map(),
        requestCache: new Map(),
        requestTimes: [],
        messageQueue: Promise.resolve(),
      };
      room.peers.set(peer.userId, peer);
      metrics.authSuccess += 1;
      clearTimeout(authTimer);
      startLeaseRenewal(peer);
      const existingProducers = [];
      for (const existingPeer of room.peers.values()) {
        for (const producer of existingPeer.producers.values()) {
          existingProducers.push({ producerId: producer.id, userId: existingPeer.userId, kind: producer.kind });
        }
      }
      send(socket, {
        type: "authenticated",
        payload: { userId: peer.userId, maxPeers: maxPeersPerRoom, existingProducers },
      });
      broadcast(room, socket, { type: "member-connected", payload: { userId: peer.userId } });
    } catch (error) {
      metrics.authFailure += 1;
      const reason = error instanceof Error ? error.message : "authentication failed";
      console.warn("voice authentication failed", {
        roomId: attemptedIdentity?.roomId,
        userId: attemptedIdentity?.userId,
        reason,
        elapsedMs: Date.now() - authStartedAt,
      });
      if (consumedIdentity) {
        void callBackend("release", {
          sessionId: consumedIdentity.sessionId,
          roomId: Number(consumedIdentity.roomId),
          userId: Number(consumedIdentity.userId),
        }).catch(() => undefined);
      }
      if (socket.readyState === WebSocket.OPEN) {
        socket.close(1008, reason.slice(0, 100));
      }
    }
  };

  const processRawMessage = async (raw) => {
    let message;
    try {
      message = JSON.parse(raw.toString());
    } catch {
      socket.close(1003, "invalid JSON");
      return;
    }
    if (!message || typeof message !== "object" || Array.isArray(message)) {
      socket.close(1008, "invalid request");
      return;
    }
    if (!peer) {
      if (message.type !== "auth" || typeof message.ticket !== "string") {
        socket.close(1008, "ticket required");
        return;
      }
      void authenticate(message);
      return;
    }
    if (typeof message.id !== "string" || message.id.length < 1 || message.id.length > 128
        || typeof message.type !== "string" || !message.payload || typeof message.payload !== "object"
        || Array.isArray(message.payload)) {
      socket.close(1008, "invalid request envelope");
      return;
    }
    const now = Date.now();
    peer.requestTimes = peer.requestTimes.filter((time) => now - time < peerRequestWindowMs);
    if (peer.requestTimes.length >= maxRequestsPerPeerWindow) {
      metrics.rateLimited += 1;
      reject(socket, message.id, "RATE_LIMITED", "Too many requests");
      return;
    }
    peer.requestTimes.push(now);
    peer.messageQueue = peer.messageQueue.then(async () => {
      if (socket.readyState !== WebSocket.OPEN) return;
      pruneRequestCache(peer);
      const fingerprint = requestFingerprint(message);
      const cached = peer.requestCache.get(message.id);
      if (cached) {
        if (cached.fingerprint !== fingerprint) {
          reject(socket, message.id, "REQUEST_ID_REUSED", "Request ID was already used with another payload");
        } else {
          send(socket, cached.response);
        }
        return;
      }
      const response = await handleMessage(peer, socket, message);
      if (response && response.id === message.id) {
        if (!response.ok) {
          metrics.requestFailure += 1;
          console.warn("voice request rejected", {
            roomId: peer.roomId,
            type: message.type,
            code: response.error?.code,
            reason: response.error?.message,
            producerId: message.type === "consume" ? message.payload?.producerId : undefined,
          });
        }
        peer.requestCache.set(message.id, { fingerprint, response, expiresAt: Date.now() + requestCacheTtlMs });
        pruneRequestCache(peer);
      }
    }).catch((error) => {
      metrics.requestFailure += 1;
      console.warn("voice request failed", {
        roomId: peer.roomId,
        type: message.type,
        reason: error instanceof Error ? error.message : "Unknown error",
        producerId: message.type === "consume" ? message.payload?.producerId : undefined,
      });
      reject(socket, message.id, "MEDIA_OPERATION_FAILED", error instanceof Error ? error.message : "Unknown error");
    });
  };

  socket.on("message", (raw) => {
    void processRawMessage(raw);
  });
  socket.on("close", () => {
    clearTimeout(authTimer);
    if (!peer) return;
    const room = peer.room;
    removePeer(peer);
    broadcast(room, null, { type: "member-disconnected", payload: { userId: peer.userId } });
  });
  socket.on("error", () => {
    if (peer) removePeer(peer);
  });
}

async function main() {
  if (secret.length < 32) throw new Error("KARAOKE_MEDIA_TICKET_SECRET must contain at least 32 characters");
  if (backendServiceSecret.length < 32) throw new Error("KARAOKE_MEDIA_SERVICE_SECRET must contain at least 32 characters");
  if (!Number.isFinite(unauthenticatedTimeoutMs) || unauthenticatedTimeoutMs < 1000) {
    throw new Error("MEDIASOUP_AUTH_TIMEOUT_MS must be at least 1000");
  }
  if (!Number.isInteger(ticketClockToleranceSeconds) || ticketClockToleranceSeconds < 0 || ticketClockToleranceSeconds > 300) {
    throw new Error("KARAOKE_MEDIA_TICKET_CLOCK_TOLERANCE_SECONDS must be between 0 and 300");
  }
  for (const origin of allowedOrigins) {
    let parsed;
    try { parsed = new URL(origin); } catch { throw new Error(`Invalid MEDIASOUP_ALLOWED_ORIGINS entry: ${origin}`); }
    if (parsed.origin !== origin || !["http:", "https:"].includes(parsed.protocol)) {
      throw new Error(`MEDIASOUP_ALLOWED_ORIGINS must contain exact http(s) origins: ${origin}`);
    }
  }
  worker = await mediasoup.createWorker({
    logLevel: process.env.MEDIASOUP_LOG_LEVEL || "warn",
    rtcMinPort,
    rtcMaxPort,
  });
  worker.on("died", (error) => {
    console.error("mediasoup worker died", error);
    process.exit(1);
  });

  const server = http.createServer((request, response) => {
    if (request.url === "/health" || request.url === "/health/ready") {
      response.writeHead(200, { "content-type": "application/json" });
      response.end(JSON.stringify({ status: "ok", rooms: rooms.size, workerPid: worker.pid }));
      return;
    }
    if (request.url === "/metrics") {
      let peers = 0;
      let transports = 0;
      let producers = 0;
      let consumers = 0;
      for (const room of rooms.values()) {
        peers += room.peers.size;
        for (const peer of room.peers.values()) {
          transports += peer.transports.size;
          producers += peer.producers.size;
          consumers += peer.consumers.size;
        }
      }
      const lines = [
        "# HELP moodify_karaoke_rooms Current mediasoup rooms.",
        "# TYPE moodify_karaoke_rooms gauge",
        `moodify_karaoke_rooms ${rooms.size}`,
        "# HELP moodify_karaoke_peers Current authenticated voice peers.",
        "# TYPE moodify_karaoke_peers gauge",
        `moodify_karaoke_peers ${peers}`,
        "# HELP moodify_karaoke_transports Current WebRTC transports.",
        "# TYPE moodify_karaoke_transports gauge",
        `moodify_karaoke_transports ${transports}`,
        "# HELP moodify_karaoke_producers Current audio producers.",
        "# TYPE moodify_karaoke_producers gauge",
        `moodify_karaoke_producers ${producers}`,
        "# HELP moodify_karaoke_consumers Current audio consumers.",
        "# TYPE moodify_karaoke_consumers gauge",
        `moodify_karaoke_consumers ${consumers}`,
        "# HELP moodify_karaoke_auth_total Voice signaling authentication attempts.",
        "# TYPE moodify_karaoke_auth_total counter",
        `moodify_karaoke_auth_total{result=\"success\"} ${metrics.authSuccess}`,
        `moodify_karaoke_auth_total{result=\"failure\"} ${metrics.authFailure}`,
        "# HELP moodify_karaoke_rate_limited_total Signaling requests rejected by rate limits.",
        "# TYPE moodify_karaoke_rate_limited_total counter",
        `moodify_karaoke_rate_limited_total ${metrics.rateLimited}`,
        "# HELP moodify_karaoke_request_failures_total Signaling operations that failed.",
        "# TYPE moodify_karaoke_request_failures_total counter",
        `moodify_karaoke_request_failures_total ${metrics.requestFailure}`,
      ];
      response.writeHead(200, { "content-type": "text/plain; version=0.0.4; charset=utf-8" });
      response.end(`${lines.join("\n")}\n`);
      return;
    }
    response.writeHead(404);
    response.end();
  });
  const wss = new WebSocketServer({ server, path: "/ws", maxPayload: 64 * 1024 });
  if (allowedOrigins.size === 0) throw new Error("MEDIASOUP_ALLOWED_ORIGINS must list the application origins");
  wss.on("connection", (socket, request) => {
    const origin = request.headers.origin;
    if (typeof origin !== "string" || !allowedOrigins.has(origin)) {
      socket.close(1008, "origin not allowed");
      return;
    }
    createPeerSocket(socket);
  });
  server.listen(port, signalingListenIp, () => console.log(`Moodify mediasoup signaling listening on ${signalingListenIp}:${port}`));
}

setInterval(() => {
  const now = Date.now();
  for (const [jti, expiry] of replayedTickets) if (expiry <= now) replayedTickets.delete(jti);
}, 60_000).unref();

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
