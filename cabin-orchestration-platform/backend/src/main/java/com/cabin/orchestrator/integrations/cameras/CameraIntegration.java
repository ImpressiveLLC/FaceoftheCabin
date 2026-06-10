package com.cabin.orchestrator.integrations.cameras;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CameraIntegration {
    public List<String> configuredStreams() {
        return List.of(
            "rtsp://camera-front-door/stream1",
            "rtsp://camera-driveway/stream1"
        );
    }
}
