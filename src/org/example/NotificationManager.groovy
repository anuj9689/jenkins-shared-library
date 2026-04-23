package org.example

class NotificationManager implements Serializable {

    private def script

    NotificationManager(def script) {
        this.script = script
    }

    // ── SLACK NOTIFICATION ────────────────────────────────────

    void sendSlack(String status, Map details) {
        try {
            String color   = getColor(status)
            String emoji   = getEmoji(status)
            String message = buildSlackMessage(status, emoji, details)

            script.slackSend(
                channel          : '#jenkins-notifications',
                color            : color,
                message          : message,
                tokenCredentialId: 'slack-token'
            )

            script.echo "Slack notification sent: ${status}"

        } catch (Exception e) {
            script.echo "WARNING: Slack notification failed: ${e.message}"
        }
    }

    // ── EMAIL NOTIFICATION ────────────────────────────────────

    void sendEmail(String status, Map details) {
        try {
            String subject = buildEmailSubject(status, details)
            String body    = buildEmailBody(status, details)

            script.emailext(
                subject  : subject,
                body     : body,
                mimeType : 'text/html',
                to       : details.recipients ?: 'YOUR_EMAIL@gmail.com',
                attachLog: status == 'FAILURE'
            )

            script.echo "Email notification sent: ${status}"

        } catch (Exception e) {
            script.echo "WARNING: Email notification failed: ${e.message}"
        }
    }

    // ── MS TEAMS NOTIFICATION ─────────────────────────────────

    void sendTeams(String status, Map details) {
        try {
            String color   = getTeamsColor(status)
            String emoji   = getEmoji(status)
            String message = buildTeamsMessage(status, emoji, details)

            script.withCredentials([
                script.string(
                    credentialsId: 'teams-webhook',
                    variable     : 'TEAMS_URL'
                )
            ]) {
                script.sh("""
                    curl -s -X POST "\$TEAMS_URL" \
                        -H "Content-Type: application/json" \
                        -d '${message}' || echo "Teams notification failed"
                """)
            }

            script.echo "Teams notification sent: ${status}"

        } catch (Exception e) {
            script.echo "WARNING: Teams notification failed: ${e.message}"
        }
    }

    // ── SEND ALL NOTIFICATIONS ────────────────────────────────

    void notifyAll(String status, Map details) {
        sendSlack(status, details)
        sendEmail(status, details)
        sendTeams(status, details)
    }

    // ── PRIVATE — MESSAGE BUILDERS ────────────────────────────

    // CHANGE 1: Artifacts, Tests, Coverage links add kiye
    private String buildSlackMessage(String status, String emoji, Map d) {

        // Links build karo
        String buildUrl      = d.buildUrl      ?: 'http://localhost:8080'
        String artifactsUrl  = d.artifactsUrl  ?: "${buildUrl}artifact/${d.service}/tests/reports/test-report.pdf"
        String testsUrl      = d.testsUrl      ?: "${buildUrl}testReport/"
        String coverageUrl   = d.coverageUrl   ?: "${buildUrl}Coverage_20Report/"

        return """
${emoji} *${status}: ${d.jobName} #${d.buildNumber}*
> *Branch*      : ${d.branch}
> *Service*     : ${d.service}
> *Version*     : ${d.version}
> *Env*         : ${d.environment}
> *Duration*    : ${d.duration}
> *Changes*     : ${d.changes}

*Quick Links:*
> <${buildUrl}|🔗 View Build Page>
> <${testsUrl}|📊 Test Results>
> <${coverageUrl}|📈 Coverage Report>
> <${artifactsUrl}|📄 Download PDF Report>
        """.trim()
    }

    private String buildEmailSubject(String status, Map d) {
        String emoji = getEmoji(status)
        return "${emoji} ${status}: ${d.jobName} #${d.buildNumber} [${d.branch}]"
    }

    // CHANGE 2: 4 action buttons add kiye email mein
    private String buildEmailBody(String status, Map d) {
        String color = getHtmlColor(status)

        // Links build karo
        String buildUrl     = d.buildUrl     ?: 'http://localhost:8080'
        String artifactsUrl = d.artifactsUrl ?: "${buildUrl}artifact/${d.service}/tests/reports/test-report.pdf"
        String testsUrl     = d.testsUrl     ?: "${buildUrl}testReport/"
        String coverageUrl  = d.coverageUrl  ?: "${buildUrl}Coverage_20Report/"

        return """
<!DOCTYPE html>
<html>
<body style="font-family: Arial, sans-serif; margin: 0; padding: 20px;">

  <!-- Header -->
  <div style="background-color: ${color};
              padding: 15px;
              border-radius: 8px;
              margin-bottom: 20px;">
    <h2 style="color: white; margin: 0;">
      ${getEmoji(status)} Build ${status}
    </h2>
  </div>

  <!-- Build Details Table -->
  <table style="width:100%;
                border-collapse:collapse;
                margin-bottom:20px;">
    <tr style="background-color:#f2f2f2;">
      <td style="padding:10px; border:1px solid #ddd; font-weight:bold; width:30%;">Job Name</td>
      <td style="padding:10px; border:1px solid #ddd;">${d.jobName}</td>
    </tr>
    <tr>
      <td style="padding:10px; border:1px solid #ddd; font-weight:bold;">Build Number</td>
      <td style="padding:10px; border:1px solid #ddd;">#${d.buildNumber}</td>
    </tr>
    <tr style="background-color:#f2f2f2;">
      <td style="padding:10px; border:1px solid #ddd; font-weight:bold;">Branch</td>
      <td style="padding:10px; border:1px solid #ddd;">${d.branch}</td>
    </tr>
    <tr>
      <td style="padding:10px; border:1px solid #ddd; font-weight:bold;">Service</td>
      <td style="padding:10px; border:1px solid #ddd;">${d.service}</td>
    </tr>
    <tr style="background-color:#f2f2f2;">
      <td style="padding:10px; border:1px solid #ddd; font-weight:bold;">Version</td>
      <td style="padding:10px; border:1px solid #ddd;">${d.version}</td>
    </tr>
    <tr>
      <td style="padding:10px; border:1px solid #ddd; font-weight:bold;">Environment</td>
      <td style="padding:10px; border:1px solid #ddd;">${d.environment}</td>
    </tr>
    <tr style="background-color:#f2f2f2;">
      <td style="padding:10px; border:1px solid #ddd; font-weight:bold;">Duration</td>
      <td style="padding:10px; border:1px solid #ddd;">${d.duration}</td>
    </tr>
    <tr>
      <td style="padding:10px; border:1px solid #ddd; font-weight:bold;">Changes</td>
      <td style="padding:10px; border:1px solid #ddd;">${d.changes}</td>
    </tr>
  </table>

  <!-- CHANGE 2: 4 Action Buttons -->
  <p style="font-weight:bold; margin-bottom:10px;">Quick Links:</p>
  <table style="margin-bottom:20px;">
    <tr>
      <td style="padding:5px;">
        <a href="${buildUrl}"
           style="background-color:#0066cc;
                  color:white;
                  padding:10px 15px;
                  text-decoration:none;
                  border-radius:5px;
                  display:inline-block;">
          🔗 View Build
        </a>
      </td>
      <td style="padding:5px;">
        <a href="${testsUrl}"
           style="background-color:#28a745;
                  color:white;
                  padding:10px 15px;
                  text-decoration:none;
                  border-radius:5px;
                  display:inline-block;">
          📊 Test Results
        </a>
      </td>
      <td style="padding:5px;">
        <a href="${coverageUrl}"
           style="background-color:#6f42c1;
                  color:white;
                  padding:10px 15px;
                  text-decoration:none;
                  border-radius:5px;
                  display:inline-block;">
          📈 Coverage
        </a>
      </td>
      <td style="padding:5px;">
        <a href="${artifactsUrl}"
           style="background-color:#fd7e14;
                  color:white;
                  padding:10px 15px;
                  text-decoration:none;
                  border-radius:5px;
                  display:inline-block;">
          📄 PDF Report
        </a>
      </td>
    </tr>
  </table>

  <p style="color:#666; font-size:12px;">
    This is an automated notification from Jenkins CI/CD Pipeline.
  </p>

</body>
</html>
        """.trim()
    }

    // CHANGE 3: 4 action buttons add kiye Teams mein
    private String buildTeamsMessage(String status, String emoji, Map d) {
        String color = getTeamsColor(status)

        // Links build karo
        String buildUrl     = d.buildUrl     ?: 'http://localhost:8080'
        String artifactsUrl = d.artifactsUrl ?: "${buildUrl}artifact/${d.service}/tests/reports/test-report.pdf"
        String testsUrl     = d.testsUrl     ?: "${buildUrl}testReport/"
        String coverageUrl  = d.coverageUrl  ?: "${buildUrl}Coverage_20Report/"

        return """
{
    "@type": "MessageCard",
    "@context": "http://schema.org/extensions",
    "themeColor": "${color}",
    "summary": "${status}: ${d.jobName} #${d.buildNumber}",
    "sections": [{
        "activityTitle": "${emoji} **${status}: ${d.jobName}**",
        "activitySubtitle": "Build #${d.buildNumber} | Branch: ${d.branch}",
        "facts": [
            {"name": "Service",     "value": "${d.service}"},
            {"name": "Version",     "value": "${d.version}"},
            {"name": "Environment", "value": "${d.environment}"},
            {"name": "Duration",    "value": "${d.duration}"},
            {"name": "Changes",     "value": "${d.changes}"}
        ],
        "markdown": true
    }],
    "potentialAction": [
        {
            "@type": "OpenUri",
            "name": "🔗 View Build",
            "targets": [{"os": "default", "uri": "${buildUrl}"}]
        },
        {
            "@type": "OpenUri",
            "name": "📊 Test Results",
            "targets": [{"os": "default", "uri": "${testsUrl}"}]
        },
        {
            "@type": "OpenUri",
            "name": "📈 Coverage Report",
            "targets": [{"os": "default", "uri": "${coverageUrl}"}]
        },
        {
            "@type": "OpenUri",
            "name": "📄 PDF Report",
            "targets": [{"os": "default", "uri": "${artifactsUrl}"}]
        }
    ]
}
        """.trim()
    }

    // ── PRIVATE — HELPERS ─────────────────────────────────────
    // NO CHANGE — same rakha

    private String getColor(String status) {
        switch(status) {
            case 'SUCCESS'  : return 'good'
            case 'FAILURE'  : return 'danger'
            case 'UNSTABLE' : return 'warning'
            case 'STARTED'  : return '#439FE0'
            default         : return '#808080'
        }
    }

    private String getHtmlColor(String status) {
        switch(status) {
            case 'SUCCESS'  : return '#28a745'
            case 'FAILURE'  : return '#dc3545'
            case 'UNSTABLE' : return '#ffc107'
            case 'STARTED'  : return '#17a2b8'
            default         : return '#6c757d'
        }
    }

    private String getTeamsColor(String status) {
        switch(status) {
            case 'SUCCESS'  : return '28a745'
            case 'FAILURE'  : return 'dc3545'
            case 'UNSTABLE' : return 'ffc107'
            case 'STARTED'  : return '17a2b8'
            default         : return '6c757d'
        }
    }

    private String getEmoji(String status) {
        switch(status) {
            case 'SUCCESS'  : return 'SUCCESS'
            case 'FAILURE'  : return 'FAILED'
            case 'UNSTABLE' : return 'UNSTABLE'
            case 'STARTED'  : return 'STARTED'
            default         : return 'INFO'
        }
    }
}