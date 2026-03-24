package com.notepad.notepad_app.verticles;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.VerticleBase;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.LinkedList;
import java.util.Queue;

public class QueueVerticle extends VerticleBase {

  // ── The actual queue data structure ──────────────────────────────
  // LinkedList implements the Queue interface — first in, first out (FIFO)
  // Each item is a JsonObject containing the task data
  private final Queue<JsonObject> noteQueue = new LinkedList<>();

  // How many notes are currently being processed simultaneously
  private int activeWorkers = 0;

  // Maximum simultaneous database operations allowed
  // This controls how hard you hit the database
  private static final int MAX_WORKERS = 3;

  // Counts for monitoring
  private int totalProcessed = 0;
  private int totalFailed    = 0;

  @Override
  public Future<Void> start() {

    // ── Listen for tasks being added to the queue ─────────────────
    // RouterVerticle will send here instead of directly to notes.create
    vertx.eventBus().consumer("queue.note.save", this::handleEnqueue);

    // ── Listen for queue status requests ─────────────────────────
    // Useful for monitoring — see how many items are waiting
    vertx.eventBus().consumer("queue.status", this::handleStatus);

    // ── Listen for queue drain requests ──────────────────────────
    // Manually trigger processing if queue stalls
    vertx.eventBus().consumer("queue.drain", msg -> drainQueue());

    System.out.println("✅ QueueVerticle started — max workers: " + MAX_WORKERS);
    return Future.succeededFuture();
  }

  // ─────────────────────────────────────────────────────────────────
  // ENQUEUE — add a task to the queue
  // ─────────────────────────────────────────────────────────────────
  private void handleEnqueue(Message<JsonObject> message) {
    JsonObject task = message.body();

    // Add metadata to the task before queuing
    task.put("queuedAt",  System.currentTimeMillis()); // when it was queued
    task.put("messageId", java.util.UUID.randomUUID().toString()); // unique ID

    // Add to the back of the queue
    noteQueue.offer(task);

    System.out.println("📥 Task queued — queue size: " + noteQueue.size()
      + " | active workers: " + activeWorkers);

    // Reply immediately to the browser — do not make the user wait
    // The actual save happens asynchronously in the background
    message.reply(new JsonObject()
      .put("success",   true)
      .put("queued",    true)
      .put("position",  noteQueue.size())
      .put("messageId", task.getString("messageId")));

    // Try to process the queue
    drainQueue();
  }

  // ─────────────────────────────────────────────────────────────────
  // DRAIN — process items from the queue
  // ─────────────────────────────────────────────────────────────────
  private void drainQueue() {

    // Keep pulling tasks from the queue as long as:
    // 1. There are tasks waiting
    // 2. We have not hit the worker limit
    while (!noteQueue.isEmpty() && activeWorkers < MAX_WORKERS) {

      // Take the next task from the FRONT of the queue (FIFO)
      JsonObject task = noteQueue.poll();

      if (task == null) break; // safety check

      activeWorkers++; // one more worker is now active

      long waitTime = System.currentTimeMillis() - task.getLong("queuedAt");
      System.out.println("⚙️  Processing task | waited: " + waitTime + "ms"
        + " | active workers: " + activeWorkers
        + " | remaining in queue: " + noteQueue.size());

      // Process the task
      processTask(task);
    }

    // If queue has items but all workers are busy, they will print a
    // waiting message. drainQueue() is called again when a worker finishes.
    if (!noteQueue.isEmpty() && activeWorkers >= MAX_WORKERS) {
      System.out.println("⏳ Queue has " + noteQueue.size()
        + " waiting — all " + MAX_WORKERS + " workers busy");
    }
  }

  // ─────────────────────────────────────────────────────────────────
  // PROCESS — send the task to NoteVerticle for the actual DB work
  // ─────────────────────────────────────────────────────────────────
  private void processTask(JsonObject task) {

    // Determine which operation this task is
    // The task has an "operation" field: "create", "update", or "delete"
    String operation = task.getString("operation", "create");
    String address   = "notes." + operation; // "notes.create", "notes.update", etc.

    // Send to NoteVerticle via the event bus
    vertx.eventBus().<JsonObject>request(address, task)
      .onSuccess(reply -> {
        activeWorkers--; // this worker is now free
        totalProcessed++;

        JsonObject result = reply.body();
        System.out.println("✅ Task complete | operation: " + operation
          + " | success: " + result.getBoolean("success")
          + " | total processed: " + totalProcessed);

        // Worker is free — try to process more from the queue
        drainQueue();
      })
      .onFailure(err -> {
        activeWorkers--; // free the worker even on failure
        totalFailed++;

        System.err.println("❌ Task failed | operation: " + operation
          + " | error: " + err.getMessage()
          + " | total failed: " + totalFailed);

        // Decide what to do with failed tasks
        handleFailedTask(task, err.getMessage());

        // Continue draining even after failure
        drainQueue();
      });
  }

  // ─────────────────────────────────────────────────────────────────
  // RETRY — what to do when a task fails
  // ─────────────────────────────────────────────────────────────────
  private void handleFailedTask(JsonObject task, String errorMessage) {

    // How many times has this task been retried?
    int retries = task.getInteger("retries", 0);

    if (retries < 3) {
      // Retry up to 3 times
      task.put("retries", retries + 1);
      task.put("lastError", errorMessage);
      task.put("queuedAt", System.currentTimeMillis()); // reset wait time

      // Put it back in the queue for another attempt
      noteQueue.offer(task);

      System.out.println("🔄 Retrying task | attempt: " + (retries + 1)
        + " of 3 | error was: " + errorMessage);

    } else {
      // Gave up after 3 retries — log it as permanently failed
      System.err.println("💀 Task permanently failed after 3 retries"
        + " | operation: " + task.getString("operation")
        + " | userId: "    + task.getInteger("userId")
        + " | error: "     + errorMessage);

      // In a real fintech system you would:
      // 1. Write this to a "dead letter" database table
      // 2. Send an alert to the engineering team
      // 3. Notify the user their action failed
    }
  }

  // ─────────────────────────────────────────────────────────────────
  // STATUS — return current queue statistics
  // ─────────────────────────────────────────────────────────────────
  private void handleStatus(Message<JsonObject> message) {
    message.reply(new JsonObject()
      .put("queueSize",      noteQueue.size())
      .put("activeWorkers",  activeWorkers)
      .put("maxWorkers",     MAX_WORKERS)
      .put("totalProcessed", totalProcessed)
      .put("totalFailed",    totalFailed)
      .put("status",         noteQueue.isEmpty() && activeWorkers == 0
        ? "idle" : "busy"));
  }
}
