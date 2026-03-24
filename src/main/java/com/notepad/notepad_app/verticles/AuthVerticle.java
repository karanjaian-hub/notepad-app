package com.notepad.notepad_app.verticles;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.mindrot.jbcrypt.BCrypt;

public class AuthVerticle extends VerticleBase {

  private final Pool pool;

  public AuthVerticle(Pool pool) {
    this.pool = pool;
  }

  @Override
  public Future<Void> start() {
// Registering the event bus handlers,, RouterVerticle will call these addresses..
    vertx.eventBus().consumer("auth.signup", this::handleSignup);
    vertx.eventBus().consumer("auth.login", this::handleLogin);
    return Future.succeededFuture();
  }

  private void handleSignup(Message<JsonObject> message) {
    JsonObject body       = message.body();
    String username       = body.getString("username");
    String email          = body.getString("email");
    String password       = body.getString("password");
    String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());

    pool.preparedQuery(
        "INSERT INTO users (username, email, password_hash) VALUES ($1, $2, $3) RETURNING id"
      )
      .execute(Tuple.of(username, email, hashedPassword))
      .onSuccess(rows -> {
        int newUserId = rows.iterator().next().getInteger("id");
        message.reply(new JsonObject()
          .put("success", true)
          .put("userId", newUserId)
          .put("message", "User created successfully"));
      })
      .onFailure(err -> {
        String errorMsg = err.getMessage().contains("23505")
          ? "Username or email already exists"
          : "Signup failed: " + err.getMessage();
        message.reply(new JsonObject()
          .put("success", false)
          .put("message", errorMsg));
      });
  }

  private void handleLogin(Message<JsonObject> message) {
    JsonObject body = message.body();
    String email    = body.getString("email");
    String password = body.getString("password");

    pool.preparedQuery("SELECT id, username, password_hash FROM users WHERE email = $1")
      .execute(Tuple.of(email))
      .onSuccess(rows -> {
        if (rows.rowCount() == 0) {
          message.reply(new JsonObject()
            .put("success", false)
            .put("message", "Invalid email or password"));
          return;
        }
        var row           = rows.iterator().next();
        String storedHash = row.getString("password_hash");

        if (BCrypt.checkpw(password, storedHash)) {
          message.reply(new JsonObject()
            .put("success", true)
            .put("userId", row.getInteger("id"))
            .put("username", row.getString("username")));
        } else {
          message.reply(new JsonObject()
            .put("success", false)
            .put("message", "Invalid email or password"));
        }
      })
      .onFailure(err -> message.reply(new JsonObject()
        .put("success", false)
        .put("message", "Login failed: " + err.getMessage())));
  }
}
