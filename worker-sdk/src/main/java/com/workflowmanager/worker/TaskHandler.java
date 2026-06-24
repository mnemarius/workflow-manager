package com.workflowmanager.worker;

/**
 * A unit of work a worker can execute.
 *
 * <p>The engine guarantees <strong>at-least-once</strong> delivery. A handler may be invoked
 * more than once for the same task; implementations must therefore be idempotent. This is the
 * load-bearing assumption of the whole system, not an optional nicety.
 */
@FunctionalInterface
public interface TaskHandler {

    String handle(String input);
}
