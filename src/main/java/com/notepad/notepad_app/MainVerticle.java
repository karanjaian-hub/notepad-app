package com.notepad.notepad_app;

import com.notepad.notepad_app.verticles.AuthVerticle;
import com.notepad.notepad_app.verticles.DatabaseVerticle;
import com.notepad.notepad_app.verticles.NoteVerticle;
import com.notepad.notepad_app.verticles.QueueVerticle;
import com.notepad.notepad_app.verticles.RouterVerticle;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;

public class MainVerticle extends VerticleBase {

  @Override
  public Future<Void> start() {

    // ── Read from environment variables ───────────────────────
    // Railway sets these automatically when you add a PostgreSQL plugin
    // Locally they fall back to your laptop values
    String dbHost     = System.getenv().getOrDefault("PGHOST",     "localhost");
    String dbPort     = System.getenv().getOrDefault("PGPORT",     "5432");
    String dbName     = System.getenv().getOrDefault("PGDATABASE", "notepad_db");
    String dbUser     = System.getenv().getOrDefault("PGUSER",     "postgres");
    String dbPassword = System.getenv().getOrDefault("PGPASSWORD", "your_password");

    // Railway assigns a random port via PORT env variable
    // Fall back to 8080 for local development
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

    System.out.println("🔧 Connecting to database: " + dbHost + ":" + dbPort + "/" + dbName);
    System.out.println("🔧 HTTP server will start on port: " + port);

    PgConnectOptions connectOptions = new PgConnectOptions()
      .setPort(Integer.parseInt(dbPort))
      .setHost(dbHost)
      .setDatabase(dbName)
      .setUser(dbUser)
      .setPassword(dbPassword);

    PoolOptions poolOptions = new PoolOptions().setMaxSize(5);

    Pool pool = PgBuilder.pool()
      .with(poolOptions)
      .connectingTo(connectOptions)
      .using(vertx)
      .build();

    return vertx.deployVerticle(new DatabaseVerticle(pool))
      .compose(v -> vertx.deployVerticle(new AuthVerticle(pool)))
      .compose(v -> vertx.deployVerticle(new NoteVerticle(pool)))
      .compose(v -> vertx.deployVerticle(new QueueVerticle()))
      .compose(v -> vertx.deployVerticle(new RouterVerticle(pool, port)))
      .onSuccess(v -> System.out.println("✅ All verticles deployed successfully"))
      .mapEmpty();
  }
}
