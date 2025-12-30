package com.github.dimitryivaniuta.gateway.proxy.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Embeddable
public class ApiClientPolicyId implements Serializable {

    @Column(name = "client_key", nullable = false, length = 128)
    private String clientKey;

    @Column(name = "method_key", nullable = false, length = 1024)
    private String methodKey;
}
