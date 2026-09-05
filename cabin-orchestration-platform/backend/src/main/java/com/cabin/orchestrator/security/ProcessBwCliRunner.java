package com.cabin.orchestrator.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Real `bw` subprocess execution. Secrets (API client secret, master
 * password) are passed via extraEnv, never as a command-line argument --
 * argv is visible to any local process via /proc/{pid}/cmdline or `ps`,
 * environment variables are not (bar a sufficiently privileged
 * /proc/{pid}/environ read, which is the accepted, unavoidable floor for
 * "a subprocess needs this secret at all").
 */
@Component
class ProcessBwCliRunner implements BwCliRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessBwCliRunner.class);
    private static final long TIMEOUT_SECONDS = 30;

    @Override
    public CliResult run(List<String> args, Map<String, String> extraEnv, String stdin) {
        List<String> command = new ArrayList<>();
        command.add("bw");
        command.addAll(args);
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().putAll(extraEnv);
            Process process = builder.start();
            if (stdin != null) {
                try (var out = process.getOutputStream()) {
                    out.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                // Never echo args -- the first element after "bw" can legitimately
                // be a secret-bearing payload (e.g. an encoded item body).
                log.warn("bw {} timed out after {}s", args.isEmpty() ? "" : args.get(0), TIMEOUT_SECONDS);
                return new CliResult(-1, stdout, "timed out after " + TIMEOUT_SECONDS + "s");
            }
            return new CliResult(process.exitValue(), stdout, stderr);
        } catch (IOException e) {
            log.warn("bw {} failed to start: {}", args.isEmpty() ? "" : args.get(0), e.getMessage());
            return new CliResult(-1, "", "failed to start bw: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CliResult(-1, "", "interrupted");
        }
    }
}
