package org.testcontainers.utility;

import com.spotify.docker.client.messages.ContainerState;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * Utility functions for dealing with docker status based on the information available to us, and trying to be
 * defensive.
 * <p>
 * <p>In docker-java version 2.2.0, which we're using, only these
 * fields are available in the container state returned from Docker Inspect: "isRunning", "isPaused", "startedAt", and
 * "finishedAt". There are states that can occur (including "created", "OOMkilled" and "dead") that aren't directly
 * shown through this result.
 * <p>
 * <p>Docker also doesn't seem to use null values for timestamps; see DOCKER_TIMESTAMP_ZERO, below.
 */
public class DockerStatus {

    /**
     * When the docker client has an "empty" timestamp, it returns this special value, rather than
     * null or an empty string.
     */
    static final String DOCKER_TIMESTAMP_ZERO = "0001-01-01T00:00:00Z";

    /**
     * Based on this status, is this container running, and has it been doing so for the specified amount of time?
     *
     * @param state                  the state provided by InspectContainer
     * @param minimumRunningDuration minimum duration to consider this as "solidly" running, or null
     * @param now                    the time to consider as the current time
     * @return true if we can conclude that the container is running, false otherwise
     */
    public static boolean isContainerRunning(ContainerState state,
                                             Duration minimumRunningDuration,
                                             Instant now) {
        if (state.running()) {
            if (minimumRunningDuration == null) {
                return true;
            }
            Instant startedAt = DateTimeFormatter.ISO_INSTANT.parse(
                state.startedAt(), Instant::from);

            if (startedAt.isBefore(now.minus(minimumRunningDuration))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Based on this status, has the container halted?
     *
     * @param state the state provided by InspectContainer
     * @return true if we can conclude that the container has started but is now stopped, false otherwise.
     */
    public static boolean isContainerStopped(ContainerState state) {

        // get some preconditions out of the way
        if (state.running() || state.paused()) {
            return false;
        }

        // if the finished timestamp is non-empty, that means the container started and finished.
        if (!isDockerTimestampEmpty(state.startedAt()) && !isDockerTimestampEmpty(state.finishedAt())) {
            return true;
        }
        return false;
    }

    public static boolean isDockerTimestampEmpty(Date dockerTimestamp) {
        // This is a defensive approach. Current versions of Docker use the DOCKER_TIMESTAMP_ZERO value, but
        // that could change.
        return dockerTimestamp == null
            || dockerTimestamp.getTime() == 0;
    }

}
