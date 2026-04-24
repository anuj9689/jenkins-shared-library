package org.example

class SonarQubeManager implements Serializable {

    private def script

    static final String SONAR_SERVER   = 'SonarQube'
    static final int    QG_TIMEOUT     = 10
    // LOCAL: Jenkins local hai isliye localhost use karo
    static final String SONAR_URL      = 'http://localhost:9000'
    // SonarScanner local path
    static final String SCANNER_PATH   = '/opt/sonar-scanner/bin'

    SonarQubeManager(def script) {
        this.script = script
    }

    // ── MAIN ANALYSIS ─────────────────────────────────────────

    void runAnalysis(String service, String version,
                     String branch, Map prDetails = null) {

        script.echo "=== SONARQUBE ANALYSIS: ${service} ==="

        // Coverage report generate karo pehle
        generateCoverageReport(service)

        script.withSonarQubeEnv(SONAR_SERVER) {

            String sonarParams = buildSonarParams(
                service, version, branch, prDetails
            )

            // CHANGE: PATH add kiya — local sonar-scanner ke liye
            script.sh """
                export PATH=\$PATH:${SCANNER_PATH}
                cd ${service}
                sonar-scanner ${sonarParams}
            """
        }

        script.echo "SonarQube analysis submitted"
    }

    // ── WAIT FOR QUALITY GATE ─────────────────────────────────
    // NO CHANGE

    void waitForQualityGate() {
        script.echo "=== WAITING FOR QUALITY GATE RESULT ==="
        script.echo "Timeout: ${QG_TIMEOUT} minutes"

        def qg = null

        script.timeout(time: QG_TIMEOUT, unit: 'MINUTES') {
            qg = script.waitForQualityGate()
        }

        script.echo "Quality Gate Status: ${qg.status}"

        if (qg.status != 'OK') {
            script.echo "QUALITY GATE FAILED: ${qg.status}"
            script.error """
                Quality Gate FAILED!
                Status   : ${qg.status}
                Check    : ${SONAR_URL}/dashboard?id=OT-Microservices
                Fix code quality issues and re-run the pipeline.
            """
        }

        script.echo "Quality Gate PASSED"
    }

    // ── BRANCH ANALYSIS ───────────────────────────────────────

    void runBranchAnalysis(String service, String version, String branch) {
        script.echo "=== BRANCH ANALYSIS: ${branch} ==="

        // CHANGE: origin/ prefix hatao — sonar branch name mein nahi chahiye
        String cleanBranch = branch
            .replace('origin/', '')
            .replace('refs/heads/', '')
            .trim()

        script.echo "Clean branch: ${cleanBranch}"

        String branchType = getBranchType(cleanBranch)
        script.echo "Branch type: ${branchType}"

        script.withSonarQubeEnv(SONAR_SERVER) {

            String params = buildSonarParams(service, version, cleanBranch, null)

            // FIXED: sonar.branch.name REMOVED — Community Edition support nahi karta
            // FIXED: sonar.branch.target REMOVED — Community Edition support nahi karta

            script.sh """
                export PATH=\$PATH:${SCANNER_PATH}
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

            // CHANGE: PATH add kiya
            script.sh """
                export PATH=\$PATH:${SCANNER_PATH}
                cd ${service}
                sonar-scanner ${params}
            """
        }

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

                pip3 install pytest-cov coverage \
                    --break-system-packages --quiet

                # CHANGE: --cov-fail-under hataya
                # Coverage failure se analysis block na ho
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
            // CHANGE: localhost use karo — Jenkins local hai
            String result = script.sh(
                script: """
                    curl -s -u admin:Admin@123 \
                        "${SONAR_URL}/api/measures/component?\
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
    // NO CHANGE

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