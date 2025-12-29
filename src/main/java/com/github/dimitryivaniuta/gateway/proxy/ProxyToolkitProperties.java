package com.github.dimitryivaniuta.gateway.proxy;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "proxy-toolkit")
public class ProxyToolkitProperties {
    private boolean enabled = true;
    private int maxPayloadChars = 20_000;

    // don't wrap infrastructure / JDK
    private List<String> excludePackages = List.of(
            "org.springframework",
            "jakarta",
            "java",
            "kotlin",
            "com.zaxxer"
    );
}
