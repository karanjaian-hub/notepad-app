package com.notepad.notepad_app.verticles;

import com.notepad.notepad_app.util.JwtUtil;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.JWTAuthHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.sqlclient.Pool;

public class RouterVerticle extends VerticleBase {

  private final Pool pool;
  private JWTAuth jwtAuth;

  public RouterVerticle(Pool pool) {
    this.pool = pool;
  }

  @Override
  public Future<Void> start() {
    jwtAuth = JwtUtil.createJwtProvider(vertx);

    Router router = Router.router(vertx);

    // Parse request bodies (required for POST/PUT)
    router.route().handler(BodyHandler.create());

    // Redirect root to app
    router.get("/").handler(ctx -> ctx.redirect("/app/index.html"));

    // Serve frontend static files from resources/webroot
    router.route("/app/*").handler(StaticHandler.create("webroot"));

    // ── Public auth routes ────────────────────────────────────────
    router.post("/api/auth/signup").handler(this::handleSignup);
    router.post("/api/auth/login").handler(this::handleLogin);

    // ── Protected routes — JWT required ───────────────────────────
    router.route("/api/notes/*").handler(JWTAuthHandler.create(jwtAuth));
    router.route("/api/queue/*").handler(JWTAuthHandler.create(jwtAuth));

    // Notes — GET and DELETE stay direct, POST and PUT go through queue
    router.get("/api/notes").handler(this::handleGetNotes);
    router.post("/api/notes").handler(this::handleCreateNoteQueued);
    router.put("/api/notes/:id").handler(this::handleUpdateNoteQueued);
    router.delete("/api/notes/:id").handler(this::handleDeleteNote);

    // Queue status endpoint
    router.get("/api/queue/status").handler(this::handleQueueStatus);

    return vertx.createHttpServer()
      .requestHandler(router)
      .listen(8080)
      .onSuccess(s -> System.out.println("🚀 Server running on http://localhost:8080"))
      .mapEmpty();
  }

  // ═════════════════════════════════════════════════════════════════
  // AUTH HANDLERS
  // ═════════════════════════════════════════════════════════════════

  private void handleSignup(RoutingContext ctx) {
    JsonObject body = ctx.body().asJsonObject();
    if (body == null
      || !body.containsKey("username")
      || !body.containsKey("email")
      || !body.containsKey("password")) {
      sendError(ctx, 400, "Missing fields");
      return;
    }

    vertx.eventBus().<JsonObject>request("auth.signup", body)
      .onSuccess(reply -> {
        JsonObject result = reply.body();
        int status = result.getBoolean("success") ? 201 : 409;
        ctx.response()
          .setStatusCode(status)
          .putHeader("Content-Type", "application/json")
          .end(result.encode());
      })
      .onFailure(err -> sendError(ctx, 500, err.getMessage()));
  }

  private void handleLogin(RoutingContext ctx) {
    JsonObject body = ctx.body().asJsonObject();
    if (body == null
      || !body.containsKey("email")
      || !body.containsKey("password")) {
      sendError(ctx, 400, "Missing fields");
      return;
    }

    vertx.eventBus().<JsonObject>request("auth.login", body)
      .onSuccess(reply -> {
        JsonObject result = reply.body();
        if (result.getBoolean("success")) {
          String token = JwtUtil.generateToken(
            jwtAuth,
            result.getInteger("userId"),
            result.getString("username")
          );
          ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject()
              .put("token",    token)
              .put("username", result.getString("username"))
              .encode());
        } else {
          ctx.response()
            .setStatusCode(401)
            .putHeader("Content-Type", "application/json")
            .end(result.encode());
        }
      })
      .onFailure(err -> sendError(ctx, 500, err.getMessage()));
  }

  // ═════════════════════════════════════════════════════════════════
  // NOTE HANDLERS — direct (GET, DELETE)
  // ═════════════════════════════════════════════════════════════════

  private void handleGetNotes(RoutingContext ctx) {
    int userId;
    try { userId = getUserId(ctx); }
    catch (Exception e) { sendError(ctx, 401, "Invalid token"); return; }

    JsonObject req = new JsonObject().put("userId", userId);

    vertx.eventBus().<JsonObject>request("notes.getAll", req)
      .onSuccess(reply -> ctx.response()
        .putHeader("Content-Type", "application/json")
        .end(reply.body().encode()))
      .onFailure(err -> sendError(ctx, 500, err.getMessage()));
  }

  private void handleDeleteNote(RoutingContext ctx) {
    int userId;
    int noteId;
    try {
      userId = getUserId(ctx);
      noteId = Integer.parseInt(ctx.pathParam("id"));
    } catch (Exception e) {
      sendError(ctx, 400, "Invalid request");
      return;
    }

    JsonObject req = new JsonObject()
      .put("noteId", noteId)
      .put("userId", userId);

    vertx.eventBus().<JsonObject>request("notes.delete", req)
      .onSuccess(reply -> ctx.response()
        .putHeader("Content-Type", "application/json")
        .end(reply.body().encode()))
      .onFailure(err -> sendError(ctx, 500, err.getMessage()));
  }

  // ═════════════════════════════════════════════════════════════════
  // NOTE HANDLERS — queued (POST, PUT)
  // ═════════════════════════════════════════════════════════════════

  private void handleCreateNoteQueued(RoutingContext ctx) {
    JsonObject body = ctx.body().asJsonObject();
    if (body == null || !body.containsKey("title")) {
      sendError(ctx, 400, "Title is required");
      return;
    }

    int userId;
    try { userId = getUserId(ctx); }
    catch (Exception e) { sendError(ctx, 401, "Invalid token"); return; }

    // Build the task — includes operation type so QueueVerticle
    // knows which NoteVerticle handler to call
    JsonObject task = new JsonObject()
      .put("operation", "create")
      .put("userId",    userId)
      .put("title",     body.getString("title"))
      .put("content",   body.getString("content", ""));

    // Send to the queue instead of directly to notes.create
    vertx.eventBus().<JsonObject>request("queue.note.save", task)
      .onSuccess(reply -> {
        JsonObject result = reply.body();
        ctx.response()
          .setStatusCode(202) // 202 Accepted — queued, not yet saved
          .putHeader("Content-Type", "application/json")
          .end(new JsonObject()
            .put("success",   true)
            .put("queued",    true)
            .put("messageId", result.getString("messageId"))
            .put("position",  result.getInteger("position"))
            .put("message",   "Note queued for saving")
            .encode());
      })
      .onFailure(err -> sendError(ctx, 500, err.getMessage()));
  }

  private void handleUpdateNoteQueued(RoutingContext ctx) {
    JsonObject body = ctx.body().asJsonObject();
    if (body == null) {
      sendError(ctx, 400, "Request body required");
      return;
    }

    int userId;
    int noteId;
    try {
      userId = getUserId(ctx);
      noteId = Integer.parseInt(ctx.pathParam("id"));
    } catch (Exception e) {
      sendError(ctx, 400, "Invalid request");
      return;
    }

    // Build the task
    JsonObject task = new JsonObject()
      .put("operation", "update")
      .put("userId",    userId)
      .put("noteId",    noteId)
      .put("title",     body.getString("title",   ""))
      .put("content",   body.getString("content", ""));

    vertx.eventBus().<JsonObject>request("queue.note.save", task)
      .onSuccess(reply -> ctx.response()
        .setStatusCode(202)
        .putHeader("Content-Type", "application/json")
        .end(new JsonObject()
          .put("success", true)
          .put("queued",  true)
          .put("message", "Note update queued")
          .encode()))
      .onFailure(err -> sendError(ctx, 500, err.getMessage()));
  }

  // ═════════════════════════════════════════════════════════════════
  // QUEUE STATUS HANDLER
  // ═════════════════════════════════════════════════════════════════

  private void handleQueueStatus(RoutingContext ctx) {
    vertx.eventBus().<JsonObject>request("queue.status", new JsonObject())
      .onSuccess(reply -> ctx.response()
        .putHeader("Content-Type", "application/json")
        .end(reply.body().encode()))
      .onFailure(err -> sendError(ctx, 500, err.getMessage()));
  }

  // ═════════════════════════════════════════════════════════════════
  // HELPERS
  // ═════════════════════════════════════════════════════════════════

  private int getUserId(RoutingContext ctx) {
    JsonObject principal = ctx.user().principal();

    if (principal.containsKey("userId")) {
      return principal.getInteger("userId");
    }

    JsonObject accessToken = principal.getJsonObject("access_token");
    if (accessToken != null && accessToken.containsKey("userId")) {
      return accessToken.getInteger("userId");
    }

    throw new RuntimeException("userId not found in JWT principal: " + principal.encode());
  }

  private void sendError(RoutingContext ctx, int code, String message) {
    ctx.response()
      .setStatusCode(code)
      .putHeader("Content-Type", "application/json")
      .end(new JsonObject().put("message", message).encode());
  }
}
