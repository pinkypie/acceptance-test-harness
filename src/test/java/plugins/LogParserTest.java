package plugins;

import org.jenkinsci.test.acceptance.junit.AbstractJUnitTest;
import org.jenkinsci.test.acceptance.junit.WithPlugins;
import org.jenkinsci.test.acceptance.plugins.logparser.LogParserGlobalConfig;
import org.jenkinsci.test.acceptance.plugins.logparser.LogParserPublisher;
import org.jenkinsci.test.acceptance.po.*;
import org.junit.Before;
import org.junit.Test;
import org.openqa.selenium.By;
import org.jenkinsci.test.acceptance.junit.Resource;
import org.openqa.selenium.WebElement;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import org.jenkinsci.test.acceptance.plugins.logparser.LogParserOutputPage;

@WithPlugins("log-parser")
public class LogParserTest extends AbstractJUnitTest {

    private static final String SUMMARY_XPATH = "//div[@id='main-panel']/table/tbody/tr[3]";

    private LogParserGlobalConfig config;

    private Map<String, String> parserRules;

    @Before
    public void globalConfig() {
        config = new LogParserGlobalConfig(jenkins.getConfigPage());
        parserRules = new HashMap<>();
        // initialize a sample rule for the following test cases
        Resource sampleRule = resource("/logparser_plugin/rules/log-parser-rules-sample");
        parserRules.put("sampleRule", "" + sampleRule.url.getPath());
        addLogParserRules(parserRules);
    }

    /**
     * Test that the link from the sidebar points to a valid position in the content page and
     * that the text in the content page has the correct color.
     */
    @Test
    public void testLinksAndColor() throws Exception {
        Job job = configureSampleJob();

        Build build = job.startBuild().waitUntilFinished();
        build.open();

        driver.findElement(By.partialLinkText("Parsed Console Output")).click();
        LogParserOutputPage outputPage = new LogParserOutputPage(build);

        assertThat(outputPage.getFragmentOfContentFrame("Error", 1), is("ERROR1"));

        assertThat(outputPage.getColor("Error",1), is("red"));
    }

    /**
     * Test the number of warnings and errors recognized by the plugin.
     */
    @Test
    public void testErrorReporting() throws Exception {
        Job job = configureSampleJob();

        Build build = job.startBuild().waitUntilFinished();

        build.open();

        WebElement summary = driver.findElement(By.xpath(SUMMARY_XPATH + "/td[2]"));
        assertThat(summary.getText(), is("13 errors, 4 warnings"));

        driver.findElement(By.partialLinkText("Parsed Console Output")).click();
        LogParserOutputPage outputPage = new LogParserOutputPage(build);

        assertThat(outputPage.getNumberOfMatches("Error"), is(13));
        assertThat(outputPage.getLinkList("Error"), hasSize(13));

        assertThat(outputPage.getNumberOfMatches("Warning"), is(4));
        assertThat(outputPage.getLinkList("Warning"), hasSize(4));

        assertThat(outputPage.getNumberOfMatches("Info"), is(2));
        assertThat(outputPage.getLinkList("Info"), hasSize(2));
    }

    /**
     * Test information for failed log parsing.
     */
    @Test
    public void invalidRulePath() {
        FreeStyleJob job = jenkins.jobs.create(FreeStyleJob.class, "fail-job");

        // configure invalid route
        job.configure(() -> {
            LogParserPublisher lpp = job.addPublisher(LogParserPublisher.class);
            lpp.setRule(LogParserPublisher.RuleType.PROJECT, "invalidPath");
        });

        Build build = job.startBuild().waitUntilFinished();

        // check information on build overview
        build.open();
        WebElement tableRow = driver.findElement(By.xpath(SUMMARY_XPATH));
        WebElement icon = tableRow.findElement(By.xpath("td[1]/img"));
        assertThat(icon.getAttribute("src"), containsString("graph.png"));
        WebElement text = tableRow.findElement(By.xpath("td[2]"));
        assertThat(text.getText(), is("Log parsing has failed"));

        // check information in parsed console output
        driver.findElement(By.partialLinkText("Parsed Console Output")).click();
        WebElement output = driver.findElement(By.id("main-panel"));
        assertThat(output.getText(), containsString("ERROR: Failed to parse console log"));
    }

    /**
     * Check whether trend is visible
     */
    @Test
    public void trendVisible() {
        FreeStyleJob job = jenkins.jobs.create(FreeStyleJob.class, "simple-job");

        job.configure(() -> {
            // sample use of the LogParserPublisher
            LogParserPublisher lpp = job.addPublisher(LogParserPublisher.class);
            lpp.setShowGraphs(true);
            lpp.setRule(LogParserPublisher.RuleType.GLOBAL, parserRules.get("sampleRule"));
        });

        // Trend is shown after second build
        job.startBuild().waitUntilFinished();
        job.startBuild().waitUntilFinished();

        // Check trend is visible
        job.open();
        WebElement trend = driver.findElement(By.className("test-trend-caption"));
        assertThat(trend.getText(), containsString("Log Parser Trend"));
        WebElement img = driver.findElement(By.xpath("//img[@src='logparser/trend']"));
        assertThat(img.getAttribute("alt"), containsString("[Log Parser Chart]"));
    }

    /**
     * Test case:
     * Check for a build to be marked as failed and another build to be marked as unstable.
     */
    @Test
    public void checkMarkedFailedAndUnstable() {
        FreeStyleJob job = jenkins.jobs.create(FreeStyleJob.class, "simple-job");
        job.configure(() -> {
            job.addShellStep("echo marked as error");
            LogParserPublisher lpp = job.addPublisher(LogParserPublisher.class);
            lpp.setMarkOnBuildFail(true);
            lpp.setRule(resource("/logparser_plugin/rules/log-parser-rule-markings"));
        });
        job.startBuild().waitUntilFinished().shouldFail();

        job.configure(() -> {
            job.addShellStep("echo marked as warning");
            LogParserPublisher lpp = job.getPublisher(LogParserPublisher.class);
            lpp.setMarkOnBuildFail(false);
            lpp.setMarkOnUnstableWarning(true);
        });
        job.startBuild().waitUntilFinished().shouldBeUnstable();
    }

    /**
     * Adds a post-build-step to the job which prints out the content of the specified file.
     *
     * @param job The job where the post-build-step is added.
     * @param str The name of the file which shall be printed out.
     */
    private void catToConsole(final Job job, final String str) {
        job.addShellStep("cat " + str);
    }

    /**
     * Adds a new rule to the existing config.
     *
     * @param description The description of the new rule.
     * @param pathToFile  The path to the rule file.
     */
    private void addLogParserRule(final String description, final String pathToFile) {
        jenkins.configure();
        config.addParserConfig(description, pathToFile);
        jenkins.save();
    }

    /**
     * Adds a new rule to the existing config.
     *
     * @param description The description of the new rule.
     * @param resource    The {@link Resource} object of the rule file.
     */
    private void addLogParserRule(final String description, Resource resource) {
        addLogParserRule(description, resource.url.getPath());
    }

    /**
     * Adds serveral rules to the existing config.
     *
     * @param rules Map of the rules. Key is the description and Value is the path.
     */
    private void addLogParserRules(final Map<String, String> rules) {
        jenkins.configure();
        for (Map.Entry<String, String> rule : rules.entrySet()) {
            config.addParserConfig(rule.getKey(), rule.getValue());
        }
        jenkins.save();
    }

    private Job configureSampleJob() {
        FreeStyleJob job = jenkins.jobs.create(FreeStyleJob.class, "sampleJob");

        // configure job
        job.configure(() -> {
            LogParserPublisher lpp = job.addPublisher(LogParserPublisher.class);
            lpp.setRule(LogParserPublisher.RuleType.GLOBAL, parserRules.get("sampleRule"));

            // write sample output
            Resource sampleRule = resource("/logparser_plugin/console-outputs/sample-log");
            catToConsole(job, sampleRule.url.getPath());
        });
        return job;
    }
}
