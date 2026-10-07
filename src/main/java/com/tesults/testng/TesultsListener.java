package com.tesults.testng;

import org.testng.ITestContext;
import org.testng.IExecutionListener;
import org.testng.ITestResult;
import org.testng.TestListenerAdapter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.tesults.tesults.*;

public class TesultsListener extends TestListenerAdapter implements IExecutionListener {

    private static final String TESTNG_DRY_RUN_PROPERTY = "testng.mode.dryrun";

    private static class CustomField {
        public String key;
        public String value;
    }

    // Preserve the existing cumulative list used for target uploads.
    List<Map<String,Object>> cases = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
    // Keep action output independent so it can be written once per execution.
    List<Map<String,Object>> outputCases = Collections.synchronizedList(new ArrayList<Map<String, Object>>());

    static Map<String, List<String>> files = new ConcurrentHashMap<String, List<String>>();
    static Map<String, List<CustomField>> customFields = new ConcurrentHashMap<String, List<CustomField>>();
    static Map<String, List<Map<String, Object>>> steps = new ConcurrentHashMap<String, List<Map<String, Object>>>();

    volatile boolean disabled = false;

    // Options
    String config = System.getProperty("tesultsConfig");
    String target = System.getProperty("tesultsTarget");
    String outputFile = resolveOutputFile();
    String filesDir = System.getProperty("tesultsFiles");
    Boolean nosuites = System.getProperty("tesultsNoSuites") == null ? false : true;
    String buildName = System.getProperty("tesultsBuildName");
    String buildDesc = System.getProperty("tesultsBuildDesc");
    String buildResult = System.getProperty("tesultsBuildResult");
    String buildReason = System.getProperty("tesultsBuildReason");

    private List<String> filesForCase(String suite, String name) {
        if (filesDir == null) {
            return null;
        }
        List<String> caseFiles = new ArrayList<String>();
        String pathString = Paths.get(filesDir, name).toString();
        if (!suite.equals("") && suite != null) {
            pathString = Paths.get(this.filesDir, suite, name).toString();
        }
        File path = new File(pathString);
        try {
            File[] files = path.listFiles();
            for (File file : files) {
                if (!file.isDirectory()) {
                    if (!file.getName().equals(".DS_Store")) { // Ignore os files
                        caseFiles.add(file.getAbsolutePath());
                    }
                }
            }
        } catch (NullPointerException ex) {
            // Dereference of listFiles can produce this.
        } catch (Exception ex) {
            System.out.println("TesultsListener Exception: " + ex.toString());
        }
        return caseFiles;
    }

    private Map<String, Object> createTestCase (ITestResult iTestResult) {
        Map<String, Object> testCase = new HashMap<String, Object>();
        String suite = iTestResult.getInstanceName();
        String className = iTestResult.getInstanceName();
        String name = iTestResult.getName();
        //testCase.put("name", getTestMethodName(iTestResult));
        testCase.put("name", name);
        if (nosuites != true) {
            testCase.put("suite", suite);
        } else {
            suite = null;
            testCase.put("_Class", className);
        }
        String desc = iTestResult.getMethod().getDescription();
        if (desc != null) {
            testCase.put("desc", desc);
        }
        /*Object[] params = iTestResult.getParameters();
        if (params != null) {
            if (params.length > 0) {
                Map<String, String> parameters = new HashMap<String, String>();
                for (int i = 0; i < params.length; i++) {
                    parameters.put(String.valueOf(i), params[i].toString());
                }
                testCase.put("params", parameters);
            }
        }*/

        testCase.put("start", iTestResult.getStartMillis());
        testCase.put("end", iTestResult.getEndMillis());

        // Files:
        List<String> testFiles = filesForCase(suite == null ? "" : suite, name);
        if (testFiles != null) {
            if (testFiles.size() > 0) {
                testCase.put("files", testFiles);
            }
        }

        // Enhanced reporting files:
        String key = keyForTestSuiteAndName(nosuites == true ? className : suite, name);
        List<String> paths = files.get(key);
        if (paths != null) {
            synchronized (paths) {
                paths = new ArrayList<String>(paths);
            }
            List<String> existingPaths = (List<String>) testCase.get("files");
            if (existingPaths != null) {
                paths.addAll(existingPaths);
            }
            testCase.put("files", paths);
        }

        // Enhanced reporting custom fields:
        List<CustomField> existingCustomFields = customFields.get(key);
        if (existingCustomFields != null) {
            synchronized (existingCustomFields) {
                for (CustomField customField: existingCustomFields) {
                    testCase.put("_" + customField.key, customField.value);
                }
            }
        }

        // Enhanced reporting steps:
        List<Map<String, Object>> stepList = steps.get(key);
        if (stepList != null) {
            synchronized (stepList) {
                testCase.put("steps", new ArrayList<Map<String, Object>>(stepList));
            }
        }

        return testCase;
    }

    private Map<String, Object> createBuildCase () {
        if (buildName != null) {
            if (buildName.equals("")) {
                buildName = "-";
            }
            Map<String, Object> buildCase = new HashMap<String, Object>();

            buildCase.put("name", buildName);
            buildCase.put("suite", "[build]");

            if (buildDesc != null) {
                if (buildDesc.equals("")) {
                    buildDesc = "-";
                }
                buildCase.put("desc", buildDesc);
            }
            if (buildReason != null) {
                if (buildReason.equals("")) {
                    buildReason = "-";
                }
                buildCase.put("reason", buildReason);
            }
            if (buildResult != null) {
                if (buildResult.toLowerCase().equals("pass")) {
                    buildCase.put("result", "pass");
                } else if (buildResult.toLowerCase().equals("fail")) {
                    buildCase.put("result", "fail");
                } else {
                    buildCase.put("result", "unknown");
                }
            } else {
                buildCase.put("result", "unknown");
            }

            // Files:
            List<String> files = filesForCase("[build]", buildName);
            if (files != null) {
                if (files.size() > 0) {
                    buildCase.put("files", files);
                }
            }

            return buildCase;
        } else {
            return null;
        }
    }

    @Override
    public synchronized void onStart(ITestContext itestContext) {
        // The JUnit Platform TestNG engine uses TestNG dry runs for discovery.
        // Those callbacks do not represent an actual test execution and must not
        // be uploaded or written to the GitHub Actions interchange file.
        if (Boolean.parseBoolean(System.getProperty(TESTNG_DRY_RUN_PROPERTY))) {
            disabled = true;
            return;
        }

        if (config != null) {
            FileInputStream in = null;
            try {
                Properties props = new Properties();
                in = new FileInputStream(config);
                props.load(in);
                if (target != null && props.getProperty(target, null) != null) {
                    target = props.getProperty(target);
                    if (target.equals("")) {
                        System.out.println("Invalid target value in configuration file");
                    }
                }
                if (filesDir == null) {
                    filesDir = props.getProperty("tesultsFiles", null);
                }
                if (nosuites == false) {
                    String nosuitesConfig = props.getProperty("tesultsNoSuites", null);
                    if (nosuitesConfig != null) {
                        if (nosuitesConfig.toLowerCase().equals("true")) {
                            nosuites = true;
                        }
                    }
                }
                if (buildName == null) {
                    buildName = props.getProperty("tesultsBuildName", null);
                }
                if (buildDesc == null) {
                    buildDesc = props.getProperty("tesultsBuildDesc", null);
                }
                if (buildResult == null) {
                    buildResult = props.getProperty("tesultsBuildResult", null);
                }
                if (buildReason == null) {
                    buildReason = props.getProperty("tesultsBuildReason", null);
                }
                if (outputFile == null) {
                    outputFile = nonBlank(props.getProperty("tesultsOutputFile", null));
                }
            } catch (FileNotFoundException e) {
                System.out.println("Configuration file specified for Tesults not found");
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        if (target == null && outputFile == null) {
            System.out.println("Tesults disabled - target not provided.");
            disabled = true;
            return;
        }
        disabled = false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized void onFinish(ITestContext iTestContext) {
        if (disabled || target == null) {
            return;
        }

        Map<String, Object> buildCase = createBuildCase();
        if (buildCase != null) {
            cases.add(buildCase);
        }

        // Map<String, Object> to hold your test results data.
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("target", target);

        Map<String, Object> results = new HashMap<String, Object>();
        results.put("cases", snapshot(cases));
        data.put("results", results);
        TesultsOutput.addMetadata(data);

        // Upload
        System.out.println("Tesults results uploading...");
        Map<String, Object> response = upload(data);
        System.out.println("success: " + response.get("success"));
        System.out.println("message: " + response.get("message"));
        System.out.println("warnings: " + ((List<String>) response.get("warnings")).size());
        System.out.println("errors: " + ((List<String>) response.get("errors")).size());
    }

    @Override
    public void onExecutionStart() {
        // TestNG execution-level output is completed in onExecutionFinish.
    }

    @Override
    public void onExecutionFinish() {
        if (disabled || outputFile == null) {
            return;
        }

        List<Map<String, Object>> localCases = snapshot(outputCases);
        Map<String, Object> buildCase = createBuildCase();
        if (buildCase != null) {
            localCases.add(buildCase);
        }

        try {
            TesultsOutput.write(outputFile, localCases);
        } catch (Exception ex) {
            System.out.println("Error writing Tesults results file: " + ex.getMessage());
        }
    }

    Map<String, Object> upload(Map<String, Object> data) {
        return Results.upload(data);
    }

    @Override
    public void onTestSuccess(ITestResult iTestResult) {
        if (disabled) {
            return;
        }
        Map<String, Object> testCase = createTestCase(iTestResult);
        testCase.put("result", "pass");
        record(testCase);
    }

    @Override
    public void onTestFailure(ITestResult iTestResult) {
        if (disabled) {
            return;
        }
        Map<String, Object> testCase = createTestCase(iTestResult);
        testCase.put("result", "fail");
        testCase.put("reason", iTestResult.getThrowable().getMessage());
        record(testCase);
    }

    @Override
    public void onTestSkipped(ITestResult iTestResult) {
        if (disabled) {
            return;
        }
        Map<String, Object> testCase = createTestCase(iTestResult);
        testCase.put("result", "unknown");
        record(testCase);
    }

    private void record(Map<String, Object> testCase) {
        cases.add(testCase);
        outputCases.add(testCase);
    }

    private static List<Map<String, Object>> snapshot(List<Map<String, Object>> source) {
        synchronized (source) {
            return new ArrayList<Map<String, Object>>(source);
        }
    }

    private static String resolveOutputFile() {
        String environmentOutput = nonBlank(System.getenv("TESULTS_OUTPUT_FILE"));
        return environmentOutput == null
                ? nonBlank(System.getProperty("tesultsOutputFile"))
                : environmentOutput;
    }

    private static String nonBlank(String value) {
        return value == null || value.trim().equals("") ? null : value;
    }

    // Enhanced reporting

    private static String keyForTestSuiteAndName (String suite, String name) {
        return suite + "-" + name;
    }
    public static void file (Method method, String path) {
        try {
            String suite = method.getDeclaringClass().getName();
            String name = method.getName();
            String key = keyForTestSuiteAndName(suite, name);
            List<String> paths = files.computeIfAbsent(
                    key,
                    ignored -> Collections.synchronizedList(new ArrayList<String>())
            );
            paths.add(path);
        } catch (Exception ex) {

        }
    }

    public static void custom (Method method, String name, String value) {
        try {
            String suite = method.getDeclaringClass().getName();
            String testName = method.getName();
            String key = keyForTestSuiteAndName(suite, testName);
            List<CustomField> existingCustomFields = customFields.computeIfAbsent(
                    key,
                    ignored -> Collections.synchronizedList(new ArrayList<CustomField>())
            );
            CustomField newCustomField = new CustomField();
            newCustomField.key = name;
            newCustomField.value = value;
            existingCustomFields.add(newCustomField);
        } catch (Exception ex) {

        }
    }

    public static void step (Method method, Map<String, Object> step) {
        try {
            String suite = method.getDeclaringClass().getName();
            String name = method.getName();
            String key = keyForTestSuiteAndName(suite, name);
            List<Map<String, Object>> stepsList = steps.computeIfAbsent(
                    key,
                    ignored -> Collections.synchronizedList(new ArrayList<Map<String, Object>>())
            );
            stepsList.add(step);
        } catch (Exception ex) {

        }
    }
}
