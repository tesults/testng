# Tesults TestNG Listener

The Tesults TestNG Listener makes it easy to push results data to Tesults from your TestNG tests.


## Documentation

Documentation is available at https://www.tesults.com/docs/testng


## GitHub Actions

`tesults-testng` can produce a local results file for the Tesults Test
Automation Reporting action. No Tesults target token is required for this mode.

Use `tesults-testng` 1.3.0 or later and run tests through TestNG:

```groovy
dependencies {
    testImplementation 'com.tesults.testng:tesults-testng:1.3.0'
}

test {
    useTestNG()
}
```

Add the action before the test step:

```yaml
- uses: tesults/test-automation-reporting@v1
- run: ./gradlew test
```

The action sets `TESULTS_OUTPUT_FILE` automatically. The listener also accepts
`tesultsOutputFile` as a JVM system property or configuration-file property;
the environment variable takes precedence. If both local output and
`tesultsTarget` are configured, the listener writes the local report and
continues uploading results to Tesults using the existing per-context behavior.


## Support

help@tesults.com
