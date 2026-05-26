package com.parking.api.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Slf4j
@Configuration
public class RedisConfig {

    @Value("${redis.host:localhost}")
    private String redisHost;

    @Value("${redis.port:6379}")
    private int redisPort;

    @Value("${redis.password:}")
    private String redisPassword;

    @Value("${redis.use-tls:false}")
    private boolean useTls;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();

        String scheme = useTls ? "rediss://" : "redis://";
        String address = scheme + redisHost + ":" + redisPort;

        log.info("Configuring Redisson: address={}, tls={}", address, useTls);

        config.useSingleServer()
                .setAddress(address)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(20)
                .setConnectTimeout(5000)
                .setRetryAttempts(3)
                .setRetryInterval(1500);

        // Only set password if non-empty
        if (StringUtils.hasText(redisPassword)) {
            config.useSingleServer().setPassword(redisPassword);
        }

        return Redisson.create(config);
    }
}
