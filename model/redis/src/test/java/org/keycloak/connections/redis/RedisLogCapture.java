/*
 * Copyright 2025 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.connections.redis;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Attaches a JUL handler to the {@code org.keycloak.connections.redis} logger and exposes
 * the captured messages. Use in a try-with-resources block.
 *
 * <p>JBoss Logging delegates to {@code java.util.logging} when no JBoss LogManager is on
 * the classpath, which is the case in plain {@code mvn test}. That's good enough for our
 * test gate: we just need to prove that what production code <em>does log</em> does not
 * contain secret material.</p>
 */
final class RedisLogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level originalLevel;
    private final Handler handler;
    private final List<LogRecord> records = new ArrayList<>();

    static RedisLogCapture start() {
        return new RedisLogCapture("org.keycloak.connections.redis");
    }

    private RedisLogCapture(String loggerName) {
        this.logger = Logger.getLogger(loggerName);
        this.originalLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        this.handler = new Handler() {
            @Override public void publish(LogRecord r) { records.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
    }

    /** All captured messages, concatenated. */
    String allMessages() {
        StringBuilder sb = new StringBuilder();
        for (LogRecord r : records) {
            sb.append(r.getLevel()).append(' ').append(r.getMessage()).append('\n');
        }
        return sb.toString();
    }

    boolean contains(String substring) {
        return allMessages().contains(substring);
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setLevel(originalLevel);
    }
}
