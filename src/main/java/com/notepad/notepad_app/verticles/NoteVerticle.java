package com.notepad.notepad_app.verticles;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

public class NoteVerticle extends VerticleBase {

  private final Pool pool;

  public NoteVerticle(Pool pool) {
    this.pool = pool;
  }

  @Override
  public Future<Void> start() {
    vertx.eventBus().consumer("notes.getAll", this::handleGetAll);
    vertx.eventBus().consumer("notes.create", this::handleCreate);
    vertx.eventBus().consumer("notes.update", this::handleUpdate);
    vertx.eventBus().consumer("notes.delete", this::handleDelete);
    return Future.succeededFuture();
  }

  private void handleGetAll(Message<JsonObject> message) {
    int userId = message.body().getInteger("userId");

    pool.preparedQuery(
        "SELECT id, title, content, created_at, updated_at FROM notes WHERE user_id = $1 ORDER BY updated_at DESC"
      )
      .execute(Tuple.of(userId))
      .onSuccess(rows -> {
        JsonArray notes = new JsonArray();
        for (Row row : rows) {
          notes.add(new JsonObject()
            .put("id",        row.getInteger("id"))
            .put("title",     row.getString("title"))
            .put("content",   row.getString("content"))
            // ✅ Append Z to make it a valid UTC ISO string
            // JavaScript's new Date() parses this correctly
            .put("createdAt", row.getLocalDateTime("created_at").toString() + "Z")
            .put("updatedAt", row.getLocalDateTime("updated_at").toString() + "Z"));
        }
        message.reply(new JsonObject()
          .put("success", true)
          .put("notes", notes));
      })
      .onFailure(err -> message.reply(new JsonObject()
        .put("success", false)
        .put("message", err.getMessage())));
  }

  private void handleCreate(Message<JsonObject> message) {
    JsonObject body = message.body();
    int userId      = body.getInteger("userId");
    String title    = body.getString("title");
    String content  = body.getString("content", "");

    pool.preparedQuery(
        "INSERT INTO notes (user_id, title, content) VALUES ($1, $2, $3) RETURNING id, created_at"
      )
      .execute(Tuple.of(userId, title, content))
      .onSuccess(rows -> {
        var row = rows.iterator().next();
        message.reply(new JsonObject()
          .put("success", true)
          .put("id",      row.getInteger("id"))
          .put("message", "Note created"));
      })
      .onFailure(err -> message.reply(new JsonObject()
        .put("success", false)
        .put("message", err.getMessage())));
  }

  private void handleUpdate(Message<JsonObject> message) {
    JsonObject body = message.body();
    int noteId      = body.getInteger("noteId");
    int userId      = body.getInteger("userId");
    String title    = body.getString("title");
    String content  = body.getString("content", "");

    pool.preparedQuery(
        "UPDATE notes SET title=$1, content=$2, updated_at=NOW() WHERE id=$3 AND user_id=$4"
      )
      .execute(Tuple.of(title, content, noteId, userId))
      .onSuccess(rows -> message.reply(new JsonObject()
        .put("success", rows.rowCount() > 0)
        .put("message", rows.rowCount() > 0 ? "Updated" : "Note not found")))
      .onFailure(err -> message.reply(new JsonObject()
        .put("success", false)
        .put("message", err.getMessage())));
  }

  private void handleDelete(Message<JsonObject> message) {
    int noteId = message.body().getInteger("noteId");
    int userId = message.body().getInteger("userId");

    pool.preparedQuery("DELETE FROM notes WHERE id=$1 AND user_id=$2")
      .execute(Tuple.of(noteId, userId))
      .onSuccess(rows -> message.reply(new JsonObject()
        .put("success", rows.rowCount() > 0)
        .put("message", rows.rowCount() > 0 ? "Deleted" : "Note not found")))
      .onFailure(err -> message.reply(new JsonObject()
        .put("success", false)
        .put("message", err.getMessage())));
  }
}
