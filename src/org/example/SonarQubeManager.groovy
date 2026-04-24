package org.example

class SonarQubeManager implements Serializable {

    private def script

    // SonarQube server name
    // Jenkins Configure System mein jo naam diya tha
    static final String SONAR_SERVER = 'SonarQube'
    static final int    QG_TIMEOUT   = 5      // minutes

    SonarQubeManager(def script) {
        this.script = script
    }

    // ── MAIN ANALYSIS ─────────────────────────────────────────

    void runAnalysis(String service, String version,
                     String branch, Map prDetails = null) {

        script.echo "=== SONARQUBE ANALYSIS: ${service} ==="

        // Coverage report generate karo pehle
        generateCoverageReport(service)

        // SonarQube analysis run karo
        script.withSonarQubeEnv(SONAR_SERVER) {

            String sonarParams = buildSonarParams(
                service, version, branch, prDetails
            )

            script.sh """
                cd ${service}
                sonar-scanner ${sonarParams}
            """
        }

        script.echo "SonarQube analysis submitted"
    }

    // ── WAIT FOR QUALITY GATE ─────────────────────────────────

    void waitForQualityGate() {
        script.echo "=== WAITING FOR QUALITY GATE RESULT ==="
        script.echo "Timeout: ${QG_TIMEOUT} minutes"

        def qg = null

        script.timeout(time: QG_TIMEOUT, unit: 'MINUTES') {
            qg = script.waitForQualityGate()
        }

        script.echo "Quality Gate Status: ${qg.status}"

        if (qg.status != 'OK') {
            // Notification bhejo failure ki
            script.echo "QUALITY GATE FAILED: ${qg.status}"
            script.error """
                Quality Gate FAILED!
                Status   : ${qg.status}
                Check    : http://localhost:9000/dashboard?id=OT-Microservices
                Fix code quality issues and re-run the pipeline.
            """
        }

        script.echo "Quality Gate PASSED"
    }

    // ── BRANCH ANALYSIS ───────────────────────────────────────

    void runBranchAnalysis(String service, String version, String branch) {
        script.echo "=== BRANCH ANALYSIS: ${branch} ==="

        // Branch type determine karo
        String branchType = getBranchType(branch)
        script.echo "Branch type: ${branchType}"

        script.withSonarQubeEnv(SONAR_SERVER) {

            String params = buildSonarParams(service, version, branch, null)

            // Branch specific params add karo
            params += " -Dsonar.branch.name=${branch}"

            if (branchType == 'feature') {
                // Feature branch — main se compare karo
                params += " -Dsonar.branch.target=master"
            }

            script.sh """
                cd ${service}
                sonar-scanner ${params}
            """
        }
    }

    // ── PR ANALYSIS WITH DECORATION ───────────────────────────

    void runPRAnalysis(String service, String version, Map prDetails) {
        script.echo "=== PR ANALYSIS: PR #${prDetails.prNumber} ==="
        script.echo "PR: ${prDetails.prBranch} → ${prDetails.targetBranch}"

        script.withSonarQubeEnv(SONAR_SERVER) {

            String params = buildSonarParams(service, version, null, prDetails)

            script.sh """
                cd ${service}
                sonar-scanner ${params}
            """
        }

        // Wait for Quality Gate on PR
        waitForQualityGate()
    }

    // ── GENERATE COVERAGE REPORT ──────────────────────────────

    void generateCoverageReport(String service) {
        script.echo "Generating coverage report for SonarQube..."

        script.catchError(
            buildResult : 'SUCCESS',
            stageResult : 'UNSTABLE'
        ) {
            script.sh """
                cd ${service}

                # Install coverage if not present
                pip3 install pytest-cov coverage \
                    --break-system-packages --quiet

                # Generate coverage report
                python3 -m pytest tests/unit/ \
                    --cov=. \
                    --cov-report=xml:tests/reports/coverage.xml \
                    -q 2>/dev/null || true

                echo "Coverage report generated"
                ls -la tests/reports/coverage.xml || echo "No coverage report"
            """
        }
    }

    // ── GET ANALYSIS RESULTS ──────────────────────────────────

    Map getAnalysisResults(String projectKey) {
        try {
            String result = script.sh(
                script: """
                    curl -s -u admin:Admin@123 \
                        "http://localhost:9000/api/measures/component?\
component=${projectKey}&\
metricKeys=coverage,bugs,vulnerabilities,code_smells,duplicated_lines_density" \
                        | python3 -c "
import sys, json
data = json.load(sys.stdin)
measures = data.get('component', {}).get('measures', [])
for m in measures:
    print(m['metric'] + '=' + m.get('value', 'N/A'))
"
                """,
                returnStdout: true
            ).trim()

            script.echo "SonarQube Metrics:\n${result}"
            return [metrics: result]

        } catch (Exception e) {
            script.echo "Could not fetch metrics: ${e.message}"
            return [metrics: 'unavailable']
        }
    }

    // ── PRIVATE HELPERS ───────────────────────────────────────

    private String buildSonarParams(String service, String version,
                                     String branch, Map prDetails) {
        String params = """
            -Dsonar.projectKey=OT-Microservices
            -Dsonar.projectName=OT-Microservices
            -Dsonar.projectVersion=${version}
            -Dsonar.sources=.
            -Dsonar.language=py
            -Dsonar.python.version=3.9
            -Dsonar.exclusions=tests/**,**/__pycache__/**,**/*.pyc
            -Dsonar.python.coverage.reportPaths=tests/reports/coverage.xml
            -Dsonar.python.xunit.reportPath=tests/reports/unit-results.xml
        """.stripIndent().replaceAll('\n', ' ')

        // PR decoration params
        if (prDetails) {
            params += """
                -Dsonar.pullrequest.key=${prDetails.prNumber}
                -Dsonar.pullrequest.branch=${prDetails.prBranch}
                -Dsonar.pullrequest.base=${prDetails.targetBranch}
                -Dsonar.pullrequest.github.repository=${prDetails.repo ?: 'anuj9689/OT-Microservices'}
            """.stripIndent().replaceAll('\n', ' ')
        }

        return params
    }

    private String getBranchType(String branch) {
        if (branch?.startsWith('feature/')) return 'feature'
        if (branch?.startsWith('hotfix/'))  return 'hotfix'
        if (branch?.startsWith('release/')) return 'release'
        if (branch == 'master' || branch == 'main') return 'main'
        return 'other'
    }
}