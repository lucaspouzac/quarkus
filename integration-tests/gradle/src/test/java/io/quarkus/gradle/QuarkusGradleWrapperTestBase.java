package io.quarkus.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;

public class QuarkusGradleWrapperTestBase extends QuarkusGradleTestBase {

    private static final String GRADLE_WRAPPER_WINDOWS = "gradlew.bat";
    private static final String GRADLE_WRAPPER_UNIX = "./gradlew";
    private static final String MAVEN_REPO_LOCAL = "maven.repo.local";

    private Map<String, String> systemProps;

    private static final List<Process> gradleProcesses = new ArrayList<>();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(QuarkusGradleWrapperTestBase::killProcesses));
    }

    protected void setupTestCommand() {

    }

    @AfterAll
    static void killProcesses() {
        gradleProcesses.forEach(Process::destroy);
        for (int i = 0; i < 100; i++) {
            if (gradleProcesses.stream().noneMatch(Process::isAlive)) {
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        gradleProcesses.forEach(Process::destroyForcibly);
    }

    public BuildResult runGradleWrapper(File projectDir, String... args) throws IOException, InterruptedException {
        setupTestCommand();
        List<String> systemProperties = getSytemProperties();
        List<String> command = new ArrayList<>(systemProperties.size() + args.length + 4);
        command.add(getGradleWrapperCommand());
        command.addAll(systemProperties);
        command.add("-Dorg.gradle.console=plain");
        command.add("-Dorg.gradle.daemon=false");
        command.add("--stacktrace");
        command.add("--info");
        command.addAll(Arrays.asList(args));

        File logOutput = new File(projectDir, "command-output.log");

        System.out.println("$ " + String.join(" ", command));
        Process p = new ProcessBuilder()
                .directory(projectDir)
                .command(command)
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(logOutput)
                .redirectOutput(logOutput)
                .start();

        gradleProcesses.add(p);

        //long timeout for native tests
        //that may also need to download docker
        boolean done = p.waitFor(10, TimeUnit.MINUTES);
        if (!done) {
            destroyProcess(p);
        }
        final BuildResult commandResult = BuildResult.of(logOutput);
        int exitCode = p.exitValue();
        if (exitCode != 0) {
            printCommandOutput(projectDir, command, commandResult, exitCode);
        }
        return commandResult;
    }

    protected void setSystemProperty(String name, String value) {
        if (systemProps == null) {
            systemProps = new HashMap<>();
        }
        systemProps.put(name, value);
    }

    private String getGradleWrapperCommand() {
        return Paths.get(getGradleWrapperName()).toAbsolutePath().toString();
    }

    private String getGradleWrapperName() {
        if (System.getProperty("os.name").toLowerCase(Locale.ENGLISH).indexOf("windows") >= 0) {
            return GRADLE_WRAPPER_WINDOWS;
        }
        return GRADLE_WRAPPER_UNIX;
    }

    private List<String> getSytemProperties() {
        List<String> args = null;
        if (systemProps != null) {
            args = new ArrayList<>(systemProps.size() + 1);
            for (Map.Entry<String, String> prop : systemProps.entrySet()) {
                args.add(toPropertyArg(prop.getKey(), prop.getValue()));
            }
        }
        final String mavenRepoLocal = System.getProperty(MAVEN_REPO_LOCAL);
        if (mavenRepoLocal != null) {
            final String arg = toPropertyArg(MAVEN_REPO_LOCAL, mavenRepoLocal);
            if (args == null) {
                args = Collections.singletonList(arg);
            } else {
                args.add(arg);
            }
        }
        return args == null ? Collections.emptyList() : args;
    }

    private static String toPropertyArg(String name, String value) {
        return new StringBuilder().append("-D").append(name).append("=").append(value).toString();
    }

    private void printCommandOutput(File projectDir, List<String> command, BuildResult commandResult, int exitCode) {
        System.err.println(
                "Command: " + String.join(" ", command) + " ran from: " + projectDir.getAbsolutePath()
                        + " failed with exit code: " + exitCode + " and the following output:");
        System.err.println(commandResult.getOutput());
    }

    /**
     * Try to destroy the process normally a few times
     * and resort to forceful destruction if necessary
     */
    private static void destroyProcess(Process wrapperProcess) {
        wrapperProcess.destroy();
        int i = 0;
        while (i++ < 10) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {

            }
            if (!wrapperProcess.isAlive()) {
                break;
            }
        }

        if (wrapperProcess.isAlive()) {
            wrapperProcess.destroyForcibly();
        }
    }
}
