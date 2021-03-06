/*
 * Copyright 2013 David Tinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.qdb.server.output;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.qdb.server.controller.JsonService;
import io.qdb.server.filter.MessageFilterFactory;
import io.qdb.server.model.Output;
import io.qdb.server.repo.Repository;
import io.qdb.server.queue.QueueManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Ensures that each enabled output has a corresponding {@link OutputJob} instance running to process its queue.
 * Detects changes to outputs by listening to repository events.
 */
@Singleton
public class OutputManager implements Closeable, Thread.UncaughtExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OutputManager.class);

    private final Repository repo;
    private final QueueManager queueManager;
    private final OutputHandlerFactory handlerFactory;
    private final MessageFilterFactory messageFilterFactory;
    private final JsonService jsonService;
    private final Map<String, OutputJob> jobs = new ConcurrentHashMap<String, OutputJob>(); // output id -> job
    private final ExecutorService pool;

    @Inject
    public OutputManager(EventBus eventBus, Repository repo, QueueManager queueManager,
                         OutputHandlerFactory handlerFactory, MessageFilterFactory messageFilterFactory,
                         JsonService jsonService) throws IOException {
        this.repo = repo;
        this.queueManager = queueManager;
        this.handlerFactory = handlerFactory;
        this.messageFilterFactory = messageFilterFactory;
        this.jsonService = jsonService;
        this.pool = new ThreadPoolExecutor(1, Integer.MAX_VALUE,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                new ThreadFactoryBuilder().setNameFormat("output-%d").setUncaughtExceptionHandler(this).build());
        eventBus.register(this);
        for (Output output : this.repo.findOutputs(0, -1)) outputChanged(output);
    }

    @Override
    public void close() throws IOException {
        List<OutputJob> busy = new ArrayList<OutputJob>(jobs.values());
        for (OutputJob job : busy) job.stop();
        pool.shutdownNow();
    }

    @Subscribe
    public void handleRepoEvent(Repository.ObjectEvent ev) {
        if (ev.value instanceof Output) {
            Output output = (Output) ev.value;
            if (ev.type == Repository.ObjectEvent.Type.DELETED) {
                synchronized (this) {
                    OutputJob job = jobs.remove(output.getId());
                    if (job != null) job.stop();
                }
            } else {
                outputChanged(output);
            }
        }
    }

    private synchronized void outputChanged(Output o) {
        String oid = o.getId();

        OutputJob existing = jobs.get(oid);
        if (existing != null) {
            existing.outputChanged(o);  // this will cause job to restart if it didn't make the change
            return;
        }

        if (!o.isEnabled()) return;

        OutputJob job = new OutputJob(this, handlerFactory, messageFilterFactory, queueManager, repo, jsonService, oid);
        jobs.put(oid, job);
        pool.execute(job);
    }

    void onOutputJobExit(OutputJob job) {
        jobs.remove(job.getOid());
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        log.error(e.toString(), e);
    }
}
