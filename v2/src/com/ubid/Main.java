package com.ubid;

import com.ubid.model.ModelParameters;
import com.ubid.pipeline.PipelineOrchestrator;
import com.ubid.pipeline.PipelineOrchestrator.PipelineStatus;
import com.ubid.server.ApiServer;
import com.ubid.storage.DataStore;

import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {

    public static void main(String[] args) throws Exception {
        Path base = Paths.get("").toAbsolutePath();
        System.out.println("Working directory: " + base);

        DataStore dataStore = new DataStore(base);
        dataStore.ensureDirectories();

        ModelParameters params = dataStore.loadModelParams();
        PipelineStatus  status = new PipelineStatus();

        // Sync live stats from persisted data
        try {
            status.registrySize  = dataStore.getRegistryCount();
            status.pendingReview = dataStore.getReviewQueueCount();
        } catch (Exception e) {
            System.err.println("Warning: could not read existing data - " + e.getMessage());
        }

        PipelineOrchestrator orchestrator =
            new PipelineOrchestrator(dataStore, params, status);

        Thread pipelineThread = new Thread(orchestrator, "ubid-pipeline");
        pipelineThread.setDaemon(true);
        pipelineThread.start();

        Path webDir = base.resolve("web");
        ApiServer apiServer = new ApiServer(8080, dataStore, params, status, webDir);
        apiServer.start();

        System.out.println("==============================================");
        System.out.println("  UBID Platform started");
        System.out.println("  Server started at   http://localhost:8080");
        System.out.println("  Reviewer UI at      http://localhost:8080/reviewer");
        System.out.println("==============================================");
        System.out.println("  Drop dept_*.csv + confirmation.txt into landing/");
        System.out.println("==============================================");

        // Keep main thread alive
        Thread.currentThread().join();
    }
}
