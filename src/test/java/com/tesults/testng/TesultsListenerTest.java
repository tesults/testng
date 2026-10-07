package com.tesults.testng;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testng.IExecutionListener;
import org.testng.ITestContext;
import org.testng.ITestNGListener;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;
import org.testng.TestNG;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TesultsListenerTest {
    private static final List<String> TESULTS_PROPERTIES = Arrays.asList(
            "tesultsConfig", "tesultsTarget", "tesultsOutputFile", "tesultsFiles",
            "tesultsNoSuites", "tesultsBuildName", "tesultsBuildDesc",
            "tesultsBuildResult", "tesultsBuildReason", "testng.mode.dryrun"
    );

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void resetState() {
        for (String property : TESULTS_PROPERTIES) {
            System.clearProperty(property);
        }
        TesultsListener.files.clear();
        TesultsListener.customFields.clear();
        TesultsListener.steps.clear();
    }

    @Test
    void neitherTargetNorOutputPreservesDisabledBehavior() {
        CapturingListener listener = new CapturingListener();
        listener.onStart(context());
        listener.onTestSuccess(result("suite", "ignored", null));
        listener.onFinish(context());
        listener.onExecutionFinish();

        assertTrue(listener.disabled);
        assertTrue(listener.uploads.isEmpty());
        assertTrue(listener.cases.isEmpty());
        assertTrue(listener.outputCases.isEmpty());
    }

    @Test
    void testNgDryRunDoesNotProduceResults() {
        Path output = temporaryDirectory.resolve("dry-run.json");
        System.setProperty("tesultsTarget", "target-token");
        System.setProperty("tesultsOutputFile", output.toString());
        System.setProperty("testng.mode.dryrun", "true");
        CapturingListener listener = new CapturingListener();

        listener.onStart(context());
        listener.onTestSuccess(result("suite", "discovered-only", null));
        listener.onFinish(context());
        listener.onExecutionFinish();

        assertTrue(listener.disabled);
        assertTrue(listener.uploads.isEmpty());
        assertTrue(listener.cases.isEmpty());
        assertTrue(listener.outputCases.isEmpty());
        assertFalse(Files.exists(output));
    }

    @Test
    void targetOnlyPreservesCumulativePerContextUploads() {
        System.setProperty("tesultsTarget", "target-token");
        CapturingListener listener = new CapturingListener();

        listener.onStart(context());
        listener.onTestSuccess(result("suite", "first", null));
        listener.onFinish(context());
        listener.onStart(context());
        listener.onTestFailure(result("suite", "second", new AssertionError("expected failure")));
        listener.onFinish(context());

        assertEquals(2, listener.uploads.size());
        assertEquals(1, uploadedCases(listener.uploads.get(0)).size());
        assertEquals(2, uploadedCases(listener.uploads.get(1)).size());
        assertEquals("target-token", listener.uploads.get(1).get("target"));
        assertMetadata(listener.uploads.get(1));
        assertFalse(listener.disabled);
    }

    @Test
    void outputOnlyWritesOnceForMultipleContexts() throws IOException {
        Path output = temporaryDirectory.resolve("nested/results.json");
        System.setProperty("tesultsOutputFile", output.toString());
        CapturingListener listener = new CapturingListener();

        listener.onStart(context());
        listener.onTestSuccess(result("suite-one", "first", null));
        listener.onFinish(context());
        listener.onStart(context());
        listener.onTestSkipped(result("suite-two", "second", null));
        listener.onFinish(context());
        listener.onExecutionFinish();

        assertTrue(listener.uploads.isEmpty());
        JSONObject payload = readJson(output);
        assertEquals("", payload.getString("target"));
        assertMetadata(payload);
        JSONArray cases = payload.getJSONObject("results").getJSONArray("cases");
        assertEquals(2, cases.length());
        assertEquals("pass", resultFor(cases, "first"));
        assertEquals("unknown", resultFor(cases, "second"));
    }

    @Test
    void targetAndOutputBothComplete() throws IOException {
        Path output = temporaryDirectory.resolve("combined.json");
        System.setProperty("tesultsTarget", "combined-target");
        System.setProperty("tesultsOutputFile", output.toString());
        CapturingListener listener = new CapturingListener();

        listener.onStart(context());
        listener.onTestSuccess(result("suite", "passes", null));
        listener.onFinish(context());
        listener.onExecutionFinish();

        assertEquals(1, listener.uploads.size());
        assertEquals("combined-target", listener.uploads.get(0).get("target"));
        assertEquals("", readJson(output).getString("target"));
    }

    @Test
    void outputFailureDoesNotPreventTargetUpload() throws IOException {
        Path directoryInsteadOfFile = Files.createDirectory(temporaryDirectory.resolve("output-directory"));
        System.setProperty("tesultsTarget", "upload-after-output-error");
        System.setProperty("tesultsOutputFile", directoryInsteadOfFile.toString());
        CapturingListener listener = new CapturingListener();

        listener.onStart(context());
        listener.onTestSuccess(result("suite", "passes", null));
        listener.onFinish(context());
        listener.onExecutionFinish();

        assertEquals(1, listener.uploads.size());
    }

    @Test
    void configurationFileResolvesTargetOutputAndBuild() throws IOException {
        Path output = temporaryDirectory.resolve("configured.json");
        Path config = temporaryDirectory.resolve("tesults.properties");
        Files.write(config, Arrays.asList(
                "friendly-target=resolved-token",
                "tesultsOutputFile=" + output,
                "tesultsBuildName=build-42",
                "tesultsBuildResult=pass"
        ), StandardCharsets.UTF_8);
        System.setProperty("tesultsConfig", config.toString());
        System.setProperty("tesultsTarget", "friendly-target");
        CapturingListener listener = new CapturingListener();

        listener.onStart(context());
        listener.onTestSuccess(result("suite", "passes", null));
        listener.onFinish(context());
        listener.onExecutionFinish();

        assertEquals("resolved-token", listener.uploads.get(0).get("target"));
        JSONArray cases = readJson(output).getJSONObject("results").getJSONArray("cases");
        assertEquals(2, cases.length());
        assertEquals("pass", resultFor(cases, "build-42"));
    }

    @Test
    void configurationFileCanEnableOutputWithoutATarget() throws IOException {
        Path output = temporaryDirectory.resolve("config-output-only.json");
        Path config = temporaryDirectory.resolve("output-only.properties");
        Files.write(config, Collections.singletonList("tesultsOutputFile=" + output), StandardCharsets.UTF_8);
        System.setProperty("tesultsConfig", config.toString());
        CapturingListener listener = new CapturingListener();

        listener.onStart(context());
        listener.onTestSuccess(result("suite", "passes", null));
        listener.onFinish(context());
        listener.onExecutionFinish();

        assertFalse(listener.disabled);
        assertTrue(listener.uploads.isEmpty());
        assertEquals("pass", resultFor(
                readJson(output).getJSONObject("results").getJSONArray("cases"),
                "passes"
        ));
    }

    @Test
    void enhancedReportingFromPublishedOnePointTwoIsPreserved() throws Exception {
        Path output = temporaryDirectory.resolve("enhanced.json");
        Path attachment = temporaryDirectory.resolve("evidence.txt");
        Files.write(attachment, Collections.singletonList("evidence"), StandardCharsets.UTF_8);
        System.setProperty("tesultsOutputFile", output.toString());
        Method method = EnhancedFixture.class.getDeclaredMethod("enhanced");
        TesultsListener.file(method, attachment.toString());
        TesultsListener.custom(method, "Browser", "Chrome");
        Map<String, Object> step = new HashMap<String, Object>();
        step.put("name", "Open page");
        step.put("result", "pass");
        TesultsListener.step(method, step);
        CapturingListener listener = new CapturingListener();

        listener.onStart(context());
        listener.onTestSuccess(result(EnhancedFixture.class.getName(), "enhanced", null));
        listener.onFinish(context());
        listener.onExecutionFinish();

        JSONObject testCase = readJson(output).getJSONObject("results").getJSONArray("cases").getJSONObject(0);
        assertEquals(attachment.toString(), testCase.getJSONArray("files").getString(0));
        assertEquals("Chrome", testCase.getString("_Browser"));
        assertEquals("Open page", testCase.getJSONArray("steps").getJSONObject(0).getString("name"));
    }

    @Test
    void parallelCallbacksDoNotLoseCases() throws Exception {
        Path output = temporaryDirectory.resolve("parallel.json");
        System.setProperty("tesultsOutputFile", output.toString());
        CapturingListener listener = new CapturingListener();
        listener.onStart(context());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Callable<Void>> tasks = new ArrayList<Callable<Void>>();
        for (int index = 0; index < 100; index++) {
            final int caseIndex = index;
            tasks.add(() -> {
                listener.onTestSuccess(result("parallel", "case-" + caseIndex, null));
                return null;
            });
        }

        executor.invokeAll(tasks);
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        listener.onFinish(context());
        listener.onExecutionFinish();

        assertEquals(100, readJson(output).getJSONObject("results").getJSONArray("cases").length());
    }

    @Test
    void separateExecutionsMergeIntoOneFile() throws IOException {
        Path output = temporaryDirectory.resolve("forked.json");
        System.setProperty("tesultsOutputFile", output.toString());

        CapturingListener first = new CapturingListener();
        first.onStart(context());
        first.onTestSuccess(result("fork", "first", null));
        first.onFinish(context());
        first.onExecutionFinish();

        CapturingListener second = new CapturingListener();
        second.onStart(context());
        second.onTestSuccess(result("fork", "second", null));
        second.onFinish(context());
        second.onExecutionFinish();

        assertEquals(2, readJson(output).getJSONObject("results").getJSONArray("cases").length());
    }

    @Test
    void serviceLoaderRegistersListenerForTestNg() {
        boolean listenerFound = false;
        boolean executionListenerFound = false;
        for (ITestNGListener listener : ServiceLoader.load(ITestNGListener.class)) {
            if (listener instanceof TesultsListener) {
                listenerFound = true;
                executionListenerFound = listener instanceof IExecutionListener;
            }
        }

        assertTrue(listenerFound);
        assertTrue(executionListenerFound);
    }

    @Test
    void automaticListenerRunsThroughRealTestNgExecution() throws IOException {
        Path output = temporaryDirectory.resolve("automatic.json");
        System.setProperty("tesultsOutputFile", output.toString());
        TestNG testng = new TestNG(false);
        testng.setUseDefaultListeners(false);
        testng.setTestClasses(new Class<?>[]{AutomaticFixture.class});

        testng.run();

        assertFalse(testng.hasFailure());
        JSONObject payload = readJson(output);
        assertEquals("pass", resultFor(payload.getJSONObject("results").getJSONArray("cases"), "automaticPass"));
        assertMetadata(payload);
    }

    private static List<Map<String, Object>> uploadedCases(Map<String, Object> upload) {
        return (List<Map<String, Object>>) ((Map<String, Object>) upload.get("results")).get("cases");
    }

    private static JSONObject readJson(Path path) throws IOException {
        return new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }

    private static String resultFor(JSONArray cases, String name) {
        for (int index = 0; index < cases.length(); index++) {
            JSONObject testCase = cases.getJSONObject(index);
            if (name.equals(testCase.getString("name"))) {
                return testCase.getString("result");
            }
        }
        return null;
    }

    private static void assertMetadata(Map<String, Object> payload) {
        Map<String, Object> metadata = (Map<String, Object>) payload.get("metadata");
        assertNotNull(metadata);
        assertEquals("tesults-testng", metadata.get("integration_name"));
        assertEquals("1.3.0", metadata.get("integration_version"));
        assertEquals("testng", metadata.get("test_framework"));
    }

    private static void assertMetadata(JSONObject payload) {
        JSONObject metadata = payload.getJSONObject("metadata");
        assertEquals("tesults-testng", metadata.getString("integration_name"));
        assertEquals("1.3.0", metadata.getString("integration_version"));
        assertEquals("testng", metadata.getString("test_framework"));
    }

    private static ITestContext context() {
        return null;
    }

    private static ITestResult result(String suite, String name, Throwable throwable) {
        ITestNGMethod testMethod = (ITestNGMethod) Proxy.newProxyInstance(
                TesultsListenerTest.class.getClassLoader(),
                new Class<?>[]{ITestNGMethod.class},
                (proxy, method, args) -> "getDescription".equals(method.getName())
                        ? "description for " + name
                        : defaultValue(method.getReturnType())
        );
        return (ITestResult) Proxy.newProxyInstance(
                TesultsListenerTest.class.getClassLoader(),
                new Class<?>[]{ITestResult.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getInstanceName":
                            return suite;
                        case "getName":
                            return name;
                        case "getMethod":
                            return testMethod;
                        case "getStartMillis":
                            return 100L;
                        case "getEndMillis":
                            return 200L;
                        case "getThrowable":
                            return throwable;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    static class CapturingListener extends TesultsListener {
        final List<Map<String, Object>> uploads = new ArrayList<Map<String, Object>>();

        @Override
        Map<String, Object> upload(Map<String, Object> data) {
            uploads.add(data);
            Map<String, Object> response = new HashMap<String, Object>();
            response.put("success", true);
            response.put("message", "captured");
            response.put("warnings", new ArrayList<String>());
            response.put("errors", new ArrayList<String>());
            return response;
        }
    }

    static class EnhancedFixture {
        void enhanced() {
        }
    }

    public static class AutomaticFixture {
        @org.testng.annotations.Test
        public void automaticPass() {
        }
    }
}
