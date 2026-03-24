package com.notepad.notepad_app;

import com.notepad.notepad_app.verticles.*;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;

public class MainVerticle extends VerticleBase {

  @Override
  public Future<Void> start() {
// configuring postgresql connection
    PgConnectOptions connectOptions = new PgConnectOptions()
      .setPort(5433)
      .setHost("localhost")
      .setDatabase("notepad_db")
      .setUser("postgres")
      .setPassword("postgres");

// creating the pool for the app,, a pool manages/maintains connections btwn the db and the app..
    PoolOptions poolOptions = new PoolOptions().setMaxSize(5); // can hold a ax of five connections at a time,

    Pool pool = PgBuilder.pool()
      .with(poolOptions)
      .connectingTo(connectOptions)
      .using(vertx)
      .build();

//note: during deployment,, the verticles are arranged in a specific ordered sequence..
//deployment chain..
    return vertx.deployVerticle(new DatabaseVerticle(pool))
      .compose(v -> vertx.deployVerticle(new AuthVerticle(pool)))
      .compose(v -> vertx.deployVerticle(new NoteVerticle(pool)))
      .compose(v -> vertx.deployVerticle(new QueueVerticle()))
      .compose(v -> vertx.deployVerticle(new RouterVerticle(pool)))
      .onSuccess(v -> System.out.println("✅ All verticles deployed successfully"))
      .mapEmpty();
  }
}
