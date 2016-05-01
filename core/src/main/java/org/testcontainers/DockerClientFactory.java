package org.testcontainers;

import com.spotify.docker.client.*;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import com.spotify.docker.client.messages.ProgressMessage;
import lombok.Synchronized;
import org.slf4j.Logger;
import org.testcontainers.dockerclient.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static java.util.Arrays.asList;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Singleton class that provides initialized Docker clients.
 * <p>
 * The correct client configuration to use will be determined on first use, and cached thereafter.
 */
public class DockerClientFactory {

    private static DockerClientFactory instance;
    private static final Logger LOGGER = getLogger(DockerClientFactory.class);

    // Cached client builder
    private DefaultDockerClient.Builder config;
    private boolean preconditionsChecked = false;

    private static final List<DockerConfigurationStrategy> CONFIGURATION_STRATEGIES =
            asList(new EnvironmentAndSystemPropertyConfigurationStrategy(),
                    new DockerMachineConfigurationStrategy(),
                    new UnixSocketConfigurationStrategy());

    /**
     * Private constructor
     */
    private DockerClientFactory() {

    }

    /**
     * Obtain an instance of the DockerClientFactory.
     *
     * @return the singleton instance of DockerClientFactory
     */
    public synchronized static DockerClientFactory instance() {
        if (instance == null) {
            instance = new DockerClientFactory();
        }

        return instance;
    }

    /**
     *
     * @return a new initialized Docker client
     */
    public DockerClient client() {
        return client(true);
    }

    /**
     *
     * @param failFast fail if client fails to ping Docker daemon
     * @return a new initialized Docker client
     */
    @Synchronized
    public DockerClient client(boolean failFast) {
        DockerClient client = null;
        try {
            if (config == null) {
                config = DockerConfigurationStrategy.getFirstValidConfig(CONFIGURATION_STRATEGIES);
            }

            client = config.build();

            if (!preconditionsChecked) {
                String version = client.version().version();
                checkVersion(version);
                checkDiskSpaceAndHandleExceptions(client);
                preconditionsChecked = true;
            }

            if (failFast) {
                // Ping, to fail fast if our docker environment has gone away
                client.ping();
            }
        } catch (DockerException | InterruptedException e) {
            throw new RuntimeException(e); // TODO
        }

        return client;
    }

    /**
     * @param config docker client configuration to extract the host IP address from
     * @return the IP address of the host running Docker
     */
    private String dockerHostIpAddress(DefaultDockerClient.Builder config) {
        return DockerClientConfigUtils.getDockerHostIpAddress(config);
    }

    /**
     * @return the IP address of the host running Docker
     */
    public String dockerHostIpAddress() {
        return dockerHostIpAddress(config);
    }

    private void checkVersion(String version) {
        String[] splitVersion = version.split("\\.");
        if (Integer.valueOf(splitVersion[0]) <= 1 && Integer.valueOf(splitVersion[1]) < 6) {
            throw new IllegalStateException("Docker version 1.6.0+ is required, but version " + version + " was found");
        }
    }

    private void checkDiskSpaceAndHandleExceptions(DockerClient client) {
        try {
            checkDiskSpace(client);
        } catch (NotEnoughDiskSpaceException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("Encountered and ignored error while checking disk space", e);
        }
    }

    /**
     * Check whether this docker installation is likely to have disk space problems
     * @param client an active Docker client
     */
    private void checkDiskSpace(DockerClient client) throws DockerException, InterruptedException {

        List<com.spotify.docker.client.messages.Image> images = client.listImages();
        if (!images.stream().anyMatch(it -> it.repoTags().contains("alpine:3.2"))) {
            final CountDownLatch latch = new CountDownLatch(1);
            client.pull("alpine:3.2", new ProgressHandler() {
                @Override
                public void progress(ProgressMessage message) throws DockerException {
                    latch.countDown();
                }
            });
            latch.await();
        }

        ContainerConfig containerConfig = ContainerConfig.builder()
                .image("alpine:3.2")
                .cmd("df", "-P")
                .build();
        ContainerCreation container = client.createContainer(containerConfig);
        String id = container.id();

        client.startContainer(id);
        client.waitContainer(id);

        LogStream logStream = client.logs(id, DockerClient.LogsParameter.STDOUT);

        try {
            String logResults = logStream.readFully();

            int availableKB = 0;
            int use = 0;
            String[] lines = logResults.split("\n");
            for (String line : lines) {
                String[] fields = line.split("\\s+");
                if (fields[5].equals("/")) {
                    availableKB = Integer.valueOf(fields[3]);
                    use = Integer.valueOf(fields[4].replace("%", ""));
                }
            }
            int availableMB = availableKB / 1024;

            LOGGER.info("Disk utilization in Docker environment is {}% ({} MB available)", use, availableMB);

            if (availableMB < 2048) {
                LOGGER.error("Docker environment has less than 2GB free - execution is unlikely to succeed so will be aborted.");
                throw new NotEnoughDiskSpaceException("Not enough disk space in Docker environment");
            }
        } finally {
            try {
                client.removeContainer(id, true);
            } catch (DockerException | InterruptedException ignored) {

            }
        }
    }

    private static class NotEnoughDiskSpaceException extends RuntimeException {
        NotEnoughDiskSpaceException(String message) {
            super(message);
        }
    }
}

