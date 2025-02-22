/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.ingest.geoip;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.flush.FlushRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.hash.MessageDigests;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.DeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.index.reindex.DeleteByQueryAction;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.ingest.geoip.GeoIpTaskState.Metadata;
import org.elasticsearch.ingest.geoip.stats.GeoIpDownloaderStats;
import org.elasticsearch.persistent.AllocatedPersistentTask;
import org.elasticsearch.persistent.PersistentTasksCustomMetadata.PersistentTask;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.threadpool.Scheduler;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Main component responsible for downloading new GeoIP databases.
 * New databases are downloaded in chunks and stored in .geoip_databases index
 * Downloads are verified against MD5 checksum provided by the server
 * Current state of all stored databases is stored in cluster state in persistent task state
 */
public class GeoIpDownloader extends AllocatedPersistentTask {

    private static final Logger logger = LogManager.getLogger(GeoIpDownloader.class);

    public static final Setting<TimeValue> POLL_INTERVAL_SETTING = Setting.timeSetting("ingest.geoip.downloader.poll.interval",
        TimeValue.timeValueDays(3), TimeValue.timeValueDays(1), Property.Dynamic, Property.NodeScope);
    public static final Setting<String> ENDPOINT_SETTING = Setting.simpleString("ingest.geoip.downloader.endpoint",
        "https://geoip.elastic.co/v1/database", Property.NodeScope);

    public static final String GEOIP_DOWNLOADER = "geoip-downloader";
    static final String DATABASES_INDEX = ".geoip_databases";
    static final String DATABASES_INDEX_PATTERN = DATABASES_INDEX + "*";
    static final int MAX_CHUNK_SIZE = 1024 * 1024;

    private final Client client;
    private final HttpClient httpClient;
    private final ClusterService clusterService;
    private final ThreadPool threadPool;
    private final String endpoint;

    //visible for testing
    protected volatile GeoIpTaskState state;
    private volatile TimeValue pollInterval;
    private volatile Scheduler.ScheduledCancellable scheduled;
    private volatile GeoIpDownloaderStats stats = GeoIpDownloaderStats.EMPTY;

    GeoIpDownloader(Client client, HttpClient httpClient, ClusterService clusterService, ThreadPool threadPool, Settings settings,
                    long id, String type, String action, String description, TaskId parentTask, Map<String, String> headers) {
        super(id, type, action, description, parentTask, headers);
        this.httpClient = httpClient;
        this.client = client;
        this.clusterService = clusterService;
        this.threadPool = threadPool;
        endpoint = ENDPOINT_SETTING.get(settings);
        pollInterval = POLL_INTERVAL_SETTING.get(settings);
        clusterService.getClusterSettings().addSettingsUpdateConsumer(POLL_INTERVAL_SETTING, this::setPollInterval);
    }

    public void setPollInterval(TimeValue pollInterval) {
        this.pollInterval = pollInterval;
        if (scheduled != null && scheduled.cancel()) {
            scheduleNextRun(new TimeValue(1));
        }
    }

    //visible for testing
    void updateDatabases() throws IOException {
        logger.info("updating geoip databases");
        List<Map<String, Object>> response = fetchDatabasesOverview();
        for (Map<String, Object> res : response) {
            if (res.get("name").toString().endsWith(".tgz")) {
                processDatabase(res);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> fetchDatabasesOverview() throws IOException {
        String url = endpoint + "?elastic_geoip_service_tos=agree";
        logger.info("fetching geoip databases overview from [" + url + "]");
        byte[] data = httpClient.getBytes(url);
        try (XContentParser parser = XContentType.JSON.xContent().createParser(NamedXContentRegistry.EMPTY,
            DeprecationHandler.THROW_UNSUPPORTED_OPERATION, data)) {
            return (List<T>) parser.list();
        }
    }

    //visible for testing
    void processDatabase(Map<String, Object> databaseInfo) {
        String name = databaseInfo.get("name").toString().replace(".tgz", "") + ".mmdb";
        String md5 = (String) databaseInfo.get("md5_hash");
        if (state.contains(name) && Objects.equals(md5, state.get(name).getMd5())) {
            updateTimestamp(name, state.get(name));
            return;
        }
        logger.info("updating geoip database [" + name + "]");
        String url = databaseInfo.get("url").toString();
        if (url.startsWith("http") == false) {
            //relative url, add it after last slash (i.e resolve sibling) or at the end if there's no slash after http[s]://
            int lastSlash = endpoint.substring(8).lastIndexOf('/');
            url = (lastSlash != -1 ? endpoint.substring(0, lastSlash + 8) : endpoint) + "/" + url;
        }
        long start = System.currentTimeMillis();
        try (InputStream is = httpClient.get(url)) {
            int firstChunk = state.contains(name) ? state.get(name).getLastChunk() + 1 : 0;
            int lastChunk = indexChunks(name, is, firstChunk, md5, start);
            if (lastChunk > firstChunk) {
                state = state.put(name, new Metadata(start, firstChunk, lastChunk - 1, md5, start));
                updateTaskState();
                stats = stats.successfulDownload(System.currentTimeMillis() - start).count(state.getDatabases().size());
                logger.info("updated geoip database [" + name + "]");
                deleteOldChunks(name, firstChunk);
            }
        } catch (Exception e) {
            stats = stats.failedDownload();
            logger.error("error updating geoip database [" + name + "]", e);
        }
    }

    //visible for testing
    void deleteOldChunks(String name, int firstChunk) {
        BoolQueryBuilder queryBuilder = new BoolQueryBuilder()
            .filter(new MatchQueryBuilder("name", name))
            .filter(new RangeQueryBuilder("chunk").to(firstChunk, false));
        DeleteByQueryRequest request = new DeleteByQueryRequest();
        request.indices(DATABASES_INDEX);
        request.setQuery(queryBuilder);
        client.execute(DeleteByQueryAction.INSTANCE, request, ActionListener.wrap(r -> {
        }, e -> logger.warn("could not delete old chunks for geoip database [" + name + "]", e)));
    }

    //visible for testing
    protected void updateTimestamp(String name, Metadata old) {
        logger.info("geoip database [" + name + "] is up to date, updated timestamp");
        state = state.put(name, new Metadata(old.getLastUpdate(), old.getFirstChunk(), old.getLastChunk(), old.getMd5(),
            System.currentTimeMillis()));
        stats = stats.skippedDownload();
        updateTaskState();
    }

    void updateTaskState() {
        PlainActionFuture<PersistentTask<?>> future = PlainActionFuture.newFuture();
        updatePersistentTaskState(state, future);
        state = ((GeoIpTaskState) future.actionGet().getState());
    }

    //visible for testing
    int indexChunks(String name, InputStream is, int chunk, String expectedMd5, long timestamp) throws IOException {
        MessageDigest md = MessageDigests.md5();
        for (byte[] buf = getChunk(is); buf.length != 0; buf = getChunk(is)) {
            md.update(buf);
            IndexRequest indexRequest = new IndexRequest(DATABASES_INDEX)
                .id(name + "_" + chunk + "_" + timestamp)
                .create(true)
                .source(XContentType.SMILE, "name", name, "chunk", chunk, "data", buf);
            client.index(indexRequest).actionGet();
            chunk++;
        }

        // May take some time before automatic flush kicks in:
        // (otherwise the translog will contain large documents for some time without good reason)
        FlushRequest flushRequest = new FlushRequest(DATABASES_INDEX);
        client.admin().indices().flush(flushRequest).actionGet();
        // Ensure that the chunk documents are visible:
        RefreshRequest refreshRequest = new RefreshRequest(DATABASES_INDEX);
        client.admin().indices().refresh(refreshRequest).actionGet();

        String actualMd5 = MessageDigests.toHexString(md.digest());
        if (Objects.equals(expectedMd5, actualMd5) == false) {
            throw new IOException("md5 checksum mismatch, expected [" + expectedMd5 + "], actual [" + actualMd5 + "]");
        }
        return chunk;
    }

    //visible for testing
    byte[] getChunk(InputStream is) throws IOException {
        byte[] buf = new byte[MAX_CHUNK_SIZE];
        int chunkSize = 0;
        while (chunkSize < MAX_CHUNK_SIZE) {
            int read = is.read(buf, chunkSize, MAX_CHUNK_SIZE - chunkSize);
            if (read == -1) {
                break;
            }
            chunkSize += read;
        }
        if (chunkSize < MAX_CHUNK_SIZE) {
            buf = Arrays.copyOf(buf, chunkSize);
        }
        return buf;
    }

    void setState(GeoIpTaskState state) {
        this.state = state;
    }

    void runDownloader() {
        if (isCancelled() || isCompleted()) {
            return;
        }
        try {
            updateDatabases();
        } catch (Exception e) {
            logger.error("exception during geoip databases update", e);
        }
        try {
            cleanDatabases();
        } catch (Exception e) {
            logger.error("exception during geoip databases cleanup", e);
        }
        scheduleNextRun(pollInterval);
    }

    private void cleanDatabases() {
        long expiredDatabases = state.getDatabases().entrySet().stream()
            .filter(e -> e.getValue().isValid(clusterService.state().metadata().settings()) == false)
            .peek(e -> {
                String name = e.getKey();
                Metadata meta = e.getValue();
                deleteOldChunks(name, meta.getLastChunk() + 1);
                state = state.put(name, new Metadata(meta.getLastUpdate(), meta.getFirstChunk(), meta.getLastChunk(), meta.getMd5(),
                    meta.getLastCheck() - 1));
                updateTaskState();
            }).count();
        stats = stats.expiredDatabases((int) expiredDatabases);
    }

    @Override
    protected void onCancelled() {
        if (scheduled != null) {
            scheduled.cancel();
        }
    }

    @Override
    public GeoIpDownloaderStats getStatus() {
        return isCancelled() || isCompleted() ? null : stats;
    }

    private void scheduleNextRun(TimeValue time) {
        if (threadPool.scheduler().isShutdown() == false) {
            scheduled = threadPool.schedule(this::runDownloader, time, ThreadPool.Names.GENERIC);
        }
    }
}
