package com.processing.api.support;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class StackControl {
    private static final Set<String> SERVICES = Set.of("rabbitmq", "switch", "authorization", "card-management");

    private StackControl() {
    }

    public static void stop(String service) {
        execute(service, "stop", "-t", "1", service);
    }

    public static void start(String service) {
        execute(service, "up", "-d", "--no-deps", "--wait", "--wait-timeout", "120", service);
    }

    private static void execute(String service, String... arguments) {
        if (!SERVICES.contains(service) || !Boolean.getBoolean("api.manageStack")) {
            throw new IllegalStateException("Use scripts/run-api-tests.sh for isolated failure scenarios");
        }
        Path root = Path.of(System.getProperty("api.projectRoot", "../..")).toAbsolutePath().normalize();
        List<String> command = new ArrayList<>(List.of("docker", "compose", "--env-file", "/dev/null",
                "--project-name", "smp-api-tests", "-f", root.resolve("docker-compose.api-tests.yaml").toString()));
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(root.resolve(
                            "services/api-tests/target/compose-control.log").toFile())).start();
            if (!process.waitFor(150, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Compose operation timed out: " + service);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Compose operation failed: " + service);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Docker Compose is required", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Compose operation interrupted", exception);
        }
    }
}
