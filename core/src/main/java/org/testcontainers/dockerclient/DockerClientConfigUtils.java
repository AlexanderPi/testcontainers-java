package org.testcontainers.dockerclient;

import com.spotify.docker.client.DefaultDockerClient;

public class DockerClientConfigUtils {
    public static String getDockerHostIpAddress(DefaultDockerClient.Builder config) {
        switch (config.uri().getScheme()) {
        case "http":
        case "https":
        case "tcp":
            return config.uri().getHost();
        case "unix":
            return "localhost";
        default:
            return null;
        }
    }
}
