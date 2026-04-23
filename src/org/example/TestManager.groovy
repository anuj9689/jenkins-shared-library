package org.example

class TestManager implements Serializable {

    private def script
    private static final int COVERAGE_THRESHOLD = 80

    TestManager(def script) {
        this.script = script
    }

    // ── INSTALL TEST DEPENDENCIES ─────────────────────────────

    void installDependencies(String service) {
        script.echo "Installing test dependencies for ${service}..."
        script.sh """
            cd ${service}
            pip3 install -r tests/requirements-test.txt \
                --break-system-packages --quiet
        """
    }

    // ── RUN UNIT TESTS ────────────────────────────────────────

    void runUnitTests(String service) {
        script.echo "=== RUNNING UNIT TESTS: ${service} ==="
        script.catchError(
            buildResult : 'UNSTABLE',
            stageResult : 'FAILURE'
        ) {
            script.sh """
                cd ${service}
                python3 -m pytest tests/unit/ \
                    --junitxml=tests/reports/unit-results.xml \
                    --cov=. \
                    --cov-report=xml:tests/reports/unit-coverage.xml \
                    --cov-report=html:tests/reports/unit-coverage-html \
                    -v 2>&1 | tee tests/reports/unit-output.txt
            """
        }
        script.echo "Unit tests complete"
    }

    // ── RUN INTEGRATION TESTS ─────────────────────────────────

    void runIntegrationTests(String service, String appUrl = '') {
        script.echo "=== RUNNING INTEGRATION TESTS: ${service} ==="
        script.catchError(
            buildResult : 'UNSTABLE',
            stageResult : 'FAILURE'
        ) {
            script.sh """
                cd ${service}
                export APP_URL="${appUrl ?: 'http://localhost:8081'}"
                python3 -m pytest tests/integration/ \
                    --junitxml=tests/reports/integration-results.xml \
                    -v 2>&1 | tee tests/reports/integration-output.txt
            """
        }
        script.echo "Integration tests complete"
    }

    // ── RUN E2E TESTS ─────────────────────────────────────────

    void runE2ETests(String service, String appUrl = '') {
        script.echo "=== RUNNING E2E TESTS: ${service} ==="
        script.catchError(
            buildResult : 'UNSTABLE',
            stageResult : 'FAILURE'
        ) {
            script.sh """
                cd ${service}
                export APP_URL="${appUrl ?: 'http://localhost:8081'}"
                python3 -m pytest tests/e2e/ \
                    --junitxml=tests/reports/e2e-results.xml \
                    -v 2>&1 | tee tests/reports/e2e-output.txt
            """
        }
        script.echo "E2E tests complete"
    }

    // ── CHECK COVERAGE THRESHOLD ──────────────────────────────

    void checkCoverageThreshold(String service) {
        script.echo "=== CHECKING COVERAGE THRESHOLD: ${COVERAGE_THRESHOLD}% ==="
        script.catchError(
            buildResult : 'FAILURE',
            stageResult : 'FAILURE'
        ) {
            script.sh """
                cd ${service}
                python3 -m pytest tests/unit/ \
                    --cov=. \
                    --cov-report=xml:tests/reports/coverage.xml \
                    --cov-fail-under=${COVERAGE_THRESHOLD} \
                    -q 2>&1 | tee tests/reports/coverage-output.txt
                echo "Coverage check passed - above ${COVERAGE_THRESHOLD}%"
            """
        }
    }

    // ── GENERATE PDF REPORT ───────────────────────────────────

    void generatePDFReport(String service) {
        script.echo "=== GENERATING PDF REPORT ==="
        script.catchError(
            buildResult : 'SUCCESS',
            stageResult : 'UNSTABLE'
        ) {
            script.sh """
                cd ${service}
                pip3 install fpdf2 --break-system-packages --quiet
                python3 tests/generate_report.py
                echo "PDF Report generated successfully"
                ls -la tests/reports/
            """
        }
    }

    // ── PUBLISH TEST RESULTS ──────────────────────────────────

    void publishResults(String service) {
        script.echo "=== PUBLISHING TEST RESULTS ==="

        // Publish JUnit results
        try {
            script.junit(
                testResults             : "${service}/tests/reports/*-results.xml",
                allowEmptyResults       : true,
                skipPublishingChecks    : false
            )
        } catch (Exception e) {
            script.echo "Warning: JUnit publish failed: ${e.message}"
        }

        // Publish HTML coverage report
        try {
            script.publishHTML(
                target: [
                    allowMissing         : true,
                    alwaysLinkToLastBuild: true,
                    keepAll              : true,
                    reportDir            : "${service}/tests/reports/unit-coverage-html",
                    reportFiles          : 'index.html',
                    reportName           : 'Coverage Report'
                ]
            )
        } catch (Exception e) {
            script.echo "Warning: HTML publish failed: ${e.message}"
        }

        // Archive all test artifacts
        try {
            script.archiveArtifacts(
                artifacts       : "${service}/tests/reports/**/*",
                allowEmptyArchive: true,
                fingerprint     : true
            )
        } catch (Exception e) {
            script.echo "Warning: Archive failed: ${e.message}"
        }

        script.echo "Results published successfully"
    }

    // ── RUN ALL TESTS ─────────────────────────────────────────

    void runAllTests(String service, String appUrl = '') {
        installDependencies(service)
        runUnitTests(service)
        runIntegrationTests(service, appUrl)
        runE2ETests(service, appUrl)
        checkCoverageThreshold(service)
        generatePDFReport(service)
        publishResults(service)
    }
}