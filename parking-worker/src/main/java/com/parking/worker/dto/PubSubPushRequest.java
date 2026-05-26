package com.parking.worker.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Represents a Pub/Sub push subscription HTTP request body.
 * See: https://cloud.google.com/pubsub/docs/push#receive_push
 */
@Data
@NoArgsConstructor
public class PubSubPushRequest {

    private Message message;
    private String subscription;

    @Data
    @NoArgsConstructor
    public static class Message {

        /** Base64-encoded message payload. */
        @JsonProperty("data")
        private String data;

        @JsonProperty("messageId")
        private String messageId;

        @JsonProperty("attributes")
        private Map<String, String> attributes;
    }
}
