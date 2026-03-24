package com.notepad.notepad_app.util;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.ext.auth.PubSecKeyOptions;

public class JwtUtil {

  private static final String SECRET = System.getenv().getOrDefault(
    "JWT_SECRET",
    "my_super_secret_key_change_this_to_something_long_enough"
  );

  public static JWTAuth createJwtProvider(Vertx vertx) {
    JWTAuthOptions config = new JWTAuthOptions()
      .addPubSecKey(new PubSecKeyOptions()
        .setAlgorithm("HS256")
        .setBuffer(SECRET));

    return JWTAuth.create(vertx, config);
  }

  public static String generateToken(JWTAuth jwtAuth, int userId, String username) {
    return jwtAuth.generateToken(
      new JsonObject()
        .put("userId", userId)
        .put("username", username),
      new JWTOptions().setExpiresInSeconds(3600)
    );
  }
}
