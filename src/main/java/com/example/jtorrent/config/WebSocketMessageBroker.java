package com.example.jtorrent.config;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket configuration for real-time torrent updates.
 * <p>
 * This enables clients to connect via WebSocket and receive
 * automatic updates about torrent progress, speeds, and status.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketMessageBroker implements WebSocketMessageBrokerConfigurer {

    /**
     * Configure message broker.
     *
     * - enableSimpleBroker: Creates an in-memory message broker
     *   Messages sent to /topic will be broadcast to all subscribers
     *
     * - setApplicationDestinationPrefixes: Messages from clients to server
     *   will have /app prefix (we won't use this much, mainly server->client)
     */
    @Override
    public void configureMessageBroker(@NonNull MessageBrokerRegistry registry) {
        // Enable simple in-memory broker for /topic destinations
        registry.enableSimpleBroker("/topic");

        // Prefix for messages FROM client TO server (if needed)
        registry.setApplicationDestinationPrefixes("/app");
    }


    /**
     * Register STOMP endpoints.
     *
     * Clients will connect to: ws://localhost:8080/ws
     *
     * - withSockJS(): Fallback to polling if WebSocket not supported
     *   (older browsers)
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Register /ws endpoint
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")  // Allow connections from anywhere (for development)
                .withSockJS();                   // Enable SockJS fallback
    }
}
