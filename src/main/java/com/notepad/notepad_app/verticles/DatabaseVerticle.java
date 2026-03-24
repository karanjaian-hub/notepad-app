package com.notepad.notepad_app.verticles;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;

public class DatabaseVerticle extends VerticleBase {

  private final Pool pool;

  public DatabaseVerticle(Pool pool) {
    this.pool = pool;
  }

  @Override
  public Future<Void> start() {

    String createUsersTable = """
            CREATE TABLE IF NOT EXISTS users (
                id SERIAL PRIMARY KEY,
                username VARCHAR(50) UNIQUE NOT NULL,
                email VARCHAR(100) UNIQUE NOT NULL,
                password_hash VARCHAR(255) NOT NULL,
                created_at TIMESTAMP DEFAULT NOW()
            );
        """;

    String createNotesTable = """
            CREATE TABLE IF NOT EXISTS notes (
                id SERIAL PRIMARY KEY,
                user_id INT REFERENCES users(id) ON DELETE CASCADE,
                title VARCHAR(200) NOT NULL,
                content TEXT,
                created_at TIMESTAMP DEFAULT NOW(),
                updated_at TIMESTAMP DEFAULT NOW()
            );
        """;

    return pool.withConnection((SqlConnection conn) ->
        conn.query(createUsersTable).execute()
          .compose(v -> conn.query(createNotesTable).execute())
      )
      .onSuccess(v -> System.out.println("✅ DATABASE TABLES ARE READY"))
      .onFailure(err -> System.err.println("❌ Failed to create tables: " + err.getMessage()))
      .mapEmpty();
  }
}
