package com.tesults.testng;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

final class TesultsOutput {
    private static final String INTEGRATION_NAME = "tesults-testng";
    private static final String INTEGRATION_VERSION = integrationVersion();
    private static final String TEST_FRAMEWORK = "testng";

    private TesultsOutput() {
    }

    static void addMetadata(Map<String, Object> data) {
        data.put("metadata", metadata());
    }

    static void write(String outputFile, List<Map<String, Object>> currentCases) throws IOException {
        Path path = Paths.get(outputFile).toAbsolutePath();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (RandomAccessFile randomAccessFile = new RandomAccessFile(path.toFile(), "rw");
             FileChannel channel = randomAccessFile.getChannel();
             FileLock ignored = channel.lock()) {
            JSONArray mergedCases = existingCases(channel);
            for (Map<String, Object> testCase : currentCases) {
                mergedCases.put(new JSONObject(testCase));
            }

            JSONObject results = new JSONObject();
            results.put("cases", mergedCases);
            JSONObject payload = new JSONObject();
            payload.put("target", "");
            payload.put("results", results);
            payload.put("metadata", new JSONObject(metadata()));

            byte[] encoded = payload.toString().getBytes(StandardCharsets.UTF_8);
            channel.truncate(0);
            channel.position(0);
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static JSONArray existingCases(FileChannel channel) throws IOException {
        long size = channel.size();
        if (size == 0) {
            return new JSONArray();
        }
        if (size > Integer.MAX_VALUE) {
            throw new IOException("Existing Tesults results file is too large");
        }

        ByteBuffer buffer = ByteBuffer.allocate((int) size);
        channel.position(0);
        while (buffer.hasRemaining() && channel.read(buffer) != -1) {
            // Read the complete file while holding the process-wide file lock.
        }
        String json = new String(buffer.array(), StandardCharsets.UTF_8);
        JSONObject existing = new JSONObject(json);
        return existing.getJSONObject("results").getJSONArray("cases");
    }

    private static Map<String, Object> metadata() {
        Map<String, Object> metadata = new HashMap<String, Object>();
        metadata.put("integration_name", INTEGRATION_NAME);
        metadata.put("integration_version", INTEGRATION_VERSION);
        metadata.put("test_framework", TEST_FRAMEWORK);
        return metadata;
    }

    private static String integrationVersion() {
        Properties properties = new Properties();
        try (InputStream stream = TesultsOutput.class.getResourceAsStream("version.properties")) {
            if (stream == null) {
                return "unknown";
            }
            properties.load(stream);
            return properties.getProperty("version", "unknown");
        } catch (IOException ex) {
            return "unknown";
        }
    }
}
